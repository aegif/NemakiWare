/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.commons.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Subclasses of this class are used to store document content for a short
 * period of time.
 * 
 * The OpenCMIS server framework creates such a new stream when it needs to
 * offload document content for a moment. That can happen when a document is
 * created, new content is set for an existing document, or a new document
 * version is created. How and where subclasses of this output stream store the
 * content is not specified.
 * 
 * The {@link #getInputStream()} and {@link #getLength()} methods are not called
 * before all bytes have been written to this output stream and the
 * {@link #close()} method has been called. The {@link #destroy(Throwable)}
 * method can be called at any time and indicates that this stream is not needed
 * anymore and attached resources can be cleaned up.
 */
public abstract class TempStoreOutputStream extends OutputStream {

    /**
     * Sets the MIME type of the stream.
     * 
     * This method is usually be called once before {@link #getInputStream()} is
     * called. It might never be called if the MIME type is unknown or multiple
     * times if previous MIME type detections were inaccurate.
     * 
     * @param mimeType
     *            the MIME type or {@code null} if the MIME type is unknown or
     *            should be reset to unknown
     */
    public abstract void setMimeType(String mimeType);

    /**
     * Sets the file name.
     * 
     * This method is usually be called once before {@link #getInputStream()} is
     * called. It might never be called if the file name is unknown.
     * 
     * @param filename
     *            the file name or {@code null} if the file name is unknown or
     *            should be reset to unknown
     */
    public abstract void setFileName(String filename);

    /**
     * Returns an {@link InputStream} that serves the content that has been
     * provided to this {@link TempStoreOutputStream} instance.
     * 
     * If this method is called multiple times, the same {@link InputStream}
     * object must be returned.
     * 
     * Implementations should clean up all attached resources when this stream
     * is closed.
     * 
     * @return the input stream
     * 
     * @throws IOException
     *             if the input stream could not be created
     */
    public abstract InputStream getInputStream() throws IOException;

    /**
     * Returns the length of the stream in bytes.
     * 
     * @return the length of the stream
     */
    public abstract long getLength();

    /**
     * This method is called if the stream has to be released before it is fully
     * written or read.
     * 
     * Implementations should clean up all attached resources.
     * 
     * @param cause
     *            the throwable that caused the call of this method or
     *            {@code null} if no throwable object is available
     */
    public abstract void destroy(Throwable cause);
}
