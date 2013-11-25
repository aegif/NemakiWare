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
import java.util.List;

import jp.aegif.nemaki.model.Content;

import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public interface ObjectService {

	/**
	 * Creates a new document, folder or policy. The property
	 * "cmis:objectTypeId" defines the type and implicitly the base type.
	 * 
	 * @return object representing created document.
	 */
	public abstract ObjectData create(CallContext callContext,
			Properties properties, String folderId,
			ContentStream contentStream, VersioningState versioningState,
			List<String> policies, ExtensionsData extension);

	/**
	 * Sets the content stream for the specified document object.
	 * @param changeToken TODO
	 */
	public abstract void setContentStream(CallContext callContext,
			Holder<String> objectId, boolean overwriteFlag,
			ContentStream contentStream, Holder<String> changeToken);
	
	public void deleteContentStream(CallContext callContext,
			Holder<String> objectId, Holder<String> changeToken,
			ExtensionsData extension);

	/**
	 * Deletes the specified folder object and all of its child- and
	 * descendant-objects.
	 * 
	 * TODO Not Yet Implemented
	 */
	public abstract FailedToDeleteData deleteTree(CallContext callContext,
			String folderId,
			Boolean allVersions, UnfileObject unfileObjects,
			Boolean continueOnFailure, ExtensionsData extensione);

	/**
	 * Deletes object. Attachments of the object get deleted too.
	 * 
	 * @param objectId
	 *            id of the object to be deleted.
	 */
	public abstract void deleteObject(CallContext callContext, String objectId,
			Boolean allVersions);

	/**
	 * Moves the specified file-able object from one folder to another.
	 * 
	 * TODO Not Yet Implemented
	 * @param sourceFolderId TODO
	 */
	public abstract void moveObject(CallContext callContext,
			Holder<String> objectId, String sourceFolderId,
			String targetFolderId, NemakiCmisService couchCmisService);

	/**
	 * Gets the list of associated renditions for the specified object. Only
	 * rendition attributes are returned, not rendition stream. No renditions,
	 * so empty.
	 * 
	 * TODO Not Yet Implemented
	 */
	public abstract List<RenditionData> getRenditions(CallContext callContext,
			String objectId, String renditionFilter, BigInteger maxItems,
			BigInteger skipCount, ExtensionsData extension);

	/**
	 * Updates properties of the object. Doing so also updates the
	 * "last modified" date. Custom properties(Aspect) is passed as
	 * CmisExtensionElement
	 * @param changeToken TODO
	 * @return TODO
	 */
	public abstract Content updateProperties(CallContext callContext,
			Holder<String> objectId, Properties properties, Holder<String> changeToken);

	public abstract List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(CallContext callContext,
			List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken,
			Properties properties, List<String> addSecondaryTypeIds,
			List<String> removeSecondaryTypeIds, ExtensionsData extension);
		
	
	/**
	 * Gets the content stream for the specified document object, or gets a
	 * rendition stream for a specified rendition of a document or folder
	 * object.
	 * @param offset TODO
	 * @param length TODO
	 */
	public abstract ContentStream getContentStream(CallContext callContext,
			String objectId, String streamId, BigInteger offset, BigInteger length);

	/**
	 * Gets the specified information for the object specified by path.
	 */
	public abstract ObjectData getObjectByPath(CallContext callContext,
			String path, String filter, Boolean includeAllowableActions,
			Boolean includeAcl, ObjectInfoHandler objectInfos);

	/**
	 * Gets the specified information for the object specified by id.
	 */
	public abstract ObjectData getObject(CallContext callContext,
			String objectId, String filter,
			Boolean includeAllowableActions, Boolean includeAcl,
			ObjectInfoHandler objectInfos);

	/**
	 * Gets the list of allowable actions for an object.
	 * 
	 * TODO Not Yet Implemented
	 */
	public abstract AllowableActions getAllowableActions(
			CallContext callContext, String objectId);

	/**
	 * Creates a folder object of the specified type (given by the
	 * cmis:objectTypeId property) in the specified location.
	 * @param policies TODO
	 * @param addAces TODO
	 * @param removeAces TODO
	 * @param extensionData TODO
	 */
	public abstract String createFolder(CallContext callContext,
			Properties properties, String folderId, List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension);

	/**
	 * Creates a document object as a copy of the given source document in the
	 * (optionally) specified location.
	 * 
	 * @param policies TODO
	 * @param addAces TODO
	 * @param removeAces TODO
	 */
	public abstract String createDocumentFromSource(CallContext callContext,
			String sourceId, Properties properties, String folderId,
			VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces);

	/**
	 * Creates a document object of the specified type (given by the
	 * cmis:objectTypeId property) in the (optionally) specified location.
	 * @param policies TODO
	 * @param addAces TODO
	 * @param removeAces TODO
	 * 
	 * @return id of created document.
	 */
	public abstract String createDocument(CallContext callContext,
			Properties properties, String folderId,
			ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces);
	
	public abstract String createRelationship (CallContext callContext, Properties properties, List<String> policies,
			Acl addAces, Acl removeAces, ExtensionsData extension);	
	
	public abstract String createPolicy(CallContext callContext, Properties properties,
			List<String> policies, Acl addAces, Acl removeAces,
			ExtensionsData extension);

}
