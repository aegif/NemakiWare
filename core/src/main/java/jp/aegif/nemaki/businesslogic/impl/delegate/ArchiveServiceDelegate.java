package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.exception.ParentNoLongerExistException;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.constant.NodeType;
import jp.aegif.nemaki.util.constant.PrincipalId;

import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Delegate for Archive-related business logic operations.
 * Extracted from ContentServiceImpl as part of class decomposition.
 *
 * Handles archive CRUD, restore/destroy operations, and retention lifecycle.
 */
public class ArchiveServiceDelegate {

	private static final Logger log = LoggerFactory.getLogger(ArchiveServiceDelegate.class);

	private final ContentDaoService contentDaoService;
	private final ContentService contentService;
	private final Supplier<SolrUtil> solrUtilSupplier;
	private final BiConsumer<CallContext, NodeBase> signatureSetter;

	public ArchiveServiceDelegate(ContentDaoService contentDaoService, ContentService contentService,
			Supplier<SolrUtil> solrUtilSupplier, BiConsumer<CallContext, NodeBase> signatureSetter) {
		this.contentDaoService = contentDaoService;
		this.contentService = contentService;
		this.solrUtilSupplier = solrUtilSupplier;
		this.signatureSetter = signatureSetter;
	}

	// ///////////////////////////////////////
	// Archive CRUD
	// ///////////////////////////////////////

	public List<Archive> getAllArchives(String repositoryId) {
		return contentDaoService.getAllArchives(repositoryId);
	}

	public List<Archive> getArchives(String repositoryId, Integer skip, Integer limit, Boolean desc) {
		return contentDaoService.getArchives(repositoryId, skip, limit, desc);
	}

	public List<Archive> getArchivesByCreator(String repositoryId, String creator) {
		return contentDaoService.getArchivesByCreator(repositoryId, creator);
	}

	public List<Archive> getArchivesByArchivedBy(String repositoryId, String archivedBy) {
		return contentDaoService.getArchivesByArchivedBy(repositoryId, archivedBy);
	}

	public Archive getArchive(String repositoryId, String archiveId) {
		return contentDaoService.getArchive(repositoryId, archiveId);
	}

	public Archive getArchiveByOriginalId(String repositoryId, String originalId) {
		return contentDaoService.getArchiveByOriginalId(repositoryId, originalId);
	}

	public Archive createArchive(CallContext callContext, String repositoryId, String objectId,
			Boolean deletedWithParent) {
		Content content = contentService.getContent(repositoryId, objectId);

		Archive a = buildArchiveBase(callContext, repositoryId, content, deletedWithParent);

		// Set Document archive specific info
		if (content.isDocument()) {
			Document document = (Document) content;
			a.setAttachmentNodeId(document.getAttachmentNodeId());
			a.setVersionSeriesId(document.getVersionSeriesId());
			a.setIsLatestVersion(document.isLatestVersion());

			// Set mimeType and contentStreamLength from AttachmentNode
			if (document.getAttachmentNodeId() != null) {
				try {
					AttachmentNode attachment = contentDaoService.getAttachment(repositoryId, document.getAttachmentNodeId());
					if (attachment != null) {
						a.setMimeType(attachment.getMimeType());
						// Use actual content size from CouchDB (not metadata which may be stale/compressed)
						Long actualSize = getAttachmentActualSize(repositoryId, document.getAttachmentNodeId());
						if (actualSize != null && actualSize >= 0) {
							a.setContentStreamLength(actualSize);
						} else {
							a.setContentStreamLength(attachment.getLength());
						}
					}
				} catch (Exception e) {
					log.warn("Failed to get attachment info for archive: {}", e.getMessage());
				}
			}
		}

		return contentDaoService.createArchive(repositoryId, a, deletedWithParent);
	}

	public Archive createArchive(CallContext callContext, String repositoryId, String objectId,
			Boolean deletedWithParent, String mimeType, Long contentStreamLength) {
		Content content = contentService.getContent(repositoryId, objectId);

		Archive a = buildArchiveBase(callContext, repositoryId, content, deletedWithParent);

		// Set Document archive specific info
		if (content.isDocument()) {
			Document document = (Document) content;
			a.setAttachmentNodeId(document.getAttachmentNodeId());
			a.setVersionSeriesId(document.getVersionSeriesId());
			a.setIsLatestVersion(document.isLatestVersion());

			if (mimeType != null) {
				a.setMimeType(mimeType);
			}
			if (contentStreamLength != null) {
				a.setContentStreamLength(contentStreamLength);
			}
		}

		return contentDaoService.createArchive(repositoryId, a, deletedWithParent);
	}

