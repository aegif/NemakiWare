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
package jp.aegif.nemaki.service.cmis;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.repository.NemakiRepository;
import jp.aegif.nemaki.repository.RepositoryMap;

import org.apache.chemistry.opencmis.commons.PropertyIds;
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
import org.apache.chemistry.opencmis.commons.data.PropertyData;
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
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.server.AbstractCmisService;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Nemaki CMIS service.
 */
public class NemakiCmisService extends AbstractCmisService {

	/**
	 * Context data of the current CMIS call.
	 */
	private CallContext context;

	/**
	 * Map containing all Nemaki repositories.
	 */
	private RepositoryMap repositoryMap;

	/**
	 * Create a new NemakiCmisService.
	 */
	public NemakiCmisService(RepositoryMap repositoryMap) {
		this.repositoryMap = repositoryMap;
	}

	// --- Navigation Service Implementation ---

	/**
	 * Gets the list of child objects contained in the specified folder.
	 */
	@Override
	public ObjectInFolderList getChildren(String repositoryId, String folderId,
			String filter, String orderBy, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePathSegment, BigInteger maxItems,
			BigInteger skipCount, ExtensionsData extension) {
		return getRepository(repositoryId).getChildren(getCallContext(),
				folderId, filter, includeAllowableActions, includePathSegment,
				maxItems, skipCount, this);
	}

	/**
	 * Gets the set of descendant objects contained in the specified folder or
	 * any of its child folders.
	 */
	@Override
	public List<ObjectInFolderContainer> getDescendants(String repositoryId,
			String folderId, BigInteger depth, String filter,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePathSegment, ExtensionsData extension) {
		return getRepository(repositoryId).getDescendants(getCallContext(),
				folderId, depth, filter, includeAllowableActions,
				includePathSegment, this, false);
	}

	/**
	 * Gets the parent folder object for the specified folder object.
	 */
	@Override
	public ObjectData getFolderParent(String repositoryId, String folderId,
			String filter, ExtensionsData extension) {
		return getRepository(repositoryId).getFolderParent(getCallContext(),
				folderId, filter, this);
	}

	/**
	 * Gets the set of descendant folder objects contained in the specified
	 * folder.
	 */
	@Override
	public List<ObjectInFolderContainer> getFolderTree(String repositoryId,
			String folderId, BigInteger depth, String filter,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePathSegment, ExtensionsData extension) {
		return getRepository(repositoryId).getDescendants(getCallContext(),
				folderId, depth, filter, includeAllowableActions,
				includePathSegment, this, true);
	}

	/**
	 * Gets the parent folder(s) for the specified non-folder, fileable object.
	 */
	@Override
	public List<ObjectParentData> getObjectParents(String repositoryId,
			String objectId, String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includeRelativePathSegment, ExtensionsData extension) {
		return getRepository(repositoryId).getObjectParents(getCallContext(),
				objectId, filter, includeAllowableActions,
				includeRelativePathSegment, this);
	}

	/**
	 * Gets the list of documents that are checked out that the user has access
	 * to. No checkout for now, so empty.
	 */
	@Override
	public ObjectList getCheckedOutDocs(String repositoryId, String folderId,
			String filter, String orderBy, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

		return getRepository(repositoryId).getCheckedOutDocs(getCallContext(),
				folderId, filter, orderBy, includeAllowableActions,
				includeRelationships, renditionFilter, maxItems, skipCount,
				extension);

	}

	// ---- Object Service Implementation ---

	/**
	 * Creates a new document, folder or policy. The property
	 * "cmis:objectTypeId" defines the type and implicitly the base type.
	 */
	@Override
	public String create(String repositoryId, Properties properties,
			String folderId, ContentStream contentStream,
			VersioningState versioningState, List<String> policies,
			ExtensionsData extension) {
		ObjectData object = getRepository(repositoryId).create(
				getCallContext(), properties, folderId, contentStream,
				versioningState, policies, extension);
		return object.getId();
	}

