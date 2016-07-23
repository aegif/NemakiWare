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

import jp.aegif.nemaki.util.spring.aspect.log.LogParam;

public interface ObjectService {

	/**
	 * Creates a new document, folder or policy. The property
	 * "cmis:objectTypeId" defines the type and implicitly the base type.
	 * 
	 * @param repositoryId
	 *            TODO
	 * 
	 * @return object representing created document.
	 */
	public abstract ObjectData create(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("properties") Properties properties, @LogParam("folderId") String folderId,
			@LogParam("contentStream") ContentStream contentStream, @LogParam("versioningState") VersioningState versioningState,
			@LogParam("policies") List<String> policies, @LogParam("extension") ExtensionsData extension);

	/**
	 * Sets the content stream for the specified document object.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param changeToken
	 *            TODO
	 */
	public abstract void setContentStream(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") Holder<String> objectId, @LogParam("overwriteFlag") boolean overwriteFlag,
			@LogParam("contentStream") ContentStream contentStream, @LogParam("changeToken") Holder<String> changeToken);

	public void deleteContentStream(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") Holder<String> objectId, @LogParam("changeToken") Holder<String> changeToken,
			@LogParam("extension") ExtensionsData extension);

	public void appendContentStream(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") Holder<String> objectId, @LogParam("changeToken") Holder<String> changeToken,
			@LogParam("contentStream") ContentStream contentStream, @LogParam("isLastChunk") boolean isLastChunk,
			@LogParam("extension") ExtensionsData extension);

	/**
	 * Deletes the specified folder object and all of its child- and
	 * descendant-objects.
	 * 
	 * TODO Not Yet Implemented
	 * 
	 * @param repositoryId
	 *            TODO
	 */
	public abstract FailedToDeleteData deleteTree(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("folderId") String folderId, @LogParam("allVersions") Boolean allVersions,
			@LogParam("unfileObjects") UnfileObject unfileObjects, @LogParam("continueOnFailure") Boolean continueOnFailure,
			@LogParam("extensione") ExtensionsData extension);

	/**
	 * Deletes object. Attachments of the object get deleted too.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param objectId
	 *            id of the object to be deleted.
	 */
	public abstract void deleteObject(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") String objectId, @LogParam("allVersions") Boolean allVersions);

	/**
	 * Moves the specified file-able object from one folder to another.
	 * 
	 * TODO Not Yet Implemented
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param sourceFolderId
	 *            TODO
	 */
	public abstract void moveObject(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") Holder<String> objectId, @LogParam("sourceFolderId") String sourceFolderId,
			@LogParam("targetFolderId") String targetFolderId);

	/**
	 * Gets the list of associated renditions for the specified object. Only
	 * rendition attributes are returned, not rendition stream. No renditions,
	 * so empty.
	 * 
	 * TODO Not Yet Implemented
	 * 
	 * @param repositoryId
	 *            TODO
	 */
	public abstract List<RenditionData> getRenditions(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("objectId") String objectId, @LogParam("renditionFilter") String renditionFilter,
			@LogParam("maxItems") BigInteger maxItems, @LogParam("skipCount") BigInteger skipCount,
			@LogParam("extension") ExtensionsData extension);

	/**
	 * Updates properties of the object. Doing so also updates the
	 * "last modified" date. Custom properties(Aspect) is passed as
	 * CmisExtensionElement
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param changeToken
	 *            TODO
	 * @return TODO
	 */
	public abstract void updateProperties(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") Holder<String> objectId, @LogParam("properties") Properties properties,
			@LogParam("changeToken") Holder<String> changeToken);

	public abstract List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId,
			@LogParam("objectIdAndChangeToken") List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken,
			@LogParam("properties") Properties properties, @LogParam("addSecondaryTypeIds") List<String> addSecondaryTypeIds,
			@LogParam("removeSecondaryTypeIds") List<String> removeSecondaryTypeIds, @LogParam("extension") ExtensionsData extension);

	/**
	 * Gets the content stream for the specified document object, or gets a
	 * rendition stream for a specified rendition of a document or folder
	 * object.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param offset
	 *            TODO
	 * @param length
	 *            TODO
	 */
	public abstract ContentStream getContentStream(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("objectId") String objectId, @LogParam("streamId") String streamId,
			@LogParam("offset") BigInteger offset, @LogParam("length") BigInteger length);

