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
 * You should have received a copy of the GNU General Public Licensealong with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public interface ContentService {

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	/**
	 * @param repositoryId TODO
	 * @param content
	 * @return
	 */
	public boolean isRoot(String repositoryId, Content content);

	public boolean isTopLevel(String repositoryId, Content content);
	
	/**
	 * Check if any object of a type exists
	 * @param repositoryId TODO
	 * @param objectTypeId
	 * @return
	 */
	boolean existContent(String repositoryId, String objectTypeId);

	/**
	 * Get a content(without type-specified)
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	Content getContent(String repositoryId, String objectId);

	/**
	 * Get a fileable content by path
	 * @param repositoryId TODO
	 * @param path
	 *
	 * @return
	 */
	Content getContentByPath(String repositoryId, String path);

	/**
	 * Get the parent folder
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	Folder getParent(String repositoryId, String objectId);

	/**
	 * Get children under a folder
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	List<Content> getChildren(String repositoryId, String folderId);

	/**
	 * Get a document
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	Document getDocument(String repositoryId, String objectId);

	/**
	 * Get the latest version document of a given versionSeries
	 * @param repositoryId TODO
	 * @param versionSeriesId
	 *
	 * @return
	 */
	Document getDocumentOfLatestVersion(String repositoryId, String versionSeriesId);

	/**
	 * Get the latest version major document of a given versionSeries
	 * @param repositoryId TODO
	 * @param versionSeriesId
	 *
	 * @return
	 */
	Document getDocumentOfLatestMajorVersion(String repositoryId, String versionSeriesId);

	/**
	 * Get all versions of a document
	 * @param callContext TODO
	 * @param repositoryId TODO
	 * @param versionSeriesId
	 * @return
	 */
	List<Document> getAllVersions(CallContext callContext, String repositoryId, String versionSeriesId);

	/**
	 * Get checkout documents in a folder
	 * @param repositoryId TODO
	 * @param folderId
	 * @param orderBy
	 * @param extension
	 *
	 * @return
	 */
	List<Document> getCheckedOutDocs(String repositoryId, String folderId,
			String orderBy, ExtensionsData extension);

	/**
	 * Get a version series
	 * @param repositoryId TODO
	 * @param document
	 * @return
	 */
	VersionSeries getVersionSeries(String repositoryId, Document document);

	/**
	 * Get a version series
	 * @param repositoryId TODO
	 * @param versionSeriesId
	 *
	 * @return
	 */
	VersionSeries getVersionSeries(String repositoryId, String versionSeriesId);

	/**
	 * Get a folder
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	Folder getFolder(String repositoryId, String objectId);

	Folder getSystemFolder(String repositoryId);
	
	/**
	 * Get a path string
	 * @param repositoryId TODO
	 * @param content
	 *
	 * @return
	 */
	String calculatePath(String repositoryId, Content content);



	/**
	 * Get a relationship
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	Relationship getRelationship(String repositoryId, String objectId);

	/**
	 * Search relationships by the edge node
	 * @param repositoryId TODO
	 * @param objectId
	 * @param relationshipDirection
	 *
	 * @return
	 */
	List<Relationship> getRelationsipsOfObject(String repositoryId,
			String objectId, RelationshipDirection relationshipDirection);

	/**
	 * Get a policy
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	Policy getPolicy(String repositoryId, String objectId);

	/**
	 * Get an item
	 * @param repositoryId TODO
	 * @param objectId
	 * @return
	 */
	Item getItem(String repositoryId, String objectId);
	
	UserItem getUserItem(String repositoryId, String objectId);
	UserItem getUserItemById(String repositoryId, String userId);
	List<UserItem> getUserItems(String repositoryId);
	
	GroupItem getGroupItem(String repositoryId, String objectId);
	GroupItem getGroupItemById(String repositoryId, String groupId);
	List<GroupItem> getGroupItems(String repositoryId);
	
	Set<String> getGroupIdsContainingUser(String repositoryId, String userId);
	String getAnonymous(String repositoryId);
	String getAnyone(String repositoryId);

	/**
	 * Create a document
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param properties
	 * @param parentFolder
	 * @param contentStream
	 * @param versioningState
	 * @return
	 */
	Document createDocument(CallContext callContext, String repositoryId,
			Properties properties, Folder parentFolder,
			ContentStream contentStream, VersioningState versioningState, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces, org.apache.chemistry.opencmis.commons.data.Acl removeAces);

	/**
	 * Copy a document
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param properties
	 * @param target
	 * @param original
	 * @param versioningState
	 * @param policies
	 * @param addAces
	 * @param removeAces
	 * @return
	 */
	Document createDocumentFromSource(CallContext callContext,
			String repositoryId, Properties properties, Folder target,
			Document original, VersioningState versioningState,
			List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces, org.apache.chemistry.opencmis.commons.data.Acl removeAces);

	/**
	 * Copy a document setting new content stream
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param original
	 * @param contentStream
	 * @return
	 */
	Document createDocumentWithNewStream(CallContext callContext,
			String repositoryId, Document original, ContentStream contentStream);

	Document replacePwc(CallContext callContext, String repositoryId, Document original, ContentStream contentStream);

	/**
	 * Check out and create PWC
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param objectId
	 * @param extension
	 * @return
	 */
	Document checkOut(CallContext callContext, String repositoryId,
			String objectId, ExtensionsData extension);

	/**
	 * Cancel checking out
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param objectId
	 * @param extension
	 */
	void cancelCheckOut(CallContext callContext, String repositoryId,
			String objectId, ExtensionsData extension);

	/**
	 * Check in and delete PWC
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param objectId
	 * @param major
	 * @param properties
	 * @param contentStream
	 * @param checkinComment
	 * @param policies
	 * @param addAces
	 * @param removeAces
	 * @param extension
	 * @return
	 */
	Document checkIn(CallContext callContext, String repositoryId,
			Holder<String> objectId, Boolean major, Properties properties,
			ContentStream contentStream, String checkinComment,
			List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);

	/**
	 * Create a folder
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param properties
	 * @param parentFolder
	 * @param extension TODO
	 * @return
	 */
	Folder createFolder(CallContext callContext, String repositoryId,
			Properties properties, Folder parentFolder, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl
			 addAces, org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);

	/**
	 * Create a relationship
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param properties
	 * @param policies
	 * @param addAces
	 * @param removeAces
	 * @param extension
	 * @return
	 */
	Relationship createRelationship(CallContext callContext,
			String repositoryId, Properties properties,
			List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);

	/**
	 * Create a policy
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param properties
	 * @param policies
	 * @param addAces
	 * @param removeAces
	 * @param extension
	 * @return
	 */
	Policy createPolicy(CallContext callContext, String repositoryId,
			Properties properties,
			List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);

	/**
	 * Create an item
	 * @param callContext
	 * @param repositoryId TODO
	 * @param properties
	 * @param folderId
	 * @param policies
	 * @param addAces
	 * @param removeAces
	 * @param extension
	 * @return
	 */
	Item createItem(CallContext callContext, String repositoryId,
			Properties properties, String folderId, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces, org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);

	UserItem createUserItem(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);
	UserItem createUserItem(CallContext callContext, String repositoryId, UserItem userItem);
	GroupItem createGroupItem(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);
	GroupItem createGroupItem(CallContext callContext, String repositoryId, GroupItem groupItem);
	
	Content update(CallContext callContext, String repositoryId, Content content);
	
	/**
	 * Update a content(for general-purpose)
	 * @param repositoryId TODO
	 * @param content
	 *
	 * @return
	 */
	Content updateInternal(String repositoryId, Content content);

	/**
	 * Update properties of a content
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param properties
	 * @param content
	 * @return
	 */
	Content updateProperties(CallContext callContext, String repositoryId,
			Properties properties, Content content);

	/**
	 * Move a content
	 * @param callContext TODO
	 * @param repositoryId TODO
	 * @param content
	 * @param target
	 */
	void move(CallContext callContext, String repositoryId, Content content, Folder target);

	/**
	 * Apply a policy from a content
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param policyId
	 * @param objectId
	 * @param extension
	 */
	void applyPolicy(CallContext callContext, String repositoryId, String policyId,
			String objectId, ExtensionsData extension);

	/**
	 * Remove a policy from a content
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param policyId
	 * @param objectId
	 * @param extension
	 */
	void removePolicy(CallContext callContext, String repositoryId,
			String policyId, String objectId, ExtensionsData extension);

	List<Policy> getAppliedPolicies(String repositoryId, String objectId, ExtensionsData extension);


	/**
	 * Delete a content(for general-purpose)
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param objectId
	 * @param deletedWithParent
	 */
	void delete(CallContext callContext, String repositoryId,
			String objectId, Boolean deletedWithParent);

	/**
	 * Delete a document (and also its versions)
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param objectId
	 * @param allVersions
	 * @param deleteWithParent
	 */
	void deleteDocument(CallContext callContext, String repositoryId,
			String objectId, Boolean allVersions, Boolean deleteWithParent);

	/**
	 * Delete an attachment node
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param attachmentId
	 */
	void deleteAttachment(CallContext callContext, String repositoryId, String attachmentId);

	/**
	 * Delete content stream
	 * @param callContext
	 * @param repositoryId TODO
	 * @param objectId
	 */
	void deleteContentStream(CallContext callContext, String repositoryId, Holder<String> objectId);

	/**
	 * Delete a whole folder tree
	 *
	 * @param context
	 * @param repositoryId TODO
	 * @param folderId
	 * @param allVersions
	 * @param continueOnFailure
	 * @param deletedWithParent
	 * @return TODO
	 * @throws Exception
	 */
	List<String> deleteTree(CallContext context, String repositoryId, String folderId,
			Boolean allVersions, Boolean continueOnFailure, Boolean deletedWithParent);

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	/**
	 * Get an attachment
	 * @param repositoryId TODO
	 * @param attachmentId
	 *
	 * @return
	 */
	AttachmentNode getAttachment(String repositoryId, String attachmentId);

	/**
	 * Get an attachment without stream
	 * @param repositoryId TODO
	 * @param attachmentId
	 *
	 * @return
	 */
	AttachmentNode getAttachmentRef(String repositoryId, String attachmentId);

	/**
	 *
	 *
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param objectId
	 * @param changeToken
	 * @param contentStream
	 * @param isLastChunk
	 * @param extension
	 */
	void appendAttachment(CallContext callContext, String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ContentStream contentStream,
			boolean isLastChunk, ExtensionsData extension);

	/**
	 * Get a rendition
	 * @param repositoryId TODO
	 * @param streamId
	 *
	 * @return
	 */
	Rendition getRendition(String repositoryId, String streamId);

	/**
	 * Get renditions of a content
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	List<Rendition> getRenditions(String repositoryId, String objectId);

	// ///////////////////////////////////////
	// Acl
	// ///////////////////////////////////////
	public Acl calculateAcl(String repositoryId, Content content);

	public Boolean getAclInheritedWithDefault(String repositoryId, Content content);
	
	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	/**
	 * Get a change event
	 * @param repositoryId TODO
	 * @param changeTokenId
	 *
	 * @return
	 */
	Change getChangeEvent(String repositoryId, String changeTokenId);

	/**
	 * Get latest change events in the change log
	 * @param repositoryId TODO
	 * @param context
	 * @param changeLogToken
	 * @param includeProperties
	 * @param filter
	 * @param includePolicyIds
	 * @param includeAcl
	 * @param maxItems
	 * @param extension
	 *
	 * @return
	 */
	List<Change> getLatestChanges(String repositoryId,
			CallContext context, Holder<String> changeLogToken,
			Boolean includeProperties, String filter, Boolean includePolicyIds,
			Boolean includeAcl, BigInteger maxItems, ExtensionsData extension);

	/**
	 * Get the latest change token in the repository
	 * @param repositoryId TODO
	 *
	 * @return
	 */
	String getLatestChangeToken(String repositoryId);

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	/**
	 * Get all archives in the repository
	 * @param repositoryId TODO
	 *
	 * @return
	 */
	List<Archive> getAllArchives(String repositoryId);

	/**
	 * Get a specified number of archives
	 * @param repositoryId
	 * @param skip
	 * @param limit
	 * @param desc
	 * @return
	 */
	List<Archive> getArchives(String repositoryId, Integer skip, Integer limit, Boolean desc);
	
	
	/**
	 * Get an archive
	 * @param repositoryId TODO
	 * @param archiveId
	 *
	 * @return
	 */
	Archive getArchive(String repositoryId, String archiveId);

	/**
	 * Get an archive by its original content's id
	 * @param repositoryId TODO
	 * @param archiveId
	 *
	 * @return
	 */
	Archive getArchiveByOriginalId(String repositoryId, String archiveId);

	/**
	 * Create an archive of a content
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param objectId
	 * @param deletedWithParent
	 * @return
	 */
	Archive createArchive(CallContext callContext, String repositoryId,
			String objectId, Boolean deletedWithParent);

	/**
	 * Create an archive of an attachment
	 *
	 * @param callContext
	 * @param repositoryId TODO
	 * @param attachmentId
	 * @return
	 */
	Archive createAttachmentArchive(CallContext callContext, String repositoryId, String attachmentId);
	
	/**
	 * Restore a content from an archive
	 * @param repositoryId TODO
	 * @param archiveId
	 */
	void restoreArchive(String repositoryId, String archiveId) throws ParentNoLongerExistException;

	/**
	 * Destroy an archive from database 
	 * @param repositoryId
	 * @param archiveId
	 */
	public void destroyArchive(String repositoryId, String archiveId);
	
	/**
	 * Write change event
	 * @param callContext
	 * @param repositoryId TODO
	 * @param content
	 * @param acl
	 * @param changeType
	 */
	String writeChangeEvent(CallContext callContext, String repositoryId, Content content,
			Acl acl, ChangeType changeType);
	
	/**
	 * Update version without checkIn/Out
	 * @param callContext
	 * @param repositoryId
	 * @param objectId
	 * @param major
	 * @param properties
	 * @param contentStream
	 * @param checkinComment
	 * @param originalDoc
	 * @return
	 */
	public Document updateWithoutCheckInOut(CallContext callContext, String repositoryId,
			Boolean major, Properties properties,
			ContentStream contentStream, String checkinComment, Document previousDoc, VersionSeries versionSeries);

	/**
	 * Get the actual attachment size from CouchDB metadata
	 * @param repositoryId Repository ID
	 * @param attachmentId Attachment node ID
	 * @return Actual size in bytes from CouchDB attachment metadata, or null if not available
	 */
	Long getAttachmentActualSize(String repositoryId, String attachmentId);
}