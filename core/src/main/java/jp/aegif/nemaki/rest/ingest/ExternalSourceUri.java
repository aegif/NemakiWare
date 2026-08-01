package jp.aegif.nemaki.rest.ingest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds canonical URIs for external source objects used as lineage inputs.
 *
 * <p>Pattern: {@code {sourceSystem}://tenant/{tenantId}/{objectTypePath}/{objectId}}
 *
 * <p>User-supplied segments (tenantId, objectId) are percent-encoded to ensure
 * valid URI syntax — after rejecting {@code ?} / {@code #} (raw or pre-encoded), because
 * encoding them first would smuggle URL-shaped values past
 * {@code ExternalAssetIdentity.parse}'s literal-character checks. Path structure segments
 * (sourceSystem, objectTypePath) are not encoded, so a delimiter there stays literal and is
 * caught by that downstream parse instead.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code google_drive://tenant/t1/files/fileId}</li>
 *   <li>{@code notion://workspace/ws1/pages/pageId}</li>
 *   <li>{@code salesforce://org/org1/records/Account/recId}</li>
 *   <li>{@code slack://workspace/ws1/channels/ch1/messages/msgId}</li>
 * </ul>
 */
public final class ExternalSourceUri {

    private ExternalSourceUri() {}

    /**
     * Builds a general-purpose external source URI.
     *
     * @param sourceSystem    canonical system name (e.g. "google_drive", "notion")
     * @param tenantId        workspace / tenant / org identifier (nullable — omitted if null)
     * @param objectTypePath  path segment(s) for the object type (e.g. "files", "pages", "records/Account")
     * @param objectId        stable external object ID
     */
    public static String build(String sourceSystem, String tenantId, String objectTypePath, String objectId) {
        if (sourceSystem == null || sourceSystem.isBlank()) {
            throw new IllegalArgumentException("sourceSystem must not be blank");
        }
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("objectId must not be blank");
        }
        StringBuilder sb = new StringBuilder(sourceSystem).append("://");
        if (tenantId != null && !tenantId.isBlank()) {
            sb.append("tenant/").append(encode(tenantId)).append('/');
        }
        if (objectTypePath != null && !objectTypePath.isBlank()) {
            sb.append(objectTypePath).append('/');
        }
        sb.append(encode(objectId));
        return sb.toString();
    }

    private static String encode(String value) {
        requireNoUriDelimiters(value);
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Rejects {@code ?} and {@code #} — raw or already percent-encoded — in a value that is
     * about to be percent-encoded into the URI.
     *
     * <p>Without this, encoding would turn a raw {@code ?} into {@code %3F} <em>before</em>
     * {@code ExternalAssetIdentity.parse} runs its literal-character checks, so a signed URL
     * supplied as an external object id would sail through validation and end up — reversibly —
     * inside a qualified name. Rejection over stripping, and rejection of the pre-encoded forms
     * too: a value carrying {@code %3F} is a URL that has already been through an encoder once,
     * which is the same smell one level down. The throw is absorbed by the producer's fail-open
     * boundary; losing the one capture is the deliberate, secure trade.
     */
    private static void requireNoUriDelimiters(String value) {
        if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("external identifier must not contain a query"
                    + " string or fragment delimiter — it is a URL, not an id; strip it in the"
                    + " connector");
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("%3f") || lower.contains("%23")) {
            throw new IllegalArgumentException("external identifier must not contain"
                    + " percent-encoded query/fragment delimiters — it is an encoded URL, not"
                    + " an id; strip it in the connector");
        }
    }

    /** Shorthand for file_share sources: {@code {system}://tenant/{tenant}/files/{fileId}} */
    public static String forFileShare(String sourceSystem, String tenantId, String fileId) {
        return build(sourceSystem, tenantId, "files", fileId);
    }

    /** Shorthand for compound_note pages: {@code {system}://workspace/{wsId}/pages/{pageId}} */
    public static String forNotePage(String sourceSystem, String workspaceId, String pageId) {
        return build(sourceSystem, workspaceId, "pages", pageId);
    }

    /** Shorthand for chat messages: {@code {system}://workspace/{wsId}/channels/{chId}/messages/{msgId}} */
    public static String forChatMessage(String sourceSystem, String workspaceId,
                                        String channelId, String messageId) {
        return build(sourceSystem, workspaceId, "channels/" + channelId + "/messages", messageId);
    }

    /** Shorthand for business records: {@code {system}://org/{orgId}/records/{objectType}/{recordId}} */
    public static String forBusinessRecord(String sourceSystem, String orgId,
                                           String objectType, String recordId) {
        return build(sourceSystem, orgId, "records/" + objectType, recordId);
    }

    /** Shorthand for mail messages: {@code {sourceSystem}://tenant/{accountId}/mailboxes/{mailboxId}/messages/{messageStableId}} */
    public static String forMailMessage(String sourceSystem, String accountId,
                                        String mailboxId, String messageStableId) {
        return build(sourceSystem, accountId,
                "mailboxes/" + encodeMailboxSegment(mailboxId) + "/messages", messageStableId);
    }

    /** Shorthand for mail attachments: {@code {sourceSystem}://tenant/{accountId}/mailboxes/{mailboxId}/messages/{msgId}/attachments/{attachId}} */
    public static String forMailAttachment(String sourceSystem, String accountId, String mailboxId,
                                           String messageStableId, String attachmentId) {
        return build(sourceSystem, accountId,
                "mailboxes/" + encodeMailboxSegment(mailboxId) + "/messages/" + encode(messageStableId)
                        + "/attachments",
                attachmentId);
    }

    /**
     * Encodes a mailbox name, whose legitimate alphabet is wider than an object id's.
     *
     * <p>Almost nothing can be blanket-rejected here: an IMAP mailbox name is an arbitrary
     * {@code astring} (RFC 9051 §9), so {@code #news.comp.mail.misc} (the standardised
     * namespace convention, §5.1.2.1) and even {@code Questions?} are legal names a connector
     * passes through verbatim. Rejecting a character the grammar allows would silently lose
     * every lineage fact of that mailbox while the imports keep succeeding — the exact failure
     * mode the strict id policy was told off for in review, twice.
     *
     * <p>So URL detection here keys on the one feature only a URL has: a scheme
     * ({@code ://}). That catches a pasted signed URL whatever carries its token — query
     * string or OAuth-style fragment — while a query-shaped suffix without a scheme is
     * indistinguishable from a legal mailbox name and is let through, percent-encoded and
     * inert. The id-bearing segments keep the strict policy in {@link #encode}.
     */
    private static String encodeMailboxSegment(String value) {
        if (value.contains("://")) {
            throw new IllegalArgumentException("mailbox name must not carry a URI scheme —"
                    + " it is a URL, not a mailbox; strip it in the connector");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
