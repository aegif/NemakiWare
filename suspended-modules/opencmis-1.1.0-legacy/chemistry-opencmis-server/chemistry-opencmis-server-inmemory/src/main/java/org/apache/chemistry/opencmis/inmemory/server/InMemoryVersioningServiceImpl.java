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

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.NameValidator;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Content;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Document;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;
import org.apache.chemistry.opencmis.inmemory.types.PropertyCreationHelper;
import org.apache.chemistry.opencmis.server.support.TypeManager;

public class InMemoryVersioningServiceImpl extends InMemoryAbstractServiceImpl {

    private InMemoryObjectServiceImpl fObjectService;

    public InMemoryVersioningServiceImpl(StoreManager storeManager, InMemoryObjectServiceImpl objectService) {
        super(storeManager);
        fObjectService = objectService;
    }

    public void cancelCheckOut(CallContext context, String repositoryId, String objectId, ExtensionsData extension) {

        StoredObject so = validator.cancelCheckOut(context, repositoryId, objectId, extension);
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        String user = context.getUsername();
        VersionedDocument verDoc = testHasProperCheckedOutStatus(so, user);
        DocumentVersion pwc = verDoc.getPwc();
        verDoc.cancelCheckOut(user);
        objStore.deleteVersion(pwc);

        // if this is the last version delete the document itself
        if (verDoc.getAllVersions().size() == 0) {
            fStoreManager.getObjectStore(repositoryId).deleteObject(verDoc.getId(), true, user);
        }
    }

    public void checkIn(CallContext context, String repositoryId, Holder<String> objectId, Boolean majorParam,
            Properties properties, ContentStream contentStreamParam, String checkinComment, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension, ObjectInfoHandler objectInfos) {

        Acl aclAdd = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                addAces);
        Acl aclRemove = org.apache.chemistry.opencmis.inmemory.TypeValidator.expandAclMakros(context.getUsername(),
                removeAces);

        StoredObject so = validator.checkIn(context, repositoryId, objectId, aclAdd, aclRemove, policies, extension);

        String user = context.getUsername();
        VersionedDocument verDoc = testHasProperCheckedOutStatus(so, user);
        
        DocumentVersion pwc = verDoc.getPwc();
        if (pwc == null || !pwc.getId().equals(objectId.getValue())) {
            throw new CmisConstraintException("Error: Can't checkin, " + objectId
            + " is not a private working copy.");        	
        }


        // check if the contentStream is a usable object or ignore it otherwise
        // Note Browser binding sets an empty object
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        ContentStream contentStream = contentStreamParam;
        if (contentStream != null) {
            if (contentStream.getStream() == null) {
                contentStream = null;
            } else {
                objStore.setContent(so, contentStream);
            }
        }

        boolean major = (null == majorParam ? true : majorParam);

