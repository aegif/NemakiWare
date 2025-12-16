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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.data.MutableContentStream;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

/**
 * Content stream data implementation.
 */
public class ContentStreamImpl extends AbstractExtensionData implements MutableContentStream {

    private static final long serialVersionUID = 1L;

    private String filename;
    private BigInteger length;
    private String mimetype;
    private transient InputStream stream;

    /**
     * Constructor.
     */
    public ContentStreamImpl() {
    }

    /**
     * Constructor.
     */
    public ContentStreamImpl(String filename, BigInteger length, String mimetype, InputStream stream) {
        this.filename = filename;
        this.length = length;
        this.mimetype = mimetype;
        this.stream = stream;
    }

    /**
     * Convenience constructor for tests.
     */
    public ContentStreamImpl(String filename, String mimetype, String string) {
        if (string == null) {
            throw new IllegalArgumentException("String must be set!");
        }

        byte[] bytes = null;
        try {
            bytes = string.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new CmisRuntimeException("Unsupported encoding 'UTF-8'!", e);
        }

        this.filename = filename;
        this.length = BigInteger.valueOf(bytes.length);
        this.mimetype = mimetype;
        this.stream = new ByteArrayInputStream(bytes);
    }

    @Override
    public String getFileName() {
        return filename;
    }

    @Override
    public void setFileName(String filename) {
        this.filename = filename;
    }

    @Override
    public long getLength() {
        return length == null ? -1 : length.longValue();
    }

    @Override
    public BigInteger getBigLength() {
        return length;
    }

    @Override
    public void setLength(BigInteger length) {
        this.length = length;
    }

    @Override
    public String getMimeType() {
        return mimetype;
    }

    @Override
    public void setMimeType(String mimeType) {
        this.mimetype = mimeType;
    }

    @Override
    public InputStream getStream() {
        return stream;
    }

    @Override
    public void setStream(InputStream stream) {
        this.stream = stream;
    }

    @Override
    public String toString() {
        return "ContentStream [filename=" + filename + ", length=" + length + ", MIME type=" + mimetype
                + ", has stream=" + (stream != null) + "]" + super.toString();
    }
}
