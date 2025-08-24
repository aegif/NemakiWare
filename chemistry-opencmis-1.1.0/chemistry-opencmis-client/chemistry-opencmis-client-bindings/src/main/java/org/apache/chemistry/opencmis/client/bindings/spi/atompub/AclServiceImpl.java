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
package org.apache.chemistry.opencmis.client.bindings.spi.atompub;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomAcl;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.ExtendedAclService;

/**
 * ACL Service AtomPub client.
 */
public class AclServiceImpl extends AbstractAtomPubService implements AclService, ExtendedAclService {

    /**
     * Constructor.
     */
    public AclServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public Acl applyAcl(String repositoryId, String objectId, Acl addAces, Acl removeAces,
            AclPropagation aclPropagation, ExtensionsData extension) {

        // fetch the current ACL
        Acl originalAces = getAcl(repositoryId, objectId, false, null);

        // if no changes required, just return the ACL
        if (!isAclMergeRequired(addAces, removeAces)) {
            return originalAces;
        }

        // merge ACLs
        Acl newACL = mergeAcls(originalAces, addAces, removeAces);

        // update ACL
        AtomAcl acl = updateAcl(repositoryId, objectId, newACL, aclPropagation);
        Acl result = acl.getACL();

        return result;
    }

    @Override
    public Acl getAcl(String repositoryId, String objectId, Boolean onlyBasicPermissions, ExtensionsData extension) {
        return getAclInternal(repositoryId, objectId, onlyBasicPermissions, extension);
    }

    @Override
    public Acl setAcl(String repositoryId, String objectId, Acl aces) {
        AtomAcl acl = updateAcl(repositoryId, objectId, aces, AclPropagation.OBJECTONLY);
        Acl result = acl.getACL();

        return result;
    }
}
