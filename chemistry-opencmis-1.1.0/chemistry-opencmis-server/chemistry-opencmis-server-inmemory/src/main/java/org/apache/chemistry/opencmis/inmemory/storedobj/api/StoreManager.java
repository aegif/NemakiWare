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

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.server.support.TypeManager;

/**
 * Interface to a repository implementation. This interface is the entry point
 * to a repository that can persist CMIS objects. Using this interface the type
 * information can be retrieved or set, a repository can be created or for a
 * given repository the store can be retrieved.
 */
public interface StoreManager {

    /**
     * Return a list of all available repositories.
     * 
     * @return list of repository ids
     */
    List<String> getAllRepositoryIds();

    /**
     * Initialize the store for the given repository. Only called for
     * repositories that exist on startup (i.e. for each repository id returned
     * in a previous getAllRepositoryIds() call.
     * 
     * @param repositoryId
     *            id of repository to initialize
     */
    void initRepository(String repositoryId);

    /**
     * Get the object store for the given repository id.
     * 
     * @param repositoryId
     *            repository id of object
     * @return the object store in which objects for this repository are stored.
     */
    ObjectStore getObjectStore(String repositoryId);

    /**
     * Get a permission and parameter validating instance.
     * 
     * @return validator and permission checker
     */
    CmisServiceValidator getServiceValidator();

    /**
     * Create a new repository with the given id. Create the repository,
     * initiate the type system and initialize it so that it is ready for use.
     * 
     * @param repositoryId
     *            id of repository
     * @param typeCreatorClassName
     *            class implementing the type creation, the class must implement
     *            the interface TypeCreator
     */
    void createAndInitRepository(String repositoryId, String typeCreatorClassName);

    /**
     * Add option to specify runtime options
     */
    void addFlag(String flag);
    
    /**
     * Retrieve a list with all type definitions.
     * 
     * @param repositoryId
     *            id of repository
     * @param includePropertyDefinitions
     *            indicates whether to include property definitions in returned
     *            type
     * @param cmis11
     *            true if for CMIS version 1.1 false if for 1.0
     * @return map with type definition
     */
    Collection<TypeDefinitionContainer> getTypeDefinitionList(String repositoryId, boolean includePropertyDefinitions,
            boolean cmis11);

    /**
     * Retrieve a type definition for a give repository and type id.
     * 
     * @param repositoryId
     *            id of repository
     * @param typeId
     *            id of type definition
     * @param cmis11
     *            true if for CMIS version 1.1 false if for 1.0
     * @param cmis11
     *            true if for CMIS version 1.1 false if for 1.0
     * @return type definition
     */
    TypeDefinitionContainer getTypeById(String repositoryId, String typeId, boolean cmis11);

    /**
     * Retrieve a type definition for a give repository and type id with or
     * without property definitions and limited to depth in hierarchy.
     * 
     * @param repositoryId
     *            id of repository
     * @param typeId
     *            id of type definition
     * @param includePropertyDefinitions
     *            indicates whether to include property definitions in returned
     *            type
     * @param depth
     *            limit depth of type hierarchy in return (-1 means unlimited)
     * @param cmis11
     *            true if for CMIS version 1.1 false if for 1.0
     * @return type definition
     */
    TypeDefinitionContainer getTypeById(String repositoryId, String typeId, boolean includePropertyDefinitions,
            int depth, boolean cmis11);

    /**
     * Retrieve a factory to create CMIS data structures used as containers.
     * 
     * @return factory object
     */
    BindingsObjectFactory getObjectFactory();

    /**
     * Retrieve a list of root types in the repositories. Root types are
     * available by definition and need to to be created by a client. CMIS
     * supports documents, folders, relations and policies as root types.
     * 
     * @param repositoryId
     *            id of repository
     * @param inclPropDefs
     *            true to include property definitions, false otherwise
     * @param cmis11
     *            true if for CMIS version 1.1 false if for 1.0
     * @return list of root types
     */
    List<TypeDefinitionContainer> getRootTypes(String repositoryId, boolean inclPropDefs, boolean cmis11);

    /**
     * Retrieve the repository information for a repository.
     * 
     * @param context
     *            call context of the corresponding call
     * @param repositoryId
     *            id of repository
     * @return repository information
     */
    RepositoryInfo getRepositoryInfo(CallContext context, String repositoryId);

    /**
     * Retrieve the type manager for a given repository.
     * 
     * @param repositoryId
     *            id of repository
     * @return type manager for this repository or null if repository is unknown
     */
    TypeManager getTypeManager(String repositoryId);

    /**
     * Get information if a repository supports single filing.
     * 
     * @param repositoryId
     *            repository id of to get information from
     * @return true if single filing is supported false otherwise
     */
    boolean supportsSingleFiling(String repositoryId);

    /**
     * Get information if a repository supports multi filing.
     * 
     * @param repositoryId
     *            repository id of to get information from
     * @return true if multi filing is supported false otherwise
     */
    boolean supportsMultiFilings(String repositoryId);

    /**
     * Execute a query against the repository (same parameter as the discovery
     * service query method.
     * 
     * @param callContext
     *            call context of this query
     * @param user
     *            user executing the query
     * @param repositoryId
     *            id of repository
     * @param statement
     *            query statement
     * @param searchAllVersions
     *            search in all versions of objects
     * @param includeAllowableActions
     *            include allowable actions
     * @param includeRelationships
     *            include relationships
     * @param renditionFilter
     *            include renditions
     * @param maxItems
     *            max number of items to return
     * @param skipCount
     *            items to skip
     * @return list of objects matching the query
     */
    ObjectList query(CallContext callContext, String user, String repositoryId, String statement,
            Boolean searchAllVersions, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, BigInteger maxItems, BigInteger skipCount);

}