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
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.MimeHelper;
import org.apache.chemistry.opencmis.commons.server.TempStoreOutputStream;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;

/**
 * Simple multi-part parser, following all necessary standards for the CMIS
 * browser binding.
 */
public class MultipartParser {

    public static final String MULTIPART = "multipart/";

    private static final String CHARSET_FIELD = "_charset_";

    private static final int MAX_FIELD_BYTES = 10 * 1024 * 1024;
    private static final int BUFFER_SIZE = 256 * 1024;

    private static final byte CR = 0x0D;
    private static final byte LF = 0x0A;
    private static final byte DASH = 0x2D;
    private static final byte[] BOUNDARY_PREFIX = { CR, LF, DASH, DASH };

    private final HttpServletRequest request;
    private final TempStoreOutputStreamFactory streamFactory;
    private final InputStream requestStream;

    private byte[] boundary;
    private int[] badCharacters;
    private int[] goodSuffixes;

    private byte[] buffer;
    private byte[] buffer2;
    private int bufferPosition;
    private int bufferCount;
    private boolean eof;

    private int fieldBytes;
    private boolean hasContent;

    private Map<String, String> headers;

    private String filename;
    private String contentType;
    private BigInteger contentSize;
    private InputStream contentStream;

    private Map<String, String[]> fields;
    private Map<String, byte[][]> rawFields;
    private String charset = IOUtils.ISO_8859_1;

    public MultipartParser(HttpServletRequest request, TempStoreOutputStreamFactory streamFactory) throws IOException {
        this.request = request;
        this.streamFactory = streamFactory;
        this.requestStream = request.getInputStream();

        extractBoundary();

        buffer = new byte[BUFFER_SIZE + boundary.length];
        buffer2 = new byte[buffer.length];
        bufferPosition = 0;
        bufferCount = 0;
        eof = false;

        hasContent = false;
        fieldBytes = 0;

        fields = new HashMap<String, String[]>();
        rawFields = new HashMap<String, byte[][]>();

        // JAKARTA EE FIX: Check if multipart was already processed by container
        boolean streamConsumed = false;
        try {
            int available = requestStream.available();
            if (available == 0 && request.getContentLength() > 0) {
                // Stream consumed but content was present - likely processed by Tomcat
                System.out.println("MULTIPART FIX: Stream consumed by container (Content-Length=" +
                                  request.getContentLength() + " but available=" + available +
                                  "), will use Parts API in parse()");
                streamConsumed = true;
                eof = true;
            }
        } catch (Exception e) {
            System.out.println("MULTIPART FIX: Error checking stream: " + e.getMessage());
        }

        if (!streamConsumed) {
            skipPreamble();
        }
    }

    private void addField(String name, String value) {
        String[] values = fields.get(name);

        if (values == null) {
            fields.put(name, new String[] { value });
        } else {
            String[] newValues = new String[values.length + 1];
            System.arraycopy(values, 0, newValues, 0, values.length);
            newValues[newValues.length - 1] = value;
            fields.put(name, newValues);
        }
    }

    private void addRawField(String name, byte[] value) {
        byte[][] values = rawFields.get(name);

        if (values == null) {
            byte[][] newValue = new byte[][] { value };
            rawFields.put(name, newValue);
        } else {
            byte[][] newValues = new byte[values.length + 1][];
            System.arraycopy(values, 0, newValues, 0, values.length);
            newValues[newValues.length - 1] = value;
            rawFields.put(name, newValues);
        }
    }

