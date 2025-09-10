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
package org.apache.chemistry.opencmis.client.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.MutableContentStream;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.MimeTypes;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;

/**
 * Methods to create {@link ContentStream} objects.
 */
public final class ContentStreamUtils {

    private static final String OCTETSTREAM = "application/octet-stream";

    private ContentStreamUtils() {
    }

    // --- generic ---
    /**
     * Creates a content stream object for an InputStream.
     *
     * @param filename
     *            name of the content stream
     * @param length
     *            length of the stream in bytes
     * @param mimetype
     *            content MIME type
     * @param stream
     *            the InputStream
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createContentStream(String filename, long length, String mimetype,
            InputStream stream) {
        return createContentStream(filename, length < 0 ? null : BigInteger.valueOf(length), mimetype, stream);
    }

    /**
     * Creates a content stream object for an InputStream.
     *
     * @param filename
     *            name of the content stream
     * @param length
     *            length of the stream in bytes
     * @param mimetype
     *            content MIME type
     * @param stream
     *            the InputStream
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createContentStream(String filename, BigInteger length, String mimetype,
            InputStream stream) {
        return new ContentStreamImpl(checkFilename(filename), length, checkMIMEType(mimetype), stream);
    }

    // --- byte arrays ---
    /**
     * Creates a content stream object from a byte array.
     *
     * The MIME type is set to "application/octet-stream".
     *
     * @param filename
     *            name of the content stream
     * @param contentBytes
     *            content bytes
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createByteArrayContentStream(String filename, byte[] contentBytes) {
        return createByteArrayContentStream(filename, contentBytes, OCTETSTREAM);
    }

    /**
     * Creates a content stream object from a byte array.
     *
     * @param filename
     *            name of the content stream
     * @param contentBytes
     *            the content bytes
     * @param mimetype
     *            content MIME type
     *
     * @return a {@link MutableContentStream} object
     *
     */
    public static MutableContentStream createByteArrayContentStream(String filename, byte[] contentBytes,
            String mimetype) {
        if (contentBytes == null) {
            return createContentStream(filename, null, mimetype, null);
        }

        return createByteArrayContentStream(filename, contentBytes, 0, contentBytes.length, mimetype);
    }

    /**
     * Creates a content stream object from a byte array.
     *
     * @param filename
     *            name of the content stream
     * @param contentBytes
     *            the content bytes
     * @param offset
     *            the offset in the content bytes
     * @param length
     *            the maximum number of bytes to read from the content bytes
     * @param mimetype
     *            content MIME type
     *
     * @return a {@link MutableContentStream} object
     *
     */
    public static MutableContentStream createByteArrayContentStream(String filename, byte[] contentBytes, int offset,
            int length, String mimetype) {
        if (contentBytes == null) {
            return createContentStream(filename, null, mimetype, null);
        }

        if (offset < 0 || offset > contentBytes.length) {
            throw new IndexOutOfBoundsException("Invalid offset!");
        } else if (length < 0 || (offset + length) > contentBytes.length || (offset + length) < 0) {
            throw new IndexOutOfBoundsException("Invalid length!");
        }

        return createContentStream(filename, length, mimetype, new AutoCloseInputStream(new ByteArrayInputStream(
                contentBytes, offset, length)));
    }

    // --- strings ---
    /**
     * Creates a content stream object from a string.
     *
     * The MIME type is set to "text/plain".
     *
     * @param filename
     *            name of the content stream
     * @param content
     *            the content string
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createTextContentStream(String filename, String content) {
        return createTextContentStream(filename, content, "text/plain; charset=UTF-8");
    }

    /**
     * Creates a content stream object from a string.
     *
     * @param filename
     *            name of the content stream
     * @param content
     *            the content string
     * @param mimetype
     *            content MIME type
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createTextContentStream(String filename, String content, String mimetype) {
        byte[] contentBytes = IOUtils.toUTF8Bytes(content);
        return createByteArrayContentStream(filename, contentBytes, checkMIMEType(mimetype));
    }

    // --- files ---
    /**
     * Creates a content stream object from file.
     *
     * The MIME type is guessed from the file name and the content.
     *
     * @param file
     *            the file
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createFileContentStream(File file) throws FileNotFoundException {
        return createFileContentStream(file.getName(), file, MimeTypes.getMIMEType(file));
    }

    /**
     * Creates a content stream object from file.
     *
     * The MIME type is guessed from the file name and the content.
     *
     * @param filename
     *            name of the content stream
     * @param file
     *            the file
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createFileContentStream(String filename, File file) throws FileNotFoundException {
        return createFileContentStream(filename, file, MimeTypes.getMIMEType(file));
    }

    /**
     * Creates a content stream object from file.
     *
     * @param file
     *            the file
     * @param mimetype
     *            content MIME type
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createFileContentStream(File file, String mimetype) throws FileNotFoundException {
        return createFileContentStream(file.getName(), file, mimetype);
    }

    /**
     * Creates a content stream object from file.
     *
     * @param filename
     *            name of the content stream
     * @param file
     *            the file
     * @param mimetype
     *            content MIME type
     *
     * @return a {@link MutableContentStream} object
     */
    public static MutableContentStream createFileContentStream(String filename, File file, String mimetype)
            throws FileNotFoundException {
        return createContentStream(filename, file.length(), mimetype, new AutoCloseInputStream(new BufferedInputStream(
                new FileInputStream(file))));
    }

