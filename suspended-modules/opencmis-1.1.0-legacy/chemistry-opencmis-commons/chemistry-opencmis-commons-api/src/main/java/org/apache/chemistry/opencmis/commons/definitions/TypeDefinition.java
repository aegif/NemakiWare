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
package org.apache.chemistry.opencmis.commons.definitions;

import java.io.Serializable;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

/**
 * Base type definition interface.
 * 
 * @cmis 1.0
 */
public interface TypeDefinition extends Serializable, ExtensionsData {

    /**
     * Returns the type ID.
     * 
     * @return the type ID, not {@code null}
     * 
     * @cmis 1.0
     */
    String getId();

    /**
     * Returns the local name.
     * 
     * @return the local name
     * 
     * @cmis 1.0
     */
    String getLocalName();

    /**
     * Returns the local namespace.
     * 
     * @return the local namespace
     * 
     * @cmis 1.0
     */
    String getLocalNamespace();

    /**
     * Returns the display name.
     * 
     * @return the display name
     * 
     * @cmis 1.0
     */
    String getDisplayName();

    /**
     * Returns the query name
     * 
     * @return the query name
     * 
     * @cmis 1.0
     */
    String getQueryName();

    /**
     * Returns the property description.
     * 
     * @return returns the description
     * 
     * @cmis 1.0
     */
    String getDescription();

    /**
     * Returns the base object type ID.
     * 
     * @return the base object type ID
     * 
     * @cmis 1.0
     */
    BaseTypeId getBaseTypeId();

    /**
     * Returns the parent type ID.
     * 
     * @return the parent type ID or {@code null} if the type is a base type
     * 
     * @cmis 1.0
     */
    String getParentTypeId();

    /**
     * Returns if an object of this type can be created.
     * 
     * @return {@code true} if an object of this type can be created;
     *         {@code false} if creation of objects of this type is not
     *         possible; {@code null} - unknown (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isCreatable();

    /**
     * Returns if an object of this type can be filed.
     * 
     * @return {@code true} if an object of this type can be filed;
     *         {@code false} if an object of this type cannot be filed;
     *         {@code null} - unknown (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isFileable();

    /**
     * Returns if this type is queryable.
     * 
     * @return {@code true} if this type is queryable; {@code false} if this
     *         type is not queryable; {@code null} - unknown (noncompliant
     *         repository)
     * 
     * @cmis 1.0
     */
    Boolean isQueryable();

    /**
     * Returns if this type is full text indexed.
     * 
     * @return {@code true} if this type is full text indexed; {@code false} if
     *         this type is not full text indexed; {@code null} - unknown
     *         (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isFulltextIndexed();

    /**
     * Returns if this type is included in queries that query the super type.
     * 
     * @return {@code true} if this type is included; {@code false} if this type
     *         is not included; {@code null} - unknown (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isIncludedInSupertypeQuery();

    /**
     * Returns if objects of this type are controllable by policies.
     * 
     * @return {@code true} if objects are controllable by policies;
     *         {@code false} if objects are not controllable by policies;
     *         {@code null} - unknown (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isControllablePolicy();

    /**
     * Returns if objects of this type are controllable by ACLs.
     * 
     * @return {@code true} if objects are controllable by ACLs; {@code false}
     *         if objects are not controllable by ACLs; {@code null} - unknown
     *         (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isControllableAcl();

    /**
     * Returns the property definitions of this type.
     * 
     * @return the property definitions or {@code null} if the property
     *         definitions were not requested
     * 
     * @cmis 1.0
     */
    Map<String, PropertyDefinition<?>> getPropertyDefinitions();

    /**
     * Returns type mutability flags.
     * 
     * @return type mutability flags
     * 
     * @cmis 1.1
     */
    TypeMutability getTypeMutability();
}
