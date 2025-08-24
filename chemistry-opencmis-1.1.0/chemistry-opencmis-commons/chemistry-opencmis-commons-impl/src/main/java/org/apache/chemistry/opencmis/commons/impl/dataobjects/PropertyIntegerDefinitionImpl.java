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

import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.definitions.MutablePropertyIntegerDefinition;

/**
 * Boolean property definition data implementation.
 */
public class PropertyIntegerDefinitionImpl extends AbstractPropertyDefinition<BigInteger> implements
        MutablePropertyIntegerDefinition {

    private static final long serialVersionUID = 1L;

    private BigInteger minValue;
    private BigInteger maxValue;

    @Override
    public BigInteger getMinValue() {
        return minValue;
    }

    @Override
    public void setMinValue(BigInteger minValue) {
        this.minValue = minValue;
    }

    @Override
    public BigInteger getMaxValue() {
        return maxValue;
    }

    @Override
    public void setMaxValue(BigInteger maxValue) {
        this.maxValue = maxValue;
    }
}
