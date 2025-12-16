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
package org.apache.chemistry.opencmis.client.bindings.impl;

public final class ClientVersion {

    public static final String OPENCMIS_VERSION;
    public static final String OPENCMIS_CLIENT;
    public static final String OPENCMIS_USER_AGENT;

    static {
        Package p = Package.getPackage("org.apache.chemistry.opencmis.client.bindings.impl");
        if (p == null) {
            OPENCMIS_VERSION = "?";
            OPENCMIS_CLIENT = "Apache-Chemistry-OpenCMIS";
        } else {
            OPENCMIS_VERSION = p.getImplementationVersion();
            OPENCMIS_CLIENT = "Apache-Chemistry-OpenCMIS/" + (OPENCMIS_VERSION == null ? "?" : OPENCMIS_VERSION);
        }

        String java = "Java " + System.getProperty("java.version");
        String os = System.getProperty("os.name") + " " + System.getProperty("os.version");

        OPENCMIS_USER_AGENT = OPENCMIS_CLIENT + " (" + java + "; " + os + ")";
    }

    private ClientVersion() {
    }
}
