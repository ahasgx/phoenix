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
package org.apache.phoenix.util;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.phoenix.schema.PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS;

import java.util.Map;

import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.coprocessor.BaseScannerRegionObserver;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.QualifierEncodingScheme;
import org.apache.phoenix.schema.PTable.StorageScheme;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

public class EncodedColumnsUtil {

    public static boolean usesEncodedColumnNames(PTable table) {
        return usesEncodedColumnNames(table.getEncodingScheme());
    }
    
    public static boolean usesEncodedColumnNames(QualifierEncodingScheme encodingScheme) {
        return encodingScheme != null && encodingScheme != QualifierEncodingScheme.NON_ENCODED_QUALIFIERS;
    }

    public static byte[] getEncodedColumnQualifier(PColumn column) {
        checkArgument(!SchemaUtil.isPKColumn(column), "No column qualifiers for PK columns");
        checkArgument(!column.isDynamic(), "No encoded column qualifiers for dynamic columns");
        //TODO: samarth this would need to use encoding scheme.
        return Bytes.toBytes(column.getEncodedColumnQualifier());
    }
    
    public static int getEncodedColumnQualifier(byte[] bytes, int offset, int length) {
      //TODO: samarth this would need to use encoding scheme.
        return Bytes.toInt(bytes, offset, length);
    }
    
    public static byte[] getEncodedColumnQualifier(int value) {
      //TODO: samarth this would need to use encoding scheme.
        return Bytes.toBytes(value);
    }
    
    public static int getEncodedColumnQualifier(byte[] bytes) {
      //TODO: samarth this would need to use encoding scheme.
        return Bytes.toInt(bytes);
    }

    public static byte[] getColumnQualifier(PColumn column, PTable table) {
      return EncodedColumnsUtil.getColumnQualifier(column, usesEncodedColumnNames(table));
    }
    
    public static void setColumns(PColumn column, PTable table, Scan scan) {
    	if (table.getStorageScheme() == StorageScheme.ONE_CELL_PER_COLUMN_FAMILY) {
            // if a table storage scheme is COLUMNS_STORED_IN_SINGLE_CELL set then all columns of a column family are stored in a single cell 
            // (with the qualifier name being same as the family name), just project the column family here
            // so that we can calculate estimatedByteSize correctly in ProjectionCompiler 
    		scan.addFamily(column.getFamilyName().getBytes());
    	}
        else {
        	scan.addColumn(column.getFamilyName().getBytes(), EncodedColumnsUtil.getColumnQualifier(column, table));
        }
    }
    
    public static byte[] getColumnQualifier(PColumn column, boolean encodedColumnName) {
        checkArgument(!SchemaUtil.isPKColumn(column), "No column qualifiers for PK columns");
        if (column.isDynamic()) { // Dynamic column names don't have encoded column names
            return column.getName().getBytes();
        }
        return encodedColumnName ? getEncodedColumnQualifier(column) : column.getName().getBytes(); 
    }

    /**
     * @return pair of byte arrays. The first part of the pair is the empty key value's column qualifier, and the second
     *         part is the value to use for it.
     */
    public static Pair<byte[], byte[]> getEmptyKeyValueInfo(PTable table) {
        return usesEncodedColumnNames(table) ? new Pair<>(QueryConstants.ENCODED_EMPTY_COLUMN_BYTES,
                QueryConstants.ENCODED_EMPTY_COLUMN_VALUE_BYTES) : new Pair<>(QueryConstants.EMPTY_COLUMN_BYTES,
                QueryConstants.EMPTY_COLUMN_VALUE_BYTES);
    }

    /**
     * @return pair of byte arrays. The first part of the pair is the empty key value's column qualifier, and the second
     *         part is the value to use for it.
     */
    public static Pair<byte[], byte[]> getEmptyKeyValueInfo(boolean usesEncodedColumnNames) {
        return usesEncodedColumnNames ? new Pair<>(QueryConstants.ENCODED_EMPTY_COLUMN_BYTES,
                QueryConstants.ENCODED_EMPTY_COLUMN_VALUE_BYTES) : new Pair<>(QueryConstants.EMPTY_COLUMN_BYTES,
                QueryConstants.EMPTY_COLUMN_VALUE_BYTES);
    }

    public static boolean hasEncodedColumnName(PColumn column){
        return !SchemaUtil.isPKColumn(column) && !column.isDynamic() && column.getEncodedColumnQualifier() != null;
    }

    public static Pair<Integer, Integer> getMinMaxQualifiersFromScan(Scan scan) {
        Integer minQ = null, maxQ = null;
        byte[] minQualifier = scan.getAttribute(BaseScannerRegionObserver.MIN_QUALIFIER);
        if (minQualifier != null) {
            minQ = getEncodedColumnQualifier(minQualifier);
        }
        byte[] maxQualifier = scan.getAttribute(BaseScannerRegionObserver.MAX_QUALIFIER);
        if (maxQualifier != null) {
            maxQ = getEncodedColumnQualifier(maxQualifier);
        }
        if (minQualifier == null) {
            return null;
        }
        return new Pair<>(minQ, maxQ);
    }

    public static boolean setQualifierRanges(PTable table) {
        return table.getStorageScheme() != null
                && table.getStorageScheme() == StorageScheme.ONE_CELL_PER_KEYVALUE_COLUMN
                && usesEncodedColumnNames(table) && !table.isTransactional()
                && !ScanUtil.hasDynamicColumns(table);
    }

    public static boolean useQualifierAsIndex(Pair<Integer, Integer> minMaxQualifiers) {
        return minMaxQualifiers != null;
    }

    public static Map<String, Pair<Integer, Integer>> getQualifierRanges(PTable table) {
        Preconditions.checkArgument(table.getEncodingScheme() != NON_ENCODED_QUALIFIERS,
            "Use this method only for tables with encoding scheme "
                    + NON_ENCODED_QUALIFIERS);
        Map<String, Pair<Integer, Integer>> toReturn = Maps.newHashMapWithExpectedSize(table.getColumns().size());
        for (PColumn column : table.getColumns()) {
            if (!SchemaUtil.isPKColumn(column)) {
                String colFamily = column.getFamilyName().getString();
                Pair<Integer, Integer> minMaxQualifiers = toReturn.get(colFamily);
                Integer encodedColumnQualifier = column.getEncodedColumnQualifier();
                if (minMaxQualifiers == null) {
                    minMaxQualifiers = new Pair<>(encodedColumnQualifier, encodedColumnQualifier);
                    toReturn.put(colFamily, minMaxQualifiers);
                } else {
                    if (encodedColumnQualifier < minMaxQualifiers.getFirst()) {
                        minMaxQualifiers.setFirst(encodedColumnQualifier);
                    } else if (encodedColumnQualifier > minMaxQualifiers.getSecond()) {
                        minMaxQualifiers.setSecond(encodedColumnQualifier);
                    }
                }
            }
        }
        return toReturn;
    }
    
}
