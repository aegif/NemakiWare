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

import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.MutablePropertyDateTime;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;

/**
 * DateTime property data implementation.
 */
public class PropertyDateTimeImpl extends AbstractPropertyData<GregorianCalendar> implements MutablePropertyDateTime {

    private static final long serialVersionUID = 1L;

    public PropertyDateTimeImpl() {
    }

    public PropertyDateTimeImpl(String id, List<GregorianCalendar> values) {
        setId(id);
        setValues(values);
    }

    public PropertyDateTimeImpl(String id, GregorianCalendar value) {
        setId(id);
        setValue(value);
    }

    public PropertyDateTimeImpl(PropertyDefinition<GregorianCalendar> propDef, List<GregorianCalendar> values) {
        setPropertyDefinition(propDef);
        setValues(values);
    }

    public PropertyDateTimeImpl(PropertyDefinition<GregorianCalendar> propDef, GregorianCalendar value) {
        setPropertyDefinition(propDef);
        setValue(value);
    }
}
