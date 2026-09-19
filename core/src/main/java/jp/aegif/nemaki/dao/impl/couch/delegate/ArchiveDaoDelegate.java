package jp.aegif.nemaki.dao.impl.couch.delegate;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;
import com.ibm.cloud.sdk.core.service.exception.ServiceResponseException;

import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Archive;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.couch.CouchArchive;
import jp.aegif.nemaki.model.couch.CouchVersionSeries;

/**
 * Delegate for Archive DAO operations.
 * Extracted from ContentDaoServiceImpl as part of class decomposition.
 */
public class ArchiveDaoDelegate {

	private static final Log log = LogFactory.getLog(ArchiveDaoDelegate.class);

	private final CloudantClientPool connectorPool;
	private final RepositoryInfoMap repositoryInfoMap;
	private final DaoHelper daoHelper;

	public ArchiveDaoDelegate(CloudantClientPool connectorPool, RepositoryInfoMap repositoryInfoMap, DaoHelper daoHelper) {
		this.connectorPool = connectorPool;
		this.repositoryInfoMap = repositoryInfoMap;
		this.daoHelper = daoHelper;
	}

	// ///////////////////////////////////////
	// Archive
	// ///////////////////////////////////////
	public Archive getArchive(String repositoryId, String objectId) {
		String archive = repositoryInfoMap.getArchiveId(repositoryId);
		CouchArchive ca = connectorPool.get(archive).get(CouchArchive.class, objectId);
		if (ca == null) {
			return null;
		}
		return ca.convert();
	}

