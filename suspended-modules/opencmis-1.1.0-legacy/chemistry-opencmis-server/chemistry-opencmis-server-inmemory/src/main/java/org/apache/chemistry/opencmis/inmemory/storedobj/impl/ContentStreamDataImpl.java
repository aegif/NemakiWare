package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.LastModifiedContentStream;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContentStreamDataImpl implements LastModifiedContentStream {

    private static final int SIZE_KB = 1024;

    private static final int BUFFER_SIZE = 0xFFFF;

    private static final Logger LOG = LoggerFactory.getLogger(ContentStreamDataImpl.class.getName());

    private static long totalLength = 0L;
    private static long totalCalls = 0L;

    private long fLength;

    private String fMimeType;

    private String fFileName;

    private byte[] fContent;

    private GregorianCalendar fLastModified;

    private long fStreamLimitOffset;

    private long fStreamLimitLength;

    private final long sizeLimitKB;
    
    private final boolean doNotStoreContent;

    private static synchronized long getTotalLength() {
        return totalLength;
    }

    private static synchronized void increaseTotalLength(long length) {
        totalLength += length;
    }

    private static synchronized void decreaseTotalLength(long length) {
        totalLength -= length;
    }

    private static synchronized long getTotalCalls() {
        return totalCalls;
    }

    private static synchronized void increaseTotalCalls() {
        totalCalls++;
    }

    public ContentStreamDataImpl(long maxAllowedContentSizeKB) {
        sizeLimitKB = maxAllowedContentSizeKB;
        fLength = 0;
        doNotStoreContent = false;
    }

    public ContentStreamDataImpl(long maxAllowedContentSizeKB, boolean doNotStore) {
        sizeLimitKB = maxAllowedContentSizeKB;
        fLength = 0;
        doNotStoreContent = doNotStore;
    }

    public void setContent(InputStream in) throws IOException {
        fStreamLimitOffset = -1;
        fStreamLimitLength = -1;
        if (null == in) {
            fContent = null; // delete content
            fLength = 0;
        } else {
            byte[] buffer = new byte[BUFFER_SIZE];
            ByteArrayOutputStream contentStream = new ByteArrayOutputStream();
            int len = in.read(buffer);
            while (len != -1) {
                if (!doNotStoreContent) {
                    contentStream.write(buffer, 0, len);
                }
                fLength += len;
                if (sizeLimitKB > 0 && fLength > sizeLimitKB * SIZE_KB) {
                    throw new CmisInvalidArgumentException("Content size exceeds max. allowed size of " + sizeLimitKB
                            + "KB.");
                }
                len = in.read(buffer);
            }
            if (!doNotStoreContent) {
                fContent = contentStream.toByteArray();
                fLength = contentStream.size();
            }
            contentStream.close();
            in.close();
        }
        increaseTotalLength(fLength);
        increaseTotalCalls();
        LOG.debug("setting content stream, total no calls " + getTotalCalls() + ".");
        LOG.debug("setting content stream, new size total " + (getTotalLength() / (SIZE_KB * SIZE_KB)) + "MB.");
    }

    public void appendContent(InputStream is) throws IOException {

        if (null == is) {
            return; // nothing to do
        } else {
            byte[] buffer = new byte[BUFFER_SIZE];
            ByteArrayOutputStream contentStream = new ByteArrayOutputStream();

            // first read existing stream
            if (!doNotStoreContent) {
                contentStream.write(fContent);
            }
            decreaseTotalLength(fLength);

            // then append new content
            int len = is.read(buffer);
            while (len != -1) {
                contentStream.write(buffer, 0, len);
                fLength += len;
                if (sizeLimitKB > 0 && fLength > sizeLimitKB * SIZE_KB) {
                    throw new CmisInvalidArgumentException("Content size exceeds max. allowed size of " + sizeLimitKB
                            + "KB.");
                }
                len = is.read(buffer);
            }
            if (!doNotStoreContent) {
                fContent = contentStream.toByteArray();
            }
            fLength = contentStream.size();
            contentStream.close();
            is.close();
        }
        increaseTotalLength(fLength);
        increaseTotalCalls();
        LOG.debug("setting content stream, total no calls " + getTotalCalls() + ".");
        LOG.debug("setting content stream, new size total " + (getTotalLength() / (SIZE_KB * SIZE_KB)) + "MB.");
    }

    @Override
    public long getLength() {
        return fLength;
    }

    @Override
    public BigInteger getBigLength() {
        return BigInteger.valueOf(fLength);
    }

    @Override
    public String getMimeType() {
        return fMimeType;
    }

    public void setMimeType(String mimeType) {
        this.fMimeType = mimeType;
    }

    @Override
    public String getFileName() {
        return fFileName;
    }

    public void setFileName(String fileName) {
        this.fFileName = fileName;
    }

    public String getFilename() {
        return fFileName;
    }

    @Override
    public InputStream getStream() {
        if (doNotStoreContent) {
            return new RandomInputStream(fLength);
        }
        
        if (null == fContent) {
            return null;
        } else if (fStreamLimitOffset <= 0 && fStreamLimitLength < 0) {
                return new ByteArrayInputStream(fContent);
        } else {            
            return new ByteArrayInputStream(fContent, (int) (fStreamLimitOffset < 0 ? 0 : fStreamLimitOffset),
                    (int) (fStreamLimitLength < 0 ? fLength : fStreamLimitLength));
        }
    }

    public void setLastModified(GregorianCalendar lastModified) {
        this.fLastModified = lastModified;
    }

    @Override
    public GregorianCalendar getLastModified() {
        return fLastModified;
    }

    public ContentStream getCloneWithLimits(long offset, long length) {
        ContentStreamDataImpl clone = new ContentStreamDataImpl(0, doNotStoreContent);
        clone.fFileName = fFileName;
        clone.fLength = length < 0 ? fLength - offset : Math.min(fLength - offset, length);
        clone.fContent = fContent;
        clone.fMimeType = fMimeType;
        clone.fStreamLimitOffset = offset;
        clone.fStreamLimitLength = clone.fLength;
        clone.fLastModified = fLastModified;
        return clone;
    }

    public final byte[] getBytes() {
        return fContent;
    }

    @Override
    public List<CmisExtensionElement> getExtensions() {
        return null;
    }

    @Override
    public void setExtensions(List<CmisExtensionElement> extensions) {
        // not implemented
    }
}
