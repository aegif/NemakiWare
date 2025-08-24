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

import static org.apache.chemistry.opencmis.commons.impl.WSConverter.closeStream;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convert;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertExtensionHolder;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.convertHolder;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setExtensionValues;
import static org.apache.chemistry.opencmis.commons.impl.WSConverter.setHolderValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Resource;
import jakarta.jws.WebService;
import jakarta.xml.ws.Holder;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.soap.MTOM;

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
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisAccessControlListType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisAllowableActionsType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisBulkUpdateType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisContentStreamType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisException;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisExtensionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisObjectIdAndChangeTokenType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisObjectType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisPropertiesType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.CmisRenditionType;
import org.apache.chemistry.opencmis.commons.impl.jaxb.DeleteTreeResponse.FailedToDelete;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumIncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumUnfileObject;
import org.apache.chemistry.opencmis.commons.impl.jaxb.EnumVersioningState;
import org.apache.chemistry.opencmis.commons.impl.jaxb.ObjectServicePort;
import org.apache.chemistry.opencmis.commons.server.CmisService;

/**
 * CMIS Object Service.
 */
@MTOM
@WebService(endpointInterface = "org.apache.chemistry.opencmis.commons.impl.jaxb.ObjectServicePort")
public class ObjectService extends AbstractService implements ObjectServicePort {
    @Resource
    public WebServiceContext wsContext;

    @Override
    public void createDocument(String repositoryId, CmisPropertiesType properties, String folderId,
            CmisContentStreamType contentStream, EnumVersioningState versioningState, List<String> policies,
            CmisAccessControlListType addAces, CmisAccessControlListType removeAces,
            jakarta.xml.ws.Holder<CmisExtensionType> extension, Holder<String> objectId) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            String id = service.createDocument(repositoryId, convert(properties), folderId,
                    convert(contentStream, false), convert(VersioningState.class, versioningState), policies,
                    convert(addAces, null), convert(removeAces, null), extData);

            closeStream(contentStream);

            if (stopAfterService(service)) {
                return;
            }

            if (objectId != null) {
                objectId.value = id;
            }

            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void createDocumentFromSource(String repositoryId, String sourceId, CmisPropertiesType properties,
            String folderId, EnumVersioningState versioningState, List<String> policies,
            CmisAccessControlListType addAces, CmisAccessControlListType removeAces,
            jakarta.xml.ws.Holder<CmisExtensionType> extension, Holder<String> objectId) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            String id = service.createDocumentFromSource(repositoryId, sourceId, convert(properties), folderId,
                    convert(VersioningState.class, versioningState), policies, convert(addAces, null),
                    convert(removeAces, null), extData);

            if (stopAfterService(service)) {
                return;
            }

            if (objectId != null) {
                objectId.value = id;
            }

            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void createFolder(String repositoryId, CmisPropertiesType properties, String folderId,
            List<String> policies, CmisAccessControlListType addAces, CmisAccessControlListType removeAces,
            jakarta.xml.ws.Holder<CmisExtensionType> extension, Holder<String> objectId) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            String id = service.createFolder(repositoryId, convert(properties), folderId, policies,
                    convert(addAces, null), convert(removeAces, null), extData);

            if (stopAfterService(service)) {
                return;
            }

            if (objectId != null) {
                objectId.value = id;
            }

            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void createPolicy(String repositoryId, CmisPropertiesType properties, String folderId,
            List<String> policies, CmisAccessControlListType addAces, CmisAccessControlListType removeAces,
            jakarta.xml.ws.Holder<CmisExtensionType> extension, Holder<String> objectId) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            String id = service.createPolicy(repositoryId, convert(properties), folderId, policies,
                    convert(addAces, null), convert(removeAces, null), extData);

            if (stopAfterService(service)) {
                return;
            }

            if (objectId != null) {
                objectId.value = id;
            }

            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void createRelationship(String repositoryId, CmisPropertiesType properties, List<String> policies,
            CmisAccessControlListType addAces, CmisAccessControlListType removeAces,
            jakarta.xml.ws.Holder<CmisExtensionType> extension, Holder<String> objectId) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            String id = service.createRelationship(repositoryId, convert(properties), policies, convert(addAces, null),
                    convert(removeAces, null), extData);

            if (stopAfterService(service)) {
                return;
            }

