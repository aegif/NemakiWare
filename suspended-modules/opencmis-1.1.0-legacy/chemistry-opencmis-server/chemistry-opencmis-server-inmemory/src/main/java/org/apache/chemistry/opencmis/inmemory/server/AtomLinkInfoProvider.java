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

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.impl.server.RenditionInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.commons.server.RenditionInfo;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Content;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Filing;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;
import org.apache.chemistry.opencmis.inmemory.types.PropertyCreationHelper;
import org.apache.chemistry.opencmis.server.support.TypeManager;

/**
 * For the Atom binding more information might be required than the result of a
 * service call provides (mainly to fill all the links). This class fills the
 * objectInfoHolder that was introduced for this purpose
 * 
 */
public class AtomLinkInfoProvider {

    private final StoreManager fStoreManager;

    public AtomLinkInfoProvider(StoreManager storeManager) {
        fStoreManager = storeManager;
    }

    /**
     * FillObjectInfoHolder object with required information needed for Atom
     * binding for a single object.
     * 
     * @param callContext
     *            call context of associated request
     * @param repositoryId
     *            id of repository
     * @param so
     *            object to retrieve information for
     * @param od
     *            object data
     * @param objInfo
     *            Holder to fill with information
     */
    public void fillInformationForAtomLinks(CallContext callContext, String repositoryId, StoredObject so, ObjectData od, 
            ObjectInfoImpl objInfo) {
        if (null == objInfo || null == so) {
            return;
        }
        boolean cmis11 = callContext.getCmisVersion() != CmisVersion.CMIS_1_0;
        TypeDefinition typeDef = fStoreManager.getTypeById(repositoryId, so.getTypeId(), cmis11).getTypeDefinition();
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        // Fill all setters:
        objInfo.setId(so.getId());
        objInfo.setName(so.getName());
        objInfo.setCreatedBy(so.getCreatedBy()); // !
        objInfo.setCreationDate(so.getCreatedAt()); // !
        objInfo.setLastModificationDate(so.getModifiedAt());
        objInfo.setTypeId(so.getTypeId());
        objInfo.setBaseType(typeDef.getBaseTypeId());
        objInfo.setObject(od);

        // versioning information:
        if (so instanceof DocumentVersion) {
            DocumentVersion ver = (DocumentVersion) so;
            DocumentVersion pwc = ver.getParentDocument().getPwc();
            objInfo.setIsCurrentVersion(ver == ver.getParentDocument().getLatestVersion(false));
            objInfo.setVersionSeriesId(ver.getParentDocument().getId());
            objInfo.setWorkingCopyId(pwc == null ? null : pwc.getId());
            objInfo.setWorkingCopyOriginalId(pwc == ver && ver.getParentDocument().getLatestVersion(false) != null ? ver
                    .getParentDocument().getLatestVersion(false).getId()
                    : null);
        } else if (so instanceof VersionedDocument) {
            VersionedDocument doc = (VersionedDocument) so;
            DocumentVersion pwc = doc.getPwc();
            objInfo.setIsCurrentVersion(false);
            objInfo.setVersionSeriesId(doc.getId());
            objInfo.setWorkingCopyId(pwc == null ? null : pwc.getId());
            objInfo.setWorkingCopyOriginalId(null);
        } else { // unversioned document
            objInfo.setIsCurrentVersion(true);
            objInfo.setVersionSeriesId(null);
            objInfo.setWorkingCopyId(null);
            objInfo.setWorkingCopyOriginalId(null);
        }

        if (so instanceof Content) {
            ContentStream contentStream = ((Content) so).getContent();
            objInfo.setHasContent(contentStream != null);
            objInfo.setContentType(contentStream != null ? contentStream.getMimeType() : null);
            objInfo.setFileName(contentStream != null ? contentStream.getFileName() : null);
        } else {
            objInfo.setHasContent(false);
            objInfo.setContentType(null);
            objInfo.setFileName(null);
        }

        // Filing
        if (so instanceof Filing) {
            Filing sop = ((Filing) so);
            objInfo.setHasParent(sop.hasParent());
        } else {
            objInfo.setHasParent(false);
        }

        List<RenditionData> renditions = objStore.getRenditions(so, "*", 0, 0);
        if (renditions == null || renditions.size() == 0) {
            objInfo.setRenditionInfos(null);
        } else {
            List<RenditionInfo> infos = new ArrayList<RenditionInfo>();
            for (RenditionData rendition : renditions) {
                RenditionInfoImpl info = new RenditionInfoImpl();
                info.setKind(rendition.getKind());
                info.setId(rendition.getStreamId());
                info.setContentType(rendition.getMimeType());
                info.setLength(rendition.getBigLength());
                info.setTitle(rendition.getTitle());
                infos.add(info);
            }
            objInfo.setRenditionInfos(infos);
        }

        // Relationships
        objInfo.setSupportsRelationships(true);
        List<StoredObject> rels = objStore.getRelationships(so.getId(), null, RelationshipDirection.SOURCE);
        List<String> srcIds = new ArrayList<String>(rels.size());
        for (StoredObject rel : rels) {
            srcIds.add(rel.getId());
        }

        rels = objStore.getRelationships(so.getId(), null, RelationshipDirection.TARGET);
        List<String> targetIds = new ArrayList<String>(rels.size());
        for (StoredObject rel : rels) {
            targetIds.add(rel.getId());
        }
        objInfo.setRelationshipSourceIds(srcIds);
        objInfo.setRelationshipTargetIds(targetIds);

        objInfo.setSupportsPolicies(true);

        objInfo.setHasAcl(true);

        objInfo.setSupportsDescendants(true);
        objInfo.setSupportsFolderTree(true);

    }

