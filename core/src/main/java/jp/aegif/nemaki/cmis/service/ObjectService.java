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
package jp.aegif.nemaki.cmis.service;

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public interface ObjectService {

	/**
	 * Creates a new document, folder or policy. The property
	 * "cmis:objectTypeId" defines the type and implicitly the base type.
	 * @param repositoryId TODO
	 * 
	 * @return object representing created document.
	 */
	public abstract ObjectData create(CallContext callContext,
			String repositoryId, Properties properties,
			String folderId, ContentStream contentStream,
			VersioningState versioningState, List<String> policies, ExtensionsData extension);

	/**
	 * Sets the content stream for the specified document object.
	 * @param repositoryId TODO
	 * @param changeToken TODO
	 */
	public abstract void setContentStream(CallContext callContext,
			String repositoryId, Holder<String> objectId,
			boolean overwriteFlag, ContentStream contentStream, Holder<String> changeToken);
	
	public void deleteContentStream(CallContext callContext,
			String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ExtensionsData extension);

	public void appendContentStream(CallContext callContext, String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ContentStream contentStream,
			boolean isLastChunk, ExtensionsData extension);
	
	/**
	 * Deletes the specified folder object and all of its child- and
	 * descendant-objects.
	 * 
	 * TODO Not Yet Implemented
	 * @param repositoryId TODO
	 */
	public abstract FailedToDeleteData deleteTree(CallContext callContext,
			String repositoryId,
			String folderId, Boolean allVersions,
			UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extensione);

	/**
	 * Deletes object. Attachments of the object get deleted too.
	 * @param repositoryId TODO
	 * @param objectId
	 *            id of the object to be deleted.
	 */
	public abstract void deleteObject(CallContext callContext, String repositoryId,
			String objectId, Boolean allVersions);

	/**
	 * Moves the specified file-able object from one folder to another.
	 * 
	 * TODO Not Yet Implemented
	 * @param repositoryId TODO
	 * @param sourceFolderId TODO
	 */
	public abstract void moveObject(CallContext callContext,
			String repositoryId, Holder<String> objectId,
			String sourceFolderId, String targetFolderId);

	/**
	 * Gets the list of associated renditions for the specified object. Only
	 * rendition attributes are returned, not rendition stream. No renditions,
	 * so empty.
	 * 
	 * TODO Not Yet Implemented
	 * @param repositoryId TODO
	 */
	public abstract List<RenditionData> getRenditions(CallContext callContext,
			String repositoryId, String objectId, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);

	/**
	 * Updates properties of the object. Doing so also updates the
	 * "last modified" date. Custom properties(Aspect) is passed as
	 * CmisExtensionElement
	 * @param repositoryId TODO
	 * @param changeToken TODO
	 * @return TODO
	 */
	public abstract void updateProperties(CallContext callContext,
			String repositoryId, Holder<String> objectId, Properties properties, Holder<String> changeToken);

	public abstract List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(CallContext callContext,
			String repositoryId,
			List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken, Properties properties,
			List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension);
		
	
	/**
	 * Gets the content stream for the specified document object, or gets a
	 * rendition stream for a specified rendition of a document or folder
	 * object.
	 * @param repositoryId TODO
	 * @param offset TODO
	 * @param length TODO
	 */
	public abstract ContentStream getContentStream(CallContext callContext,
			String repositoryId, String objectId, String streamId, BigInteger offset, BigInteger length);

	/**
	 * Gets the specified information for the object specified by path.
	 * @param repositoryId TODO
	 * @param includeRelationships TODO
	 * @param renditionFilter TODO
	 * @param includePolicyIds TODO
	 * @param extension TODO
	 */
	public abstract ObjectData getObjectByPath(CallContext callContext,
			String repositoryId, String path, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension);

	/**
	 * Gets the specified information for the object specified by id.
	 * @param repositoryId TODO
	 * @param includeRelationships TODO
	 * @param renditionFilter TODO
	 * @param includePolicyIds TODO
	 * @param extension TODO
	 */
	public abstract ObjectData getObject(CallContext callContext,
			String repositoryId, String objectId,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension);

	/**
	 * Gets the list of allowable actions for an object.
	 * 
	 * TODO Not Yet Implemented
	 * @param repositoryId TODO
	 */
	public abstract AllowableActions getAllowableActions(
			CallContext callContext, String repositoryId, String objectId);

	/**
	 * Creates a folder object of the specified type (given by the
	 * cmis:objectTypeId property) in the specified location.
	 * @param repositoryId TODO
	 * @param policies TODO
	 * @param addAces TODO
	 * @param removeAces TODO
	 * @param extensionData TODO
	 */
	public abstract String createFolder(CallContext callContext,
			String repositoryId, Properties properties, String folderId, List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension);

	/**
	 * Creates a document object as a copy of the given source document in the
	 * (optionally) specified location.
	 * @param repositoryId TODO
	 * @param policies TODO
	 * @param addAces TODO
	 * @param removeAces TODO
	 */
	public abstract String createDocumentFromSource(CallContext callContext,
			String repositoryId, String sourceId, Properties properties,
			String folderId, VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces);

	/**
	 * Creates a document object of the specified type (given by the
	 * cmis:objectTypeId property) in the (optionally) specified location.
	 * @param repositoryId TODO
	 * @param policies TODO
	 * @param addAces TODO
	 * @param removeAces TODO
	 * @return id of created document.
	 */
	public abstract String createDocument(CallContext callContext,
			String repositoryId, Properties properties,
			String folderId, ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces);
	
	public abstract String createRelationship (CallContext callContext, String repositoryId, Properties properties,
			List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension);	
	
	public abstract String createPolicy(CallContext callContext, String repositoryId,
			Properties properties, String folderId, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension);
	
	public abstract String createItem(CallContext callContext, String repositoryId,
			Properties properties, String folderId, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension);

}