    private void extractBoundary() {
        String requestContentType = request.getContentType();

        // parse content type and extract boundary
        byte[] extractedBoundary = MimeHelper.getBoundaryFromMultiPart(requestContentType);
        
        if (extractedBoundary == null) {
            throw new CmisInvalidArgumentException("Invalid multipart request!");
        }

        boundary = new byte[BOUNDARY_PREFIX.length + extractedBoundary.length];
        System.arraycopy(BOUNDARY_PREFIX, 0, boundary, 0, BOUNDARY_PREFIX.length);
        System.arraycopy(extractedBoundary, 0, boundary, BOUNDARY_PREFIX.length, extractedBoundary.length);

        // prepare boundary search

        int m = boundary.length;

        badCharacters = new int[256];
        Arrays.fill(badCharacters, -1);

        for (int j = 0; j < m; j++) {
            badCharacters[boundary[j] & 0xff] = j;
        }

        int[] f = new int[m + 1];
        goodSuffixes = new int[m + 1];
        int i = m;
        int j = m + 1;
        f[i] = j;
        while (i > 0) {
            while (j <= m && boundary[i - 1] != boundary[j - 1]) {
                if (goodSuffixes[j] == 0) {
                    goodSuffixes[j] = j - i;
                }
                j = f[j];
            }
            i--;
            j--;
            f[i] = j;
        }

        j = f[0];
        for (i = 0; i <= m; i++) {
            if (goodSuffixes[i] == 0) {
                goodSuffixes[i] = j;
            }

            if (i == j) {
                j = f[j];
            }
        }
    }

    private int findBoundary() {
        if (bufferCount < boundary.length) {
            if (eof) {
                throw new CmisInvalidArgumentException("Unexpected end of stream!");
            } else {
                return -1;
            }
        }

        int m = boundary.length;

        int i = 0;
        while (i <= bufferCount - m) {
            int j = m - 1;
            while (j >= 0 && boundary[j] == buffer[i + j]) {
                j--;
            }

            if (j < 0) {
                return i;
            } else {
                i += Math.max(goodSuffixes[j + 1], j - badCharacters[buffer[i + j] & 0xff]);
            }
        }

        return -1;
    }

    private void readBuffer() throws IOException {
        if (bufferPosition < bufferCount) {
            System.arraycopy(buffer, bufferPosition, buffer2, 0, bufferCount - bufferPosition);
            bufferCount = bufferCount - bufferPosition;

            byte[] tmpBuffer = buffer2;
            buffer2 = buffer;
            buffer = tmpBuffer;
        } else {
            bufferCount = 0;
        }

        bufferPosition = 0;

        if (eof) {
            return;
        }

        while (true) {
            int r = requestStream.read(buffer, bufferCount, buffer.length - bufferCount);
            if (r == -1) {
                eof = true;
                break;
            }

            bufferCount += r;
            if (buffer.length == bufferCount) {
                break;
            }
        }
    }

    private int nextByte() throws IOException {
        if (bufferCount == 0) {
            if (eof) {
                return -1;
            } else {
                readBuffer();
                return nextByte();
            }
        }

        if (bufferCount > bufferPosition) {
            return buffer[bufferPosition++] & 0xff;
        }

        readBuffer();
        return nextByte();
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(128);

        int r;
        while ((r = nextByte()) > -1) {
            if (r == CR) {
                if (nextByte() != LF) {
                    throw new CmisInvalidArgumentException("Invalid multipart request!");
                }
                break;
            }

            sb.append((char) r);
        }

        return sb.toString();
    }

    private void readHeaders() throws IOException {
        int b = nextByte();
        if (b == -1) {
            throw new CmisInvalidArgumentException("Unexpected end of stream!");
        }

        if (b == DASH) {
            b = nextByte();
            if (b == DASH) {
                // expected end of stream
                headers = null;
                return;
            }
        } else if (b == CR) {
            b = nextByte();
            if (b == LF) {
                parseHeaders();
                return;
            }
        }

        throw new CmisInvalidArgumentException("Invalid multipart request!");
    }

    private void parseHeaders() throws IOException {
        headers = new HashMap<String, String>();

        while (true) {
            String line = readLine();
            if (line.length() == 0) {
                // empty line -> end of headers
                break;
            }

            int x = line.indexOf(':');
            if (x > 0) {
                headers.put(line.substring(0, x).toLowerCase(Locale.ENGLISH).trim(), line.substring(x + 1).trim());
            }
        }
    }

