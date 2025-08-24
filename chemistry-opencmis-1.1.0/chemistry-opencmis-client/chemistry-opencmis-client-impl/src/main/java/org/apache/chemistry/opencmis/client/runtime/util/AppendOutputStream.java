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
package org.apache.chemistry.opencmis.client.runtime.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.SequenceInputStream;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.util.ContentStreamUtils;
import org.apache.chemistry.opencmis.client.util.OperationContextUtils;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * This OutputStream allows overwriting and appending to the content of a
 * document.
 * 
 * This class is a convenience layer on top of the CMIS setContentStream() and
 * appendContentStream() operations. It provides the glue to other (legacy)
 * interfaces that know how to work with standard streams. However, calling
 * setContentStream() and appendContentStream() directly provides more control
 * and may be more efficient than using this class.
 * 
 * Data written to this stream is buffered. The default buffer size is 64 KiB.
 * The data is send to the repository if the buffer is full, or {@link #flush()}
 * is called, or {@link #close()} is called. Because sending data to the
 * repository requires a HTTP call, this should be triggered only when
 * necessary. Depending on the use case, the buffer size should be increased.
 * 
 * If the overwrite mode is enabled, the first call is a setContentStream()
 * call, which overwrites the existing content. All following calls are
 * appendContentStream() calls. If the overwrite mode is not enabled, all calls
 * are appendContentStream() calls.
 * 
 * If the document is versioned, it's the responsibility of the caller to check
 * it out and check it in. If the document is auto-versioned, each chunk of
 * bytes may create a new version.
 * 
 * If the repository supports change tokens and the provided document has a
 * non-empty change token property ({@code cmis:changeToken}), change tokens are
 * respected.
 * 
 * This class is not thread safe.
 */
public class AppendOutputStream extends OutputStream {

    public final static OperationContext DOCUMENT_OPERATION_CONTEXT = OperationContextUtils
            .createMinimumOperationContext("cmis:contentStreamFileName", "cmis:contentStreamMimeType",
                    "cmis:changeToken");

    private final static int DEFAULT_BUFFER_SIZE = 64 * 1024;

    private Session session;
    private String repId;
    private String documentId;
    private String changeToken;
    private boolean overwrite;
    private String filename;
    private String mimeType;
    private byte[] buffer;
    private int pos;
    private boolean isClosed;

    public AppendOutputStream(Session session, Document doc, boolean overwrite, String filename, String mimeType) {
        this(session, doc, overwrite, filename, mimeType, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates an OutputStream that appends content to a document.
     * 
     * @param session
     *            the session object, must not be {@code null}
     * @param doc
     *            the document, must not be {@code null}
     * @param overwrite
     *            if {@code true} the first call to repository sets a new
     *            content, if {@code false} the all calls append to the current
     *            content
     * @param filename
     *            the file name, may be {@code null}
     * @param mimeType
     *            the MIME type, may be {@code null}
     * @param bufferSize
     *            buffer size
     */
    public AppendOutputStream(Session session, Document doc, boolean overwrite, String filename, String mimeType,
            int bufferSize) {
        if (session == null) {
            throw new IllegalArgumentException("Session must be set!");
        }
        if (doc == null) {
            throw new IllegalArgumentException("Document must be set!");
        }
        if (bufferSize < 0) {
            throw new IllegalArgumentException("Buffer size must be positive!");
        }

        if (filename == null) {
            this.filename = doc.getContentStreamFileName();
        } else {
            this.filename = filename;
        }

        if (mimeType == null) {
            this.mimeType = doc.getContentStreamMimeType();
        } else {
            this.mimeType = mimeType;
        }

        this.session = session;
        this.repId = session.getRepositoryInfo().getId();
        this.documentId = doc.getId();
        this.changeToken = doc.getChangeToken();
        this.overwrite = overwrite;
        this.buffer = new byte[bufferSize];
        this.pos = 0;
        this.isClosed = false;
    }

    @Override
    public void write(int b) throws IOException {
        if (isClosed) {
            throw new IOException("Stream is already closed!");
        }

        if (pos + 1 > buffer.length) {
            // not enough space in the buffer for the additional byte
            // -> write buffer and the byte
            send(new byte[] { (byte) (b & 0xFF) }, 0, 1, false);
        } else {
            buffer[pos++] = (byte) (b & 0xFF);
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (isClosed) {
            throw new IOException("Stream is already closed!");
        }

        if (b == null) {
            throw new IllegalArgumentException("Data must not be null!");
        } else if (off < 0 || off > b.length) {
            throw new IndexOutOfBoundsException("Invalid offset!");
        } else if (len < 0 || (off + len) > b.length || (off + len) < 0) {
            throw new IndexOutOfBoundsException("Invalid length!");
        } else if (len == 0) {
            return;
        }

        if (pos + len > buffer.length) {
            // not enough space in the buffer for the additional bytes
            // -> write buffer and bytes
            send(b, off, len, false);
        } else {
            System.arraycopy(b, off, buffer, pos, len);
            pos += len;
        }
    }

    @Override
    public void flush() throws IOException {
        flush(false);
    }

    /**
     * Appends (or sets) the current buffer to the document.
     * 
     * @param isLastChunk
     *            indicates if this is the last chunk of the content
     * @throws IOException
     *             if an error occurs
     */
    public void flush(boolean isLastChunk) throws IOException {
        if (isClosed) {
            throw new IOException("Stream is already closed!");
        }

        send(isLastChunk);
    }

    /**
     * Updates the document content with the provided content stream.
     */
    protected void send(boolean isLastChunk) throws IOException {
        send(null, 0, 0, isLastChunk);
    }

    /**
     * Updates the document content with the provided content stream.
     */
    protected void send(byte[] extraBytes, int extraOff, int extraLen, boolean isLastChunk) throws IOException {

        ContentStream contentStream = null;
        if (extraBytes == null) {
            if (pos == 0) {
                // buffer is empty and no extra bytes -> nothing to do
                return;
            } else {
                // buffer is not empty and no extra bytes -> send buffer
                contentStream = ContentStreamUtils.createByteArrayContentStream(filename, buffer, 0, pos, mimeType);
            }
        } else {
            if (pos == 0) {
                // buffer is empty but we have extra bytes
                // -> only send extra bytes
                contentStream = ContentStreamUtils.createByteArrayContentStream(filename, extraBytes, extraOff,
                        extraLen, mimeType);
            } else {
                // buffer is not empty and we have extra bytes
                // -> send buffer and extra bytes
                contentStream = ContentStreamUtils.createContentStream(filename, pos + extraLen, mimeType,
                        new ContentStreamUtils.AutoCloseInputStream(new SequenceInputStream(new ByteArrayInputStream(
                                buffer, 0, pos), new ByteArrayInputStream(extraBytes, extraOff, extraLen))));
            }
        }

        Holder<String> objectIdHolder = new Holder<String>(documentId);
        Holder<String> changeTokenHolder = changeToken != null ? new Holder<String>(changeToken) : null;

        try {
            if (overwrite) {
                // start a new content stream
                session.getBinding()
                        .getObjectService()
                        .setContentStream(repId, objectIdHolder, Boolean.TRUE, changeTokenHolder,
                                session.getObjectFactory().convertContentStream(contentStream), null);

                // the following calls should append, not overwrite
                overwrite = false;
            } else {
                // append to content stream
                session.getBinding()
                        .getObjectService()
                        .appendContentStream(repId, objectIdHolder, changeTokenHolder,
                                session.getObjectFactory().convertContentStream(contentStream), isLastChunk, null);
            }
        } catch (Exception e) {
            isClosed = true;
            throw new IOException("Could not append to document: " + e.toString(), e);
        }

        if (objectIdHolder.getValue() != null) {
            documentId = objectIdHolder.getValue();
        }
        if (changeTokenHolder != null) {
            changeToken = changeTokenHolder.getValue();
        }

        pos = 0;
    }

    @Override
    public void close() throws IOException {
        close(true);
    }

    /**
     * Closes the stream.
     * 
     * @param isLastChunk
     *            indicates if this is the last chunk of the content
     * @throws IOException
     *             if an error occurs
     */
    public void close(boolean isLastChunk) throws IOException {
        if (isClosed) {
            throw new IOException("Stream is already closed!");
        }

        if (pos > 0) {
            flush(isLastChunk);
        }
    }
}
