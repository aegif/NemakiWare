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
package org.apache.chemistry.opencmis.server.shared;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;

/**
 * A stream that counts bytes and throws an exception if the given maximum is
 * reached. Counted bytes can be deducted to excludes parts of stream from the
 * length limitation.
 */
public class CappedInputStream extends InputStream {

    private InputStream stream;
    private long max;
    private long counter;

    public CappedInputStream(InputStream stream, long max) {
        this.stream = stream;
        this.max = max;
        this.counter = 0;
    }

    /**
     * Returns the counter.
     */
    public long getCounter() {
        return counter;
    }

    /**
     * Deducts the byte counter.
     */
    public void deductBytes(int byteCount) {
        counter -= byteCount;
    }

    /**
     * Deducts the byte counter.
     */
    public void deductString(String s, String encoding) throws UnsupportedEncodingException {
        if (encoding == null) {
            counter -= s.getBytes("UTF-8").length;
        } else {
            counter -= s.getBytes(encoding).length;
        }
    }

    private void checkLength() throws IOException {
        if (counter > max) {
            throw new CmisInvalidArgumentException("Limit exceeded!");
        }
    }

    @Override
    public int available() throws IOException {
        return stream.available();
    }

    @Override
    public synchronized void mark(int readlimit) {
    }

    @Override
    public synchronized void reset() throws IOException {
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public int read() throws IOException {
        checkLength();

        int b = stream.read();
        if (b > -1) {
            counter++;
        }

        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkLength();

        int l = stream.read(b, off, len);
        counter += l;

        return l;
    }

    @Override
    public int read(byte[] b) throws IOException {
        checkLength();

        int l = stream.read(b);
        counter += l;

        return l;
    }

    @Override
    public long skip(long n) throws IOException {
        checkLength();

        long l = stream.skip(n);
        counter += l;

        return l;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
