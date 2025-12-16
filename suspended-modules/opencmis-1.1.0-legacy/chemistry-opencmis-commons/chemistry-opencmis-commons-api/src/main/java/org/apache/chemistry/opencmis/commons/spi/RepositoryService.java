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
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;

/**
 * Repository Service interface.
 * 
 * <p>
 * <em>
 * See the CMIS 1.0 and CMIS 1.1 specifications for details on the operations, parameters,
 * exceptions and the domain model.
 * </em>
 * </p>
 */
public interface RepositoryService {

    /**
     * Returns a list of CMIS repository information available from this CMIS
     * service endpoint.
     * 
     * In contrast to the CMIS specification this method returns repository
     * infos not only repository IDs.
     * 
     * @param extension
     *            extension data
     * @return the list of repositories
     */
    List<RepositoryInfo> getRepositoryInfos(ExtensionsData extension);

    /**
     * Returns information about the CMIS repository, the optional capabilities
     * it supports and its access control information if applicable.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param extension
     *            extension data
     * @return the repository info
     */
    RepositoryInfo getRepositoryInfo(String repositoryId, ExtensionsData extension);

    /**
     * Returns the list of object types defined for the repository that are
     * children of the specified type.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param typeId
     *            <em>(optional)</em> the typeId of an object type specified in
     *            the repository (if not specified the repository MUST return
     *            all base object types)
     * @param includePropertyDefinitions
     *            <em>(optional)</em> if <code>true</code> the repository MUST
     *            return the property definitions for each object type returned
     *            (default is <code>false</code>)
     * @param maxItems
     *            <em>(optional)</em> the maximum number of items to return in a
     *            response (default is repository specific)
     * @param skipCount
     *            <em>(optional)</em> number of potential results that the
     *            repository MUST skip/page over before returning any results
     *            (default is 0)
     * @param extension
     *            extension data
     * @return the list of type children
     */
    TypeDefinitionList getTypeChildren(String repositoryId, String typeId, Boolean includePropertyDefinitions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);

    /**
     * Returns the set of descendant object type defined for the repository
     * under the specified type.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param typeId
     *            <em>(optional)</em> the typeId of an object type specified in
     *            the repository (if not specified the repository MUST return
     *            all types and MUST ignore the value of the depth parameter)
     * @param depth
     *            <em>(optional)</em> the number of levels of depth in the type
     *            hierarchy from which to return results (default is repository
     *            specific)
     * @param includePropertyDefinitions
     *            <em>(optional)</em> if <code>true</code> the repository MUST
     *            return the property definitions for each object type returned
     *            (default is <code>false</code>)
     * @param extension
     *            extension data
     * @return the tree of type descendants
     */
    List<TypeDefinitionContainer> getTypeDescendants(String repositoryId, String typeId, BigInteger depth,
            Boolean includePropertyDefinitions, ExtensionsData extension);

    /**
     * Gets the definition of the specified object type.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param typeId
     *            typeId of an object type specified in the repository
     * @param extension
     *            extension data
     * @return the type definition
     */
    TypeDefinition getTypeDefinition(String repositoryId, String typeId, ExtensionsData extension);

    /**
     * Creates a new type.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param type
     *            the type definition
     * @param extension
     *            extension data
     * @return the newly created type
     */
    TypeDefinition createType(String repositoryId, TypeDefinition type, ExtensionsData extension);

    /**
     * Updates a type.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param type
     *            the type definition
     * @param extension
     *            extension data
     * @return the updated type
     */
    TypeDefinition updateType(String repositoryId, TypeDefinition type, ExtensionsData extension);

    /**
     * Deletes a type.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param typeId
     *            typeId of an object type specified in the repository
     * @param extension
     *            extension data
     */
    void deleteType(String repositoryId, String typeId, ExtensionsData extension);
}
