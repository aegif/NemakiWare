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
package org.apache.chemistry.opencmis.commons.data;

import java.util.List;

/**
 * Mutable PropertyData.
 */
public interface MutablePropertyData<T> extends PropertyData<T> {

    /**
     * Sets the property ID.
     * 
     * @param id
     *            the property ID, should not be {@code null}
     */
    void setId(String id);

    /**
     * Set the display name.
     * 
     * @param displayName
     *            the display name
     */
    void setDisplayName(String displayName);

    /**
     * Set the local name.
     * 
     * @param localName
     *            the local name
     */
    void setLocalName(String localName);

    /**
     * Set the query name.
     * 
     * @param queryName
     *            the query name
     */
    void setQueryName(String queryName);

    /**
     * Sets the property value.
     * <p>
     * If this property is a single value property, this list must either be
     * empty or {@code null} (= unset) or must only contain one entry.
     * 
     * @param values
     *            the property value or {@code null} to unset the property
     */
    void setValues(List<T> values);

    /**
     * Sets a property value.
     * <p>
     * If this property is a multi value property, this value becomes the only
     * value in the list of values.
     * 
     * @param value
     *            the property value or {@code null} to unset the property
     */
    void setValue(T value);

}
