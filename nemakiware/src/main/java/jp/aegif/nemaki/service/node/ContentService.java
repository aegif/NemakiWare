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
package jp.aegif.nemaki.service.node;

import java.math.BigInteger;
import java.util.List;

import jp.aegif.nemaki.model.Acl;
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
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Node Service interface.
 */
public interface ContentService {

	/**
	 * Get one piece of CMIS content.
	 * 
	 * @clazz Type of CMIS content expected, must inherit from Content.
	 */

	Content getContentAsTheBaseType(String objectId);

	/**
	 * Get the pieces of content available at that path.
	 */
	Content getContentByPath(String path);

	/**
	 * Get child contents in a given folder
	 * 
	 * @param objectId
	 * @return
	 */
	List<Content> getChildren(String folderId);

	Folder getParent(String objectId);
	
	/**
	 * Get descendant contents in a given folder
	 * 
	 * @param objectId
	 * @param depth
	 * @return
	 */
	List<Content> getDescendants(String folderId, int depth);

	Document getDocument(String objectId);
	
	List<Document> getCheckedOutDocs(String folderId, String orderBy,ExtensionsData extension);
	
	Folder getFolder(String objectId);
	
	String getPath(Content content);
	
	/**
	 * 
	 * @param versionSeriesId
	 * @return
	 */
	Document getDocumentOfLatestVersion(String versionSeriesId);

	List<Relationship> getRelationsipsOfObject(String objectId, RelationshipDirection relationshipDirection);
	Relationship getRelationship(String objectId);
	Policy getPolicy(String objectId);
	void applyPolicy(CallContext callContext, String policyId,
			String objectId, ExtensionsData extension);
	void removePolicy(CallContext callContext, String policyId,
			String objectId, ExtensionsData extension);
	List<Policy> getAppliedPolicies(String objectId, ExtensionsData extension);
	
	
	
	/**
	 * 
	 * @param versionSeriesId
	 * @return
	 */
	List<Document> getAllVersions(String versionSeriesId);

	/**
	 * Create one content
	 * 
	 * @return TODO
	 */
	Document createDocument(CallContext callContext,Properties properties, Folder parentFolder,
			ContentStream contentStream, VersioningState versioningState, String versionSeriesId);
	
	Document createDocumentFromSource(CallContext callContext,
			Properties properties, String folderId,
			Document original, VersioningState versioningState, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces, org.apache.chemistry.opencmis.commons.data.Acl removeAces);
	
	Document createDocumentWithNewStream(CallContext callContext, Document original, ContentStream contentStream);

	Document checkOut(CallContext callContext, String objectId, ExtensionsData extension);
	
	void cancelCheckOut(CallContext callContext, String objectId,ExtensionsData extension);
	
	Document checkIn(CallContext callContext, Holder<String> objectId,
			Boolean major, Properties properties, ContentStream contentStream,
			String checkinComment, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension); 
	
	Folder createFolder(CallContext callContext, Properties properties, Folder parentFolder);
	
	Relationship createRelationship(CallContext callContext,
			Properties properties, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);
	
	Policy createPolicy(CallContext callContext, Properties properties,
			String folderId, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);

	Archive createArchive(CallContext callContext, String objectId, Boolean deletedWithParent);
	Archive createAttachmentArchive(CallContext callContext, String attachmentId);
	
	
	VersionSeries getVersionSeries(String versionSeriesId);
	
	
	/**
	 * Update one content
	 * 
	 * @return TODO
	 */
	Content updateProperties(CallContext callContext, Properties properties, Content content);
	Content update(Content content);
	
	/**
	 * Move a Content
	 */
	void move(Content content, String targetFolderId);

	/**
	 * Delete Content
	 * @param callContext TODO
	 */
	void delete(CallContext callContext, String objectId, Boolean deletedWithParent);

	void deleteDocument(CallContext callContext, String objectId,
			Boolean allVersions, Boolean deleteWithParent);
	
	void deleteAttachment(CallContext callContext, String attachmentId); 

	void deleteTree(CallContext context, String folderId, Boolean allVersions,
			Boolean continueOnFailure, Boolean deletedWithParent)
			throws Exception;

	
	public Acl getInheritedAcl(Content content);
	public org.apache.chemistry.opencmis.commons.data.Acl convertToCmisAcl(Content content, Boolean onlyBasicPermissions);
	
	// //////////////////////////////////////////////////////////////////////////////
	// Attachment
	// //////////////////////////////////////////////////////////////////////////////
	/**
	 * Get an attachment.
	 */
	AttachmentNode getAttachment(String attachmentId);
	
	/**
	 *Get an attachment Ref (without Stream) 
	 */
	AttachmentNode getAttachmentRef(String attachmentId); 

	/**
	 * Create a new attachment.
	 * @param attachment TODO
	 */
	String createAttachment(CallContext callContext, ContentStream contentStream);
	
	Rendition getRendition(String streamId);
	List<Rendition> getRenditions(String objectId);
	
	// //////////////////////////////////////////////////////////////////////////////
	// Change
	// //////////////////////////////////////////////////////////////////////////////
	/**
	 * 
	 * @param  
	 * @param
	 * @return
	 */
	List<Change> getLatestChanges(CallContext context,
			Holder<String> changeLogToken, Boolean includeProperties,
			String filter, Boolean includePolicyIds, Boolean includeAcl,
			BigInteger maxItems, ExtensionsData extension);
	
	
	/**
	 * Get a latest ChangeToken
	 */
	String getLatestChangeToken();
	
	Change getChangeEvent(String token);

	// //////////////////////////////////////////////////////////////////////////////
	// Archive
	// //////////////////////////////////////////////////////////////////////////////
	/**
	 * 
	 * @return
	 */
	List<Archive> getAllArchives();

	Archive getArchive(String archiveId);
	
	Archive getArchiveByOriginalId(String archiveId);
	
	/**
	 * 
	 * @param archiveId
	 */
	void restoreArchive(String archiveId);
}
