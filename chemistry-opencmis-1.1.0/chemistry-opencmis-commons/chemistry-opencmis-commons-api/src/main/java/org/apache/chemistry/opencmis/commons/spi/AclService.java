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

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;

/**
 * ACL Service interface.
 * 
 * <p>
 * <em>
 * See the CMIS 1.0 and CMIS 1.1 specifications for details on the operations, parameters,
 * exceptions and the domain model.
 * </em>
 * </p>
 */
public interface AclService {

    /**
     * Get the ACL currently applied to the specified object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param onlyBasicPermissions
     *            <em>(optional)</em> an indicator if only basic permissions
     *            should be returned (default is {@code true})
     * @param extension
     *            extension data
     * @return the ACL of the object
     */
    Acl getAcl(String repositoryId, String objectId, Boolean onlyBasicPermissions, ExtensionsData extension);

    /**
     * Adds or removes the given ACEs to or from the ACL of the object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param addAces
     *            <em>(optional)</em> the ACEs to be added
     * @param removeAces
     *            <em>(optional)</em> the ACEs to be removed
     * @param aclPropagation
     *            <em>(optional)</em> specifies how ACEs should be handled
     *            (default is {@link AclPropagation#REPOSITORYDETERMINED})
     * @param extension
     *            extension data
     * @return the ACL of the object
     */
    Acl applyAcl(String repositoryId, String objectId, Acl addAces, Acl removeAces, AclPropagation aclPropagation,
            ExtensionsData extension);
}
