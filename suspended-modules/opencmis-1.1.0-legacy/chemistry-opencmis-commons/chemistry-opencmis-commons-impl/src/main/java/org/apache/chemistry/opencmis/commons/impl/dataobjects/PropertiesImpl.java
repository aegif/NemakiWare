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
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.MutableProperties;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;

/**
 * Properties data implementation.
 */
public class PropertiesImpl extends AbstractExtensionData implements MutableProperties {

    private static final long serialVersionUID = 1L;

    private final List<PropertyData<?>> propertyList = new ArrayList<PropertyData<?>>();
    private final Map<String, PropertyData<?>> properties = new LinkedHashMap<String, PropertyData<?>>();

    /**
     * Constructor.
     */
    public PropertiesImpl() {
    }

    /**
     * Constructor.
     * 
     * @param properties
     *            initial collection of properties
     */
    public PropertiesImpl(Collection<PropertyData<?>> properties) {
        addProperties(properties);
    }

    /**
     * Shallow copy constructor.
     * 
     * Creates a new collection of properties but references the original
     * property and extension objects.
     */
    public PropertiesImpl(Properties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties not set!");
        }

        addProperties(properties.getPropertyList());
        setExtensions(properties.getExtensions());
    }

    @Override
    public Map<String, PropertyData<?>> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public List<PropertyData<?>> getPropertyList() {
        return Collections.unmodifiableList(propertyList);
    }

    protected void addProperties(Collection<PropertyData<?>> properties) {
        if (properties != null) {
            for (PropertyData<?> prop : properties) {
                addProperty(prop);
            }
        }
    }

    @Override
    public void addProperty(PropertyData<?> property) {
        if (property == null) {
            return;
        }

        propertyList.add(property);
        properties.put(property.getId(), property);
    }

    @Override
    public void replaceProperty(PropertyData<?> property) {
        if (property == null || property.getId() == null) {
            return;
        }

        removeProperty(property.getId());

        propertyList.add(property);
        properties.put(property.getId(), property);
    }

    @Override
    public void removeProperty(String id) {
        if (id == null) {
            return;
        }

        Iterator<PropertyData<?>> iterator = propertyList.iterator();
        while (iterator.hasNext()) {
            PropertyData<?> property = iterator.next();
            if (id.equals(property.getId())) {
                iterator.remove();
                break;
            }
        }

        properties.remove(id);
    }

    @Override
    public String toString() {
        return "Properties Data [properties=" + propertyList + "]" + super.toString();
    }

}
