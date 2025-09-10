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
package org.apache.chemistry.opencmis.client.runtime;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyData;

/**
 * Property Implementation.
 */
public class PropertyImpl<T> extends AbstractPropertyData<T> implements Property<T>, Serializable {

    private static final long serialVersionUID = 1L;
    private final PropertyDefinition<T> propertyDefinition;

    /**
     * Constructs a property from a list of values.
     */
    public PropertyImpl(PropertyDefinition<T> pd, List<T> values) {
        if (pd == null) {
            throw new IllegalArgumentException("Type must be set!");
        }
        if (values == null) {
            throw new IllegalArgumentException("Value must be set!");
        }
        propertyDefinition = pd;
        initialize(pd);
        setValues(values);
    }

    /**
     * Copy constructor.
     */
    public PropertyImpl(Property<T> property) {
        if (property == null) {
            throw new IllegalArgumentException("Source must be set!");
        }

        propertyDefinition = property.getDefinition();
        initialize(property.getDefinition());
        setValues(new ArrayList<T>(property.getValues()));
    }

    protected void initialize(PropertyDefinition<?> pd) {
        setId(pd.getId());
        setDisplayName(pd.getDisplayName());
        setLocalName(pd.getLocalName());
        setQueryName(pd.getQueryName());
    }

    @Override
    public PropertyDefinition<T> getDefinition() {
        return propertyDefinition;
    }

    @Override
    public PropertyType getType() {
        return propertyDefinition.getPropertyType();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <U> U getValue() {
        List<T> values = getValues();
        if (propertyDefinition.getCardinality() == Cardinality.SINGLE) {
            return values.isEmpty() ? null : (U) values.get(0);
        } else {
            return (U) values;
        }
    }

    @Override
    public String getValueAsString() {
        List<T> values = getValues();
        if (values.isEmpty()) {
            return null;
        }

        return formatValue(values.get(0));
    }

    @Override
    public String getValuesAsString() {
        List<T> values = getValues();

        StringBuilder result = new StringBuilder(128);
        for (T value : values) {
            if (result.length() > 0) {
                result.append(", ");
            }

            result.append(formatValue(value));
        }

        return "[" + result.toString() + "]";
    }

    private String formatValue(T value) {
        String result;

        if (value == null) {
            return null;
        }

        if (value instanceof GregorianCalendar) {
            result = ((GregorianCalendar) value).getTime().toString();
        } else {
            result = value.toString();
        }

        return result;
    }

    @Override
    public boolean isMultiValued() {
        return propertyDefinition.getCardinality() == Cardinality.MULTI;
    }
}
