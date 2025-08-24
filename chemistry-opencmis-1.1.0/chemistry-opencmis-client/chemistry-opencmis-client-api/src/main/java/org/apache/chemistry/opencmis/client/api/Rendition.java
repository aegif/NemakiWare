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
package org.apache.chemistry.opencmis.client.api;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RenditionData;

/**
 * Rendition.
 * 
 * @cmis 1.0
 */
public interface Rendition extends RenditionData {

    /**
     * Returns the size of the rendition in bytes if available.
     * 
     * @return the size of the rendition in bytes or -1 if the size is not
     *         available
     */
    long getLength();

    /**
     * Returns the height in pixels if the rendition is an image.
     * 
     * @return the height in pixels or -1 if the height is not available or the
     *         rendition is not an image
     */
    long getHeight();

    /**
     * Returns the width in pixels if the rendition is an image.
     * 
     * @return the width in pixels or -1 if the width is not available or the
     *         rendition is not an image
     */
    long getWidth();

    /**
     * Returns the rendition document if the rendition is a stand-alone
     * document.
     * 
     * @return the rendition document or {@code null} if there is no rendition
     *         document
     */
    Document getRenditionDocument();

    /**
     * Returns the rendition document using the provided
     * {@link OperationContext} if the rendition is a stand-alone document.
     * 
     * @return the rendition document or {@code null} if there is no rendition
     *         document
     */
    Document getRenditionDocument(OperationContext context);

    /**
     * Returns the content stream of the rendition.
     * 
     * @return the content stream of the rendition or {@code null} if the
     *         rendition has no content
     */
    ContentStream getContentStream();

    /**
     * Returns the content URL of the rendition if the binding supports content
     * URLs.
     * 
     * Depending on the repository and the binding, the server might not return
     * the content but an error message. Authentication data is not attached.
     * That is, a user may have to re-authenticate to get the content.
     * 
     * @return the content URL of the rendition or {@code null} if the binding
     *         does not support content URLs
     */
    String getContentUrl();
}
