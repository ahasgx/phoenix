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

import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.StorageScheme;
import org.apache.phoenix.schema.types.PInteger;

public class EncodedColumnsUtil {

    public static boolean usesEncodedColumnNames(PTable table) {
        return usesEncodedColumnNames(table.getStorageScheme());
    }
    
    public static boolean usesEncodedColumnNames(StorageScheme storageScheme) {
        return storageScheme != null && storageScheme != StorageScheme.NON_ENCODED_COLUMN_NAMES;
    }

    public static byte[] getEncodedColumnQualifier(PColumn column) {
        checkArgument(!SchemaUtil.isPKColumn(column), "No column qualifiers for PK columns");
        checkArgument(!column.isDynamic(), "No encoded column qualifiers for dynamic columns");
        return Bytes.toBytes(column.getEncodedColumnQualifier());
    }
    
    public static int getEncodedColumnQualifier(byte[] bytes, int offset, int length) {
        return Bytes.toInt(bytes, offset, length);
    }
    
    public static byte[] getEncodedColumnQualifier(int value) {
        return Bytes.toBytes(value);
    }
    
    public static int getEncodedColumnQualifier(byte[] bytes) {
        return Bytes.toInt(bytes);
    }

    public static byte[] getColumnQualifier(PColumn column, PTable table) {
      return EncodedColumnsUtil.getColumnQualifier(column, usesEncodedColumnNames(table));
    }
    
    public static void setColumns(PColumn column, PTable table, Scan scan) {
    	if (table.getStorageScheme() == StorageScheme.COLUMNS_STORED_IN_SINGLE_CELL) {
            // if a table storage scheme is COLUMNS_STORED_IN_SINGLE_CELL set then all columns of a column family are stored in a single cell 
            // (with the qualifier name being same as the family name), just project the column family here
            // so that we can calculate estimatedByteSize correctly in ProjectionCompiler 
    		scan.addFamily(column.getFamilyName().getBytes());
    		//scan.addColumn(column.getFamilyName().getBytes(), column.getFamilyName().getBytes());
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
    
}
