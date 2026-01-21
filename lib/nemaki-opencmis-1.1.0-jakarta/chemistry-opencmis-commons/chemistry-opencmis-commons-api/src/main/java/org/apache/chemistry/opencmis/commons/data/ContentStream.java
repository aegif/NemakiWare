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
 * Content stream.
 */
public interface ContentStream extends ExtensionsData {

    /**
     * Returns the length of the stream.
     * 
     * @return the length of the stream in bytes or -1 if the length is unknown
     */
    long getLength();

    /**
     * Returns the length of the stream.
     * 
     * @return the length of the stream in bytes or {@code null} if the length
     *         is unknown
     */
    BigInteger getBigLength();

    /**
     * Returns the MIME type of the stream.
     * 
     * @return the MIME type of the stream or {@code null} if the MIME type is
     *         unknown
     */
    String getMimeType();

    /**
     * Returns the file name of the stream.
     * 
     * @return the file name of the stream or {@code null} if the file name is
     *         unknown
     */
    String getFileName();

    /**
     * Returns the stream.
     * <p>
     * It is important to close this stream properly!
     * 
     * @return the stream
     */
    InputStream getStream();
}