	public Archive getArchiveByOriginalId(String repositoryId, String originalId) {
		try {
			// Query 'all' view with originalId (all view emits doc.originalId as key)
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "all", originalId, CouchArchive.class);

			if (couchArchives != null && !couchArchives.isEmpty()) {
				// Return the first (and should be only) result
				return couchArchives.get(0).convert();
			}

			return null;
		} catch (Exception e) {
			// Null is "no archive exists for this original" — the answer tombstone
			// resolution DELETES catalog entities on, and deleteDocumentArchive orphans
			// attachment archives on. A failed lookup is neither.
			log.error("Error getting archive by original ID: " + originalId + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the archive for original '" + originalId + "' in '"
					+ repositoryId + "' could not be looked up; this is NOT a finding that"
					+ " none exists", e);
		}
	}

	/**
	 * The three answers a lookup for an archived attachment can give.
	 *
	 * <h2>Why this is not just {@code null}</h2>
	 *
	 * <p>It used to be. One {@code null} meant all three of "this document never had an
	 * attachment", "it names one but no archive row exists" and "the lookup failed" — and the
	 * caller dereferenced it, so all three became an NPE that was rethrown as "Failed to restore
	 * attachment". That reported a failure for the first case, which is not one (found by the
	 * first restore drill, 2026-08-24).
	 *
	 * <p>Guarding the dereference fixed that case and made the OTHER two silently succeed:
	 * an unreadable archive database, or a document whose attachment row is missing, would
	 * restore "successfully" without its content. That is the same collapse of "absent" into
	 * "could not read" the roadmap holds as the DAO fail-fast work (§2-1) — reproduced at the
	 * restore door by the guard that was supposed to fix it (external review).
	 */
	public sealed interface AttachmentArchiveLookup {
		/** The document names no attachment. There is nothing to restore, and that is normal. */
		record None() implements AttachmentArchiveLookup { }

		/** The document names an attachment whose archive row is missing, or the read failed. */
		record Unavailable(String reason) implements AttachmentArchiveLookup { }

		/** Found. */
		record Found(Archive archive) implements AttachmentArchiveLookup { }
	}

	/** As {@link #lookupAttachmentArchive}, but collapsing every answer to the archive or null. */
	public Archive getAttachmentArchive(String repositoryId, Archive archive) {
		AttachmentArchiveLookup lookup = lookupAttachmentArchive(repositoryId, archive);
		return lookup instanceof AttachmentArchiveLookup.Found found ? found.archive() : null;
	}

	/** Which of the three answers this lookup has — see {@link AttachmentArchiveLookup}. */
	public AttachmentArchiveLookup lookupAttachmentArchive(String repositoryId, Archive archive) {
		if (archive == null) {
			return new AttachmentArchiveLookup.Unavailable("no document archive was given");
		}
		try {
			// Use the archive's attachmentNodeId to find the attachment archive.
			// The "attachments" view emits by originalId, and attachmentNodeId is
			// the originalId of the attachment that was archived alongside the document.
			String attachmentNodeId = archive.getAttachmentNodeId();
			if (attachmentNodeId == null || attachmentNodeId.isEmpty()) {
				log.info("Archive " + archive.getId() + " names no attachment; nothing to restore");
				return new AttachmentArchiveLookup.None();
			}

			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "attachments", attachmentNodeId, CouchArchive.class);

			if (couchArchives != null && !couchArchives.isEmpty()) {
				return new AttachmentArchiveLookup.Found(couchArchives.get(0).convert());
			}

			// The document SAYS it has an attachment and the archive does not hold it. Restoring
			// the document without it and calling that success would hand back a document whose
			// content is gone, silently.
			return new AttachmentArchiveLookup.Unavailable("archive " + archive.getId()
					+ " names attachment " + attachmentNodeId + " but no archived attachment "
					+ "row exists for it");
		} catch (Exception e) {
			log.error("Error getting attachment archive for: " + archive.getId() + " in repository: " + repositoryId, e);
			return new AttachmentArchiveLookup.Unavailable("the attachment archive could not be "
					+ "read: " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	public List<Archive> getChildArchives(String repositoryId, Archive archive) {
		try {
			// Query 'children' view with archive ID (children view emits doc.parentId as key)
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "children", archive.getId(), CouchArchive.class);

			List<Archive> archives = new ArrayList<Archive>();
			if (couchArchives != null) {
				for (CouchArchive couchArchive : couchArchives) {
					archives.add(couchArchive.convert());
				}
			}

			return archives;
		} catch (Exception e) {
			log.error("Error getting child archives for: " + archive.getId() + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the child archives could not be read for '" + archive.getId() + "' in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public List<Archive> getArchivesOfVersionSeries(String repositoryId, String versionSeriesId) {
		try {
			// Query 'versionSeries' view with versionSeriesId (versionSeries view emits doc.versionSeriesId as key)
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "versionSeries", versionSeriesId, CouchArchive.class);

			List<Archive> archives = new ArrayList<Archive>();
			if (couchArchives != null) {
				for (CouchArchive couchArchive : couchArchives) {
					archives.add(couchArchive.convert());
				}
			}

			return archives;
		} catch (Exception e) {
			log.error("Error getting archives of version series: " + versionSeriesId + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the version-series archives could not be read for '" + versionSeriesId + "' in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public List<Archive> getAllArchives(String repositoryId) {
		try {
			// Query 'all' view without key to get all archives (all view emits doc.originalId as key)
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "all", null, CouchArchive.class);

			List<Archive> archives = new ArrayList<Archive>();
			if (couchArchives != null) {
				for (CouchArchive couchArchive : couchArchives) {
					archives.add(couchArchive.convert());
				}
			}

			return archives;
		} catch (Exception e) {
			log.error("Error getting all archives in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	/** Rows the most recent getArchives on THIS thread could not decode (view rows or nulls). */
	private final ThreadLocal<Integer> lastUnreadableArchives = ThreadLocal.withInitial(() -> 0);

	public int lastUnreadableArchiveCount() {
		return lastUnreadableArchives.get();
	}

	public List<Archive> getArchives(String repositoryId, Integer skip, Integer limit, Boolean desc) {
		lastUnreadableArchives.set(0);
		try {
			// Query allByCreated view with pagination parameters (same as v2.4)
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();

			if (skip != null && skip > 0) {
				queryParams.put("skip", skip.longValue());
			}
			if (limit != null && limit > 0) {
				queryParams.put("limit", limit.longValue());
			}
			if (desc == null) {
				queryParams.put("descending", true);
			} else if (desc) {
				queryParams.put("descending", true);
			}

			ViewResult result = client.queryView("_repo", "allByCreated", queryParams);
			List<Archive> archives = new ArrayList<Archive>();

			if (result.getRows() == null) {
				// The door getChildren closed first: "answered without rows" is not "there are
				// no archives", and the caller diffs this list against a snapshot and deletes
				// by absence — an empty page here would reconcile the whole catalog away.
				throw new IllegalStateException("the archive view answered without rows for '"
						+ repositoryId + "'; that is not the same as there being no archives");
			}
			if (result.getRows() != null) {
				for (ViewResultRow row : result.getRows()) {
					// The 'allByCreated' view emits: emit(doc.created, doc)
					// So the document data is in getValue(), not getDoc() (which requires include_docs=true)
					Object docValue = row.getValue();
					if (docValue != null) {
						try {
							ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
							CouchArchive ca = mapper.convertValue(docValue, CouchArchive.class);
							if (ca != null) {
								archives.add(ca.convert());
							} else {
								lastUnreadableArchives.set(lastUnreadableArchives.get() + 1);
							}
						} catch (Exception e) {
							// COUNTED, not just logged. A row the view returned and this code
							// could not decode is an archive that exists; dropping it makes the
							// list shorter than the archive database, and the Purview sync
							// diffs that list against its snapshot — so a dropped row read as
							// a DELETED archive and was reconciled out of the catalog.
							lastUnreadableArchives.set(lastUnreadableArchives.get() + 1);
							log.warn("Failed to convert archive document (counted as unreadable,"
									+ " not as absent): " + e.getMessage());
						}
					} else {
						lastUnreadableArchives.set(lastUnreadableArchives.get() + 1);
					}
				}
			}

			return archives;
		} catch (Exception e) {
			// NOT an empty list. "The archive view could not be asked" and "there are no
			// archives" are different facts, and the callers of this list include a diff
			// that deletes by absence — the exact substitution getChildren stopped making.
			log.error("Error getting archives in repository: " + repositoryId, e);
			throw new IllegalStateException("the archive view could not be read for '"
					+ repositoryId + "'; this is NOT a finding that there are no archives", e);
		}
	}

	public List<Archive> getArchivesByCreator(String repositoryId, String creator) {
		try {
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			// Cloudant SDK auto-serializes key to JSON, so pass raw string without quotes
			queryParams.put("key", creator);

			lastUnreadableArchives.set(0);
			ViewResult result = client.queryView("_repo", "byCreator", queryParams);
			// The byArchivedBy standard, applied to its twin: this listing is UNIONed into
			// the non-admin trash, so a row dropped here is a document the user cannot find
			// or restore, presented inside a listing that claims to be complete.
			if (result == null || result.getRows() == null) {
				throw new IllegalStateException("the byCreator view answered without rows in '"
						+ repositoryId + "'; that is not the same as the trash being empty");
			}
			List<Archive> archives = new ArrayList<Archive>();
			int unreadable = 0;
			ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
			for (ViewResultRow row : result.getRows()) {
				// Use row.getDoc() instead of row.getValue() because the emit value
				// contains raw timestamps (long) that cannot be deserialized to GregorianCalendar.
				// includeDocs=true is set in queryView, so getDoc() returns the full document.
				com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
				if (doc == null) {
					unreadable++;
					continue;
				}
				try {
					Map<String, Object> docMap = doc.getProperties();
					if (docMap == null) {
						unreadable++;
						continue;
					}
					if (!docMap.containsKey("_id") && doc.getId() != null) {
						docMap.put("_id", doc.getId());
					}
					if (!docMap.containsKey("_rev") && doc.getRev() != null) {
						docMap.put("_rev", doc.getRev());
					}
					String jsonString = mapper.writeValueAsString(docMap);
					CouchArchive ca = mapper.readValue(jsonString, CouchArchive.class);
					if (ca != null) {
						archives.add(ca.convert());
					} else {
						unreadable++;
					}
				} catch (Exception e) {
					unreadable++;
					log.warn("Failed to convert archive document: " + e.getMessage());
				}
			}
			lastUnreadableArchives.set(unreadable);
			if (unreadable > 0) {
				throw new IllegalStateException(unreadable + " archive row(s) by creator could"
						+ " not be read in '" + repositoryId + "'; serving the remainder as"
						+ " the whole trash would hide restorable documents");
			}

			return archives;
		} catch (Exception e) {
			log.error("Error getting archives by creator in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives by creator could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public List<Archive> getArchivesByArchivedBy(String repositoryId, String archivedBy) {
		lastUnreadableArchives.set(0);
		try {
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			Map<String, Object> queryParams = new HashMap<String, Object>();
			// Cloudant SDK auto-serializes key to JSON, so pass raw string without quotes
			queryParams.put("key", archivedBy);

			ViewResult result = client.queryView("_repo", "byArchivedBy", queryParams);
			List<Archive> archives = new ArrayList<Archive>();

			if (result == null || result.getRows() == null) {
				// Same rule as getArchives: "answered without rows" is not "there are none".
				throw new IllegalStateException("the byArchivedBy view answered without rows"
						+ " for '" + repositoryId + "'; that is not the same as there being"
						+ " no archives");
			}
			{
				ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
				for (ViewResultRow row : result.getRows()) {
					com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
					if (doc == null) {
						lastUnreadableArchives.set(lastUnreadableArchives.get() + 1);
						continue;
					}
					{
						try {
							Map<String, Object> docMap = doc.getProperties();
							if (docMap != null) {
								if (!docMap.containsKey("_id") && doc.getId() != null) {
									docMap.put("_id", doc.getId());
								}
								if (!docMap.containsKey("_rev") && doc.getRev() != null) {
									docMap.put("_rev", doc.getRev());
								}
								String jsonString = mapper.writeValueAsString(docMap);
								CouchArchive ca = mapper.readValue(jsonString, CouchArchive.class);
								if (ca != null) {
									archives.add(ca.convert());
								} else {
									lastUnreadableArchives.set(lastUnreadableArchives.get() + 1);
								}
							} else {
								lastUnreadableArchives.set(lastUnreadableArchives.get() + 1);
							}
						} catch (Exception e) {
							// COUNTED, per the same-file rule getArchives set: a row that will
							// not decode is an archive that exists.
							lastUnreadableArchives.set(lastUnreadableArchives.get() + 1);
							log.warn("Failed to convert archive document (counted as"
									+ " unreadable, not as absent): " + e.getMessage());
						}
					}
				}
			}

			return archives;
		} catch (Exception e) {
			log.error("Error getting archives by archivedBy in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public Archive createArchive(String repositoryId, Archive archive, Boolean deletedWithParent) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);
		CloudantClientWrapper client = connectorPool.getClient(repositoryId);
		CloudantClientWrapper archiveClient = connectorPool.get(archiveId);

		// Read the full raw CouchDB document to preserve ALL fields
		// (versionLabel, majorVersion, secondaryIds, aspects, subTypeProperties, etc.)
		com.ibm.cloud.cloudant.v1.model.Document rawDoc = client.get(archive.getOriginalId());
		String lastRevision = rawDoc != null ? rawDoc.getRev() : null;

		// Build archive document from raw properties + archive metadata
		Map<String, Object> archiveMap = new HashMap<>();

		// Copy all original document properties (preserves versionLabel, secondaryIds, etc.)
		if (rawDoc != null) {
			Map<String, Object> originalProps = rawDoc.getProperties();
			if (originalProps != null) {
				archiveMap.putAll(originalProps);
			}
		}

		// Add archive-specific metadata (overwriting any conflicts)
		archiveMap.put("originalId", archive.getOriginalId());
		archiveMap.put("lastRevision", lastRevision);
		archiveMap.put("deletedWithParent", deletedWithParent);

		// Copy fields from the Archive model (set by ContentServiceImpl)
		if (archive.getName() != null) archiveMap.put("name", archive.getName());
		if (archive.getType() != null) archiveMap.put("type", archive.getType());
		if (archive.getParentId() != null) archiveMap.put("parentId", archive.getParentId());
		if (archive.getPath() != null) archiveMap.put("path", archive.getPath());
		if (archive.getAttachmentNodeId() != null) archiveMap.put("attachmentNodeId", archive.getAttachmentNodeId());
		if (archive.getVersionSeriesId() != null) archiveMap.put("versionSeriesId", archive.getVersionSeriesId());
		archiveMap.put("latestVersion", archive.isLatestVersion());
		if (archive.getMimeType() != null) archiveMap.put("mimeType", archive.getMimeType());
		archiveMap.put("contentStreamLength", archive.getContentStreamLength());
		if (archive.getCreator() != null) archiveMap.put("creator", archive.getCreator());
		if (archive.getModifier() != null) archiveMap.put("modifier", archive.getModifier());
		if (archive.getAclSnapshot() != null) archiveMap.put("aclSnapshot", archive.getAclSnapshot());
		if (archive.getArchiveState() != null) archiveMap.put("archiveState", archive.getArchiveState());
		if (archive.getArchivedAt() != null) archiveMap.put("archivedAt", archive.getArchivedAt().getTimeInMillis());
		if (archive.getArchivedBy() != null) archiveMap.put("archivedBy", archive.getArchivedBy());

		// Remove CouchDB internal fields (new doc will get its own _id/_rev)
		archiveMap.remove("_id");
		archiveMap.remove("_rev");
		archiveMap.remove("_attachments");

		// Write to archive DB
		archiveClient.create(archiveMap);

		// Return the archive model for caller
		CouchArchive ca = new CouchArchive(archive);
		ca.setLastRevision(lastRevision);
		return ca.convert();
	}

	public Archive createAttachmentArchive(String repositoryId, Archive archive) {
		String archiveId = repositoryInfoMap.getArchiveId(repositoryId);
		CloudantClientWrapper sourceClient = connectorPool.getClient(repositoryId);
		CloudantClientWrapper archiveClient = connectorPool.get(archiveId);

		// Read the full raw CouchDB attachment node to preserve ALL fields
		// (name, mimeType, length, actualLength, etc.)
		com.ibm.cloud.cloudant.v1.model.Document rawDoc = sourceClient.get(archive.getOriginalId());

		// Handle case where attachment was already deleted
		if (rawDoc == null) {
			log.warn(daoHelper.buildLogMsg(archive.getOriginalId(),
					"attachment no longer exists (may have been deleted by another version)"));
			CouchArchive ca = new CouchArchive(archive);
			archiveClient.create(ca);
			return ca.convert();
		}

		String lastRevision = rawDoc.getRev();

		// Build archive document from raw properties + archive metadata
		Map<String, Object> archiveMap = new HashMap<>();

		// Copy all original attachment node properties (preserves name, mimeType, length, etc.)
		Map<String, Object> originalProps = rawDoc.getProperties();
		if (originalProps != null) {
			archiveMap.putAll(originalProps);
		}

		// Add archive-specific metadata
		archiveMap.put("originalId", archive.getOriginalId());
		archiveMap.put("lastRevision", lastRevision);
		archiveMap.put("deletedWithParent", archive.isDeletedWithParent());
		if (archive.getName() != null) archiveMap.put("name", archive.getName());
		if (archive.getType() != null) archiveMap.put("type", archive.getType());
		if (archive.getParentId() != null) archiveMap.put("parentId", archive.getParentId());

		// Remove CouchDB internal fields
		archiveMap.remove("_id");
		archiveMap.remove("_rev");
		archiveMap.remove("_attachments");

		// Write to archive DB
		com.ibm.cloud.cloudant.v1.model.DocumentResult createResult = archiveClient.create(archiveMap);
		String archiveDocId = createResult != null ? createResult.getId() : null;

		// Copy binary content (_attachments) from source to archive
		// Use getAttachments() (not getProperties()) - Cloudant SDK extracts _attachments separately
		Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> sourceAttachments = rawDoc.getAttachments();
		boolean hasBinary = sourceAttachments != null && !sourceAttachments.isEmpty();
		try {
			Object binaryContent = sourceClient.getAttachment(archive.getOriginalId(), "content");
			if (binaryContent instanceof java.io.InputStream) {
				// getAttachment hands the caller a live connection. Every path out of this block
				// has to close it: an archiveDocId we never got, or a throw from the get/create
				// below, otherwise strands the connection until GC notices.
				try (java.io.InputStream body = (java.io.InputStream) binaryContent) {
					if (archiveDocId != null) {
						com.ibm.cloud.cloudant.v1.model.Document archiveDoc = archiveClient.get(archiveDocId);
						String archiveRev = archiveDoc != null ? archiveDoc.getRev() : null;
						// Use the original mimeType from the raw document
						Object mimeTypeObj = originalProps != null ? originalProps.get("mimeType") : null;
						String mimeType = mimeTypeObj instanceof String ? (String) mimeTypeObj : "application/octet-stream";
						if (mimeType.isEmpty()) mimeType = "application/octet-stream";
						archiveClient.createAttachment(archiveDocId, archiveRev, "content", body, mimeType);
						log.info("createAttachmentArchive: binary content copied to archive for " + archive.getOriginalId());
					}
				}
			}
		} catch (Exception e) {
			if (hasBinary) {
				throw new RuntimeException("createAttachmentArchive: failed to copy binary content for "
					+ archive.getOriginalId() + " (binary exists in source)", e);
			}
			log.info("createAttachmentArchive: no binary content to copy for " + archive.getOriginalId());
		}

		CouchArchive ca = new CouchArchive(archive);
		ca.setLastRevision(lastRevision);
		return ca.convert();
	}

	public String deleteArchive(String repositoryId, String archiveId) {
		String archive = repositoryInfoMap.getArchiveId(repositoryId);

		try {
			CouchArchive ca = connectorPool.get(archive).get(CouchArchive.class, archiveId);
			if (ca == null) {
				// get() returned null without throwing exception
				log.warn(daoHelper.buildLogMsg(archiveId, "archive not found in database (get() returned null)"));
				return null;
			}
			connectorPool.get(archive).delete(ca);
			return archiveId;
		} catch (NotFoundException e) {
			// Archive document does not exist - thrown by get() or delete()
			log.warn(daoHelper.buildLogMsg(archiveId, "archive not found in database (NotFoundException thrown)"));
			return null;
		} catch (ServiceResponseException e) {
			// CouchDB/Cloudant service error (connection, timeout, 5xx errors)
			throw new CmisRuntimeException("deleteArchive: CouchDB service error, archiveId=" + archiveId, e);
		} catch (Exception e) {
			// Unexpected error
			throw new CmisRuntimeException("deleteArchive: unexpected error, archiveId=" + archiveId, e);
		}
	}

	public void deleteDocumentArchive(String repositoryId, String archiveId) {
		Archive docArchive = getArchive(repositoryId, archiveId);
		if (docArchive == null) {
			log.warn(daoHelper.buildLogMsg(archiveId, "document archive not found"));
			return;
		}

		// Handle attachment archive deletion
		String attachmentNodeId = docArchive.getAttachmentNodeId();
		if (attachmentNodeId != null) {
			Archive attachmentArchive = getArchiveByOriginalId(repositoryId, attachmentNodeId);
			if (attachmentArchive != null) {
				String deletedAttachmentArchiveId = deleteArchive(repositoryId, attachmentArchive.getId());
				if (deletedAttachmentArchiveId == null) {
					log.warn(daoHelper.buildLogMsg(attachmentArchive.getId(), "attachment archive already deleted or not found"));
				}
			} else {
				log.warn(daoHelper.buildLogMsg(attachmentNodeId, "attachment archive not found by original ID"));
			}
		} else {
			log.warn(daoHelper.buildLogMsg(archiveId, "document archive has no attachment node ID"));
		}

		// Delete the document archive itself
		String deletedDocArchiveId = deleteArchive(repositoryId, docArchive.getId());
		if (deletedDocArchiveId == null) {
			throw new CmisRuntimeException("deleteDocumentArchive: document archive deletion failed, archiveId=" + docArchive.getId());
		}
	}

	public void restoreContent(String repositoryId, Archive archive) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			// Get the archived content document
			String archiveId = archive.getId();
			String originalId = archive.getOriginalId();

			log.info("restoreContent: archiveId=" + archiveId + ", originalId=" + originalId + ", repositoryId=" + repositoryId);

			// Get the archive repository
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper archiveClient = connectorPool.getClient(archiveRepositoryId);

			// Retrieve the archived document
			com.ibm.cloud.cloudant.v1.model.Document archivedDoc = archiveClient.get(archiveId);
			if (archivedDoc == null) {
				log.warn("Archive document not found: " + archiveId);
				return;
			}

			// CLOUDANT FIX: Use Document.get() to create Map for manipulation
			Map<String, Object> docMap = new HashMap<>();

			// Copy standard document fields
			docMap.put("_id", originalId); // Set restored ID
			// Skip _rev to let CouchDB assign new revision

			// CRITICAL FIX (2026-02-09): Copy all custom fields from Document using getProperties()
			// Previously cast created/modified to String, but they are stored as Numbers (timestamps)
			// LazilyParsedNumber cannot be cast to String, causing ClassCastException
			try {
				Map<String, Object> properties = archivedDoc.getProperties();
				if (properties != null && !properties.isEmpty()) {
					log.info("restoreContent: Copying " + properties.size() + " properties from archived doc");
					for (Map.Entry<String, Object> entry : properties.entrySet()) {
						String key = entry.getKey();
						// Skip archive-specific fields and document metadata
						if (!"isArchive".equals(key) && !"originalId".equals(key) &&
							!"_rev".equals(key) && !"_id".equals(key) &&
							!"archiveState".equals(key) && !"archivedAt".equals(key) &&
							!"archivedBy".equals(key) && !"coldArchivedAt".equals(key) &&
							!"coldMoveMode".equals(key) && !"contentRef".equals(key) &&
							!"aclSnapshot".equals(key) && !"propsSnapshot".equals(key) &&
							!"deletedWithParent".equals(key) && !"lastRevision".equals(key) &&
							// content_incarnation belongs to the PRE-DELETE lifetime (design §8.1).
							// Copying it would restore a Content whose _rev restarts at 1 while Solr
							// still holds "same incarnation, generation 50" — the content fence would
							// then read "generation 1 < 50" and refuse the restored write FOR EVER.
							// A fresh one forces the §4.4 mismatch path, which correctly overwrites
							// the pre-delete Solr document. Minted below, never copied.
							!jp.aegif.nemaki.epoch.ContentIncarnation.FIELD.equals(key)) {
							docMap.put(key, entry.getValue());
						}
					}
				}
			} catch (Exception e) {
				log.warn("CLOUDANT FIX: Error accessing getProperties() during restore: " + e.getMessage());
			}

			// A RESTORE IS A NEW LIFETIME (design §8.1, wiring gate 3). Always mint — never carry the
			// archived value over, and never leave the field absent either: an incarnation-less
			// restored Content would be indistinguishable from pre-migration content, and the fence
			// would compare its restarted generation against the pre-delete one.
			docMap.put(jp.aegif.nemaki.epoch.ContentIncarnation.FIELD,
					jp.aegif.nemaki.epoch.ContentIncarnation.mint());

			// FIX (2026-02-11): CouchArchive does not store 'objectType' field.
			// Normal documents have 'objectType' (e.g. "cmis:document") which is required
			// for CMIS operations like checkOut. Restore it from the archive's 'type' field.
			if (!docMap.containsKey("objectType") && docMap.containsKey("type")) {
				docMap.put("objectType", docMap.get("type"));
				log.info("restoreContent: restored objectType from type field: " + docMap.get("type"));
			}

			log.info("restoreContent: docMap keys=" + docMap.keySet() + ", about to purge tombstone for " + originalId);

			// CRITICAL FIX (2026-02-09): Handle CouchDB tombstone for deleted documents
			// CouchDB keeps tombstones (_deleted=true) for deleted documents.
			// PUT with the same ID fails with 409 Conflict even with tombstone _rev.
			// Must purge the tombstone first, then create the document fresh.
			boolean purged = client.purgeTombstone(originalId);
			log.info("restoreContent: purgeTombstone result=" + purged + " for " + originalId);

			// Create the restored document in the main repository
			com.ibm.cloud.cloudant.v1.model.DocumentResult createResult = client.create(originalId, docMap);
			log.info("restoreContent: create result=" + (createResult != null ? "id=" + createResult.getId() + ",ok=" + createResult.isOk() : "null") + " for " + originalId);

			log.info("Content restored from archive: " + archiveId + " to original ID: " + originalId);

		} catch (Exception e) {
			log.error("Error restoring content from archive: " + archive.getId() + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to restore content from archive", e);
		}
	}

	public void restoreAttachment(String repositoryId, Archive archive) {
		if (archive == null) {
			// getAttachmentArchive returns null, by design, when the document archive carries
			// no attachmentNodeId (it logs a WARN and returns) or when the attachments view
			// finds no row. There is simply no attachment to restore, and that is not an
			// error: the DOCUMENT has already been restored by the caller.
			//
			// Dereferencing it threw an NPE that the catch below turned into "Failed to
			// restore attachment from archive", which propagated out of restoreArchive as a
			// failure — so the REST caller was told the restore failed while the object was in
			// fact back. Found by the first restore drill (2026-08-24), on a document whose
			// archive had no attachmentNodeId.
			log.info("restoreAttachment: nothing to restore — the document archive names no "
					+ "attachment (repository " + repositoryId + ")");
			return;
		}
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			String archiveId = archive.getId();
			String originalId = archive.getOriginalId();

			log.info("restoreAttachment: archiveId=" + archiveId + ", originalId=" + originalId);

			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper archiveClient = connectorPool.getClient(archiveRepositoryId);

			// Read the raw archive document to preserve ALL fields
			com.ibm.cloud.cloudant.v1.model.Document archivedDoc = archiveClient.get(archiveId);
			if (archivedDoc == null) {
				log.warn("Archive attachment document not found: " + archiveId);
				return;
			}

			// Build restored document from raw properties
			Map<String, Object> docMap = new HashMap<>();
			docMap.put("_id", originalId);
			// Copy all custom fields from archive
			Map<String, Object> properties = archivedDoc.getProperties();
			if (properties != null) {
				for (Map.Entry<String, Object> entry : properties.entrySet()) {
					String key = entry.getKey();
					// Skip archive-specific and CouchDB internal fields
					if (!"originalId".equals(key) && !"_rev".equals(key) && !"_id".equals(key) &&
						!"lastRevision".equals(key) && !"archiveState".equals(key) &&
						!"archivedAt".equals(key) && !"archivedBy".equals(key) &&
						!"deletedWithParent".equals(key) && !"aclSnapshot".equals(key) &&
						!"propsSnapshot".equals(key) && !"coldArchivedAt".equals(key) &&
						!"coldMoveMode".equals(key) && !"contentRef".equals(key)) {
						docMap.put(key, entry.getValue());
					}
				}
			}

			// Purge tombstone
			boolean purged = client.purgeTombstone(originalId);
			log.info("restoreAttachment: purgeTombstone result=" + purged + " for " + originalId);

			// Create the restored attachment document
			client.create(originalId, docMap);

			// Restore binary attachment from archive
			// Use getAttachments() (not getProperties()) - Cloudant SDK extracts _attachments separately
			Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> archiveAttachments = archivedDoc.getAttachments();
			boolean archiveHasBinary = archiveAttachments != null && !archiveAttachments.isEmpty();
			try {
				Object attachmentData = archiveClient.getAttachment(archiveId, "content");
				// Closed on every path out, including the throws below (see createAttachmentArchive).
				try (java.io.InputStream body = attachmentData instanceof java.io.InputStream
						? (java.io.InputStream) attachmentData : null) {
					if (attachmentData instanceof java.io.InputStream) {
						com.ibm.cloud.cloudant.v1.model.Document doc = client.get(originalId);
						String revision = doc != null ? doc.getRev() : null;

						// Get mimeType from the restored document properties
						String mimeType = docMap.get("mimeType") instanceof String
							? (String) docMap.get("mimeType") : "application/octet-stream";
						if (mimeType.isEmpty()) mimeType = "application/octet-stream";

						client.createAttachment(originalId, revision, "content", body, mimeType);

						// Update length metadata, preserving _attachments stubs
						com.ibm.cloud.cloudant.v1.model.Document updatedDoc = client.get(originalId);
						if (updatedDoc != null) {
							Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> atts = updatedDoc.getAttachments();
							if (atts != null && atts.get("content") != null) {
								long actualLength = atts.get("content").length();
								Map<String, Object> updateMap = new HashMap<>();
								updateMap.put("_id", originalId);
								updateMap.put("_rev", updatedDoc.getRev());
								Map<String, Object> props = updatedDoc.getProperties();
								if (props != null) {
									updateMap.putAll(props);
								}
								updateMap.put("length", actualLength);
								updateMap.put("actualLength", actualLength);
								// Include _attachments stubs to preserve binary
								Map<String, Object> attachmentsStubs = new HashMap<>();
								for (Map.Entry<String, com.ibm.cloud.cloudant.v1.model.Attachment> entry : atts.entrySet()) {
									Map<String, Object> stub = new HashMap<>();
									stub.put("stub", true);
									stub.put("content_type", entry.getValue().contentType());
									stub.put("length", entry.getValue().length());
									attachmentsStubs.put(entry.getKey(), stub);
								}
								updateMap.put("_attachments", attachmentsStubs);
								client.update(updateMap);
								log.info("restoreAttachment: updated length to " + actualLength + " for " + originalId);
							}
						}

						log.info("Binary attachment restored for: " + originalId);
					}
					}
			} catch (Exception attachmentError) {
				if (archiveHasBinary) {
					throw new RuntimeException("Failed to restore binary attachment for: " + originalId
						+ " (binary exists in archive)", attachmentError);
				}
				log.info("restoreAttachment: no binary content in archive for " + originalId);
			}

			log.info("Attachment restored from archive: " + archiveId + " to original ID: " + originalId);

		} catch (Exception e) {
			log.error("Error restoring attachment from archive: " + archive.getId() + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to restore attachment from archive", e);
		}
	}

	public void restoreDocumentWithArchive(String repositoryId, Archive contentArchive) {
		// LOOK BEFORE RESTORING. Separating "there is none" from "we could not get it" has to
		// happen before the document is written, or the failure this method reports is the
		// exact shape it was written to fix: the document is back and the caller is told the
		// restore failed (external review of the first fix).
		AttachmentArchiveLookup lookup = lookupAttachmentArchive(repositoryId, contentArchive);
		if (lookup instanceof AttachmentArchiveLookup.Unavailable unavailable) {
			throw new RuntimeException("Failed to restore attachment from archive: "
					+ unavailable.reason() + " — nothing was restored");
		}
		restoreContent(repositoryId, contentArchive);
		Archive attachmentArchive = lookup instanceof AttachmentArchiveLookup.Found found
				? found.archive() : null;
		restoreAttachment(repositoryId, attachmentArchive);

		// DEFENSIVE FIX: After both document and attachment are restored,
		// ensure the document's mimeType is set correctly.
		// Old archives (created with CouchArchive model) may have lost the mimeType field,
		// causing contentStreamMimeType to be null/application/octet-stream after restore.
		// In this case, read the mimeType from the restored attachment node.
		try {
			String originalId = contentArchive.getOriginalId();
			String attachmentNodeId = contentArchive.getAttachmentNodeId();
			if (originalId != null && attachmentNodeId != null) {
				CloudantClientWrapper client = connectorPool.getClient(repositoryId);
				com.ibm.cloud.cloudant.v1.model.Document docNode = client.get(originalId);
				if (docNode != null) {
					Map<String, Object> docProps = docNode.getProperties();
					Object currentMimeType = docProps != null ? docProps.get("mimeType") : null;
					// If mimeType is missing or is the generic octet-stream fallback
					if (currentMimeType == null || "application/octet-stream".equals(currentMimeType)) {
						com.ibm.cloud.cloudant.v1.model.Document attNode = client.get(attachmentNodeId);
						if (attNode != null) {
							Map<String, Object> attProps = attNode.getProperties();
							Object attMimeType = attProps != null ? attProps.get("mimeType") : null;
							if (attMimeType instanceof String && !((String) attMimeType).isEmpty()
									&& !"application/octet-stream".equals(attMimeType)) {
								// Update the document with the correct mimeType from attachment
								Map<String, Object> updateMap = new HashMap<>();
								updateMap.put("_id", originalId);
								updateMap.put("_rev", docNode.getRev());
								if (docProps != null) {
									updateMap.putAll(docProps);
								}
								updateMap.put("mimeType", attMimeType);
								// Preserve _attachments stubs if any
								Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> atts = docNode.getAttachments();
								if (atts != null && !atts.isEmpty()) {
									Map<String, Object> stubs = new HashMap<>();
									for (Map.Entry<String, com.ibm.cloud.cloudant.v1.model.Attachment> e : atts.entrySet()) {
										Map<String, Object> stub = new HashMap<>();
										stub.put("stub", true);
										stub.put("content_type", e.getValue().contentType());
										stub.put("length", e.getValue().length());
										stubs.put(e.getKey(), stub);
									}
									updateMap.put("_attachments", stubs);
								}
								client.update(updateMap);
								log.info("restoreDocumentWithArchive: Fixed mimeType from attachment node: "
										+ currentMimeType + " -> " + attMimeType + " for " + originalId);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			log.warn("restoreDocumentWithArchive: Failed to fix mimeType from attachment node: " + e.getMessage());
		}
	}

	public void restoreVersionSeries(String repositoryId, String versionSeriesId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			// Purge the tombstone left by the deleted VersionSeries
			boolean purged = client.purgeTombstone(versionSeriesId);
			log.info("restoreVersionSeries: purgeTombstone result=" + purged + " for " + versionSeriesId);

			// Create a new VersionSeries with checkout state cleared
			VersionSeries newVs = new VersionSeries();
			newVs.setId(versionSeriesId);
			newVs.setVersionSeriesCheckedOut(false);
			newVs.setVersionSeriesCheckedOutBy(null);
			newVs.setVersionSeriesCheckedOutId(null);

			GregorianCalendar now = new GregorianCalendar();
			newVs.setCreated(now);
			newVs.setModified(now);

			CouchVersionSeries cvs = new CouchVersionSeries(newVs);
			client.create(cvs);

			log.info("restoreVersionSeries: recreated VersionSeries " + versionSeriesId + " in repository " + repositoryId);
		} catch (Exception e) {
			log.error("Error restoring VersionSeries: " + versionSeriesId + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to restore VersionSeries", e);
		}
	}

	// ///////////////////////////////////////
	// Retention lifecycle
	// ///////////////////////////////////////
	public List<Archive> getArchivesByState(String repositoryId, String state) {
		try {
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			List<CouchArchive> couchArchives = client.queryView("_repo", "byArchiveState", state, CouchArchive.class);
			List<Archive> archives = new ArrayList<Archive>();
			if (couchArchives != null) {
				for (CouchArchive ca : couchArchives) {
					archives.add(ca.convert());
				}
			}
			return archives;
		} catch (Exception e) {
			log.error("Error getting archives by state: " + state + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public List<Archive> getSearchableArchives(String repositoryId, String state) {
		// Delegate to paged version with no skip/limit (returns all matching rows)
		return getSearchableArchivesByStatePaged(repositoryId, state, 0, 0, true);
	}

	public List<Archive> getSearchableArchivesPaged(String repositoryId, int skip, int limit, boolean descending) {
		try {
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			CloudantClientWrapper.PagedViewResult<CouchArchive> paged =
					client.queryViewPaged("_repo", "archivesByArchivedAt", CouchArchive.class, skip, limit, descending);
			List<Archive> archives = new ArrayList<Archive>();
			for (CouchArchive ca : paged.items) {
				archives.add(ca.convert());
			}
			return archives;
		} catch (Exception e) {
			log.error("Error getting paged archives in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public long getSearchableArchivesCount(String repositoryId) {
		try {
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			// Count-only query: includeDocs=false, limit=0, returns only total_rows
			return client.queryViewCount("_repo", "archivesByArchivedAt");
		} catch (Exception e) {
			// 0 is "the trash is empty", which this failure does not establish — the pager
			// built on it renders an empty listing over archives that still exist.
			log.error("Error getting archive count in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives could not be counted in '"
					+ repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public List<Archive> getSearchableArchivesByStatePaged(String repositoryId, String state, int skip, int limit, boolean descending) {
		try {
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			CloudantClientWrapper.PagedViewResult<CouchArchive> paged =
					client.queryViewPagedWithKey("_repo", "searchableArchives", state, CouchArchive.class, skip, limit, descending);
			List<Archive> archives = new ArrayList<Archive>();
			for (CouchArchive ca : paged.items) {
				archives.add(ca.convert());
			}
			return archives;
		} catch (Exception e) {
			log.error("Error getting paged archives by state" + (state != null ? " (state=" + state + ")" : "") + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public long getSearchableArchivesByStateCount(String repositoryId, String state) {
		try {
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper client = connectorPool.getClient(archiveRepositoryId);
			return client.queryViewCountByKey("_repo", "searchableArchives", state);
		} catch (Exception e) {
			// Same rule as the unfiltered count above: a failed count is not zero.
			log.error("Error getting archive count by state" + (state != null ? " (state=" + state + ")" : "") + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives could not be counted in '"
					+ repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public List<Archive> getArchivesForColdTransition(String repositoryId, GregorianCalendar beforeDate) {
		try {
			// Get all ARCHIVED_LOCAL archives and filter by archivedAt
			List<Archive> localArchives = getArchivesByState(repositoryId, Archive.STATE_ARCHIVED_LOCAL);
			List<Archive> candidates = new ArrayList<Archive>();
			for (Archive a : localArchives) {
				// Only document archives have content streams that can be migrated.
				// Folder and attachment archives would be skipped by moveToCold() anyway,
				// so filter them here to avoid wasted processing on every scheduler run.
				if (!Boolean.TRUE.equals(a.isDocument())) {
					continue;
				}
				// Skip archives that have already been copied/moved to cold storage
				if (a.getColdArchivedAt() != null) {
					continue;
				}
				if (a.getArchivedAt() != null && a.getArchivedAt().before(beforeDate)) {
					candidates.add(a);
				}
			}
			return candidates;
		} catch (Exception e) {
			log.error("Error getting archives for cold transition in repository: " + repositoryId, e);
			throw new IllegalStateException("the archives could not be read in '" + repositoryId + "'; this is NOT a finding that there are none", e);
		}
	}

	public void updateArchiveState(String repositoryId, String archiveId,
			String newState, Map<String, String> contentRef, GregorianCalendar coldArchivedAt) {
		try {
			String archiveRepoId = repositoryInfoMap.getArchiveId(repositoryId);
			CouchArchive ca = connectorPool.get(archiveRepoId).get(CouchArchive.class, archiveId);
			if (ca == null) {
				log.warn("Archive not found for state update: " + archiveId);
				return;
			}
			ca.setArchiveState(newState);
			if (contentRef != null) {
				ca.setContentRef(contentRef);
			}
			if (coldArchivedAt != null) {
				ca.setColdArchivedAt(coldArchivedAt);
			}
			connectorPool.get(archiveRepoId).update(ca);
			log.info("Updated archive state: " + archiveId + " -> " + newState);
		} catch (Exception e) {
			log.error("Error updating archive state for " + archiveId + ": " + e.getMessage(), e);
			throw new RuntimeException("Failed to update archive state", e);
		}
	}

	/**
	 * Resets cold-move metadata on an archive, clearing contentRef, coldArchivedAt, and coldMoveMode.
	 * Used when a cold move fails and the archive should be eligible for retry.
	 */
	public void resetColdMoveMetadata(String repositoryId, String archiveId) {
		try {
			String archiveRepoId = repositoryInfoMap.getArchiveId(repositoryId);
			CouchArchive ca = connectorPool.get(archiveRepoId).get(CouchArchive.class, archiveId);
			if (ca == null) {
				log.warn("Archive not found for cold-move reset: " + archiveId);
				return;
			}
			ca.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);
			ca.setContentRef(null);
			ca.setColdArchivedAt(null);
			ca.setColdMoveMode(null);
			connectorPool.get(archiveRepoId).update(ca);
			log.info("Reset cold-move metadata for archive: " + archiveId + " -> ARCHIVED_LOCAL (retry eligible)");
		} catch (Exception e) {
			log.error("Error resetting cold-move metadata for " + archiveId + ": " + e.getMessage(), e);
		}
	}

	public java.io.InputStream getArchiveContentStream(String repositoryId, Archive archive) {
		try {
			if (archive == null || !archive.isDocument()) {
				return null;
			}

			// Get the attachment archive from the closet DB
			Archive attachmentArchive = getAttachmentArchive(repositoryId, archive);
			if (attachmentArchive == null) {
				log.warn("No attachment archive found for archive: " + archive.getId());
				return null;
			}

			// Get binary content from the closet DB CouchDB attachment
			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper archiveClient = connectorPool.getClient(archiveRepositoryId);
			Object streamObj = archiveClient.getAttachment(attachmentArchive.getId(), "content");
			if (streamObj instanceof java.io.InputStream) {
				return (java.io.InputStream) streamObj;
			}

			return null;
		} catch (Exception e) {
			log.error("Error getting archive content stream for: " + archive.getId() + " in repository: " + repositoryId, e);
			return null;
		}
	}

	public boolean deleteArchiveContent(String repositoryId, Archive archive) {
		try {
			if (archive == null || !archive.isDocument()) {
				return false;
			}

			// Use attachmentNodeId to find the attachment archive via "attachments" view
			String attachmentNodeId = archive.getAttachmentNodeId();
			if (attachmentNodeId == null || attachmentNodeId.isEmpty()) {
				log.warn("No attachmentNodeId on archive: " + archive.getId());
				return false;
			}

			String archiveRepositoryId = repositoryInfoMap.getArchiveId(repositoryId);
			CloudantClientWrapper archiveClient = connectorPool.getClient(archiveRepositoryId);
			List<CouchArchive> couchArchives = archiveClient.queryView(
					"_repo", "attachments", attachmentNodeId, CouchArchive.class);

			if (couchArchives.isEmpty()) {
				log.warn("No attachment archive found for archive: " + archive.getId()
						+ " (attachmentNodeId=" + attachmentNodeId + ")");
				return false;
			}

			CouchArchive attachmentArchive = couchArchives.get(0);
			String docId = attachmentArchive.getId();
			String revision = attachmentArchive.getRevision();

			if (docId == null || revision == null) {
				log.warn("Attachment archive missing id or revision for archive: " + archive.getId());
				return false;
			}

			// Delete binary content attachment from the closet DB
			archiveClient.deleteAttachment(docId, revision, "content");

			log.info("Deleted archive content attachment for: " + archive.getId() + " in repository: " + repositoryId);
			return true;
		} catch (Exception e) {
			log.error("Error deleting archive content for: " + archive.getId() + " in repository: " + repositoryId, e);
			return false;
		}
	}

	public List<String> getExpiredDocumentIds(String repositoryId, GregorianCalendar beforeDate) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			long endKey = beforeDate.getTimeInMillis();

			Map<String, Object> params = new HashMap<String, Object>();
			params.put("endkey", endKey);

			com.ibm.cloud.cloudant.v1.model.ViewResult viewResult =
					client.queryView("_repo", "documentsByExpirationDate", params);

			List<String> ids = new ArrayList<String>();
			if (viewResult == null || viewResult.getRows() == null) {
				// The catch below refuses a failed sweep; an unanswered view is the same
				// failure through the other door, and the scheduler records it as a
				// completed pass that found no candidates.
				throw new IllegalStateException("the retention view did not answer for '"
						+ repositoryId + "'; that is not the same as there being no"
						+ " candidates");
			}
			{
				for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : viewResult.getRows()) {
					if (row.getValue() != null) {
						ids.add(row.getValue().toString().replace("\"", ""));
					}
				}
			}
			return ids;
		} catch (Exception e) {
			// An empty list here is "nothing has expired", and the scheduler records that as
			// a completed sweep. A retention policy that could not be evaluated has not been
			// evaluated — the direction is safe (nothing is archived) but the audit record
			// would say the opposite. The scheduler catches this per repository and logs it
			// as an error rather than as "0 candidates".
			log.error("Error querying documentsByExpirationDate view: " + e.getMessage(), e);
			throw new IllegalStateException("the expired documents of '" + repositoryId
					+ "' could not be listed; this is NOT a finding that none have expired", e);
		}
	}

	/**
	 * Query the documentsByLastModification view to find latestVersion documents
	 * whose lastModificationDate is before the given cutoff and that do NOT have
	 * cmis:rm_expirationDate set.
	 * Used by the "archive after N days of inactivity" retention feature.
	 */
	public List<String> getStaleDocumentIds(String repositoryId, GregorianCalendar beforeDate) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			long endKey = beforeDate.getTimeInMillis();

			Map<String, Object> params = new HashMap<String, Object>();
			params.put("endkey", endKey);

			com.ibm.cloud.cloudant.v1.model.ViewResult viewResult =
					client.queryView("_repo", "documentsByLastModification", params);

			List<String> ids = new ArrayList<String>();
			if (viewResult == null || viewResult.getRows() == null) {
				// The catch below refuses a failed sweep; an unanswered view is the same
				// failure through the other door, and the scheduler records it as a
				// completed pass that found no candidates.
				throw new IllegalStateException("the retention view did not answer for '"
						+ repositoryId + "'; that is not the same as there being no"
						+ " candidates");
			}
			{
				for (com.ibm.cloud.cloudant.v1.model.ViewResultRow row : viewResult.getRows()) {
					if (row.getValue() != null) {
						ids.add(row.getValue().toString().replace("\"", ""));
					}
				}
			}
			return ids;
		} catch (Exception e) {
			// Same rule as the expiration sweep above.
			log.error("Error querying documentsByLastModification view: " + e.getMessage(), e);
			throw new IllegalStateException("the stale documents of '" + repositoryId
					+ "' could not be listed; this is NOT a finding that none are stale", e);
		}
	}

	public void updateArchiveColdMoveMode(String repositoryId, String archiveId, String coldMoveMode) {
		try {
			String archiveRepoId = repositoryInfoMap.getArchiveId(repositoryId);
			CouchArchive ca = connectorPool.get(archiveRepoId).get(CouchArchive.class, archiveId);
			if (ca == null) {
				log.warn("Archive not found for coldMoveMode update: " + archiveId);
				return;
			}
			ca.setColdMoveMode(coldMoveMode);
			connectorPool.get(archiveRepoId).update(ca);
			log.info("Updated archive coldMoveMode: " + archiveId + " -> " + coldMoveMode);
		} catch (Exception e) {
			log.error("Error updating archive coldMoveMode for " + archiveId + ": " + e.getMessage(), e);
		}
	}
}
