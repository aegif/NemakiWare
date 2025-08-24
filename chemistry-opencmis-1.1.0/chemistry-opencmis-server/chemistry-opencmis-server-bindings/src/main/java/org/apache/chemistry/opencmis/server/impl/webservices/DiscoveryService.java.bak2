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
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertHolder;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setHolderValue;

import java.math.BigInteger;

import jakarta.annotation.Resource;
import jakarta.jws.WebService;
import jakarta.xml.ws.Holder;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.soap.MTOM;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisExtensionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisObjectListType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.DiscoveryServicePort;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumIncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CmisService;

/**
 * CMIS Discovery Service.
 */
@MTOM
@WebService(endpointInterface = "org.apache.chemistry.opencmis.commons.impl.jaxb.DiscoveryServicePort")
public class DiscoveryService extends AbstractService implements DiscoveryServicePort {
    @Resource
    public WebServiceContext wsContext;

    @Override
    public void getContentChanges(String repositoryId, Holder<String> changeLogToken, Boolean includeProperties,
            String filter, Boolean includePolicyIds, Boolean includeAcl, BigInteger maxItems,
            CmisExtensionType extension, Holder<CmisObjectListType> objects) throws CmisException {
        CmisService service = null;
        CmisVersion cmisVersion = null;
        try {
            service = getService(wsContext, repositoryId);
            cmisVersion = getCmisVersion(wsContext);

            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeLogTokenHolder = convertHolder(changeLogToken);

            if (stopBeforeService(service)) {
                return;
            }

            ObjectList changesList = service.getContentChanges(repositoryId, changeLogTokenHolder, includeProperties,
                    filter, includePolicyIds, includeAcl, maxItems, convert(extension));

            if (stopAfterService(service)) {
                return;
            }

            if (objects != null) {
                objects.value = convert(changesList, cmisVersion);
            }

            setHolderValue(changeLogTokenHolder, changeLogToken);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public CmisObjectListType query(String repositoryId, String statement, Boolean searchAllVersions,
            Boolean includeAllowableActions, EnumIncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        CmisVersion cmisVersion = null;
        try {
            service = getService(wsContext, repositoryId);
            cmisVersion = getCmisVersion(wsContext);

            if (stopBeforeService(service)) {
                return null;
            }

            ObjectList serviceResult = service.query(repositoryId, statement, searchAllVersions,
                    includeAllowableActions, convert(IncludeRelationships.class, includeRelationships),
                    renditionFilter, maxItems, skipCount, convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            return convert(serviceResult, cmisVersion);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }
}
