/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.schema.stats;

import static org.apache.phoenix.query.QueryServices.COMMIT_STATS_ASYNC;
import static org.apache.phoenix.query.QueryServicesOptions.DEFAULT_COMMIT_STATS_ASYNC;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;


/**
 * The scanner that does the scanning to collect the stats during major compaction.{@link StatisticsCollector}
 */
public class StatisticsScanner implements InternalScanner {
    private static final Log LOG = LogFactory.getLog(StatisticsScanner.class);
    private InternalScanner delegate;
    private StatisticsWriter stats;
    private HRegion region;
    private StatisticsCollector tracker;
    private ImmutableBytesPtr family;
    private Pair<HRegionInfo, HRegionInfo> mergeRegions;
    private final Configuration config;

    public StatisticsScanner(StatisticsCollector tracker, StatisticsWriter stats, RegionCoprocessorEnvironment env,
            InternalScanner delegate, ImmutableBytesPtr family, Pair<HRegionInfo, HRegionInfo> mergeRegions) {
        this.tracker = tracker;
        this.stats = stats;
        this.delegate = delegate;
        this.region = env.getRegion();
        this.config = env.getConfiguration();
        this.family = family;
        this.mergeRegions = mergeRegions;
        StatisticsCollectionRunTracker.getInstance(config).addCompactingRegion(region.getRegionInfo());
    }

    @Override
    public boolean next(List<Cell> result) throws IOException {
        boolean ret = delegate.next(result);
        updateStat(result);
        return ret;
    }

    @Override
    public boolean next(List<Cell> result, int limit) throws IOException {
        boolean ret = delegate.next(result, limit);
        updateStat(result);
        return ret;
    }

    /**
     * Update the current statistics based on the lastest batch of key-values from the underlying scanner
     * 
     * @param results
     *            next batch of {@link KeyValue}s
     */
    protected void updateStat(final List<Cell> results) {
        if (!results.isEmpty()) {
            tracker.collectStatistics(results);
        }
    }
    
    private class StatisticsScannerCallable implements Callable<Void> {
        @Override
        public Void call() throws IOException {
            IOException toThrow = null;
            StatisticsCollectionRunTracker statsRunState =
                    StatisticsCollectionRunTracker.getInstance(config);
            try {
                // update the statistics table
                // Just verify if this if fine
                ArrayList<Mutation> mutations = new ArrayList<Mutation>();
                if (mergeRegions != null) {
                    if (mergeRegions.getFirst() != null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Deleting stale stats for the region "
                                    + mergeRegions.getFirst().getRegionNameAsString() + " as part of major compaction");
                        }
                        stats.deleteStats(mergeRegions.getFirst().getRegionName(), tracker, family, mutations);
                    }
                    if (mergeRegions.getSecond() != null) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Deleting stale stats for the region "
                                    + mergeRegions.getSecond().getRegionNameAsString() + " as part of major compaction");
                        }
                        stats.deleteStats(mergeRegions.getSecond().getRegionName(), tracker, family, mutations);
                    }
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Deleting the stats for the region " + region.getRegionNameAsString()
                        + " as part of major compaction");
                }
                stats.deleteStats(region.getRegionName(), tracker, family, mutations);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Adding new stats for the region " + region.getRegionNameAsString()
                        + " as part of major compaction");
                }
                stats.addStats(region.getRegionName(), tracker, family, mutations);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Committing new stats for the region " + region.getRegionNameAsString()
                        + " as part of major compaction");
                }
                stats.commitStats(mutations);
            } catch (IOException e) {
                LOG.error("Failed to update statistics table!", e);
                toThrow = e;
            } finally {
                try {
                    statsRunState.removeCompactingRegion(region.getRegionInfo());
                    stats.close();
                } catch (IOException e) {
                    if (toThrow == null) toThrow = e;
                    LOG.error("Error while closing the stats table", e);
                } finally {
                    // close the delegate scanner
                    try {
                        delegate.close();
                    } catch (IOException e) {
                        if (toThrow == null) toThrow = e;
                        LOG.error("Error while closing the scanner", e);
                    } finally {
                        if (toThrow != null) { throw toThrow; }
                    }
                }
            }
            return null;
        }
    }
    
        @Override
    public void close() throws IOException {
        boolean async = config.getBoolean(COMMIT_STATS_ASYNC, DEFAULT_COMMIT_STATS_ASYNC);
        StatisticsScannerCallable callable = new StatisticsScannerCallable();
        if (!async) {
            callable.call();
        } else {
            StatisticsCollectionRunTracker.getInstance(config).runTask(callable);
        }
    }
}