        verDoc.checkIn(major, properties, ((Content)so).getContent(), checkinComment, policies, user);
        if (null != properties && null != properties.getProperties()) {
            // rename:
            PropertyData<?> pd = properties.getProperties().get(PropertyIds.NAME);
            if (pd != null) {
                String newName = (String) pd.getFirstValue();
                if (newName == null || newName.equals("")) {
                    throw new CmisConstraintException("updateProperties failed, name must not be empty.");
                }
                if (!NameValidator.isValidName(newName)) {
                    throw new CmisInvalidArgumentException(NameValidator.ERROR_ILLEGAL_NAME);
                }
                // Note: the test for duplicated name in folder is left to the
                // object store
                objStore.rename(so, (String) pd.getFirstValue(), user);
            }
        }
        so.updateSystemBasePropertiesWhenModified(null, context.getUsername());
        // To be able to provide all Atom links in the response we need
        // additional information:
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }
    }

    public void checkOut(CallContext context, String repositoryId, Holder<String> objectId, ExtensionsData extension,
            Holder<Boolean> contentCopied, ObjectInfoHandler objectInfos) {

        StoredObject so = validator.checkOut(context, repositoryId, objectId, extension, contentCopied);

        boolean cmis11 = context.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = getTypeDefinition(repositoryId, so, cmis11);
        if (!typeDef.getBaseTypeId().equals(BaseTypeId.CMIS_DOCUMENT)) {
            throw new CmisNotSupportedException("Only documents can be checked-out.");
        } else if (!((DocumentTypeDefinition) typeDef).isVersionable()) {
            throw new CmisNotSupportedException("Object can't be checked-out, type is not versionable.");
        }

        checkIsVersionableObject(so);

        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);
        VersionedDocument verDoc = getVersionedDocumentOfObjectId(so);

        ContentStream content = null;

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        if (so instanceof DocumentVersion) {
            content = objStore.getContent(so, 0, -1);
        } else {
            DocumentVersion latestVer = ((VersionedDocument) so).getLatestVersion(false);
            content = objStore.getContent(latestVer, 0, -1);
        }

        if (verDoc.isCheckedOut()) {
            throw new CmisUpdateConflictException("Document " + objectId.getValue() + " is already checked out.");
        }

        String user = context.getUsername();
        checkHasUser(user);

        DocumentVersion pwc = verDoc.checkOut(user);
        objectStore.setContent(pwc, content);
        objectStore.storeVersion(pwc);
        objectId.setValue(pwc.getId()); // return the id of the created pwc
        if (null != contentCopied) {
            contentCopied.setValue(true);
        }

        // To be able to provide all Atom links in the response we need
        // additional information:
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, pwc, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }
    }

    public List<ObjectData> getAllVersions(CallContext context, String repositoryId, String objectId,
            String versionSeriesId, String filter, Boolean includeAllowableActions, ExtensionsData extension,
            ObjectInfoHandler objectInfos) {

        // Note that in AtomPub object id is null and versionSeriesId is set and
        // in SOAP bindinf versionSeriesId is set
        // and objectId is null
        StoredObject so;
        List<ObjectData> res = new ArrayList<ObjectData>();
        String id = versionSeriesId;
        if (null == versionSeriesId) {
            if (null == objectId) {
                throw new CmisInvalidArgumentException("getAllVersions requires a version series id, but it was null.");
            }
            id = objectId;
        }
        so = validator.getAllVersions(context, repositoryId, objectId, id, extension);

        if (!(so instanceof VersionedDocument)) {
            if (!(so instanceof DocumentVersion)) {
                throw new CmisInvalidArgumentException("getAllVersions requires an id of a versioned document.");
            }
            so = ((DocumentVersion) so).getParentDocument();
        }

        VersionedDocument verDoc = (VersionedDocument) so;
        List<DocumentVersion> versions = verDoc.getAllVersions();
        for (DocumentVersion version : versions) {
            ObjectData objData = getObject(context, repositoryId, version.getId(), filter, includeAllowableActions,
                    IncludeRelationships.NONE, false, extension, objectInfos);
            res.add(objData);
        }

        // reverse list of versions because spec expects latest version first
        List<ObjectData> temp = new ArrayList<ObjectData>(res.size());
        for (ObjectData ver : res) {
            temp.add(0, ver);
        }
        res = temp;

        // provide information for Atom links for version series:
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        return res;
    }

    public ObjectData getObjectOfLatestVersion(CallContext context, String repositoryId, String objectId,
            String versionSeriesId, Boolean major, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension, ObjectInfoHandler objectInfos) {

        StoredObject so = validator.getObjectOfLatestVersion(context, repositoryId, objectId, versionSeriesId,
                extension);

        ObjectData objData = null;

        // In AtomPu8b you do not get the version series id, only the object id
        if (so instanceof DocumentVersion) {
            so = ((DocumentVersion) so).getParentDocument();
        }

        if (so instanceof VersionedDocument) {
            VersionedDocument verDoc = (VersionedDocument) so;
            DocumentVersion latestVersion = verDoc.getLatestVersion(major);
            objData = getObject(context, repositoryId, latestVersion.getId(), filter, includeAllowableActions,
                    includeRelationships, includePolicyIds, extension, objectInfos);
        } else if (so instanceof Document) {
            objData = getObject(context, repositoryId, so.getId(), filter, includeAllowableActions,
                    includeRelationships, includePolicyIds, extension, objectInfos);
        } else {
            throw new CmisInvalidArgumentException("Object is not instance of a document (version series)");
        }

        // provide information for Atom links for version series:
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        return objData;
    }

    public Properties getPropertiesOfLatestVersion(CallContext context, String repositoryId, String objectId,
            String versionSeriesId, Boolean major, String filter, ExtensionsData extension) {

        StoredObject so = validator.getPropertiesOfLatestVersion(context, repositoryId, objectId, versionSeriesId,
                extension);
        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);
        StoredObject latestVersionObject = null;

        // In AtomPu8b you do not get the version series id, only the object id
        if (so instanceof DocumentVersion) {
            so = ((DocumentVersion) so).getParentDocument();
        }

        if (so instanceof VersionedDocument) {
            VersionedDocument verDoc = (VersionedDocument) so;
            latestVersionObject = verDoc.getLatestVersion(major);
        } else if (so instanceof Document) {
            latestVersionObject = so;
        } else {
            throw new CmisInvalidArgumentException("Object is not instance of a document (version series)");
        }

        List<String> requestedIds = FilterParser.getRequestedIdsFromFilter(filter);

        TypeManager tm = fStoreManager.getTypeManager(repositoryId);

        Properties props = PropertyCreationHelper.getPropertiesFromObject(latestVersionObject, objectStore, tm,
                requestedIds, true);

        return props;
    }

    private ObjectData getObject(CallContext context, String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, Boolean includePolicies,
            ExtensionsData extension, ObjectInfoHandler objectInfos) {

        return fObjectService.getObject(context, repositoryId, objectId, filter, includeAllowableActions,
                includeRelationships, null, includePolicies, includeAllowableActions, extension, objectInfos);
    }
}
