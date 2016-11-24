/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by
 * applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.apache.phoenix.expression;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.phoenix.expression.visitor.ExpressionVisitor;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PArrayDataType;
import org.apache.phoenix.schema.types.PArrayDataType.PArrayDataTypeBytesArrayBuilder;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.TrustedByteArrayOutputStream;

/**
 * Creates an expression for Upsert with Values/Select using ARRAY
 */
public class ArrayConstructorExpression extends BaseCompoundExpression {
    private PDataType baseType;
    private int position = -1;
    private Object[] elements;
    private final ImmutableBytesWritable valuePtr = new ImmutableBytesWritable();
    private int estimatedSize = 0;
    // store the offset postion in this.  Later based on the total size move this to a byte[]
    // and serialize into byte stream
    private int[] offsetPos;
    private boolean rowKeyOrderOptimizable;
    private byte serializationVersion;
    
    public ArrayConstructorExpression() {
    }

    public ArrayConstructorExpression(List<Expression> children, PDataType baseType, boolean rowKeyOrderOptimizable) {
    	this(children, baseType, rowKeyOrderOptimizable, PArrayDataType.SORTABLE_SERIALIZATION_VERSION);
    }
    
    public ArrayConstructorExpression(List<Expression> children, PDataType baseType, boolean rowKeyOrderOptimizable, byte serializationVersion) {
        super(children);
        init(baseType, rowKeyOrderOptimizable, serializationVersion);
    }

    public ArrayConstructorExpression clone(List<Expression> children) {
        return new ArrayConstructorExpression(children, this.baseType, this.rowKeyOrderOptimizable, PArrayDataType.SORTABLE_SERIALIZATION_VERSION);
    }
    
    private void init(PDataType baseType, boolean rowKeyOrderOptimizable, byte serializationVersion) {
        this.baseType = baseType;
        this.rowKeyOrderOptimizable = rowKeyOrderOptimizable;
        elements = new Object[getChildren().size()];
        valuePtr.set(ByteUtil.EMPTY_BYTE_ARRAY);
        estimatedSize = PArrayDataType.estimateSize(this.children.size(), this.baseType);
        if (!this.baseType.isFixedWidth()) {
            offsetPos = new int[children.size()];
        }
        this.serializationVersion = serializationVersion;
    }

    @Override
    public PDataType getDataType() {
        return PDataType.fromTypeId(baseType.getSqlType() + PDataType.ARRAY_TYPE_BASE);
    }

    @Override
    public void reset() {
        super.reset();
        position = 0;
        Arrays.fill(elements, null);
        valuePtr.set(ByteUtil.EMPTY_BYTE_ARRAY);
    }

    @Override
    public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
        if (position == elements.length) {
            ptr.set(valuePtr.get(), valuePtr.getOffset(), valuePtr.getLength());
            return true;
        }
        TrustedByteArrayOutputStream byteStream = new TrustedByteArrayOutputStream(estimatedSize);
        DataOutputStream oStream = new DataOutputStream(byteStream);
        PArrayDataTypeBytesArrayBuilder builder =
                new PArrayDataTypeBytesArrayBuilder(byteStream, oStream, children.size(), baseType, getSortOrder(), rowKeyOrderOptimizable, serializationVersion);
        try {
            for (int i = position >= 0 ? position : 0; i < elements.length; i++) {
                Expression child = children.get(i);
                if (!child.evaluate(tuple, ptr)) {
                    if (tuple != null && !tuple.isImmutable()) {
                        if (position >= 0) position = i;
                        return false;
                    }
                    else {
                        // its possible for the expression to evaluate to null if the serialization format is immutable and the data type is variable length
                        builder.appendMissingElement();
                    }
                } else {
                    builder.appendElem(ptr.get(), ptr.getOffset(), ptr.getLength());
                }
            }
            if (position >= 0) position = elements.length;
            byte[] bytes = builder.getBytesAndClose();
            ptr.set(bytes, 0, bytes.length);
            valuePtr.set(ptr.get(), ptr.getOffset(), ptr.getLength());
            return true;
        } finally {
            try {
                byteStream.close();
                oStream.close();
            } catch (IOException e) {
                // Should not happen
            }
        }
    }


    @Override
    public void readFields(DataInput input) throws IOException {
        super.readFields(input);
        boolean rowKeyOrderOptimizable = false;
        int baseTypeOrdinal = WritableUtils.readVInt(input);
        if (baseTypeOrdinal < 0) {
            rowKeyOrderOptimizable = true;
            baseTypeOrdinal = -(baseTypeOrdinal+1);
        }
        byte serializationVersion = input.readByte();
        init(PDataType.values()[baseTypeOrdinal], rowKeyOrderOptimizable, serializationVersion);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        super.write(output);
        if (rowKeyOrderOptimizable) {
            WritableUtils.writeVInt(output, -(baseType.ordinal()+1));
        } else {
            WritableUtils.writeVInt(output, baseType.ordinal());
        }
        output.write(serializationVersion);
    }
    
    @Override
    public boolean requiresFinalEvaluation() {
        return true;
    }

    @Override
    public final <T> T accept(ExpressionVisitor<T> visitor) {
        List<T> l = acceptChildren(visitor, visitor.visitEnter(this));
        T t = visitor.visitLeave(this, l);
        if (t == null) {
            t = visitor.defaultReturn(this, l);
        }
        return t;
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(PArrayDataType.ARRAY_TYPE_SUFFIX + "[");
        if (children.size()==0)
            return buf.append("]").toString();
        for (int i = 0; i < children.size() - 1; i++) {
            buf.append(children.get(i) + ",");
        }
        buf.append(children.get(children.size()-1) + "]");
        return buf.toString();
    }

}