	/**
	 * Creates a document object of the specified type (given by the
	 * cmis:objectTypeId property) in the (optionally) specified location.
	 */
	@Override
	public String createDocument(String repositoryId, Properties properties,
			String folderId, ContentStream contentStream,
			VersioningState versioningState, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {
		return getRepository(repositoryId).createDocument(getCallContext(),
				properties, folderId, contentStream, versioningState, policies, addAces, removeAces);
	}

	/**
	 * Creates a document object as a copy of the given source document in the
	 * (optionally) specified location.
	 */
	@Override
	public String createDocumentFromSource(String repositoryId,
			String sourceId, Properties properties, String folderId,
			VersioningState versioningState, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension) {
		return getRepository(repositoryId).createDocumentFromSource(
				getCallContext(), sourceId, properties, folderId,
				versioningState, policies, addAces, removeAces);
	}

	/**
	 * Creates a folder object of the specified type (given by the
	 * cmis:objectTypeId property) in the specified location.
	 */
	@Override
	public String createFolder(String repositoryId, Properties properties,
			String folderId, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		return getRepository(repositoryId).createFolder(getCallContext(),
				properties, folderId, policies, addAces, removeAces, extension);
	}
	
	@Override
	public String createRelationship(String repositoryId,
			Properties properties, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		// TODO Auto-generated method stub
		return getRepository(repositoryId).createRelationship(getCallContext(), properties, policies, addAces,
				removeAces, extension);
	}
	
	@Override
	public String createPolicy(String repositoryId, Properties properties,
			String folderId, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		return getRepository(repositoryId).createPolicy(getCallContext(), properties, folderId, policies,
				addAces, removeAces, extension);
	}
	
	

	/**
	 * Deletes the content stream for the specified document object.
	 */
	@Override
	public void deleteContentStream(String repositoryId,
			Holder<String> objectId, Holder<String> changeToken,
			ExtensionsData extension) {
		getRepository(repositoryId).deleteContentStream(getCallContext(), objectId, changeToken, extension);
	}

	/**
	 * Deletes an object or cancels a check out. For the Web Services binding
	 * this is always an object deletion. For the AtomPub it depends on the
	 * referenced object. If it is a checked out document then the check out
	 * must be canceled. If the object is not a checked out document then the
	 * object must be deleted.
	 */
	@Override
	public void deleteObjectOrCancelCheckOut(String repositoryId,
			String objectId, Boolean allVersions, ExtensionsData extension) {
		//TODO When checkOut implemented, implement switching the two methods
		ObjectData o = getObject(repositoryId, objectId, null, false, IncludeRelationships.NONE, null, null, null, null);
		PropertyData<?> isPWC = o.getProperties().getProperties().get(PropertyIds.IS_PRIVATE_WORKING_COPY);
		if(isPWC != null && isPWC.getFirstValue().equals(true)){
			getRepository(repositoryId).cancelCheckOut(getCallContext(), objectId, null);
		}else{
			getRepository(repositoryId).deleteObject(getCallContext(), objectId, allVersions);
		}
		
	}

	
	
	/**
	 * Deletes the specified folder object and all of its child- and
	 * descendant-objects.
	 */
	@Override
	public FailedToDeleteData deleteTree(String repositoryId, String folderId,
			Boolean allVersions, UnfileObject unfileObjects,
			Boolean continueOnFailure, ExtensionsData extension) {
		return getRepository(repositoryId).deleteTree(getCallContext(),
				folderId, allVersions, unfileObjects,
				continueOnFailure, extension);
	}

	/**
	 * Gets the list of allowable actions for an object.
	 */
	@Override
	public AllowableActions getAllowableActions(String repositoryId,
			String objectId, ExtensionsData extension) {
		return getRepository(repositoryId).getAllowableActions(
				getCallContext(), objectId);
	}

	/**
	 * Gets the content stream for the specified document object, or gets a
	 * rendition stream for a specified rendition of a document or folder
	 * object.
	 */
	@Override
	public ContentStream getContentStream(String repositoryId, String objectId,
			String streamId, BigInteger offset, BigInteger length,
			ExtensionsData extension) {
		return getRepository(repositoryId).getContentStream(getCallContext(),
				objectId, streamId);
	}

	/**
	 * Gets the specified information for the object specified by id.
	 */
	@Override
	public ObjectData getObject(String repositoryId, String objectId,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl,
			ExtensionsData extension) {
		return getRepository(repositoryId).getObject(getCallContext(),
				objectId, filter, includeAllowableActions, includeAcl,
				this);
	}

	/**
	 * Gets the specified information for the object specified by path.
	 */
	@Override
	public ObjectData getObjectByPath(String repositoryId, String path,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl,
			ExtensionsData extension) {
		return getRepository(repositoryId).getObjectByPath(getCallContext(),
				path, filter, includeAllowableActions, includeAcl, this);
	}

	/**
	 * Gets the list of properties for an object.
	 */
	@Override
	public Properties getProperties(String repositoryId, String objectId,
			String filter, ExtensionsData extension) {
		ObjectData object = getRepository(repositoryId).getObject(
				getCallContext(), objectId, filter, false, false, this);
		return object.getProperties();
	}

	/**
	 * Gets the list of associated renditions for the specified object. Only
	 * rendition attributes are returned, not rendition stream. No renditions,
	 * so empty.
	 */
	@Override
	public List<RenditionData> getRenditions(String repositoryId,
			String objectId, String renditionFilter, BigInteger maxItems,
			BigInteger skipCount, ExtensionsData extension) {

		return getRepository(repositoryId).getRenditions(getCallContext(),
				objectId, renditionFilter, maxItems, skipCount, extension);
	}

	/**
	 * Moves the specified file-able object from one folder to another.
	 */
	@Override
	public void moveObject(String repositoryId, Holder<String> objectId,
			String targetFolderId, String sourceFolderId,
			ExtensionsData extension) {
		//TODO Implement permission check here
		//PermissionMapping.CAN_GET_PROPERTIES_OBJECT
		
		
		getRepository(repositoryId).moveObject(getCallContext(), objectId, 
				sourceFolderId, targetFolderId, this);
	}

	/**
	 * Sets the content stream for the specified document object.
	 */
	@Override
	public void setContentStream(String repositoryId, Holder<String> objectId,
			Boolean overwriteFlag, Holder<String> changeToken,
			ContentStream contentStream, ExtensionsData extension) {
		getRepository(repositoryId).setContentStream(getCallContext(),
				objectId, overwriteFlag, contentStream);
	}

	/**
	 * Updates properties of the specified object.
	 */
	@Override
	public void updateProperties(String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, Properties properties,
			ExtensionsData extension) {
		getRepository(repositoryId).updateProperties(getCallContext(),
				objectId, changeToken, properties);
	}

	// --- Versioning Service Implementation ---
	@Override
	public void checkOut(String repositoryId, Holder<String> objectId,
			ExtensionsData extension, Holder<Boolean> contentCopied) {
		getRepository(repositoryId).checkOut(getCallContext(), objectId, extension, contentCopied);
	}
	
	@Override
	public void cancelCheckOut(String repositoryId, String objectId,
			ExtensionsData extension) {
		getRepository(repositoryId).cancelCheckOut(getCallContext(), objectId, extension);
	}

	@Override
	public void checkIn(String repositoryId, Holder<String> objectId,
			Boolean major, Properties properties, ContentStream contentStream,
			String checkinComment, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		getRepository(repositoryId).checkIn(getCallContext(), objectId, major, properties, contentStream, checkinComment, policies, addAces, removeAces, extension);
	}

	/**
	 * Returns the list of all document objects in the specified version series,
	 * sorted by the property "cmis:creationDate" descending.
	 */
	@Override
	public List<ObjectData> getAllVersions(String repositoryId,
			String objectId, String versionSeriesId, String filter,
			Boolean includeAllowableActions, ExtensionsData extension) {
		
		return getRepository(repositoryId).getAllVersions(getCallContext(), objectId, versionSeriesId, filter, includeAllowableActions, extension);
	}

	/**
	 * Get the latest document object in the version series.
	 */
	@Override
	public ObjectData getObjectOfLatestVersion(String repositoryId,
			String objectId, String versionSeriesId, Boolean major,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl,
			ExtensionsData extension) {
		return getRepository(repositoryId).getObjectOfLatestVersion(context, objectId, versionSeriesId, major, filter, includeAllowableActions, includeRelationships, renditionFilter, includePolicyIds, includeAcl, extension);
		
	}

	/**
	 * Get a subset of the properties for the latest document object in the
	 * version series.
	 */
	@Override
	public Properties getPropertiesOfLatestVersion(String repositoryId,
			String objectId, String versionSeriesId, Boolean major,
			String filter, ExtensionsData extension) {
		ObjectData object = getObjectOfLatestVersion(repositoryId, objectId, versionSeriesId, major, filter, false, IncludeRelationships.NONE, null, false, false, extension);
		return object.getProperties();
	}

	// --- ACL Service Implementation ---

	/**
	 * Applies a new ACL (Access Control List) to an object. Since it is not
	 * possible to transmit an "add ACL" and a "remove ACL" via AtomPub, the
	 * merging has to be done on the client side. The ACEs provided here is
	 * supposed to the new complete ACL.
	 */
	@Override
	public Acl applyAcl(String repositoryId, String objectId, Acl aces,
			AclPropagation aclPropagation) {
		return getRepository(repositoryId).applyAcl(getCallContext(), objectId,
				aces, aclPropagation);
	}

	/**
	 * Get the ACL (Access Control List) currently applied to the specified
	 * object.
	 */
	@Override
	public Acl getAcl(String repositoryId, String objectId,
			Boolean onlyBasicPermissions, ExtensionsData extension) {
		return getRepository(repositoryId).getAcl(getCallContext(), objectId, onlyBasicPermissions);
	}

	// --- Repository Service Implementation ---

	/**
	 * Returns information about the CMIS repository, the optional capabilities
	 * it supports and its access control information.
	 */
	@Override
	public RepositoryInfo getRepositoryInfo(String repositoryId,
			ExtensionsData extension) {

		for (NemakiRepository repository : repositoryMap.getRepositories()) {
			if (repository.hasThisRepositoryId(repositoryId)) {
				return repository.getRepositoryInfo();
			}
		}
		throw new CmisObjectNotFoundException("Unknown repository '"
				+ repositoryId + "'!");
	}

	/**
	 * Returns a list of CMIS repository information available from this CMIS
	 * service endpoint. In contrast to the CMIS specification this method
	 * returns repository infos not only repository ids. (See OpenCMIS doc)
	 */
	@Override
	public List<RepositoryInfo> getRepositoryInfos(ExtensionsData arg0) {
		List<RepositoryInfo> result = new ArrayList<RepositoryInfo>();
		for (NemakiRepository repository : repositoryMap.getRepositories()) {
			result.add(repository.getRepositoryInfo());
		}
		return result;
	}

	/**
	 * Returns the list of object types defined for the repository that are
	 * children of the specified type.
	 */
	@Override
	public TypeDefinitionList getTypeChildren(String repositoryId,
			String typeId, Boolean includePropertyDefinitions,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		return getRepository(repositoryId).getTypeChildren(getCallContext(),
				typeId, includePropertyDefinitions, maxItems, skipCount);
	}

	/**
	 * Gets the definition of the specified object type.
	 */
	@Override
	public TypeDefinition getTypeDefinition(String repositoryId, String typeId,
			ExtensionsData extension) {
		return getRepository(repositoryId).getTypeDefinition(getCallContext(),
				typeId);
	}

	/**
	 * Returns the set of descendant object type defined for the repository
	 * under the specified type.
	 */
	@Override
	public List<TypeDefinitionContainer> getTypeDescendants(
			String repositoryId, String typeId, BigInteger depth,
			Boolean includePropertyDefinitions, ExtensionsData extension) {
		return getRepository(repositoryId).getTypeDescendants(getCallContext(),
				typeId, depth, includePropertyDefinitions);
	}

	// --- Discovery Service Implementation ---

	/**
	 * Executes a CMIS query statement against the contents of the repository.
	 */
	@Override
	public ObjectList query(String repositoryId, String statement,
			Boolean searchAllVersions, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		return getRepository(repositoryId).query(getCallContext(), statement,
				searchAllVersions, includeAllowableActions,
				includeRelationships, renditionFilter, maxItems, skipCount,
				extension);
	}

	@Override
	public ObjectList getContentChanges(String repositoryId,
			Holder<String> changeLogToken, Boolean includeProperties,
			String filter, Boolean includePolicyIds, Boolean includeAcl,
			BigInteger maxItems, ExtensionsData extension) {

		return getRepository(repositoryId).getContentChanges(getCallContext(),
				changeLogToken, includeProperties, filter, includePolicyIds,
				includeAcl, maxItems, extension);
	}
	
	// --- Relationship Service Implementation ---
	@Override
	public ObjectList getObjectRelationships(String repositoryId,
			String objectId, Boolean includeSubRelationshipTypes,
			RelationshipDirection relationshipDirection, String typeId,
			String filter, Boolean includeAllowableActions,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		return getRepository(repositoryId).getObjectRelationships(getCallContext(), objectId, includeSubRelationshipTypes, relationshipDirection, typeId, filter, includeAllowableActions, maxItems, skipCount, extension);
	}
	
	// --- Policy Service Implementation ---
	@Override
	public void applyPolicy(String repositoryId, String policyId,
			String objectId, ExtensionsData extension) {
		getRepository(repositoryId).applyPolicy(getCallContext(), policyId, objectId, extension);
	}
	
	@Override
	public void removePolicy(String repositoryId, String policyId,
			String objectId, ExtensionsData extension) {
		getRepository(repositoryId).removePolicy(getCallContext(), policyId, objectId, extension);
	}

	@Override
	public List<ObjectData> getAppliedPolicies(String repositoryId,
			String objectId, String filter, ExtensionsData extension) {
		return getRepository(repositoryId).getAppliedPolicies(getCallContext(), objectId, filter, extension);
	}

	// --- Multi-filing Service Implementation ---
	@Override
	public void addObjectToFolder(String repositoryId, String objectId,
			String folderId, Boolean allVersions, ExtensionsData extension) {
		// TODO Auto-generated method stub
		super.addObjectToFolder(repositoryId, objectId, folderId, allVersions,
				extension);
	}

	@Override
	public void removeObjectFromFolder(String repositoryId, String objectId,
			String folderId, ExtensionsData extension) {
		// TODO Auto-generated method stub
		super.removeObjectFromFolder(repositoryId, objectId, folderId, extension);
	}

	/**
	 * Get repository that has given id.
	 */
	private NemakiRepository getRepository(String repositoryId) {
		for (NemakiRepository repository : repositoryMap.getRepositories()) {
			if (repository.hasThisRepositoryId(repositoryId)) {
				return repository;
			}
		}
		throw new CmisObjectNotFoundException("Unknown repository '"
				+ repositoryId + "'!");
	}

	@Override
	public void appendContentStream(String repositoryId,
			Holder<String> objectId, Holder<String> changeToken,
			ContentStream contentStream, boolean isLastChunk,
			ExtensionsData extension) {
		// TODO Auto-generated method stub
		super.appendContentStream(repositoryId, objectId, changeToken, contentStream,
				isLastChunk, extension);
	}

	@Override
	public Acl applyAcl(String repositoryId, String objectId, Acl addAces,
			Acl removeAces, AclPropagation aclPropagation,
			ExtensionsData extension) {
		// TODO Auto-generated method stub
		return super.applyAcl(repositoryId, objectId, addAces, removeAces,
				aclPropagation, extension);
	}

	@Override
	public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(
			String repositoryId,
			List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken,
			Properties properties, List<String> addSecondaryTypeIds,
			List<String> removeSecondaryTypeIds, ExtensionsData extension) {
		// TODO Auto-generated method stub
		return super.bulkUpdateProperties(repositoryId, objectIdAndChangeToken,
				properties, addSecondaryTypeIds, removeSecondaryTypeIds, extension);
	}

	@Override
	public String createItem(String repositoryId, Properties properties,
			String folderId, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		// TODO Auto-generated method stub
		return super.createItem(repositoryId, properties, folderId, policies, addAces,
				removeAces, extension);
	}

	@Override
	public TypeDefinition createType(String repositoryId, TypeDefinition type,
			ExtensionsData extension) {
		// TODO Auto-generated method stub
		return super.createType(repositoryId, type, extension);
	}

	@Override
	public void deleteType(String repositoryId, String typeId,
			ExtensionsData extension) {
		// TODO Auto-generated method stub
		super.deleteType(repositoryId, typeId, extension);
	}

	@Override
	public TypeDefinition updateType(String repositoryId, TypeDefinition type,
			ExtensionsData extension) {
		// TODO Auto-generated method stub
		return super.updateType(repositoryId, type, extension);
	}

	/*
	 * Setters/Getters
	 */
	public void setCallContext(CallContext context) {
		this.context = context;
	}

	private CallContext getCallContext() {
		return context;
	}

}
