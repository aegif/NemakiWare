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

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;

/**
 * Navigation Service interface.
 * 
 * <p>
 * <em>
 * See the CMIS 1.0 and CMIS 1.1 specifications for details on the operations, parameters,
 * exceptions and the domain model.
 * </em>
 * </p>
 */
public interface NavigationService {

    /**
     * Gets the list of child objects contained in the specified folder.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param folderId
     *            the identifier for the folder
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param orderBy
     *            <em>(optional)</em> a comma-separated list of query names that
     *            define the order of the result set. Each query name must be
     *            followed by the ascending modifier "ASC" or the descending
     *            modifier "DESC" (default is repository specific)
     * @param includeAllowableActions
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the available actions for each object in the result set
     *            (default is {@code false})
     * @param includeRelationships
     *            <em>(optional)</em> indicates what relationships in which the
     *            objects participate must be returned (default is
     *            {@link IncludeRelationships#NONE})
     * @param renditionFilter
     *            <em>(optional)</em> indicates what set of renditions the
     *            repository must return whose kind matches this filter (default
     *            is {@code "cmis:none"})
     * @param includePathSegment
     *            <em>(optional)</em> if {@code true}, returns a path segment
     *            for each child object for use in constructing that object's
     *            path (default is {@code false})
     * @param maxItems
     *            <em>(optional)</em> the maximum number of items to return in a
     *            response (default is repository specific)
     * @param skipCount
     *            <em>(optional)</em> number of potential results that the
     *            repository MUST skip/page over before returning any results
     *            (default is 0)
     * @param extension
     *            extension data
     * @return the list of children
     */
    ObjectInFolderList getChildren(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePathSegment, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);

    /**
     * Gets the set of descendant objects contained in the specified folder or
     * any of its child folders.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param folderId
     *            the identifier for the folder
     * @param depth
     *            the number of levels of depth in the folder hierarchy from
     *            which to return results
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param includeAllowableActions
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the available actions for each object in the result set
     *            (default is {@code false})
     * @param includeRelationships
     *            <em>(optional)</em> indicates what relationships in which the
     *            objects participate must be returned (default is
     *            {@link IncludeRelationships#NONE})
     * @param renditionFilter
     *            <em>(optional)</em> indicates what set of renditions the
     *            repository must return whose kind matches this filter (default
     *            is {@code "cmis:none"})
     * @param includePathSegment
     *            <em>(optional)</em> if {@code true}, returns a path segment
     *            for each child object for use in constructing that object's
     *            path (default is {@code false})
     * @param extension
     *            extension data
     * @return the tree of descendants
     **/
    List<ObjectInFolderContainer> getDescendants(String repositoryId, String folderId, BigInteger depth, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePathSegment, ExtensionsData extension);

    /**
     * Gets the set of descendant folder objects contained in the specified
     * folder.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param folderId
     *            the identifier for the folder
     * @param depth
     *            the number of levels of depth in the folder hierarchy from
     *            which to return results
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param includeAllowableActions
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the available actions for each object in the result set
     *            (default is {@code false})
     * @param includeRelationships
     *            <em>(optional)</em> indicates what relationships in which the
     *            objects participate must be returned (default is
     *            {@link IncludeRelationships#NONE})
     * @param renditionFilter
     *            <em>(optional)</em> indicates what set of renditions the
     *            repository must return whose kind matches this filter (default
     *            is {@code "cmis:none"})
     * @param includePathSegment
     *            <em>(optional)</em> if {@code true}, returns a path segment
     *            for each child object for use in constructing that object's
     *            path (default is {@code false})
     * @param extension
     *            extension data
     * @return the folder tree
     **/
    List<ObjectInFolderContainer> getFolderTree(String repositoryId, String folderId, BigInteger depth, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePathSegment, ExtensionsData extension);

    /**
     * Gets the parent folder(s) for the specified non-folder, fileable object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param includeAllowableActions
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the available actions for each object in the result set
     *            (default is {@code false})
     * @param includeRelationships
     *            <em>(optional)</em> indicates what relationships in which the
     *            objects participate must be returned (default is
     *            {@link IncludeRelationships#NONE})
     * @param renditionFilter
     *            <em>(optional)</em> indicates what set of renditions the
     *            repository must return whose kind matches this filter (default
     *            is {@code "cmis:none"})
     * @param includeRelativePathSegment
     *            <em>(optional)</em> if {@code true}, returns a relative path
     *            segment for each parent object for use in constructing that
     *            object's path (default is {@code false})
     * @param extension
     *            extension data
     * @return the list of parents
     */
    List<ObjectParentData> getObjectParents(String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includeRelativePathSegment, ExtensionsData extension);

    /**
     * Gets the parent folder object for the specified folder object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param folderId
     *            the identifier for the folder
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param extension
     *            extension data
     * @return the folder parent
     */
    ObjectData getFolderParent(String repositoryId, String folderId, String filter, ExtensionsData extension);

    /**
     * Gets the list of documents that are checked out that the user has access
     * to.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param folderId
     *            the identifier for the folder
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param orderBy
     *            <em>(optional)</em> a comma-separated list of query names that
     *            define the order of the result set. Each query name must be
     *            followed by the ascending modifier "ASC" or the descending
     *            modifier "DESC" (default is repository specific)
     * @param includeAllowableActions
     *            <em>(optional)</em> if {@code true}, then the repository must
     *            return the available actions for each object in the result set
     *            (default is {@code false})
     * @param includeRelationships
     *            <em>(optional)</em> indicates what relationships in which the
     *            objects participate must be returned (default is
     *            {@link IncludeRelationships#NONE})
     * @param renditionFilter
     *            <em>(optional)</em> indicates what set of renditions the
     *            repository must return whose kind matches this filter (default
     *            is {@code "cmis:none"})
     * @param maxItems
     *            <em>(optional)</em> the maximum number of items to return in a
     *            response (default is repository specific)
     * @param skipCount
     *            <em>(optional)</em> number of potential results that the
     *            repository MUST skip/page over before returning any results
     *            (default is 0)
     * @param extension
     *            extension data
     * @return the list of checked out documents
     */
    ObjectList getCheckedOutDocs(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);
}