	/**
	 * Gets the specified information for the object specified by path.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param includeRelationships
	 *            TODO
	 * @param renditionFilter
	 *            TODO
	 * @param includePolicyIds
	 *            TODO
	 * @param extension
	 *            TODO
	 */
	public abstract ObjectData getObjectByPath(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("path") String path, @LogParam("filter") String filter, @LogParam("includeAllowableActions") Boolean includeAllowableActions,
			@LogParam("includeRelationships") IncludeRelationships includeRelationships, @LogParam("renditionFilter") String renditionFilter,
			@LogParam("includePolicyIds")Boolean includePolicyIds, @LogParam("includeAcl")Boolean includeAcl, @LogParam("extension") ExtensionsData extension);

	/**
	 * Gets the specified information for the object specified by id.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param includeRelationships
	 *            TODO
	 * @param renditionFilter
	 *            TODO
	 * @param includePolicyIds
	 *            TODO
	 * @param extension
	 *            TODO
	 */
	public abstract ObjectData getObject(@LogParam("callContext") CallContext callContext,
			@LogParam("repositoryId") String repositoryId, @LogParam("objectId") String objectId,
			@LogParam("filter") String filter, @LogParam("includeAllowableActions") Boolean includeAllowableActions,
			@LogParam("IncludeRelationships") IncludeRelationships includeRelationships,
			@LogParam("renditionFilter") String renditionFilter, @LogParam("includePolicyIds") Boolean includePolicyIds,
			@LogParam("includeAcl") Boolean includeAcl, @LogParam("extension") ExtensionsData extension);

	/**
	 * Gets the list of allowable actions for an object.
	 * 
	 * TODO Not Yet Implemented
	 * 
	 * @param repositoryId
	 *            TODO
	 */
	public abstract AllowableActions getAllowableActions(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId, @LogParam("objectId")String objectId);

	/**
	 * Creates a folder object of the specified type (given by the
	 * cmis:objectTypeId property) in the specified location.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param policies
	 *            TODO
	 * @param addAces
	 *            TODO
	 * @param removeAces
	 *            TODO
	 * @param extensionData
	 *            TODO
	 */
	public abstract String createFolder(@LogParam("")CallContext callContext, @LogParam("repositoryId")String repositoryId, @LogParam("properties")Properties properties,
			@LogParam("folderId")String folderId, @LogParam("policies")List<String> policies, @LogParam("addAces")Acl addAces, @LogParam("removeAces")Acl removeAces, @LogParam("extension")ExtensionsData extension);

	/**
	 * Creates a document object as a copy of the given source document in the
	 * (optionally) specified location.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param policies
	 *            TODO
	 * @param addAces
	 *            TODO
	 * @param removeAces
	 *            TODO
	 */
	public abstract String createDocumentFromSource(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId, @LogParam("sourceId")String sourceId,
			@LogParam("properties")Properties properties, @LogParam("folderId")String folderId, @LogParam("versioningState")VersioningState versioningState, @LogParam("policies")List<String> policies, @LogParam("addAces")Acl addAces,
			@LogParam("removeAces")Acl removeAces);

	/**
	 * Creates a document object of the specified type (given by the
	 * cmis:objectTypeId property) in the (optionally) specified location.
	 * 
	 * @param repositoryId
	 *            TODO
	 * @param policies
	 *            TODO
	 * @param addAces
	 *            TODO
	 * @param removeAces
	 *            TODO
	 * @return id of created document.
	 */
	public abstract String createDocument(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId, @LogParam("properties")Properties properties,
			@LogParam("folderId")String folderId, @LogParam("contentStream")ContentStream contentStream, @LogParam("versioningState")VersioningState versioningState, @LogParam("policies")List<String> policies,
			@LogParam("addAces")Acl addAces, @LogParam("removeAces")Acl removeAces);

	public abstract String createRelationship(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId, @LogParam("properties")Properties properties,
			@LogParam("policies")List<String> policies, @LogParam("addAces")Acl addAces, @LogParam("removeAces")Acl removeAces, @LogParam("extension")ExtensionsData extension);

	public abstract String createPolicy(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId, @LogParam("properties")Properties properties,
			@LogParam("folderId")String folderId, @LogParam("policies")List<String> policies, @LogParam("policies")Acl addAces, @LogParam("removeAces")Acl removeAces, @LogParam("extension")ExtensionsData extension);

	public abstract String createItem(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId, @LogParam("properties")Properties properties,
			@LogParam("folderId")String folderId, @LogParam("policies")List<String> policies, @LogParam("addAces")Acl addAces, @LogParam("removeAces")Acl removeAces, @LogParam("extension")ExtensionsData extension);

}
