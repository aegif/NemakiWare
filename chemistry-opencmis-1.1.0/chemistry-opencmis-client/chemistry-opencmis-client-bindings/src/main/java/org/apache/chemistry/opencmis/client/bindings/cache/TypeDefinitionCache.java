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
package org.apache.chemistry.opencmis.client.bindings.cache;

import java.io.Serializable;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;

/**
 * A cache for type definition objects.
 * <p>
 * Implementations of this interface have to be tread-safe.
 */
public interface TypeDefinitionCache extends Serializable {

    /**
     * Initializes the cache.
     */
    void initialize(BindingSession session);

    /**
     * Adds a type definition object to the cache.
     * 
     * @param repositoryId
     *            the repository id
     * @param typeDefinition
     *            the type definition object
     */
    void put(String repositoryId, TypeDefinition typeDefinition);

    /**
     * Retrieves a type definition object from the cache.
     * 
     * @param repositoryId
     *            the repository id
     * @param typeId
     *            the type id
     * @return the type definition object or <code>null</code> if the object is
     *         not in the cache
     */
    TypeDefinition get(String repositoryId, String typeId);

    /**
     * Removes a type definition object from the cache.
     * 
     * @param repositoryId
     *            the repository id
     * @param typeId
     *            the type id
     */
    void remove(String repositoryId, String typeId);

    /**
     * Removes all type definition objects of a repository from the cache.
     * 
     * @param repositoryId
     *            the repository id
     */
    void remove(String repositoryId);

    /**
     * Removes all cache entries.
     */
    void removeAll();
}