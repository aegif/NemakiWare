/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.chemistry.opencmis.client.bindings.spi.local;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.ExtendedAclService;

public class AclServiceImpl extends AbstractLocalService implements AclService, ExtendedAclService {

    /**
     * Constructor.
     */
    public AclServiceImpl(BindingSession session, CmisServiceFactory factory) {
        setSession(session);
        setServiceFactory(factory);
    }

    @Override
    public Acl applyAcl(String repositoryId, String objectId, Acl addAces, Acl removeAces,
            AclPropagation aclPropagation, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            Acl serviceResult = service
                    .applyAcl(repositoryId, objectId, addAces, removeAces, aclPropagation, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public Acl getAcl(String repositoryId, String objectId, Boolean onlyBasicPermissions, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            Acl serviceResult = service.getAcl(repositoryId, objectId, onlyBasicPermissions, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }

    @Override
    public Acl setAcl(String repositoryId, String objectId, Acl aces) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            Acl serviceResult = service.applyAcl(repositoryId, objectId, aces, AclPropagation.OBJECTONLY);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }
}
