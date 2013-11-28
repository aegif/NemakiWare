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
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
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
	List<NemakiTypeDefinition> getTypeDefinitions();
	NemakiTypeDefinition getTypeDefinition(String typeId);
	NemakiTypeDefinition createTypeDefinition(NemakiTypeDefinition typeDefinition);
	NemakiTypeDefinition updateTypeDefinition(NemakiTypeDefinition typeDefinition);
	void deleteTypeDefinition(String nodeId);
	List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores();
	NemakiPropertyDefinitionCore getPropertyDefinitionCore(String nodeId);
	NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String propertyId);
	NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String nodeId);
	List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String coreNodeId);
	NemakiPropertyDefinitionCore createPropertyDefinitionCore(NemakiPropertyDefinitionCore propertyDefinitionCore);
	NemakiPropertyDefinitionDetail createPropertyDefinitionDetail(NemakiPropertyDefinitionDetail propertyDefinitionDetail);
	NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(NemakiPropertyDefinitionDetail propertyDefinitionDetail);
	void deletePropertyDefinition(String propertyId);
	
	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	Content getContent(String objectId);
	boolean existContent(String objectTypeId);
	Document getDocument(String objectId);
	List<Document> getCheckedOutDocuments(String parentFolderId);
	VersionSeries getVersionSeries(String nodeId);
	Document getDocumentOfLatestVersion(String versionSeriesId);
	List<Document> getAllVersions(String versionSeriesId);
	Folder getFolder(String objectId);
	Folder getFolderByPath(String path);
	List<Content> getLatestChildrenIndex(String parentId);
	Content getChildByName(String parentId, String name);
	Relationship getRelationship(String objectId);
	List<Relationship> getRelationshipsBySource(String sourceId);
	List<Relationship> getRelationshipsByTarget(String targetId);
	Policy getPolicy(String objectId);
	List<Policy> getAppliedPolicies(String objectId);
	Item getItem(String objectId);
	Document create(Document document);
	VersionSeries create(VersionSeries versionSeries);
	Folder create(Folder folder);
	Relationship create(Relationship relationship);
	Policy create(Policy policy);
	Item create(Item item);
	Document update(Document document);
	VersionSeries update(VersionSeries versionSeries);
	Folder update(Folder folder);
	Relationship update(Relationship relationship);
	Policy update(Policy policy);
	Item update(Item item);
	void delete(String objectId);
	
	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	AttachmentNode getAttachment(String attachmentId, boolean includeStream);
	Rendition getRendition(String objectId);
	String createAttachment(AttachmentNode attachment, ContentStream cs);	
	
	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	Change getChangeEvent(String token);
	Change getLatestChange();
	List<Change> getLatestChanges(int startToken, int maxItems);
	Change create(Change change);
	Change update(Change change);
	
	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	Archive getArchive(String archiveId);
	Archive getArchiveByOriginalId(String originalId);
	Archive getAttachmentArchive(Archive archive);
	List<Archive> getChildArchives(Archive archive);
	List<Archive> getArchivesOfVersionSeries(String versionSeriesId);
	List<Archive> getAllArchives();
	Archive createArchive(Archive archive, Boolean deleteWithParent);
	Archive createAttachmentArchive(Archive archive);
	void deleteArchive(String archiveId);
	void restoreContent(Archive archive);
	void restoreAttachment(Archive archive);
}