    /**
     * FillObjectInfoHolder object with required information needed for Atom
     * binding for a single object.
     * 
     * @param repositoryId
     *            id of repository
     * @param so
     *            object to retrieve information for
     * @param objectInfo
     *            Holder to fill with information
     */
    public void fillInformationForAtomLinks(CallContext callContext, String repositoryId, StoredObject so, ObjectInfoImpl objectInfo) {
        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        ObjectData od = PropertyCreationHelper.getObjectData(callContext, tm, objStore, so, null, null, false,
                IncludeRelationships.NONE, null, false, false, null);
        fillInformationForAtomLinks(callContext, repositoryId, so, od, objectInfo);
    }

    /**
     * FillObjectInfoHolder object with required information needed for Atom
     * binding for a single object.
     * 
     * @param callContext
     *            call context of associated request
     * @param repositoryId
     *            id of repository
     * @param objectId
     *            object to retrieve information for
     * @param objectInfo
     *            Holder to fill with information
     */
    public void fillInformationForAtomLinks(CallContext callContext, String repositoryId, String objectId, ObjectInfoImpl objectInfo) {
        if (null == objectInfo || null == objectId) {
            return;
        }

        ObjectStore objectStore = fStoreManager.getObjectStore(repositoryId);
        StoredObject so = objectStore.getObjectById(objectId);
        fillInformationForAtomLinks(callContext, repositoryId, so, objectInfo);
    }

