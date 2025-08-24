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
package org.apache.chemistry.opencmis.inmemory.storedobj.api;

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public interface CmisServiceValidator {

    void getRepositoryInfos(CallContext context, ExtensionsData extension);

    void getRepositoryInfo(CallContext context, String repositoryId, ExtensionsData extension);

    void getTypeChildren(CallContext context, String repositoryId, String typeId, ExtensionsData extension);

    void getTypeDescendants(CallContext context, String repositoryId, String typeId, ExtensionsData extension);

    void getTypeDefinition(CallContext context, String repositoryId, String typeId, ExtensionsData extension);

    StoredObject getChildren(CallContext context, String repositoryId, String folderId, ExtensionsData extension);

    StoredObject getDescendants(CallContext context, String repositoryId, String folderId, ExtensionsData extension);

    StoredObject getFolderTree(CallContext context, String repositoryId, String folderId, ExtensionsData extension);

    StoredObject getObjectParents(CallContext context, String repositoryId, String objectId, ExtensionsData extension);

    StoredObject getFolderParent(CallContext context, String repositoryId, String folderId, ExtensionsData extension);

    StoredObject getCheckedOutDocs(CallContext context, String repositoryId, String folderId, ExtensionsData extension);

    StoredObject createDocument(CallContext context, String repositoryId, String folderId, List<String> policyIds,
            ExtensionsData extension);

    StoredObject createDocumentFromSource(CallContext context, String repositoryId, String sourceId, String folderId,
            List<String> policyIds, ExtensionsData extension);

    StoredObject createFolder(CallContext context, String repositoryId, String folderId, List<String> policyIds,
            ExtensionsData extension);

    // relationship has no parent, returns source and target object
    StoredObject[] createRelationship(CallContext context, String repositoryId, String sourceId, String targetId,
            List<String> policyIds, ExtensionsData extension);

    StoredObject createPolicy(CallContext context, String repositoryId, String folderId, Acl addAces, Acl removeAces,
            List<String> policyIds, ExtensionsData extension);

    StoredObject getAllowableActions(CallContext context, String repositoryId, String objectId, 
            ExtensionsData extension);

    StoredObject getObject(CallContext context, String repositoryId, String objectId, ExtensionsData extension);

    StoredObject getProperties(CallContext context, String repositoryId, String objectId, ExtensionsData extension);

    StoredObject getRenditions(CallContext context, String repositoryId, String objectId, ExtensionsData extension);

    StoredObject getObjectByPath(CallContext context, String repositoryId, String path, ExtensionsData extension);

    StoredObject getContentStream(CallContext context, String repositoryId, String objectId, String streamId,
            ExtensionsData extension);

    StoredObject updateProperties(CallContext context, String repositoryId, Holder<String> objectId,
            ExtensionsData extension);

    StoredObject[] moveObject(CallContext context, String repositoryId, Holder<String> objectId, String targetFolderId,
            String sourceFolderId, ExtensionsData extension);

    StoredObject deleteObject(CallContext context, String repositoryId, String objectId, Boolean allVersions,
            ExtensionsData extension);

    StoredObject deleteTree(CallContext context, String repositoryId, String folderId, Boolean allVersions,
            UnfileObject unfileObjects, ExtensionsData extension);

    StoredObject setContentStream(CallContext context, String repositoryId, Holder<String> objectId,
            Boolean overwriteFlag, ExtensionsData extension);

    // CMIS 1.1
    StoredObject appendContentStream(CallContext context, String repositoryId, Holder<String> objectId,
            ExtensionsData extension);

    StoredObject deleteContentStream(CallContext context, String repositoryId, Holder<String> objectId,
            ExtensionsData extension);

    StoredObject checkOut(CallContext context, String repositoryId, Holder<String> objectId, ExtensionsData extension,
            Holder<Boolean> contentCopied);

    StoredObject cancelCheckOut(CallContext context, String repositoryId, String objectId, ExtensionsData extension);

    StoredObject checkIn(CallContext context, String repositoryId, Holder<String> objectId, Acl addAces,
            Acl removeAces, List<String> policyIds, ExtensionsData extension);

    StoredObject getObjectOfLatestVersion(CallContext context, String repositoryId, String objectId,
            String versionSeriesId, ExtensionsData extension);

    StoredObject getPropertiesOfLatestVersion(CallContext context, String repositoryId, String objectId,
            String versionSeriesId, ExtensionsData extension);

    StoredObject getAllVersions(CallContext context, String repositoryId, String objectId, String versionSeriesId,
            ExtensionsData extension);

    void query(CallContext context, String repositoryId, ExtensionsData extension);

    void getContentChanges(CallContext context, String repositoryId, ExtensionsData extension);

    StoredObject[] addObjectToFolder(CallContext context, String repositoryId, String objectId, String folderId,
            Boolean allVersions, ExtensionsData extension);

    StoredObject[] removeObjectFromFolder(CallContext context, String repositoryId, String objectId, String folderId,
            ExtensionsData extension);

    StoredObject getObjectRelationships(CallContext context, String repositoryId, String objectId,
            RelationshipDirection relationshipDirection, String typeId, ExtensionsData extension);

    StoredObject getAcl(CallContext context, String repositoryId, String objectId, ExtensionsData extension);

    StoredObject applyAcl(CallContext context, String repositoryId, String objectId, AclPropagation aclPropagation,
            ExtensionsData extension);

    StoredObject[] applyPolicy(CallContext context, String repositoryId, String policyId, String objectId,
            ExtensionsData extension);

    StoredObject[] removePolicy(CallContext context, String repositoryId, String policyId, String objectId,
            ExtensionsData extension);

    StoredObject getAppliedPolicies(CallContext context, String repositoryId, String objectId, 
            ExtensionsData extension);

    StoredObject create(CallContext context, String repositoryId, String folderId, ExtensionsData extension);

    StoredObject applyAcl(CallContext context, String repositoryId, String objectId);

    // CMIS 1.1
    StoredObject createItem(CallContext context, String repositoryId, Properties properties, String folderId,
            List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension);

    void createType(CallContext callContext, String repositoryId, TypeDefinition type, ExtensionsData extension);
    
    TypeDefinition updateType(CallContext callContext, String repositoryId, TypeDefinition type, 
            ExtensionsData extension);
    
    TypeDefinition deleteType(CallContext callContext, String repositoryId, String typeId, ExtensionsData extension);
}