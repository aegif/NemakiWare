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

import java.io.Serializable;

import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.CapabilityJoin;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.CapabilityQuery;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;

public interface RepositoryCapabilities extends Serializable, ExtensionsData {

    // Object

    /**
     * Returns the Content Stream Updates capability.
     * 
     * @return the capability enum or {@code null} if the the repository does not
     *         provide this value
     * 
     * @cmis 1.0
     */
    CapabilityContentStreamUpdates getContentStreamUpdatesCapability();

    /**
     * Returns the Changes capability.
     * 
     * @return the capability enum or {@code null} if the the repository does not
     *         provide this value
     * 
     * @cmis 1.0
     */
    CapabilityChanges getChangesCapability();

    /**
     * Returns Rendition capability.
     * 
     * @return the capability enum or {@code null} if the the repository does not
     *         provide this value
     * 
     * @cmis 1.0
     */
    CapabilityRenditions getRenditionsCapability();

    // Navigation

    /**
     * Returns the Get Descendants capability.
     * 
     * @return {@code true} if supported, {@code false} if not supported, or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isGetDescendantsSupported();

    /**
     * Returns Get Folder Tree capability.
     * 
     * @return {@code true} if supported, {@code false} if not supported, or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isGetFolderTreeSupported();

    /**
     * Returns the Order By capability.
     *
     * @return the capability enum or {@code null} if the the repository does not
     *         provide this value
     * 
     * @cmis 1.1
     */
    CapabilityOrderBy getOrderByCapability();

    // Filing

    /**
     * Returns the Multifiling capability.
     * 
     * @return {@code true} if supported, {@code false} if not supported, or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isMultifilingSupported();

    /**
     * Returns the Unfiling capability.
     * 
     * @return {@code true} if supported, {@code false} if not supported, or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isUnfilingSupported();

    /**
     * Returns the Version Specific Filing capability.
     * 
     * @return {@code true} if supported, {@code false} if not supported, or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isVersionSpecificFilingSupported();

    // Versioning

    /**
     * Returns the PWC Searchable capability.
     * 
     * @return {@code true} if supported, {@code false} if not supported, or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isPwcSearchableSupported();

    /**
     * Returns the PWC Updatable capability.
     * 
     * @return {@code true} if supported, {@code false} if not supported, or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isPwcUpdatableSupported();

    /**
     * Returns the All Versions Searchable capability.
     * 
     * @return {@code true} if supported, {@code false} if not supported, or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isAllVersionsSearchableSupported();

    // Query

    /**
     * Returns the Query capability.
     * 
     * @return the capability enum or {@code null} if the the repository does not
     *         provide this value
     * 
     * @cmis 1.0
     */
    CapabilityQuery getQueryCapability();

    /**
     * Returns the Join capability.
     * 
     * @return the capability enum or {@code null} if the the repository does not
     *         provide this value
     * 
     * @cmis 1.0
     */
    CapabilityJoin getJoinCapability();

    // ACLs

    /**
     * Returns the ACL capability.
     * 
     * @return the capability enum or {@code null} if the the repository does not
     *         provide this value
     * 
     * @cmis 1.0
     */
    CapabilityAcl getAclCapability();

    // Type mutability

    /**
     * Returns the Creatable Property Types capability.
     * 
     * @return the creatable property types or {@code null} if the the repository
     *         does not provide this value
     * 
     * @cmis 1.1
     */
    CreatablePropertyTypes getCreatablePropertyTypes();

    /**
     * Returns the New Type Settable Attributes capability.
     * 
     * @return the attributes that can be set when a new type is created or
     *         {@code null} if the the repository does not provide this value
     * 
     * @cmis 1.1
     */
    NewTypeSettableAttributes getNewTypeSettableAttributes();

}