    /**
     * FillObjectInfoHolder object with required information needed for Atom
     * binding after a getChildren() call in navigation service.
     * 
     * @param callContext
     *            call context of associated request
     * @param repositoryId
     *            id of repository
     * @param objectId
     *            object to retrieve information for
     * @param objectInfos
     *            Holder to fill with information
     * @param objList
     *            result of getChildren call
     */
    public void fillInformationForAtomLinks(CallContext callContext, String repositoryId, String objectId, ObjectInfoHandler objectInfos,
            ObjectInFolderList objList) {

        if (null == objectInfos || null == objList || null == objectId) {
            return;
        }

        // Fill object information for requested object
        ObjectInfoImpl objectInfo = new ObjectInfoImpl();
        fillInformationForAtomLinks(callContext, repositoryId, objectId, objectInfo);
        objectInfos.addObjectInfo(objectInfo);

        // Fill object information for all children in result list
        for (ObjectInFolderData object : objList.getObjects()) {
            objectInfo = new ObjectInfoImpl();
            fillInformationForAtomLinks(callContext, repositoryId, object.getObject().getId(), objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }
    }

    /**
     * FillObjectInfoHolder object with required information needed for Atom
     * binding for an object list.
     * 
     * @param callContext
     *            call context of associated request
     * @param repositoryId
     *            id of repository
     * @param objectId
     *            object to retrieve information for
     * @param objectInfos
     *            Holder to fill with information
     * @param objList
     *            result of getChildren call
     */
    public void fillInformationForAtomLinks(CallContext callContext, String repositoryId, String objectId, ObjectInfoHandler objectInfos,
            ObjectList objList) {

        ObjectInfoImpl objectInfo = new ObjectInfoImpl();
        if (null != objectId) {
            // Fill object information for requested object
            fillInformationForAtomLinks(callContext, repositoryId, objectId, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        if (null != objList && null != objList.getObjects()) {
            // Fill object information for all children in result list
            List<ObjectData> listObjects = objList.getObjects();
            if (null != listObjects) {
                for (ObjectData object : listObjects) {
                    objectInfo = new ObjectInfoImpl();
                    fillInformationForAtomLinks(object, objectInfo);
                    objectInfos.addObjectInfo(objectInfo);
                }
            }
        }

    }

    private void fillInformationForAtomLinks(ObjectData od, ObjectInfoImpl objectInfo) {
        objectInfo.setObject(od);
    }

    /**
     * FillObjectInfoHolder object with required information needed for Atom
     * binding for an ObjectInFolderContainer.
     * 
     * @param repositoryId
     *            id of repository
     * @param objectInfos
     *            Holder to fill with information
     * @param oifc
     *            result of previous call
     */
    private void fillInformationForAtomLinks(CallContext callContext, String repositoryId, ObjectInfoHandler objectInfos,
            ObjectInFolderContainer oifc) {

        if (null == objectInfos || null == oifc) {
            return;
        }

        // Fill object information for all elements in result list
        fillInformationForAtomLinks(callContext, repositoryId, objectInfos, oifc.getObject());

        if (null != oifc.getChildren()) {
            for (ObjectInFolderContainer object : oifc.getChildren()) {
                // call recursively
                ObjectInfoImpl objectInfo = new ObjectInfoImpl();
                fillInformationForAtomLinks(callContext, repositoryId, objectInfos, object);
                objectInfos.addObjectInfo(objectInfo);
            }
        }
    }

    /**
     * FillObjectInfoHolder object with required information needed for Atom
     * binding for a list with ObjectInFolderContainers.
     * 
     * @param repositoryId
     *            id of repository
     * @param objectId
     *            object to retrieve information for
     * @param objectInfos
     *            Holder to fill with information
     * @param oifcList
     *            result of getDescendants call
     */
    public void fillInformationForAtomLinks(CallContext callContext, String repositoryId, String objectId, ObjectInfoHandler objectInfos,
            List<ObjectInFolderContainer> oifcList) {

        if (null == objectInfos || null == oifcList || null == objectId) {
            return;
        }

        ObjectInfoImpl objectInfo = new ObjectInfoImpl();
        // Fill object information for requested object
        fillInformationForAtomLinks(callContext, repositoryId, objectId, objectInfo);
        objectInfos.addObjectInfo(objectInfo);

        for (ObjectInFolderContainer object : oifcList) {
            fillInformationForAtomLinks(callContext, repositoryId, objectInfos, object);
        }
    }

    private void fillInformationForAtomLinks(CallContext callContext, String repositoryId, ObjectInfoHandler objectInfos,
            ObjectInFolderData object) {

        ObjectInfoImpl objectInfo = new ObjectInfoImpl();
        fillInformationForAtomLinks(callContext, repositoryId, object.getObject().getId(), objectInfo);
        objectInfos.addObjectInfo(objectInfo);
    }

    /**
     * FillObjectInfoHolder object with required information needed for Atom
     * binding for a list with ObjectParentData objects.
     * 
     * @param repositoryId
     *            id of repository
     * @param objectId
     *            object to retrieve information for
     * @param objectInfos
     *            Holder to fill with information
     * @param objParents
     *            result of getObjectParents call
     */
    public void fillInformationForAtomLinksGetParents(CallContext callContext, String repositoryId, String objectId,
            ObjectInfoHandler objectInfos, List<ObjectParentData> objParents) {

        if (null == objectInfos || null == objParents || null == objectId) {
            return;
        }

        // Fill object information for requested object
        ObjectInfoImpl objectInfo = new ObjectInfoImpl();
        fillInformationForAtomLinks(callContext, repositoryId, objectId, objectInfo);

        for (ObjectParentData object : objParents) {
            objectInfo = new ObjectInfoImpl();
            fillInformationForAtomLinks(callContext, repositoryId, object.getObject().getId(), objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }
    }

}
