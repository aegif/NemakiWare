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

/**
 * The interface version adds the version specific functionality to an object.
 * It has minor and major versions, stores a comment and has a label.
 */
public interface Version {

    /**
     * Check if this version is a major version.
     * 
     * @return true if major version, false if it is a minor version
     */
    boolean isMajor();

    /**
     * Check if this version is a private working copy.
     * 
     * @return true if it a PWC, false if not
     */
    boolean isPwc();

    /**
     * make the private working copy an official version.
     * 
     * @param isMajor
     *            true if major version, false if it is a minor version
     */
    void commit(boolean isMajor);

    /**
     * Set the check.in comment.
     * 
     * @param comment
     *            check-in comment
     */
    void setCheckinComment(String comment);

    /**
     * Get the check-in comment.
     * 
     * @return check-in comment
     */
    String getCheckinComment();

    /**
     * Get the version label.
     * 
     * @return the version label
     */
    String getVersionLabel();

    /**
     * Get the versioned document (parent) of this version.
     * 
     * @return versioned document
     */
    VersionedDocument getParentDocument();
}