            if (objectId != null) {
                objectId.value = id;
            }

            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void createItem(String repositoryId, CmisPropertiesType properties, String folderId,
            CmisAccessControlListType addAces, CmisAccessControlListType removeAces,
            jakarta.xml.ws.Holder<CmisExtensionType> extension, Holder<String> objectId) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            String id = service.createItem(repositoryId, convert(properties), folderId, null, convert(addAces, null),
                    convert(removeAces, null), extData);

            if (stopAfterService(service)) {
                return;
            }

            if (objectId != null) {
                objectId.value = id;
            }

            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void deleteContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            jakarta.xml.ws.Holder<CmisExtensionType> extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder = convertHolder(objectId);
            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder = convertHolder(changeToken);
            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            service.deleteContentStream(repositoryId, objectIdHolder, changeTokenHolder, extData);

            if (stopAfterService(service)) {
                return;
            }

            setHolderValue(objectIdHolder, objectId);
            setHolderValue(changeTokenHolder, changeToken);
            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void deleteObject(String repositoryId, String objectId, Boolean allVersions,
            jakarta.xml.ws.Holder<CmisExtensionType> extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            service.deleteObject(repositoryId, objectId, allVersions, extData);

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

    @Override
    public FailedToDelete deleteTree(String repositoryId, String folderId, Boolean allVersions,
            EnumUnfileObject unfileObjects, Boolean continueOnFailure, CmisExtensionType extension)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return null;
            }

