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
package org.apache.chemistry.opencmis.inmemory.server;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateObjectIdAndChangeTokenImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FailedToDeleteDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.NameValidator;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Content;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Document;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Fileable;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Filing;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Folder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;
import org.apache.chemistry.opencmis.inmemory.types.DocumentTypeCreationHelper;
import org.apache.chemistry.opencmis.inmemory.types.PropertyCreationHelper;
import org.apache.chemistry.opencmis.server.support.TypeManager;
import org.apache.chemistry.opencmis.server.support.TypeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryObjectServiceImpl extends InMemoryAbstractServiceImpl {
    private static final String UNKNOWN_USER = "unknown";
    private static final String UNKNOWN_OBJECT_ID = "Unknown object id: ";
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryServiceFactoryImpl.class.getName());

    public InMemoryObjectServiceImpl(StoreManager storeManager) {
        super(storeManager);
    }

    public String createDocument(CallContext context, String repositoryId, Properties properties, String folderId,
            ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {

        LOG.debug("start createDocument()");
        // Attach the CallContext to a thread local context that can be
        // accessed from everywhere

        StoredObject so = createDocumentIntern(context, repositoryId, properties, folderId, contentStream,
                versioningState, policies, addAces, removeAces, extension);
        LOG.debug("stop createDocument()");
        return so.getId();
    }

    public String createDocumentFromSource(CallContext context, String repositoryId, String sourceId,
            Properties properties, String folderId, VersioningState versioningState, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {

        LOG.debug("start createDocumentFromSource()");
        StoredObject so = validator.createDocumentFromSource(context, repositoryId, sourceId, folderId, policies,
                extension);
        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);

        ContentStream content = getContentStream(context, repositoryId, sourceId, null, BigInteger.valueOf(-1),
                BigInteger.valueOf(-1), null);

        if (so == null) {
            throw new CmisObjectNotFoundException(UNKNOWN_OBJECT_ID + sourceId);
        }

        // build properties collection
        List<String> requestedIds = FilterParser.getRequestedIdsFromFilter("*");
        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        Properties existingProps = PropertyCreationHelper.getPropertiesFromObject(so, objectStore, tm, requestedIds,
                true);

        PropertiesImpl newPD = new PropertiesImpl();
        // copy all existing properties
        for (PropertyData<?> prop : existingProps.getProperties().values()) {
            newPD.addProperty(prop);
        }

        if (null != properties) {
            // overwrite all new properties
            for (PropertyData<?> prop : properties.getProperties().values()) {
                newPD.addProperty(prop);
            }
        }

        String res = createDocument(context, repositoryId, newPD, folderId, content, versioningState, policies,
                addAces, removeAces, null);
        LOG.debug("stop createDocumentFromSource()");
        return res;
    }

    public String createFolder(CallContext context, String repositoryId, Properties properties, String folderId,
            List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {
        LOG.debug("start createFolder()");

        Folder folder = createFolderIntern(context, repositoryId, properties, folderId, policies, addAces, removeAces,
                extension);
        LOG.debug("stop createFolder()");
        return folder.getId();
    }

    public String createPolicy(CallContext context, String repositoryId, Properties properties, String folderId,
            List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {

        LOG.debug("start createPolicy()");
        StoredObject so = createPolicyIntern(context, repositoryId, properties, folderId, policies, addAces,
                removeAces, extension);
        LOG.debug("stop createPolicy()");
        return so == null ? null : so.getId();
    }

    public String createRelationship(CallContext context, String repositoryId, Properties properties,
            List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {

        LOG.debug("start createRelationship()");
        StoredObject so = createRelationshipIntern(context, repositoryId, properties, policies, addAces, removeAces,
                extension);
        LOG.debug("stop createRelationship()");
        return so == null ? null : so.getId();
    }

    // CMIS 1.1
    public String createItem(CallContext context, String repositoryId, Properties properties, String folderId,
            List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {
        StoredObject so = createItemIntern(context, repositoryId, properties, folderId, policies, addAces, removeAces,
                extension);
        return so.getId();
    }

    @SuppressWarnings("unchecked")
    public String create(CallContext context, String repositoryId, Properties properties, String folderId,
            ContentStream contentStream, VersioningState versioningState, List<String> policies,
            ExtensionsData extension, ObjectInfoHandler objectInfos) {

        if (null == properties || null == properties.getProperties()) {
            throw new CmisInvalidArgumentException("Cannot create object, without properties.");
        }

        // Find out what kind of object needs to be created
        PropertyData<String> pd = (PropertyData<String>) properties.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        String typeId = pd == null ? null : pd.getFirstValue();
        if (null == typeId) {
            throw new CmisInvalidArgumentException(
                    "Cannot create object, without a type (no property with id CMIS_OBJECT_TYPE_ID).");
        }

        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinitionContainer typeDefC = fStoreManager.getTypeById(repositoryId, typeId, cmis11);
        if (typeDefC == null) {
            throw new CmisInvalidArgumentException("Cannot create object, a type with id " + typeId + " is unknown");
        }

        // check if the given type is a document type
        BaseTypeId typeBaseId = typeDefC.getTypeDefinition().getBaseTypeId();
        StoredObject so = null;
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        if (typeBaseId.equals(DocumentTypeCreationHelper.getCmisDocumentType().getBaseTypeId())) {
            so = createDocumentIntern(context, repositoryId, properties, folderId, contentStream, versioningState,
                    null, null, null, null);
        } else if (typeBaseId.equals(DocumentTypeCreationHelper.getCmisFolderType().getBaseTypeId())) {
            so = createFolderIntern(context, repositoryId, properties, folderId, null, null, null, null);
        } else if (typeBaseId.equals(DocumentTypeCreationHelper.getCmisPolicyType().getBaseTypeId())) {
            so = createPolicyIntern(context, repositoryId, properties, folderId, null, null, null, null);
        } else if (typeBaseId.equals(DocumentTypeCreationHelper.getCmisRelationshipType().getBaseTypeId())) {
            so = createRelationshipIntern(context, repositoryId, properties, null, null, null, null);
        } else if (typeBaseId.equals(DocumentTypeCreationHelper.getCmisItemType().getBaseTypeId())) {
            so = createItemIntern(context, repositoryId, properties, folderId, null, null, null, null);
        } else {
            LOG.error("The type contains an unknown base object id, object can't be created");
        }

        // Make a call to getObject to convert the resulting id into an
        // ObjectData
        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        ObjectData od = PropertyCreationHelper.getObjectData(context, tm, objStore, so, null, context.getUsername(), false,
                IncludeRelationships.NONE, null, false, false, extension);

        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, od, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }
        return so != null ? so.getId() : null;
    }

    public void deleteContentStream(CallContext context, String repositoryId, Holder<String> objectId,
            Holder<String> changeToken, ExtensionsData extension) {

        LOG.debug("start deleteContentStream()");
        StoredObject so = validator.deleteContentStream(context, repositoryId, objectId, extension);

        if (so == null) {
            throw new CmisObjectNotFoundException(UNKNOWN_OBJECT_ID + objectId);
        }

        if (so.getChangeToken() != null && (changeToken == null
                || !so.getChangeToken().equals(changeToken.getValue()))) {
            throw new CmisUpdateConflictException("deleteContentStream failed, ChangeToken does not match.");
        }

        if (!(so instanceof Content)) {
            throw new CmisObjectNotFoundException("Id" + objectId
                    + " does not refer to a document, but only documents can have content");
        }

        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);
        objectStore.setContent(so, null);
        if (null != changeToken) {
            String changeTokenVal = so.getChangeToken();
            LOG.debug("deleteContentStream(), new change token is: " + changeTokenVal);
            changeToken.setValue(changeTokenVal);
        }
        LOG.debug("stop deleteContentStream()");
    }

    public void deleteObject(CallContext context, String repositoryId, String objectId, Boolean allVersions,
            ExtensionsData extension) {

        LOG.debug("start deleteObject()");
        validator.deleteObject(context, repositoryId, objectId, allVersions, extension);
        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);
        LOG.debug("delete object for id: " + objectId);

        // check if it is the root folder
        if (objectId.equals(objectStore.getRootFolder().getId())) {
            throw new CmisNotSupportedException("You can't delete a root folder");
        }

        objectStore.deleteObject(objectId, allVersions, context.getUsername());
        LOG.debug("stop deleteObject()");
    }

    public FailedToDeleteData deleteTree(CallContext context, String repositoryId, String folderId, Boolean allVers,
            UnfileObject unfile, Boolean continueOnFail, ExtensionsData extension) {

        LOG.debug("start deleteTree()");
        boolean allVersions = (null == allVers ? true : allVers);
        UnfileObject unfileObjects = (null == unfile ? UnfileObject.DELETE : unfile);
        boolean continueOnFailure = (null == continueOnFail ? false : continueOnFail);
        StoredObject so = validator.deleteTree(context, repositoryId, folderId, allVersions, unfileObjects, extension);
        List<String> failedToDeleteIds = new ArrayList<String>();
        FailedToDeleteDataImpl result = new FailedToDeleteDataImpl();

        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);

        if (null == so) {
            throw new CmisInvalidArgumentException("Cannot delete object with id  " + folderId
                    + ". Object does not exist.");
        }

        if (!(so instanceof Folder)) {
            throw new CmisInvalidArgumentException("deleteTree can only be invoked on a folder, but id " + folderId
                    + " does not refer to a folder");
        }

        if (unfileObjects == UnfileObject.UNFILE) {
            throw new CmisNotSupportedException("This repository does not support unfile operations.");
        }

        // check if it is the root folder
        if (folderId.equals(objectStore.getRootFolder().getId())) {
            throw new CmisNotSupportedException("You can't delete a root folder");
        }

        // recursively delete folder
        deleteRecursive(objectStore, (Folder) so, continueOnFailure, allVersions, failedToDeleteIds,
                context.getUsername());

        result.setIds(failedToDeleteIds);
        LOG.debug("stop deleteTree()");
        return result;
    }

    public AllowableActions getAllowableActions(CallContext context, String repositoryId, String objectId,
            ExtensionsData extension) {

        LOG.debug("start getAllowableActions()");
        StoredObject so = validator.getAllowableActions(context, repositoryId, objectId, extension);

        fStoreManager.getObjectStore(repositoryId);

        if (so == null) {
            throw new CmisObjectNotFoundException(UNKNOWN_OBJECT_ID + objectId);
        }

        String user = context.getUsername();
        AllowableActions allowableActions = so.getAllowableActions(context, user);
        LOG.debug("stop getAllowableActions()");
        return allowableActions;
    }

    public ContentStream getContentStream(CallContext context, String repositoryId, String objectId, String streamId,
            BigInteger offset, BigInteger length, ExtensionsData extension) {

        LOG.debug("start getContentStream()");
        StoredObject so = validator.getContentStream(context, repositoryId, objectId, streamId, extension);

        if (so == null) {
            throw new CmisObjectNotFoundException(UNKNOWN_OBJECT_ID + objectId);
        }

        if (!(so instanceof Content) && objectId.endsWith("-rendition")) {
            throw new CmisConstraintException("Id" + objectId
                    + " does not refer to a document or version, but only those can have content");
        }

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        ContentStream csd = getContentStream(objStore, so, streamId, offset, length);

        if (null == csd) {
            throw new CmisConstraintException("Object " + so.getId() + " does not have content.");
        }

        LOG.debug("stop getContentStream()");
        return csd;
    }

    public ObjectData getObject(CallContext context, String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension, ObjectInfoHandler objectInfos) {

        LOG.debug("start getObject()");

        StoredObject so = validator.getObject(context, repositoryId, objectId, extension);
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        if (so == null) {
            throw new CmisObjectNotFoundException(UNKNOWN_OBJECT_ID + objectId);
        }

        String user = context.getUsername();
        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        ObjectData od = PropertyCreationHelper.getObjectData(context, tm, objStore, so, filter, user, includeAllowableActions,
                includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension);

        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        // fill an example extension
        String ns = "http://apache.org/opencmis/inmemory";
        List<CmisExtensionElement> extElements = new ArrayList<CmisExtensionElement>();

        Map<String, String> attr = new HashMap<String, String>();
        attr.put("type", so.getTypeId());

        extElements.add(new CmisExtensionElementImpl(ns, "objectId", attr, objectId));
        extElements.add(new CmisExtensionElementImpl(ns, "name", null, so.getName()));
        od.setExtensions(Collections.singletonList((CmisExtensionElement) new CmisExtensionElementImpl(ns,
                "exampleExtension", null, extElements)));

        LOG.debug("stop getObject()");

        return od;
    }

    public ObjectData getObjectByPath(CallContext context, String repositoryId, String path, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension, ObjectInfoHandler objectInfos) {

        LOG.debug("start getObjectByPath()");
        StoredObject so = validator.getObjectByPath(context, repositoryId, path, extension);
        if (so instanceof VersionedDocument) {
            VersionedDocument verDoc = (VersionedDocument) so;
            so = verDoc.getLatestVersion(false);
        }

        String user = context.getUsername();
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        ObjectData od = PropertyCreationHelper.getObjectData(context, tm, objStore, so, filter, user, includeAllowableActions,
                includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension);

        LOG.debug("stop getObjectByPath()");

        // To be able to provide all Atom links in the response we need
        // additional information:
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        return od;
    }

    public Properties getProperties(CallContext context, String repositoryId, String objectId, String filter,
            ExtensionsData extension) {

        LOG.debug("start getProperties()");
        StoredObject so = validator.getProperties(context, repositoryId, objectId, extension);
        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);

        if (so == null) {
            throw new CmisObjectNotFoundException(UNKNOWN_OBJECT_ID + objectId);
        }

        // build properties collection
        List<String> requestedIds = FilterParser.getRequestedIdsFromFilter(filter);
        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        Properties props = PropertyCreationHelper.getPropertiesFromObject(so, objectStore, tm, requestedIds, true);
        LOG.debug("stop getProperties()");
        return props;
    }

    public List<RenditionData> getRenditions(CallContext context, String repositoryId, String objectId,
            String renditionFilter, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

        LOG.debug("start getRenditions()");
        StoredObject so = validator.getRenditions(context, repositoryId, objectId, extension);

        if (so == null) {
            throw new CmisObjectNotFoundException(UNKNOWN_OBJECT_ID + objectId);
        }

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        List<RenditionData> renditions = objStore.getRenditions(so, renditionFilter, maxItems == null ? 0 : maxItems.longValue(),
                skipCount == null ? 0 : skipCount.longValue());
        LOG.debug("stop getRenditions()");
        return renditions;
    }

    public ObjectData moveObject(CallContext context, String repositoryId, Holder<String> objectId,
            String targetFolderId, String sourceFolderId, ExtensionsData extension, ObjectInfoHandler objectInfos) {

        LOG.debug("start moveObject()");
        StoredObject[] sos = validator.moveObject(context, repositoryId, objectId, targetFolderId, sourceFolderId,
                extension);
        StoredObject so = sos[0];
        Folder targetFolder = null;
        Folder sourceFolder = null;
        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);
        String user = context.getUsername();

        if (null == so) {
            throw new CmisObjectNotFoundException("Unknown object: " + objectId.getValue());
        } else if (!(so instanceof Filing)) {
            throw new CmisInvalidArgumentException("Object must be fileable: " + objectId.getValue());
        }

        StoredObject soTarget = objectStore.getObjectById(targetFolderId);
        if (null == soTarget) {
            throw new CmisObjectNotFoundException("Unknown target folder: " + targetFolderId);
        } else if (soTarget instanceof Folder) {
            targetFolder = (Folder) soTarget;
        } else {
            throw new CmisNotSupportedException("Destination " + targetFolderId
                    + " of a move operation must be a folder");
        }

        StoredObject soSource = objectStore.getObjectById(sourceFolderId);
        if (null == soSource) {
            throw new CmisObjectNotFoundException("Unknown source folder: " + sourceFolderId);
        } else if (soSource instanceof Folder) {
            sourceFolder = (Folder) soSource;
        } else {
            throw new CmisNotSupportedException("Source " + sourceFolderId + " of a move operation must be a folder");
        }

        boolean foundOldParent = false;
        for (String parentId : objectStore.getParentIds(so, user)) {
            if (parentId.equals(soSource.getId())) {
                foundOldParent = true;
                break;
            }
        }
        if (!foundOldParent) {
            throw new CmisNotSupportedException("Cannot move object, source folder " + sourceFolderId
                    + "is not a parent of object " + objectId.getValue());
        }

        if (so instanceof Folder && hasDescendant(context.getUsername(), objectStore, (Folder) so, targetFolder)) {
            throw new CmisNotSupportedException("Destination of a move cannot be a subfolder of the source");
        }

        objectStore.move(so, sourceFolder, targetFolder, user);
        objectId.setValue(so.getId());

        LOG.debug("stop moveObject()");

        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        ObjectData od = PropertyCreationHelper.getObjectData(context, tm, objectStore, so, null, user, false,
                IncludeRelationships.NONE, null, false, false, extension);

        // To be able to provide all Atom links in the response we need
        // additional information:
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, od, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        return od;
    }

    public void setContentStream(CallContext context, String repositoryId, Holder<String> objectId, Boolean overwrite,
            Holder<String> changeToken, ContentStream contentStream, ExtensionsData extension) {

        LOG.debug("start setContentStream()");
        boolean overwriteFlag = (overwrite == null ? true : overwrite);
        Content content;

        StoredObject so = validator.setContentStream(context, repositoryId, objectId, overwriteFlag, extension);

        if (changeToken != null && changeToken.getValue() != null
                && Long.valueOf(so.getChangeToken()) > Long.valueOf(changeToken.getValue())) {
            throw new CmisUpdateConflictException("setContentStream failed: changeToken does not match");
        }

        if (!(so instanceof Document || so instanceof VersionedDocument || so instanceof DocumentVersion)) {
            throw new CmisObjectNotFoundException("Id" + objectId
                    + " does not refer to a document, but only documents can have content");
        }

        // validate content allowed
        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = getTypeDefinition(repositoryId, so, cmis11);
        if (!(typeDef instanceof DocumentTypeDefinition)) {
            throw new CmisInvalidArgumentException("Object does not refer to a document, can't set content");
        }
        TypeValidator.validateContentAllowed((DocumentTypeDefinition) typeDef, null != contentStream);

        if (so instanceof Document) {
            content = ((Document) so);
        } else if (so instanceof DocumentVersion) {
            // something that is versionable check the proper status of the
            // object
            String user = context.getUsername();
            testHasProperCheckedOutStatus(so, user);
            content = (DocumentVersion) so;
        } else {
            throw new IllegalArgumentException("Content cannot be set on this object (must be document or version)");
        }

        if (!overwriteFlag && content.hasContent()) {
            throw new CmisContentAlreadyExistsException(
                    "cannot overwrite existing content if overwrite flag is not set");
        }

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        objStore.setContent(so, contentStream);
        so.updateSystemBasePropertiesWhenModified(null, context.getUsername());
        if (null != changeToken) {
            String changeTokenVal = so.getChangeToken();
            LOG.debug("setContentStream(), new change token is: " + changeTokenVal);
            changeToken.setValue(changeTokenVal);
        }
        LOG.debug("stop setContentStream()");
    }

    public void updateProperties(CallContext context, String repositoryId, Holder<String> objectId,
            Holder<String> changeToken, Properties properties, Acl acl, ExtensionsData extension,
            ObjectInfoHandler objectInfos) {

        LOG.debug("start updateProperties()");
        if (properties == null) {
            throw new CmisRuntimeException("update properties: no properties given for object id: "
                    + objectId.getValue());
        }
        StoredObject so = validator.updateProperties(context, repositoryId, objectId, extension);
        String user = context.getUsername();
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        // Validation
        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = getTypeDefinition(repositoryId, so, cmis11);
        boolean isCheckedOut = false;

        isCheckedOut = isCheckedOut(so, user);

        Map<String, PropertyData<?>> oldProperties = new HashMap<String, PropertyData<?>>();

        // check properties for validity
        validateProperties(repositoryId, so, properties, false, cmis11);

        if (changeToken != null && changeToken.getValue() != null
                && Long.valueOf(so.getChangeToken()) > Long.valueOf(changeToken.getValue())) {
            throw new CmisUpdateConflictException("updateProperties failed: changeToken does not match");
        }

        // update properties
        boolean hasUpdatedProp = false;

        // Find secondary type definitions to consider for update
        List<String> existingSecondaryTypeIds = so.getSecondaryTypeIds();
        @SuppressWarnings("unchecked")
        PropertyData<String> pdSec = (PropertyData<String>) properties.getProperties().get(
                PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
        List<String> newSecondaryTypeIds = pdSec == null ? null : pdSec.getValues();
        Set<String> secondaryTypeIds = new HashSet<String>();
        if (null != existingSecondaryTypeIds) {
            secondaryTypeIds.addAll(existingSecondaryTypeIds);
        }
        if (null != newSecondaryTypeIds) {
            secondaryTypeIds.addAll(newSecondaryTypeIds);
        }

        // Find secondary type definitions to delete (null means not set --> do
        // not change, empty --> remove all secondary types)
        if (null != newSecondaryTypeIds) {
            List<String> propertiesIdToDelete = getListOfPropertiesToDeleteFromRemovedSecondaryTypes(context, repositoryId, so,
                    newSecondaryTypeIds);
            for (String propIdToRemove : propertiesIdToDelete) {
                oldProperties.put(propIdToRemove, null);
            }
        }

        // update properties:
        for (String key : properties.getProperties().keySet()) {
            if (key.equals(PropertyIds.NAME)) {
                continue; // ignore here
            }

            PropertyData<?> value = properties.getProperties().get(key);
            PropertyDefinition<?> propDef = typeDef.getPropertyDefinitions().get(key);
            if (cmis11 && null == propDef) {
                TypeDefinition typeDefSecondary = getSecondaryTypeDefinition(context, repositoryId, secondaryTypeIds, key);
                if (null == typeDefSecondary) {
                    throw new CmisInvalidArgumentException("Cannot update property " + key + ": not contained in type");
                }
                propDef = typeDefSecondary.getPropertyDefinitions().get(key);
            }

            if (null == propDef) {
                throw new CmisInvalidArgumentException("Unknown property " + key
                        + ": not contained in type (or any secondary type)");
            }

            if (value.getValues() == null || value.getFirstValue() == null) {
                // delete property
                // check if a required a property
                if (propDef.isRequired()) {
                    throw new CmisConstraintException(
                            "updateProperties failed, following property can't be deleted, because it is required: "
                                    + key);
                }
                oldProperties.put(key, null);
                hasUpdatedProp = true;
            } else {
                if (propDef.getUpdatability() == Updatability.WHENCHECKEDOUT) {
                    if (!isCheckedOut) {
                        throw new CmisUpdateConflictException(
                                "updateProperties failed, following property can't be updated, because it is not "
                                + "checked-out: " + key);
                    }
                } else if (propDef.getUpdatability() != Updatability.READWRITE) {
                    throw new CmisConstraintException(
                            "updateProperties failed, following property can't be updated, because it is not writable: "
                                    + key);
                }
                oldProperties.put(key, value);
                hasUpdatedProp = true;
            }
        }
        
        // get name from properties and perform special rename to check if
        // path already exists
        PropertyData<?> pd = properties.getProperties().get(PropertyIds.NAME);
        if (pd != null && so instanceof Filing) {
            String newName = (String) pd.getFirstValue();
            boolean hasParent = ((Filing) so).hasParent();
            if (so instanceof Folder && !hasParent) {
                throw new CmisConstraintException("updateProperties failed, you cannot rename the root folder");
            }
            if (newName == null || newName.equals("")) {
                throw new CmisConstraintException("updateProperties failed, name must not be empty.");
            }
            if (!NameValidator.isValidName(newName)) {
                throw new CmisInvalidArgumentException(NameValidator.ERROR_ILLEGAL_NAME);
            }
            // Note: the test for duplicated name in folder is left to the
            // object store
            objStore.rename((Fileable) so, (String) pd.getFirstValue(), user);
            hasUpdatedProp = true;
        }

        objStore.updateObject(so, oldProperties, user);

        if (hasUpdatedProp) {
            objectId.setValue(so.getId()); // might have a new id
            if (null != changeToken) {
                String changeTokenVal = so.getChangeToken();
                LOG.debug("updateProperties(), new change token is: " + changeTokenVal);
                changeToken.setValue(changeTokenVal);
            }
        }

        if (null != acl) {
            objStore.applyAcl(so, acl, AclPropagation.OBJECTONLY, user);
        }

        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        ObjectData od = PropertyCreationHelper.getObjectData(context, tm, objStore, so, null, user, false,
                IncludeRelationships.NONE, null, false, false, extension);

        // To be able to provide all Atom links in the response we need
        // additional information:
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, od, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        LOG.debug("stop updateProperties()");
    }

    // CMIS 1.1
    public void appendContentStream(CallContext context, String repositoryId, Holder<String> objectId,
            Holder<String> changeToken, ContentStream contentStream, ExtensionsData extension) {

        LOG.debug("start appendContentStream()");
        StoredObject so = validator.appendContentStream(context, repositoryId, objectId, extension);

        if (changeToken != null && changeToken.getValue() != null
                && Long.valueOf(so.getChangeToken()) > Long.valueOf(changeToken.getValue())) {
            throw new CmisUpdateConflictException("appendContentStream failed: changeToken does not match");
        }

        if (!(so instanceof Document || so instanceof VersionedDocument || so instanceof DocumentVersion)) {
            throw new CmisObjectNotFoundException("Id" + objectId
                    + " does not refer to a document, but only documents can have content");
        }

        // validate content allowed
        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = getTypeDefinition(repositoryId, so, cmis11);
        if (!(typeDef instanceof DocumentTypeDefinition)) {
            throw new CmisInvalidArgumentException("Object does not refer to a document, can't set content");
        }
        TypeValidator.validateContentAllowed((DocumentTypeDefinition) typeDef, null != contentStream);

        if (so instanceof DocumentVersion) {
            // something that is versionable check the proper status of the
            // object
            String user = context.getUsername();
            testHasProperCheckedOutStatus(so, user);
        } else if (!(so instanceof Document)){
            throw new IllegalArgumentException("Content cannot be set on this object (must be document or version)");
        }

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        objStore.appendContent(so, contentStream);
        so.updateSystemBasePropertiesWhenModified(null, context.getUsername());
        if (null != changeToken) {
            String changeTokenVal = so.getChangeToken();
            LOG.debug("appendContentStream(), new change token is: " + changeTokenVal);
            changeToken.setValue(changeTokenVal);
        }
    }

    public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(CallContext context, String repositoryId,
            List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken, Properties properties,
            List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension,
            ObjectInfoHandler objectInfos) {

        List<BulkUpdateObjectIdAndChangeToken> result = new ArrayList<BulkUpdateObjectIdAndChangeToken>();
        for (BulkUpdateObjectIdAndChangeToken obj : objectIdAndChangeToken) {
            Holder<String> objId = new Holder<String>(obj.getId());
            Holder<String> changeToken = new Holder<String>(obj.getChangeToken());
            try {
                updateProperties(context, repositoryId, objId, changeToken, properties, null, null, objectInfos);
                result.add(new BulkUpdateObjectIdAndChangeTokenImpl(obj.getId(), changeToken.getValue()));
            } catch (Exception e) {
                LOG.error("updating properties in bulk upadate failed for object" + obj.getId() + ": ", e);
            }
        }
        return result;
    }

    // ///////////////////////////////////////////////////////
    // private helper methods

    private StoredObject createDocumentIntern(CallContext context, String repositoryId, Properties properties,
            String folderId, ContentStream contentStream, VersioningState versioningState, List<String> policies,
            Acl addACEs, Acl removeACEs, ExtensionsData extension) {

        Acl aclAdd = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                addACEs);
        Acl aclRemove = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                removeACEs);

        StoredObject so = validator.createDocument(context, repositoryId, folderId, policies, extension);

        // Validation stuff
        TypeValidator.validateRequiredSystemProperties(properties);

        String user = context.getUsername();
        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = getTypeDefinition(repositoryId, properties, cmis11);

        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);
        Map<String, PropertyData<?>> propMap = properties.getProperties();
        // get name from properties
        PropertyData<?> pd = propMap.get(PropertyIds.NAME);
        String name = (String) pd.getFirstValue();

        // validate ACL
        TypeValidator.validateAcl(typeDef, aclAdd, aclRemove);

        Folder folder = null;
        if (null != folderId) {
            if (null == so) {
                throw new CmisInvalidArgumentException(" Cannot create document, folderId: " + folderId 
                        + " is invalid");
            }

            if (so instanceof Folder) {
                folder = (Folder) so;
            } else {
                throw new CmisInvalidArgumentException("Can't creat document, folderId does not refer to a folder: "
                        + folderId);
            }

            TypeValidator.validateAllowedChildObjectTypes(typeDef, folder.getAllowedChildObjectTypeIds());
        }

        // check if the given type is a document type
        if (!typeDef.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT)) {
            throw new CmisInvalidArgumentException("Cannot create a document, with a non-document type: "
                    + typeDef.getId());
        }

        // check name syntax
        if (!NameValidator.isValidName(name)) {
            throw new CmisInvalidArgumentException(NameValidator.ERROR_ILLEGAL_NAME + " Name is: " + name);
        }

        // validate content allowed
        TypeValidator.validateContentAllowed((DocumentTypeDefinition) typeDef, null != contentStream);

        // Check that documents are not created as checked-out as this results in an inconsistent state
        TypeValidator.validateVersionStateForCreate((DocumentTypeDefinition) typeDef, versioningState);
        if (typeDef instanceof DocumentTypeDefinition && ((DocumentTypeDefinition) typeDef).isVersionable() 
        		&& null != versioningState && versioningState.equals(VersioningState.CHECKEDOUT)) {
            throw new CmisConstraintException("Creating of checked-out documents is not supported.");
        }

        // set properties that are not set but have a default:
        Map<String, PropertyData<?>> propMapNew = setDefaultProperties(typeDef, propMap);
        if (propMapNew != propMap) {
            properties = new PropertiesImpl(propMapNew.values());
            propMap = propMapNew;
        }

        validateProperties(repositoryId, null, properties, false, cmis11);

        // set user, creation date, etc.
        if (user == null) {
            user = UNKNOWN_USER;
        }

        StoredObject createdDoc = null;
        ContentStream contentStreamNew = contentStream;
        // check if content stream parameters are set and if not set some
        // defaults
        if (null != contentStream
                && (contentStream.getFileName() == null || contentStream.getFileName().length() == 0
                        || contentStream.getMimeType() == null || contentStream.getMimeType().length() == 0)) {
            ContentStreamImpl cs = new ContentStreamImpl();
            cs.setStream(contentStream.getStream());
            if (contentStream.getFileName() == null || contentStream.getFileName().length() == 0) {
                cs.setFileName(name);
            } else {
                cs.setFileName(contentStream.getFileName());
            }
            cs.setLength(contentStream.getBigLength());
            if (contentStream.getMimeType() == null || contentStream.getMimeType().length() == 0) {
                cs.setMimeType("application/octet-stream");
            } else {
                cs.setMimeType(contentStream.getMimeType());
            }
            cs.setExtensions(contentStream.getExtensions());
            contentStreamNew = cs;
        }

        // Now we are sure to have document type definition:
        if (((DocumentTypeDefinition) typeDef).isVersionable()) {
            DocumentVersion version = objectStore.createVersionedDocument(name, propMap, user, folder, policies,
                    aclAdd, aclRemove, contentStreamNew, versioningState);
            createdDoc = version; // return the version and not the version series to
                          // caller
        } else {
            Document doc = objectStore.createDocument(propMap, user, folder, contentStreamNew, policies, aclAdd, aclRemove);
            createdDoc = doc;
        }

        return createdDoc;
    }

    private Folder createFolderIntern(CallContext context, String repositoryId, Properties properties, String folderId,
            List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {

        Acl aclAdd = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                addAces);
        Acl aclRemove = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                removeAces);
        Properties propertiesNew = properties;

        validator.createFolder(context, repositoryId, folderId, policies, extension);
        TypeValidator.validateRequiredSystemProperties(properties);
        String user = context.getUsername();

        ObjectStore fs = fStoreManager.getObjectStore(repositoryId);
        Folder parent = null;

        // get required properties
        PropertyData<?> pd = properties.getProperties().get(PropertyIds.NAME);
        String folderName = (String) pd.getFirstValue();
        if (null == folderName || folderName.length() == 0) {
            throw new CmisInvalidArgumentException("Cannot create a folder without a name.");
        }

        // check name syntax
        if (!NameValidator.isValidName(folderName)) {
            throw new CmisInvalidArgumentException(NameValidator.ERROR_ILLEGAL_NAME + " Name is: " + folderName);
        }

        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = getTypeDefinition(repositoryId, properties, cmis11);

        // check if the given type is a folder type
        if (!typeDef.getBaseTypeId().equals(BaseTypeId.CMIS_FOLDER)) {
            throw new CmisInvalidArgumentException("Cannot create a folder, with a non-folder type: "
                    + typeDef.getId());
        }

        Map<String, PropertyData<?>> propMap = propertiesNew.getProperties();
        Map<String, PropertyData<?>> propMapNew = setDefaultProperties(typeDef, propMap);
        if (propMapNew != propMap) { // NOSONAR
            propertiesNew = new PropertiesImpl(propMapNew.values());
        }

        validateProperties(repositoryId, null, propertiesNew, false, cmis11);

        // validate ACL
        TypeValidator.validateAcl(typeDef, aclAdd, aclRemove);

        StoredObject so = null;
        // create folder
        try {
            LOG.debug("get folder for id: " + folderId);
            so = fs.getObjectById(folderId);
        } catch (Exception e) {
            throw new CmisObjectNotFoundException("Failed to retrieve folder.", e);
        }

        if (so instanceof Folder) {
            parent = (Folder) so;
        } else {
            throw new CmisInvalidArgumentException("Can't create folder, folderId does not refer to a folder: "
                    + folderId);
        }

        if (user == null) {
            user = UNKNOWN_USER;
        }

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        Folder newFolder = objStore.createFolder(folderName, propertiesNew.getProperties(), user, parent, policies,
                aclAdd, aclRemove);
        LOG.debug("stop createFolder()");
        return newFolder;
    }

    private StoredObject createPolicyIntern(CallContext context, String repositoryId, Properties properties,
            String folderId, List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {

        Acl aclAdd = addAces;
        Acl aclRemove = removeAces;

        validator.createPolicy(context, repositoryId, folderId, aclAdd, aclRemove, policies, extension);

        aclAdd = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(), aclAdd);
        aclRemove = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                aclRemove);

        String user = context.getUsername();
        Map<String, PropertyData<?>> propMap = properties.getProperties();
        // get name from properties
        PropertyData<?> pd = propMap.get(PropertyIds.NAME);
        String name = (String) pd.getFirstValue();
        pd = propMap.get(PropertyIds.POLICY_TEXT);
        String policyText = (pd == null ? null : (String) pd.getFirstValue());

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        StoredObject storedObject = objStore.createPolicy(name, policyText, propMap, user, aclAdd, aclRemove);

        return storedObject;
    }

    private StoredObject createRelationshipIntern(CallContext context, String repositoryId, Properties properties,
            List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {

        TypeValidator.validateRequiredSystemProperties(properties);

        String user = context.getUsername();

        Acl aclAdd = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                addAces);
        Acl aclRemove = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                removeAces);

        // get required properties
        PropertyData<?> pd = properties.getProperties().get(PropertyIds.SOURCE_ID);
        String sourceId = (String) pd.getFirstValue();
        if (null == sourceId || sourceId.length() == 0) {
            throw new CmisInvalidArgumentException("Cannot create a relationship without a sourceId.");
        }

        pd = properties.getProperties().get(PropertyIds.TARGET_ID);
        String targetId = (String) pd.getFirstValue();
        if (null == targetId || targetId.length() == 0) {
            throw new CmisInvalidArgumentException("Cannot create a relationship without a targetId.");
        }

        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = getTypeDefinition(repositoryId, properties, cmis11);

        // check if the given type is a relationship type
        if (!typeDef.getBaseTypeId().equals(BaseTypeId.CMIS_RELATIONSHIP)) {
            throw new CmisInvalidArgumentException("Cannot create a relationship, with a non-relationship type: "
                    + typeDef.getId());
        }

        StoredObject[] relationObjects = validator.createRelationship(context, repositoryId, sourceId, targetId,
                policies, extension);

        // set default properties
        Properties propertiesNew;
        Map<String, PropertyData<?>> propMap = properties.getProperties();
        Map<String, PropertyData<?>> propMapNew = setDefaultProperties(typeDef, propMap);
        if (propMapNew != propMap) { // NOSONAR
            propertiesNew = new PropertiesImpl(propMapNew.values());
        } else {
            propertiesNew = properties;
        }

        validateProperties(repositoryId, null, propertiesNew, false, cmis11);

        // validate ACL
        TypeValidator.validateAcl(typeDef, aclAdd, aclRemove);

        // validate the allowed types of the relationship
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        TypeDefinition sourceTypeDef = fStoreManager.getTypeById(repositoryId,
                objStore.getObjectById(sourceId).getTypeId(), cmis11).getTypeDefinition();
        TypeDefinition targetTypeDef = fStoreManager.getTypeById(repositoryId,
                objStore.getObjectById(targetId).getTypeId(), cmis11).getTypeDefinition();
        TypeValidator.validateAllowedRelationshipTypes((RelationshipTypeDefinition) typeDef, sourceTypeDef,
                targetTypeDef);

        // get name from properties
        pd = propMap.get(PropertyIds.NAME);
        String name = (String) pd.getFirstValue();

        StoredObject storedObject = objStore.createRelationship(name, relationObjects[0], relationObjects[1],
                propMapNew, user, aclAdd, aclRemove);
        return storedObject;
    }

    private StoredObject createItemIntern(CallContext context, String repositoryId, Properties properties,
            String folderId, List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {

        StoredObject so = validator.createItem(context, repositoryId, properties, folderId, policies, addAces, removeAces, extension);

        Acl aclAdd = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                addAces);
        Acl aclRemove = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                removeAces);

        validator.createDocument(context, repositoryId, folderId, policies, extension);

        // Validation stuff
        TypeValidator.validateRequiredSystemProperties(properties);

        String user = context.getUsername();
        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = getTypeDefinition(repositoryId, properties, cmis11);

        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);
        Map<String, PropertyData<?>> propMap = properties.getProperties();
        // get name from properties
        PropertyData<?> pd = propMap.get(PropertyIds.NAME);
        String name = (String) pd.getFirstValue();

        // validate ACL
        TypeValidator.validateAcl(typeDef, aclAdd, aclRemove);

        Folder folder = null;
        if (null != folderId) {
            if (null == so) {
                throw new CmisInvalidArgumentException(" Cannot create item, folderId: " + folderId + " is invalid");
            }

            if (so instanceof Folder) {
                folder = (Folder) so;
            } else {
                throw new CmisInvalidArgumentException("Can't create item, folderId does not refer to a folder: "
                        + folderId);
            }

            TypeValidator.validateAllowedChildObjectTypes(typeDef, folder.getAllowedChildObjectTypeIds());
        }

        // check if the given type is an item type
        if (!typeDef.getBaseTypeId().equals(BaseTypeId.CMIS_ITEM)) {
            throw new CmisInvalidArgumentException("Cannot create an item, with a non-item type: " + typeDef.getId());
        }

        // check name syntax
        if (!NameValidator.isValidName(name)) {
            throw new CmisInvalidArgumentException(NameValidator.ERROR_ILLEGAL_NAME + " Name is: " + name);
        }

        Properties propertiesNew = properties;
        // set properties that are not set but have a default:
        Map<String, PropertyData<?>> propMapNew = setDefaultProperties(typeDef, propMap);
        if (propMapNew != propMap) { // NOSONAR
            propertiesNew = new PropertiesImpl(propMapNew.values());
        }

        validateProperties(repositoryId, null, propertiesNew, false, cmis11);

        // set user, creation date, etc.
        if (user == null) {
            user = UNKNOWN_USER;
        }

        StoredObject item = null;

        // Now we are sure to have document type definition:
        item = objectStore.createItem(name, propMapNew, user, folder, policies, aclAdd, aclRemove);
        return item;
    }

    private boolean hasDescendant(String user, ObjectStore objStore, Folder sourceFolder, Folder targetFolder) {
        String sourceId = sourceFolder.getId();
        String targetId = targetFolder.getId();

        Folder folder = targetFolder;
        while (targetId != null) {
            if (targetId.equals(sourceId)) {
                return true;
            }
            List<String> parentIds = objStore.getParentIds(folder, user);
            targetId = parentIds == null || parentIds.isEmpty() ? null : parentIds.get(0);
            if (null != targetId) {
                folder = (Folder) objStore.getObjectById(targetId);
            }
        }
        return false;
    }

    /*
     * Recursively delete a tree by traversing it and first deleting all
     * children and then the object itself.
     * 
     * returns true if operation should continue, false if it should
     *         stop
     */
    private boolean deleteRecursive(ObjectStore objStore, Folder parentFolder, boolean continueOnFailure,
            boolean allVersions, List<String> failedToDeleteIds, String user) {

        ObjectStore.ChildrenResult childrenResult = objStore.getChildren(parentFolder, -1, -1, "Admin", true);
        List<Fileable> children = childrenResult.getChildren();

        if (null == children) {
            return true;
        }

        for (Fileable child : children) {
            if (child instanceof Folder) {
                boolean mustContinue = deleteRecursive(objStore, (Folder) child, continueOnFailure, allVersions,
                        failedToDeleteIds, user);
                if (!mustContinue && !continueOnFailure) {
                    return false; // stop further deletions
                }
            } else {
                try {
                    objStore.deleteObject(child.getId(), allVersions, user);
                } catch (Exception e) {
                    failedToDeleteIds.add(child.getId());
                }
            }
        }
        objStore.deleteObject(parentFolder.getId(), allVersions, user);
        return true;
    }

    private static ContentStream getContentStream(ObjectStore objStore, StoredObject so, String streamId, BigInteger offset, 
            BigInteger length) {
        ContentStream csd = null;
        long lOffset = offset == null ? 0 : offset.longValue();
        long lLength = length == null ? -1 : length.longValue();

        if (streamId == null) {
            csd =  objStore.getContent(so, lOffset, lLength);
        } else if (streamId.endsWith("-rendition")) {
            csd = objStore.getRenditionContent(so, streamId, lOffset, lLength);
        }

        return csd;
    }

    private Map<String, PropertyData<?>> setDefaultProperties(TypeDefinition typeDef,
            Map<String, PropertyData<?>> properties) {
        Map<String, PropertyData<?>> propertiesReturn = properties;
        Map<String, PropertyDefinition<?>> propDefs = typeDef.getPropertyDefinitions();
        boolean hasCopied = false;

        for (PropertyDefinition<?> propDef : propDefs.values()) {
            String propId = propDef.getId();
            List<?> defaultVal = propDef.getDefaultValue();
            if (defaultVal != null && !defaultVal.isEmpty() && null == properties.get(propId)) {
                if (!hasCopied) {
                    // copy because it is an unmodified collection
                    propertiesReturn = new HashMap<String, PropertyData<?>>(properties);
                    hasCopied = true;
                }
                Object value = propDef.getCardinality() == Cardinality.SINGLE ? defaultVal.get(0) : defaultVal;
                PropertyData<?> pd = fStoreManager.getObjectFactory().createPropertyData(propDef, value);
                // set property:
                propertiesReturn.put(propId, pd);
            }
        }
        return propertiesReturn;
    }

    private void validateProperties(String repositoryId, StoredObject so, Properties properties,
            boolean checkMandatory, boolean cmis11) {
        TypeDefinition typeDef;

        if (null != so) {
            typeDef = getTypeDefinition(repositoryId, so, cmis11);
        } else {
            typeDef = getTypeDefinition(repositoryId, properties, cmis11);
        }

        // check properties for validity
        if (!cmis11) {
            TypeValidator.validateProperties(typeDef, properties, checkMandatory, cmis11);
            return;
        }

        // CMIS 1.1 secondary types
        PropertyData<?> pd = properties.getProperties().get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);

        @SuppressWarnings("unchecked")
        List<String> secondaryTypeIds = (List<String>) (pd == null ? null : pd.getValues());
        // if no secondary types are passed use the existing ones:
        if (null != so && (null == secondaryTypeIds || secondaryTypeIds.size() == 0)) {
            secondaryTypeIds = so.getSecondaryTypeIds();
        }

        if (null != secondaryTypeIds && secondaryTypeIds.size() != 0) {
            List<String> allTypeIds = new ArrayList<String>(secondaryTypeIds);
            allTypeIds.add(typeDef.getId());
            List<TypeDefinition> typeDefs = getTypeDefinition(repositoryId, allTypeIds, cmis11);
            TypeValidator.validateProperties(typeDefs, properties, checkMandatory);
        } else {
            TypeValidator.validateProperties(typeDef, properties, checkMandatory, true);
        }
    }

    private TypeDefinition getSecondaryTypeDefinition(CallContext context, String repositoryId, Set<String> secondaryTypeIds,
            String propertyId) {
        if (null == secondaryTypeIds || secondaryTypeIds.isEmpty()) {
            return null;
        }

        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        for (String typeId : secondaryTypeIds) {
            TypeDefinitionContainer typeDefC = fStoreManager.getTypeById(repositoryId, typeId, cmis11);
            TypeDefinition typeDef = typeDefC.getTypeDefinition();

            if (TypeValidator.typeContainsProperty(typeDef, propertyId)) {
                return typeDef;
            }
        }

        return null;
    }

    private List<String> getListOfPropertiesToDeleteFromRemovedSecondaryTypes(CallContext context, String repositoryId, StoredObject so,
            List<String> newSecondaryTypeIds) {

        List<String> propertiesToDelete = new ArrayList<String>(); // properties
                                                                   // id to be
                                                                   // removed

        // calculate delta to be removed
        List<String> existingSecondaryTypeIds = so.getSecondaryTypeIds();
        List<String> delta = new ArrayList<String>(existingSecondaryTypeIds);
        delta.removeAll(newSecondaryTypeIds);
        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;

        for (String typeDefId : delta) {
            TypeDefinitionContainer typeDefC = fStoreManager.getTypeById(repositoryId, typeDefId, cmis11);
            TypeDefinition typeDef = typeDefC.getTypeDefinition();
            propertiesToDelete.addAll(typeDef.getPropertyDefinitions().keySet());
        }

        // TODO: the list may contain too many properties, if the same property
        // is also in a type not to be removed
        return propertiesToDelete;
    }

}
