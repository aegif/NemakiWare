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

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;

/**
 * Policy Service interface.
 * 
 * <p>
 * <em>
 * See the CMIS 1.0 and CMIS 1.1 specifications for details on the operations, parameters,
 * exceptions and the domain model.
 * </em>
 * </p>
 */
public interface PolicyService {

    /**
     * Applies a specified policy to an object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param policyId
     *            the policy to add
     * @param objectId
     *            the object
     * @param extension
     *            extension data
     */
    void applyPolicy(String repositoryId, String policyId, String objectId, ExtensionsData extension);

    /**
     * Removes a specified policy from an object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param policyId
     *            the policy to remove
     * @param objectId
     *            the object
     * @param extension
     *            extension data
     */
    void removePolicy(String repositoryId, String policyId, String objectId, ExtensionsData extension);

    /**
     * Gets the list of policies currently applied to the specified object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the object
     * @param filter
     *            <em>(optional)</em> a comma-separated list of query names that
     *            defines which properties must be returned by the repository
     *            (default is repository specific)
     * @param extension
     *            extension data
     * 
     * @return the list of applied policies
     */
    List<ObjectData> getAppliedPolicies(String repositoryId, String objectId, String filter, ExtensionsData extension);
}
