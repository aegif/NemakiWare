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
package org.apache.chemistry.opencmis.server.impl.browser;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.server.shared.HttpUtils;
import org.apache.chemistry.opencmis.server.shared.QueryStringHttpServletRequestWrapper;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;

public final class POSTHttpServletRequestWrapper extends QueryStringHttpServletRequestWrapper {

    public static final String FORM_URLENCODED = "application/x-www-form-urlencoded";
    private static final int MAX_CONTENT_BYTES = 10 * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String CHARSET_FIELD = "_charset_";

    private String filename;
    private String contentType;
    private BigInteger size;
    private InputStream stream;

    @SuppressWarnings("unchecked")
    public POSTHttpServletRequestWrapper(HttpServletRequest request, TempStoreOutputStreamFactory streamFactory)
            throws IOException {
        super(request);

        // MULTIPART DEBUG: Enhanced logging for multipart request processing
        System.out.println("MULTIPART DEBUG: POSTHttpServletRequestWrapper constructor called");
        System.out.println("MULTIPART DEBUG: Request URI: " + request.getRequestURI());
        System.out.println("MULTIPART DEBUG: Request method: " + request.getMethod());
        System.out.println("MULTIPART DEBUG: Content-Type: " + request.getContentType());
        System.out.println("MULTIPART DEBUG: Content-Length: " + request.getContentLength());
        
        // CRITICAL DEBUG: Check InputStream state BEFORE MultipartParser
        try {
            System.out.println("MULTIPART DEBUG: Checking InputStream state before MultipartParser...");
            InputStream inputStream = request.getInputStream();
            int available = inputStream.available();
            System.out.println("MULTIPART DEBUG: InputStream.available() = " + available + " bytes");
            
            // Check if this is a chunked request
            String transferEncoding = request.getHeader("Transfer-Encoding");
            String contentLength = request.getHeader("Content-Length");
            System.out.println("MULTIPART DEBUG: Transfer-Encoding header: " + transferEncoding);
            System.out.println("MULTIPART DEBUG: Content-Length header: " + contentLength);
            
            // Test if stream supports mark/reset
            if (inputStream.markSupported()) {
                System.out.println("MULTIPART DEBUG: InputStream supports mark/reset");
                inputStream.mark(10);
                int firstByte = inputStream.read();
                inputStream.reset();
                System.out.println("MULTIPART DEBUG: First byte peek: " + firstByte + " (stream intact after reset)");
            } else {
                System.out.println("MULTIPART DEBUG: InputStream does NOT support mark/reset - stream cannot be inspected without consuming");
            }
        } catch (Exception e) {
            System.out.println("MULTIPART DEBUG: ERROR checking InputStream state: " + e.getMessage());
        }
        
        try {
            if (MultipartParser.isMultipartContent(request)) {
                System.out.println("MULTIPART DEBUG: Processing as multipart content");
                // multipart processing
                MultipartParser parser = new MultipartParser(request, streamFactory);
                System.out.println("MULTIPART DEBUG: MultipartParser created successfully");
                
                System.out.println("MULTIPART DEBUG: Starting parser.parse()");
                parser.parse();
                System.out.println("MULTIPART DEBUG: parser.parse() completed successfully");

                if (parser.hasContent()) {
                    System.out.println("MULTIPART DEBUG: Parser has content - setting filename, contentType, size, stream");
                    filename = parser.getFilename();
                    contentType = parser.getContentType();
                    size = parser.getSize();
                    stream = parser.getStream();
                    System.out.println("MULTIPART DEBUG: Content details - filename: " + filename + ", contentType: " + contentType + ", size: " + size);
                } else {
                    System.out.println("MULTIPART DEBUG: Parser has no content");
                }

                System.out.println("MULTIPART DEBUG: Processing parser fields");
                for (Map.Entry<String, String[]> e : parser.getFields().entrySet()) {
                    System.out.println("MULTIPART DEBUG: Adding parameter '" + e.getKey() + "' with " + e.getValue().length + " values");
                    for (String value : e.getValue()) {
                        System.out.println("MULTIPART DEBUG: Parameter '" + e.getKey() + "' value: '" + value + "'");
                    }
                    addParameter(e.getKey(), e.getValue());
                }

                String filenameControl = HttpUtils.getStringParameter(this, Constants.CONTROL_FILENAME);
                if (filenameControl != null && filenameControl.trim().length() > 0) {
                    System.out.println("MULTIPART DEBUG: Overriding filename from control parameter: " + filenameControl);
                    filename = filenameControl;
                }

                String contentTypeControl = HttpUtils.getStringParameter(this, Constants.CONTROL_CONTENT_TYPE);
                if (contentTypeControl != null && contentTypeControl.trim().length() > 0) {
                    System.out.println("MULTIPART DEBUG: Overriding contentType from control parameter: " + contentTypeControl);
                    contentType = contentTypeControl;
                }
                
                System.out.println("MULTIPART DEBUG: Multipart processing completed successfully");
                
            } else if (isFormUrlencodedContent(request)) {
                System.out.println("MULTIPART DEBUG: Processing as form-urlencoded content");
                // form data processing
                if (!parseFormUrlEncodedData(request)) {
                    parameters.putAll(request.getParameterMap());
                }
            } else {
                System.out.println("MULTIPART DEBUG: ERROR - Neither multipart nor form-urlencoded content");
                System.out.println("MULTIPART DEBUG: Content-Type: " + request.getContentType());
                // spec incompliant form encoding
                throw new CmisInvalidArgumentException("Invalid form encoding!");
            }
        } catch (Exception e) {
            System.out.println("MULTIPART DEBUG: EXCEPTION in POSTHttpServletRequestWrapper constructor: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("MULTIPART DEBUG: EXCEPTION cause: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            e.printStackTrace();
            throw e;
        }
    }

    public String getFilename() {
        return filename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    public BigInteger getSize() {
        return size;
    }

    public InputStream getStream() {
        return stream;
    }

    /**
     * Parses a form data request
     * 
     * @param request
     *            the request
     * @return {@code true} if the body contained data, {@code false} otherwise
     */
    protected boolean parseFormUrlEncodedData(HttpServletRequest request) throws IOException {
        byte[] data = new byte[BUFFER_SIZE];
        int dataPos = 0;

        InputStream stream = request.getInputStream();
        int b;
        byte[] buffer = new byte[BUFFER_SIZE];

        // read stream
        while ((b = stream.read(buffer)) != -1) {
            if (dataPos + b > MAX_CONTENT_BYTES) {
                throw new CmisInvalidArgumentException("Limit exceeded!");
            }

            if (data.length - dataPos < b) {
                // expand buffer
                int newSize = ((data.length + b) * 2 < MAX_CONTENT_BYTES ? (data.length + b * 2) : MAX_CONTENT_BYTES);
                byte[] newbuf = new byte[newSize];
                System.arraycopy(data, 0, newbuf, 0, dataPos);
                data = newbuf;
            }

            System.arraycopy(buffer, 0, data, dataPos, b);
            dataPos += b;
        }

        if (dataPos == 0) {
            // empty stream
            return false;
        }

        // parse parameters
        boolean parseName = true;
        boolean parseCharset = false;
        int startPos = 0;

        List<String[]> rawParameters = new ArrayList<String[]>();
        String rawName = null;
        String rawValue = null;

        String charset = null;

        for (int i = 0; i < dataPos; i++) {
            switch (data[i]) {
            case '=':
                if (startPos < i) {
                    rawName = new String(data, startPos, i - startPos, IOUtils.ISO_8859_1);
                    if (CHARSET_FIELD.equalsIgnoreCase(rawName)) {
                        parseCharset = true;
                    }
                }

                parseName = false;
                startPos = i + 1;
                break;

            case '&':
                if (parseName) {
                    if (startPos < i) {
                        rawName = new String(data, startPos, i - startPos, IOUtils.ISO_8859_1);
                        rawParameters.add(new String[] { rawName, null });
                    }
                } else {
                    if (rawName != null) {
                        rawValue = new String(data, startPos, i - startPos, IOUtils.ISO_8859_1);
                        rawParameters.add(new String[] { rawName, rawValue });
                        if (parseCharset) {
                            charset = rawValue;
                        }
                    }
                }

                rawName = null;
                rawValue = null;

                parseName = true;
                parseCharset = false;
                startPos = i + 1;
                break;

            default:
                break;
            }
        }

        if (startPos < dataPos) {
            // there is a final parameter after the last '&'
            if (parseName) {
                rawName = new String(data, startPos, dataPos - startPos, IOUtils.ISO_8859_1);
                rawParameters.add(new String[] { rawName, null });
            } else {
                if (rawName != null) {
                    rawValue = new String(data, startPos, dataPos - startPos, IOUtils.ISO_8859_1);
                    rawParameters.add(new String[] { rawName, rawValue });
                    if (parseCharset) {
                        charset = rawValue;
                    }
                }
            }
        } else if (!parseName) {
            // the stream ended with '='
            rawParameters.add(new String[] { rawName, "" });
        }

        data = null;

        // find charset
        if (charset == null) {
            // check charset in content type
            String contentType = request.getContentType();
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
        }

        if (charset == null) {
            // set default charset
            charset = IOUtils.UTF8;
        }

        // decode parameters
        for (String[] rawParameter : rawParameters) {
            String name = URLDecoder.decode(rawParameter[0], charset);

            String value = null;
            if (rawParameter[1] != null) {
                value = URLDecoder.decode(rawParameter[1], charset);
            }

            addParameter(name, value);
        }

        return true;
    }

    /**
     * Returns if the request is a form-urlencoded request.
     */
    public static final boolean isFormUrlencodedContent(HttpServletRequest request) {
        String contentType = request.getContentType();

        if (contentType != null && contentType.toLowerCase(Locale.ENGLISH).startsWith(FORM_URLENCODED)) {
            return true;
        }

        return false;
    }
}
