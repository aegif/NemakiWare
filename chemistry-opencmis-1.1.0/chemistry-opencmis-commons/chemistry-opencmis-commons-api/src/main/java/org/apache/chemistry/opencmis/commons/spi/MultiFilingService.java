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
package org.apache.chemistry.opencmis.commons.spi;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;

/**
 * MultiFiling Service interface.
 * 
 * <p>
 * <em>
 * See the CMIS 1.0 and CMIS 1.1 specifications for details on the operations, parameters,
 * exceptions and the domain model.
 * </em>
 * </p>
 */
public interface MultiFilingService {

    /**
     * Adds an existing fileable non-folder object to a folder.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the object to add
     * @param folderId
     *            the folder
     * @param allVersions
     *            a flag that indicates if all versions of a document should be
     *            added to the folder or just this single version
     * @param extension
     *            extension data
     */
    void addObjectToFolder(String repositoryId, String objectId, String folderId, Boolean allVersions,
            ExtensionsData extension);

    /**
     * Removes an existing fileable non-folder object from a folder.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the object to remove
     * @param folderId
     *            the folder
     * @param extension
     *            extension data
     */
    void removeObjectFromFolder(String repositoryId, String objectId, String folderId, ExtensionsData extension);
}
