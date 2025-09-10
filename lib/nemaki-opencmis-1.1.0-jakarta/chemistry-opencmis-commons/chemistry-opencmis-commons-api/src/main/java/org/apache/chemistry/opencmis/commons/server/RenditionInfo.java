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
package org.apache.chemistry.opencmis.commons.server;

import java.math.BigInteger;

/**
 * Contains information about a rendition of an object. This data is used to
 * generate the appropriate links in AtomPub entries and feeds.
 */
public interface RenditionInfo {

    /**
     * Return the ID of the rendition.
     * 
     * @return the rendition ID, not {@code null}
     */
    String getId();

    /**
     * Return the content type of the rendition.
     * 
     * @return the MIME type, should not be {@code null}
     */
    String getContenType();

    /**
     * Return the kind of the rendition.
     * 
     * @return the rendition kind, may be {@code null}
     */
    String getKind();

    /**
     * Return the title of the rendition.
     * 
     * @return the rendition title, may be {@code null}
     */
    String getTitle();

    /**
     * Return the size of the rendition in bytes.
     * 
     * @return the rendition size in bytes, may be {@code null} if unknown
     */
    BigInteger getLength();
}
