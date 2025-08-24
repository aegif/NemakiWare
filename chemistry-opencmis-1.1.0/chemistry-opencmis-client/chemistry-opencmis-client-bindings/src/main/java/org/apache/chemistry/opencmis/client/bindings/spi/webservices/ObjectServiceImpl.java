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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisExtensionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisObjectIdAndChangeTokenType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisRenditionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumIncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumUnfileObject;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumVersioningState;
import org.apache.chemistry.opencmis.commons.impl.jaxb.ObjectServicePort;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;

/**
 * Object Service Web Services client.
 */
public class ObjectServiceImpl extends AbstractWebServicesService implements ObjectService {

    private final AbstractPortProvider portProvider;

    /**
     * Constructor.
     */
    public ObjectServiceImpl(BindingSession session, AbstractPortProvider portProvider) {
        setSession(session);
        this.portProvider = portProvider;
    }

    @Override
    public String createDocument(String repositoryId, Properties properties, String folderId,
            ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addACEs,
            Acl removeACEs, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "createDocument");

        try {
            jakarta.xml.ws.Holder<String> objectId = new jakarta.xml.ws.Holder<String>();
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.createDocument(repositoryId, convert(properties), folderId, convert(contentStream, false),
                    convert(EnumVersioningState.class, versioningState), policies, convert(addACEs),
                    convert(removeACEs), portExtension, objectId);

            setExtensionValues(portExtension, extension);

            return objectId.value;
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public String createDocumentFromSource(String repositoryId, String sourceId, Properties properties,
            String folderId, VersioningState versioningState, List<String> policies, Acl addACEs, Acl removeACEs,
            ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId),
                "createDocumentFromSource");

