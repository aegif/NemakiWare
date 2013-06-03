/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.service.dao;

import java.util.List;

import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
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
public interface ContentDaoService {

	/**
	 * Get a content by id
	 * Return Document/Folder as Content class
	 */
	Content getContent(String objectId);
	
	List<Document> getCheckedOutDocuments(String parentFolderId);
	
	Folder getFolderByPath(String path);
	
	Document getDocument(String objectId);
	
	VersionSeries getVersionSeries(String nodeId);
	
	Folder getFolder(String objectId);
	
	Relationship getRelationship(String objectId);
	
	List<Relationship> getRelationshipsBySource(String sourceId);
	
	List<Relationship> getRelationshipsByTarget(String targetId);
	
	Policy getPolicy(String objectId);
	
	List<Policy> getAppliedPolicies(String objectId);
	
	
	/**
	 * Create a content
	 */

	Document create(Document document);
	VersionSeries createVersionSeries(VersionSeries versionSeries);
	
	Folder create(Folder folder);
	
	Relationship create(Relationship relationship);
	
	Policy create(Policy policy);
	
	Change create(Change change);
	
	Change getLatestChange();
	
	/**
	 * @param latestChangeToken: "<= 0" means "From the start of the change log" 
	 * @param maxItems: "<= 0" means "Without a limited number to retrieve"s
	 * @return: Return results with descending order
	 */
	List<Change> getLatestChanges(int startToken, int maxItems);
	
	Change updateChange(Change change);
	

	Document updateDocument(Document document);
	
	VersionSeries updateVersionSeries(VersionSeries versionSeries);
	
	Folder updateFolder(Folder folder);
	
	Policy updatePolicy(Policy policy);
	
	Relationship updateRelationship(Relationship relationship);
	
	/**
	 * Delete a content
	 */
	void delete(String objectId);
	
	/**
	 * Get contents in the parent with parentId
	 */
	List<Content> getLatestChildrenIndex(String parentId);
	
	Content getChildByName(String parentId, String name);

	/**
	 * Create attachment
	 */
	String createAttachment(AttachmentNode attachment, ContentStream cs);
	
	/**
	 * Get a NemakiAttachment
	 */
	AttachmentNode getAttachment(String attachmentId);

	
	Change getChangeEvent(String token);
	
	/**
	 * Get documents with the same versionSeriesId
	 */
	List<Document> getAllVersions(String versionSeriesId);
	
	/**
	 * Get the latest version of a document 
	 */
	Document getDocumentOfLatestVersion(String versionSeriesId);
	
	/////////
	//Archive
	/////////
	Archive getArchive(String archiveId);
	Archive getArchiveByOriginalId(String originalId);
	Archive getAttachmentArchive(Archive archive);
	Archive createAttachmentArchive(Archive archive);
	Rendition getRendition(String objectId);
	List<Archive> getChildArchives(Archive archive);
	List<Archive> getArchivesOfVersionSeries(String versionSeriesId);
	void deleteArchive(String archiveId);
	void createArchive(Archive archive, Boolean deleteWithParent);
	List<Archive> getAllArchives();
	void restoreContent(Archive archive);
	void restoreAttachment(Archive archive);
}
