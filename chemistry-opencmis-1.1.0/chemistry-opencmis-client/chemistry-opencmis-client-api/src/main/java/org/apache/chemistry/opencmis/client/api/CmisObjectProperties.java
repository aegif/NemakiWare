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

import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

/**
 * Accessors to CMIS object properties.
 * <p>
 * A property might not be available because either the repository didn't
 * provide it or a property filter was used to retrieve this object.
 * <p>
 * The property values represent a snapshot of the object when it was loaded.
 * The object and its properties may be out-of-date if the object has been
 * modified in the repository.
 */
public interface CmisObjectProperties {

    /**
     * Returns a list of all available CMIS properties.
     * 
     * @return all available CMIS properties
     */
    List<Property<?>> getProperties();

    /**
     * Returns a property.
     * 
     * @param id
     *            the ID of the property
     * 
     * @return the property or {@code null} if the property hasn't been
     *         requested or hasn't been provided by the repository
     */
    <T> Property<T> getProperty(String id);

    /**
     * Returns the value of a property.
     * 
     * @param id
     *            the ID of the property
     * 
     * @return the property value or {@code null} if the property hasn't been
     *         requested, hasn't been provided by the repository, or the
     *         property value isn't set
     */
    <T> T getPropertyValue(String id);

    // convenience accessors

    /**
     * Returns the name of this CMIS object (CMIS property {@code cmis:name}).
     * 
     * @return the name of the object or {@code null} if the property hasn't
     *         been requested or hasn't been provided by the repository
     * 
     * @cmis 1.0
     */
    String getName();

    /**
     * Returns the description of this CMIS object (CMIS property
     * {@code cmis:description}).
     * 
     * @return the description of the object or {@code null} if the property
     *         hasn't been requested, hasn't been provided by the repository, or
     *         the property value isn't set
     * 
     * @cmis 1.1
     */
    String getDescription();

    /**
     * Returns the user who created this CMIS object (CMIS property
     * {@code cmis:createdBy}).
     * 
     * @return the creator of the object or {@code null} if the property hasn't
     *         been requested or hasn't been provided by the repository
     * 
     * @cmis 1.0
     */
    String getCreatedBy();

    /**
     * Returns the timestamp when this CMIS object has been created (CMIS
     * property {@code cmis:creationDate}).
     * 
     * @return the creation time of the object or {@code null} if the property
     *         hasn't been requested or hasn't been provided by the repository
     * 
     * @cmis 1.0
     */
    GregorianCalendar getCreationDate();

    /**
     * Returns the user who modified this CMIS object (CMIS property
     * {@code cmis:lastModifiedBy}).
     * 
     * @return the last modifier of the object or {@code null} if the property
     *         hasn't been requested or hasn't been provided by the repository
     * 
     * @cmis 1.0
     */
    String getLastModifiedBy();

    /**
     * Returns the timestamp when this CMIS object has been modified (CMIS
     * property {@code cmis:lastModificationDate}).
     * 
     * @return the last modification date of the object or {@code null} if the
     *         property hasn't been requested or hasn't been provided by the
     *         repository
     * 
     * @cmis 1.0
     */
    GregorianCalendar getLastModificationDate();

    /**
     * Returns the id of the base type of this CMIS object (CMIS property
     * {@code cmis:baseTypeId}).
     * 
     * @return the base type id of the object or {@code null} if the property
     *         hasn't been requested or hasn't been provided by the repository
     * 
     * @cmis 1.0
     */
    BaseTypeId getBaseTypeId();

    /**
     * Returns the base type of this CMIS object (object type identified by
     * {@code cmis:baseTypeId}).
     * 
     * @return the base type of the object or {@code null} if the property
     *         {@code cmis:baseTypeId} hasn't been requested or hasn't been
     *         provided by the repository
     * 
     * @cmis 1.0
     */
    ObjectType getBaseType();

    /**
     * Returns the type of this CMIS object (object type identified by
     * {@code cmis:objectTypeId}).
     * 
     * @return the type of the object or {@code null} if the property
     *         {@code cmis:objectTypeId} hasn't been requested or hasn't been
     *         provided by the repository
     * 
     * @cmis 1.0
     */
    ObjectType getType();

    /**
     * Returns the secondary types of this CMIS object (object types identified
     * by {@code cmis:secondaryObjectTypeIds}).
     * 
     * @return the secondary types of the object or {@code null} if the property
     *         {@code cmis:secondaryObjectTypeIds} hasn't been requested or
     *         hasn't been provided by the repository
     * @cmis 1.1
     */
    List<SecondaryType> getSecondaryTypes();

    /**
     * Returns a list of primary and secondary object types that define the
     * given property.
     * 
     * @param id
     *            the ID of the property
     * 
     * @return a list of object types that define the given property or
     *         {@code null} if the property couldn't be found in the object
     *         types that are attached to this object
     * 
     */
    List<ObjectType> findObjectType(String id);

    /**
     * Returns the change token (CMIS property {@code cmis:changeToken}).
     * 
     * @return the change token of the object or {@code null} if the property
     *         hasn't been requested or hasn't been provided or isn't supported
     *         by the repository
     * 
     * @cmis 1.0
     */
    String getChangeToken();
}
