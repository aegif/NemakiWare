/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.factory;

import java.math.BigInteger;
import java.util.List;

import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.PolicyService;
import jp.aegif.nemaki.cmis.service.RelationshipService;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.cmis.service.VersioningService;

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
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Nemaki repository
 */
public class Repository {

	private String repositoryId;
	
	private AclService aclService;
	private DiscoveryService discoveryService;
	private NavigationService navigationService;
	private ObjectService objectService;
	private RepositoryService repositoryService;
	private VersioningService versioningService;
	private PolicyService policyService;
	private RelationshipService relationshipService;

	// -- Object Service

	public ObjectData create(CallContext callContext, String repositoryId,
			Properties properties, String folderId,
			ContentStream contentStream, VersioningState versioningState,
			List<String> policies, ExtensionsData extension) {

		return objectService.create(callContext, repositoryId, properties,
				folderId, contentStream, versioningState, policies, extension);
	}

	public void setContentStream(CallContext callContext,
			String repositoryId, Holder<String> objectId,
			boolean overwriteFlag, Holder<String> changeToken, ContentStream contentStream) {

		objectService.setContentStream(callContext, repositoryId, objectId,
				overwriteFlag, contentStream, changeToken);
	}

	public void deleteContentStream(CallContext callContext,
			String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ExtensionsData extension) {
		objectService.deleteContentStream(callContext, repositoryId, objectId,
				changeToken, extension);
	}

	public void appendContentStream(CallContext callContext,
			String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ContentStream contentStream,
			boolean isLastChunk, ExtensionsData extension) {
		objectService.appendContentStream(callContext, repositoryId, objectId,
				changeToken, contentStream, isLastChunk, extension);

	}

	public FailedToDeleteData deleteTree(CallContext callContext,
			String repositoryId, String folderId, Boolean allVersions,
			UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension) {

		return objectService.deleteTree(callContext, repositoryId, folderId,
				allVersions, unfileObjects, continueOnFailure, extension);
	}

	public void deleteObject(CallContext callContext, String repositoryId,
			String objectId, Boolean allVersions) {
		objectService.deleteObject(callContext, repositoryId, objectId, allVersions);
	}

	public void moveObject(CallContext callContext, String repositoryId,
			Holder<String> objectId, String sourceFolderId,
			String targetFolderId, CmisService couchCmisService) {
		objectService.moveObject(callContext, repositoryId, objectId,
				sourceFolderId, targetFolderId);
	}

	public List<RenditionData> getRenditions(CallContext callContext,
			String repositoryId, String objectId, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

		return objectService.getRenditions(callContext, repositoryId,
				objectId, renditionFilter, maxItems, skipCount, extension);
	}

	public void updateProperties(CallContext callContext,
			String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, Properties properties) {

		objectService.updateProperties(callContext, repositoryId, objectId,
				properties, changeToken);
	}

	public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(
			CallContext callContext,
			String repositoryId,
			List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken, Properties properties,
			List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension) {
		return objectService.bulkUpdateProperties(callContext,
				repositoryId, objectIdAndChangeToken, properties,
				addSecondaryTypeIds, removeSecondaryTypeIds, extension);
	}

	public ContentStream getContentStream(CallContext callContext,
			String repositoryId, String objectId, String streamId,
			BigInteger offset, BigInteger length) {

		return objectService.getContentStream(callContext, repositoryId, objectId,
				streamId, offset, length);
	}

	public ObjectData getObjectByPath(CallContext callContext, String repositoryId,
			String path, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePolicyIds,
			Boolean includeAcl, ExtensionsData extension) {

		return objectService.getObjectByPath(callContext, repositoryId, path,
				filter, includeAllowableActions, includeRelationships,
				renditionFilter, includePolicyIds, includeAcl, extension);
	}

	public ObjectData getObject(CallContext callContext, String repositoryId,
			String objectId, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePolicyIds,
			Boolean includeAcl, ExtensionsData extension) {

		ObjectData object = objectService.getObject(callContext, repositoryId,
				objectId, filter, includeAllowableActions,
				includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension);
		return object;
	}

	public AllowableActions getAllowableActions(CallContext callContext,
			String repositoryId, String objectId) {

		return objectService.getAllowableActions(callContext, repositoryId, objectId);
	}

