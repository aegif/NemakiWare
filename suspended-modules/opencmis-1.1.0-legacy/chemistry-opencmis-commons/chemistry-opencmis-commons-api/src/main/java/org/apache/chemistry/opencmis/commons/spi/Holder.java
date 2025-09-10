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

/**
 * Holder for IN/OUT parameters.
 */
public class Holder<T> {

    private T value;

    /**
     * Constructs a holder with a {@code null} value.
     */
    public Holder() {
    }

    /**
     * Constructs a holder with the given value.
     */
    public Holder(T value) {
        this.value = value;
    }

    /**
     * Returns the value.
     * 
     * @return the value of the holder
     */
    public T getValue() {
        return value;
    }

    /**
     * Sets a new value of the holder.
     * 
     * @param value
     *            the new value
     */
    public void setValue(T value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "Holder(" + value + ")";
    }
}
