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
package org.apache.chemistry.opencmis.commons.impl;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

public final class IOUtils {

    /** UTF-8 character set name. */
    public static final String UTF8 = "UTF-8";
    /** ISO-8859-1 character set name. */
    public static final String ISO_8859_1 = "ISO-8859-1";

    private IOUtils() {
    }

    /**
     * Returns UTF-8 bytes of the given string or throws a
     * {@link CmisRuntimeException} if the charset 'UTF-8' is not available.
     * 
     * @param s
     *            the input string
     * 
     * @return the UTF-8 bytes
     */
    public static byte[] toUTF8Bytes(String s) {
        if (s == null) {
            return null;
        }

        try {
            return s.getBytes(UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new CmisRuntimeException("Unsupported encoding 'UTF-8'!", e);
        }
    }

    /**
     * Converts a UTF-8 encoded byte array into a string or throws a
     * {@link CmisRuntimeException} if the charset 'UTF-8' is not available.
     * 
     * @param bytes
     *            the byte array
     * @return the string
     */
    public static String toUTF8String(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        try {
            return new String(bytes, UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new CmisRuntimeException("Unsupported encoding 'UTF-8'!", e);
        }
    }

    /**
     * URL encodes the given string or throws a {@link CmisRuntimeException} if
     * the charset 'UTF-8' is not available.
     * 
     * @param s
     *            the string to encode
     * 
     * @return the encoded
     */
    public static String encodeURL(String s) {
        if (s == null) {
            return null;
        }

        try {
            return URLEncoder.encode(s, UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new CmisRuntimeException("Unsupported encoding 'UTF-8'!", e);
        }
    }

    /**
     * URL decodes the given string or throws a {@link CmisRuntimeException} if
     * the charset 'UTF-8' is not available.
     * 
     * @param s
     *            the string to decode
     * 
     * @return the decoded string
     */
    public static String decodeURL(String s) {
        if (s == null) {
            return null;
        }

        try {
            return URLDecoder.decode(s, UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new CmisRuntimeException("Unsupported encoding 'UTF-8'!", e);
        }
    }

    /**
     * Checks if a stream has more bytes. If the provided stream is not
     * markable, it wrappes a {@link BufferedInputStream} around the stream and
     * returns it.
     * 
     * @param stream
     *            the stream
     * @param bufferSize
     *            the size of the buffer in bytes if a
     *            {@link BufferedInputStream} has to be created
     * @return {@code null} if the stream doesn't have more bytes, the provided
     *         stream if the provided stream is markable and has more bytes, or
     *         a {@link BufferedInputStream} if the provided stream is not
     *         markable and has more bytes
     * 
     * @throws IOException
     */
    public static InputStream checkForBytes(InputStream stream, int bufferSize) throws IOException {
        if (stream == null) {
            return null;
        }

        InputStream checkStream = stream;

        if (!stream.markSupported()) {
            checkStream = new BufferedInputStream(stream, bufferSize);
        }

        if (checkForBytes(checkStream)) {
            return checkStream;
        }

        return null;
    }

    /**
     * Checks if a stream has more bytes.
     * 
     * @param stream
     *            a markable stream
     * @return {@code true} if the stream has more bytes, {@code false}
     *         otherwise
     */
    public static boolean checkForBytes(InputStream stream) throws IOException {
        if (stream == null) {
            return false;
        }

        if (!stream.markSupported()) {
            throw new IllegalArgumentException("Stream must support marks!");
        }

        stream.mark(2);

        if (stream.read() != -1) {
            stream.reset();
            return true;
        }

        return false;
    }

    /**
     * Closes a stream and ignores any exceptions.
     * 
     * @param closeable
     *            the {@link Closeable} object
     */
    @SuppressWarnings("PMD.EmptyCatchBlock")
    public static void closeQuietly(final Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (final IOException ioe) {
            // ignore
        }
    }

    /**
     * Closes the stream of a {@link ContentStream} object and ignores any
     * exceptions.
     * 
     * @param contentStream
     *            the content stream
     */
    public static void closeQuietly(final ContentStream contentStream) {
        if (contentStream != null) {
            closeQuietly(contentStream.getStream());
        }
    }

    /**
     * Consumes and closes the provided stream.
     * 
     * @param stream
     *            the stream
     */
    @SuppressWarnings({ "PMD.EmptyCatchBlock", "PMD.EmptyWhileStmt" })
    public static void consumeAndClose(final InputStream stream) {
        if (stream == null) {
            return;
        }

        try {
            final byte[] buffer = new byte[64 * 1024];
            while (stream.read(buffer) > -1) {
                // just consume
            }
        } catch (IOException e) {
            // ignore
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    /**
     * Consumes and closes the provided reader.
     * 
     * @param reader
     *            the reader
     */
    @SuppressWarnings({ "PMD.EmptyCatchBlock", "PMD.EmptyWhileStmt" })
    public static void consumeAndClose(final Reader reader) {
        if (reader == null) {
            return;
        }

        try {
            final char[] buffer = new char[64 * 1024];
            while (reader.read(buffer) > -1) {
                // just consume
            }
        } catch (IOException e) {
            // ignore
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    /**
     * Copies all bytes of an input stream to an output stream.
     * 
     * Neither the input stream nor the output stream will the closed after the
     * copy.
     * 
     * @param in
     *            the input stream, must not be {@code null}
     * @param out
     *            the output stream, must not be {@code null}
     */
    public static void copy(InputStream in, OutputStream out) throws IOException {
        copy(in, out, 64 * 1024);
    }

    /**
     * Copies all bytes of an input stream to an output stream.
     * 
     * Neither the input stream nor the output stream will the closed after the
     * copy.
     * 
     * @param in
     *            the input stream, must not be {@code null}
     * @param out
     *            the output stream, must not be {@code null}
     * @param bufferSize
     *            the size of the internal buffer, must be positive
     */
    public static void copy(InputStream in, OutputStream out, int bufferSize) throws IOException {
        assert in != null;
        assert out != null;
        assert bufferSize > 0;

        int b;
        byte[] buffer = new byte[bufferSize];

        while ((b = in.read(buffer)) > -1) {
            out.write(buffer, 0, b);
        }
    }

    /**
     * Reads lines from an UTF-8 encoded stream and closes the stream.
     * 
     * @param stream
     *            the stream
     * @param handler
     *            a handler the processes each line.
     * @param maxLines
     *            maximum number of lines or -1 for unlimited number of lines
     */
    public static void readLinesFromStream(InputStream stream, LineHandler handler, int maxLines) throws IOException {
        if (stream == null) {
            return;
        }

        int counter = 0;

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(stream, UTF8));

            String line;
            while ((line = reader.readLine()) != null) {
                if (maxLines > -1 && counter == maxLines) {
                    break;
                }
                counter++;

                if (!handler.handle(line)) {
                    break;
                }
            }
        } finally {
            closeQuietly(reader);
        }
    }

    /**
     * Reads the first line from a stream and closes the stream.
     * 
     * @param stream
     *            the input stream
     */
    public static String readFirstLine(InputStream stream) throws IOException {
        final StringBuilder result = new StringBuilder(128);

        readLinesFromStream(stream, new LineHandler() {
            @Override
            public boolean handle(String line) {
                result.append(line);
                return false;
            }
        }, 1);

        return result.toString();
    }

    /**
     * Reads all lines from a stream and closes the stream.
     * 
     * @param stream
     *            the input stream
     */
    public static String readAllLines(InputStream stream) throws IOException {
        return readAllLines(stream, Integer.MAX_VALUE);
    }

    public static String readAllLines(InputStream stream, int maxLines) throws IOException {
        final StringBuilder result = new StringBuilder(1024);

        readLinesFromStream(stream, new LineHandler() {
            @Override
            public boolean handle(String line) {
                result.append(line);
                result.append('\n');
                return true;
            }
        }, maxLines);

        return result.toString();
    }

    /**
     * Reads all lines from a stream, removes the header, and closes the stream.
     * 
     * @param stream
     *            the input stream
     */
    public static String readAllLinesAndRemoveHeader(InputStream stream) throws IOException {
        return readAllLinesAndRemoveHeader(stream, Integer.MAX_VALUE);
    }

    public static String readAllLinesAndRemoveHeader(InputStream stream, int maxLines) throws IOException {
        final StringBuilder result = new StringBuilder(1024);

        readLinesFromStream(stream, new SkipHeaderLineHandler() {
            @Override
            public boolean handle(String line) {
                if (!isHeaderLine(line)) {
                    result.append(line);
                    result.append('\n');
                }
                return true;
            }
        }, maxLines);

        return result.toString();
    }

    /**
     * Reads all lines from a stream, ignores all comments, and closes the
     * stream.
     * 
     * @param stream
     *            the input stream
     */
    public static String readAllLinesAndIgnoreComments(InputStream stream) throws IOException {
        return readAllLinesAndIgnoreComments(stream, Integer.MAX_VALUE);
    }

    public static String readAllLinesAndIgnoreComments(InputStream stream, int maxLines) throws IOException {
        final StringBuilder result = new StringBuilder(1024);

        readLinesFromStream(stream, new IgnoreCommentsLineHandler() {
            @Override
            public boolean handle(String line) {
                if (!isComment(line)) {
                    result.append(line);
                    result.append('\n');
                }
                return true;
            }
        }, maxLines);

        return result.toString();
    }

    /**
     * Reads all lines from a stream, ignores all comments, and closes the
     * stream.
     * 
     * @param stream
     *            the input stream
     */
    public static List<String> readAllLinesAsList(InputStream stream) throws IOException {
        return readAllLinesAsList(stream, Integer.MAX_VALUE);
    }

    public static List<String> readAllLinesAsList(InputStream stream, int maxLines) throws IOException {
        final List<String> result = new ArrayList<String>();

        readLinesFromStream(stream, new IgnoreCommentsLineHandler() {
            @Override
            public boolean handle(String line) {
                if (!isComment(line)) {
                    result.add(line);
                }
                return true;
            }
        }, maxLines);

        return result;
    }

    /**
     * Reads all lines from a stream, ignores all comments, and closes the
     * stream.
     * 
     * @param stream
     *            the input stream
     */
    public static Map<String, String> readAllLinesAsMap(InputStream stream) throws IOException {
        return readAllLinesAsMap(stream, Integer.MAX_VALUE);
    }

    public static Map<String, String> readAllLinesAsMap(InputStream stream, int maxLines) throws IOException {
        final Map<String, String> result = new HashMap<String, String>();

        readLinesFromStream(stream, new IgnoreCommentsLineHandler() {
            @Override
            public boolean handle(String line) {
                if (!isComment(line)) {
                    int x = line.indexOf('=');
                    if (x < 0) {
                        result.put(line.trim(), "");
                    } else {
                        result.put(line.substring(0, x).trim(), line.substring(x + 1).trim());
                    }
                }
                return true;
            }
        }, maxLines);

        return result;
    }

    public interface LineHandler {
        /**
         * Handles a line.
         * 
         * @param line
         *            the line to handle
         * 
         * @return <code>true</code> if the next line should be processed,
         *         <code>false</code> if the processing should stop.
         */
        boolean handle(String line);
    }

    public abstract static class SkipHeaderLineHandler implements LineHandler {

        private boolean header = true;

        public boolean isHeaderLine(String line) {
            if (!header) {
                return false;
            }

            String trim = line.trim();
            if (trim.length() == 0) {
                header = false;
                return true;
            }

            char c = trim.charAt(0);
            return (c == '/') || (c == '*') || (c == '#');
        }
    }

    public abstract static class IgnoreCommentsLineHandler implements LineHandler {

        public boolean isComment(String line) {
            String trim = line.trim();
            if (trim.length() == 0) {
                return true;
            }

            return trim.charAt(0) == '#';
        }
    }
}
