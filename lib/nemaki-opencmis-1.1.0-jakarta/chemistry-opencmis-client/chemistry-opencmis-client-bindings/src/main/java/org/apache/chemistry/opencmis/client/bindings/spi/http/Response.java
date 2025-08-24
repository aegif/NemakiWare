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
package org.apache.chemistry.opencmis.client.bindings.spi.http;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.impl.Base64;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;

/**
 * HTTP Response.
 */
public class Response {
    private static final int MAX_ERROR_LENGTH = 128 * 1024;

    private final int responseCode;
    private final String responseMessage;
    private final Map<String, List<String>> headers;
    private InputStream stream;
    private String errorContent;
    private BigInteger length;
    private String charset;
    private boolean hasResponseStream;

    public Response(int responseCode, String responseMessage, Map<String, List<String>> headers,
            InputStream responseStream, InputStream errorStream) {
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.stream = responseStream;
        this.hasResponseStream = stream != null;
        boolean isGZIP = responseStream instanceof GZIPInputStream;

        this.headers = new HashMap<String, List<String>>();
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                this.headers.put(e.getKey() == null ? null : e.getKey().toLowerCase(Locale.ENGLISH), e.getValue());
            }
        }

        // determine charset
        charset = IOUtils.UTF8;
        String contentType = getContentTypeHeader();
        if (contentType != null) {
            String[] parts = contentType.split(";");
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim().toLowerCase(Locale.ENGLISH);
                if (part.startsWith("charset")) {
                    int x = part.indexOf('=');
                    charset = part.substring(x + 1).trim();
                    break;
                }
            }
        }

        // if there is an error page, get it
        if (errorStream != null) {
            if (contentType != null) {
                String contentTypeLower = contentType.toLowerCase(Locale.ENGLISH).split(";")[0];
                if (contentTypeLower.startsWith("text/") || contentTypeLower.endsWith("+xml")
                        || contentTypeLower.startsWith("application/xml")
                        || contentTypeLower.startsWith("application/json")) {
                    errorStream = new BufferedInputStream(errorStream, 64 * 1024);
                    StringBuilder sb = new StringBuilder(4096);

                    try {
                        String encoding = getContentEncoding();
                        if (encoding != null) {
                            String encLower = encoding.trim().toLowerCase(Locale.ENGLISH);
                            if (encLower.equals("gzip") && !isGZIP) {
                                errorStream = new GZIPInputStream(errorStream, 64 * 1024);
                            } else if (encLower.equals("deflate")) {
                                errorStream = new InflaterInputStream(errorStream, new Inflater(true), 64 * 1024);
                            }
                        }

                        InputStreamReader reader = new InputStreamReader(errorStream, charset);
                        char[] buffer = new char[4096];
                        int b;
                        while ((b = reader.read(buffer)) > -1) {
                            sb.append(buffer, 0, b);
                            if (sb.length() >= MAX_ERROR_LENGTH) {
                                break;
                            }
                        }
                        reader.close();

                        errorContent = sb.toString();
                    } catch (IOException e) {
                        errorContent = "Unable to retrieve content: " + e.getMessage();
                    }
                }
            } else {
                IOUtils.closeQuietly(errorStream);
            }

            IOUtils.closeQuietly(responseStream);

            return;
        }

        // get the stream length
        length = null;
        String lengthStr = getHeader("Content-Length");
        if (lengthStr != null && !isGZIP) {
            try {
                length = new BigInteger(lengthStr);
            } catch (NumberFormatException e) {
                // content-length is not a number -> ignore
            }
        }

        if (stream == null || BigInteger.ZERO.equals(length) || responseCode == 204) {
            hasResponseStream = false;
        } else {
            stream = new BufferedInputStream(stream, 64 * 1024);
            try {
                hasResponseStream = IOUtils.checkForBytes(stream);
            } catch (IOException ioe) {
                throw new CmisConnectionException("IO exception!", ioe);
            }

            if (hasResponseStream) {
                String encoding = getContentEncoding();
                if (encoding != null) {
                    String encLower = encoding.trim().toLowerCase(Locale.ENGLISH);
                    if (encLower.equals("gzip") && !isGZIP) {
                        // if the stream is gzip encoded, decode it
                        length = null;
                        try {
                            stream = new GZIPInputStream(stream, 64 * 1024);
                        } catch (IOException e) {
                            errorContent = e.getMessage();
                            stream = null;
                            IOUtils.closeQuietly(responseStream);
                        }
                    } else if (encLower.equals("deflate")) {
                        // if the stream is deflate encoded, decode it
                        length = null;
                        stream = new InflaterInputStream(stream, new Inflater(true), 64 * 1024);
                    }
                }

                String transferEncoding = getContentTransferEncoding();
                if (transferEncoding != null && transferEncoding.trim().toLowerCase(Locale.ENGLISH).equals("base64")) {
                    // if the stream is base64 encoded, decode it
                    length = null;
                    stream = new Base64.InputStream(stream);
                }
            }
        }
    }

    public int getResponseCode() {
        return responseCode;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public String getHeader(String name) {
        List<String> list = headers.get(name.toLowerCase(Locale.US));
        if (isNullOrEmpty(list)) {
            return null;
        }

        return list.get(0);
    }

    public String getContentTypeHeader() {
        return getHeader("Content-Type");
    }

    public BigInteger getContentLengthHeader() {
        String lengthStr = getHeader("Content-Length");
        if (lengthStr == null) {
            return null;
        }

        try {
            return new BigInteger(lengthStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getLocactionHeader() {
        return getHeader("Location");
    }

    public String getContentLocactionHeader() {
        return getHeader("Content-Location");
    }

    public String getContentTransferEncoding() {
        return getHeader("Content-Transfer-Encoding");
    }

    public String getContentEncoding() {
        return getHeader("Content-Encoding");
    }

    public String getContentDisposition() {
        return getHeader("Content-Disposition");
    }

    public String getCharset() {
        return charset;
    }

    public BigInteger getContentLength() {
        return length;
    }

    public boolean hasResponseStream() {
        return hasResponseStream;
    }

    public InputStream getStream() {
        return stream;
    }

    public String getErrorContent() {
        return errorContent;
    }
}