	/**
	 * Build common archive base fields shared by both createArchive overloads.
	 */
	private Archive buildArchiveBase(CallContext callContext, String repositoryId,
			Content content, Boolean deletedWithParent) {
		Archive a = new Archive();

		a.setOriginalId(content.getId());
		a.setName(content.getName());
		a.setType(content.getType());
		a.setDeletedWithParent(deletedWithParent);
		a.setParentId(content.getParentId());
		signatureSetter.accept(callContext, a);

		// Preserve the original document creator as the archive owner.
		a.setCreator(content.getCreator());

		// Record who actually performed the deletion.
		a.setArchivedBy(callContext.getUsername());

		// Set retention lifecycle fields
		a.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);
		a.setArchivedAt(new GregorianCalendar());

		// Snapshot ACL for archived content access control
		try {
			if (content.getAcl() != null) {
				com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
				a.setAclSnapshot(mapper.writeValueAsString(content.getAcl()));
			}
		} catch (Exception e) {
			log.warn("Failed to snapshot ACL for archive: {}", e.getMessage());
		}

		// Set path (calculate from folder hierarchy)
		try {
			String path = contentService.calculatePath(repositoryId, content);
			a.setPath(path);
		} catch (Exception e) {
			log.warn("Failed to calculate path for archive: {}", e.getMessage());
		}

