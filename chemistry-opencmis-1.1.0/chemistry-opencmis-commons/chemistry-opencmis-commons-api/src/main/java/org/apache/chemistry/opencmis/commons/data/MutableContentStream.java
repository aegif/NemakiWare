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
package org.apache.chemistry.opencmis.commons.data;

import java.io.InputStream;
import java.math.BigInteger;

/**
 * Mutable ContentStream.
 */
public interface MutableContentStream extends ContentStream {

    /**
     * Sets the file name.
     * 
     * @param filename
     *            the file name
     */
    void setFileName(String filename);

    /**
     * Sets the length of the stream.
     * 
     * @param length
     *            the length of the stream in bytes or {@code null} if the
     *            length is unknown
     */
    void setLength(BigInteger length);

    /**
     * Sets the MIME type of the stream.
     * 
     * @param mimeType
     *            the MIME type
     */
    void setMimeType(String mimeType);

    /**
     * Sets the stream.
     * 
     * @param stream
     *            the stream
     */
    void setStream(InputStream stream);
}
