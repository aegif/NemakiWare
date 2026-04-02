package jp.aegif.nemaki.rest.ingest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds canonical URIs for external source objects used as lineage inputs.
 *
 * <p>Pattern: {@code {sourceSystem}://tenant/{tenantId}/{objectTypePath}/{objectId}}
 *
 * <p>User-supplied segments (tenantId, objectId) are percent-encoded to ensure
 * valid URI syntax. Path structure segments (sourceSystem, objectTypePath) are
 * assumed to be code-controlled and are not encoded.
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
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        return build(sourceSystem, accountId, "mailboxes/" + encode(mailboxId) + "/messages", messageStableId);
    }

    /** Shorthand for mail attachments: {@code {sourceSystem}://tenant/{accountId}/mailboxes/{mailboxId}/messages/{msgId}/attachments/{attachId}} */
    public static String forMailAttachment(String sourceSystem, String accountId, String mailboxId,
                                           String messageStableId, String attachmentId) {
        return build(sourceSystem, accountId,
                "mailboxes/" + encode(mailboxId) + "/messages/" + encode(messageStableId) + "/attachments",
                attachmentId);
    }
}
