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
import jp.aegif.nemaki.model.Item;
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

public interface ContentService {

	// ///////////////////////////////////////
	// Content
	// ///////////////////////////////////////
	boolean existContent(String objectTypeId);

	/**
	 * Get a content(without type-specified)
	 *
	 * @param objectId
	 * @return
	 */
	Content getContent(String objectId);

	/**
	 * Get a fileable content by path
	 *
	 * @param path
	 * @return
	 */
	Content getContentByPath(String path);

	/**
	 * Get the parent folder
	 *
	 * @param objectId
	 * @return
	 */
	Folder getParent(String objectId);

	/**
	 * Get children under a folder
	 *
	 * @param objectId
	 * @return
	 */
	List<Content> getChildren(String folderId);

	/**
	 * Get descendant contents in a folder
	 *
	 * @param objectId
	 * @param depth
	 *            -1 means infinity.
	 * @return descendants are returned in a flatten list.
	 */
	List<Content> getDescendants(String folderId, int depth);

	/**
	 * Get a document
	 *
	 * @param objectId
	 * @return
	 */
	Document getDocument(String objectId);

	/**
	 * Get the latest version document of a given versionSeries
	 *
	 * @param versionSeriesId
	 * @return
	 */
	Document getDocumentOfLatestVersion(String versionSeriesId);

	/**
	 * Get the latest version major document of a given versionSeries
	 *
	 * @param versionSeriesId
	 * @return
	 */
	Document getDocumentOfLatestMajorVersion(String versionSeriesId);

	/**
	 * Get all versions of a document
	 *
	 * @param versionSeriesId
	 * @return
	 */
	List<Document> getAllVersions(String versionSeriesId);

	/**
	 * Get checkout documents in a folder
	 *
	 * @param folderId
	 * @param orderBy
	 * @param extension
	 * @return
	 */
	List<Document> getCheckedOutDocs(String folderId, String orderBy,
			ExtensionsData extension);

	/**
	 * Get a version series
	 *
	 * @param versionSeriesId
	 * @return
	 */
	VersionSeries getVersionSeries(String versionSeriesId);

	/**
	 * Get a folder
	 *
	 * @param objectId
	 * @return
	 */
	Folder getFolder(String objectId);

	/**
	 * Get a path string
	 *
	 * @param content
	 * @return
	 */
	String calculatePath(Content content);



	/**
	 * Get a relationship
	 *
	 * @param objectId
	 * @return
	 */
	Relationship getRelationship(String objectId);

	/**
	 * Search relationships by the edge node
	 *
	 * @param objectId
	 * @param relationshipDirection
	 * @return
	 */
	List<Relationship> getRelationsipsOfObject(String objectId,
			RelationshipDirection relationshipDirection);

	/**
	 * Get a policy
	 *
	 * @param objectId
	 * @return
	 */
	Policy getPolicy(String objectId);

	/**
	 * Get an item
	 * @param objectId
	 * @return
	 */
	Item getItem(String objectId);

	/**
	 * Create a document
	 *
	 * @param callContext
	 * @param properties
	 * @param parentFolder
	 * @param contentStream
	 * @param versioningState
	 * @param versionSeriesId
	 * @return
	 */
	Document createDocument(CallContext callContext, Properties properties,
			Folder parentFolder, ContentStream contentStream,
			VersioningState versioningState, String versionSeriesId);

	/**
	 * Copy a document
	 *
	 * @param callContext
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
			Properties properties, Folder target, Document original,
			VersioningState versioningState, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces);

	/**
	 * Copy a document setting new content stream
	 *
	 * @param callContext
	 * @param original
	 * @param contentStream
	 * @return
	 */
	Document createDocumentWithNewStream(CallContext callContext,
			Document original, ContentStream contentStream);

	/**
	 * Check out and create PWC
	 *
	 * @param callContext
	 * @param objectId
	 * @param extension
	 * @return
	 */
	Document checkOut(CallContext callContext, String objectId,
			ExtensionsData extension);

	/**
	 * Cancel checking out
	 *
	 * @param callContext
	 * @param objectId
	 * @param extension
	 */
	void cancelCheckOut(CallContext callContext, String objectId,
			ExtensionsData extension);

	/**
	 * Check in and delete PWC
	 *
	 * @param callContext
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
	Document checkIn(CallContext callContext, Holder<String> objectId,
			Boolean major, Properties properties, ContentStream contentStream,
			String checkinComment, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces,
			ExtensionsData extension);

	/**
	 * Create a folder
	 *
	 * @param callContext
	 * @param properties
	 * @param parentFolder
	 * @return
	 */
	Folder createFolder(CallContext callContext, Properties properties,
			Folder parentFolder);

	/**
	 * Create a relationship
	 *
	 * @param callContext
	 * @param properties
	 * @param policies
	 * @param addAces
	 * @param removeAces
	 * @param extension
	 * @return
	 */
	Relationship createRelationship(CallContext callContext,
			Properties properties, List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces,
			ExtensionsData extension);

	/**
	 * Create a policy
	 *
	 * @param callContext
	 * @param properties
	 * @param policies
	 * @param addAces
	 * @param removeAces
	 * @param extension
	 * @return
	 */
	Policy createPolicy(CallContext callContext, Properties properties,
			List<String> policies,
			org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces,
			ExtensionsData extension);