            FailedToDeleteData serviceResult = service.deleteTree(repositoryId, folderId, allVersions,
                    convert(UnfileObject.class, unfileObjects), continueOnFailure, convert(extension));

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
    public CmisAllowableActionsType getAllowableActions(String repositoryId, String objectId,
            CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        CmisVersion cmisVersion = null;
        try {
            service = getService(wsContext, repositoryId);
            cmisVersion = getCmisVersion(wsContext);

            if (stopBeforeService(service)) {
                return null;
            }

            AllowableActions serviceResult = service.getAllowableActions(repositoryId, objectId, convert(extension));

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
    public CmisContentStreamType getContentStream(String repositoryId, String objectId, String streamId,
            BigInteger offset, BigInteger length, CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return null;
            }

            ContentStream serviceResult = service.getContentStream(repositoryId, objectId, streamId, offset, length,
                    convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            return convert(serviceResult, true);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public CmisObjectType getObject(String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, EnumIncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePolicyIds, Boolean includeAcl, CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        CmisVersion cmisVersion = null;
        try {
            service = getService(wsContext, repositoryId);
            cmisVersion = getCmisVersion(wsContext);

            if (stopBeforeService(service)) {
                return null;
            }

            ObjectData serviceResult = service.getObject(repositoryId, objectId, filter, includeAllowableActions,
                    convert(IncludeRelationships.class, includeRelationships), renditionFilter, includePolicyIds,
                    includeAcl, convert(extension));

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
    public CmisObjectType getObjectByPath(String repositoryId, String path, String filter,
            Boolean includeAllowableActions, EnumIncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePolicyIds, Boolean includeAcl, CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        CmisVersion cmisVersion = null;
        try {
            service = getService(wsContext, repositoryId);
            cmisVersion = getCmisVersion(wsContext);

            if (stopBeforeService(service)) {
                return null;
            }

            ObjectData serviceResult = service.getObjectByPath(repositoryId, path, filter, includeAllowableActions,
                    convert(IncludeRelationships.class, includeRelationships), renditionFilter, includePolicyIds,
                    includeAcl, convert(extension));

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
    public CmisPropertiesType getProperties(String repositoryId, String objectId, String filter,
            CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            if (stopBeforeService(service)) {
                return null;
            }

            Properties serviceResult = service.getProperties(repositoryId, objectId, filter, convert(extension));

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
    public List<CmisRenditionType> getRenditions(String repositoryId, String objectId, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, CmisExtensionType extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            List<CmisRenditionType> result = new ArrayList<CmisRenditionType>();

            if (stopBeforeService(service)) {
                return null;
            }

            List<RenditionData> renditionList = service.getRenditions(repositoryId, objectId, renditionFilter,
                    maxItems, skipCount, convert(extension));

            if (stopAfterService(service)) {
                return null;
            }

            if (renditionList != null) {
                for (RenditionData rendition : renditionList) {
                    result.add(convert(rendition));
                }
            }

            return result;
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void moveObject(String repositoryId, Holder<String> objectId, String targetFolderId, String sourceFolderId,
            jakarta.xml.ws.Holder<CmisExtensionType> extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder = convertHolder(objectId);
            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            service.moveObject(repositoryId, objectIdHolder, targetFolderId, sourceFolderId, extData);

            if (stopAfterService(service)) {
                return;
            }

            setHolderValue(objectIdHolder, objectId);
            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void setContentStream(String repositoryId, Holder<String> objectId, Boolean overwriteFlag,
            Holder<String> changeToken, CmisContentStreamType contentStream, jakarta.xml.ws.Holder<CmisExtensionType> extension)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder = convertHolder(objectId);
            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder = convertHolder(changeToken);
            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            service.setContentStream(repositoryId, objectIdHolder, overwriteFlag, changeTokenHolder,
                    convert(contentStream, false), extData);

            closeStream(contentStream);

            if (stopAfterService(service)) {
                return;
            }

            setHolderValue(objectIdHolder, objectId);
            setHolderValue(changeTokenHolder, changeToken);
            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void appendContentStream(String repositoryId, Holder<String> objectId, Boolean isLastChunk,
            Holder<String> changeToken, CmisContentStreamType contentStream, jakarta.xml.ws.Holder<CmisExtensionType> extension)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder = convertHolder(objectId);
            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder = convertHolder(changeToken);
            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            service.appendContentStream(repositoryId, objectIdHolder, changeTokenHolder, convert(contentStream, true),
                    isLastChunk, extData);

            closeStream(contentStream);

            if (stopAfterService(service)) {
                return;
            }

            setHolderValue(objectIdHolder, objectId);
            setHolderValue(changeTokenHolder, changeToken);
            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void updateProperties(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            CmisPropertiesType properties, jakarta.xml.ws.Holder<CmisExtensionType> extension) throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            org.apache.chemistry.opencmis.commons.spi.Holder<String> objectIdHolder = convertHolder(objectId);
            org.apache.chemistry.opencmis.commons.spi.Holder<String> changeTokenHolder = convertHolder(changeToken);
            ExtensionsData extData = convertExtensionHolder(extension);

            if (stopBeforeService(service)) {
                return;
            }

            service.updateProperties(repositoryId, objectIdHolder, changeTokenHolder, convert(properties), extData);

            if (stopAfterService(service)) {
                return;
            }

            setHolderValue(objectIdHolder, objectId);
            setHolderValue(changeTokenHolder, changeToken);
            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }

    @Override
    public void bulkUpdateProperties(String repositoryId, CmisBulkUpdateType bulkUpdateData,
            jakarta.xml.ws.Holder<CmisExtensionType> extension, jakarta.xml.ws.Holder<CmisObjectIdAndChangeTokenType> objectIdAndChangeToken)
            throws CmisException {
        CmisService service = null;
        try {
            service = getService(wsContext, repositoryId);

            ExtensionsData extData = convertExtensionHolder(extension);

            List<BulkUpdateObjectIdAndChangeToken> objectIdsAndChangeTokens = null;
            Properties properties = null;
            List<String> addSecondaryTypeIds = null;
            List<String> removeSecondaryTypeIds = null;
            if (bulkUpdateData != null) {
                if (!bulkUpdateData.getObjectIdAndChangeToken().isEmpty()) {
                    objectIdsAndChangeTokens = new ArrayList<BulkUpdateObjectIdAndChangeToken>();
                    for (CmisObjectIdAndChangeTokenType idAndToken : bulkUpdateData.getObjectIdAndChangeToken()) {
                        objectIdsAndChangeTokens.add(convert(idAndToken));
                    }
                }
                properties = convert(bulkUpdateData.getProperties());
                if (!bulkUpdateData.getAddSecondaryTypeIds().isEmpty()) {
                    addSecondaryTypeIds = bulkUpdateData.getAddSecondaryTypeIds();
                }
                if (!bulkUpdateData.getRemoveSecondaryTypeIds().isEmpty()) {
                    removeSecondaryTypeIds = bulkUpdateData.getRemoveSecondaryTypeIds();
                }
            }

            if (stopBeforeService(service)) {
                return;
            }

            List<BulkUpdateObjectIdAndChangeToken> result = service.bulkUpdateProperties(repositoryId,
                    objectIdsAndChangeTokens, properties, addSecondaryTypeIds, removeSecondaryTypeIds, extData);

            if (stopAfterService(service)) {
                return;
            }

            if (objectIdAndChangeToken != null && result != null) {
                // TODO: add workaround
                // see: https://tools.oasis-open.org/issues/browse/CMIS-754
            }

            setExtensionValues(extData, extension);
        } catch (Exception e) {
            throw convertException(e);
        } finally {
            closeService(service);
        }
    }
}