	public String createFolder(CallContext callContext, String repositoryId,
			Properties properties, String folderId, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {

		return objectService.createFolder(callContext, repositoryId, properties,
				folderId, policies, addAces, removeAces, extension);
	}

	public String createDocumentFromSource(CallContext callContext,
			String repositoryId, String sourceId, Properties properties,
			String folderId, VersioningState versioningState,
			List<String> policies, Acl addAces, Acl removeAces) {

		return objectService.createDocumentFromSource(callContext, repositoryId,
				sourceId, properties, folderId, versioningState, policies,
				addAces, removeAces);
	}

	public String createDocument(CallContext callContext,
			String repositoryId, Properties properties,
			String folderId, ContentStream contentStream,
			VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces) {

		return objectService.createDocument(callContext, repositoryId, properties,
				folderId, contentStream, versioningState, policies, addAces, removeAces);
	}

	public void cancelCheckOut(CallContext callContext, String repositoryId,
			String objectId, ExtensionsData extension) {
		versioningService.cancelCheckOut(callContext, repositoryId, objectId, extension);
	}

	public void checkIn(CallContext callContext, String repositoryId,
			Holder<String> objectId, Boolean major, Properties properties,
			ContentStream contentStream, String checkinComment, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {
		versioningService.checkIn(callContext, repositoryId, objectId, major,
				properties, contentStream, checkinComment, policies, addAces,
				removeAces, extension);
	}

	public String createRelationship(CallContext callContext,
			String repositoryId, Properties properties, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {
		return objectService.createRelationship(callContext, repositoryId,
				properties, policies, addAces, removeAces, extension);
	}

	public String createPolicy(CallContext callContext, String repositoryId,
			Properties properties, String folderId, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {
		return objectService.createRelationship(callContext, repositoryId,
				properties, policies, addAces, removeAces, extension);
	}

	public String createItem(CallContext callContext, String repositoryId,
			Properties properties, String folderId, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {
		return objectService.createItem(callContext, repositoryId, properties,
				folderId, policies, addAces, removeAces, extension);
	}

	// --- Navigation Service

	public ObjectInFolderList getChildren(CallContext callContext,
			String repositoryId, String folderId, String filter,
			String orderBy,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegments,
			BigInteger maxItems, BigInteger skipCount,
			ExtensionsData extension, Holder<ObjectData> parentObjectData) {

		return navigationService.getChildren(callContext, repositoryId, folderId,
				filter, orderBy, includeAllowableActions,
				includeRelationships, renditionFilter, includePathSegments, maxItems,
				skipCount, extension, parentObjectData);
	}

	public List<ObjectInFolderContainer> getDescendants(
			CallContext callContext, String repositoryId, String folderId,
			BigInteger depth, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePathSegment,
			boolean foldersOnly, ExtensionsData extension, Holder<ObjectData> anscestorObjectData) {

		return navigationService.getDescendants(callContext, repositoryId, folderId,
				depth, filter, includeAllowableActions,
				includeRelationships, renditionFilter, includePathSegment, foldersOnly,
				extension, anscestorObjectData);
	}

	public ObjectData getFolderParent(CallContext callContext, String repositoryId,
			String folderId, String filter, ObjectInfoHandler objectInfos) {

		return navigationService.getFolderParent(callContext, repositoryId, folderId, filter);
	}

	public List<ObjectParentData> getObjectParents(CallContext callContext,
			String repositoryId, String objectId, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includeRelativePathSegment, ExtensionsData extension) {

		return navigationService.getObjectParents(callContext, repositoryId,
				objectId, filter, includeAllowableActions,
				includeRelationships, renditionFilter, includeRelativePathSegment, extension);
	}

	public ObjectList getCheckedOutDocs(CallContext callContext,
			String repositoryId, String folderId, String filter,
			String orderBy,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

		return navigationService.getCheckedOutDocs(callContext, repositoryId,
				folderId, filter, orderBy, includeAllowableActions,
				includeRelationships, renditionFilter, maxItems, skipCount, extension);
	}

	// --- Repository Service

	public boolean hasThisRepositoryId(String repositoryId) {
		return repositoryService.hasThisRepositoryId(repositoryId);
	}

	public RepositoryInfo getRepositoryInfo(String repositoryId) {
		return repositoryService.getRepositoryInfo(repositoryId);
	}

	public TypeDefinitionList getTypeChildren(CallContext callContext,
			String repositoryId, String typeId,
			Boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount) {
		return repositoryService.getTypeChildren(callContext, repositoryId,
				typeId, includePropertyDefinitions, maxItems, skipCount);
	}

	public List<TypeDefinitionContainer> getTypeDescendants(
			CallContext callContext, String repositoryId, String typeId,
			BigInteger depth, Boolean includePropertyDefinitions) {
		return repositoryService.getTypeDescendants(callContext, repositoryId, typeId,
				depth, includePropertyDefinitions);
	}

	public TypeDefinition getTypeDefinition(CallContext callContext,
			String repositoryId, String typeId) {
		return repositoryService.getTypeDefinition(callContext, repositoryId, typeId);
	}

	public TypeDefinition createType(CallContext callContext,
			String repositoryId, TypeDefinition type, ExtensionsData extension) {
		return repositoryService.createType(callContext, repositoryId, type, extension);
	}

	public void deleteType(CallContext callContext, String repositoryId,
			String typeId, ExtensionsData extension) {
		repositoryService.deleteType(callContext, repositoryId, typeId, extension);
	}

	public TypeDefinition updateType(CallContext callContext,
			String repositoryId, TypeDefinition type, ExtensionsData extension) {
		return repositoryService.updateType(callContext, repositoryId, type, extension);
	}

	// --- ACL Service
	public Acl getAcl(CallContext callContext, String repositoryId,
			String objectId, Boolean onlyBasicPermissions) {
		return aclService.getAcl(callContext, repositoryId, objectId, onlyBasicPermissions);
	}

	public Acl applyAcl(CallContext callContext, String repositoryId, String objectId,
			Acl acl, AclPropagation aclPropagation) {
		Acl newACL = null;

		newACL = aclService
				.applyAcl(callContext, repositoryId, objectId, acl, aclPropagation);

		return newACL;
	}

	// --- Discovery Service

	/**
	 * Executes a CMIS query statement against the contents of the repository.
	 * 
	 * TODO this should be replaced with other search engine like Lucene.
	 * @param repositoryId TODO
	 */
	public ObjectList query(CallContext callContext, String repositoryId,
			String statement, Boolean searchAllVersions,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

		return discoveryService.query(callContext, repositoryId,
				statement, searchAllVersions,
				includeAllowableActions, includeRelationships, renditionFilter, maxItems,
				skipCount, extension);
	}

	public ObjectList getContentChanges(CallContext context,
			String repositoryId, Holder<String> changeLogToken,
			Boolean includeProperties, String filter, Boolean includePolicyIds,
			Boolean includeAcl, BigInteger maxItems, ExtensionsData extension) {

		return discoveryService.getContentChanges(context, repositoryId,
				changeLogToken, includeProperties, filter, includePolicyIds,
				includeAcl, maxItems, extension);
	}

	// --- Versioning Service
	public void checkOut(CallContext callContext, String repositoryId,
			Holder<String> objectId, ExtensionsData extension, Holder<Boolean> contentCopied) {
		versioningService.checkOut(callContext, repositoryId, objectId,
				extension, contentCopied);
	}

	public List<ObjectData> getAllVersions(CallContext context,
			String repositoryId, String objectId, String versionSeriesId,
			String filter, Boolean includeAllowableActions, ExtensionsData extension) {

		return versioningService.getAllVersions(context, repositoryId,
				objectId, versionSeriesId, filter, includeAllowableActions, extension);
	}

	public ObjectData getObjectOfLatestVersion(CallContext context,
			String repositoryId, String objectId, String versionSeriesId,
			Boolean major, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includePolicyIds,
			Boolean includeAcl, ExtensionsData extension) {
		return versioningService.getObjectOfLatestVersion(context, repositoryId,
				objectId, versionSeriesId, major, filter,
				includeAllowableActions, includeRelationships, renditionFilter,
				includePolicyIds, includeAcl, extension);

	}

	// --- Relationship Service Implementation ---
	public ObjectList getObjectRelationships(CallContext callContext,
			String repositoryId, String objectId,
			Boolean includeSubRelationshipTypes, RelationshipDirection relationshipDirection,
			String typeId, String filter,
			Boolean includeAllowableActions, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		return relationshipService.getObjectRelationships(callContext,
				repositoryId, objectId, includeSubRelationshipTypes,
				relationshipDirection, typeId, filter, includeAllowableActions, maxItems,
				skipCount, extension);
	}

	// --- Policy Service Implementation ---
	public void applyPolicy(CallContext callContext, String repositoryId,
			String policyId, String objectId, ExtensionsData extension) {
		policyService.applyPolicy(callContext, repositoryId, policyId, objectId, extension);
	}

	public void removePolicy(CallContext callContext, String repositoryId,
			String policyId, String objectId, ExtensionsData extension) {
		policyService.removePolicy(callContext, repositoryId, policyId, objectId, extension);
	}

	public List<ObjectData> getAppliedPolicies(CallContext callContext,
			String repositoryId, String objectId, String filter, ExtensionsData extension) {
		return policyService.getAppliedPolicies(callContext, repositoryId, objectId,
				filter, extension);
	}

	// --- Multi-filing Service Implementation ---
	public void addObjectToFolder(CallContext callContext, String repositoryId,
			String objectId, String folderId, Boolean allVersions, ExtensionsData extension) {
		throw new CmisNotSupportedException(
				"Multi-filing service is not supported",
				BigInteger.valueOf(405));
	}

	public void removeObjectFromFolder(CallContext callContext,
			String repositoryId, String objectId, String folderId, ExtensionsData extension) {
		throw new CmisNotSupportedException(
				"Multi-filing service is not supported",
				BigInteger.valueOf(405));
	}

	public void setAclService(AclService aclService) {
		this.aclService = aclService;
	}

	public void setDiscoveryService(DiscoveryService discoveryService) {
		this.discoveryService = discoveryService;
	}

	public void setNavigationService(NavigationService navigationService) {
		this.navigationService = navigationService;
	}

	public void setObjectService(ObjectService objectService) {
		this.objectService = objectService;
	}

	public void setRepositoryService(RepositoryService repositoryService) {
		this.repositoryService = repositoryService;
	}

	public void setVersioningService(VersioningService versioningService) {
		this.versioningService = versioningService;
	}

	public void setPolicyService(PolicyService policyService) {
		this.policyService = policyService;
	}

	public void setRelationshipService(RelationshipService relationshipService) {
		this.relationshipService = relationshipService;
	}

}
