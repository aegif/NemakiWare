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
package org.apache.chemistry.opencmis.commons.definitions;

import java.math.BigDecimal;

import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;

/**
 * Property definition of a decimal property.
 */
public interface PropertyDecimalDefinition extends PropertyDefinition<BigDecimal> {

    /**
     * Returns the min value of this decimal.
     * 
     * @return the min value or <code>null</code> if no limit is specified
     */
    BigDecimal getMinValue();

    /**
     * Returns the max value of this decimal.
     * 
     * @return the max value or <code>null</code> if no limit is specified
     */
    BigDecimal getMaxValue();

    /**
     * Returns the precision this decimal.
     * 
     * @return the precision or <code>null</code> if the decimal supports any
     *         value
     * 
     * @see DecimalPrecision
     */
    DecimalPrecision getPrecision();
}
