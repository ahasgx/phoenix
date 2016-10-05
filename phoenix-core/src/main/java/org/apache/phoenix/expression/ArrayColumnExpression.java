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
package org.apache.phoenix.expression;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.phoenix.compile.CreateTableCompiler.ViewWhereExpressionVisitor;
import org.apache.phoenix.expression.visitor.ExpressionVisitor;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PDatum;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PArrayDataType;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PVarbinary;
import org.apache.phoenix.util.EncodedColumnsUtil;
import org.apache.phoenix.util.SchemaUtil;

/**
 * 
 * Class to access a column that is stored in a KeyValue that contains all
 * columns for a given column family (stored in an array).
 *
 */
public class ArrayColumnExpression extends KeyValueColumnExpression {
    
    private int encodedCQ;
    private String displayName;
    
    public ArrayColumnExpression() {
    }
    
    public ArrayColumnExpression(PDatum column, byte[] cf, int encodedCQ) {
        super(column, cf, cf);
        this.encodedCQ = encodedCQ;
    }
    
    public ArrayColumnExpression(PColumn column, String displayName, boolean encodedColumnName) {
        super(column, column.getFamilyName().getBytes(), column.getFamilyName().getBytes());
        this.displayName = SchemaUtil.getColumnDisplayName(column.getFamilyName().getString(), column.getName().getString());
        this.encodedCQ = column.getEncodedColumnQualifier();
    }

    @Override
    public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
    	if (!super.evaluate(tuple, ptr)) {
            return false;
        } else if (ptr.getLength() == 0) { 
        	return true; 
        }

        // Given a ptr to the entire array, set ptr to point to a particular element within that array
        // given the type of an array element (see comments in PDataTypeForArray)
    	PArrayDataType.positionAtArrayElement(ptr, encodedCQ, PVarbinary.INSTANCE, null);
        return true;
    }

    @Override
    public void readFields(DataInput input) throws IOException {
        super.readFields(input);
        encodedCQ = WritableUtils.readVInt(input);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        super.write(output);
        WritableUtils.writeVInt(output, encodedCQ);
    }
    
    public KeyValueColumnExpression getKeyValueExpression() {
    	final boolean isNullable = isNullable();
    	final SortOrder sortOrder = getSortOrder();
    	final Integer scale = getScale();
    	final Integer maxLength = getMaxLength();
    	final PDataType datatype = getDataType();
        return new KeyValueColumnExpression(new PDatum() {
			
			@Override
			public boolean isNullable() {
				return isNullable;
			}
			
			@Override
			public SortOrder getSortOrder() {
				return sortOrder;
			}
			
			@Override
			public Integer getScale() {
				return scale;
			}
			
			@Override
			public Integer getMaxLength() {
				return maxLength;
			}
			
			@Override
			public PDataType getDataType() {
				return datatype;
			}
		}, getColumnFamily(), getEncodedColumnQualifier());
    }
    
    @Override
    public String toString() {
        return displayName;
    }
    
    public byte[] getEncodedColumnQualifier() {
        return EncodedColumnsUtil.getEncodedColumnQualifier(encodedCQ);
    }
    
    @Override
    public <T> T accept(ExpressionVisitor<T> visitor) {
        //FIXME: this is ugly but can't think of a good solution.
        if (visitor instanceof ViewWhereExpressionVisitor) {
            return visitor.visit(this);
        } else {
            return super.accept(visitor);
        }
    }
}
