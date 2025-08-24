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
package org.apache.chemistry.opencmis.client.bindings.spi.webservices;

import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convert;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.jaxb.ACLServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumACLPropagation;
import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.ExtendedAclService;

/**
 * ACL Service Web Services client.
 */
public class AclServiceImpl extends AbstractWebServicesService implements AclService, ExtendedAclService {

    private final AbstractPortProvider portProvider;

    /**
     * Constructor.
     */
    public AclServiceImpl(BindingSession session, AbstractPortProvider portProvider) {
        setSession(session);
        this.portProvider = portProvider;
    }

    @Override
    public Acl applyAcl(String repositoryId, String objectId, Acl addACEs, Acl removeACEs,
            AclPropagation aclPropagation, ExtensionsData extension) {
        ACLServicePort port = portProvider.getACLServicePort(getCmisVersion(repositoryId), "applyACL");

        try {
            return convert(port.applyACL(repositoryId, objectId, convert(addACEs), convert(removeACEs),
                    convert(EnumACLPropagation.class, aclPropagation), convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public Acl getAcl(String repositoryId, String objectId, Boolean onlyBasicPermissions, ExtensionsData extension) {
        ACLServicePort port = portProvider.getACLServicePort(getCmisVersion(repositoryId), "getACL");

        try {
            return convert(port.getACL(repositoryId, objectId, onlyBasicPermissions, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public Acl setAcl(String repositoryId, String objectId, Acl aces) {
        Acl currentAcl = getAcl(repositoryId, objectId, false, null);

        List<Ace> removeAces = new ArrayList<Ace>();
        if (currentAcl.getAces() != null) {
            for (Ace ace : currentAcl.getAces()) {
                if (ace.isDirect()) {
                    removeAces.add(ace);
                }
            }
        }

        return applyAcl(repositoryId, objectId, aces, new AccessControlListImpl(removeAces), AclPropagation.OBJECTONLY,
                null);
    }
}
