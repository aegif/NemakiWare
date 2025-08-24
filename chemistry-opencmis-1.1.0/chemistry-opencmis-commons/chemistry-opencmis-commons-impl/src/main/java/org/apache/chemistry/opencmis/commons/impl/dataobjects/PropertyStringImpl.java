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

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.MutablePropertyString;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;

/**
 * String property data implementation.
 */
public class PropertyStringImpl extends AbstractPropertyData<String> implements MutablePropertyString {

    private static final long serialVersionUID = 1L;

    public PropertyStringImpl() {
    }

    public PropertyStringImpl(String id, List<String> values) {
        setId(id);
        setValues(values);
    }

    public PropertyStringImpl(String id, String value) {
        setId(id);
        setValue(value);
    }

    public PropertyStringImpl(PropertyDefinition<String> propDef, List<String> values) {
        setPropertyDefinition(propDef);
        setValues(values);
    }

    public PropertyStringImpl(PropertyDefinition<String> propDef, String value) {
        setPropertyDefinition(propDef);
        setValue(value);
    }
}
