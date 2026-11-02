package jp.aegif.nemaki.dao.impl.couch.connector;

/**
 * An update that failed BEFORE its request went to CouchDB, so the store was not written by it.
 *
 * <p>A {@code RuntimeException} subtype with the message the untyped failure carried, so every caller that treats a
 * failed update as a failure keeps doing exactly that. What it adds is the one fact a caller that created something for
 * the write needs: whether the store may hold the write. A failure after the request went out says nothing either way —
 * the write may have landed and its answer been lost — and that caller must then keep what it created. Without the name,
 * a document that could not be serialised, or had no revision, read the same as a timeout, and content created for it
 * was kept as if the store might point at it (closing batch 39 review, Codex P2 and subagent F2).
 */
public class CouchUpdateNotSentException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public CouchUpdateNotSentException(String message, Throwable cause) {
		super(message, cause);
	}
}
