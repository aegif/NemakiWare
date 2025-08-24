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
package org.apache.chemistry.opencmis.server.support;

import java.util.Collection;
import java.util.List;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;

public interface TypeManager {

    /**
     * return a type definition from the type definition id
     * 
     * @param typeId
     *            id of the type definition
     * @return type definition for this id
     */
    TypeDefinitionContainer getTypeById(String typeId);

    /**
     * return a type definition from the type query name or null if not found
     * 
     * @param typeQueryName
     *            query name of the type definition
     * @return type definition for this query name
     */
    TypeDefinition getTypeByQueryName(String typeQueryName);

    /**
     * return a list of all types known in this repository
     * Note: This method is not needed for the query parser.
     * 
     * @return
     *      list of type definitions
     */
    Collection<TypeDefinitionContainer> getTypeDefinitionList();

    /**
     * return a list of the root types as defined in the CMIS spec (for
     * document, folder, policy and relationship
     * Note: This method is not needed for the query parser.
     * 
     * @return
     *      list of type definitions
     */
    List<TypeDefinitionContainer> getRootTypes();

    /**
     * retrieve the property id from a type for a given property query name 
     * 
     * @param typeDefinition
     *      type definition containing query name
     * @param propQueryName
     *      query name of property
     * @return
     *      property id of property or null if not found
     */
    String getPropertyIdForQueryName(TypeDefinition typeDefinition, String propQueryName);

    /**
     * Add a type to the type system. Add all properties from inherited types,
     * add type to children of parent types.
     * Note: This method is not needed for the query parser.
     * 
     * @param typeDefinition
     *            new type to add
     * @param addInheritedProperties
     *            add properties from supertype to type definition
     */
    void addTypeDefinition(TypeDefinition typeDefinition, boolean addInheritedProperties);

    /**
     * Modify an existing type definition.
     * Note: This method is not needed for the query parser.
     * 
     * @param typeDefinition
     *            type to be modified
     */
    void updateTypeDefinition(TypeDefinition typeDefinition);

    /**
     * Delete a type from the type system. Delete will succeed only if type is
     * not in use. Otherwise an exception is thrown.
     * Note: This method is not needed for the query parser.
     * 
     * @param typeId
     *            id of type to be deleted
     */
    void deleteTypeDefinition(String typeId);
}