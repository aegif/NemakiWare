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
import jp.aegif.nemaki.businesslogic.WebhookService;
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
	private WebhookService webhookService;
	private int threadMax;

	// BTL-005: Bounded executor for deleteTree. Lazily initialized after Spring DI sets threadMax.
	private volatile ExecutorService deleteTreeExecutor;
	private final Object treeExecutorLock = new Object();
	// BTL-006: Separate bounded executor for bulkUpdateProperties.
	private volatile ExecutorService bulkUpdateExecutor;
	private final Object bulkUpdateExecutorLock = new Object();

	@Override
	public ObjectData getObjectByPath(CallContext callContext, String repositoryId, String path, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequired("objectId", path);
		// NOTE: CouchDB にパスフィールドは保存されていない。getContentByPath() は
		// ルートから parentId チェーンを辿るツリー走査でパスを解決する (計算量 O(depth))。
		// トレードオフ: 移動・名称変更時にパスフィールドのメンテナンスが不要。
		// 将来の最適化案: materialized path field / パスキャッシュ / Solr path 活用
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

		if (log.isDebugEnabled()) log.debug(MessageFormat.format("ObjcetService#getObject START: Repo={0}, Id={1}", repositoryId, objectId));

		exceptionService.invalidArgumentRequired("objectId", objectId);

		Lock lock = threadLockService.getReadLock(repositoryId, objectId);
		try {
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////
			Content content = contentService.getContent(repositoryId, objectId);
			if (log.isDebugEnabled()) log.debug(MessageFormat.format("ObjcetService#getObject getContent success: Repo={0}, Id={1}", repositoryId, objectId));

			// WORK AROUND: getObject(versionSeriesId) is interpreted as
			// getDocumentOflatestVersion
			if (content == null) {
				VersionSeries versionSeries = contentService.getVersionSeries(repositoryId, objectId);
				if (versionSeries != null) {
					content = contentService.getDocumentOfLatestVersion(repositoryId, objectId);
				}
			}
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			if (content == null) {
				return null;
			}
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
					content);
			if (log.isDebugEnabled()) log.debug(MessageFormat.format("ObjcetService#getObject permissionDenied check success: Repo={0}, Id={1}", repositoryId, objectId));

			// //////////////////
			// Body of the method
			// //////////////////
			ObjectData object = compileService.compileObjectData(callContext, repositoryId, content, filter,
					includeAllowableActions, includeRelationships, null, includeAcl);

			if (log.isDebugEnabled()) log.debug(MessageFormat.format("ObjcetService#getObject END: Repo={0}, Id={1} Type={2} Name={3}", repositoryId, objectId, content.getObjectType(), content.getName()));

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
		
		// CMIS 1.1: Check constraints first
		exceptionService.constraintContentStreamDownload(repositoryId, document);
		
		// Per CMIS 1.1 Section 2.1.10.1: If a document has no content stream, return null
		if (document.getAttachmentNodeId() == null) {
			return null;
		}
		
		// Get attachment
		AttachmentNode attachment = null;
		try {
			attachment = contentService.getAttachment(repositoryId, document.getAttachmentNodeId());
		} catch (CmisObjectNotFoundException e) {
			if (log.isDebugEnabled()) {
				log.debug("Attachment not found for document " + document.getId() + 
					" (attachmentId=" + document.getAttachmentNodeId() + ") - returning null per CMIS 1.1");
			}
			return null;
		} catch (Exception e) {
			log.error("Attachment retrieval error for document " + document.getId() + 
				" (attachmentId=" + document.getAttachmentNodeId() + "): " + e.getMessage());
			throw new RuntimeException("Failed to retrieve content stream: " + e.getMessage(), e);
		}
		
		if (attachment == null) {
			if (log.isDebugEnabled()) {
				log.debug("Attachment is null for document " + document.getId() + 
					" (attachmentId=" + document.getAttachmentNodeId() + ") - returning null per CMIS 1.1");
			}
			return null;
		}
		
		attachment.setRangeOffset(rangeOffset);
		attachment.setRangeLength(rangeLength);

		String name = attachment.getName();
		String mimeType = attachment.getMimeType();
		InputStream is = attachment.getInputStream();

		if (is == null) {
			log.error("attachment.getInputStream() returned null");
			throw new RuntimeException("Content stream InputStream is null!");
		}

		// Use AttachmentNode metadata length from CouchDB _attachments metadata.
		// No need to download the entire content again just to measure size.
		// For range requests the stream body is sliced (AttachmentNode.getInputStream),
		// so the declared length must be the SLICED length — advertising the full
		// attachment length made clients hit "Premature EOF" on ranged reads
		// (TCK ContentRangesTest).
		long attachmentLength = computeRangeAwareLength(attachment.getLength(), rangeOffset, rangeLength);
		BigInteger length;
		if (attachmentLength >= 0) {
			length = BigInteger.valueOf(attachmentLength);
		} else {
			length = BigInteger.valueOf(-1);
			if (log.isDebugEnabled()) {
				log.debug("Using CMIS standard -1 (unknown size) for: " + name);
			}
		}

		if (log.isDebugEnabled()) {
			log.debug("Creating ContentStreamImpl - name: " + name + ", length: " + length + ", mimeType: " + mimeType);
		}
		ContentStream cs = new ContentStreamImpl(name, length, mimeType, is);

		return cs;
	}

	/**
	 * Compute the length of the content stream that will actually be returned,
	 * taking a range request into account. Mirrors the slicing semantics of
	 * {@link jp.aegif.nemaki.model.AttachmentNode#getInputStream()}:
	 * offset defaults to 0 (negative treated as 0), an offset at/past the end
	 * yields an empty stream, and the length is clamped to the remaining bytes.
	 *
	 * @param attachmentLength total attachment length, or negative if unknown
	 * @return the sliced length, or -1 when the total length is unknown
	 */
	static long computeRangeAwareLength(long attachmentLength, BigInteger rangeOffset, BigInteger rangeLength) {
		if (attachmentLength < 0) {
			return -1;
		}
		if (rangeOffset == null && rangeLength == null) {
			return attachmentLength;
		}
		long offset = (rangeOffset != null) ? rangeOffset.longValue() : 0L;
		if (offset < 0) {
			offset = 0;
		}
		if (offset >= attachmentLength) {
			return 0;
		}
		long rangeLen = (rangeLength != null) ? rangeLength.longValue() : (attachmentLength - offset);
		if (offset + rangeLen > attachmentLength) {
			rangeLen = attachmentLength - offset;
		}
		return Math.max(0, rangeLen);
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

		// Use -1 for length to avoid CouchDB gzip compression size mismatch.
		// CouchDB _attachments.length reports compressed size, but the SDK returns
		// decompressed content. Setting Content-Length to compressed size causes
		// Tomcat to truncate the response, producing "Invalid PDF structure" errors.
		String mimeType = rendition.getMimetype();
		InputStream is = rendition.getInputStream();
		ContentStream cs = new ContentStreamImpl("preview_" + streamId, BigInteger.valueOf(-1), mimeType, is);

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
			throw new CmisInvalidArgumentException("Properties must not be null for createFolder");
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

		// //////////////////
		// Body of the method
		// //////////////////
		// Parent write lock: make the name check + create atomic per parent
		// (see createDocument for rationale — prevents concurrent duplicate names).
		Lock parentLock = threadLockService.getWriteLock(repositoryId, parentFolder.getId());
		parentLock.lock();
		try {
			exceptionService.nameConstraintViolation(repositoryId, parentFolder, properties);

			Folder folder = contentService.createFolder(callContext, repositoryId, properties, parentFolder, policies,
					addAces, removeAces, null);

			// Invalidate IN_TREE folder hierarchy cache
			solrUtil.invalidateFolderHierarchyCache(repositoryId);

			return folder.getId();
		} finally {
			parentLock.unlock();
		}
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
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER,
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

		// //////////////////
		// Body of the method
		// //////////////////
		// Hold a write lock on the PARENT folder so the name-uniqueness check and
		// the actual create are atomic per parent. Without it, concurrent creates
		// of the same name all pass the check before any of them inserts, so
		// duplicate-named children get persisted (the sequential path correctly
		// returns nameConstraintViolation / 409, but the concurrent path did not).
		Lock parentLock = threadLockService.getWriteLock(repositoryId, parentFolder.getId());
		parentLock.lock();
		try {
			exceptionService.nameConstraintViolation(repositoryId, parentFolder, properties);

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
		} finally {
			parentLock.unlock();
		}
	}

	@Override
	public String createDocumentFromSource(CallContext callContext, String repositoryId, String sourceId,
			Properties properties, String folderId, VersioningState versioningState, List<String> policies, Acl addAces,
			Acl removeAces, ExtensionsData extension) {
		Document original = contentService.getDocument(repositoryId, sourceId);

		// //////////////////
		// Source-object authorization (security)
		// //////////////////
		// The caller must be allowed to READ the copy source. Without this,
		// any user who knows (or guesses) a sourceId could copy an object
		// they cannot read into a folder they can write to, then read the
		// copy back — an ACL bypass / IDOR. Mirror the same checks the
		// direct read paths (getObject/getContentStream) apply: object
		// existence, property-read permission, and — since the content is
		// duplicated into the new document — content-view permission.
		exceptionService.objectNotFound(DomainType.OBJECT, original, sourceId);
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
				original);
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_VIEW_CONTENT_OBJECT,
				original);

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
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER,
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
		}

		exceptionService.constraintControllableVersionable(td, versioningState, null);
		versioningState = (td.isVersionable() && versioningState == null) ? VersioningState.MAJOR : versioningState;
		exceptionService.constraintPermissionDefined(repositoryId, addAces, null);
		exceptionService.constraintPermissionDefined(repositoryId, removeAces, null);

		// //////////////////
		// Body of the method
		// //////////////////
		// Parent write lock: make the name check + create atomic per parent
		// (see createDocument for rationale — prevents concurrent duplicate names).
		Lock parentLock = threadLockService.getWriteLock(repositoryId, parentFolder.getId());
		parentLock.lock();
		try {
			if (properties != null) {
				exceptionService.nameConstraintViolation(repositoryId, parentFolder, properties);
			}
			Document document = contentService.createDocumentFromSource(callContext, repositoryId, properties,
					parentFolder, original, versioningState, policies, addAces, removeAces);
			return document.getId();
		} finally {
			parentLock.unlock();
		}
	}

	@Override
	public void setContentStream(CallContext callContext, String repositoryId, Holder<String> objectId,
			boolean overwriteFlag, ContentStream contentStream, Holder<String> changeToken, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);

		Content webhookContent = null;
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

			// Capture data for webhook trigger (fired outside lock)
			webhookContent = result;

			nemakiCachePool.get(repositoryId).removeCmisAndContentCache(oldId);
			// Versionable documents produce a new object ID; invalidate that cache entry too
			// to prevent stale references from being read by concurrent threads
			if (result != null && !oldId.equals(result.getId())) {
				nemakiCachePool.get(repositoryId).removeCmisAndContentCache(result.getId());
			}
		} finally {
			lock.unlock();
		}

		// Trigger CONTENT_UPDATED webhook outside write lock to avoid
		// holding the lock during parent-folder traversal and async dispatch.
		// triggerWebhookByEventType captures an immutable snapshot of Content
		// fields at call entry, so brief races between unlock and this call
		// are tolerated.
		if (webhookService != null && webhookContent != null) {
			try {
				webhookService.triggerWebhookByEventType(callContext, repositoryId,
						webhookContent, "CONTENT_UPDATED", null);
			} catch (Exception e) {
				log.warn("Failed to trigger CONTENT_UPDATED webhook for object "
						+ webhookContent.getId() + ": " + e.getMessage());
			}
		}
	}

	@Override
	public void deleteContentStream(CallContext callContext, String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);

		Content webhookContent = null;
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

		// Capture data for webhook trigger (fired outside lock)
		webhookContent = updatedDocument;

		nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId.getValue());

		} finally {
			lock.unlock();
		}

		// Trigger CONTENT_UPDATED webhook outside write lock
		if (webhookService != null && webhookContent != null) {
			try {
				webhookService.triggerWebhookByEventType(callContext, repositoryId,
						webhookContent, "CONTENT_UPDATED", null);
			} catch (Exception e) {
				log.warn("Failed to trigger CONTENT_UPDATED webhook for object "
						+ webhookContent.getId() + ": " + e.getMessage());
			}
		}
	}

	@Override
	public void appendContentStream(CallContext callContext, String repositoryId, Holder<String> objectId,
			Holder<String> changeToken, ContentStream contentStream, boolean isLastChunk, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredHolderString("objectId", objectId);

		Content webhookContent = null;
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

			// Capture content inside lock to avoid race with concurrent writers.
			// Re-fetching after unlock could return state from a subsequent update,
			// producing mismatched payload/changeToken in the webhook.
			webhookContent = contentService.getContent(repositoryId, objectId.getValue());

			nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId.getValue());
		} finally {
			lock.unlock();
		}

		// Trigger CONTENT_UPDATED webhook outside write lock
		if (webhookService != null && webhookContent != null) {
			try {
				webhookService.triggerWebhookByEventType(callContext, repositoryId,
						webhookContent, "CONTENT_UPDATED", null);
			} catch (Exception e) {
				log.warn("Failed to trigger CONTENT_UPDATED webhook for object "
						+ webhookContent.getId() + ": " + e.getMessage());
			}
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
		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER,
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

		// BTL-006: Use dedicated bulkUpdate executor (separate from deleteTree)
		List<Future<BulkUpdateObjectIdAndChangeToken>> futures = new ArrayList<>();
		for (BulkUpdateObjectIdAndChangeToken objectIdAndChangeToken : objectIdAndChangeTokenList) {
			futures.add(getBulkUpdateExecutor().submit(new BulkUpdateTask(callContext, repositoryId, objectIdAndChangeToken,
					properties, addSecondaryTypeIds, removeSecondaryTypeIds, extension)));
		}

		for (Future<BulkUpdateObjectIdAndChangeToken> future : futures) {
			try {
				BulkUpdateObjectIdAndChangeToken result = future.get();
				results.add(result);
			} catch (InterruptedException e) {
				log.warn("Bulk update operation was interrupted", e);
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				log.debug("Bulk update task failed for one object", e);
			}
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
		exceptionService.invalidArgumentRequiredString("sourceFolderId", sourceFolderId);
		exceptionService.invalidArgumentRequiredString("targetFolderId", targetFolderId);

		final String movingId = objectId.getValue();
		if (movingId.equals(targetFolderId)) {
			exceptionService.constraint(movingId, "Cannot move a folder into itself");
		}

		// Two locks, and for a FOLDER a third that serialises folder moves repository-wide.
		//
		// Locking the moved object and the destination is not enough to make the cycle guard
		// below sound. Concurrent moves of A under a descendant of B and of B under a descendant
		// of A hold {A, B_child} and {B, A_child} — disjoint sets, so neither excludes the other,
		// and each evaluates its guard against a hierarchy where the other move has not landed.
		// Both pass, both commit, and the pair forms a cycle. Widening the lock set does not fix
		// it either: the set that would have to be held is the whole ancestor chain, which is
		// exactly what the other move is changing.
		//
		// So folder moves take a repository-scoped write lock and run one at a time. This is a
		// rare administrative operation, and a cycle is not cosmetic — the ACL inheritance walk
		// climbs parents, so a self-ancestor folder makes every effective-ACL computation beneath
		// it unanswerable. Non-folder moves cannot create a cycle (a document has no children)
		// and are deliberately left concurrent.
		//
		// The repository lock is taken FIRST and released LAST, so its ordering relative to the
		// per-object locks is fixed and cannot deadlock against them.
		Content moving = contentService.getContent(repositoryId, movingId);
		exceptionService.objectNotFound(DomainType.OBJECT, moving, movingId);
		Lock hierarchyLock = (moving != null && moving.isFolder())
				? threadLockService.getWriteLock(repositoryId, FOLDER_HIERARCHY_LOCK_KEY) : null;
		Lock firstLock = threadLockService.getWriteLock(repositoryId,
				movingId.compareTo(targetFolderId) <= 0 ? movingId : targetFolderId);
		Lock secondLock = threadLockService.getWriteLock(repositoryId,
				movingId.compareTo(targetFolderId) <= 0 ? targetFolderId : movingId);
		try {
			if (hierarchyLock != null) {
				hierarchyLock.lock();
			}
			firstLock.lock();
			secondLock.lock();
			// //////////////////
			// General Exception
			// //////////////////
			// Re-read UNDER the locks: the pre-lock read above only decided whether this is a
			// folder move. Anything the guard depends on must come from a state no concurrent
			// move can still change.
			Content content = contentService.getContent(repositoryId, movingId);
			exceptionService.objectNotFound(DomainType.OBJECT, content, movingId);
			Folder source = contentService.getFolder(repositoryId, sourceFolderId);
			exceptionService.objectNotFound(DomainType.OBJECT, source, sourceFolderId);
			Folder target = contentService.getFolder(repositoryId, targetFolderId);
			exceptionService.objectNotFound(DomainType.OBJECT, target, targetFolderId);
			if (content.isFolder()) {
				rejectMoveIntoOwnSubtree(repositoryId, movingId, target);
			}
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_MOVE_OBJECT, content);
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_MOVE_SOURCE, source);
			exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_MOVE_TARGET, target);
			exceptionService.nameConstraintViolation(repositoryId, target, content.getName());

			// //////////////////
			// Body of the method
			// //////////////////
			contentService.move(callContext, repositoryId, content, target);

			nemakiCachePool.get(repositoryId).removeCmisCache(content.getId());

			// ACL-in-Solr: refresh the readers of inheriting descendants of the
			// moved folder (their inherited ACL changed with the new ancestor
			// chain); the moved object itself is refreshed by ContentServiceImpl.move.
			// Resolved lazily to avoid a construction-time dependency cycle.
			try {
				jp.aegif.nemaki.cmis.service.AclService aclService =
						jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
								.getBean("AclService", jp.aegif.nemaki.cmis.service.AclService.class);
				aclService.refreshMovedSubtreeSearchIndexAcl(repositoryId, content);
			} catch (Exception e) {
				log.warn("moveObject: descendant readers refresh failed: " + e.getMessage());
			}

			// Invalidate IN_TREE folder hierarchy cache (folder may have been moved)
			solrUtil.invalidateFolderHierarchyCache(repositoryId);
		} finally {
			secondLock.unlock();
			firstLock.unlock();
			if (hierarchyLock != null) {
				hierarchyLock.unlock();
			}
		}
	}

	/**
	 * Pseudo-object id under which folder moves serialise per repository. Not a real object, so
	 * it can never collide with one — object ids are hex, this is not.
	 */
	private static final String FOLDER_HIERARCHY_LOCK_KEY = "__folder-hierarchy__";

	/**
	 * Refuse to move {@code movingId} underneath itself.
	 *
	 * <p>Walks from the destination up to the root. The walk carries its own visited set and hop
	 * budget because the store may ALREADY contain a cycle (nothing prevented one before this
	 * guard existed) — a guard that hangs on the damage it is meant to prevent is worse than none.
	 * Hitting either bound is reported as a constraint violation rather than ignored: the move
	 * cannot be shown to be safe, so it does not proceed.
	 */
	private void rejectMoveIntoOwnSubtree(String repositoryId, String movingId, Folder target) {
		java.util.Set<String> visited = new java.util.HashSet<>();
		Content cursor = target;
		int hops = 0;
		while (cursor != null) {
			if (movingId.equals(cursor.getId())) {
				exceptionService.constraint(movingId,
						"Cannot move a folder into its own subtree (destination is a descendant)");
			}
			if (!visited.add(cursor.getId())) {
				exceptionService.constraint(movingId,
						"Cannot verify the move: the folder hierarchy above the destination"
								+ " contains a cycle");
			}
			if (++hops > jp.aegif.nemaki.acl.AclSemantics.MAX_ANCESTOR_HOPS) {
				exceptionService.constraint(movingId,
						"Cannot verify the move: the folder hierarchy above the destination is"
								+ " deeper than " + jp.aegif.nemaki.acl.AclSemantics.MAX_ANCESTOR_HOPS
								+ " levels");
			}
			String parentId = cursor.getParentId();
			if (parentId == null) {
				return;
			}
			cursor = contentService.getFolder(repositoryId, parentId);
		}
	}

	@Override
	public void deleteObject(CallContext callContext, String repositoryId, String objectId, Boolean allVersions,
			ExtensionsData extension) {

		// Check if the object is a folder before deletion (for cache invalidation)
		Content content = contentService.getContent(repositoryId, objectId);
		boolean isFolder = (content instanceof Folder);

		objectServiceInternal.deleteObjectInternal(callContext, repositoryId, objectId, allVersions, false);

		// Invalidate IN_TREE folder hierarchy cache when a folder is deleted
		if (isFolder) {
			solrUtil.invalidateFolderHierarchyCache(repositoryId);
		}
	}

	@Override
	public FailedToDeleteData deleteTree(CallContext callContext, String repositoryId, String folderId,
			Boolean allVersions, UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("objectId", folderId);
		Folder folder = contentService.getFolder(repositoryId, folderId);

		// Null check BEFORE permissionDenied to avoid NPE on content.getId()
		if (folder == null)
			exceptionService.constraint(folderId, "deleteTree cannot be invoked on a non-folder object");

		exceptionService.permissionDenied(callContext, repositoryId, PermissionMapping.CAN_DELETE_TREE_FOLDER, folder);
		exceptionService.constraintDeleteRootFolder(repositoryId, folderId);

		// Reject deletion of the .system folder itself
		if (isSystemProtected(folder)) {
			log.warn("[deleteTree] Rejected: attempt to delete system-protected folder: id=" + folder.getId()
				+ ", name=" + folder.getName());
			exceptionService.constraint(folderId,
				"Cannot delete the system folder '" + folder.getName() + "' — it contains essential system objects (users, groups)");
		}

		// //////////////////
		// Body of the method
		// //////////////////
		// BTL-005: DFS post-order traversal — process children before parent
		// without materializing the entire tree in memory.
		List<String> failedIds = new ArrayList<>();
		deleteTreeDFS(callContext, repositoryId, folder, allVersions, failedIds);

		// Delete folder from Solr index
		if (folder != null) {
			solrUtil.deleteDocument(repositoryId, folder.getId());
		}

		// Invalidate IN_TREE folder hierarchy cache
		solrUtil.invalidateFolderHierarchyCache(repositoryId);

		FailedToDeleteDataImpl fdd = new FailedToDeleteDataImpl();
		fdd.setIds(failedIds);

		if (!failedIds.isEmpty()) {
			log.warn("[deleteTree] Orphan objects detected: folderId=" + folder.getId()
				+ ", folderName=" + folder.getName() + ", orphanIds=" + failedIds
				+ ", action=Check archive management or query CouchDB directly for cleanup");
		}

		return fdd;
	}

	/**
	 * BTL-005: DFS post-order deletion — recursively delete children before the parent.
	 * Only the current folder's children are loaded at each recursion level,
	 * so memory usage is proportional to tree depth × average branching factor,
	 * not to total tree size.
	 *
	 * Within each folder, non-folder children are deleted in parallel using the
	 * shared executor (BTL-006), while sub-folders are processed recursively first.
	 *
	 * System-protected objects are never deleted:
	 * - The ".system" folder (and all its descendants: users, groups, user/group items)
	 * - Objects of type "nemaki:user" or "nemaki:group" (safety net)
	 */
	private void deleteTreeDFS(CallContext callContext, String repositoryId,
			Content node, Boolean allVersions, List<String> failedIds) {
		if (node.isFolder()) {
			List<Content> children = contentService.getChildren(repositoryId, node.getId());
			if (CollectionUtils.isNotEmpty(children)) {
				// Separate sub-folders (recurse) from non-folders (parallel delete)
				List<Content> subFolders = new ArrayList<>();
				List<Content> nonFolders = new ArrayList<>();
				for (Content child : children) {
					if (isSystemProtected(child)) {
						log.info("[deleteTree] Skipping system-protected object: id=" + child.getId()
							+ ", name=" + child.getName() + ", objectType=" + child.getObjectType());
						failedIds.add(child.getId());
						continue;
					}
					if (child.isFolder()) {
						subFolders.add(child);
					} else {
						nonFolders.add(child);
					}
				}

				// Recurse into sub-folders first (post-order)
				for (Content subFolder : subFolders) {
					deleteTreeDFS(callContext, repositoryId, subFolder, allVersions, failedIds);
				}

				// Delete non-folder children in parallel using deleteTree executor
				if (!nonFolders.isEmpty()) {
					List<Future<String>> futures = new ArrayList<>();
					for (Content child : nonFolders) {
						futures.add(getDeleteTreeExecutor().submit(() -> {
							try {
								objectServiceInternal.deleteObjectInternal(callContext, repositoryId, child, allVersions, true);
								return null; // success
							} catch (Exception e) {
								return child.getId(); // failure
							}
						}));
					}
					for (Future<String> f : futures) {
						try {
							String failedId = f.get();
							if (failedId != null) {
								failedIds.add(failedId);
							}
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						} catch (ExecutionException e) {
							// should not happen since we catch in the callable
						}
					}
				}
			}
		}

		// Delete the node itself (folder after its children, or non-folder leaf)
		try {
			objectServiceInternal.deleteObjectInternal(callContext, repositoryId, node, allVersions, true);
		} catch (Exception e) {
			failedIds.add(node.getId());
		}
	}

	/**
	 * Returns true if the given content object is a system-protected object
	 * that must never be deleted by deleteTree.
	 *
	 * Protected objects:
	 * - The ".system" folder (prevents recursion into users/groups)
	 * - Items of type "nemaki:user" or "nemaki:group" (safety net)
	 */
	private boolean isSystemProtected(Content content) {
		if (content == null) return false;

		// Skip the ".system" folder — prevents entire system subtree deletion
		if (".system".equals(content.getName()) && content.isFolder()) {
			return true;
		}

		// Safety net: never delete user or group items via deleteTree
		String objectType = content.getObjectType();
		if ("nemaki:user".equals(objectType) || "nemaki:group".equals(objectType)) {
			return true;
		}

		return false;
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

	public void setWebhookService(WebhookService webhookService) {
		this.webhookService = webhookService;
	}

	/**
	 * Lazily create deleteTree executor using the Spring-injected threadMax setting.
	 * Double-checked locking ensures thread safety and single initialization.
	 */
	private ExecutorService getDeleteTreeExecutor() {
		ExecutorService executor = deleteTreeExecutor;
		if (executor != null) {
			return executor;
		}
		synchronized (treeExecutorLock) {
			executor = deleteTreeExecutor;
			if (executor != null) {
				return executor;
			}
			int maxThreads = threadMax > 0 ? threadMax : Runtime.getRuntime().availableProcessors();
			// corePoolSize = maxThreads so threads are eagerly created for parallel tree ops.
			// allowCoreThreadTimeOut ensures idle threads are reclaimed after 60s.
			java.util.concurrent.ThreadPoolExecutor tpe = new java.util.concurrent.ThreadPoolExecutor(
				maxThreads, maxThreads,
				60L, TimeUnit.SECONDS,
				new java.util.concurrent.LinkedBlockingQueue<>(1024),
				Thread.ofVirtual().name("deleteTree-vt-", 0).factory(),
				new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
			tpe.allowCoreThreadTimeOut(true);
			executor = tpe;
			deleteTreeExecutor = executor;
			log.info("deleteTree executor initialized with maxThreads=" + maxThreads + " (thread.max=" + threadMax + ")");
			return executor;
		}
	}

	/**
	 * Lazily create bulkUpdateProperties executor using the Spring-injected threadMax setting.
	 * Separate from deleteTree executor to prevent thread starvation under concurrent use.
	 */
	private ExecutorService getBulkUpdateExecutor() {
		ExecutorService executor = bulkUpdateExecutor;
		if (executor != null) {
			return executor;
		}
		synchronized (bulkUpdateExecutorLock) {
			executor = bulkUpdateExecutor;
			if (executor != null) {
				return executor;
			}
			int maxThreads = threadMax > 0 ? threadMax : Runtime.getRuntime().availableProcessors();
			java.util.concurrent.ThreadPoolExecutor tpe = new java.util.concurrent.ThreadPoolExecutor(
				maxThreads, maxThreads,
				60L, TimeUnit.SECONDS,
				new java.util.concurrent.LinkedBlockingQueue<>(1024),
				Thread.ofVirtual().name("bulkUpdate-vt-", 0).factory(),
				new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
			tpe.allowCoreThreadTimeOut(true);
			executor = tpe;
			bulkUpdateExecutor = executor;
			log.info("bulkUpdate executor initialized with maxThreads=" + maxThreads + " (thread.max=" + threadMax + ")");
			return executor;
		}
	}

	/**
	 * Shutdown hook for executors.
	 * Called by Spring via destroy-method="destroy".
	 */
	public void destroy() {
		shutdownExecutor(deleteTreeExecutor, "deleteTree");
		shutdownExecutor(bulkUpdateExecutor, "bulkUpdate");
	}

	private void shutdownExecutor(ExecutorService executor, String name) {
		if (executor != null) {
			executor.shutdown();
			try {
				if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
					log.warn(name + " executor did not terminate within timeout, forcing shutdown. "
						+ "Active tasks may be interrupted.");
					executor.shutdownNow();
				}
			} catch (InterruptedException e) {
				log.warn(name + " executor shutdown interrupted, forcing shutdown");
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}
}
