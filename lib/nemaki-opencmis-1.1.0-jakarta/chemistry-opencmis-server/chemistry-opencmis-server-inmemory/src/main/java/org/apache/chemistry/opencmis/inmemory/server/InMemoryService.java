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
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractCmisService;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;

public class InMemoryService extends AbstractCmisService {

    private final StoreManager storeManager; // singleton root of everything
    private final CallContext callContext;
    private final InMemoryRepositoryServiceImpl fRepSvc;
    private final InMemoryObjectServiceImpl fObjSvc;
    private final InMemoryNavigationServiceImpl fNavSvc;
    private final InMemoryVersioningServiceImpl fVerSvc;
    private final InMemoryDiscoveryServiceImpl fDisSvc;
    private final InMemoryMultiFilingServiceImpl fMultiSvc;
    private final InMemoryRelationshipServiceImpl fRelSvc;
    private final InMemoryPolicyServiceImpl fPolSvc;
    private final InMemoryAclService fAclSvc;

    public StoreManager getStoreManager() {
        return storeManager;
    }

    public InMemoryService(StoreManager sm, CallContext ctx) {
        storeManager = sm;
        callContext = ctx;
        fRepSvc = new InMemoryRepositoryServiceImpl(storeManager);
        fNavSvc = new InMemoryNavigationServiceImpl(storeManager);
        fObjSvc = new InMemoryObjectServiceImpl(storeManager);
        fVerSvc = new InMemoryVersioningServiceImpl(storeManager, fObjSvc);
        fDisSvc = new InMemoryDiscoveryServiceImpl(storeManager);
        fMultiSvc = new InMemoryMultiFilingServiceImpl(storeManager);
        fRelSvc = new InMemoryRelationshipServiceImpl(storeManager, fRepSvc);
        fPolSvc = new InMemoryPolicyServiceImpl(storeManager);
        fAclSvc = new InMemoryAclService(storeManager);
    }

    public CallContext getCallContext() {
        return callContext;
    }

    // --- repository service ---

    @Override
    public void close() {
        super.close();
    }

    @Override
    public List<RepositoryInfo> getRepositoryInfos(ExtensionsData extension) {
        return fRepSvc.getRepositoryInfos(getCallContext(), extension);
    }

    @Override
    public RepositoryInfo getRepositoryInfo(String repositoryId, ExtensionsData extension) {
        return fRepSvc.getRepositoryInfo(getCallContext(), repositoryId, extension);
    }

    @Override
    public TypeDefinitionList getTypeChildren(String repositoryId, String typeId, Boolean includePropertyDefinitions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        return fRepSvc.getTypeChildren(getCallContext(), repositoryId, typeId, includePropertyDefinitions, maxItems,
                skipCount, extension);
    }

    @Override
    public TypeDefinition getTypeDefinition(String repositoryId, String typeId, ExtensionsData extension) {
        return fRepSvc.getTypeDefinition(getCallContext(), repositoryId, typeId, extension);
    }

