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
package org.apache.chemistry.opencmis.commons.spi;

import java.util.HashMap;
import java.util.Map;

/**
 * Holder for IN/OUT parameters that can hold extra values.
 */
public class ExtendedHolder<T> extends Holder<T> {

    private Map<String, Object> extraValues = new HashMap<String, Object>();

    /**
     * Constructs a holder with a {@code null} value.
     */
    public ExtendedHolder() {
        super();
    }

    /**
     * Constructs a holder with the given value.
     */
    public ExtendedHolder(T value) {
        super(value);
    }

    /**
     * Sets an extra value.
     * 
     * @param name
     *            the name of the value
     * @param value
     *            the value
     */
    public void setExtraValue(String name, Object value) {
        extraValues.put(name, value);
    }

    /**
     * Gets an extra value,
     * 
     * @param name
     *            the name of the value
     * @return the value or {@code null} if a value with the given name doesn't
     *         exist
     */
    public Object getExtraValue(String name) {
        return extraValues.get(name);
    }

    @Override
    public String toString() {
        return "ExtendedHolder(" + getValue() + ", " + extraValues.toString() + ")";
    }
}
