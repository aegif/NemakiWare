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
package org.apache.chemistry.opencmis.server.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

import org.mockito.Mockito;

public class HttpRequestMockHelper {

    public static HttpServletRequest createMultipartRequest(String boundary, byte[] content) throws IOException {
        FakeServletInputStream stream = new FakeServletInputStream(content);

        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getContentType()).thenReturn("multipart/form-data; boundary=\"" + boundary + "\"");
        Mockito.when(request.getInputStream()).thenReturn(stream);

        return request;
    }

    public static HttpServletRequest createMultipartRequest(String boundary, InputStream inputStream)
            throws IOException {
        FakeServletInputStream stream = new FakeServletInputStream(inputStream);

        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getContentType()).thenReturn("multipart/form-data; boundary=\"" + boundary + "\"");
        Mockito.when(request.getInputStream()).thenReturn(stream);

        return request;
    }

    public static HttpServletRequest createFormRequest(String encoding, byte[] content) throws IOException {
        FakeServletInputStream stream = new FakeServletInputStream(content);

        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getContentType()).thenReturn(
                "application/x-www-form-urlencoded" + (encoding == null ? "" : ";charset=" + encoding));
        Mockito.when(request.getInputStream()).thenReturn(stream);

        return request;
    }

    private static class FakeServletInputStream extends ServletInputStream {

        private InputStream stream;

        public FakeServletInputStream(byte[] content) {
            this.stream = new ByteArrayInputStream(content);
        }

        public FakeServletInputStream(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public int read() throws IOException {
            return stream.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return stream.read(b, off, len);
        }

        @Override
        public int read(byte[] b) throws IOException {
            return stream.read(b);
        }

        @Override
        public boolean isFinished() {
            try {
                return stream.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }
    }
}
