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
package jp.aegif.nemaki.cmis.service.impl;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.ObjectServiceInternal;
import jp.aegif.nemaki.cmis.service.RelationshipService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.lock.ThreadLockService;
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
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateObjectIdAndChangeTokenImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FailedToDeleteDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RenditionDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.InputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public class ObjectServiceImpl implements ObjectService {

	private static final Log log = LogFactory.getLog(ObjectServiceImpl.class);

	private TypeManager typeManager;
	private ObjectServiceInternal objectServiceInternal;
	private ContentService contentService;
	private ExceptionService exceptionService;
	private CompileService compileService;
	private RelationshipService relationshipService;
	private SolrUtil solrUtil;
	private NemakiCachePool nemakiCachePool;
	private ThreadLockService threadLockService;
	private int threadMax;

	@Override
	public ObjectData getObjectByPath(CallContext callContext, String repositoryId, String path, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", path);
		// FIXME path is not preserved in db.
		Content content = contentService.getContentByPath(repositoryId, path);
		exceptionService.objectNotFoundByPath(DomainType.OBJECT, content, path);

		Lock lock = threadLockService.getReadLock(repositoryId, content.getId());
		try {
			lock.lock();

			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
					content);

			// //////////////////
			// Body of the method
			// //////////////////
			return compileService.compileObjectData(callContext, repositoryId, content, filter, includeAllowableActions,
					includeRelationships, renditionFilter, includeAcl);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public ObjectData getObject(CallContext callContext, String repositoryId, String objectId, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension) {

		log.info(MessageFormat.format("ObjcetService#getObject START: Repo={0}, Id={1}", repositoryId, objectId));

		exceptionService.invalidArgumentRequired("objectId", objectId);

		Lock lock = threadLockService.getReadLock(repositoryId, objectId);
		try {
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////
			Content content = contentService.getContent(repositoryId, objectId);
			log.info(MessageFormat.format("ObjcetService#getObject getContent success: Repo={0}, Id={1}", repositoryId, objectId));

			// WORK AROUND: getObject(versionSeriesId) is interpreted as
			// getDocumentOflatestVersion
			if (content == null) {
				VersionSeries versionSeries = contentService.getVersionSeries(repositoryId, objectId);
				if (versionSeries != null) {
					content = contentService.getDocumentOfLatestVersion(repositoryId, objectId);
				}
			}
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
					content);
			log.info(MessageFormat.format("ObjcetService#getObject permissionDenied check success: Repo={0}, Id={1}", repositoryId, objectId));

			// //////////////////
			// Body of the method
			// //////////////////
			ObjectData object = compileService.compileObjectData(callContext, repositoryId, content, filter,
					includeAllowableActions, includeRelationships, null, includeAcl);

			log.info(MessageFormat.format("ObjcetService#getObject END: Repo={0}, Id={1} Type={2} Name={3}", repositoryId, objectId, content.getObjectType(), content.getObjectType(), content.getName()));

			return object;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public ContentStream getContentStream(CallContext callContext, String repositoryId, String objectId,
			String streamId, BigInteger offset, BigInteger length) {
		exceptionService.invalidArgumentRequired("objectId", objectId);

		Lock lock = threadLockService.getReadLock(repositoryId, objectId);
		try {
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////
			Content content = contentService.getContent(repositoryId, objectId);
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
					content);

			// //////////////////
			// Body of the method
			// //////////////////
			if (streamId == null) {
				return getContentStreamInternal(repositoryId, content, offset, length);
			} else {
				return getRenditionStream(repositoryId, content, streamId);
			}
		} finally {
			lock.unlock();
		}
	}

	// NOTE: HTTP range (offset and length of stream) is not yet implemented.
	// This is optional per CMIS spec.
	private ContentStream getContentStreamInternal(String repositoryId, Content content, BigInteger rangeOffset,
			BigInteger rangeLength) {
		if (!content.isDocument()) {
			exceptionService.constraint(content.getId(),
					"getContentStream cannnot be invoked to other than document type.");
		}
		Document document = (Document) content;
		
		// CRITICAL CMIS 1.1 SPECIFICATION FIX: Check constraints FIRST
		// This will throw appropriate exceptions for:
		// - NOTALLOWED documents (constraint exception)
		// - REQUIRED documents without attachment (constraint exception)
		// - ALLOWED documents without attachment (passes through)
		exceptionService.constraintContentStreamDownload(repositoryId, document);
		
		// CMIS 1.1 Standard Compliance: After constraint check, handle null attachmentNodeId
		// Per CMIS 1.1 Section 2.1.10.1: If a document has no content stream, return null
		// This is reached only for ALLOWED documents (REQUIRED would have thrown exception above)
		if (document.getAttachmentNodeId() == null) {
			return null;
		}
		
		// After constraint check passes, get attachment
		if (log.isDebugEnabled()) {
			log.debug("Getting attachment: contentService=" + contentService.getClass().getName() + 
				", repositoryId=" + repositoryId + ", attachmentNodeId=" + document.getAttachmentNodeId());
		}
		
		AttachmentNode attachment = null;
		try {
			attachment = contentService.getAttachment(repositoryId, document.getAttachmentNodeId());
			if (log.isDebugEnabled()) {
				log.debug("Attachment retrieved: " + (attachment != null ? attachment.getClass().getName() : "NULL") + 
					", InputStream available: " + (attachment != null && attachment.getInputStream() != null));
			}
		} catch (CmisObjectNotFoundException e) {
			// CloudantClientWrapper now throws proper exception when attachment not found
			if (log.isDebugEnabled()) {
				log.debug("Attachment not found for document " + document.getId() + 
					" (attachmentId=" + document.getAttachmentNodeId() + ") - returning null per CMIS 1.1");
			}
			return null;
		} catch (Exception e) {
			// Handle other CloudantClientWrapper exceptions
			log.error("Attachment retrieval error for document " + document.getId() + 
				" (attachmentId=" + document.getAttachmentNodeId() + "): " + e.getMessage());
			throw new RuntimeException("Failed to retrieve content stream: " + e.getMessage(), e);
		}
		
		// CRITICAL FIX: Handle null attachment per CMIS 1.1 specification
		if (attachment == null) {
			if (log.isDebugEnabled()) {
				log.debug("Attachment is null for document " + document.getId() + 
					" (attachmentId=" + document.getAttachmentNodeId() + ") - returning null per CMIS 1.1");
			}
			return null;
		}
		
		attachment.setRangeOffset(rangeOffset);
		attachment.setRangeLength(rangeLength);

		// Set content stream with CMIS-compliant length handling
		if (log.isDebugEnabled()) {
			log.debug("Content stream creation debug");
		}
		
		String name = attachment.getName();
		if (log.isDebugEnabled()) {
			log.debug("Content name: " + name);
		}
		String mimeType = attachment.getMimeType();
		if (log.isDebugEnabled()) {
			log.debug("Content mimeType: " + mimeType);
		}
		
		if (log.isDebugEnabled()) {
			log.debug("Getting input stream from attachment...");
		}
		InputStream is = attachment.getInputStream();
		if (log.isDebugEnabled()) {
			log.debug("InputStream retrieved: " + (is != null ? "SUCCESS" : "NULL"));
		}

		if (is == null) {
			log.error("attachment.getInputStream() returned null");
			throw new RuntimeException("Content stream InputStream is null!");
		}

		// CRITICAL TCK DEBUG: Verify InputStream contains data after getAttachment()
		if (log.isDebugEnabled()) {
			log.debug("InputStream class: " + is.getClass().getName());
			log.debug("InputStream.markSupported(): " + is.markSupported());
		}

		try {
			int available = is.available();
			if (log.isDebugEnabled()) {
				log.debug("InputStream.available(): " + available);
			}

			// Try to read first few bytes to verify stream contains data
			if (is.markSupported()) {
				is.mark(100);
				byte[] testBuffer = new byte[50];
				int testBytesRead = is.read(testBuffer);
				if (testBytesRead > 0) {
					String preview = new String(testBuffer, 0, testBytesRead, "UTF-8");
				}
				is.reset();
			} else {
			}
		} catch (Exception debugEx) {
			if (log.isDebugEnabled()) {
				log.debug("Exception during InputStream verification: " + debugEx.getMessage());
			}
		}
		// CRITICAL TCK FIX: Always use actual size from CouchDB _attachments metadata
		// This ensures we get the correct size even after appendContent operations
		// which may update binary content without updating AttachmentNode.length field
		BigInteger length;

		if (log.isDebugEnabled()) {
			log.debug("Getting actual content size from CouchDB attachment metadata for: " + document.getAttachmentNodeId());
		}

		Long actualSizeFromDB = contentService.getAttachmentActualSize(repositoryId, document.getAttachmentNodeId());
		if (actualSizeFromDB != null && actualSizeFromDB > 0) {
			length = BigInteger.valueOf(actualSizeFromDB);
		} else {
			// Fallback: Use AttachmentNode.length if CouchDB metadata unavailable
			long attachmentLength = attachment.getLength();
			if (attachmentLength > 0) {
				length = BigInteger.valueOf(attachmentLength);
			} else {
				length = BigInteger.valueOf(-1);
				if (log.isDebugEnabled()) {
					log.debug("Using CMIS standard -1 (unknown size) for: " + name);
				}
			}
		}
	if (log.isDebugEnabled()) {
		log.debug("Creating ContentStreamImpl with final length: " + length);
	}
	ContentStream cs = new ContentStreamImpl(name, length, mimeType, is);

		return cs;
	}

	/**
	 * Calculate the actual size of an InputStream by reading through it
	 * This is needed when AttachmentNode.getLength() returns -1 (unknown size)
	 * Same logic as ContentServiceImpl.calculateStreamSize()
	 * @param inputStream The stream to measure
	 * @return The actual size in bytes, or -1 if calculation fails
	 */
	private long calculateActualStreamSize(InputStream inputStream) {
		if (inputStream == null) {
			return 0L;
		}
		
		long totalBytes = 0L;
		byte[] buffer = new byte[8192]; // 8KB buffer for efficient reading
		
		try {
			// Mark the stream for reset if possible
			if (inputStream.markSupported()) {
				inputStream.mark(Integer.MAX_VALUE);
			}
			
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				totalBytes += bytesRead;
			}
			
			// Reset stream to beginning if possible
			if (inputStream.markSupported()) {
				inputStream.reset();
			} else {
				if (log.isDebugEnabled()) {
					log.debug("InputStream does not support mark/reset - stream position cannot be restored");
				}
			}
			
		} catch (IOException e) {
			if (log.isDebugEnabled()) {
				log.debug("Error calculating stream size: " + e.getMessage());
			}
			return -1L;
		}
		
		return totalBytes;
	}

	private ContentStream getRenditionStream(String repositoryId, Content content, String streamId) {
		if (!content.isDocument() && !content.isFolder()) {
			exceptionService.constraint(content.getId(),
					"getRenditionStream cannnot be invoked to other than document or folder type.");
		}

		exceptionService.constraintRenditionStreamDownload(content, streamId);

		Rendition rendition = contentService.getRendition(repositoryId, streamId);

		BigInteger length = BigInteger.valueOf(rendition.getLength());
		String mimeType = rendition.getMimetype();
		InputStream is = rendition.getInputStream();
		ContentStream cs = new ContentStreamImpl("preview_" + streamId, length, mimeType, is);

		return cs;
	}

	@Override
	public List<RenditionData> getRenditions(CallContext callContext, String repositoryId, String objectId,
			String renditionFilter, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

		Lock lock = threadLockService.getReadLock(repositoryId, objectId);
		try {
			lock.lock();

			List<Rendition> renditions = contentService.getRenditions(repositoryId, objectId);

			List<RenditionData> results = new ArrayList<RenditionData>();
			for (Rendition rnd : renditions) {
				RenditionDataImpl data = new RenditionDataImpl(rnd.getId(), rnd.getMimetype(),
						BigInteger.valueOf(rnd.getLength()), rnd.getKind(), rnd.getTitle(),
						BigInteger.valueOf(rnd.getWidth()), BigInteger.valueOf(rnd.getHeight()),
						rnd.getRenditionDocumentId());
				results.add(data);
			}
			return results;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public AllowableActions getAllowableActions(CallContext callContext, String repositoryId, String objectId) {

		exceptionService.invalidArgumentRequired("objectId", objectId);

		Lock lock = threadLockService.getReadLock(repositoryId, objectId);

		try {
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////
			Content content = contentService.getContent(repositoryId, objectId);
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			// NOTE: The permission key doesn't exist according to CMIS
			// specification.

			// //////////////////
			// Body of the method
			// //////////////////
			return compileService.compileAllowableActions(callContext, repositoryId, content);

		} finally {
			lock.unlock();
		}
	}

	@Override
	public ObjectData create(CallContext callContext, String repositoryId, Properties properties, String folderId,
			ContentStream contentStream, VersioningState versioningState, List<String> policies,
			ExtensionsData extension) {

		String typeId = DataUtil.getObjectTypeId(properties);
		TypeDefinition type = typeManager.getTypeDefinition(repositoryId, typeId);
		if (type == null) {
			throw new CmisObjectNotFoundException("Type '" + typeId + "' is unknown!");
		}

		String objectId = null;
		// NOTE: ACE parameters (addAces, removeAces) are currently passed as null.
		// Future enhancement: support ACE setting during object creation.
		if (type.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT) {
			objectId = createDocument(callContext, repositoryId, properties, folderId, contentStream, versioningState,
					null, null, null, null);
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_FOLDER) {
			objectId = createFolder(callContext, repositoryId, properties, folderId, policies, null, null, extension);
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP) {
			objectId = createRelationship(callContext, repositoryId, properties, policies, null, null, extension);
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_POLICY) {
			objectId = createPolicy(callContext, repositoryId, properties, folderId, policies, null, null, extension);
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_ITEM) {
			objectId = createItem(callContext, repositoryId, properties, folderId, policies, null, null, extension);
		} else {
			throw new CmisObjectNotFoundException("Cannot create object of type '" + typeId + "'!");
		}

		Content retrievedContent = contentService.getContent(repositoryId, objectId);
		ObjectData object = compileService.compileObjectData(callContext, repositoryId,
				retrievedContent, null, false, IncludeRelationships.NONE, null, false);

		return object;
	}

	@Override
	public String createFolder(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {
		
		if (log.isDebugEnabled()) {
			log.debug("NEMAKI CREATEFOLDER: CALLED WITH REPOSITORYID=" + repositoryId + ", FOLDERID=" + folderId);
		}
		if (properties != null) {
			String objectTypeId = DataUtil.getObjectTypeId(properties);
			if (log.isDebugEnabled()) {
				log.debug("NEMAKI CREATEFOLDER: OBJECTTYPEID=" + objectTypeId);
			}
		} else {
			if (log.isDebugEnabled()) {
				log.debug("NEMAKI CREATEFOLDER: PROPERTIES IS NULL");
			}
		}
		
		// CRITICAL FIX: Validate type definition before casting to prevent ClassCastException
		String objectTypeId = DataUtil.getObjectTypeId(properties);
		TypeDefinition rawTypeDefinition = typeManager.getTypeDefinition(repositoryId, objectTypeId);
		
		if (!(rawTypeDefinition instanceof FolderTypeDefinition)) {
			throw new CmisInvalidArgumentException("Invalid object type for folder creation: " + objectTypeId + 
				". Expected folder type but got: " + 
				(rawTypeDefinition != null ? rawTypeDefinition.getClass().getSimpleName() : "null"));
		}
		
		FolderTypeDefinition td = (FolderTypeDefinition) rawTypeDefinition;
		
		Folder parentFolder = contentService.getFolder(repositoryId, folderId);

		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("properties", properties);
		exceptionService.objectNotFoundParentFolder(repositoryId, folderId, parentFolder);
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_CREATE_FOLDER_FOLDER,
				parentFolder);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(repositoryId, properties, BaseTypeId.CMIS_FOLDER);
		exceptionService.constraintAllowedChildObjectTypeId(repositoryId, parentFolder, properties);
		exceptionService.constraintPropertyValue(repositoryId, td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces, properties);
		exceptionService.constraintPermissionDefined(repositoryId, addAces, null);
		exceptionService.constraintPermissionDefined(repositoryId, removeAces, null);
		exceptionService.nameConstraintViolation(repositoryId, parentFolder, properties);

		// //////////////////
		// Body of the method
		// //////////////////
		Folder folder = contentService.createFolder(callContext, repositoryId, properties, parentFolder, policies,
				addAces, removeAces, null);
		return folder.getId();
	}

	@Override
	public String createDocument(CallContext callContext, String repositoryId, Properties properties, String folderId,
			ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {

		String objectTypeId = DataUtil.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID);

		// CRITICAL DEBUG: Always log contentStream status at ERROR level
		Object nameProperty = properties.getProperties().get("cmis:name");
		if (contentStream != null) {
		}

		if (log.isDebugEnabled()) {
			Object secondaryTypeIds = properties.getProperties().get("cmis:secondaryObjectTypeIds");
			log.debug("ObjectServiceImpl.createDocument called:");
			log.debug("  - Document Name: " + (nameProperty != null ? nameProperty : "NULL"));
			log.debug("  - Object Type ID: " + objectTypeId);
			log.debug("  - Secondary Type IDs: " + (secondaryTypeIds != null ? secondaryTypeIds : "NULL"));
			if (contentStream != null) {
				long length = contentStream.getLength();
				BigInteger bigLength = contentStream.getBigLength();
				log.debug("  - ContentStream provided: YES");
				log.debug("  - ContentStream getLength(): " + length);
				log.debug("  - ContentStream getBigLength(): " + bigLength);
				log.debug("  - ContentStream MimeType: " + contentStream.getMimeType());
				log.debug("  - ContentStream FileName: " + contentStream.getFileName());
			} else {
				log.debug("  - ContentStream provided: NO");
			}
			log.debug("  - Repository ID: " + repositoryId);
			log.debug("  - Folder ID: " + folderId);
		}
		
		// Get object type definition for validation
		TypeDefinition rawTypeDefinition = typeManager.getTypeDefinition(repositoryId, objectTypeId);
		
		if (!(rawTypeDefinition instanceof DocumentTypeDefinition)) {
			throw new CmisInvalidArgumentException("Invalid object type for document creation: " + objectTypeId);
		}
		
		DocumentTypeDefinition td = (DocumentTypeDefinition) rawTypeDefinition;
		Folder parentFolder = contentService.getFolder(repositoryId, folderId);

		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("properties", properties);
		exceptionService.invalidArgumentRequiredParentFolderId(repositoryId, folderId);
		exceptionService.objectNotFoundParentFolder(repositoryId, folderId, parentFolder);
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_CREATE_FOLDER_FOLDER,
				parentFolder);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(repositoryId, properties, BaseTypeId.CMIS_DOCUMENT);
		exceptionService.constraintAllowedChildObjectTypeId(repositoryId, parentFolder, properties);
		exceptionService.constraintPropertyValue(repositoryId, td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService.constraintContentStreamRequired(td, contentStream);
		exceptionService.constraintControllableVersionable(td, versioningState, null);
		versioningState = (td.isVersionable() && versioningState == null) ? VersioningState.MAJOR : versioningState;
		exceptionService.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces, properties);
		exceptionService.constraintPermissionDefined(repositoryId, addAces, null);
		exceptionService.constraintPermissionDefined(repositoryId, removeAces, null);
		exceptionService.streamNotSupported(td, contentStream);
		exceptionService.nameConstraintViolation(repositoryId, parentFolder, properties);

		// //////////////////
		// Body of the method
		// //////////////////
		if (log.isDebugEnabled()) {
			log.debug("About to call contentService.createDocument with:");
			log.debug("  - parentFolder.getId(): " + parentFolder.getId());
			log.debug("  - versioningState: " + versioningState);
		}
		
		Document document = contentService.createDocument(callContext, repositoryId, properties, parentFolder,
				contentStream, versioningState, policies, addAces, removeAces);

		if (log.isDebugEnabled()) {
			log.debug("Returned document.getId(): " + document.getId());
			log.debug("Returned document.getAttachmentNodeId(): " + document.getAttachmentNodeId());

			if (document.getAttachmentNodeId() == null) {
				log.debug("Warning: Document created without attachmentNodeId - getContentStream() will return null");
			} else {
				log.debug("SUCCESS: ObjectServiceImpl received document with valid attachmentNodeId: " + document.getAttachmentNodeId());
			}
		}

		return document.getId();
	}

	@Override
	public String createDocumentFromSource(CallContext callContext, String repositoryId, String sourceId,
			Properties properties, String folderId, VersioningState versioningState, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		Document original = contentService.getDocument(repositoryId, sourceId);
		DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager.getTypeDefinition(repositoryId,
				original.getObjectType());

		// //////////////////
		// General Exception
		// //////////////////
		// CRITICAL TCK FIX: properties parameter is optional for createDocumentFromSource
		// If not provided, properties will be copied from source document in contentService layer
		// DO NOT validate properties as required here - removed: exceptionService.invalidArgumentRequired("properties", properties);
		exceptionService.invalidArgumentRequiredParentFolderId(repositoryId, folderId);
		Folder parentFolder = contentService.getFolder(repositoryId, folderId);
		exceptionService.objectNotFoundParentFolder(repositoryId, folderId, parentFolder);
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_CREATE_FOLDER_FOLDER,
				parentFolder);

		// //////////////////
		// Specific Exception
		// //////////////////
		// CRITICAL TCK FIX: Only validate properties if provided
		// When properties is null, source document properties will be used (already validated)
		if (properties != null) {
			exceptionService.constraintBaseTypeId(repositoryId, properties, BaseTypeId.CMIS_DOCUMENT);
			exceptionService.constraintAllowedChildObjectTypeId(repositoryId, parentFolder, properties);
			exceptionService.constraintPropertyValue(repositoryId, td, properties,
					DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
			exceptionService.constraintCotrollablePolicies(td, policies, properties);
			exceptionService.constraintCotrollableAcl(td, addAces, removeAces, properties);
			exceptionService.nameConstraintViolation(repositoryId, parentFolder, properties);
		}

		exceptionService.constraintControllableVersionable(td, versioningState, null);
		versioningState = (td.isVersionable() && versioningState == null) ? VersioningState.MAJOR : versioningState;
		exceptionService.constraintPermissionDefined(repositoryId, addAces, null);
		exceptionService.constraintPermissionDefined(repositoryId, removeAces, null);

		// //////////////////
		// Body of the method
		// //////////////////
		Document document = contentService.createDocumentFromSource(callContext, repositoryId, properties, parentFolder,
				original, versioningState, policies, addAces, removeAces);
		return document.getId();
	}

	@Override
	public void setContentStream(CallContext callContext, String repositoryId, Holder<String> objectId,
			boolean overwriteFlag, ContentStream contentStream, Holder<String> changeToken, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);

		Lock lock = threadLockService.getWriteLock(repositoryId, objectId.getValue());
		try {
			lock.lock();
			// //////////////////
			// General Exception
			// //////////////////

			exceptionService.invalidArgumentRequired("contentStream", contentStream);
			Document doc = (Document) contentService.getContent(repositoryId, objectId.getValue());
			exceptionService.objectNotFound(DomainType.OBJECT, doc, objectId.getValue());
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_SET_CONTENT_DOCUMENT,
					doc);
			DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager.getTypeDefinition(repositoryId,
					doc.getObjectType());
			exceptionService.constraintImmutable(repositoryId, doc, td);

			// //////////////////
			// Specific Exception
			// //////////////////
			exceptionService.contentAlreadyExists(doc, overwriteFlag);
			exceptionService.streamNotSupported(td, contentStream);
			exceptionService.updateConflict(doc, changeToken);
			exceptionService.versioning(callContext, doc);
			Folder parent = contentService.getParent(repositoryId, objectId.getValue());
			exceptionService.objectNotFoundParentFolder(repositoryId, objectId.getValue(), parent);

			// //////////////////
			// Body of the method
			// //////////////////
		String oldId = objectId.getValue();

		// CRITICAL TCK FIX: Handle versionable vs non-versionable documents correctly
		// Per CMIS spec:
		// - Non-versionable documents: Update in place (same object ID)
		// - Versionable documents: Create new version (new object ID)
		// - PWC: Update PWC in place (same object ID)
		DocumentTypeDefinition docType = (DocumentTypeDefinition) typeManager.getTypeDefinition(repositoryId,
				doc.getObjectType());
		boolean isVersionable = (docType.isVersionable() != null && docType.isVersionable());

		Document result = null;
		if (doc.isPrivateWorkingCopy()) {
			// PWC: Update in place
			result = contentService.replacePwc(callContext, repositoryId, doc, contentStream);
			objectId.setValue(result.getId());
		} else if (!isVersionable) {
			// CRITICAL TCK FIX: Non-versionable documents should update in place, not create new version
			// This ensures the object ID doesn't change, matching CMIS spec behavior
			result = contentService.updateDocumentWithNewStream(callContext, repositoryId, doc, contentStream);
			objectId.setValue(result.getId()); // Should be same as oldId for non-versionable
		} else {
			// Versionable: Create new version with new object ID
			result = contentService.createDocumentWithNewStream(callContext, repositoryId, doc,
					contentStream);
			objectId.setValue(result.getId());
		}

			// CRITICAL TCK FIX: Update change token holder with new value after content update
			// CMIS spec requires returning updated change token for optimistic locking
			if (changeToken != null && result != null) {
				changeToken.setValue(result.getChangeToken());
			}

			nemakiCachePool.get(repositoryId).removeCmisCache(oldId);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void deleteContentStream(CallContext callContext, String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);

		Lock lock = threadLockService.getWriteLock(repositoryId, objectId.getValue());
		try {
			lock.lock();

			// //////////////////
			// Exception
			// //////////////////
			Document document = contentService.getDocument(repositoryId, objectId.getValue());
			exceptionService.objectNotFound(DomainType.OBJECT, document, objectId.getValue());
			exceptionService.constraintContentStreamRequired(repositoryId, document);

		// //////////////////
		// Body of the method
		// //////////////////
		// CRITICAL TCK FIX: Capture updated document directly to prevent race condition
		// Using return value avoids second lookup that could see stale change token
		Document updatedDocument = contentService.deleteContentStream(callContext, repositoryId, objectId);

		// Update changeToken holder with the fresh token from returned document
		if (updatedDocument != null && changeToken != null) {
			changeToken.setValue(updatedDocument.getChangeToken());
		}

		nemakiCachePool.get(repositoryId).removeCmisCache(objectId.getValue());

		} finally {
			lock.unlock();
		}
	}

	@Override
	public void appendContentStream(CallContext callContext, String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ContentStream contentStream, boolean isLastChunk, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);

		Lock lock = threadLockService.getWriteLock(repositoryId, objectId.getValue());
		try {
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////

			exceptionService.invalidArgumentRequired("contentStream", contentStream);
			Document doc = (Document) contentService.getContent(repositoryId, objectId.getValue());
			exceptionService.objectNotFound(DomainType.OBJECT, doc, objectId.getValue());
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_SET_CONTENT_DOCUMENT,
					doc);
			DocumentTypeDefinition td = (DocumentTypeDefinition) typeManager.getTypeDefinition(repositoryId,
					doc.getObjectType());
			exceptionService.constraintImmutable(repositoryId, doc, td);

			// //////////////////
			// Specific Exception
			// //////////////////
			exceptionService.streamNotSupported(td, contentStream);

		// CRITICAL TCK FIX: appendContentStream change token auto-fill
		// If no change token provided, use current document's change token
		// This allows appendContentStream to work without requiring the client to track change tokens
		// after each append operation
		if (changeToken == null || changeToken.getValue() == null) {
			String currentChangeToken = doc.getChangeToken();
			if (currentChangeToken != null && !"null".equals(currentChangeToken)) {
				changeToken = new Holder<String>(currentChangeToken);
			}
		}
			exceptionService.updateConflict(doc, changeToken);
			exceptionService.versioning(callContext,doc);

			// //////////////////
			// Body of the method
			// //////////////////
			contentService.appendAttachment(callContext, repositoryId, objectId, changeToken, contentStream,
					isLastChunk, extension);

			nemakiCachePool.get(repositoryId).removeCmisCache(objectId.getValue());
		} finally {
			lock.unlock();
		}
	}

	@Override
	public String createRelationship(CallContext callContext, String repositoryId, Properties properties,
			List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {
		String objectTypeId = DataUtil.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID);
		RelationshipTypeDefinition td = (RelationshipTypeDefinition) typeManager.getTypeDefinition(repositoryId,
				objectTypeId);
		// //////////////////
		// Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("properties", properties);
		String sourceId = DataUtil.getIdProperty(properties, PropertyIds.SOURCE_ID);
		if (sourceId != null) {
			Content source = contentService.getContent(repositoryId, sourceId);
			if (source == null) {
				exceptionService.objectNotFound(jp.aegif.nemaki.util.constant.DomainType.OBJECT, source, sourceId);
			} else {
				exceptionService.constraintAllowedSourceTypes(td, source);
				exceptionService.permissionDenied(callContext, repositoryId,
						PermissionMapping.CAN_CREATE_RELATIONSHIP_SOURCE, source);
			}
		}
		String targetId = DataUtil.getIdProperty(properties, PropertyIds.TARGET_ID);
		if (targetId != null) {
			Content target = contentService.getContent(repositoryId, targetId);
			if (target == null) {
				exceptionService.objectNotFound(jp.aegif.nemaki.util.constant.DomainType.OBJECT, target, targetId);
			} else {
				exceptionService.constraintAllowedTargetTypes(td, target);
				exceptionService.permissionDenied(callContext, repositoryId,
						PermissionMapping.CAN_CREATE_RELATIONSHIP_TARGET, target);
			}
		}

		exceptionService.constraintBaseTypeId(repositoryId, properties, BaseTypeId.CMIS_RELATIONSHIP);
		exceptionService.constraintPropertyValue(repositoryId, td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces, properties);
		exceptionService.constraintPermissionDefined(repositoryId, addAces, null);
		exceptionService.constraintPermissionDefined(repositoryId, removeAces, null);
		exceptionService.nameConstraintViolation(repositoryId, null, properties);

		// //////////////////
		// Body of the method
		// //////////////////
		Relationship relationship = contentService.createRelationship(callContext, repositoryId, properties, policies,
				addAces, removeAces, extension);
		nemakiCachePool.get(repositoryId).removeCmisCache(relationship.getSourceId());
		nemakiCachePool.get(repositoryId).removeCmisCache(relationship.getTargetId());

		return relationship.getId();
	}

	@Override
	public String createPolicy(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("properties", properties);
		// NOTE: folderId is ignored because policy is not filable in Nemaki
		TypeDefinition td = typeManager.getTypeDefinition(repositoryId,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_TYPE_ID));
		exceptionService.constraintPropertyValue(repositoryId, td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(repositoryId, properties, BaseTypeId.CMIS_POLICY);
		// exceptionService.constraintAllowedChildObjectTypeId(parent,
		// properties);
		exceptionService.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces, properties);
		// exceptionService.nameConstraintViolation(properties, parent);

		// //////////////////
		// Body of the method
		// //////////////////
		Policy policy = contentService.createPolicy(callContext, repositoryId, properties, policies, addAces,
				removeAces, extension);
		return policy.getId();
	}

	@Override
	public String createItem(CallContext callContext, String repositoryId, Properties properties, String folderId,
			List<String> policies, Acl addAces, Acl removeAces, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		TypeDefinition td = typeManager.getTypeDefinition(repositoryId, DataUtil.getObjectTypeId(properties));
		Folder parentFolder = contentService.getFolder(repositoryId, folderId);
		exceptionService.objectNotFoundParentFolder(repositoryId, folderId, parentFolder);
		exceptionService.invalidArgumentRequired("properties", properties);
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_CREATE_FOLDER_FOLDER,
				parentFolder);

		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.constraintBaseTypeId(repositoryId, properties, BaseTypeId.CMIS_ITEM);
		exceptionService.constraintPropertyValue(repositoryId, td, properties,
				DataUtil.getIdProperty(properties, PropertyIds.OBJECT_ID));
		exceptionService.constraintCotrollablePolicies(td, policies, properties);
		exceptionService.constraintCotrollableAcl(td, addAces, removeAces, properties);

		// //////////////////
		// Body of the method
		// //////////////////
		Item item = contentService.createItem(callContext, repositoryId, properties, folderId, policies, addAces,
				removeAces, extension);
		return item.getId();
	}

	@Override
	public void updateProperties(CallContext callContext, String repositoryId, Holder<String> objectId,
			Properties properties, Holder<String> changeToken, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);

		Lock lock = threadLockService.getWriteLock(repositoryId, objectId.getValue());
		try {
			lock.lock();

			// //////////////////
			// Exception
			// //////////////////
			Content content = checkExceptionBeforeUpdateProperties(callContext, repositoryId, objectId, properties,
					changeToken);

			// //////////////////
		// //////////////////
		// Body of the method
		// //////////////////
		if (log.isDebugEnabled()) {
			String oldChangeToken = (changeToken != null) ? changeToken.getValue() : null;
			log.debug("UPDATE PROPERTIES: Object " + objectId.getValue() + " OLD change token: '" + oldChangeToken + "'");
		}

		Content updatedContent = contentService.updateProperties(callContext, repositoryId, properties, content);

		// CRITICAL TCK FIX: Update change token holder with new value after update
		// CMIS spec requires returning updated change token for optimistic locking
		if (changeToken != null && updatedContent != null) {
			String newChangeToken = updatedContent.getChangeToken();
			changeToken.setValue(newChangeToken);
			if (log.isDebugEnabled()) {
				log.debug("UPDATE PROPERTIES: Object " + objectId.getValue() + " NEW change token: '" + newChangeToken + "'");
			}
		} else {
		}

		// CRITICAL FIX (2025-12-27): Use removeCmisAndContentCache instead of removeCmisCache
		// to ensure both CMIS and Content caches are invalidated after property update.
		// Without this fix, getObject returns stale data from ContentCache even though
		// the update was successful and data is correct in CouchDB.
		nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId.getValue());
		} finally {
			lock.unlock();
		}
	}

	private Content checkExceptionBeforeUpdateProperties(CallContext callContext, String repositoryId,
			Holder<String> objectId, Properties properties, Holder<String> changeToken) {
		// //////////////////
		// General Exception
		// //////////////////

		// Allow null properties for secondary type operations
		// Properties can be null when only modifying secondary types
		// exceptionService.invalidArgumentRequired("properties", properties);

		// CRITICAL FIX (2025-12-18): Invalidate content cache BEFORE getting content for update
		// This ensures we get fresh data from CouchDB including correct aspect properties
		// Without this fix, cached content with empty aspects would cause property loss during updates
		nemakiCachePool.get(repositoryId).getContentCache().remove(objectId.getValue());
		if (log.isDebugEnabled()) {
			log.debug("checkExceptionBeforeUpdateProperties: Invalidated content cache for " + objectId.getValue() + " before update");
		}

		Content content = contentService.getContent(repositoryId, objectId.getValue());
		exceptionService.objectNotFound(DomainType.OBJECT, content, objectId.getValue());
		if (content.isDocument()) {
			Document d = (Document) content;
			exceptionService.versioning(callContext,d);
			exceptionService.constraintUpdateWhenCheckedOut(repositoryId, callContext.getUsername(), d);
			
			TypeDefinition typeDef = typeManager.getTypeDefinition(repositoryId, d);
			exceptionService.constraintImmutable(repositoryId, d, typeDef);
		}
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT,
				content);
		exceptionService.updateConflict(content, changeToken);

		// Check property constraints only if properties are provided
		if (properties != null) {
			TypeDefinition tdf = typeManager.getTypeDefinition(repositoryId, content);
			exceptionService.constraintPropertyValue(repositoryId, tdf, properties, objectId.getValue());
		}

		return content;
	}

	@Override
	public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(CallContext callContext, String repositoryId,
			List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeTokenList, Properties properties,
			List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		// Each permission is checked at each execution
		exceptionService.invalidArgumentRequiredCollection("objectIdAndChangeToken", objectIdAndChangeTokenList);
		exceptionService.invalidArgumentSecondaryTypeIds(repositoryId, properties);

		// //////////////////
		// Body of the method
		// //////////////////
		List<BulkUpdateObjectIdAndChangeToken> results = new ArrayList<BulkUpdateObjectIdAndChangeToken>();

		ExecutorService executor = Executors.newCachedThreadPool();
		List<BulkUpdateTask> tasks = new ArrayList<>();
		for (BulkUpdateObjectIdAndChangeToken objectIdAndChangeToken : objectIdAndChangeTokenList) {
			tasks.add(new BulkUpdateTask(callContext, repositoryId, objectIdAndChangeToken, properties,
					addSecondaryTypeIds, removeSecondaryTypeIds, extension));
		}

		try {
			List<Future<BulkUpdateObjectIdAndChangeToken>> _results = executor.invokeAll(tasks);
			for (Future<BulkUpdateObjectIdAndChangeToken> _result : _results) {
				try {
					BulkUpdateObjectIdAndChangeToken result = _result.get();
					results.add(result);
				} catch (Exception e) {
					log.debug("Bulk update task failed for one object", e);
				}
			}
		} catch (InterruptedException e1) {
			log.warn("Bulk update operation was interrupted", e1);
			Thread.currentThread().interrupt();
		}

		return results;
	}

	private class BulkUpdateTask implements Callable<BulkUpdateObjectIdAndChangeToken> {

		private CallContext callContext;
		private String repositoryId;
		private BulkUpdateObjectIdAndChangeToken objectIdAndChangeToken;
		private Properties properties;
		private List<String> addSecondaryTypeIds;
		private List<String> removeSecondaryTypeIds;
		private ExtensionsData extension;

		public BulkUpdateTask(CallContext callContext, String repositoryId,
				BulkUpdateObjectIdAndChangeToken objectIdAndChangeToken, Properties properties,
				List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension) {
			super();
			this.callContext = callContext;
			this.repositoryId = repositoryId;
			this.objectIdAndChangeToken = objectIdAndChangeToken;
			this.properties = properties;
			this.addSecondaryTypeIds = addSecondaryTypeIds;
			this.removeSecondaryTypeIds = removeSecondaryTypeIds;
			this.extension = extension;
		}

		@Override
		public BulkUpdateObjectIdAndChangeToken call() throws Exception {
			exceptionService.invalidArgumentRequiredString("objectId", objectIdAndChangeToken.getId());

			Lock lock = threadLockService.getWriteLock(repositoryId, objectIdAndChangeToken.getId());
			try {
				lock.lock();

				Content content = checkExceptionBeforeUpdateProperties(callContext, repositoryId,
						new Holder<String>(objectIdAndChangeToken.getId()), properties,
						new Holder<String>(objectIdAndChangeToken.getChangeToken()));
				contentService.updateProperties(callContext, repositoryId, properties, content);
				nemakiCachePool.get(repositoryId).removeCmisCache(content.getId());

				BulkUpdateObjectIdAndChangeToken result = new BulkUpdateObjectIdAndChangeTokenImpl(
						objectIdAndChangeToken.getId(), content.getId(), String.valueOf(content.getChangeToken()));
				return result;
			} catch (Exception e) {
				// Don't throw an error
				// Don't return any BulkUpdateObjectIdAndChangetoken
			} finally {
				lock.unlock();
			}

			// No BulkUpdateObjectIdAndChangeToken returned for this object
			return null;
		}

	}

	@Override
	public void moveObject(CallContext callContext, String repositoryId, Holder<String> objectId, String sourceFolderId,
			String targetFolderId, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);

		Lock lock = threadLockService.getWriteLock(repositoryId, objectId.getValue());
		try {
			lock.lock();
			// //////////////////
			// General Exception
			// //////////////////
			exceptionService.invalidArgumentRequiredString("sourceFolderId", sourceFolderId);
			exceptionService.invalidArgumentRequiredString("targetFolderId", targetFolderId);
			Content content = contentService.getContent(repositoryId, objectId.getValue());
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId.getValue());
			Folder source = contentService.getFolder(repositoryId, sourceFolderId);
			exceptionService.objectNotFound(DomainType.OBJECT, source, sourceFolderId);
			Folder target = contentService.getFolder(repositoryId, targetFolderId);
			exceptionService.objectNotFound(DomainType.OBJECT, target, targetFolderId);
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_MOVE_OBJECT, content);
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_MOVE_SOURCE, source);
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_MOVE_TARGET, target);
			exceptionService.nameConstraintViolation(repositoryId, target, content.getName());

			// //////////////////
			// Body of the method
			// //////////////////
			contentService.move(callContext, repositoryId, content, target);

			nemakiCachePool.get(repositoryId).removeCmisCache(content.getId());
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void deleteObject(CallContext callContext, String repositoryId, String objectId, Boolean allVersions,
			ExtensionsData extension) {

		objectServiceInternal.deleteObjectInternal(callContext, repositoryId, objectId, allVersions, false);
	}

	@Override
	public FailedToDeleteData deleteTree(CallContext callContext, String repositoryId, String folderId,
			Boolean allVersions, UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension) {
		// //////////////////
		// Inner classes
		// //////////////////
		class DeleteTask implements Callable<Boolean> {
			private CallContext callContext;
			private String repositoryId;
			private Content content;
			private Boolean allVersions;

			public DeleteTask() {
			}

			public DeleteTask(CallContext callContext, String repositoryId, Content content, Boolean allVersions) {
				this.callContext = callContext;
				this.repositoryId = repositoryId;
				this.content = content;
				this.allVersions = allVersions;
			}

			@Override
			public Boolean call() throws Exception {
				try {
					objectServiceInternal.deleteObjectInternal(callContext, repositoryId, content, allVersions, true);
					return false;
				} catch (Exception e) {
					return true;
				}
			}
		}

		class WrappedExecutorService {
			private ExecutorService service;
			private Folder folder;

			private WrappedExecutorService() {
			};

			public WrappedExecutorService(ExecutorService service, Folder folder) {
				this.service = service;
				this.folder = folder;
			}

			public ExecutorService getService() {
				return service;
			}

			public Folder getFolder() {
				return folder;
			}
		}

		class DeleteService {
			private Map<String, Future<Boolean>> failureIds;
			private WrappedExecutorService parentService;
			private CallContext callContext;
			private String repositoryId;
			private Content content;
			private Boolean allVersions;

			public DeleteService() {
			}

			public DeleteService(Map<String, Future<Boolean>> failureIds, WrappedExecutorService parentService,
					CallContext callContext, String repositoryId, Content content, Boolean allVersions) {
				super();
				this.failureIds = failureIds;
				this.parentService = parentService;
				this.callContext = callContext;
				this.repositoryId = repositoryId;
				this.content = content;
				this.allVersions = allVersions;
			}

			public void execute() {
				if (content.isDocument()) {
					Future<Boolean> result = parentService.getService()
							.submit(new DeleteTask(callContext, repositoryId, content, allVersions));
					failureIds.put(content.getId(), result);
				} else if (content.isFolder()) {
					WrappedExecutorService childrenService = new WrappedExecutorService(
							Executors.newFixedThreadPool(threadMax), (Folder) content);

					List<Content> children = contentService.getChildren(repositoryId, content.getId());
					if (CollectionUtils.isNotEmpty(children)) {
						for (Content child : children) {
							DeleteService deleteService = new DeleteService(this.failureIds, childrenService,
									callContext, repositoryId, child, allVersions);
							deleteService.execute();
						}
					}

					// wait til newService ends
					childrenService.getService().shutdown();
					try {
						childrenService.getService().awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
					} catch (InterruptedException e) {
						log.error(e, e);
					}

					// Lastly, delete self
					Future<Boolean> result = parentService.getService()
							.submit(new DeleteTask(callContext, repositoryId, content, allVersions));
					failureIds.put(content.getId(), result);
				}

			}
		}

		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", folderId);
		Folder folder = contentService.getFolder(repositoryId, folderId);
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_DELETE_TREE_FOLDER, folder);
		exceptionService.constraintDeleteRootFolder(repositoryId, folderId);

		// //////////////////
		// Specific Exception
		// //////////////////
		if (folder == null)
			exceptionService.constraint(folderId, "deleteTree cannot be invoked on a non-folder object");

		// //////////////////
		// Body of the method
		// //////////////////
		// Delete descendants
		Map<String, Future<Boolean>> failureIds = new HashMap<String, Future<Boolean>>();

		DeleteService deleteService = new DeleteService(failureIds,
				new WrappedExecutorService(Executors.newFixedThreadPool(threadMax), folder), callContext, repositoryId,
				folder, allVersions);
		deleteService.execute();

		// Delete folder from Solr index
		if (folder != null) {
			solrUtil.deleteDocument(repositoryId, folder.getId());
		}

		// Check FailedToDeleteData
		// FIXME Consider orphans that was failed to be deleted
		FailedToDeleteDataImpl fdd = new FailedToDeleteDataImpl();
		List<String> ids = new ArrayList<String>();
		for (Entry<String, Future<Boolean>> entry : failureIds.entrySet()) {
			Boolean failed;
			try {
				failed = entry.getValue().get();
				if (failed) {
					ids.add(entry.getKey());
				}
			} catch (InterruptedException e) {
				log.warn("Delete operation interrupted for object: " + entry.getKey(), e);
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				log.warn("Delete operation failed for object: " + entry.getKey(), e);
			}
		}
		fdd.setIds(ids);
		return fdd;
	}

	public void setObjectServiceInternal(ObjectServiceInternal objectServiceInternal) {
		this.objectServiceInternal = objectServiceInternal;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setCompileService(CompileService compileService) {
		this.compileService = compileService;
	}

	public void setRelationshipService(RelationshipService relationshipService) {
		this.relationshipService = relationshipService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setSolrUtil(SolrUtil solrUtil) {
		this.solrUtil = solrUtil;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}

	public void setThreadMax(int threadMax) {
		this.threadMax = threadMax;
	}
}