    // --- write ---

    /**
     * Writes a content stream to an output stream.
     * 
     * If the content stream is {@code null} or the stream itself is
     * {@code null}, nothing is written to the output stream. The content stream
     * is closed at the end. The output stream remains open.
     * 
     * @param contentStream
     *            the content stream, may be {@code null}
     * @param outputStream
     *            the output stream, not {@code null}
     */
    public static void writeContentStreamToOutputStream(ContentStream contentStream, OutputStream outputStream)
            throws IOException {
        if (outputStream == null) {
            throw new IllegalArgumentException("Output stream is null!");
        }

        if (contentStream == null || contentStream.getStream() == null) {
            return;
        }

        try {
            IOUtils.copy(contentStream.getStream(), outputStream);
        } finally {
            IOUtils.closeQuietly(contentStream);
        }
    }

    /**
     * Writes a content stream to a file.
     * 
     * If the content stream is {@code null} or the stream itself is
     * {@code null}, the file is empty. The content stream and the file are
     * closed at the end.
     * 
     * @param contentStream
     *            the content stream, may be {@code null}
     * @param file
     *            the file, not {@code null}
     */
    public static void writeContentStreamToFile(ContentStream contentStream, File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("File is null!");
        }

        OutputStream fileStream = new BufferedOutputStream(new FileOutputStream(file));
        try {
            writeContentStreamToOutputStream(contentStream, fileStream);
            fileStream.flush();
        } finally {
            IOUtils.closeQuietly(fileStream);
        }
    }

    // --- helpers ---
    private static String checkFilename(String filename) {
        if (filename == null || filename.length() == 0) {
            return "content";
        }

        return filename;
    }

    private static String checkMIMEType(String mimetype) {
        if (mimetype == null) {
            return OCTETSTREAM;
        }

        String result = mimetype.trim();
        if (result.length() < 3) {
            return OCTETSTREAM;
        }

        return result;
    }

    // --- classes ---
    /**
     * InputStream that gets closed when the end of the stream is reached or the
     * underlying stream throws an exception.
     */
    public static class AutoCloseInputStream extends InputStream {

        protected InputStream stream;

        public AutoCloseInputStream(InputStream in) {
            stream = in;
        }

        @Override
        public int read() throws IOException {
            if (stream != null) {
                int b = -1;

                try {
                    b = stream.read();
                } catch (IOException ioe) {
                    closeQuietly();
                    throw ioe;
                }

                if (b == -1) {
                    close();
                }

                return b;
            } else {
                throw new IOException("Stream is already closed!");
            }
        }

        @Override
        public int read(byte[] b) throws IOException {
            if (stream != null) {
                int l = -1;

                try {
                    l = stream.read(b);
                } catch (IOException ioe) {
                    closeQuietly();
                    throw ioe;
                }

                if (l == -1) {
                    close();
                }

                return l;
            } else {
                throw new IOException("Stream is already closed!");
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (stream != null) {
                int l = -1;

                try {
                    l = stream.read(b, off, len);
                } catch (IOException ioe) {
                    closeQuietly();
                    throw ioe;
                }

                if (l == -1) {
                    close();
                }

                return l;
            } else {
                throw new IOException("Stream is already closed!");
            }
        }

        @Override
        public long skip(long n) throws IOException {
            if (stream != null) {
                try {
                    return stream.skip(n);
                } catch (IOException ioe) {
                    closeQuietly();
                    throw ioe;
                }
            } else {
                throw new IOException("Stream is already closed!");
            }
        }

        @Override
        public int available() throws IOException {
            if (stream != null) {
                try {
                    return stream.available();
                } catch (IOException ioe) {
                    closeQuietly();
                    throw ioe;
                }
            } else {
                throw new IOException("Stream is already closed!");
            }
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                try {
                    stream.close();
                } catch (final IOException ioe) {
                    throw ioe;
                } finally {
                    stream = null;
                }
            }
        }

        public void closeQuietly() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (final IOException ioe) {
                    // ignore
                } finally {
                    stream = null;
                }
            }
        }

        @Override
        public synchronized void mark(int readlimit) {
            if (stream != null) {
                stream.mark(readlimit);
            }
        }

        @Override
        public synchronized void reset() throws IOException {
            if (stream != null) {
                try {
                    stream.reset();
                } catch (IOException ioe) {
                    closeQuietly();
                    throw ioe;
                }
            } else {
                throw new IOException("Stream is already closed!");
            }
        }

        @Override
        public boolean markSupported() {
            if (stream != null) {
                return stream.markSupported();
            }

            return false;
        }

        @Override
        protected void finalize() throws Throwable {
            closeQuietly();
            super.finalize();
        }
    }
}
