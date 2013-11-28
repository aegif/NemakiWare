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
package jp.aegif.nemaki.service.dao;

import java.util.List;

import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.VersionSeries;

import org.apache.chemistry.opencmis.commons.data.ContentStream;

/**
 * Dao Service implementation for CouchDB.
 * 
 * @author linzhixing
 * 
 */
public interface NonCachedContentDaoService {

	// ///////////////////////////////////////
	// Type & Property definition
	// ///////////////////////////////////////
	/**
	 * Get user-defined type definitions
	 * 
	 * @return if nothing found, return null
	 */
	List<NemakiTypeDefinition> getTypeDefinitions();

	/**
	 * Create a user-defined type definition
	 */
	NemakiTypeDefinition createTypeDefinition(
			NemakiTypeDefinition typeDefinition);

	/**
	 * Update a user-defined type definition
	 * 
	 * @param typeDefinition
	 * @return
	 */
	NemakiTypeDefinition updateTypeDefinition(
			NemakiTypeDefinition typeDefinition);
	
	/**
	 * Delete a user-defined type definition
	 * 
	 * @param typeDefinition
	 * @return
	 */
	void deleteTypeDefinition(String nodeId);
	

	/**
	 * List up user-defined property definitions
	 * @return
	 */
	List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores();
	
	/**
	 * Get the core of user-defined property definition
	 * That is, propertyId, proeprtyType, queryName, cardinality
	 * @param nodeId
	 * @return
	 */
	NemakiPropertyDefinitionCore getPropertyDefinitionCore(String nodeId);
	
