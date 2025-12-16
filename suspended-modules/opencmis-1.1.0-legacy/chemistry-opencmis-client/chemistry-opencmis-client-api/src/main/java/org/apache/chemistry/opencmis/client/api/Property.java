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

import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;

/**
 * CMIS Property.
 */
public interface Property<T> extends PropertyData<T> {

    /**
     * Returns if the property is a multi-value property.
     * 
     * @return {@code true} if the property is multi-value property,
     *         {@code false} if the property is single-value property,
     */
    boolean isMultiValued();

    /**
     * Returns the property data type.
     * 
     * @return the data type of the property, not {@code null}
     */
    PropertyType getType();

    /**
     * Returns the property definition.
     * 
     * @return the property definition, not {@code null}
     */
    PropertyDefinition<T> getDefinition();

    /**
     * Returns the property value (single or multiple).
     * 
     * @return the property value or {@code null} if the property value isn't
     *         set
     */
    <U> U getValue();

    /**
     * Returns a human readable representation of the property value. If the
     * property is multi-value property, only the first value will be returned.
     * 
     * @return the (first) property value as a string or {@code null} if the
     *         property value isn't set
     */
    String getValueAsString();

    /**
     * Returns a human readable representation of the property values.
     * 
     * @return the property value as a string or {@code null} if the property
     *         value isn't set
     */
    String getValuesAsString();
}
