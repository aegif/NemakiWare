package jp.aegif.nemaki.dao.impl.couch.delegate;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.Document;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.couch.CouchAttachmentNode;
import jp.aegif.nemaki.model.couch.CouchRendition;

/**
 * Delegate for Attachment DAO operations.
 * Extracted from ContentDaoServiceImpl as part of class decomposition.
 */
public class AttachmentDaoDelegate {

	private static final Log log = LogFactory.getLog(AttachmentDaoDelegate.class);

	private final CloudantClientPool connectorPool;
	private final DaoHelper daoHelper;

	public AttachmentDaoDelegate(CloudantClientPool connectorPool, DaoHelper daoHelper) {
		this.connectorPool = connectorPool;
		this.daoHelper = daoHelper;
	}

	/**
	 * The attachment's metadata WITHOUT opening its binary stream.
	 *
	 * <p>{@link #getAttachment} eagerly opens the CouchDB attachment body and hands the caller an
	 * {@code InputStream} it then owns. Callers that only want existence or length were using it
	 * anyway, never closing what they never read — one leaked HTTP connection per call, and a
	 * full attachment download to answer a question the document already answers. During a full
	 * reindex, which touches every document, this was measured at 3 → 1,289 established
	 * connections for 2,510 documents, and the sockets stayed for about ninety seconds after it
	 * finished. Length lives in the document, so this needs no body at all.
	 */
	public AttachmentNode getAttachmentRef(String repositoryId, String attachmentId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			// The wrapper's get() answers null only for NotFound and throws for everything
			// else, so a null here is genuine absence — and this catch must not put the
			// two back together. The fixity scan reads a null as "this document has no
			// attachment" and reports the record as having nothing to check.
			CouchAttachmentNode can = client.get(CouchAttachmentNode.class, attachmentId);
			return can == null ? null : can.convertRef();
		} catch (Exception e) {
			log.error("Error getting attachment metadata: " + attachmentId + " in repository: "
					+ repositoryId, e);
			throw new IllegalStateException("the attachment metadata of '" + attachmentId
					+ "' in '" + repositoryId + "' could not be read; this is NOT a finding"
					+ " that the document has no attachment", e);
		}
	}

	public AttachmentNode getAttachment(String repositoryId, String attachmentId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			CouchAttachmentNode can = client.get(CouchAttachmentNode.class, attachmentId);

			if (can != null) {
				// convertRef, NOT convert: convert() opens the body itself (searching every
				// repository for it), and this method then opened it a SECOND time and replaced
				// the first stream without closing it. Every attachment read leaked exactly one
				// connection, and downloaded the attachment twice to do it. The open happens once,
				// here, where the repository is already known.
				AttachmentNode result = can.convertRef();

				// A node handed back with a null stream is the sentence "this document
				// exists and has no content" — and every reader downstream believes it:
				// the exporters wrote a metadata sidecar with no bytes beside it, and the
				// fixity scan had nothing to hash. The wrapper distinguishes the two cases
				// for us: CmisObjectNotFound means the `content` attachment genuinely is
				// not on the document, anything else means the read failed.
				try {
					Object attachmentObj = client.getAttachment(attachmentId, "content");
					if (attachmentObj instanceof InputStream) {
						result.setInputStream((InputStream) attachmentObj);
					} else {
						throw new IllegalStateException("the attachment body of '"
								+ attachmentId + "' came back as "
								+ (attachmentObj == null ? "null" : attachmentObj.getClass().getName())
								+ " rather than a stream");
					}
				} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException notFound) {
					// Genuine absence: the document carries no `content` attachment.
					log.debug("No binary attachment stream found for: " + attachmentId);
				}

				return result;
			} else {
				// The wrapper's get() throws on failure, so null is genuine absence.
				log.warn("CouchAttachmentNode is null for: " + attachmentId);
				return null;
			}
		} catch (IllegalStateException e) {
			// The arms above each name what they found — a body that came back as something
			// other than a stream, in particular. Letting this general catch re-wrap them
			// replaced that with one sentence about a read that failed, which is the wrong
			// sentence and unhelpful for the operator. Same shape as getUserItemById.
			throw e;
		} catch (Exception e) {
			log.error("Error getting attachment: " + attachmentId + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the attachment '" + attachmentId + "' in '"
					+ repositoryId + "' could not be read; this is NOT a finding that the"
					+ " document has no content", e);
		}
	}

	public void setStream(String repositoryId, AttachmentNode attachmentNode) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			log.debug("=== OPTIMIZED SET STREAM ===");
			log.debug("Attachment ID: " + attachmentNode.getId());
			log.debug("Has binary content: " + (attachmentNode.getInputStream() != null));

			CouchAttachmentNode can = new CouchAttachmentNode(attachmentNode);

			// STAGE 1: Create/update metadata document with retry logic
			int retryCount = 0;
			int maxRetries = 3;
			String stage1RevisionAfterUpdate = null;

			while (retryCount < maxRetries) {
				try {
					if (attachmentNode.getId() != null && client.exists(attachmentNode.getId())) {
						Document latestDoc = client.get(attachmentNode.getId());
						if (latestDoc != null && latestDoc.getRev() != null) {
							can.setRevision(latestDoc.getRev());
						}
						// Preserve any existing binary across the metadata write. Stage 2 below
						// re-uploads it only when this node actually carries a stream; when it does
						// not, a plain update here would delete the stored binary outright.
						client.updatePreservingAttachments(can, latestDoc);
						// Re-read rather than trusting `can`: update(Map) swallows failures and
						// returns null, so the object cannot tell a successful write from a lost
						// response. This GET was removed once (ledger V3) and put back.
						Document updatedDoc = client.get(attachmentNode.getId());
						stage1RevisionAfterUpdate = updatedDoc != null ? updatedDoc.getRev() : null;
						log.debug("STAGE 1: Updated attachment metadata for: " + attachmentNode.getId() + " (new revision: " + stage1RevisionAfterUpdate + ")");
					} else {
						client.create(can);
						Document createdDoc = client.get(attachmentNode.getId());
						stage1RevisionAfterUpdate = createdDoc != null ? createdDoc.getRev() : null;
						log.debug("STAGE 1: Created attachment metadata for: " + attachmentNode.getId() + " (new revision: " + stage1RevisionAfterUpdate + ")");
					}
					break;

				} catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
					retryCount++;
					log.warn("STAGE 1 RETRY " + retryCount + "/" + maxRetries + ": Metadata document conflict - " + e.getMessage());

					if (retryCount >= maxRetries) {
						throw new RuntimeException("Failed to create/update attachment metadata after " + maxRetries + " retries", e);
					}

					try {
						Thread.sleep(100 * retryCount);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("Interrupted during setStream retry", ie);
					}
				}
			}

			// STAGE 2: Add binary content as CouchDB attachment (if present)
			if (attachmentNode.getInputStream() != null) {
				retryCount = 0;

				while (retryCount < maxRetries) {
					try {
						String revisionToUse = stage1RevisionAfterUpdate;
						if (revisionToUse == null) {
							Document doc = client.get(attachmentNode.getId());
							revisionToUse = doc != null ? doc.getRev() : null;
						}

						if (revisionToUse == null) {
							log.warn("STAGE 2: Cannot get revision for attachment: " + attachmentNode.getId());
							break;
						}

						String attachmentName = "content";
						String contentType = attachmentNode.getMimeType() != null ?
							attachmentNode.getMimeType() : "application/octet-stream";

						log.debug("STAGE 2: Adding binary content to attachment: " + attachmentNode.getId() + " (revision: " + revisionToUse + ")");

						String newRevision = client.createAttachment(
							attachmentNode.getId(),
							revisionToUse,
							attachmentName,
							attachmentNode.getInputStream(),
							contentType
						);

						log.debug("STAGE 2 SUCCESS: Stored binary content for: " + attachmentNode.getId() + " (revision: " + revisionToUse + " -> " + newRevision + ")");
						break;

					} catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
						retryCount++;
						log.warn("STAGE 2 RETRY " + retryCount + "/" + maxRetries + ": Binary attachment conflict - " + e.getMessage());

						if (retryCount >= maxRetries) {
							log.warn("STAGE 2 FAILURE: Failed to add binary content after " + maxRetries + " retries. Continuing with metadata-only attachment.");
							break;
						}

						try {
							Thread.sleep(100 * retryCount);
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							log.warn("Interrupted during binary attachment retry - continuing with metadata-only");
							break;
						}
					} catch (Exception attachmentError) {
						log.warn("STAGE 2 ERROR: Failed to store binary content for: " + attachmentNode.getId() + ". Continuing with metadata-only attachment.", attachmentError);
						break;
					}
				}
			} else {
				log.debug("STAGE 2 SKIPPED: No binary content to attach");
			}

			log.debug("=== SET STREAM COMPLETED: " + attachmentNode.getId() + " ===");

		} catch (Exception e) {
			log.error("Error setting stream for attachment: " + attachmentNode.getId() + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to set stream for attachment", e);
		}
	}

	public Rendition getRendition(String repositoryId, String objectId) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			Document doc = client.get(objectId);
			if (doc == null) {
				return null;
			}

			ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
			Map<String, Object> properties = doc.getProperties();
			if (properties == null) {
				return null;
			}

			Map<String, Object> completeMap = new HashMap<>(properties);
			completeMap.put("_id", doc.getId());
			completeMap.put("_rev", doc.getRev());

			Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> sdkAttachments = doc.getAttachments();
			if (sdkAttachments != null && !sdkAttachments.isEmpty()) {
				Map<String, Object> attachmentsMap = new HashMap<>();
				for (Map.Entry<String, com.ibm.cloud.cloudant.v1.model.Attachment> entry : sdkAttachments.entrySet()) {
					com.ibm.cloud.cloudant.v1.model.Attachment att = entry.getValue();
					Map<String, Object> attMap = new HashMap<>();
					attMap.put("content_type", att.contentType());
					attMap.put("length", att.length());
					attMap.put("digest", att.digest());
					attMap.put("revpos", att.revpos());
					attMap.put("stub", att.stub());
					attachmentsMap.put(entry.getKey(), attMap);
				}
				completeMap.put("_attachments", attachmentsMap);
			}

			CouchRendition cr = mapper.convertValue(completeMap, CouchRendition.class);
			if (cr != null) {
				Rendition rendition = cr.convert();

				try {
					Long actualSize = client.getAttachmentSize(objectId, "content");
					if (actualSize != null && actualSize >= 0) {
						long metadataLength = rendition.getLength();
						if (metadataLength != actualSize) {
							log.debug("Rendition length correction for " + objectId +
								": metadata=" + metadataLength + " -> actual=" + actualSize);
						}
						rendition.setLength(actualSize);
					}
				} catch (Exception sizeEx) {
					log.warn("Could not get actual attachment size for rendition: " + objectId +
						", using metadata length: " + rendition.getLength(), sizeEx);
				}

				try {
					Object attachmentObj = client.getAttachment(objectId, "content");
					if (attachmentObj instanceof InputStream) {
						InputStream attachmentStream = (InputStream) attachmentObj;
						rendition.setInputStream(attachmentStream);
						log.debug("Successfully set rendition binary stream for: " + objectId);
					} else {
						throw new IllegalStateException("the rendition body of '" + objectId
								+ "' came back as "
								+ (attachmentObj == null ? "null" : attachmentObj.getClass().getName())
								+ " rather than a stream");
					}
				} catch (org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException notFound) {
					// Genuine absence: no `content` attachment on the rendition document.
					log.warn("No binary attachment found for rendition: " + objectId);
				}

				return rendition;
			}
			return null;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			log.error("Error getting rendition: " + objectId + " in repository: " + repositoryId, e);
			throw new IllegalStateException("the rendition '" + objectId + "' in '"
					+ repositoryId + "' could not be read; this is NOT a finding that it does"
					+ " not exist", e);
		}
	}

	public String createAttachment(String repositoryId, AttachmentNode attachment, ContentStream contentStream) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			log.debug("=== OPTIMIZED ATTACHMENT CREATION ===");
			log.debug("Attachment ID: " + attachment.getId());
			log.debug("Content Stream present: " + (contentStream != null));
			log.debug("Stream available: " + (contentStream != null && contentStream.getStream() != null));

			// STAGE 1: Create metadata document
			CouchAttachmentNode can = new CouchAttachmentNode(attachment);

			if (contentStream != null) {
				can.setMimeType(contentStream.getMimeType());
				can.setLength(contentStream.getLength());
				can.setName(contentStream.getFileName());
				log.debug("Content stream properties - MimeType: " + contentStream.getMimeType() + ", Length: " + contentStream.getLength());
			}

			ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
			@SuppressWarnings("unchecked")
			Map<String, Object> documentMap = mapper.convertValue(can, Map.class);

			com.ibm.cloud.cloudant.v1.model.DocumentResult result = null;
			String documentId = null;
			String documentRevision = null;

			int retryCount = 0;
			int maxRetries = 3;

			while (retryCount < maxRetries) {
				try {
					if (attachment.getId() != null && !attachment.getId().isEmpty()) {
						result = client.create(attachment.getId(), documentMap);
					} else {
						result = client.create(documentMap);
					}

					documentId = result.getId();
					documentRevision = result.getRev();

					log.debug("STAGE 1 SUCCESS: Created metadata document: " + documentId + " (revision: " + documentRevision + ")");
					break;

				} catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
					retryCount++;
					log.warn("STAGE 1 RETRY " + retryCount + "/" + maxRetries + ": Document creation conflict - " + e.getMessage());

					if (retryCount >= maxRetries) {
						throw new RuntimeException("Failed to create attachment metadata after " + maxRetries + " retries due to conflicts", e);
					}

					try {
						Thread.sleep(100 * retryCount);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("Interrupted during attachment creation retry", ie);
					}
				}
			}

			if (result == null) {
				throw new RuntimeException("Failed to create attachment metadata document after all retries");
			}

			// STAGE 2: Add binary content as CouchDB attachment (if present)
			if (contentStream != null && contentStream.getStream() != null) {
				String attachmentName = "content";
				String contentType = contentStream.getMimeType() != null ?
					contentStream.getMimeType() : "application/octet-stream";

				log.debug("STAGE 2: Adding binary attachment to document: " + documentId + " (revision: " + documentRevision + ")");

				// Get stream reference once; mark for potential retry if supported.
				// ByteArrayInputStream (common in CMIS) ignores readlimit, so this is safe.
				// For other streams, retry is only attempted if mark/reset is supported.
				InputStream binaryStream = contentStream.getStream();
				boolean canRetryStream = (binaryStream instanceof java.io.ByteArrayInputStream)
					&& binaryStream.markSupported();
				if (canRetryStream) {
					binaryStream.mark(0); // ByteArrayInputStream ignores readlimit
				}

				retryCount = 0;
				String finalRevision = null;

				while (retryCount < maxRetries) {
					try {
						String revisionToUse = documentRevision;
						if (revisionToUse == null) {
							Document currentDoc = client.get(documentId);
							revisionToUse = currentDoc != null ? currentDoc.getRev() : null;
							if (revisionToUse == null) {
								throw new RuntimeException("Unable to retrieve document revision for attachment creation");
							}
						}

						if (log.isDebugEnabled()) {
							log.debug("STAGE 2 ATTEMPT " + (retryCount + 1) + ": Creating attachment with revision " + revisionToUse);
						}

						finalRevision = client.createAttachment(
							documentId,
							revisionToUse,
							attachmentName,
							binaryStream,
							contentType
						);

						log.debug("STAGE 2 SUCCESS: Added binary attachment: " + documentId +
							" (revision: " + revisionToUse + " -> " + finalRevision + ")");
						break;

					} catch (com.ibm.cloud.sdk.core.service.exception.ConflictException e) {
						retryCount++;
						log.warn("STAGE 2 RETRY " + retryCount + "/" + maxRetries +
							": Attachment creation conflict - " + e.getMessage());

						if (retryCount >= maxRetries) {
							throw new RuntimeException("Failed to add binary attachment after " +
								maxRetries + " retries due to conflicts", e);
						}

						// Reset stream for retry; abort if not possible
						if (!canRetryStream) {
							throw new RuntimeException(
								"Cannot retry binary attachment upload: InputStream does not support mark/reset", e);
						}
						try {
							binaryStream.reset();
						} catch (java.io.IOException resetEx) {
							throw new RuntimeException(
								"Cannot retry binary attachment upload: InputStream reset failed", resetEx);
						}

						documentRevision = null;

						try {
							long baseBackoff = 100;
							long backoffMs = (long) (baseBackoff * Math.pow(2, retryCount - 1) * (0.5 + Math.random() * 0.5));
							backoffMs = Math.min(backoffMs, 5000);
							if (log.isDebugEnabled()) {
								log.debug("Backing off for " + backoffMs + "ms before retry " + retryCount);
							}
							Thread.sleep(backoffMs);
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							throw new RuntimeException("Interrupted during attachment binary retry", ie);
						}
					} catch (Exception otherEx) {
						retryCount++;
						log.error("Unexpected error during attachment creation for " + documentId +
							": " + otherEx.getMessage(), otherEx);
						if (retryCount >= maxRetries) {
							throw new RuntimeException("Failed to add binary attachment after " +
								maxRetries + " retries due to unexpected errors", otherEx);
						}

						// Reset stream for retry; abort if not possible
						if (!canRetryStream) {
							throw new RuntimeException(
								"Cannot retry binary attachment upload: InputStream does not support mark/reset", otherEx);
						}
						try {
							binaryStream.reset();
						} catch (java.io.IOException resetEx) {
							throw new RuntimeException(
								"Cannot retry binary attachment upload: InputStream reset failed", resetEx);
						}

						documentRevision = null;
						try {
							long baseBackoff = 100;
							long backoffMs = (long) (baseBackoff * Math.pow(2, retryCount - 1) * (0.5 + Math.random() * 0.5));
							backoffMs = Math.min(backoffMs, 5000);
							Thread.sleep(backoffMs);
						} catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							throw new RuntimeException("Interrupted during attachment binary retry", ie);
						}
					}
				}

				// Always verify and correct metadata length after successful binary save
				if (finalRevision != null) {
					try {
						Long actualSize = client.getAttachmentSize(documentId, "content");
						long declaredLength = contentStream.getLength();
						if (actualSize != null && actualSize >= 0 && actualSize != declaredLength) {
							Document currentDoc = client.get(documentId);
							if (currentDoc != null) {
								Map<String, Object> updateDoc = new HashMap<>();
								updateDoc.put("_id", currentDoc.getId());
								updateDoc.put("_rev", currentDoc.getRev());
								if (currentDoc.getProperties() != null) {
									updateDoc.putAll(currentDoc.getProperties());
								}
								Map<String, com.ibm.cloud.cloudant.v1.model.Attachment> existingAttachments = currentDoc.getAttachments();
								if (existingAttachments != null) {
									Map<String, Object> attachMap = new HashMap<>();
									for (Map.Entry<String, com.ibm.cloud.cloudant.v1.model.Attachment> entry : existingAttachments.entrySet()) {
										Map<String, Object> stub = new HashMap<>();
										stub.put("stub", true);
										stub.put("content_type", entry.getValue().contentType());
										attachMap.put(entry.getKey(), stub);
									}
									updateDoc.put("_attachments", attachMap);
								}
								updateDoc.put("length", actualSize);
								client.update(updateDoc);
								log.debug("Corrected attachment metadata length: " + declaredLength + " -> " + actualSize);
							}
						}
					} catch (Exception e) {
						log.warn("Failed to correct attachment metadata length (non-fatal): " + e.getMessage());
					}
				}
			} else {
				log.debug("STAGE 2 SKIPPED: No binary content to attach");
			}

			log.debug("=== ATTACHMENT CREATION COMPLETED: " + documentId + " ===");
			return documentId;

		} catch (Exception e) {
			log.error("Error creating attachment in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to create attachment", e);
		}
	}

	/**
	 * Whether the last {@link #createRendition} on THIS thread actually stored its bytes.
	 *
	 * <p>A thread-local rather than a return value because the signature is on an interface with
	 * other implementations and several callers, and widening it for one caller's benefit would
	 * touch all of them. Read immediately after the call, on the calling thread, or not at all.
	 *
	 * <p>It exists because this method swallows an attachment-write failure and returns an id
	 * anyway: without it, "the rendition was created" and "the rendition has its content" are
	 * the same answer.
	 */
	public static final ThreadLocal<Boolean> renditionContentStored = new ThreadLocal<>();

	public String createRendition(String repositoryId, Rendition rendition, ContentStream contentStream) {
		renditionContentStored.set(Boolean.TRUE);
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			CouchRendition cr = new CouchRendition(rendition);

			if (contentStream != null) {
				cr.setMimetype(contentStream.getMimeType());
				cr.setLength(contentStream.getLength());
				cr.setTitle(contentStream.getFileName());
			}

			com.ibm.cloud.cloudant.v1.model.DocumentResult result;

			if (rendition.getId() != null && !rendition.getId().isEmpty()) {
				ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
				@SuppressWarnings("unchecked")
				Map<String, Object> documentMap = mapper.convertValue(cr, Map.class);
				result = client.create(rendition.getId(), documentMap);
			} else {
				ObjectMapper mapper = daoHelper.createConfiguredObjectMapper();
				@SuppressWarnings("unchecked")
				Map<String, Object> documentMap = mapper.convertValue(cr, Map.class);
				result = client.create(documentMap);
			}

			String documentId = result.getId();
			String documentRevision = result.getRev();

			log.debug("Created rendition document: " + documentId);

			if (contentStream != null && contentStream.getStream() != null) {
				try {
					String attachmentName = "content";
					String contentType = contentStream.getMimeType() != null ?
						contentStream.getMimeType() : "application/octet-stream";

					String newRevision = client.createAttachment(
						documentId,
						documentRevision,
						attachmentName,
						contentStream.getStream(),
						contentType
					);

					log.debug("Stored binary content as attachment for rendition: " + documentId + " (revision: " + newRevision + ")");

				} catch (Exception attachmentError) {
					log.warn("Failed to store binary content as attachment for rendition: " + documentId + ". Content stored as metadata only.", attachmentError);
					// The bytes are NOT stored. Callers that hash the stream as it goes past
					// would otherwise hold a digest of content nobody has and record it as the
					// digest of the stored copy — the byte count cannot see this, because the
					// SDK may have read the whole stream before the write failed. Saying so is
					// the only way that caller can tell.
					renditionContentStored.set(Boolean.FALSE);
				}
			}

			return documentId;

		} catch (Exception e) {
			log.error("Error creating rendition in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to create rendition", e);
		}
	}

	public void updateAttachment(String repositoryId, AttachmentNode attachment, ContentStream contentStream) {
		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);

			CouchAttachmentNode can = new CouchAttachmentNode(attachment);

			Document currentDoc = client.get(attachment.getId());
			if (currentDoc != null && currentDoc.getRev() != null) {
				can.setRevision(currentDoc.getRev());
				log.debug("Set current revision for attachment update: " + currentDoc.getRev());
			} else {
				log.warn("Could not retrieve current revision for attachment: " + attachment.getId());
			}

			if (contentStream != null && contentStream.getStream() != null) {
				// Save old metadata for compensating rollback on binary failure
				String oldMimeType = can.getMimeType();
				long oldLength = can.getLength();
				String oldName = can.getName();

				// STAGE 1: Update metadata first.
				//
				// The comment that used to sit here claimed CouchDB preserves existing
				// _attachments stubs when they are not mentioned in the update body. It does not:
				// this update serialises the POJO and POSTs it, and a body without _attachments
				// REPLACES the document, dropping the binary. That is why stage 2 has to re-upload
				// it, and why a read landing between the two stages finds a document with no
				// attachment at all and fails with "Content stream InputStream is null!" — an
				// intermittent HTTP 500 on an ordinary read, reproduced against a live server
				// during back-to-back appends.
				//
				// Carrying the stub forward closes that window: CouchDB keeps the existing binary
				// when the body declares it as a stub, so the document is never attachment-less.
				// Stage 2 then replaces the binary as before.
				boolean metadataUpdated = false;
				if (contentStream.getMimeType() != null || contentStream.getLength() >= 0 || contentStream.getFileName() != null) {
					can.setMimeType(contentStream.getMimeType());
					can.setLength(contentStream.getLength());
					can.setName(contentStream.getFileName());
					client.updatePreservingAttachments(can, currentDoc);
					metadataUpdated = true;
					log.debug("STAGE1: Updated attachment metadata for: " + attachment.getId());
				}

				// STAGE 2: Upload binary
				try {
					// A SECOND read, on purpose. It is not the same as the unconditional GET at the
					// top of this method: it happens later, so it can recover from a transient
					// failure of that one (get() returns null for any error, not just 404) and it
					// sees a revision advanced in between. Removing it (ledger V3) let a null or
					// stale _rev reach the binary upload; it was put back.
					Document updatedDoc = client.get(attachment.getId());
					String revisionToUse = updatedDoc != null ? updatedDoc.getRev() : can.getRevision();

					String attachmentName = "content";
					String contentType = contentStream.getMimeType() != null ?
						contentStream.getMimeType() : "application/octet-stream";

					String newRevision = client.createAttachment(
						attachment.getId(),
						revisionToUse,
						attachmentName,
						contentStream.getStream(),
						contentType
					);
					log.debug("STAGE2: Updated binary content for: " + attachment.getId() + " (revision: " + newRevision + ")");
				} catch (Exception binaryEx) {
					// STAGE 2 failed: Compensating rollback of metadata to original values
					if (metadataUpdated) {
						log.error("STAGE2 binary upload failed for " + attachment.getId()
							+ ", rolling back metadata to original values", binaryEx);
						try {
							Document latestDoc = client.get(attachment.getId());
							if (latestDoc != null && latestDoc.getRev() != null) {
								can.setRevision(latestDoc.getRev());
								can.setMimeType(oldMimeType);
								can.setLength(oldLength);
								can.setName(oldName);
								// Attachment-preserving, like stage 1. A plain update here would post a
								// body without _attachments and delete the binary — so the compensation
								// for "the new binary failed to upload" would be "the OLD binary is gone
								// too", which is worse than the failure it is compensating for.
								client.updatePreservingAttachments(can, latestDoc);
								log.info("Rollback successful: metadata restored for " + attachment.getId());
							} else {
								// Cannot get latest rev — rollback impossible
								throw new AttachmentUpdateRollbackException(
									attachment.getId(), oldMimeType, oldLength, oldName,
									binaryEx, new IllegalStateException("Could not retrieve latest revision for rollback"));
							}
						} catch (AttachmentUpdateRollbackException rbe) {
							throw rbe; // re-throw our own exception
						} catch (Exception rollbackEx) {
							// Rollback itself failed — metadata is inconsistent
							throw new AttachmentUpdateRollbackException(
								attachment.getId(), oldMimeType, oldLength, oldName,
								binaryEx, rollbackEx);
						}
					}
					throw new RuntimeException("Failed to upload binary content for attachment " + attachment.getId(), binaryEx);
				}
			} else {
				// Metadata-only update (no binary content change).
				//
				// "No binary change" is exactly why this must preserve the stub: a plain update
				// posts a body without _attachments, so a path whose whole point is to leave the
				// binary alone would silently delete it.
				if (contentStream != null) {
					can.setMimeType(contentStream.getMimeType());
					can.setLength(contentStream.getLength());
					can.setName(contentStream.getFileName());
				}
				client.updatePreservingAttachments(can, currentDoc);
				log.debug("Updated attachment metadata (no binary) for: " + attachment.getId());
			}

		} catch (AttachmentUpdateRollbackException e) {
			throw e; // preserve specific exception type for callers
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			log.error("Error updating attachment: " + attachment.getId() + " in repository: " + repositoryId, e);
			throw new RuntimeException("Failed to update attachment", e);
		}
	}

	public Long getAttachmentActualSize(String repositoryId, String attachmentId) {
		if (attachmentId == null || attachmentId.trim().isEmpty()) {
			return null;
		}

		try {
			CloudantClientWrapper client = connectorPool.getClient(repositoryId);
			if (client == null) {
				throw new IllegalStateException("there is no CouchDB client for '" + repositoryId
						+ "', so the size of attachment '" + attachmentId + "' cannot be"
						+ " established");
			}

			Long size = client.getAttachmentSize(attachmentId, "content");
			if (size != null && size >= 0) {
				return size;
			}
			// The wrapper answers null when the document carries no `content` attachment.
			return null;

		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			// A null size is read as "unknown, use the metadata length" — which is exactly
			// the number the fixity check is trying to CORROBORATE. Handing back the
			// document's own claim when the stored bytes could not be measured turns the
			// check into a comparison of a number with itself.
			log.error("Error retrieving attachment size for " + attachmentId + ": " + e.getMessage(), e);
			throw new IllegalStateException("the stored size of attachment '" + attachmentId
					+ "' in '" + repositoryId + "' could not be read; this is NOT a finding"
					+ " that it matches the recorded length", e);
		}
	}
}