    private byte[] readBodyBytes() throws IOException {
        readBuffer();

        int boundaryPosition = findBoundary();

        if (boundaryPosition > -1) {
            // the body bytes are completely in the buffer
            int len = boundaryPosition - bufferPosition;
            addFieldBytes(len);

            byte[] body = new byte[len];
            System.arraycopy(buffer, bufferPosition, body, 0, len);
            bufferPosition = boundaryPosition + boundary.length;
            return body;
        }

        // the body bytes are not completely in the buffer
        // read all available bytes
        int len = Math.min(BUFFER_SIZE, bufferCount) - bufferPosition;
        addFieldBytes(len);

        byte[] bodyBytes = new byte[len + BUFFER_SIZE];
        int bodyBytesPos = len;

        System.arraycopy(buffer, bufferPosition, bodyBytes, 0, len);
        bufferPosition = bufferPosition + len;

        // read next chunk
        while (true) {
            readBuffer();

            boundaryPosition = findBoundary();

            if (boundaryPosition > -1) {
                // last chunk
                len = boundaryPosition - bufferPosition;
                addFieldBytes(len);

                if (bodyBytesPos + len >= bodyBytes.length) {
                    byte[] newBodyBytes = new byte[bodyBytesPos + len];
                    System.arraycopy(bodyBytes, 0, newBodyBytes, 0, bodyBytesPos);
                    bodyBytes = newBodyBytes;
                }
                System.arraycopy(buffer, bufferPosition, bodyBytes, bodyBytesPos, len);
                bodyBytesPos += len;

                bufferPosition = boundaryPosition + boundary.length;
                break;
            } else {
                // not the last chunk
                len = Math.min(BUFFER_SIZE, bufferCount) - bufferPosition;
                addFieldBytes(len);

                if (bodyBytesPos + len >= bodyBytes.length) {
                    int newSize = bodyBytes.length << 1;
                    if (newSize < 0 || newSize > MAX_FIELD_BYTES) {
                        newSize = MAX_FIELD_BYTES;
                    }
                    if (newSize < bodyBytesPos + len) {
                        newSize = bodyBytesPos + BUFFER_SIZE;
                    }

                    byte[] newBodyBytes = new byte[newSize];
                    System.arraycopy(bodyBytes, 0, newBodyBytes, 0, bodyBytesPos);
                    bodyBytes = newBodyBytes;
                }
                System.arraycopy(buffer, bufferPosition, bodyBytes, bodyBytesPos, len);
                bodyBytesPos += len;

                bufferPosition = bufferPosition + len;
            }
        }

        if (bodyBytes.length == bodyBytesPos) {
            return bodyBytes;
        }

        byte[] returnBytes = new byte[bodyBytesPos];
        System.arraycopy(bodyBytes, 0, returnBytes, 0, returnBytes.length);

        return returnBytes;
    }

    private void addFieldBytes(int len) {
        fieldBytes += len;
        if (fieldBytes > MAX_FIELD_BYTES) {
            throw new CmisInvalidArgumentException("Limit exceeded!");
        }
    }

    private void readBodyAsStream(String contentType, String filename) throws IOException {
        TempStoreOutputStream stream = streamFactory.newOutputStream();
        stream.setMimeType(contentType);
        stream.setFileName(filename);

        try {
            while (true) {
                readBuffer();

                int boundaryPosition = findBoundary();

                if (boundaryPosition > -1) {
                    stream.write(buffer, bufferPosition, boundaryPosition - bufferPosition);
                    bufferPosition = boundaryPosition + boundary.length;
                    break;
                } else {
                    int len = Math.min(BUFFER_SIZE, bufferCount) - bufferPosition;
                    stream.write(buffer, bufferPosition, len);
                    bufferPosition = bufferPosition + len;
                }
            }

            stream.close();

            contentSize = BigInteger.valueOf(stream.getLength());
            contentStream = stream.getInputStream();
        } catch (IOException e) {
            // if something went wrong, make sure the temp file will
            // be deleted
            stream.destroy(e);
            throw e;
        }
    }

