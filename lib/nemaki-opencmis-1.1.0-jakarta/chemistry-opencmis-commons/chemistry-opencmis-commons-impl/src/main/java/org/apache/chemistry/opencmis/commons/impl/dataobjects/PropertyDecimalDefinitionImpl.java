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

import org.apache.chemistry.opencmis.commons.definitions.MutablePropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;

/**
 * Decimal property definition data implementation.
 */
public class PropertyDecimalDefinitionImpl extends AbstractPropertyDefinition<BigDecimal> implements
        MutablePropertyDecimalDefinition {

    private static final long serialVersionUID = 1L;

    private BigDecimal minValue;
    private BigDecimal maxValue;
    private DecimalPrecision precision;

    @Override
    public BigDecimal getMinValue() {
        return minValue;
    }

    @Override
    public void setMinValue(BigDecimal minValue) {
        this.minValue = minValue;
    }

    @Override
    public BigDecimal getMaxValue() {
        return maxValue;
    }

    @Override
    public void setMaxValue(BigDecimal maxValue) {
        this.maxValue = maxValue;
    }

    @Override
    public DecimalPrecision getPrecision() {
        return precision;
    }

    @Override
    public void setPrecision(DecimalPrecision precision) {
        this.precision = precision;
    }
}
