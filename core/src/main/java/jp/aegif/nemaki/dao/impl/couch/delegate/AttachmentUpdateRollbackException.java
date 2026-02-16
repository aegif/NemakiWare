package jp.aegif.nemaki.dao.impl.couch.delegate;

/**
 * Thrown when updateAttachment's binary upload (STAGE2) fails AND the
 * compensating metadata rollback also fails.
 * <p>
 * In this state the attachment document in CouchDB has:
 * <ul>
 *   <li>metadata (mimeType, length, name) = new values from the failed update</li>
 *   <li>binary content = old content (unchanged, since STAGE2 never completed)</li>
 * </ul>
 * Callers should invalidate caches for this attachment and, if possible,
 * propagate the error to the CMIS client so the operation can be retried.
 */
public class AttachmentUpdateRollbackException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String attachmentId;
	private final String oldMimeType;
	private final long oldLength;
	private final String oldName;

	public AttachmentUpdateRollbackException(String attachmentId,
			String oldMimeType, long oldLength, String oldName,
			Throwable binaryCause, Throwable rollbackCause) {
		super("Binary upload failed and metadata rollback also failed for attachment "
				+ attachmentId + " (metadata remains at new values; old mimeType="
				+ oldMimeType + ", old length=" + oldLength + ")", binaryCause);
		if (rollbackCause != null) {
			addSuppressed(rollbackCause);
		}
		this.attachmentId = attachmentId;
		this.oldMimeType = oldMimeType;
		this.oldLength = oldLength;
		this.oldName = oldName;
	}

	public String getAttachmentId() { return attachmentId; }
	public String getOldMimeType() { return oldMimeType; }
	public long getOldLength() { return oldLength; }
	public String getOldName() { return oldName; }
}
