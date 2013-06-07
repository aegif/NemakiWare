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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.constant.DomainType;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.cmis.NemakiCmisService;
import jp.aegif.nemaki.service.cmis.ObjectService;
import jp.aegif.nemaki.service.cmis.RepositoryService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.FolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FailedToDeleteDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RenditionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class ObjectServiceImpl implements ObjectService {

	private static final Log log = LogFactory.getLog(ObjectServiceImpl.class);

	private TypeManager typeManager;
	private ContentService contentService;
	private RepositoryService repositoryService;
	private ExceptionService exceptionService;
	private CompileObjectService compileObjectService;

	public ObjectData getObjectByPath(CallContext callContext, String path,
			String filter, Boolean includeAllowableActions, Boolean includeAcl,
			ObjectInfoHandler objectInfos) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", path);
		//FIXME path is not preserved in db.
		Content content = contentService.getContentByPath(path);
		// TODO create objectNotFoundByPath method
		exceptionService.objectNotFound(DomainType.OBJECT, content, path);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_PROPERTIES_OBJECT, content);

		// //////////////////
		// Body of the method
		// //////////////////
		return compileObjectService.compileObjectData(callContext, content,
				filter, includeAllowableActions, includeAcl);
	}

	public ObjectData getObject(CallContext callContext, String objectId,
			String filter, Boolean includeAllowableActions, Boolean includeAcl,
			ObjectInfoHandler objectInfos) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContentAsTheBaseType(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_GET_PROPERTIES_OBJECT, content);

		// //////////////////
		// Body of the method
		// //////////////////
		ObjectData object = compileObjectService.compileObjectData(callContext,
				content, filter, includeAllowableActions, includeAcl);

		return object;
	}

	public ContentStream getContentStream(CallContext callContext,
			String objectId, String streamId) {
		if (streamId == null) {
			return getContentStreamInternal(objectId);
		} else {
			return getRenditionStream(objectId, streamId);
		}
	}

	// TODO Node Service に移す
	private ContentStream getContentStreamInternal(String objectId) {
		Document document = contentService.getDocument(objectId);
		if (document == null) {
			// TODO validation
			return null;
		}
		AttachmentNode attachment = contentService.getAttachment(document
				.getAttachmentNodeId());

		// Set content stream
		BigInteger length = BigInteger.valueOf(attachment.getLength());
		String mimeType = attachment.getMimeType();
		InputStream is = attachment.getInputStream();
		ContentStream cs = new ContentStreamImpl("", length, mimeType, is);
		return cs;
	}

	// TODO Rendition and ContnetStream can be integrated as StreamNode class.
	private ContentStream getRenditionStream(String objectId, String streamId) {
		Content content = contentService.getContentAsTheBaseType(objectId);
		// TODO validation
		if (!content.isDocument() || content.isFolder())
			return null;

		Rendition rendition = contentService.getRendition(streamId);

		BigInteger length = BigInteger.valueOf(rendition.getLength());
		String mimeType = rendition.getMimetype();
		InputStream is = rendition.getInputStream();

		ContentStream cs = new ContentStreamImpl("", length, mimeType, is);

		return cs;
	}

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

	public AllowableActions getAllowableActions(CallContext callContext,
			String objectId) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", objectId);
		Content content = contentService.getContentAsTheBaseType(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		// NOTE: The permission key doesn't exist according to CMIS
		// specification.

		// //////////////////
		// Body of the method
		// //////////////////
		return compileObjectService.compileAllowableActions(callContext,
				content);
	}

	public ObjectData create(CallContext callContext, Properties properties,
			String folderId, ContentStream contentStream,
			VersioningState versioningState, List<String> policies,
			ExtensionsData extension) {

		String typeId = getTypeId(properties);
		TypeDefinition type = repositoryService.getTypeManager()
				.getTypeDefinition(typeId);
		if (type == null) {
			throw new CmisObjectNotFoundException("Type '" + typeId
					+ "' is unknown!");
		}

		String objectId = null;
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
			objectId = createPolicy(callContext, properties, folderId,
					policies, null, null, extension);
		} else {
			throw new CmisObjectNotFoundException(
					"Cannot create object of type '" + typeId + "'!");
		}

		return compileObjectService.compileObjectData(callContext,
				contentService.getContentAsTheBaseType(objectId), null, false, false);
	}

	public String createFolder(CallContext callContext, Properties properties,
			String folderId, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		FolderTypeDefinition td = (FolderTypeDefinition) typeManager
				.getTypeDefinition(getTypeId(properties));
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
		exceptionService.constraintPropertyValue(properties);
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

	public String createDocument(CallContext callContext,
			Properties properties, String folderId,
			ContentStream contentStream, VersioningState versioningState,
			List<String> policies, Acl addAces, Acl removeAces) {
		String objectTypeId = getIdProperty(properties,
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
		exceptionService.constraintPropertyValue(properties);
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
			VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces) {
		Document original = contentService.getDocument(sourceId);
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(original.getObjectType());
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
		exceptionService.constraintPropertyValue(properties);
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
		Document document = contentService.createDocumentFromSource(callContext, properties, folderId, original, versioningState, policies, addAces, removeAces);
		return document.getId();
	}
	
	public void setContentStream(CallContext callContext,
			Holder<String> objectId, boolean overwriteFlag,
			ContentStream contentStream) {
		// //////////////////
		// General Exception
		// //////////////////
		String id = objectId.getValue();
		exceptionService.invalidArgumentRequiredString("objectId", id);
		exceptionService
				.invalidArgumentRequired("contentStream", contentStream);
		Document doc = (Document) contentService.getContentAsTheBaseType(id);
		exceptionService.objectNotFound(DomainType.OBJECT, doc, id);
		Properties properties = compileObjectService.compileProperties(doc,
				compileObjectService.splitFilter(""), new ObjectInfoImpl());
		exceptionService.permissionDenied(callContext,
				PermissionMapping.CAN_SET_CONTENT_DOCUMENT, doc);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.contentAlreadyExists(doc, overwriteFlag);
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(getTypeId(properties));
		exceptionService.streamNotSupported(td, contentStream);
		exceptionService.versioning(doc);

		// //////////////////
		// Body of the method
		// //////////////////
		// TODO Externalize versioningState
		Folder parent = contentService.getParent(id);
		exceptionService.objectNotFoundParentFolder(id, parent);
		contentService.createDocumentWithNewStream(callContext, doc, contentStream);
	}

	@Override
	public void deleteContentStream(CallContext callContext,
			Holder<String> objectId, Holder<String> changeToken,
			ExtensionsData extension) {
		// //////////////////
		// Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);
		Document document = contentService.getDocument(objectId.getValue());
		exceptionService.objectNotFound(DomainType.OBJECT, document, document.getId());
		exceptionService.constraintContentStreamRequired(document);

		//NOTE: Nemaki does't support documents without content stream
	}

	@Override
	public String createRelationship(CallContext callContext,
			Properties properties, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		String objectTypeId = getIdProperty(properties,
				PropertyIds.OBJECT_TYPE_ID);
		RelationshipTypeDefinition td = (RelationshipTypeDefinition) typeManager
				.getTypeDefinition(objectTypeId);
		// //////////////////
		// Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredCollection("properties", properties.getPropertyList());
		String sourceId = getStringProperty(properties, PropertyIds.SOURCE_ID);
		if(sourceId != null){
			Content source = contentService.getContentAsTheBaseType(getStringProperty(properties, PropertyIds.SOURCE_ID));
			if(source == null) exceptionService.constraintAllowedSourceTypes(td, source);
			exceptionService.permissionDenied(callContext, PermissionMapping.CAN_CREATE_RELATIONSHIP_SOURCE, source);
		}
		String targetId = getStringProperty(properties, PropertyIds.TARGET_ID);
		if(targetId != null){
			Content target = contentService.getContentAsTheBaseType(getStringProperty(properties, PropertyIds.TARGET_ID));
			if(target == null) exceptionService.constraintAllowedTargetTypes(td, target);
			exceptionService.permissionDenied(callContext, PermissionMapping.CAN_CREATE_RELATIONSHIP_TARGET, target);
		}
		
		exceptionService.constraintBaseTypeId(properties,
				BaseTypeId.CMIS_RELATIONSHIP);
		exceptionService.constraintPropertyValue(properties);
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
			String folderId, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredCollection("properties", properties.getPropertyList());
		//NOTE: folderId is ignored because policy is not filable in Nemaki
		
		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(properties, BaseTypeId.CMIS_POLICY);
		exceptionService.constraintPropertyValue(properties);
		//exceptionService.constraintAllowedChildObjectTypeId(parent, properties);
		TypeDefinition td = typeManager.getTypeDefinition(getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID));
		exceptionService.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces, properties);
		//exceptionService.nameConstraintViolation(properties, parent);
		
		// //////////////////
		// Body of the method
		// //////////////////
		Policy policy = contentService.createPolicy(callContext, properties, folderId, policies, addAces, removeAces, extension);
		return policy.getId();
	}

	public void updateProperties(CallContext callContext,
			Holder<String> objectId, Properties properties, Holder<String> changeToken) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);
		exceptionService.invalidArgumentRequiredCollection("properties",
				properties.getPropertyList());
		//TODO CHeck constraintPropertyValue with objectId
		Content content = contentService.getContentAsTheBaseType(objectId.getValue());
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId.getValue());
		if(content.isDocument()){
			Document d = (Document)content;
			exceptionService.constraintAlreadyCheckedOut(d);
		}
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT, content);
		exceptionService.updateConflict(content, changeToken);
		
		// //////////////////
		// Body of the method
		// //////////////////
		contentService.updateProperties(callContext, properties, content);
	}

	public void moveObject(CallContext callContext, Holder<String> objectId,
			String sourceFolderId, String targetFolderId, NemakiCmisService couchCmisService) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);
		exceptionService.invalidArgumentRequiredString("sourceFolderId", sourceFolderId);
		exceptionService.invalidArgumentRequiredString("targetFolderId", targetFolderId);
		Content content = contentService.getContentAsTheBaseType(objectId.getValue());
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId.getValue());
		Folder source = contentService.getFolder(sourceFolderId);
		exceptionService.objectNotFound(DomainType.OBJECT, source, sourceFolderId);
		Folder target = contentService.getFolder(targetFolderId);
		exceptionService.objectNotFound(DomainType.OBJECT, target, targetFolderId);
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_MOVE_OBJECT, content);
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_MOVE_SOURCE, source);
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_MOVE_TARGET, target);
		
		// //////////////////
		// Body of the method
		// //////////////////
		contentService.move(content, targetFolderId);
	}

	public void deleteObject(CallContext callContext, String objectId,
			Boolean allVersions) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", objectId);
		Content content = contentService.getContentAsTheBaseType(objectId);
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_DELETE_OBJECT, content);
		
		// //////////////////
		// Body of the method
		// //////////////////
		if (content.isDocument()) {
			contentService.deleteDocument(callContext, content.getId(),
					allVersions, false);
		} else if (content.isFolder()) {
			List<Content> children = contentService.getChildren(objectId);
			if (children == null || children.isEmpty()) {
				exceptionService
						.constraint(objectId,
								"deleteObject method is invoked on a folder containing objects.");
			}
			contentService.delete(callContext, objectId, false);
		} else {
			contentService.delete(callContext, objectId, false);
		}
	}

	public FailedToDeleteData deleteTree(CallContext callContext, String folderId,
			Boolean allVersions, UnfileObject unfileObjects,
			Boolean continueOnFailure, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", folderId);
		Folder folder = contentService.getFolder(folderId);
		exceptionService.permissionDenied(callContext, PermissionMapping.CAN_DELETE_TREE_FOLDER, folder);
		
		// //////////////////
		// Specific Exception
		// //////////////////
		if(folder == null) exceptionService.constraint(folderId, "deleteTree cannot be invoked on a non-folder object");
		
		// //////////////////
		// Body of the method
		// //////////////////
		// Delete descendants
		try {
			contentService.deleteTree(callContext, folderId, allVersions,
					continueOnFailure, false);
		} catch (Exception e) {
			// do nothing
			e.printStackTrace();
		}

		// Check FailedToDeleteData
		// FIXME Consider orphans that was failed to be deleted
		FailedToDeleteDataImpl fdd = new FailedToDeleteDataImpl();
		List<Content> descendants = contentService.getDescendants(folderId, -1);
		if (descendants != null && !descendants.isEmpty()) {
			fdd.setIds(getIds(descendants));
		} else {
			fdd.setIds(new ArrayList<String>());
		}
		return fdd;
	}

	/**
	 * Gets the type id from a set of properties.
	 */
	private String getTypeId(Properties properties) {
		PropertyData<?> typeProperty = properties.getProperties().get(
				PropertyIds.OBJECT_TYPE_ID);
		if (!(typeProperty instanceof PropertyId)) {
			throw new CmisInvalidArgumentException("Type id must be set!");
		}
		String typeId = ((PropertyId) typeProperty).getFirstValue();
		if (typeId == null) {
			throw new CmisInvalidArgumentException("Type id must be set!");
		}
		return typeId;
	}

	/**
	 * Reads a given property from a set of properties.
	 */
	private String getStringProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyString)) {
			return null;
		}

		return ((PropertyString) property).getFirstValue();
	}

	private String getIdProperty(Properties properties, String name) {
		PropertyData<?> property = properties.getProperties().get(name);
		if (!(property instanceof PropertyId)) {
			return null;
		}

		return ((PropertyId) property).getFirstValue();
	}

	private List<String> getIds(List<Content> list) {
		List<String> ids = new ArrayList<String>();
		for (Content c : list) {
			ids.add(c.getId());
		}
		return ids;
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
}
