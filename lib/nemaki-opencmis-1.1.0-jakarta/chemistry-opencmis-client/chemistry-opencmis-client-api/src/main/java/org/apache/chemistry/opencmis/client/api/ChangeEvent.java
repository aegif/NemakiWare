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
package org.apache.chemistry.opencmis.client.api;

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ChangeEventInfo;

/**
 * Change event in the change log.
 * 
 * @cmis 1.0
 */
public interface ChangeEvent extends ChangeEventInfo {

    /**
     * Gets the ID of the object.
     * 
     * @return the object ID, not {@code null}
     */
    String getObjectId();

    /**
     * Returns the properties.
     * 
     * @return the properties
     */
    Map<String, List<?>> getProperties();

    /**
     * Returns the policy IDs.
     * 
     * @return the policy IDs
     */
    List<String> getPolicyIds();

    /**
     * Returns the ACL.
     * 
     * @return the ACL
     */
    Acl getAcl();
}