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
package org.apache.chemistry.opencmis.inmemory.server;

import jakarta.xml.bind.annotation.XmlType;

@XmlType(name = "cmisExtensionType", propOrder = { "s", "i", "f" })
public class ExtensionSample {
    private static final int MAGIC_NUMBER = 42;
    private final String s;
    private final int i;
    private final double f;

    public ExtensionSample() {
        s = "This is an example for a CMIS extension.";
        i = MAGIC_NUMBER;
        f = Math.PI;
    }

    public String getString() {
        return s;
    }

    public int getInt() {
        return i;
    }

    public double getDouble() {
        return f;
    }
}
