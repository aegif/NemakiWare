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
import java.util.List;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;

/**
 * Repository Info.
 * 
 * @cmis 1.0
 */
public interface RepositoryInfo extends Serializable, ExtensionsData {

    /**
     * Returns the repository ID.
     * 
     * @return the repository ID, not {@code null}
     * 
     * @cmis 1.0
     */
    String getId();

    /**
     * Returns the repository name.
     * 
     * @return the repository name, may be {@code null}
     * 
     * @cmis 1.0
     */
    String getName();

    /**
     * Returns the repository description.
     * 
     * @return the repository description, may be {@code null}
     * 
     * @cmis 1.0
     */
    String getDescription();

    /**
     * Returns the repository vendor name.
     * 
     * @return the repository vendor name, may be {@code null}
     * 
     * @cmis 1.0
     */
    String getVendorName();

    /**
     * Returns the repository product name.
     * 
     * @return the repository product name, may be {@code null}
     * 
     * @cmis 1.0
     */
    String getProductName();

    /**
     * Returns the repository product version.
     * 
     * @return the repository product version, may be {@code null}
     * 
     * @cmis 1.0
     */
    String getProductVersion();

    /**
     * Returns the object ID of the root folder.
     * 
     * @return the root folder ID, not {@code null}
     * 
     * @cmis 1.0
     */
    String getRootFolderId();

    /**
     * Returns the repository capabilities.
     * 
     * @return the repository capabilities, may be {@code null}
     * 
     * @cmis 1.0
     */
    RepositoryCapabilities getCapabilities();

    /**
     * Returns the ACL capabilities.
     * 
     * @return the ACL capabilities, may be {@code null}
     * 
     * @cmis 1.0
     */
    AclCapabilities getAclCapabilities();

    /**
     * Returns the latest change log token.
     * 
     * @return the latest change log token, may be {@code null}
     * 
     * @cmis 1.0
     */
    String getLatestChangeLogToken();

    /**
     * Returns the CMIS version supported by this repository as a string.
     * 
     * @return the supported CMIS version, not {@code null}
     * 
     * @cmis 1.0
     */
    String getCmisVersionSupported();

    /**
     * Returns the CMIS version supported by this repository as a
     * {@link CmisVersion} enum.
     * 
     * @return the supported CMIS version, not {@code null}
     * 
     * @cmis 1.0
     */
    CmisVersion getCmisVersion();

    /**
     * Returns the URL of a web interface for this repository, if available.
     * 
     * @return the thin client URL, not {@code null}
     * 
     * @cmis 1.0
     */
    String getThinClientUri();

    /**
     * Indicates whether the entries in the change log are incomplete or
     * complete.
     * 
     * @return {@code true} if the changes are incomplete, {@code false} if the
     *         changes are complete, or {@code null} if the repository didn't
     *         provide this flag
     * 
     * @cmis 1.0
     */
    Boolean getChangesIncomplete();

    /**
     * Returns which types of objects are considered in the change log.
     * 
     * @return the list of type IDs, not {@code null}
     * 
     * @cmis 1.0
     */
    List<BaseTypeId> getChangesOnType();

    /**
     * Returns principal ID for an anonymous user (any authenticated user). This
     * principal ID is supposed to be used in an {@link Ace}.
     * 
     * @return the principal ID for an anonymous user or {@code null} if the
     *         repository does not support anonymous users
     * 
     * @cmis 1.0
     */
    String getPrincipalIdAnonymous();

    /**
     * Returns principal ID for unauthenticated user (guest user). This
     * principal ID is supposed to be used in an {@link Ace}.
     * 
     * @return the principal ID for unauthenticated user or {@code null} if the
     *         repository does not support unauthenticated users
     * 
     * @cmis 1.0
     */
    String getPrincipalIdAnyone();

    /**
     * Returns the list of CMIS extension features supported by this repository.
     * 
     * @return the list of features, may be {@code null}
     * 
     * @cmis 1.1
     */
    List<ExtensionFeature> getExtensionFeatures();
}
