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
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

public class RandomInputStream extends InputStream {

    private Random random = new Random();
    private boolean isClosed = false;
    private long size;
    private long bytesRead;

    public RandomInputStream(long size) {
        this.size = size;
    }
    
    @Override
    public int read() throws IOException {
        checkOpen();
        long len = checkLimit(1);
        if (len > 0) {
            int result = random.nextInt() % 256;
            if (result < 0) {
                result = -result;
            }
            bytesRead++;
            return result;
        } else {
            return -1;
        }
    }

    @Override
    public int read(byte[] data, int offset, int length) throws IOException {
        checkOpen();
        int sizeToRead = (int)checkLimit(length);
        if (0 == sizeToRead) {
            return -1;
        }

        byte[] temp = new byte[sizeToRead];
        random.nextBytes(temp);
        System.arraycopy(temp, 0, data, offset, sizeToRead);
        bytesRead += length;
        return length;
    }

    @Override
    public int read(byte[] data) throws IOException {
        checkOpen();
        long len = checkLimit(data.length);
        if (0 == len) {
            return -1;
        }
        random.nextBytes(data);
        bytesRead += len;
        return (int) len;
    }

    @Override
    public long skip(long bytesToSkip) throws IOException {
        checkOpen();
        long skip = checkLimit(bytesToSkip);
        bytesRead += skip;
        return skip;
    }

    @Override
    public void close() {
        this.isClosed = true;
    }

    @Override
    public int available() {
        return (int)(size - bytesRead);
    }

    private void checkOpen() throws IOException {
        if (isClosed) {
            throw new IOException("RandomInputStream was already closed.");
        }
    }

    private long checkLimit(long sizeRequested) {
        if (bytesRead >= size) {
            return 0;
        } else if (bytesRead + sizeRequested >= size) {
            return size - bytesRead;
        } else {
            return sizeRequested;
        }
    }

}
