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
package org.apache.chemistry.opencmis.commons;

/**
 * Secondary type IDs that are defined in the CMIS specification.
 * 
 * @cmis 1.1
 */
public final class SecondaryTypeIds {

    /** Repository managed retention type. */
    public static final String REPOSITORY_MANAGED_RETENTION = "cmis:rm_repMgtRetention";
    /** Client managed retention type. */
    public static final String CLIENT_MANAGED_RETENTION = "cmis:rm_clientMgtRetention";
    /** Client managed retention type with destruction date. */
    public static final String DESTRUCTION_CLIENT_MANAGED_RETENTION = "cmis:rm_destructionRetention";
    /** Legal hold type. */
    public static final String HOLD = "cmis:rm_hold";

    private SecondaryTypeIds() {
    }
}
