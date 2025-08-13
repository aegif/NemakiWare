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
import java.util.Random;

public class RandomInputStream extends InputStream {

    private final Random random;
    private final long length;
    private long pos;

    public RandomInputStream(long length) {
        this(length, System.currentTimeMillis());
    }

    public RandomInputStream(long length, long seed) {
        random = new Random(seed);
        this.length = length;
        pos = 0;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(Integer.MAX_VALUE, length - pos);
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }

        for (long l = 0; l < n; l++) {
            if (read() == -1) {
                return l;
            }
        }

        return n;
    }

    @Override
    public int read() throws IOException {
        if (pos == length) {
            return -1;
        }

        pos++;

        return random.nextInt(256);
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (len < 0) || (len > b.length - off)) {
            throw new IndexOutOfBoundsException();
        }

        for (int i = 0; i < len; i++) {
            int r = read();
            if (r == -1) {
                return i == 0 ? -1 : i;
            }
            b[off + i] = (byte) r;
        }

        return len;
    }
}