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

import java.math.BigInteger;

/**
 * Represents a rendition.
 * 
 * @cmis 1.0
 */
public interface RenditionData extends ExtensionsData {

    /**
     * Returns the stream ID of the rendition.
     * <p>
     * The stream ID is required to fetch the content of the rendition.
     * 
     * @return the stream ID, not {@code null}
     */
    String getStreamId();

    /**
     * Returns the MIME type of the rendition.
     * 
     * @return the MIME type, should not be {@code null}
     */
    String getMimeType();

    /**
     * Returns the size of the rendition in bytes, if available.
     * 
     * @return the size of the rendition in bytes, may be {@code null}
     */
    BigInteger getBigLength();

    /**
     * Returns the kind of the rendition.
     * <p>
     * The CMIS specification only defines the kind {@code cmis:thumbnail}, but
     * a repository can provide other kinds.
     * 
     * @return the rendition kind, may be {@code null}
     */
    String getKind();

    /**
     * Returns the title of the rendition.
     * 
     * @return the rendition title, may be {@code null}
     */
    String getTitle();

    /**
     * Returns the height in pixels, if the rendition is an image.
     * 
     * @return the height in pixels, may be {@code null}
     */
    BigInteger getBigHeight();

    /**
     * Returns the width in pixels, if the rendition is an image.
     * 
     * @return the width in pixels, may be {@code null}
     */
    BigInteger getBigWidth();

    /**
     * Returns the object id of the rendition document if the rendition is a
     * stand-alone document.
     * 
     * @return the rendition document ID, may be {@code null}
     */
    String getRenditionDocumentId();
}
