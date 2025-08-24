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

import java.math.BigDecimal;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.MutablePropertyDecimal;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;

/**
 * Decimal property data implementation.
 */
public class PropertyDecimalImpl extends AbstractPropertyData<BigDecimal> implements MutablePropertyDecimal {

    private static final long serialVersionUID = 1L;

    public PropertyDecimalImpl() {
    }

    public PropertyDecimalImpl(String id, List<BigDecimal> values) {
        setId(id);
        setValues(values);
    }

    public PropertyDecimalImpl(String id, BigDecimal value) {
        setId(id);
        setValue(value);
    }

    public PropertyDecimalImpl(PropertyDefinition<BigDecimal> propDef, List<BigDecimal> values) {
        setPropertyDefinition(propDef);
        setValues(values);
    }

    public PropertyDecimalImpl(PropertyDefinition<BigDecimal> propDef, BigDecimal value) {
        setPropertyDefinition(propDef);
        setValue(value);
    }
}