    private void readBody() throws IOException {
        String contentDisposition = headers.get("content-disposition");

        if (contentDisposition == null) {
            throw new CmisInvalidArgumentException("Invalid multipart request!");
        }

        Map<String, String> params = new HashMap<String, String>();
        MimeHelper.decodeContentDisposition(contentDisposition, params);
        
        // CRITICAL FIX: Recognize ContentStream by field name "content" even without filename parameter
        // This fixes Browser Binding document creation when filename is not provided in Content-Disposition header
        String fieldName = params.get(MimeHelper.DISPOSITION_NAME);
        boolean hasFilename = params.containsKey(MimeHelper.DISPOSITION_FILENAME);
        boolean isContent = hasFilename || "content".equals(fieldName);

        if (isContent) {
            if (hasContent) {
                throw new CmisInvalidArgumentException("Only one content expected!");
            }

            hasContent = true;

            filename = params.get(MimeHelper.DISPOSITION_FILENAME);

            if (filename != null) {
                // if the browser sent the full path,
                // extract the filename segment
                int pathsep = filename.lastIndexOf('/');
                if (pathsep > -1) {
                    filename = filename.substring(pathsep + 1);
                }
                pathsep = filename.lastIndexOf('\\');
                if (pathsep > -1) {
                    filename = filename.substring(pathsep + 1);
                }

                filename = filename.trim();
            }

            contentType = headers.get("content-type");
            if (contentType == null) {
                contentType = Constants.MEDIATYPE_OCTETSTREAM;
            }

            readBodyAsStream(contentType, filename);
        } else {
            String name = params.get(MimeHelper.DISPOSITION_NAME);
            byte[] rawValue = readBodyBytes();

            if (CHARSET_FIELD.equalsIgnoreCase(name)) {
                charset = new String(rawValue, IOUtils.ISO_8859_1);
                return;
            }

            String fieldContentType = headers.get("content-type");
            if (fieldContentType != null) {
                String fieldCharset = MimeHelper.getCharsetFromContentType(fieldContentType);
                if (fieldCharset != null) {
                    addField(name, new String(rawValue, fieldCharset));
                    return;
                }
            }

            addRawField(name, rawValue);
        }
    }

    private void skipPreamble() throws IOException {
        // Note: Stream consumption check is now done in constructor
        readBuffer();

        // COMPREHENSIVE FIX: Handle chunked transfer encoding and EOF issues
        // With chunked encoding, initial buffer might not have enough bytes
        // Force reading even if EOF flag is set prematurely
        int readAttempts = 0;
        while (bufferCount < boundary.length - 2 && readAttempts < 10) {
            int prevBufferCount = bufferCount;

            // CRITICAL: Force reading even if EOF flag is set - chunked encoding issue

            // Direct stream reading bypassing EOF check
            if (!eof || (eof && bufferCount == 0)) {
                try {
                    int r = requestStream.read(buffer, bufferCount, buffer.length - bufferCount);
                    if (r > 0) {
                        bufferCount += r;
                        eof = false; // Reset EOF if we got data
                    } else if (r == -1) {
                        eof = true;
                    }
                } catch (IOException e) {
                    eof = true;
                }
            } else {
                readBuffer();
            }

            readAttempts++;

            // If buffer didn't grow after multiple attempts, break
            if (bufferCount == prevBufferCount) {
                break;
            }
        }

        // JAKARTA EE FIX: Check if stream was already consumed by container
        if (bufferCount == 0) {
            // Check if parameters are available via request.getParameter()
            String testParam = request.getParameter("cmisaction");
            if (testParam != null) {
                System.out.println("MULTIPART FIX: Stream consumed but parameters available - container processed multipart");
                // Don't throw exception - parameters are available via getParameter()
                return;
            }
        }

        if (bufferCount < boundary.length - 2) {
            throw new CmisInvalidArgumentException("Invalid multipart request!");
        }

        for (int i = 2; i < boundary.length; i++) {
            if (boundary[i] != buffer[i - 2]) {
                break;
            }

            if (i == boundary.length - 1) {
                bufferPosition = boundary.length - 2;
                readBuffer();
                return;
            }
        }

        while (true) {
            int boundaryPosition = findBoundary();

            if (boundaryPosition > -1) {
                bufferPosition = boundaryPosition + boundary.length;
                readBuffer();
                break;
            }

            bufferPosition = BUFFER_SIZE + 1;
            readBuffer();
        }
    }

    private void skipEpilogue() {
        try {
            // read to the end of stream, but max 1 MB
            int count = 0;
            byte[] tmpBuf = new byte[4096];
            int b;
            while ((b = requestStream.read(tmpBuf)) > -1) {
                count += b;
                if (count >= 1024 * 1024) {
                    break;
                }
            }
        } catch (IOException e) {
            // ignore
        }
    }