	/**
	 * Get the core of user-defined property definition by proeprtyId
	 * That is, propertyId, proeprtyType, queryName, cardinality
	 * @param nodeId
	 * @return
	 */
	NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String propertyId);
	
	/**
	 * Get a user-defined property definition detail
	 * That is, all the other attributes than core
	 * @param nodeId
	 * @return if nothing found, return null
	 */
	NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String nodeId);
	
	/**
	 * Get a user-defined property definition detail by coreNodeId
	 * That is, all the other attributes than core
	 * @param nodeId
	 * @return if nothing found, return null
	 */
	List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String coreNodeId);
	
	/**
	 * Create a user-defined property definition core
	 * @param propertyDefinitionCore
	 * @return
	 */
	NemakiPropertyDefinitionCore createPropertyDefinitionCore(
			NemakiPropertyDefinitionCore propertyDefinitionCore);

	/**
	 * Create a user-defined property definition detail
	 * @param propertyDefinitionDetail
	 * @return
	 */
	NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(
			NemakiPropertyDefinitionDetail propertyDefinitionDetail);
	
	/**
	 * Update a user-defined property definition
	 * 
	 * @param propertyDefinition
	 * @return
	 */
	NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(
			NemakiPropertyDefinitionDetail propertyDefinitionDetail);

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	/**
	 * Get a node
	 * 
	 * @param objectId
	 * @return if nothing found, return null
	 */
	NodeBase getNodeBase(String objectId);

	/**
	 * Get a content Result will be return as Content class
	 * 
	 * @param objectId
	 * @return if nothing found, return null
	 */
	Content getContent(String objectId);

	/**
	 * Check if there are any object of the specified object type
	 * @param objectTypeId
	 * @return
	 */
	boolean existContent(String objectTypeId);
	
	/**
	 * Get a document
	 * 
	 * @param objectId
	 * @return
	 */
	Document getDocument(String objectId);

	/**
	 * Get a list of checked out documents in a folder
	 * 
	 * @param parentFolderId
	 * @return if nothing found, return null
	 */
	List<Document> getCheckedOutDocuments(String parentFolderId);

	/**
	 * Get a version series
	 * 
	 * @param nodeId
	 * @return if nothing found, return null
	 */
	VersionSeries getVersionSeries(String nodeId);

	/**
	 * Get all the version series
	 * 
	 * @param versionSeriesId
	 * @return
	 */
	List<Document> getAllVersions(String versionSeriesId);

	/**
	 * Get the latest version of a document
	 * 
	 * @param versionSeriesId
	 * @return if nothing found, return null
	 */
	Document getDocumentOfLatestVersion(String versionSeriesId);

	/**
	 * Get a folder
	 * 
	 * @param objectId
	 * @return if nothing found, return null
	 */
	Folder getFolder(String objectId);

	/**
	 * Get a folder by path
	 * 
	 * @param path
	 * @return if nothing found, return null
	 */
	Folder getFolderByPath(String path);

	/**
	 * Get all the contents in a folder (as contents, that is, as indices)
	 * Documents are limited to the latest versions
	 * 
	 * @param parentId
	 * @return
	 */
	List<Content> getLatestChildrenIndex(String parentId);

	/**
	 * Get a child content by name
	 * 
	 * @param parentId
	 * @param name
	 * @return if nothing found, return null
	 */
	Content getChildByName(String parentId, String name);

	/**
	 * Get a relationship
	 * 
	 * @param objectId
	 * @return if nothing found, return null
	 */
	Relationship getRelationship(String objectId);

	/**
	 * Get a relationship by Source ID
	 * 
	 * @param sourceId
	 * @return
	 */
	List<Relationship> getRelationshipsBySource(String sourceId);

	/**
	 * Get a relationship by Target ID
	 * 
	 * @param targetId
	 * @return if nothing found, return null
	 */
	List<Relationship> getRelationshipsByTarget(String targetId);

	/**
	 * Get a policy
	 * 
	 * @param objectId
	 * @return if nothing found, return null
	 */
	Policy getPolicy(String objectId);

	/**
	 * Get an policy applied to an object
	 * 
	 * @param objectId
	 * @return if nothing found, return null
	 */
	List<Policy> getAppliedPolicies(String objectId);
	
	/**
	 * Get a item
	 * 
	 * @param objectId
	 * @return if nothing found, return null
	 */
	Item getItem(String objectId);
	
	/**
	 * Create a document
	 * 
	 * @param document
	 * @return the newly created document
	 */
	Document create(Document document);

	/**
	 * Create a version series
	 * 
	 * @param versionSeries
	 * @return the newly created version series
	 */
	VersionSeries create(VersionSeries versionSeries);

	/**
	 * Create a folder
	 * 
	 * @param folder
	 * @return the newly created folder
	 */
	Folder create(Folder folder);

	/**
	 * Create a relationship
	 * 
	 * @param relationship
	 * @return the newly created relationship
	 */
	Relationship create(Relationship relationship);

	/**
	 * Create a policy
	 * 
	 * @param policy
	 * @return the newly created policy
	 */
	Policy create(Policy policy);
	
	/**
	 * Create an item
	 * 
	 * @param policy
	 * @return the newly created item
	 */
	Item create(Item item);

	/**
	 * Update a document
	 * 
	 * @param document
	 * @return the newly updated document
	 */
	Document update(Document document);

	/**
	 * Update a version series
	 * 
	 * @param versionSeries
	 * @return the newly updated version series
	 */
	VersionSeries update(VersionSeries versionSeries);

	/**
	 * Update a folder
	 * 
	 * @param folder
	 * @return the newly updated folder
	 */
	Folder update(Folder folder);

	/**
	 * Update a relationship
	 * 
	 * @param relationship
	 * @return
	 */
	Relationship update(Relationship relationship);

	/**
	 * Update a policy
	 * 
	 * @param policy
	 * @return the newly updated policy
	 */
	Policy update(Policy policy);
	
	/**
	 * Update a item
	 * 
	 * @param policy
	 * @return the newly updated item
	 */
	Item update(Item item);

	/**
	 * Delete a content
	 * 
	 * @param objectId
	 */
	void delete(String objectId);

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	/**
	 * Get an attachment(without InputStream)
	 * 
	 * @param attachmentId
	 * @return if nothing found, return null
	 */
	AttachmentNode getAttachment(String attachmentId);

	/**
	 * Set InputStream
	 * 
	 * @param attachmentNode
	 */
	void setStream(AttachmentNode attachmentNode);

	/**
	 * Get a rendition
	 * 
	 * @param objectId
	 * @return
	 */
	Rendition getRendition(String objectId);

	/**
	 * Create an attachment
	 * 
	 * @param attachment
	 * @param contentStream
	 * @return a created attachment's node id
	 */
	String createAttachment(AttachmentNode attachment,
			ContentStream contentStream);

	/**
	 * Update an attachment
	 * (replace an existing attachment)
	 * 
	 * @param attachment
	 * @param contentStream
	 */
	void updateAttachment(AttachmentNode attachment, ContentStream contentStream);
	
	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	/**
	 * Get a change event
	 * 
	 * @param token
	 * @return if nothing found, return null
	 */
	Change getChangeEvent(String token);

	/**
	 * Get the latest change event in the repository
	 * 
	 * @return if nothing found, return null
	 */
	Change getLatestChange();

	/**
	 * Get latest change events
	 * 
	 * @param latestChangeToken
	 *            "<= 0" means "From the beginning"
	 * @param maxItems
	 *            "<= 0" means "infinite"
	 * @return Return results with descending order by time
	 */
	List<Change> getLatestChanges(int startToken, int maxItems);

	/**
	 * Create a change event
	 * 
	 * @param change
	 * @return a newly created change event
	 */
	Change create(Change change);

	/**
	 * Update a change
	 * 
	 * @param change
	 * @return a newly updated change event
	 */
	Change update(Change change);

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	/**
	 * Get an archive
	 * 
	 * @param archiveId
	 * @return if nothing found, return null
	 */
	Archive getArchive(String archiveId);

	/**
	 * Get an archive by its original content's object ID
	 * 
	 * @param originalId
	 * @return if nothing found, return null
	 */
	Archive getArchiveByOriginalId(String originalId);

	/**
	 * Get an archive of an attachment
	 * 
	 * @param archive
	 * @return if nothing found, return null
	 */
	Archive getAttachmentArchive(Archive archive);

	/**
	 * Get archives of the children of the original folder
	 * 
	 * @param archive
	 * @return if nothing found, return null
	 */
	List<Archive> getChildArchives(Archive archive);

	/**
	 * Get an archive of a version series
	 * 
	 * @param versionSeriesId
	 * @return if nothing found, return null
	 */
	List<Archive> getArchivesOfVersionSeries(String versionSeriesId);

	/**
	 * Get all the archives in the repository
	 * 
	 * @return if nothing found, return null
	 */
	List<Archive> getAllArchives();

	/**
	 * Create an archive of a content
	 * 
	 * @param archive
	 * @param deleteWithParent
	 * @return a newly created archive of a content
	 */
	Archive createArchive(Archive archive, Boolean deleteWithParent);

	/**
	 * Create an archive of an attachment
	 * 
	 * @param archive
	 * @return a newly created archive of an attachment
	 */
	Archive createAttachmentArchive(Archive archive);

	/**
	 * Delete an archive
	 * 
	 * @param archiveId
	 */
	void deleteArchive(String archiveId);

	/**
	 * Restore a content fomr its archive
	 * 
	 * @param archive
	 */
	void restoreContent(Archive archive);

	/**
	 * Restore an attachment from its archive
	 * 
	 * @param archive
	 */
	void restoreAttachment(Archive archive);
}
