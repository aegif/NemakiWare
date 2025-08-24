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
package org.apache.chemistry.opencmis.inmemory.storedobj.api;

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;

/**
 * A version series is a concrete object (meaning it can be stored) and has
 * methods for check-out and checkin. It has a path (is contained in a folder)
 * In contrast to a non-versioned document it has no content, but versions
 * instead.
 * 
 */
public interface VersionedDocument extends Filing, StoredObject {

    /**
     * Add a new version to this document.
     * 
     * @param verState
     *            versioning state of new version
     * @param user
     *            user adding the new vesion
     * @return document version added
     */
    DocumentVersion addVersion(VersioningState verState, String user);

    /**
     * Delete a version from this object, throw exception if document is checked
     * out or document does not contain this version.
     * 
     * @param version
     *            version to be removed
     * @return true if version could be removed, and other versions exist, false
     *         if the deleted version was the last version in this document
     */
    boolean deleteVersion(DocumentVersion version);

    /**
     * Test if current object is checked-out.
     * 
     * @return true if checked-out, false if not checked-out
     */
    boolean isCheckedOut();

    /**
     * Cancel a check-out operation and discard the private working copy.
     * 
     * @param user
     *            user doing the cancel check-out
     */
    void cancelCheckOut(String user);

    /**
     * Perform a check-out operation.
     * 
     * @param user
     *            user who checks-out
     * @return document version beinf the new private working copy
     */
    DocumentVersion checkOut(String user);

    /**
     * Check in a private working copy.
     * 
     * @param isMajor
     *            true if this is a major version
     * @param properties
     *            properties to set
     * @param content
     *            content of the document
     * @param checkinComment
     *            comment to attach to check-in
     * @param policyIds
     *            list of policy ids to add
     * @param user
     *            user who does the check-in
     */
    void checkIn(boolean isMajor, Properties properties, ContentStream content, String checkinComment,
            List<String> policyIds, String user);

    /**
     * Get all versions of this document.
     * 
     * @return list of document versions
     */
    List<DocumentVersion> getAllVersions();

    /**
     * Get the latest version of this document.
     * 
     * @param major
     *            true if latest major version, false to include minor versions
     * @return
     *            latest version of the document
     */
    DocumentVersion getLatestVersion(boolean major);

    /**
     * Get the user who has checked out this document.
     * 
     * @return user id of user who has checked out this document
     */
    String getCheckedOutBy();

    /**
     * Get the private working copy of this document.
     * 
     * @return private working copy
     */
    DocumentVersion getPwc();

}
