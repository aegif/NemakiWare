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
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertExtensionHolder;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertTypeContainerList;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setExtensionValues;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryInfoImpl;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisExtensionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisRepositoryEntryType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisRepositoryInfoType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisTypeDefinitionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.RepositoryServicePort;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;

/**
 * Repository Service Web Services client.
 */
public class RepositoryServiceImpl extends AbstractWebServicesService implements RepositoryService {

    private final AbstractPortProvider portProvider;

    /**
     * Constructor.
     */
    public RepositoryServiceImpl(BindingSession session, AbstractPortProvider portProvider) {
        setSession(session);
        this.portProvider = portProvider;
    }

    @Override
    public List<RepositoryInfo> getRepositoryInfos(ExtensionsData extension) {
        CmisVersion cmisVersion = CmisBindingsHelper.getForcedCmisVersion(getSession());
        if (cmisVersion == null) {
            cmisVersion = CmisVersion.CMIS_1_1;
        }

        RepositoryServicePort port = portProvider.getRepositoryServicePort(cmisVersion, "getRepositories");

        List<CmisRepositoryEntryType> entries = null;
        try {
            // get the list of repositories
            entries = port.getRepositories(convert(extension));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }

        List<RepositoryInfo> infos = null;
        if (entries != null) {
            port = portProvider.getRepositoryServicePort(cmisVersion, "getRepositoryInfo");

            try {
                infos = new ArrayList<RepositoryInfo>();

                // iterate through the list and fetch repository infos
                for (CmisRepositoryEntryType entry : entries) {
                    try {
                        CmisRepositoryInfoType info = port.getRepositoryInfo(entry.getRepositoryId(), null);
                        infos.add(convert(info));
                    } catch (CmisBaseException e) {
                        // getRepositoryInfo() failed for whatever reason
                        // -> provide at least a repository info stub
                        RepositoryInfoImpl info = new RepositoryInfoImpl();
                        info.setId(entry.getRepositoryId());
                        info.setName(entry.getRepositoryName());
                        infos.add(info);
                    }
                }
            } catch (CmisException e) {
                throw convertException(e);
            } catch (Exception e) {
                throw new CmisRuntimeException("Error: " + e.getMessage(), e);
            } finally {
                portProvider.endCall(port);
            }
        }

        return infos;
    }

    @Override
    public RepositoryInfo getRepositoryInfo(String repositoryId, ExtensionsData extension) {
        CmisVersion cmisVersion = CmisBindingsHelper.getForcedCmisVersion(getSession());
        if (cmisVersion == null) {
            cmisVersion = CmisVersion.CMIS_1_1;
        }

        RepositoryServicePort port = portProvider.getRepositoryServicePort(cmisVersion, "getRepositoryInfo");

        try {
            return convert(port.getRepositoryInfo(repositoryId, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public TypeDefinition getTypeDefinition(String repositoryId, String typeId, ExtensionsData extension) {
        RepositoryServicePort port = portProvider.getRepositoryServicePort(getCmisVersion(repositoryId),
                "getTypeDefinition");

        try {
            return convert(port.getTypeDefinition(repositoryId, typeId, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public TypeDefinitionList getTypeChildren(String repositoryId, String typeId, Boolean includePropertyDefinitions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        RepositoryServicePort port = portProvider.getRepositoryServicePort(getCmisVersion(repositoryId),
                "getTypeChildren");

        try {
            return convert(port.getTypeChildren(repositoryId, typeId, includePropertyDefinitions, maxItems, skipCount,
                    convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public List<TypeDefinitionContainer> getTypeDescendants(String repositoryId, String typeId, BigInteger depth,
            Boolean includePropertyDefinitions, ExtensionsData extension) {
        RepositoryServicePort port = portProvider.getRepositoryServicePort(getCmisVersion(repositoryId),
                "getTypeDescendants");

        try {
            return convertTypeContainerList(port.getTypeDescendants(repositoryId, typeId, depth,
                    includePropertyDefinitions, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public TypeDefinition createType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        if (getCmisVersion(repositoryId) == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("Repository is a CMIS 1.0 repository!");
        }

        RepositoryServicePort port = portProvider.getRepositoryServicePort(CmisVersion.CMIS_1_1, "createType");

        try {
            jakarta.xml.ws.Holder<CmisTypeDefinitionType> typeDef = new jakarta.xml.ws.Holder<CmisTypeDefinitionType>(
                    convert(type));

            port.createType(repositoryId, typeDef, convert(extension));

            return convert(typeDef.value);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public TypeDefinition updateType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        if (getCmisVersion(repositoryId) == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("Repository is a CMIS 1.0 repository!");
        }

        RepositoryServicePort port = portProvider.getRepositoryServicePort(CmisVersion.CMIS_1_1, "updateType");

        try {
            jakarta.xml.ws.Holder<CmisTypeDefinitionType> typeDef = new jakarta.xml.ws.Holder<CmisTypeDefinitionType>(
                    convert(type));

            port.updateType(repositoryId, typeDef, convert(extension));

            return convert(typeDef.value);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public void deleteType(String repositoryId, String typeId, ExtensionsData extension) {
        if (getCmisVersion(repositoryId) == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("Repository is a CMIS 1.0 repository!");
        }

        RepositoryServicePort port = portProvider.getRepositoryServicePort(CmisVersion.CMIS_1_1, "deleteType");

        try {
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.deleteType(repositoryId, typeId, portExtension);

            setExtensionValues(portExtension, extension);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }
}
