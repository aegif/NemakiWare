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
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

/**
 * Base property definition interface.
 * 
 * @cmis 1.0
 */
public interface PropertyDefinition<T> extends Serializable, ExtensionsData {

    /**
     * Returns the property definition ID.
     * 
     * @return the property definition ID
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
     * Returns the property type.
     * 
     * @return the property type
     * 
     * @cmis 1.0
     */
    PropertyType getPropertyType();

    /**
     * Returns the cardinality.
     * 
     * @return the cardinality
     * 
     * @cmis 1.0
     */
    Cardinality getCardinality();

    /**
     * Returns the updatability.
     * 
     * @return the updatability
     * 
     * @cmis 1.0
     */
    Updatability getUpdatability();

    /**
     * Returns if the property is inherited by a parent type.
     * 
     * @return {@code true} - is inherited; {@code false} - is not inherited;
     *         {@code null} - unknown (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isInherited();

    /**
     * Returns if the property is required.
     * 
     * @return {@code true} - is required; {@code false} - is not required;
     *         {@code null} - unknown (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isRequired();

    /**
     * Returns if the property is queryable.
     * 
     * @return {@code true} - is queryable; {@code false} - is not queryable;
     *         {@code null} - unknown (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isQueryable();

    /**
     * Returns if the property is Orderable.
     * 
     * @return {@code true} - is Orderable; {@code false} - is not Orderable;
     *         {@code null} - unknown (noncompliant repository)
     * 
     * @cmis 1.0
     */
    Boolean isOrderable();

    /**
     * Returns if the property supports open choice.
     * 
     * @return {@code true} - supports open choice; {@code false} - does not
     *         support open choice; {@code null} - unknown or not applicable
     * 
     * @cmis 1.0
     */
    Boolean isOpenChoice();

    /**
     * Returns the default value.
     * 
     * @return the default value (list) or an empty list if no default value is
     *         defined
     * 
     * @cmis 1.0
     */
    List<T> getDefaultValue();

    /**
     * Returns the choices for this property.
     * 
     * @return the choices or an empty list if no choices are defined
     * 
     * @cmis 1.0
     */
    List<Choice<T>> getChoices();
}
