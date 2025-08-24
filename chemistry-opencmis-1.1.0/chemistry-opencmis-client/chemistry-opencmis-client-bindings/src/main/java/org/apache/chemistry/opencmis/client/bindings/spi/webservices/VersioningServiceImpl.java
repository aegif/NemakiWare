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
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertHolder;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setExtensionValues;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setHolderValue;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisExtensionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisObjectType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumIncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.jaxb.VersioningServicePort;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;

/**
 * Versioning Service Web Services client.
 */
public class VersioningServiceImpl extends AbstractWebServicesService implements VersioningService {

    private final AbstractPortProvider portProvider;

    /**
     * Constructor.
     */
    public VersioningServiceImpl(BindingSession session, AbstractPortProvider portProvider) {
        setSession(session);
        this.portProvider = portProvider;
    }

    @Override
    public void checkOut(String repositoryId, Holder<String> objectId, ExtensionsData extension,
            Holder<Boolean> contentCopied) {
        VersioningServicePort port = portProvider.getVersioningServicePort(getCmisVersion(repositoryId), "checkOut");

        try {
            jakarta.xml.ws.Holder<String> portObjectId = convertHolder(objectId);
            jakarta.xml.ws.Holder<Boolean> portContentCopied = new jakarta.xml.ws.Holder<Boolean>();
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.checkOut(repositoryId, portObjectId, portExtension, portContentCopied);

            setHolderValue(portObjectId, objectId);
            setHolderValue(portContentCopied, contentCopied);
            setExtensionValues(portExtension, extension);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public void cancelCheckOut(String repositoryId, String objectId, ExtensionsData extension) {
        VersioningServicePort port = portProvider.getVersioningServicePort(getCmisVersion(repositoryId),
                "cancelCheckOut");

        try {
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.cancelCheckOut(repositoryId, objectId, portExtension);

            setExtensionValues(portExtension, extension);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public void checkIn(String repositoryId, Holder<String> objectId, Boolean major, Properties properties,
            ContentStream contentStream, String checkinComment, List<String> policies, Acl addACEs, Acl removeACEs,
            ExtensionsData extension) {
        VersioningServicePort port = portProvider.getVersioningServicePort(getCmisVersion(repositoryId), "checkIn");

        try {
            jakarta.xml.ws.Holder<String> portObjectId = convertHolder(objectId);
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.checkIn(repositoryId, portObjectId, major, convert(properties), convert(contentStream, false),
                    checkinComment, policies, convert(addACEs), convert(removeACEs), portExtension);

            setHolderValue(portObjectId, objectId);
            setExtensionValues(portExtension, extension);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public List<ObjectData> getAllVersions(String repositoryId, String objectId, String versionSeriesId, String filter,
            Boolean includeAllowableActions, ExtensionsData extension) {
        VersioningServicePort port = portProvider.getVersioningServicePort(getCmisVersion(repositoryId),
                "getAllVersions");

        try {
            List<CmisObjectType> versionList = port.getAllVersions(repositoryId, versionSeriesId, filter,
                    includeAllowableActions, convert(extension));

            // no list?
            if (versionList == null) {
                return null;
            }

            // convert list
            List<ObjectData> result = new ArrayList<ObjectData>();
            for (CmisObjectType version : versionList) {
                result.add(convert(version));
            }

            return result;
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public ObjectData getObjectOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePolicyIds, Boolean includeACL, ExtensionsData extension) {
        VersioningServicePort port = portProvider.getVersioningServicePort(getCmisVersion(repositoryId),
                "getObjectOfLatestVersion");

        try {
            return convert(port.getObjectOfLatestVersion(repositoryId, versionSeriesId, major, filter,
                    includeAllowableActions, convert(EnumIncludeRelationships.class, includeRelationships),
                    renditionFilter, includePolicyIds, includeACL, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public Properties getPropertiesOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, ExtensionsData extension) {
        VersioningServicePort port = portProvider.getVersioningServicePort(getCmisVersion(repositoryId),
                "getPropertiesOfLatestVersion");

        try {
            return convert(port.getPropertiesOfLatestVersion(repositoryId, versionSeriesId, major, filter,
                    convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }
}
