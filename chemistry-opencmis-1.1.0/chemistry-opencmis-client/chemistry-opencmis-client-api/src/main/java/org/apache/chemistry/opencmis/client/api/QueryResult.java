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

import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.PropertyData;

/**
 * Query result.
 */
public interface QueryResult {

    /**
     * Returns the list of all properties in this query result.
     * 
     * @return all properties, not {@code null}
     */
    List<PropertyData<?>> getProperties();

    /**
     * Returns a property by ID.
     * <p>
     * Because repositories are not obligated to add property IDs to their query
     * result properties, this method might not always work as expected with
     * some repositories. Use {@link #getPropertyByQueryName(String)} instead.
     * 
     * @param id
     *            the property ID
     * 
     * @return the property or {@code null} if the property doesn't exist or
     *         hasn't been requested
     */
    <T> PropertyData<T> getPropertyById(String id);

    /**
     * Returns a property by query name or alias.
     * 
     * @param queryName
     *            the property query name or alias
     * 
     * @return the property or {@code null} if the property doesn't exist or
     *         hasn't been requested
     * 
     */
    <T> PropertyData<T> getPropertyByQueryName(String queryName);

    /**
     * Returns a property (single) value by ID.
     * 
     * @param id
     *            the property ID
     * 
     * @see #getPropertyById(String)
     */
    <T> T getPropertyValueById(String id);

    /**
     * Returns a property (single) value by query name or alias.
     * 
     * @param queryName
     *            the property query name or alias
     * 
     * @return the property value or {@code null} if the property doesn't exist,
     *         hasn't been requested, or the property value isn't set
     * 
     * @see #getPropertyByQueryName(String)
     */
    <T> T getPropertyValueByQueryName(String queryName);

    /**
     * Returns a property multi-value by ID.
     * 
     * @param id
     *            the property ID
     * 
     * @return the property value or {@code null} if the property doesn't exist,
     *         hasn't been requested, or the property value isn't set
     * 
     * @see #getPropertyById(String)
     */
    <T> List<T> getPropertyMultivalueById(String id);

    /**
     * Returns a property multi-value by query name or alias.
     * 
     * @param queryName
     *            the property query name or alias
     * 
     * @return the property value or {@code null} if the property doesn't exist,
     *         hasn't been requested, or the property value isn't set
     * 
     * @see #getPropertyByQueryName(String)
     */
    <T> List<T> getPropertyMultivalueByQueryName(String queryName);

    /**
     * Returns the allowable actions if they have been requested.
     * 
     * @return the allowable actions if they have been requested, {@code null}
     *         otherwise
     */
    AllowableActions getAllowableActions();

    /**
     * Returns the relationships if they have been requested.
     * 
     * @return the relationships if they have been requested, {@code null}
     *         otherwise
     */
    List<Relationship> getRelationships();

    /**
     * Returns the renditions if they have been requested.
     * 
     * @return the rendition if they have been requested, {@code null} otherwise
     */
    List<Rendition> getRenditions();
}
