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

/**
 * Fileable CMIS object.
 * 
 * A fileable object is an object that can reside in a folder.
 */
public interface FileableCmisObject extends CmisObject {

    // object service

    /**
     * Moves this object.
     * 
     * @param sourceFolderId
     *            the object ID of the source folder
     * @param targetFolderId
     *            the object ID of the target folder
     * 
     * @return the moved object
     * 
     * @cmis 1.0
     */
    FileableCmisObject move(ObjectId sourceFolderId, ObjectId targetFolderId);

    /**
     * Moves this object.
     * 
     * @param sourceFolderId
     *            the object ID of the source folder
     * @param targetFolderId
     *            the object ID of the target folder
     * @param context
     *            the {@link OperationContext} to use to fetch the moved object
     * 
     * @return the moved object
     * 
     * @cmis 1.0
     */
    FileableCmisObject move(ObjectId sourceFolderId, ObjectId targetFolderId, OperationContext context);

    // navigation service

    /**
     * Returns the parents of this object.
     * 
     * @return the list of parent folders of this object or an empty list if
     *         this object is unfiled or if this object is the root folder
     * 
     * @cmis 1.0
     */
    List<Folder> getParents();

    /**
     * Returns the parents of this object.
     * 
     * @param context
     *            the {@link OperationContext} to use to fetch the parent folder
     *            objects
     * 
     * @return the list of parent folders of this object or an empty list if
     *         this object is unfiled or if this object is the root folder
     * 
     * @cmis 1.0
     */
    List<Folder> getParents(OperationContext context);

    /**
     * Returns the paths of this object.
     * 
     * @return the list of paths of this object or an empty list if this object
     *         is unfiled or if this object is the root folder
     * 
     * @cmis 1.0
     */
    List<String> getPaths();

    // multifiling service

    /**
     * Adds this object to a folder.
     * 
     * @param folderId
     *            the object ID of the folder to which this object should be
     *            added
     * @param allVersions
     *            if this parameter is {@code true} and this object is a
     *            document, all versions of the version series are added to the
     *            folder
     * 
     * @cmis 1.0
     */
    void addToFolder(ObjectId folderId, boolean allVersions);

    /**
     * Removes this object from a folder.
     * 
     * @param folderId
     *            the object ID of the folder from which this object should be
     *            removed
     * 
     * @cmis 1.0
     */
    void removeFromFolder(ObjectId folderId);
}
