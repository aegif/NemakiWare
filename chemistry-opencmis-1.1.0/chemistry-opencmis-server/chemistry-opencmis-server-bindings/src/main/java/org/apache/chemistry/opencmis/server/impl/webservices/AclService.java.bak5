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
package org.apache.chemistry.opencmis.server.impl.webservices;

import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convert;

import jakarta.annotation.Resource;
import jakarta.jws.WebService;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.soap.MTOM;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.impl.jaxb.ACLServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisACLType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisAccessControlListType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisExtensionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumACLPropagation;
import org.apache.chemistry.opencmis.commons.server.CmisService;

/**
 * CMIS ACL Service.
 */
@MTOM
@WebService(endpointInterface = "org.apache.chemistry.opencmis.commons.impl.jaxb.ACLServicePort")
public class AclService extends AbstractService implements ACLServicePort {
    @Resource
    public WebServiceContext wsContext;

    @Override
    public CmisACLType applyACL(String repositoryId, String objectId, CmisAccessControlListType addAces,
            CmisAccessControlListType removeAces, EnumACLPropagation aclPropagation, CmisExtensionType extension)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return null;
            }

            Acl acl = service.applyAcl(repositoryId, objectId, convert(addAces, null), convert(removeAces, null),
                    convert(AclPropagation.class, aclPropagation), convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            if (acl == null) {
                return null;
            }

            CmisACLType result = new CmisACLType();
            result.setACL(convert(acl));
            result.setExact(acl.isExact());

            return result;
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public CmisACLType getACL(String repositoryId, String objectId, Boolean onlyBasicPermissions,
            CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return null;
            }

            Acl acl = service.getAcl(repositoryId, objectId, onlyBasicPermissions, convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            if (acl == null) {
                return null;
            }

            CmisACLType result = new CmisACLType();
            result.setACL(convert(acl));
            result.setExact(acl.isExact());

            return result;
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }
}