        try {
            jakarta.xml.ws.Holder<String> objectId = new jakarta.xml.ws.Holder<String>();
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.createDocumentFromSource(repositoryId, sourceId, convert(properties), folderId,
                    convert(EnumVersioningState.class, versioningState), policies, convert(addACEs),
                    convert(removeACEs), portExtension, objectId);

            setExtensionValues(portExtension, extension);

            return objectId.value;
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public String createFolder(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addACEs, Acl removeACEs, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "createFolder");

        try {
            jakarta.xml.ws.Holder<String> objectId = new jakarta.xml.ws.Holder<String>();
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.createFolder(repositoryId, convert(properties), folderId, policies, convert(addACEs),
                    convert(removeACEs), portExtension, objectId);

            setExtensionValues(portExtension, extension);

            return objectId.value;
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public String createPolicy(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addACEs, Acl removeACEs, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "createPolicy");

        try {
            jakarta.xml.ws.Holder<String> objectId = new jakarta.xml.ws.Holder<String>();
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.createPolicy(repositoryId, convert(properties), folderId, policies, convert(addACEs),
                    convert(removeACEs), portExtension, objectId);

            setExtensionValues(portExtension, extension);

            return objectId.value;
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public String createItem(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addACEs, Acl removeACEs, ExtensionsData extension) {
        if (getCmisVersion(repositoryId) == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("Repository is a CMIS 1.0 repository!");
        }

        ObjectServicePort port = portProvider.getObjectServicePort(CmisVersion.CMIS_1_1, "");

        try {
            jakarta.xml.ws.Holder<String> objectId = new jakarta.xml.ws.Holder<String>();
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.createItem(repositoryId, convert(properties), folderId, convert(addACEs), convert(removeACEs),
                    portExtension, objectId);

            setExtensionValues(portExtension, extension);

            return objectId.value;
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public String createRelationship(String repositoryId, Properties properties, List<String> policies, Acl addACEs,
            Acl removeACEs, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "createRelationship");

        try {
            jakarta.xml.ws.Holder<String> objectId = new jakarta.xml.ws.Holder<String>();
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.createRelationship(repositoryId, convert(properties), policies, convert(addACEs), convert(removeACEs),
                    portExtension, objectId);

            setExtensionValues(portExtension, extension);

            return objectId.value;
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public void updateProperties(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            Properties properties, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "updateProperties");

        try {
            jakarta.xml.ws.Holder<String> portObjectId = convertHolder(objectId);
            jakarta.xml.ws.Holder<String> portChangeToken = getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false) ? null
                    : convertHolder(changeToken);
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.updateProperties(repositoryId, portObjectId, portChangeToken, convert(properties), portExtension);

            setHolderValue(portObjectId, objectId);
            setHolderValue(portChangeToken, changeToken);
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
    public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(String repositoryId,
            List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken, Properties properties,
            List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension) {
        if (getCmisVersion(repositoryId) == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("Repository is a CMIS 1.0 repository!");
        }

        ObjectServicePort port = portProvider.getObjectServicePort(CmisVersion.CMIS_1_1, "bulkUpdateProperties");

        try {
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);
            jakarta.xml.ws.Holder<CmisObjectIdAndChangeTokenType> bulkUpdateResponse = new jakarta.xml.ws.Holder<CmisObjectIdAndChangeTokenType>();

            port.bulkUpdateProperties(repositoryId,
                    convert(objectIdAndChangeToken, properties, addSecondaryTypeIds, removeSecondaryTypeIds),
                    portExtension, bulkUpdateResponse);

            setExtensionValues(portExtension, extension);

            List<BulkUpdateObjectIdAndChangeToken> result = null;
            if (bulkUpdateResponse.value != null) {
                // TODO: fix
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
    public void deleteObject(String repositoryId, String objectId, Boolean allVersions, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "deleteObject");

        try {
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.deleteObject(repositoryId, objectId, allVersions, portExtension);

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
    public FailedToDeleteData deleteTree(String repositoryId, String folderId, Boolean allVersions,
            UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "deleteTree");

        try {
            return convert(port.deleteTree(repositoryId, folderId, allVersions,
                    convert(EnumUnfileObject.class, unfileObjects), continueOnFailure, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public AllowableActions getAllowableActions(String repositoryId, String objectId, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "getAllowableActions");

        try {
            return convert(port.getAllowableActions(repositoryId, objectId, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public ContentStream getContentStream(String repositoryId, String objectId, String streamId, BigInteger offset,
            BigInteger length, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "getContentStream");

        try {
            boolean isPartial = false;
            if ((offset != null && offset.signum() == 1) || length != null) {
                isPartial = true;
            }

            return convert(port.getContentStream(repositoryId, objectId, streamId, offset, length, convert(extension)),
                    isPartial);
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public ObjectData getObject(String repositoryId, String objectId, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeACL, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "getObject");

        try {
            return convert(port.getObject(repositoryId, objectId, filter, includeAllowableActions,
                    convert(EnumIncludeRelationships.class, includeRelationships), renditionFilter, includePolicyIds,
                    includeACL, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public ObjectData getObjectByPath(String repositoryId, String path, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeACL, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "getObjectByPath");

        try {
            return convert(port.getObjectByPath(repositoryId, path, filter, includeAllowableActions,
                    convert(EnumIncludeRelationships.class, includeRelationships), renditionFilter, includePolicyIds,
                    includeACL, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public Properties getProperties(String repositoryId, String objectId, String filter, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "getProperties");

        try {
            return convert(port.getProperties(repositoryId, objectId, filter, convert(extension)));
        } catch (CmisException e) {
            throw convertException(e);
        } catch (Exception e) {
            throw new CmisRuntimeException("Error: " + e.getMessage(), e);
        } finally {
            portProvider.endCall(port);
        }
    }

    @Override
    public List<RenditionData> getRenditions(String repositoryId, String objectId, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "getRenditions");

        try {
            List<CmisRenditionType> renditionList = port.getRenditions(repositoryId, objectId, renditionFilter,
                    maxItems, skipCount, convert(extension));

            // no list?
            if (renditionList == null) {
                return null;
            }

            // convert list
            List<RenditionData> result = new ArrayList<RenditionData>();
            for (CmisRenditionType rendition : renditionList) {
                result.add(convert(rendition));
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
    public void moveObject(String repositoryId, Holder<String> objectId, String targetFolderId, String sourceFolderId,
            ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "moveObject");

        try {
            jakarta.xml.ws.Holder<String> portObjectId = convertHolder(objectId);
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.moveObject(repositoryId, portObjectId, targetFolderId, sourceFolderId, portExtension);

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
    public void setContentStream(String repositoryId, Holder<String> objectId, Boolean overwriteFlag,
            Holder<String> changeToken, ContentStream contentStream, ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "setContentStream");

        try {
            jakarta.xml.ws.Holder<String> portObjectId = convertHolder(objectId);
            jakarta.xml.ws.Holder<String> portChangeToken = getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false) ? null
                    : convertHolder(changeToken);
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.setContentStream(repositoryId, portObjectId, overwriteFlag, portChangeToken,
                    convert(contentStream, false), portExtension);

            setHolderValue(portObjectId, objectId);
            setHolderValue(portChangeToken, changeToken);
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
    public void deleteContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ExtensionsData extension) {
        ObjectServicePort port = portProvider.getObjectServicePort(getCmisVersion(repositoryId), "deleteContentStream");

        try {
            jakarta.xml.ws.Holder<String> portObjectId = convertHolder(objectId);
            jakarta.xml.ws.Holder<String> portChangeToken = getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false) ? null
                    : convertHolder(changeToken);
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.deleteContentStream(repositoryId, portObjectId, portChangeToken, portExtension);

            setHolderValue(portObjectId, objectId);
            setHolderValue(portChangeToken, changeToken);
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
    public void appendContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ContentStream contentStream, boolean isLastChunk, ExtensionsData extension) {
        if (getCmisVersion(repositoryId) == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("Repository is a CMIS 1.0 repository!");
        }

        ObjectServicePort port = portProvider.getObjectServicePort(CmisVersion.CMIS_1_1, "appendContentStream");

        try {
            jakarta.xml.ws.Holder<String> portObjectId = convertHolder(objectId);
            jakarta.xml.ws.Holder<String> portChangeToken = getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false) ? null
                    : convertHolder(changeToken);
            jakarta.xml.ws.Holder<CmisExtensionType> portExtension = convertExtensionHolder(extension);

            port.appendContentStream(repositoryId, portObjectId, isLastChunk, portChangeToken,
                    convert(contentStream, false), portExtension);

            setHolderValue(portObjectId, objectId);
            setHolderValue(portChangeToken, changeToken);
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