    private boolean readNext() throws IOException {
        try {
            readHeaders();

            // no headers -> end of request
            if (headers == null) {
                skipEpilogue();
                return false;
            }


            readBody();

            return true;
        } catch (IOException e) {
            e.printStackTrace();
            
            IOUtils.closeQuietly(contentStream);

            skipEpilogue();

            throw e;
        } catch (Exception e) {
            e.printStackTrace();
            
            IOUtils.closeQuietly(contentStream);
            skipEpilogue();
            throw e;
        }
    }

    public void parse() throws IOException {
        // JAKARTA EE / TOMCAT 10 FIX: Try Parts API first, fallback to stream parsing
        boolean partsApiSuccess = false;

        // Try to use Parts API if Tomcat has already processed multipart
        try {
            // If eof is already true (set in constructor), immediately use Parts API
            if (eof) {
                System.out.println("MULTIPART FIX: EOF already set in constructor - stream was consumed by container");
                System.out.println("MULTIPART FIX: Using Parts API to retrieve multipart data");

                java.util.Collection<jakarta.servlet.http.Part> parts = request.getParts();
                if (parts != null && !parts.isEmpty()) {
                    System.out.println("MULTIPART FIX: Found " + parts.size() + " parts via Parts API");

                    // Process all parts
                    for (jakarta.servlet.http.Part part : parts) {
                        String partName = part.getName();
                        System.out.println("MULTIPART FIX: Processing part '" + partName + "'");

                        if ("content".equals(partName)) {
                            // File content part
                            filename = part.getSubmittedFileName();
                            contentType = part.getContentType();

                            // CRITICAL TCK FIX: Part.getSize() may return 0 if Content-Length header is not present
                            // In that case, read the InputStream to calculate actual size
                            long partSize = part.getSize();
                            System.out.println("MULTIPART DEBUG: Part.getSize() returned: " + partSize);

                            if (partSize > 0) {
                                contentSize = java.math.BigInteger.valueOf(partSize);
                                contentStream = part.getInputStream();
                                System.out.println("MULTIPART DEBUG: Using Part.getSize() value: " + partSize);
                            } else {
                                // Read InputStream into ByteArrayOutputStream to calculate size
                                System.out.println("MULTIPART DEBUG: Part.getSize()=0, attempting to read InputStream...");
                                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                java.io.InputStream partStream = part.getInputStream();
                                System.out.println("MULTIPART DEBUG: InputStream obtained, class: " + partStream.getClass().getName());

                                try {
                                    int available = partStream.available();
                                    System.out.println("MULTIPART DEBUG: InputStream.available() = " + available);
                                } catch (Exception e) {
                                    System.out.println("MULTIPART DEBUG: InputStream.available() threw exception: " + e.getMessage());
                                }

                                try {
                                    byte[] buffer = new byte[8192];
                                    int bytesRead;
                                    int totalBytesRead = 0;
                                    int readCount = 0;
                                    while ((bytesRead = partStream.read(buffer)) != -1) {
                                        readCount++;
                                        totalBytesRead += bytesRead;
                                        baos.write(buffer, 0, bytesRead);
                                        System.out.println("MULTIPART DEBUG: Read iteration " + readCount + ": " + bytesRead + " bytes (total: " + totalBytesRead + ")");
                                    }
                                    System.out.println("MULTIPART DEBUG: InputStream read complete, total bytes: " + totalBytesRead);
                                } finally {
                                    partStream.close();
                                }

                                byte[] contentBytes = baos.toByteArray();
                                contentSize = java.math.BigInteger.valueOf(contentBytes.length);
                                contentStream = new java.io.ByteArrayInputStream(contentBytes);
                                System.out.println("MULTIPART FIX: Calculated actual size from InputStream: " + contentSize + " bytes");
                            }

                            hasContent = true;
                            System.out.println("MULTIPART FIX: Found content part - filename: " + filename + ", type: " + contentType + ", size: " + contentSize);
                        } else {
                            // Form field part
                            try (java.io.InputStream partStream = part.getInputStream()) {
                                // Read the stream into a byte array
                                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                                byte[] buffer = new byte[1024];
                                int bytesRead;
                                while ((bytesRead = partStream.read(buffer)) != -1) {
                                    baos.write(buffer, 0, bytesRead);
                                }
                                String value = new String(baos.toByteArray(), "UTF-8");
                                addField(partName, value);
                                System.out.println("MULTIPART FIX: Added field '" + partName + "' = '" + value + "'");
                            }
                        }
                    }

                    // Also get any regular form parameters
                    java.util.Map<String, String[]> paramMap = request.getParameterMap();
                    for (java.util.Map.Entry<String, String[]> entry : paramMap.entrySet()) {
                        if (!fields.containsKey(entry.getKey())) {
                            fields.put(entry.getKey(), entry.getValue());
                            System.out.println("MULTIPART FIX: Added parameter '" + entry.getKey() + "' with " + entry.getValue().length + " values");
                        }
                    }

                    partsApiSuccess = true;
                    System.out.println("MULTIPART FIX: Successfully processed multipart via Parts API");
                    System.out.println("MULTIPART FIX: Final fields map size: " + fields.size());
                    System.out.println("MULTIPART FIX: Final fields content:");
                    for (Map.Entry<String, String[]> entry : fields.entrySet()) {
                        System.out.println("MULTIPART FIX:   '" + entry.getKey() + "' = " + java.util.Arrays.toString(entry.getValue()));
                    }
                    return;
                }
            }
        } catch (jakarta.servlet.ServletException e) {
            System.out.println("MULTIPART FIX: Parts API failed with ServletException: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("MULTIPART FIX: Parts API failed with exception: " + e.getClass().getName() + ": " + e.getMessage());
        }

        // If Parts API failed or stream is available, use traditional parsing
        if (!partsApiSuccess) {
            // JAKARTA EE FIX: If stream was consumed but Parts API failed, we can't proceed
            if (eof) {
                System.out.println("MULTIPART FIX: ERROR - Stream consumed by container but Parts API failed");
                throw new CmisInvalidArgumentException("Multipart stream was consumed by container but Parts API failed to retrieve data");
            }
            System.out.println("MULTIPART FIX: Falling back to traditional stream parsing");
        }

        try {
            int partCount = 0;

            while (readNext()) {
                partCount++;
                // nothing to do here, just read
            }


            // apply charset
            for (Map.Entry<String, byte[][]> e : rawFields.entrySet()) {

                String[] otherValues = fields.get(e.getKey());
                int index = (otherValues != null ? otherValues.length : 0);

                String[] values = new String[e.getValue().length + index];

                if (otherValues != null) {
                    System.arraycopy(otherValues, 0, values, 0, otherValues.length);
                }

                for (byte[] rawValue : e.getValue()) {
                    values[index++] = new String(rawValue, charset);
                }

                fields.put(e.getKey(), values);
            }
            
        } catch (Exception e) {
            
            if (contentStream != null) {
                IOUtils.closeQuietly(contentStream);
            }

            skipEpilogue();

            fields = null;

            if (e instanceof UnsupportedEncodingException) {
                throw new CmisInvalidArgumentException("Encoding not supported!", e);
            } else if (e instanceof CmisBaseException) {
                throw (CmisBaseException) e;
            } else if (e instanceof IOException) {
                throw (IOException) e;
            } else {
                throw new CmisRuntimeException(e.getMessage(), e);
            }
        } finally {
            rawFields = null;
        }
    }

    public boolean hasContent() {
        return hasContent;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public BigInteger getSize() {
        return contentSize;
    }

    public InputStream getStream() {
        return contentStream;
    }

    public Map<String, String[]> getFields() {
        System.out.println("MULTIPART FIX: getFields() called, returning map with " + (fields != null ? fields.size() : "null") + " entries");
        if (fields != null && fields.size() > 0) {
            System.out.println("MULTIPART FIX: getFields() content:");
            for (Map.Entry<String, String[]> entry : fields.entrySet()) {
                System.out.println("MULTIPART FIX:   '" + entry.getKey() + "' = " + java.util.Arrays.toString(entry.getValue()));
            }
        }
        return fields;
    }

    /**
     * Returns if the request is a multi-part request
     */
    public static final boolean isMultipartContent(HttpServletRequest request) {
        String contentType = request.getContentType();

        if (contentType != null && contentType.toLowerCase(Locale.ENGLISH).startsWith(MULTIPART)) {
            return true;
        }

        return false;
    }
}