		return a;
	}

	public Archive createAttachmentArchive(CallContext callContext, String repositoryId, String attachmentId) {
		Archive a = new Archive();
		a.setDeletedWithParent(true);
		a.setOriginalId(attachmentId);
		a.setType(NodeType.ATTACHMENT.value());
		signatureSetter.accept(callContext, a);

		return contentDaoService.createAttachmentArchive(repositoryId, a);
	}

	// ///////////////////////////////////////
	// Restore
	// ///////////////////////////////////////

	public void restoreArchive(String repositoryId, String archiveId) throws ParentNoLongerExistException {
		Archive archive = contentDaoService.getArchive(repositoryId, archiveId);
		if (archive == null) {
			throw new CmisObjectNotFoundException("restoreArchive: archive does not exist, archiveId=" + archiveId);
		}

		if (!restorationTargetExists(repositoryId, archive)) {
			log.error("The destination of the restoration doesn't exist");
			throw new ParentNoLongerExistException();
		}

		CallContextImpl dummyContext = new CallContextImpl(null, CmisVersion.CMIS_1_1, null, null, null, null, null,
				null);
		dummyContext.put(dummyContext.USERNAME, PrincipalId.SYSTEM_IN_DB);

		Content restored = null;
		if (archive.isFolder()) {
			restored = restoreFolder(repositoryId, archive);
		} else if (archive.isDocument()) {
			restored = restoreDocument(repositoryId, archive);
		} else if (archive.isAttachment()) {
			throw new CmisNotSupportedException("restoreArchive: attachment cannot be restored alone, archiveId=" + archiveId);
		} else {
			throw new CmisNotSupportedException("restoreArchive: unsupported archive type for restoration, archiveId=" + archiveId);
		}

		if (restored != null) {
			contentService.writeChangeEvent(dummyContext, repositoryId, restored, null, ChangeType.CREATED);

			try {
				SolrUtil solrUtil = solrUtilSupplier.get();
				if (solrUtil != null) {
					solrUtil.indexDocument(repositoryId, restored);
				}
			} catch (Exception e) {
				log.warn("restoreArchive: Solr indexing failed for " + restored.getId() + ": " + e.getMessage());
			}
		}
	}

	private Document restoreDocument(String repositoryId, Archive archive) {
		String versionSeriesId = archive.getVersionSeriesId();
		if (versionSeriesId == null) {
			throw new CmisRuntimeException("restoreDocument: archive has no versionSeriesId, archiveId=" + archive.getId());
		}

		List<Archive> versions = contentDaoService.getArchivesOfVersionSeries(repositoryId, versionSeriesId);

		VersionSeries vs = contentDaoService.getVersionSeries(repositoryId, versionSeriesId);
		if (vs == null) {
			log.info("restoreDocument: VersionSeries {} not found, recreating it", versionSeriesId);
			contentDaoService.restoreVersionSeries(repositoryId, versionSeriesId);
			vs = contentDaoService.getVersionSeries(repositoryId, versionSeriesId);
		}

		for (Archive version : versions) {
			contentDaoService.restoreDocumentWithArchive(repositoryId, version);
			contentDaoService.deleteDocumentArchive(repositoryId, version.getId());
		}

		// After restoring all versions, clean up checkout state
		List<Document> restoredVersions = contentDaoService.getAllVersions(repositoryId, versionSeriesId);
		if (CollectionUtils.isNotEmpty(restoredVersions)) {
			for (Document version : restoredVersions) {
				if (version.isPrivateWorkingCopy()) {
					contentDaoService.delete(repositoryId, version.getAttachmentNodeId());
					contentDaoService.delete(repositoryId, version.getId());
				} else if (version.isVersionSeriesCheckedOut()) {
					version.setVersionSeriesCheckedOut(false);
					version.setVersionSeriesCheckedOutBy(null);
					version.setVersionSeriesCheckedOutId(null);
					contentDaoService.update(repositoryId, version);
				}
			}
		}
		if (vs != null && vs.isVersionSeriesCheckedOut()) {
			vs.setVersionSeriesCheckedOut(false);
			vs.setVersionSeriesCheckedOutBy(null);
			vs.setVersionSeriesCheckedOutId(null);
			contentDaoService.update(repositoryId, vs);
		}

		return contentService.getDocument(repositoryId, archive.getOriginalId());
	}

	private Folder restoreFolder(String repositoryId, Archive archive) throws ParentNoLongerExistException {
		contentDaoService.restoreContent(repositoryId, archive);

		List<Archive> children = contentDaoService.getChildArchives(repositoryId, archive);
		if (children != null) {
			for (Archive child : children) {
				if (child.isDeletedWithParent()) {
					restoreArchive(repositoryId, child.getId());
				}
			}
		}
		String deletedArchiveId = contentDaoService.deleteArchive(repositoryId, archive.getId());
		if (deletedArchiveId == null) {
			throw new CmisRuntimeException("restoreFolder: folder archive record deletion failed after restoration, archiveId=" + archive.getId());
		}

		return contentService.getFolder(repositoryId, archive.getOriginalId());
	}

	private Boolean restorationTargetExists(String repositoryId, Archive archive) {
		String parentId = archive.getParentId();
		Content parent = contentDaoService.getContent(repositoryId, parentId);
		return parent != null;
	}

	// ///////////////////////////////////////
	// Destroy
	// ///////////////////////////////////////

	public void destroyArchive(String repositoryId, String archiveId) {
		Archive archive = contentDaoService.getArchive(repositoryId, archiveId);
		if (archive == null) {
			throw new CmisObjectNotFoundException("destroyArchive: archive does not exist, archiveId=" + archiveId);
		}

		if (archive.isFolder()) {
			destroyFolder(repositoryId, archive);
		} else if (archive.isDocument()) {
			destroyDocument(repositoryId, archive);
		} else if (archive.isAttachment()) {
			throw new CmisNotSupportedException("destroyArchive: attachment cannot be destroyed alone, archiveId=" + archiveId);
		} else {
			String deletedId = contentDaoService.deleteArchive(repositoryId, archiveId);
			if (deletedId == null) {
				throw new CmisRuntimeException("destroyArchive: deletion failed for cmis:item archive, archiveId=" + archiveId);
			}
		}
	}

	private void destroyFolder(String repositoryId, Archive archive) {
		List<Archive> children = contentDaoService.getChildArchives(repositoryId, archive);
		if (CollectionUtils.isNotEmpty(children)) {
			for (Archive child : children) {
				destroyArchive(repositoryId, child.getId());
			}
		}
		String deletedArchiveId = contentDaoService.deleteArchive(repositoryId, archive.getId());
		if (deletedArchiveId == null) {
			throw new CmisRuntimeException("destroyFolder: folder archive deletion failed, archiveId=" + archive.getId());
		}
	}

	private void destroyDocument(String repositoryId, Archive archive) {
		String versionSeriesId = archive.getVersionSeriesId();
		if (versionSeriesId == null) {
			throw new CmisRuntimeException("destroyDocument: archive has no versionSeriesId, archiveId=" + archive.getId());
		}

		List<Archive> versions = contentDaoService.getArchivesOfVersionSeries(repositoryId, versionSeriesId);

		if (versions == null) {
			throw new CmisRuntimeException("destroyDocument: getArchivesOfVersionSeries returned null, versionSeriesId=" + versionSeriesId);
		}
		if (versions.isEmpty()) {
			throw new CmisRuntimeException("destroyDocument: no versions found, versionSeriesId=" + versionSeriesId);
		}

		for (Archive version : versions) {
			Archive attachmentArchive = contentDaoService.getAttachmentArchive(repositoryId, version);

			if (attachmentArchive != null) {
				String deletedAttachmentId = contentDaoService.deleteArchive(repositoryId, attachmentArchive.getId());
				if (deletedAttachmentId == null) {
					log.warn("destroyDocument: attachment archive deletion returned null, attachmentId=" + attachmentArchive.getId());
				}
			} else {
				log.warn("destroyDocument: attachment archive not found, versionId=" + version.getId());
			}

			String deletedVersionId = contentDaoService.deleteArchive(repositoryId, version.getId());
			if (deletedVersionId == null) {
				throw new CmisRuntimeException("destroyDocument: version archive deletion returned null, versionId=" + version.getId());
			}
		}
	}

	// ///////////////////////////////////////
	// Retention lifecycle
	// ///////////////////////////////////////

	public List<Archive> getArchivesByState(String repositoryId, String state) {
		return contentDaoService.getArchivesByState(repositoryId, state);
	}

	public List<Archive> getSearchableArchives(String repositoryId, String state) {
		return contentDaoService.getSearchableArchives(repositoryId, state);
	}

	public List<Archive> getSearchableArchivesPaged(String repositoryId, int skip, int limit, boolean descending) {
		return contentDaoService.getSearchableArchivesPaged(repositoryId, skip, limit, descending);
	}

	public long getSearchableArchivesCount(String repositoryId) {
		return contentDaoService.getSearchableArchivesCount(repositoryId);
	}

	public List<Archive> getSearchableArchivesByStatePaged(String repositoryId, String state, int skip, int limit, boolean descending) {
		return contentDaoService.getSearchableArchivesByStatePaged(repositoryId, state, skip, limit, descending);
	}

	public long getSearchableArchivesByStateCount(String repositoryId, String state) {
		return contentDaoService.getSearchableArchivesByStateCount(repositoryId, state);
	}

	public List<Archive> getArchivesForColdTransition(String repositoryId, GregorianCalendar beforeDate) {
		return contentDaoService.getArchivesForColdTransition(repositoryId, beforeDate);
	}

	public void updateArchiveState(String repositoryId, String archiveId,
			String newState, Map<String, String> contentRef, GregorianCalendar coldArchivedAt) {
		contentDaoService.updateArchiveState(repositoryId, archiveId, newState, contentRef, coldArchivedAt);
	}

	public java.io.InputStream getArchiveContentStream(String repositoryId, String archiveId) {
		Archive archive = contentDaoService.getArchive(repositoryId, archiveId);
		if (archive == null) {
			return null;
		}
		return contentDaoService.getArchiveContentStream(repositoryId, archive);
	}

	public boolean deleteArchiveContent(String repositoryId, String archiveId) {
		Archive archive = contentDaoService.getArchive(repositoryId, archiveId);
		if (archive == null) {
			return false;
		}
		return contentDaoService.deleteArchiveContent(repositoryId, archive);
	}

	public List<String> getExpiredDocumentIds(String repositoryId, GregorianCalendar beforeDate) {
		return contentDaoService.getExpiredDocumentIds(repositoryId, beforeDate);
	}

	public List<Content> getExpiredDocuments(String repositoryId, GregorianCalendar beforeDate) {
		List<String> ids = getExpiredDocumentIds(repositoryId, beforeDate);
		List<Content> results = new java.util.ArrayList<>();
		for (String id : ids) {
			Content c = contentService.getContent(repositoryId, id);
			if (c != null) {
				results.add(c);
			}
		}
		return results;
	}

	public void updateExpirationDate(String repositoryId, String objectId, GregorianCalendar newDate) {
		Content content = contentService.getContent(repositoryId, objectId);
		if (content == null) {
			throw new org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException(
					"Document not found: " + objectId);
		}

		List<jp.aegif.nemaki.model.Property> subTypeProps = content.getSubTypeProperties();
		if (subTypeProps == null) {
			subTypeProps = new java.util.ArrayList<>();
		}

		boolean found = false;
		for (jp.aegif.nemaki.model.Property prop : subTypeProps) {
			if ("cmis:rm_expirationDate".equals(prop.getKey())) {
				prop.setValue(newDate.getTimeInMillis());
				found = true;
				break;
			}
		}
		if (!found) {
			jp.aegif.nemaki.model.Property newProp = new jp.aegif.nemaki.model.Property();
			newProp.setKey("cmis:rm_expirationDate");
			newProp.setValue(newDate.getTimeInMillis());
			subTypeProps.add(newProp);
		}
		content.setSubTypeProperties(subTypeProps);
		contentDaoService.update(repositoryId, content);
	}

	public void updateArchiveColdMoveMode(String repositoryId, String archiveId, String coldMoveMode) {
		contentDaoService.updateArchiveColdMoveMode(repositoryId, archiveId, coldMoveMode);
	}

	public Long getAttachmentActualSize(String repositoryId, String attachmentId) {
		try {
			Long actualSize = contentDaoService.getAttachmentActualSize(repositoryId, attachmentId);
			if (actualSize != null && actualSize >= 0) {
				log.debug("Retrieved actual attachment size from CouchDB: " + actualSize + " bytes for attachment " + attachmentId);
				return actualSize;
			}

			log.warn("Could not retrieve actual attachment size from CouchDB for attachment: " + attachmentId);
			return null;

		} catch (Exception e) {
			log.error("Error retrieving actual attachment size for " + attachmentId + ": " + e.getMessage(), e);
			return null;
		}
	}
}
