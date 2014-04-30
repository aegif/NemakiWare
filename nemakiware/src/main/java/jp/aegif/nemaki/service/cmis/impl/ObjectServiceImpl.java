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
package jp.aegif.nemaki.service.cmis.impl;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.query.solr.SolrUtil;
import jp.aegif.nemaki.repository.type.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.cmis.ObjectService;
import jp.aegif.nemaki.service.cmis.RepositoryService;
import jp.aegif.nemaki.service.node.ContentService;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.constant.DomainType;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.FolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateObjectIdAndChangeTokenImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FailedToDeleteDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RenditionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections.CollectionUtils;

public class ObjectServiceImpl implements ObjectService {

	private TypeManager typeManager;
	private ContentService contentService;
	private RepositoryService repositoryService;
	private ExceptionService exceptionService;
	private CompileObjectService compileObjectService;
	private SolrUtil solrUtil;

	@Override
	public ObjectData getObjectByPath(CallContext callContext, String path,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl,
			ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", path);
		// FIXME path is not preserved in db.
		Content content = contentService.getContentByPath(path);
		// TODO create objectNotFoundByPath method
		exceptionService.objectNotFound(DomainType.OBJECT, content, path);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_PROPERTIES_OBJECT, content);

		// //////////////////
		// Body of the method
		// //////////////////
		return compileObjectService.compileObjectData(callContext, content,
				filter, includeAllowableActions, includeRelationships,
				renditionFilter, includeAcl, null);
	}

	@Override
	public ObjectData getObject(CallContext callContext, String objectId,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl,
			ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContent(objectId);
		// WORK AROUND: getObject(versionSeriesId) is interpreted as
		// getDocumentOflatestVersion
		if (content == null) {
			VersionSeries versionSeries = contentService
					.getVersionSeries(objectId);
			if (versionSeries != null) {
				content = contentService.getDocumentOfLatestVersion(objectId);
			}
		}
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_PROPERTIES_OBJECT, content);

		// //////////////////
		// Body of the method
		// //////////////////
		ObjectData object = compileObjectService.compileObjectData(callContext,
				content, filter, includeAllowableActions, includeRelationships,
				null, includeAcl, null);

		return object;
	}

	@Override
	public ContentStream getContentStream(CallContext callContext,
			String objectId, String streamId, BigInteger offset,
			BigInteger length) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_PROPERTIES_OBJECT, content);

		// //////////////////
		// Body of the method
		// //////////////////
		if (streamId == null) {
			return getContentStreamInternal(content, offset, length);
		} else {
			return getRenditionStream(content, streamId);
		}
	}

	// TODO Implement HTTP range(offset and length of stream), though it is not
	// obligatory.
	private ContentStream getContentStreamInternal(Content content,
			BigInteger rangeOffset, BigInteger rangeLength) {
		if (!content.isDocument()) {
			exceptionService
					.constraint(content.getId(),
							"getContentStream cannnot be invoked to other than document type.");
		}
		Document document = (Document) content;
		exceptionService.constraintContentStreamDownload(document);
		AttachmentNode attachment = contentService.getAttachment(document
				.getAttachmentNodeId());
		attachment.setRangeOffset(rangeOffset);
		attachment.setRangeLength(rangeLength);

		// Set content stream
		BigInteger length = BigInteger.valueOf(attachment.getLength());
		String name = attachment.getName();
		String mimeType = attachment.getMimeType();
		InputStream is = attachment.getInputStream();
		ContentStream cs = new ContentStreamImpl(name, length, mimeType, is);

		return cs;
	}

	private ContentStream getRenditionStream(Content content, String streamId) {
		if (!content.isDocument() && !content.isFolder()) {
			exceptionService
					.constraint(content.getId(),
							"getRenditionStream cannnot be invoked to other than document or folder type.");
		}
		
		exceptionService.constraintRenditionStreamDownload(content, streamId);
		Rendition rendition = contentService.getRendition(streamId);

		BigInteger length = BigInteger.valueOf(rendition.getLength());
		String mimeType = rendition.getMimetype();
		InputStream is = rendition.getInputStream();

		ContentStream cs = new ContentStreamImpl("", length, mimeType, is);

		return cs;
	}

	@Override
	public List<RenditionData> getRenditions(CallContext callContext,
			String objectId, String renditionFilter, BigInteger maxItems,
			BigInteger skipCount, ExtensionsData extension) {
		List<Rendition> renditions = contentService.getRenditions(objectId);

		List<RenditionData> results = new ArrayList<RenditionData>();
		for (Rendition rnd : renditions) {
			RenditionDataImpl data = new RenditionDataImpl(rnd.getId(),
					rnd.getMimetype(), BigInteger.valueOf(rnd.getLength()),
					rnd.getKind(), rnd.getTitle(), BigInteger.valueOf(rnd
							.getWidth()), BigInteger.valueOf(rnd.getHeight()),
					rnd.getRenditionDocumentId());
			results.add(data);
		}
		return results;
	}

	@Override
	public AllowableActions getAllowableActions(CallContext callContext,
			String objectId) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		// NOTE: The permission key doesn't exist according to CMIS
		// specification.

		// //////////////////
		// Body of the method
		// //////////////////
		return compileObjectService.compileAllowableActions(callContext,
				content);
	}

	@Override
	public ObjectData create(CallContext callContext, Properties properties,
			String folderId, ContentStream contentStream,
			VersioningState versioningState, List<String> policies,
			ExtensionsData extension) {

		String typeId = DataUtil.getObjectTypeId(properties);
		TypeDefinition type = repositoryService.getTypeManager()
				.getTypeDefinition(typeId);
		if (type == null) {
			throw new CmisObjectNotFoundException("Type '" + typeId
					+ "' is unknown!");
		}

		String objectId = null;
		// TODO ACE can be set !
		if (type.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT) {
			objectId = createDocument(callContext, properties, folderId,
					contentStream, versioningState, null, null, null);
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_FOLDER) {
			objectId = createFolder(callContext, properties, folderId,
					policies, null, null, extension);
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP) {
			objectId = createRelationship(callContext, properties, policies,
					null, null, extension);
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_POLICY) {
			objectId = createPolicy(callContext, properties, policies, null,
					null, extension);
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_ITEM) {
			objectId = createItem(callContext, properties, folderId, policies,
					null, null, extension);
		} else {
			throw new CmisObjectNotFoundException(
					"Cannot create object of type '" + typeId + "'!");
		}

		return compileObjectService.compileObjectData(callContext,
				contentService.getContent(objectId), null, false,
				IncludeRelationships.NONE, null, false, null);
	}

	@Override
	public String createFolder(CallContext callContext, Properties properties,
			String folderId, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		FolderTypeDefinition td = (FolderTypeDefinition) typeManager
				.getTypeDefinition(DataUtil.getObjectTypeId(properties));
		Folder parentFolder = contentService.getFolder(folderId);

		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.objectNotFoundParentFolder(folderId, parentFolder);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_CREATE_FOLDER_FOLDER, parentFolder);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(properties,
				BaseTypeId.CMIS_FOLDER);
		exceptionService.constraintAllowedChildObjectTypeId(parentFolder,
				properties);
		exceptionService.constraintPropertyValue(td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService
				.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces,
				properties);
		exceptionService.constraintPermissionDefined(addAces, null);
		exceptionService.constraintPermissionDefined(removeAces, null);
		exceptionService.nameConstraintViolation(properties, parentFolder);

		// //////////////////
		// Body of the method
		// //////////////////
		Folder folder = contentService.createFolder(callContext, properties,
				parentFolder);
		return folder.getId();
	}

	@Override
	public String createDocument(CallContext callContext,
			Properties properties, String folderId,
			ContentStream contentStream, VersioningState versioningState,
			List<String> policies, Acl addAces, Acl removeAces) {
		String objectTypeId = DataUtil.getIdProperty(properties,
				PropertyIds.OBJECT_TYPE_ID);
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(objectTypeId);
		Folder parentFolder = contentService.getFolder(folderId);

		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("properties", properties);
		exceptionService.invalidArgumentRequiredParentFolderId(folderId);
		exceptionService.objectNotFoundParentFolder(folderId, parentFolder);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_CREATE_FOLDER_FOLDER, parentFolder);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(properties,
				BaseTypeId.CMIS_DOCUMENT);
		exceptionService.constraintAllowedChildObjectTypeId(parentFolder,
				properties);
		exceptionService.constraintPropertyValue(td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService.constraintContentStreamRequired(td, contentStream);
		exceptionService.constraintControllableVersionable(td, versioningState,
				null);
		exceptionService
				.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces,
				properties);
		exceptionService.constraintPermissionDefined(addAces, null);
		exceptionService.constraintPermissionDefined(removeAces, null);
		exceptionService.streamNotSupported(td, contentStream);
		exceptionService.nameConstraintViolation(properties, parentFolder);

		// //////////////////
		// Body of the method
		// //////////////////
		Document document = contentService.createDocument(callContext,
				properties, parentFolder, contentStream, versioningState, null);
		return document.getId();
	}

	@Override
	public String createDocumentFromSource(CallContext callContext,
			String sourceId, Properties properties, String folderId,
			VersioningState versioningState, List<String> policies,
			Acl addAces, Acl removeAces) {
		Document original = contentService.getDocument(sourceId);
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(original.getObjectType());

		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("properties", properties);
		exceptionService.invalidArgumentRequiredParentFolderId(folderId);
		Folder parentFolder = contentService.getFolder(folderId);
		exceptionService.objectNotFoundParentFolder(folderId, parentFolder);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_CREATE_FOLDER_FOLDER, parentFolder);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(properties,
				BaseTypeId.CMIS_DOCUMENT);
		exceptionService.constraintAllowedChildObjectTypeId(parentFolder,
				properties);
		exceptionService.constraintPropertyValue(td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService.constraintControllableVersionable(td, versioningState,
				null);
		exceptionService
				.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces,
				properties);
		exceptionService.constraintPermissionDefined(addAces, null);
		exceptionService.constraintPermissionDefined(removeAces, null);
		exceptionService.nameConstraintViolation(properties, parentFolder);

		// //////////////////
		// Body of the method
		// //////////////////
		Document document = contentService.createDocumentFromSource(
				callContext, properties, parentFolder, original,
				versioningState, policies, addAces, removeAces);
		return document.getId();
	}

	@Override
	public void setContentStream(CallContext callContext,
			Holder<String> objectId, boolean overwriteFlag,
			ContentStream contentStream, Holder<String> changeToken) {
		// //////////////////
		// General Exception
		// //////////////////
		String id = objectId.getValue();

		exceptionService.invalidArgumentRequiredString("objectId", id);
		exceptionService
				.invalidArgumentRequired("contentStream", contentStream);
		Document doc = (Document) contentService.getContent(id);
		exceptionService.objectNotFound(DomainType.OBJECT, doc, id);
		Properties properties = compileObjectService.compileProperties(callContext,
				doc, compileObjectService.splitFilter(""), new ObjectInfoImpl());
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_SET_CONTENT_DOCUMENT, doc);
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(DataUtil.getObjectTypeId(properties));
		exceptionService.constraintImmutable(doc, td);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.contentAlreadyExists(doc, overwriteFlag);
		exceptionService.streamNotSupported(td, contentStream);
		exceptionService.updateConflict(doc, changeToken);
		exceptionService.versioning(doc);
		Folder parent = contentService.getParent(id);
		exceptionService.objectNotFoundParentFolder(id, parent);

		// //////////////////
		// Body of the method
		// //////////////////
		// TODO Externalize versioningState
		contentService.createDocumentWithNewStream(callContext, doc,
				contentStream);
	}

	@Override
	public void deleteContentStream(CallContext callContext,
			Holder<String> objectId, Holder<String> changeToken,
			ExtensionsData extension) {
		// //////////////////
		// Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredHolderString("objectId",
				objectId);
		Document document = contentService.getDocument(objectId.getValue());
		exceptionService.objectNotFound(DomainType.OBJECT, document,
				document.getId());
		exceptionService.constraintContentStreamRequired(document);
		
		// //////////////////
		// Body of the method
		// //////////////////
		contentService.deleteContentStream(callContext, objectId);
	}

	@Override
	public void appendContentStream(CallContext callContext,
			Holder<String> objectId, Holder<String> changeToken,
			ContentStream contentStream, boolean isLastChunk,
			ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		String id = objectId.getValue();

		exceptionService.invalidArgumentRequiredString("objectId", id);
		exceptionService
				.invalidArgumentRequired("contentStream", contentStream);
		Document doc = (Document) contentService.getContent(id);
		exceptionService.objectNotFound(DomainType.OBJECT, doc, id);
		Properties properties = compileObjectService.compileProperties(callContext,
				doc, compileObjectService.splitFilter(""), new ObjectInfoImpl());
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_SET_CONTENT_DOCUMENT, doc);
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(DataUtil.getObjectTypeId(properties));
		exceptionService.constraintImmutable(doc, td);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.streamNotSupported(td, contentStream);
		exceptionService.updateConflict(doc, changeToken);
		exceptionService.versioning(doc);

		// //////////////////
		// Body of the method
		// //////////////////
		contentService.appendAttachment(callContext, objectId, changeToken,
				contentStream, isLastChunk, extension);
	}

	@Override
	public String createRelationship(CallContext callContext,
			Properties properties, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		String objectTypeId = DataUtil.getIdProperty(properties,
				PropertyIds.OBJECT_TYPE_ID);
		RelationshipTypeDefinition td = (RelationshipTypeDefinition) typeManager
				.getTypeDefinition(objectTypeId);
		// //////////////////
		// Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredCollection("properties",
				properties.getPropertyList());
		String sourceId = DataUtil.getStringProperty(properties,
				PropertyIds.SOURCE_ID);
		if (sourceId != null) {
			Content source = contentService.getContent(DataUtil
					.getStringProperty(properties, PropertyIds.SOURCE_ID));
			if (source == null)
				exceptionService.constraintAllowedSourceTypes(td, source);
			exceptionService.permissionDenied(callContext,
					PermissionMapping.CAN_CREATE_RELATIONSHIP_SOURCE, source);
		}
		String targetId = DataUtil.getStringProperty(properties,
				PropertyIds.TARGET_ID);
		if (targetId != null) {
			Content target = contentService.getContent(DataUtil
					.getStringProperty(properties, PropertyIds.TARGET_ID));
			if (target == null)
				exceptionService.constraintAllowedTargetTypes(td, target);
			exceptionService.permissionDenied(callContext,
					PermissionMapping.CAN_CREATE_RELATIONSHIP_TARGET, target);
		}

		exceptionService.constraintBaseTypeId(properties,
				BaseTypeId.CMIS_RELATIONSHIP);
		exceptionService.constraintPropertyValue(td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService
				.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces,
				properties);
		exceptionService.constraintPermissionDefined(addAces, null);
		exceptionService.constraintPermissionDefined(removeAces, null);
		exceptionService.nameConstraintViolation(properties, null);

		// //////////////////
		// Body of the method
		// //////////////////
		Relationship relationship = contentService.createRelationship(
				callContext, properties, policies, addAces, removeAces,
				extension);
		return relationship.getId();
	}

	@Override
	public String createPolicy(CallContext callContext, Properties properties,
			List<String> policies, Acl addAces, Acl removeAces,
			ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredCollection("properties",
				properties.getPropertyList());
		// NOTE: folderId is ignored because policy is not filable in Nemaki
		TypeDefinition td = typeManager.getTypeDefinition(DataUtil
				.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID));
		exceptionService.constraintPropertyValue(td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(properties,
				BaseTypeId.CMIS_POLICY);
		// exceptionService.constraintAllowedChildObjectTypeId(parent,
		// properties);
		exceptionService
				.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces,
				properties);
		// exceptionService.nameConstraintViolation(properties, parent);

		// //////////////////
		// Body of the method
		// //////////////////
		Policy policy = contentService.createPolicy(callContext, properties,
				policies, addAces, removeAces, extension);
		return policy.getId();
	}

	@Override
	public String createItem(CallContext callContext, Properties properties,
			String folderId, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		TypeDefinition td = typeManager.getTypeDefinition(DataUtil
				.getObjectTypeId(properties));
		Folder parentFolder = contentService.getFolder(folderId);
		exceptionService.objectNotFoundParentFolder(folderId, parentFolder);
		exceptionService.invalidArgumentRequiredCollection("properties",
				properties.getPropertyList());

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(properties, BaseTypeId.CMIS_ITEM);
		exceptionService.constraintPropertyValue(td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService
				.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces,
				properties);

		// //////////////////
		// Body of the method
		// //////////////////
		Item item = contentService.createItem(callContext, properties,
				folderId, policies, addAces, removeAces, extension);
		return item.getId();
	}

	@Override
	public Content updateProperties(CallContext callContext,
			Holder<String> objectId, Properties properties,
			Holder<String> changeToken) {

		// //////////////////
		// Exception
		// //////////////////
		Content content = checkExceptionBeforeUpdateProperties(callContext,
				objectId, properties, changeToken);

		// //////////////////
		// Body of the method
		// //////////////////
		return contentService
				.updateProperties(callContext, properties, content);
	}

	private Content checkExceptionBeforeUpdateProperties(
			CallContext callContext, Holder<String> objectId,
			Properties properties, Holder<String> changeToken) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredHolderString("objectId",
				objectId);
		exceptionService.invalidArgumentRequiredCollection("properties",
				properties.getPropertyList());
		Content content = contentService.getContent(objectId.getValue());
		exceptionService.objectNotFound(DomainType.OBJECT, content,
				objectId.getValue());
		if (content.isDocument()) {
			Document d = (Document) content;
			exceptionService.constraintUpdateWhenCheckedOut(callContext.getUsername(), d);
			TypeDefinition typeDef = typeManager.getTypeDefinition(d);
			exceptionService.constraintImmutable(d, typeDef);
		}
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT, content);
		exceptionService.updateConflict(content, changeToken);
		

		TypeDefinition tdf = typeManager.getTypeDefinition(content);
		exceptionService.constraintPropertyValue(tdf, properties,
				objectId.getValue());

		return content;
	}

	@Override
	public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(
			CallContext callContext,
			List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken,
			Properties properties, List<String> addSecondaryTypeIds,
			List<String> removeSecondaryTypeIds, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		// Each permission is checked at each execution
		exceptionService.invalidArgumentRequiredCollection(
				"objectIdAndChangeToken", objectIdAndChangeToken);
		exceptionService.invalidArgumentSecondaryTypeIds(properties);

		// //////////////////
		// Body of the method
		// //////////////////
		List<BulkUpdateObjectIdAndChangeToken> results = new ArrayList<BulkUpdateObjectIdAndChangeToken>();

		for (BulkUpdateObjectIdAndChangeToken idAndToken : objectIdAndChangeToken) {
			try {
				Content content = checkExceptionBeforeUpdateProperties(
						callContext, new Holder<String>(idAndToken.getId()),
						properties,
						new Holder<String>(idAndToken.getChangeToken()));
				contentService.updateProperties(callContext, properties,
						content);

				BulkUpdateObjectIdAndChangeToken result = new BulkUpdateObjectIdAndChangeTokenImpl(
						idAndToken.getId(), content.getId(),
						String.valueOf(content.getChangeToken()));
				results.add(result);
			} catch (Exception e) {
				// Don't throw an error
				// Don't return any BulkUpdateObjectIdAndChangetoken
			}
		}

		return results;
	}

	@Override
	public void moveObject(CallContext callContext, Holder<String> objectId,
			String sourceFolderId, String targetFolderId) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredHolderString("objectId",
				objectId);
		exceptionService.invalidArgumentRequiredString("sourceFolderId",
				sourceFolderId);
		exceptionService.invalidArgumentRequiredString("targetFolderId",
				targetFolderId);
		Content content = contentService.getContent(objectId.getValue());
		exceptionService.objectNotFound(DomainType.OBJECT, content,
				objectId.getValue());
		Folder source = contentService.getFolder(sourceFolderId);
		exceptionService.objectNotFound(DomainType.OBJECT, source,
				sourceFolderId);
		Folder target = contentService.getFolder(targetFolderId);
		exceptionService.objectNotFound(DomainType.OBJECT, target,
				targetFolderId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_MOVE_OBJECT, content);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_MOVE_SOURCE, source);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_MOVE_TARGET, target);

		// //////////////////
		// Body of the method
		// //////////////////
		contentService.move(content, target);
	}

	@Override
	public void deleteObject(CallContext callContext, String objectId,
			Boolean allVersions) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		Content content = contentService.getContent(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_DELETE_OBJECT, content);

		// //////////////////
		// Body of the method
		// //////////////////
		if (content.isDocument()) {
			contentService.deleteDocument(callContext, content.getId(),
					allVersions, false);
		} else if (content.isFolder()) {
			List<Content> children = contentService.getChildren(objectId);
			if (!CollectionUtils.isEmpty(children)) {
				exceptionService
						.constraint(objectId,
								"deleteObject method is invoked on a folder containing objects.");
			}
			contentService.delete(callContext, objectId, false);
		} else {
			contentService.delete(callContext, objectId, false);
		}
	}

	@Override
	public FailedToDeleteData deleteTree(CallContext callContext,
			String folderId, Boolean allVersions, UnfileObject unfileObjects,
			Boolean continueOnFailure, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", folderId);
		Folder folder = contentService.getFolder(folderId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_DELETE_TREE_FOLDER, folder);

		// //////////////////
		// Specific Exception
		// //////////////////
		if (folder == null)
			exceptionService.constraint(folderId,
					"deleteTree cannot be invoked on a non-folder object");

		// //////////////////
		// Body of the method
		// //////////////////
		// Delete descendants
		List<String> failureIds = new ArrayList<String>();
	
		failureIds = contentService.deleteTree(callContext, folderId, allVersions,
				continueOnFailure, false);
		solrUtil.callSolrIndexing();

		// Check FailedToDeleteData
		// FIXME Consider orphans that was failed to be deleted
		FailedToDeleteDataImpl fdd = new FailedToDeleteDataImpl();
		
		if (CollectionUtils.isNotEmpty(failureIds)) {
			fdd.setIds(failureIds);
		} else {
			fdd.setIds(new ArrayList<String>());
		}
		return fdd;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setRepositoryService(RepositoryService repositoryService) {
		this.repositoryService = repositoryService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setCompileObjectService(
			CompileObjectService compileObjectService) {
		this.compileObjectService = compileObjectService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}
}