    @Override
    public TypeDefinition createType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        return fRepSvc.createType(getCallContext(), repositoryId, type, extension);
    }

    @Override
    public TypeDefinition updateType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        return fRepSvc.updateType(getCallContext(), repositoryId, type, extension);
    }

    @Override
    public void deleteType(String repositoryId, String typeId, ExtensionsData extension) {
        fRepSvc.deleteType(getCallContext(), repositoryId, typeId, extension);
    }

    @Override
    public List<TypeDefinitionContainer> getTypeDescendants(String repositoryId, String typeId, BigInteger depth,
            Boolean includePropertyDefinitions, ExtensionsData extension) {
        return fRepSvc.getTypeDescendants(getCallContext(), repositoryId, typeId, depth, includePropertyDefinitions,
                extension);
    }

    // --- navigation service ---

    @Override
    public ObjectList getCheckedOutDocs(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        return fNavSvc.getCheckedOutDocs(getCallContext(), repositoryId, folderId, filter, orderBy,
                includeAllowableActions, includeRelationships, renditionFilter, maxItems, skipCount, extension, this);
    }

    @Override
    public ObjectInFolderList getChildren(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePathSegment, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        return fNavSvc.getChildren(getCallContext(), repositoryId, folderId, filter, orderBy, includeAllowableActions,
                includeRelationships, renditionFilter, includePathSegment, maxItems, skipCount, extension, this);
    }

    @Override
    public List<ObjectInFolderContainer> getDescendants(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        return fNavSvc.getDescendants(getCallContext(), repositoryId, folderId, depth, filter, includeAllowableActions,
                includeRelationships, renditionFilter, includePathSegment, extension, this);
    }

    @Override
    public ObjectData getFolderParent(String repositoryId, String folderId, String filter, ExtensionsData extension) {
        return fNavSvc.getFolderParent(getCallContext(), repositoryId, folderId, filter, extension, this);
    }

    @Override
    public List<ObjectInFolderContainer> getFolderTree(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        return fNavSvc.getFolderTree(getCallContext(), repositoryId, folderId, depth, filter, includeAllowableActions,
                includeRelationships, renditionFilter, includePathSegment, extension, this);
    }

    @Override
    public List<ObjectParentData> getObjectParents(String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includeRelativePathSegment, ExtensionsData extension) {
        return fNavSvc.getObjectParents(getCallContext(), repositoryId, objectId, filter, includeAllowableActions,
                includeRelationships, renditionFilter, includeRelativePathSegment, extension, this);
    }

    // --- object service ---

    @Override
    public String create(String repositoryId, Properties properties, String folderId, ContentStream contentStream,
            VersioningState versioningState, List<String> policies, ExtensionsData extension) {
        String id = fObjSvc.create(getCallContext(), repositoryId, properties, folderId, contentStream,
                versioningState, policies, extension, this);
        return id;

    }

    @Override
    public String createDocument(String repositoryId, Properties properties, String folderId,
            ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {
        return fObjSvc.createDocument(getCallContext(), repositoryId, properties, folderId, contentStream,
                versioningState, policies, addAces, removeAces, extension);
    }

    @Override
    public String createDocumentFromSource(String repositoryId, String sourceId, Properties properties,
            String folderId, VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        return fObjSvc.createDocumentFromSource(getCallContext(), repositoryId, sourceId, properties, folderId,
                versioningState, policies, addAces, removeAces, extension);
    }

    @Override
    public String createFolder(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        return fObjSvc.createFolder(getCallContext(), repositoryId, properties, folderId, policies, addAces,
                removeAces, extension);
    }

    @Override
    public String createPolicy(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        return fObjSvc.createPolicy(getCallContext(), repositoryId, properties, folderId, policies, addAces,
                removeAces, extension);
    }

    @Override
    public String createRelationship(String repositoryId, Properties properties, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {
        return fObjSvc.createRelationship(getCallContext(), repositoryId, properties, policies, addAces, removeAces,
                extension);
    }

    @Override
    public String createItem(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        return fObjSvc.createItem(getCallContext(), repositoryId, properties, folderId, policies, addAces, removeAces,
                extension);
    }

    @Override
    public void deleteContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ExtensionsData extension) {
        fObjSvc.deleteContentStream(getCallContext(), repositoryId, objectId, changeToken, extension);
    }

    @Override
    public void deleteObject(String repositoryId, String objectId, Boolean allVersions, ExtensionsData extension) {
        fObjSvc.deleteObject(getCallContext(), repositoryId, objectId, allVersions, extension);
    }

    @Override
    public void deleteObjectOrCancelCheckOut(String repositoryId, String objectId, Boolean allVersions,
            ExtensionsData extension) {
        fObjSvc.deleteObject(getCallContext(), repositoryId, objectId, allVersions, extension);
    }

    @Override
    public FailedToDeleteData deleteTree(String repositoryId, String folderId, Boolean allVersions,
            UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension) {
        return fObjSvc.deleteTree(getCallContext(), repositoryId, folderId, allVersions, unfileObjects,
                continueOnFailure, extension);
    }

    @Override
    public AllowableActions getAllowableActions(String repositoryId, String objectId, ExtensionsData extension) {
        return fObjSvc.getAllowableActions(getCallContext(), repositoryId, objectId, extension);
    }

    @Override
    public ContentStream getContentStream(String repositoryId, String objectId, String streamId, BigInteger offset,
            BigInteger length, ExtensionsData extension) {
        return fObjSvc.getContentStream(getCallContext(), repositoryId, objectId, streamId, offset, length, extension);
    }

    @Override
    public ObjectData getObject(String repositoryId, String objectId, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension) {
        return fObjSvc.getObject(getCallContext(), repositoryId, objectId, filter, includeAllowableActions,
                includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension, this);
    }

    @Override
    public ObjectData getObjectByPath(String repositoryId, String path, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension) {
        return fObjSvc.getObjectByPath(getCallContext(), repositoryId, path, filter, includeAllowableActions,
                includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension, this);
    }

    @Override
    public Properties getProperties(String repositoryId, String objectId, String filter, ExtensionsData extension) {
        return fObjSvc.getProperties(getCallContext(), repositoryId, objectId, filter, extension);
    }

    @Override
    public List<RenditionData> getRenditions(String repositoryId, String objectId, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        return fObjSvc.getRenditions(getCallContext(), repositoryId, objectId, renditionFilter, maxItems, skipCount,
                extension);
    }

    @Override
    public void moveObject(String repositoryId, Holder<String> objectId, String targetFolderId, String sourceFolderId,
            ExtensionsData extension) {
        fObjSvc.moveObject(getCallContext(), repositoryId, objectId, targetFolderId, sourceFolderId, extension, this);
    }

    @Override
    public void setContentStream(String repositoryId, Holder<String> objectId, Boolean overwriteFlag,
            Holder<String> changeToken, ContentStream contentStream, ExtensionsData extension) {
        fObjSvc.setContentStream(getCallContext(), repositoryId, objectId, overwriteFlag, changeToken, contentStream,
                extension);
    }

    @Override
    public void updateProperties(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            Properties properties, ExtensionsData extension) {
        fObjSvc.updateProperties(getCallContext(), repositoryId, objectId, changeToken, properties, null, extension,
                this);
    }

    // CMIS 1.1
    @Override
    public void appendContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ContentStream contentStream, boolean isLastChunk, ExtensionsData extension) {
        fObjSvc.appendContentStream(getCallContext(), repositoryId, objectId, changeToken, contentStream, extension);
    }

    // CMIS 1.1
    @Override
    public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(String repositoryId,
            List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken, Properties properties,
            List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension) {
        return fObjSvc.bulkUpdateProperties(getCallContext(), repositoryId, objectIdAndChangeToken, properties,
                addSecondaryTypeIds, removeSecondaryTypeIds, extension, this);
    }

    // --- versioning service ---

    @Override
    public void cancelCheckOut(String repositoryId, String objectId, ExtensionsData extension) {
        fVerSvc.cancelCheckOut(getCallContext(), repositoryId, objectId, extension);
    }

    @Override
    public void checkIn(String repositoryId, Holder<String> objectId, Boolean major, Properties properties,
            ContentStream contentStream, String checkinComment, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        fVerSvc.checkIn(getCallContext(), repositoryId, objectId, major, properties, contentStream, checkinComment,
                policies, addAces, removeAces, extension, this);
    }

    @Override
    public void checkOut(String repositoryId, Holder<String> objectId, ExtensionsData extension,
            Holder<Boolean> contentCopied) {
        fVerSvc.checkOut(getCallContext(), repositoryId, objectId, extension, contentCopied, this);
    }

    @Override
    public ObjectData getObjectOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension) {
        return fVerSvc.getObjectOfLatestVersion(getCallContext(), repositoryId, objectId, versionSeriesId, major,
                filter, includeAllowableActions, includeRelationships, renditionFilter, includePolicyIds, includeAcl,
                extension, this);
    }

    @Override
    public Properties getPropertiesOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, ExtensionsData extension) {
        return fVerSvc.getPropertiesOfLatestVersion(getCallContext(), repositoryId, objectId, versionSeriesId, major,
                filter, extension);
    }

    @Override
    public List<ObjectData> getAllVersions(String repositoryId, String objectId, String versionSeriesId, String filter,
            Boolean includeAllowableActions, ExtensionsData extension) {
        return fVerSvc.getAllVersions(getCallContext(), repositoryId, objectId, versionSeriesId, filter,
                includeAllowableActions, extension, this);
    }

    // --- discovery service ---

    @Override
    public ObjectList getContentChanges(String repositoryId, Holder<String> changeLogToken, Boolean includeProperties,
            String filter, Boolean includePolicyIds, Boolean includeAcl, BigInteger maxItems, 
            ExtensionsData extension) {
        return fDisSvc.getContentChanges(getCallContext(), repositoryId, changeLogToken, includeProperties, filter,
                includePolicyIds, includeAcl, maxItems, extension, this);
    }

    @Override
    public ObjectList query(String repositoryId, String statement, Boolean searchAllVersions,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        return fDisSvc.query(getCallContext(), repositoryId, statement, searchAllVersions, includeAllowableActions,
                includeRelationships, renditionFilter, maxItems, skipCount, extension);
    }

    // --- multi filing service ---

    @Override
    public void addObjectToFolder(String repositoryId, String objectId, String folderId, Boolean allVersions,
            ExtensionsData extension) {
        fMultiSvc.addObjectToFolder(getCallContext(), repositoryId, objectId, folderId, allVersions, extension, this);
    }

    @Override
    public void removeObjectFromFolder(String repositoryId, String objectId, String folderId, 
            ExtensionsData extension) {
        fMultiSvc.removeObjectFromFolder(getCallContext(), repositoryId, objectId, folderId, extension, this);
    }

    // --- relationship service ---

    @Override
    public ObjectList getObjectRelationships(String repositoryId, String objectId, Boolean includeSubRelationshipTypes,
            RelationshipDirection relationshipDirection, String typeId, String filter, Boolean includeAllowableActions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        return fRelSvc.getObjectRelationships(getCallContext(), repositoryId, objectId, includeSubRelationshipTypes,
                relationshipDirection, typeId, filter, includeAllowableActions, maxItems, skipCount, extension, this);
    }

    // --- ACL service ---

    @Override
    public Acl applyAcl(String repositoryId, String objectId, Acl aces, AclPropagation aclPropagation) {
        return fAclSvc.applyAcl(getCallContext(), repositoryId, objectId, aces, aclPropagation);
    }

    @Override
    public Acl applyAcl(String repositoryId, String objectId, Acl addAces, Acl removeAces,
            AclPropagation aclPropagation, ExtensionsData extension) {
        return fAclSvc.applyAcl(getCallContext(), repositoryId, objectId, addAces, removeAces, aclPropagation,
                extension, this);
    }

    @Override
    public Acl getAcl(String repositoryId, String objectId, Boolean onlyBasicPermissions, ExtensionsData extension) {
        return fAclSvc.getAcl(getCallContext(), repositoryId, objectId, onlyBasicPermissions, extension, this);
    }

    // --- policy service ---

    @Override
    public void applyPolicy(String repositoryId, String policyId, String objectId, ExtensionsData extension) {
        fPolSvc.applyPolicy(getCallContext(), repositoryId, policyId, objectId, extension);
    }

    @Override
    public List<ObjectData> getAppliedPolicies(String repositoryId, String objectId, String filter,
            ExtensionsData extension) {
        return fPolSvc.getAppliedPolicies(getCallContext(), repositoryId, objectId, filter, extension);
    }

    @Override
    public void removePolicy(String repositoryId, String policyId, String objectId, ExtensionsData extension) {
        fPolSvc.removePolicy(getCallContext(), repositoryId, policyId, objectId, extension);
    }

    // /////////////

}