	/**
	 * Create an item
	 * @param callContext
	 * @param properties
	 * @param folderId
	 * @param policies
	 * @param addAces
	 * @param removeAces
	 * @param extension
	 * @return
	 */
	Item createItem(CallContext callContext, Properties properties,
			String folderId, List<String> policies, org.apache.chemistry.opencmis.commons.data.Acl addAces,
			org.apache.chemistry.opencmis.commons.data.Acl removeAces, ExtensionsData extension);

	/**
	 * Update a content(for general-purpose)
	 *
	 * @param content
	 * @return
	 */
	Content update(Content content);

	/**
	 * Update properties of a content
	 *
	 * @param callContext
	 * @param properties
	 * @param content
	 * @return
	 */
	Content updateProperties(CallContext callContext, Properties properties,
			Content content);

	/**
	 * Move a content
	 *
	 * @param content
	 * @param target
	 */
	void move(Content content, Folder target);

	/**
	 * Apply a policy from a content
	 *
	 * @param callContext
	 * @param policyId
	 * @param objectId
	 * @param extension
	 */
	void applyPolicy(CallContext callContext, String policyId, String objectId,
			ExtensionsData extension);

	/**
	 * Remove a policy from a content
	 *
	 * @param callContext
	 * @param policyId
	 * @param objectId
	 * @param extension
	 */
	void removePolicy(CallContext callContext, String policyId,
			String objectId, ExtensionsData extension);

	List<Policy> getAppliedPolicies(String objectId, ExtensionsData extension);


	/**
	 * Delete a content(for general-purpose)
	 *
	 * @param callContext
	 * @param objectId
	 * @param deletedWithParent
	 */
	void delete(CallContext callContext, String objectId,
			Boolean deletedWithParent);

	/**
	 * Delete a document (and also its versions)
	 *
	 * @param callContext
	 * @param objectId
	 * @param allVersions
	 * @param deleteWithParent
	 */
	void deleteDocument(CallContext callContext, String objectId,
			Boolean allVersions, Boolean deleteWithParent);

	/**
	 * Delete an attachment node
	 *
	 * @param callContext
	 * @param attachmentId
	 */
	void deleteAttachment(CallContext callContext, String attachmentId);

	/**
	 * Delete a whole folder tree
	 *
	 * @param context
	 * @param folderId
	 * @param allVersions
	 * @param continueOnFailure
	 * @param deletedWithParent
	 * @throws Exception
	 */
	void deleteTree(CallContext context, String folderId, Boolean allVersions,
			Boolean continueOnFailure, Boolean deletedWithParent)
			throws Exception;

	// ///////////////////////////////////////
	// Attachment
	// ///////////////////////////////////////
	/**
	 * Get an attachment
	 *
	 * @param attachmentId
	 * @return
	 */
	AttachmentNode getAttachment(String attachmentId);

	/**
	 * Get an attachment without stream
	 *
	 * @param attachmentId
	 * @return
	 */
	AttachmentNode getAttachmentRef(String attachmentId);

	/**
	 *
	 *
	 *
	 * @param callContext
	 * @param objectId
	 * @param changeToken
	 * @param contentStream
	 * @param isLastChunk
	 * @param extension
	 */
	void appendAttachment(CallContext callContext, Holder<String> objectId, Holder<String> changeToken,
			ContentStream contentStream, boolean isLastChunk,
			ExtensionsData extension);

	/**
	 * Get a rendition
	 *
	 * @param streamId
	 * @return
	 */
	Rendition getRendition(String streamId);

	/**
	 * Get renditions of a content
	 *
	 * @param objectId
	 * @return
	 */
	List<Rendition> getRenditions(String objectId);

	// ///////////////////////////////////////
	// Acl
	// ///////////////////////////////////////
	public Acl calculateAcl(Content content);

	// ///////////////////////////////////////
	// Change event
	// ///////////////////////////////////////
	/**
	 * Get a change event
	 *
	 * @param token
	 * @return
	 */
	Change getChangeEvent(String token);

	/**
	 * Get latest change events in the change log
	 *
	 * @param context
	 * @param changeLogToken
	 * @param includeProperties
	 * @param filter
	 * @param includePolicyIds
	 * @param includeAcl
	 * @param maxItems
	 * @param extension
	 * @return
	 */
	List<Change> getLatestChanges(CallContext context,
			Holder<String> changeLogToken, Boolean includeProperties,
			String filter, Boolean includePolicyIds, Boolean includeAcl,
			BigInteger maxItems, ExtensionsData extension);

	/**
	 * Get the latest change token in the repository
	 *
	 * @return
	 */
	String getLatestChangeToken();

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	/**
	 * Get all archives in the repository
	 *
	 * @return
	 */
	List<Archive> getAllArchives();

	/**
	 * Get an archive
	 *
	 * @param archiveId
	 * @return
	 */
	Archive getArchive(String archiveId);

	/**
	 * Get an archive by its original content's id
	 *
	 * @param archiveId
	 * @return
	 */
	Archive getArchiveByOriginalId(String archiveId);

	/**
	 * Create an archive of a content
	 *
	 * @param callContext
	 * @param objectId
	 * @param deletedWithParent
	 * @return
	 */
	Archive createArchive(CallContext callContext, String objectId,
			Boolean deletedWithParent);

	/**
	 * Create an archive of an attachment
	 *
	 * @param callContext
	 * @param attachmentId
	 * @return
	 */
	Archive createAttachmentArchive(CallContext callContext, String attachmentId);

	/**
	 * Restore a content from an archive
	 *
	 * @param archiveId
	 */
	void restoreArchive(String archiveId);
}
