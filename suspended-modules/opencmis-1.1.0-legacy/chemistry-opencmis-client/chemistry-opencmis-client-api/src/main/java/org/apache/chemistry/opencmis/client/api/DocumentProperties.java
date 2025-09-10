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

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ContentStreamHash;

/**
 * Accessors to CMIS document properties.
 * 
 * @see CmisObjectProperties
 */
public interface DocumentProperties {

    /**
     * Returns {@code true} if this document is immutable (CMIS property
     * {@code cmis:isImmutable}).
     * 
     * @return the immutable flag of the document or {@code null} if the
     *         property hasn't been requested, hasn't been provided by the
     *         repository, or the property value isn't set
     * 
     * @cmis 1.0
     */
    Boolean isImmutable();

    /**
     * Returns {@code true} if this document is the latest version (CMIS
     * property {@code cmis:isLatestVersion}).
     * 
     * @return the latest version flag of the document or {@code null} if the
     *         property hasn't been requested, hasn't been provided by the
     *         repository, or the property value isn't set
     * 
     * @cmis 1.0
     */
    Boolean isLatestVersion();

    /**
     * Returns {@code true} if this document is a major version (CMIS property
     * {@code cmis:isMajorVersion}).
     * 
     * @return the major version flag of the document or {@code null} if the
     *         property hasn't been requested, hasn't been provided by the
     *         repository, or the property value isn't set
     * 
     * @cmis 1.0
     */
    Boolean isMajorVersion();

    /**
     * Returns {@code true} if this document is the latest major version (CMIS
     * property {@code cmis:isLatestMajorVersion}).
     * 
     * @return the latest major version flag of the document or {@code null} if
     *         the property hasn't been requested, hasn't been provided by the
     *         repository, or the property value isn't set
     * 
     * @cmis 1.0
     */
    Boolean isLatestMajorVersion();

    /**
     * Returns {@code true} if this document is the PWC (CMIS property
     * {@code cmis:isPrivateWorkingCopy}).
     * 
     * @return the PWC flag of the document or {@code null} if the property
     *         hasn't been requested, hasn't been provided by the repository, or
     *         the property value isn't set
     * 
     * @see Document#isVersionSeriesPrivateWorkingCopy()
     * 
     * @cmis 1.1
     */
    Boolean isPrivateWorkingCopy();

    /**
     * Returns the version label (CMIS property {@code cmis:versionLabel}).
     * 
     * @return the version label of the document or {@code null} if the property
     *         hasn't been requested, hasn't been provided by the repository, or
     *         the property value isn't set
     * 
     * @cmis 1.0
     */
    String getVersionLabel();

    /**
     * Returns the version series ID (CMIS property {@code cmis:versionSeriesId}
     * ).
     * 
     * @return the version series ID of the document or {@code null} if the
     *         property hasn't been requested, hasn't been provided by the
     *         repository, or the property value isn't set
     * 
     * @cmis 1.0
     */
    String getVersionSeriesId();

    /**
     * Returns {@code true} if this version series is checked out (CMIS property
     * {@code cmis:isVersionSeriesCheckedOut}).
     * 
     * @return the version series checked out flag of the document or
     *         {@code null} if the property hasn't been requested, hasn't been
     *         provided by the repository, or the property value isn't set
     * 
     * @cmis 1.0
     */
    Boolean isVersionSeriesCheckedOut();

    /**
     * Returns the user who checked out this version series (CMIS property
     * {@code cmis:versionSeriesCheckedOutBy}).
     * 
     * @return the user who checked out this version series or {@code null} if
     *         the property hasn't been requested, hasn't been provided by the
     *         repository, or the property value isn't set
     * 
     * @cmis 1.0
     */
    String getVersionSeriesCheckedOutBy();

    /**
     * Returns the PWC ID of this version series (CMIS property
     * {@code cmis:versionSeriesCheckedOutId}).
     * <p>
     * Some repositories provided this value only to the user who checked out
     * the version series.
     * 
     * @return the PWC ID of this version series or {@code null} if the property
     *         hasn't been requested, hasn't been provided by the repository, or
     *         the property value isn't set
     * 
     * @cmis 1.0
     */
    String getVersionSeriesCheckedOutId();

    /**
     * Returns the checkin comment (CMIS property {@code cmis:checkinComment}).
     * 
     * @return the checkin comment of this version or {@code null} if the
     *         property hasn't been requested, hasn't been provided by the
     *         repository, or the property value isn't set
     * 
     * @cmis 1.0
     */
    String getCheckinComment();

    /**
     * Returns the content stream length or -1 if the document has no content
     * (CMIS property {@code cmis:contentStreamLength}).
     * 
     * @return the content stream length of this document or -1 if the property
     *         hasn't been requested, hasn't been provided by the repository, or
     *         the document has no content
     * 
     * @cmis 1.0
     */
    long getContentStreamLength();

    /**
     * Returns the content stream MIME type or {@code null} if the document has
     * no content (CMIS property {@code cmis:contentStreamMimeType}).
     * 
     * @return the content stream MIME type of this document or {@code null} if
     *         the property hasn't been requested, hasn't been provided by the
     *         repository, or the document has no content
     * 
     * @cmis 1.0
     */
    String getContentStreamMimeType();

    /**
     * Returns the content stream filename or {@code null} if the document has
     * no content (CMIS property {@code cmis:contentStreamFileName}).
     * 
     * @return the content stream filename of this document or {@code null} if
     *         the property hasn't been requested, hasn't been provided by the
     *         repository, or the document has no content
     * @cmis 1.0
     */
    String getContentStreamFileName();

    /**
     * Returns the content stream ID or {@code null} if the document has no
     * content (CMIS property {@code cmis:contentStreamId}).
     * 
     * @return the content stream ID of this document or {@code null} if the
     *         property hasn't been requested, hasn't been provided by the
     *         repository, or the document has no content
     * 
     * @cmis 1.0
     */
    String getContentStreamId();

    /**
     * Returns the content hashes or {@code null} if the document has no content
     * (CMIS property {@code cmis:contentStreamHash}).
     * 
     * @return the list of content hashes or {@code null} if the property hasn't
     *         been requested, hasn't been provided by the repository, or the
     *         document has no content
     * 
     * @cmis Extension
     */
    List<ContentStreamHash> getContentStreamHashes();

    /**
     * Returns the latest accessible state ID or {@code null} if the repository
     * does not support the Latest State Identifier feature extension (CMIS
     * property {@code cmis:latestAccessibleStateId }).
     * 
     * @return the latest accessible state ID or {@code null}
     * 
     * @cmis Extension
     */
    String getLatestAccessibleStateId();
}
