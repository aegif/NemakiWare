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

import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.data.RenditionData;

/**
 * RenditionData implementation.
 */
public class RenditionDataImpl extends AbstractExtensionData implements RenditionData {

    private static final long serialVersionUID = 1L;

    private String streamId;
    private String mimeType;
    private BigInteger length;
    private String kind;
    private String title;
    private BigInteger width;
    private BigInteger height;
    private String renditionDocumentId;

    public RenditionDataImpl() {
    }

    public RenditionDataImpl(String streamId, String mimeType, BigInteger length, String kind, String title,
            BigInteger width, BigInteger height, String renditionDocumentId) {
        this.streamId = streamId;
        this.mimeType = mimeType;
        this.length = length;
        this.kind = kind;
        this.title = title;
        this.width = width;
        this.height = height;
        this.renditionDocumentId = renditionDocumentId;
    }

    @Override
    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    @Override
    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    @Override
    public BigInteger getBigLength() {
        return length;
    }

    public void setBigLength(BigInteger length) {
        this.length = length;
    }

    @Override
    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    @Override
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @Override
    public BigInteger getBigHeight() {
        return height;
    }

    public void setBigHeight(BigInteger height) {
        this.height = height;
    }

    @Override
    public BigInteger getBigWidth() {
        return width;
    }

    public void setBigWidth(BigInteger width) {
        this.width = width;
    }

    @Override
    public String getRenditionDocumentId() {
        return renditionDocumentId;
    }

    public void setRenditionDocumentId(String renditionDocumentId) {
        this.renditionDocumentId = renditionDocumentId;
    }

    @Override
    public String toString() {
        return "RenditionDataImpl [, kind=" + kind + ", title=" + title + ", MIME type=" + mimeType + ", length="
                + length + ", rendition document id=" + renditionDocumentId + ", stream id=" + streamId + " height="
                + height + ", width=" + width + "]" + super.toString();
    }

}
