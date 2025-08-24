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
package org.apache.chemistry.opencmis.fileshare;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

public class ContentRangeInputStream extends FilterInputStream {

    private static final int BUFFER_SIZE = 4096;

    private long offset;
    private long length;
    private long remaining;

    public ContentRangeInputStream(InputStream stream, BigInteger offset, BigInteger length) {
        super(stream);

        this.offset = offset != null ? offset.longValue() : 0;
        this.length = length != null ? length.longValue() : Long.MAX_VALUE;

        this.remaining = this.length;

        if (this.offset > 0) {
            skipBytes();
        }
    }

    private void skipBytes() {
        long remainingSkipBytes = offset;

        try {
            while (remainingSkipBytes > 0) {
                long skipped = super.skip(remainingSkipBytes);
                remainingSkipBytes -= skipped;

                if (skipped == 0) {
                    // stream might not support skipping
                    skipBytesByReading(remainingSkipBytes);
                    break;
                }
            }
        } catch (IOException e) {
            throw new CmisRuntimeException("Skipping the stream failed!", e);
        }
    }

    private void skipBytesByReading(long remainingSkipBytes) {
        try {
            final byte[] buffer = new byte[BUFFER_SIZE];
            while (remainingSkipBytes > 0) {
                long skipped = super.read(buffer, 0, (int) Math.min(buffer.length, remainingSkipBytes));
                if (skipped == -1) {
                    break;
                }

                remainingSkipBytes -= skipped;
            }
        } catch (IOException e) {
            throw new CmisRuntimeException("Reading the stream failed!", e);
        }
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public long skip(long n) throws IOException {
        if (remaining <= 0) {
            return 0;
        }

        long skipped = super.skip(n);
        remaining -= skipped;

        return skipped;
    }

    @Override
    public int available() throws IOException {
        if (remaining <= 0) {
            return 0;
        }

        int avail = super.available();

        if (remaining < avail) {
            return (int) remaining;
        }

        return avail;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }

        remaining--;

        return super.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }

        int readBytes = super.read(b, off, (int) Math.min(len, remaining));
        if (readBytes == -1) {
            return -1;
        }

        remaining -= readBytes;

        return readBytes;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }
}
