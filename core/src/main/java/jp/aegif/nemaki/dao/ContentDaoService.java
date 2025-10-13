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
package jp.aegif.nemaki.dao;

import java.util.List;

import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.PatchHistory;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;

import org.apache.chemistry.opencmis.commons.data.ContentStream;

/**
 * Dao Service implementation for CouchDB.
 *
 * @author linzhixing
 *
 */
public interface ContentDaoService {
	// ///////////////////////////////////////
	// Type & Property definition
	// ///////////////////////////////////////
	/**
	 * Get user-defined type definitions
	 * @param repositoryId TODO
	 *
	 * @return if nothing found, return null
	 */
	List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId);

	/**
	 * Get user-defined type definition
	 * for internal use(cached service) only
	 * @param repositoryId TODO
	 * @param typeId
	 * @return
	 */
	NemakiTypeDefinition getTypeDefinition(String repositoryId, String typeId);

	/**
	 * Create a user-defined type definition
	 * @param repositoryId TODO
	 * @param typeDefinition
	 * @return
	 */
	NemakiTypeDefinition createTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition);

	/**
	 * Update a user-defined type definition
	 * @param repositoryId TODO
	 * @param typeDefinition
	 *
	 * @return
	 */
	NemakiTypeDefinition updateTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition);

	/**
	 * Delete a user-defined type definition
	 * @param repositoryId TODO
	 * @param typeDefinition
	 *
	 * @return
	 */
	void deleteTypeDefinition(String repositoryId, String nodeId);

	/**
	 * List up user-defined property definitions
	 * @param repositoryId TODO
	 * @return
	 */
	List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String repositoryId);

	/**
	 * Get the core of user-defined property definition
	 * That is, propertyId, proeprtyType, queryName, cardinality
	 * @param repositoryId TODO
	 * @param nodeId
	 * @return
	 */
	NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String nodeId);

	/**
	 * Get the core of user-defined property definition by proeprtyId
	 * That is, propertyId, proeprtyType, queryName, cardinality
	 * @param repositoryId TODO
	 * @param nodeId
	 * @return
	 */
	NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String repositoryId, String propertyId);

	/**
	 * Get a user-defined property definition detail
	 * That is, all the other attributes than core
	 * @param repositoryId TODO
	 * @param nodeId
	 * @return if nothing found, return null
	 */
	NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String repositoryId, String nodeId);

	/**
	 * Get a user-defined property definition detail by coreNodeId
	 * That is, all the other attributes than core
	 * @param repositoryId TODO
	 * @param nodeId
	 * @return if nothing found, return null
	 */
	List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String repositoryId, String coreNodeId);

	/**
	 * Create a user-defined property definition core
	 * @param repositoryId TODO
	 * @param propertyDefinitionCore
	 * @return
	 */
	NemakiPropertyDefinitionCore createPropertyDefinitionCore(String repositoryId, NemakiPropertyDefinitionCore propertyDefinitionCore);

	/**
	 * Create a user-defined property definition detail
	 * @param repositoryId TODO
	 * @param propertyDefinitionDetail
	 * @return
	 */
	NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(String repositoryId, NemakiPropertyDefinitionDetail propertyDefinitionDetail);

	/**
	 * Update a user-defined property definition
	 * @param repositoryId TODO
	 * @param propertyDefinition
	 *
	 * @return
	 */
	NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(String repositoryId, NemakiPropertyDefinitionDetail propertyDefinitionDetail);

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	/**
	 * Get a node
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return if nothing found, return null
	 */
	NodeBase getNodeBase(String repositoryId, String objectId);

	/**
	 * Get a content Result will be return as Content class
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return if nothing found, return null
	 */
	Content getContent(String repositoryId, String objectId);

	/**
	 * Get content by object ID, bypassing cache to get fresh database state
	 * This method is specifically for revision-critical operations
	 * 
	 * @param repositoryId
	 * @param objectId
	 * @return fresh content from database, if nothing found, return null
	 */
	Content getContentFresh(String repositoryId, String objectId);

	/**
	 * Get document by object ID, bypassing cache to get fresh database state
	 * This method is specifically for revision-critical operations
	 * 
	 * @param repositoryId
	 * @param objectId
	 * @return fresh document from database, if nothing found, return null
	 */
	Document getDocumentFresh(String repositoryId, String objectId);

	/**
	 * Get folder by object ID, bypassing cache to get fresh database state
	 * This method is specifically for revision-critical operations
	 * 
	 * @param repositoryId
	 * @param objectId
	 * @return fresh folder from database, if nothing found, return null
	 */
	Folder getFolderFresh(String repositoryId, String objectId);

	/**
	 * Get relationship by object ID, bypassing cache to get fresh database state
	 * This method is specifically for revision-critical operations
	 * 
	 * @param repositoryId
	 * @param objectId
	 * @return fresh relationship from database, if nothing found, return null
	 */
	Relationship getRelationshipFresh(String repositoryId, String objectId);

	/**
	 * Get policy by object ID, bypassing cache to get fresh database state
	 * This method is specifically for revision-critical operations
	 * 
	 * @param repositoryId
	 * @param objectId
	 * @return fresh policy from database, if nothing found, return null
	 */
	Policy getPolicyFresh(String repositoryId, String objectId);

	/**
	 * Get item by object ID, bypassing cache to get fresh database state
	 * This method is specifically for revision-critical operations
	 * 
	 * @param repositoryId
	 * @param objectId
	 * @return fresh item from database, if nothing found, return null
	 */
	Item getItemFresh(String repositoryId, String objectId);

	/**
	 * Check if there are any object of the specified object type
	 * @param repositoryId TODO
	 * @param objectTypeId
	 * @return
	 */
	boolean existContent(String repositoryId, String objectTypeId);

	/**
	 * Get a document
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	Document getDocument(String repositoryId, String objectId);

	/**
	 * Get a list of checked out documents in a folder
	 * @param repositoryId TODO
	 * @param parentFolderId
	 *
	 * @return if nothing found, return null
	 */
	List<Document> getCheckedOutDocuments(String repositoryId, String parentFolderId);

	/**
	 * Get a version series
	 * @param repositoryId TODO
	 * @param nodeId
	 *
	 * @return if nothing found, return null
	 */
	VersionSeries getVersionSeries(String repositoryId, String nodeId);

	/**
	 * Get the latest version of a document
	 * @param repositoryId TODO
	 * @param versionSeriesId
	 *
	 * @return if nothing found, return null
	 */
	Document getDocumentOfLatestVersion(String repositoryId, String versionSeriesId);

	/**
	 * Get the latest major version of a document
	 * @param repositoryId TODO
	 * @param versionSeriesId
	 *
	 * @return if nothing found, return null
	 */
	Document getDocumentOfLatestMajorVersion(String repositoryId, String versionSeriesId);

	/**
	 * Get all the version series
	 * @param repositoryId TODO
	 * @param versionSeriesId
	 *
	 * @return
	 */
	List<Document> getAllVersions(String repositoryId, String versionSeriesId);

	/**
	 * Get a folder
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return if nothing found, return null
	 */
	Folder getFolder(String repositoryId, String objectId);

	/**
	 * Get a folder by path
	 * @param repositoryId TODO
	 * @param path
	 *
	 * @return if nothing found, return null
	 */
	Folder getFolderByPath(String repositoryId, String path);

	/**
	 * Get all the contents in a folder (as contents, that is, as indices)
	 * Documents are limited to the latest versions
	 * @param repositoryId TODO
	 * @param parentId
	 *
	 * @return
	 */
	List<Content> getChildren(String repositoryId, String parentId);

	/**
	 * Get a child content by name
	 * @param repositoryId TODO
	 * @param parentId
	 * @param name
	 *
	 * @return if nothing found, return null
	 */
	Content getChildByName(String repositoryId, String parentId, String name);

	/**
	 * Get children name index in a folder
	 * @param repositoryId
	 * @param parentId
	 * @return
	 */
	List<String> getChildrenNames(String repositoryId, String parentId);

	/**
	 * Get a relationship
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return if nothing found, return null
	 */
	Relationship getRelationship(String repositoryId, String objectId);

	/**
	 * Get a relationship by Source ID
	 * @param repositoryId TODO
	 * @param sourceId
	 *
	 * @return
	 */
	List<Relationship> getRelationshipsBySource(String repositoryId, String sourceId);

	/**
	 * Get a relationship by Target ID
	 * @param repositoryId TODO
	 * @param targetId
	 *
	 * @return if nothing found, return null
	 */
	List<Relationship> getRelationshipsByTarget(String repositoryId, String targetId);

	/**
	 * Get a policy
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return if nothing found, return null
	 */
	Policy getPolicy(String repositoryId, String objectId);

	/**
	 * Get a policy applied to an object
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return if nothing found, return null
	 */
	List<Policy> getAppliedPolicies(String repositoryId, String objectId);

	/**
	 * Get an item
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return if nothing found, return null
	 */
	Item getItem(String repositoryId, String objectId);

	UserItem getUserItem(String repositoryId, String objectId);
	UserItem getUserItemById(String repositoryId, String userId);
	List<UserItem> getUserItems(String repositoryId);

	GroupItem getGroupItem(String repositoryId, String objectId);
	GroupItem getGroupItemById(String repositoryId, String userId);

	/**
	 * Get group item by group ID, bypassing cache to get fresh database state
	 * This method is specifically for revision-critical operations (e.g., retry logic with optimistic locking)
	 *
	 * @param repositoryId
	 * @param groupId
	 * @return fresh group item from database, if nothing found, return null
	 */
	GroupItem getGroupItemByIdFresh(String repositoryId, String groupId);

	List<GroupItem> getGroupItems(String repositoryId);
	List<String> getJoinedGroupByUserId(String repositoryId, String userId);

	PatchHistory getPatchHistoryByName(String repositoryId, String name);
	Configuration getConfiguration(String repositoryId);

	/**
	 * Create a document
	 * @param repositoryId TODO
	 * @param document
	 *
	 * @return the newly created document
	 */
	Document create(String repositoryId, Document document);

	/**
	 * Create a version series
	 * @param repositoryId TODO
	 * @param versionSeries
	 *
	 * @return the newly created version series
	 */
	VersionSeries create(String repositoryId, VersionSeries versionSeries);

	/**
	 * Create a folder
	 * @param repositoryId TODO
	 * @param folder
	 *
	 * @return the newly created folder
	 */
	Folder create(String repositoryId, Folder folder);

	/**
	 * Create a relationship
	 * @param repositoryId TODO
	 * @param relationship
	 *
	 * @return the newly created relationship
	 */
	Relationship create(String repositoryId, Relationship relationship);

	/**
	 * Create a policy
	 * @param repositoryId TODO
	 * @param policy
	 *
	 * @return the newly created policy
	 */
	Policy create(String repositoryId, Policy policy);

	/**
	 * Create an item
	 * @param repositoryId TODO
	 * @param policy
	 *
	 * @return the newly created item
	 */
	Item create(String repositoryId, Item item);

	UserItem create(String repositoryId, UserItem userItem);
	GroupItem create(String repositoryId, GroupItem groupItem);

	PatchHistory create(String repositoryId, PatchHistory patchHistory);
	Configuration create(String repositoryId, Configuration configuration);

	NodeBase create(String repositoryId, NodeBase nodeBase);

	/**
	 * Update a document
	 * @param repositoryId TODO
	 * @param document
	 *
	 * @return the newly updated document
	 */
	Document update(String repositoryId, Document document);

	Document move(String repositoryId, Document document, String sourceId);

	/**
	 * Update a version series
	 * @param repositoryId TODO
	 * @param versionSeries
	 *
	 * @return the newly updated version series
	 */
	VersionSeries update(String repositoryId, VersionSeries versionSeries);

	/**
	 * Update a folder
	 * @param repositoryId TODO
	 * @param folder
	 *
	 * @return the newly updated folder
	 */
	Folder update(String repositoryId, Folder folder);

	Folder move(String repositoryId, Folder folder, String sourceId);

	/**
	 * Update a relationship
	 * @param repositoryId TODO
	 * @param relationship
	 *
	 * @return
	 */
	Relationship update(String repositoryId, Relationship relationship);

	/**
	 * Update a relationship
	 * @param repositoryId TODO
	 * @param relationship
	 *
	 * @return
	 */
	Policy update(String repositoryId, Policy policy);

	/**
	 * Update an item
	 * @param repositoryId TODO
	 * @param policy
	 *
	 * @return the newly updated item
	 */
	Item update(String repositoryId, Item item);

	UserItem update(String repositoryId, UserItem userItem);
	GroupItem update(String repositoryId, GroupItem groupItem);

	PatchHistory update(String repositoryId, PatchHistory patchHistory);

	Configuration update(String repositoryId, Configuration configuration);

	NodeBase update(String repositoryId, NodeBase nodeBase);

	/**
	 * Delete a content
	 * @param repositoryId TODO
	 * @param objectId
	 */
	void delete(String repositoryId, String objectId);

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	/**
	 * Get an attachment(without InputStream)
	 * for non-cached service only
	 * @param repositoryId TODO
	 * @param attachmentId
	 * @return if nothing found, return null
	 */
	AttachmentNode getAttachment(String repositoryId, String attachmentId);

	/**
	 * Set InputStream
	 * for non-cached service only
	 * @param repositoryId TODO
	 * @param attachmentNode
	 */
	void setStream(String repositoryId, AttachmentNode attachmentNode);

	/**
	 * Get a rendition
	 * @param repositoryId TODO
	 * @param objectId
	 *
	 * @return
	 */
	Rendition getRendition(String repositoryId, String objectId);

	/**
	 * Create a rendition
	 * @param repositoryId TODO
	 * @param rendition
	 * @param contentStream
	 * @return
	 */
	String createRendition(String repositoryId, Rendition rendition, ContentStream contentStream);

	/**
	 * Create an attachment
	 * @param repositoryId TODO
	 * @param attachment
	 * @param contentStream
	 *
	 * @return a created attachment's node id
	 */
	String createAttachment(String repositoryId, AttachmentNode attachment, ContentStream cs);

	/**
	 * Update an attachment
	 * (replace an existing attachment)
	 * @param repositoryId TODO
	 * @param attachment
	 * @param contentStream
	 */
	void updateAttachment(String repositoryId, AttachmentNode attachment, ContentStream contentStream);

	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	/**
	 * Get a change event
	 * @param repositoryId TODO
	 * @param changeTokenId
	 *
	 * @return if nothing found, return null
	 */
	Change getChangeEvent(String repositoryId, String changeTokenId);

	/**
	 * Get the latest change event in the repository
	 * @param repositoryId TODO
	 *
	 * @return if nothing found, return null
	 */
	Change getLatestChange(String repositoryId);

	/**
	 * Get latest change events
	 * @param repositoryId TODO
	 * @param maxItems
	 *            "<= 0" means "infinite"
	 * @param latestChangeToken
	 *            "<= 0" means "From the beginning"
	 *
	 * @return Return results with descending order by time
	 */
	List<Change> getLatestChanges(String repositoryId, String startToken, int maxItems);

	/**
	 *
	 * @param repositoryId
	 * @param objectId
	 * @return
	 */
	List<Change> getObjectChanges(String repositoryId, String objectId);

	/**
	 * Create a change event
	 * @param repositoryId TODO
	 * @param change
	 *
	 * @return a newly created change event
	 */
	Change create(String repositoryId, Change change);

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	/**
	 * Get an archive
	 * @param repositoryId TODO
	 * @param archiveId
	 *
	 * @return if nothing found, return null
	 */
	Archive getArchive(String repositoryId, String archiveId);

	/**
	 * Get an archive by its original content's object ID
	 * @param repositoryId TODO
	 * @param originalId
	 *
	 * @return if nothing found, return null
	 */
	Archive getArchiveByOriginalId(String repositoryId, String originalId);

	/**
	 * Get an archive of an attachment
	 * @param repositoryId TODO
	 * @param archive
	 *
	 * @return if nothing found, return null
	 */
	Archive getAttachmentArchive(String repositoryId, Archive archive);

	/**
	 * Get archives of the children of the original folder
	 * @param repositoryId TODO
	 * @param archive
	 *
	 * @return if nothing found, return null
	 */
	List<Archive> getChildArchives(String repositoryId, Archive archive);

	/**
	 * Get an archive of a version series
	 * @param repositoryId TODO
	 * @param versionSeriesId
	 *
	 * @return if nothing found, return null
	 */
	List<Archive> getArchivesOfVersionSeries(String repositoryId, String versionSeriesId);

	/**
	 * Get all the archives in the repository
	 * @param repositoryId TODO
	 *
	 * @return if nothing found, return null
	 */
	List<Archive> getAllArchives(String repositoryId);

	List<Archive> getArchives(String repositoryId, Integer skip, Integer limit, Boolean desc);

	/**
	 * Create an archive of a content
	 * @param repositoryId TODO
	 * @param archive
	 * @param deleteWithParent
	 *
	 * @return a newly created archive of a content
	 */
	Archive createArchive(String repositoryId, Archive archive, Boolean deleteWithParent);

	/**
	 * Create an archive of an attachment
	 * @param repositoryId TODO
	 * @param archive
	 *
	 * @return a newly created archive of an attachment
	 */
	Archive createAttachmentArchive(String repositoryId, Archive archive);

	/**
	 * Delete an archive
	 * @param repositoryId TODO
	 * @param archiveId
	 */
	void deleteArchive(String repositoryId, String archiveId);

	void deleteDocumentArchive(String repositoryId, String archiveId);

	void refreshCmisObjectData(String repositoryId, String objectId);

	/**
	 * Restore a content from its archive
	 * @param repositoryId TODO
	 * @param archive
	 */
	void restoreContent(String repositoryId, Archive archive);

	/**
	 * Restore an attachment from its archive
	 * @param repositoryId TODO
	 * @param archive
	 */
	void restoreAttachment(String repositoryId, Archive archive);

	void restoreDocumentWithArchive(String repositoryId, Archive archive);

	/**
	 * Get the actual attachment size from CouchDB _attachments metadata
	 * @param repositoryId Repository ID
	 * @param attachmentId Attachment node ID
	 * @return Actual size in bytes from CouchDB attachment metadata, or null if not available
	 */
	Long getAttachmentActualSize(String repositoryId, String attachmentId);
}
