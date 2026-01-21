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
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertExtensionHolder;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertTypeContainerList;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setExtensionValues;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Resource;
import jakarta.jws.WebService;
import jakarta.xml.ws.Holder;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.soap.MTOM;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisExtensionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisRepositoryEntryType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisRepositoryInfoType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisTypeContainer;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisTypeDefinitionListType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisTypeDefinitionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.RepositoryServicePort;
import org.apache.chemistry.opencmis.commons.server.CmisService;

/**
 * CMIS Repository Service.
 */
@MTOM
@WebService(endpointInterface = "org.apache.chemistry.opencmis.commons.impl.jaxb.RepositoryServicePort")
public class RepositoryService extends AbstractService implements RepositoryServicePort {
    @Resource
    public WebServiceContext wsContext;

    @Override
    public List<CmisRepositoryEntryType> getRepositories(CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        try {
            service = getServiceForRepositoryInfo(wsContext, null);

            if (stopBeforeService(service)) {
                return null;
            }

            List<RepositoryInfo> infoDataList = service.getRepositoryInfos(convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            if (infoDataList == null) {
                return null;
            }

            List<CmisRepositoryEntryType> result = new ArrayList<CmisRepositoryEntryType>();
            for (RepositoryInfo infoData : infoDataList) {
                CmisRepositoryEntryType entry = new CmisRepositoryEntryType();
                entry.setRepositoryId(infoData.getId());
                entry.setRepositoryName(infoData.getName());

                result.add(entry);
            }

            return result;
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public CmisRepositoryInfoType getRepositoryInfo(String repositoryId, CmisExtensionType extension)
            throws CmisException {
        CmisService service = null;
        CmisVersion cmisVersion = null;
        try {
            service = getServiceForRepositoryInfo(wsContext, repositoryId);
            cmisVersion = getCmisVersion(wsContext);

            if (stopBeforeService(service)) {
                return null;
            }

            RepositoryInfo serviceResult = service.getRepositoryInfo(repositoryId, convert(extension));

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

    @Override
    public CmisTypeDefinitionListType getTypeChildren(String repositoryId, String typeId,
            Boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount, CmisExtensionType extension)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return null;
            }

            TypeDefinitionList serviceResult = service.getTypeChildren(repositoryId, typeId,
                    includePropertyDefinitions, maxItems, skipCount, convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            return convert(serviceResult);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public CmisTypeDefinitionType getTypeDefinition(String repositoryId, String typeId, CmisExtensionType extension)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return null;
            }

            TypeDefinition serviceResult = service.getTypeDefinition(repositoryId, typeId, convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            return convert(serviceResult);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public List<CmisTypeContainer> getTypeDescendants(String repositoryId, String typeId, BigInteger depth,
            Boolean includePropertyDefinitions, CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return null;
            }

            List<TypeDefinitionContainer> serviceResult = service.getTypeDescendants(repositoryId, typeId, depth,
                    includePropertyDefinitions, convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            List<CmisTypeContainer> result = new ArrayList<CmisTypeContainer>();
            convertTypeContainerList(serviceResult, result);

            return result;
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void createType(String repositoryId, jakarta.xml.ws.Holder<CmisTypeDefinitionType> type, CmisExtensionType extension)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return;
            }

            TypeDefinition serviceResult = service.createType(repositoryId, convert(type.value), convert(extension));

            if (stopAfterService(service)) {
                return;
            }

            type.value = convert(serviceResult);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void updateType(String repositoryId, jakarta.xml.ws.Holder<CmisTypeDefinitionType> type, CmisExtensionType extension)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return;
            }

            TypeDefinition serviceResult = service.updateType(repositoryId, convert(type.value), convert(extension));

            if (stopAfterService(service)) {
                return;
            }

            type.value = convert(serviceResult);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void deleteType(String repositoryId, String typeId, jakarta.xml.ws.Holder<CmisExtensionType> extension)
            throws CmisException {

        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            service.deleteType(repositoryId, typeId, extData);

            if (stopAfterService(service)) {
                return;
            }

            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }
}
