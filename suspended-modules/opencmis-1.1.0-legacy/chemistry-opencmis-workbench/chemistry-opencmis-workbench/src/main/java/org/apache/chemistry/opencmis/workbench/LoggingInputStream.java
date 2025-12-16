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
package org.apache.chemistry.opencmis.workbench;

import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingInputStream extends InputStream {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingInputStream.class);

    private final InputStream stream;
    private final String desc;
    private long marked;
    private long count = 0;
    private long startTimestamp = -1;
    private long endTimestamp = -1;

    public LoggingInputStream(InputStream stream, String desc) {
        super();
        this.stream = stream;
        this.desc = desc;
    }

    @Override
    public int available() throws IOException {
        return stream.available();
    }

    @Override
    public synchronized void mark(int readlimit) {
        stream.mark(readlimit);
        marked = count;

        if (LOG.isTraceEnabled()) {
            LOG.trace("{}: mark", desc);
        }
    }

    @Override
    public boolean markSupported() {
        return stream.markSupported();
    }

    @Override
    public synchronized void reset() throws IOException {
        stream.reset();
        count = marked;

        if (LOG.isTraceEnabled()) {
            LOG.trace("{}: reset", desc);
        }
    }

    protected void count(long len) {
        if (startTimestamp < 0) {
            startTimestamp = System.currentTimeMillis();
            LOG.info("{}: started streaming", desc);
        }
        if (len == -1 && endTimestamp == -1) {
            endTimestamp = System.currentTimeMillis();
            long time = (endTimestamp - startTimestamp);
            long kibPerSec = (time < 1 ? -1L : (long) (((double) count / 1024) / ((double) time / 1000)));

            NumberFormat nf = NumberFormat.getInstance();
            LOG.info("{}: streamed {} bytes in {} ms, {} KiB/sec", desc, nf.format(count), nf.format(time),
                    nf.format(kibPerSec));
        } else {
            count += len;
        }
    }

    @Override
    public int read() throws IOException {
        int b = stream.read();
        count(b == -1 ? -1 : 1);

        if (b != -1 && LOG.isTraceEnabled()) {
            LOG.trace("{}: read {} bytes", desc, count);
        }

        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int rb = stream.read(b, off, len);
        count(rb);

        if (rb != -1 && LOG.isTraceEnabled()) {
            LOG.trace("{}: read {} bytes", desc, count);
        }

        return rb;
    }

    @Override
    public int read(byte[] b) throws IOException {
        int rb = stream.read(b);
        count(rb);

        if (rb != -1 && LOG.isTraceEnabled()) {
            LOG.trace("{}: read {} bytes", desc, count);
        }

        return rb;
    }

    @Override
    public long skip(long n) throws IOException {
        long len = super.skip(n);
        count(len);

        if (len > 0 && LOG.isTraceEnabled()) {
            LOG.trace("{}: read {} bytes", desc, count);
        }

        return len;
    }

    @Override
    public void close() throws IOException {
        super.close();

        if (LOG.isDebugEnabled()) {
            LOG.info("{}: stream closed", desc);
        }
    }
}
