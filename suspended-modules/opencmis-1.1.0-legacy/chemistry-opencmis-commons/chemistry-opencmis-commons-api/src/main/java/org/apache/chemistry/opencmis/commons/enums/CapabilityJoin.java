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
package org.apache.chemistry.opencmis.commons.enums;

/**
 * Capability Enum: Join.
 */
public enum CapabilityJoin {

    NONE("none"), INNERONLY("inneronly"), INNERANDOUTER("innerandouter");
    private final String value;

    CapabilityJoin(String v) {
        value = v;
    }

    public String value() {
        return value;
    }

    public static CapabilityJoin fromValue(String v) {
        for (CapabilityJoin c : CapabilityJoin.values()) {
            if (c.value.equals(v)) {
                return c;
            }
        }
        throw new IllegalArgumentException(v);
    }

}
