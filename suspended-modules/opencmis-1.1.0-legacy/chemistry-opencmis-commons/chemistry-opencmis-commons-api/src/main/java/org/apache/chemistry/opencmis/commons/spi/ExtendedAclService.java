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

/**
 * Extended ACL Service interface.
 * 
 * This interface has NO equivalent in the CMIS specification. It contains ACL
 * convenience operations for clients and is built on top of the CMIS specified
 * operations.
 * 
 * This interface need not to be implemented by CMIS servers.
 */
public interface ExtendedAclService {

    /**
     * Removes the direct ACEs of an object and sets the provided ACEs.
     * 
     * The changes are local to the given object and are not propagated to
     * dependent objects.
     * 
     * The effect of this operation depends on the repository and the binding.
     * It is recommended to process the returned ACL after the method has been
     * called and check if result matches the expectation.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the identifier for the object
     * @param aces
     *            the ACEs
     * @return the ACL of the object
     */
    Acl setAcl(String repositoryId, String objectId, Acl aces);
}
