package jp.aegif.nemaki.rest.ingest.mail;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Gmail API connector adapter — fetches messages via Google Gmail API.
 *
 * <p>Uses the same Google API client initialization pattern as CloudDriveServiceImpl.
 * Access token is passed from the UI OAuth flow.
 */
public class GmailConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(GmailConnectorAdapter.class);
    private static final String USER_ME = "me";

    private final Gmail gmailService;

    public GmailConnectorAdapter(String accessToken) throws Exception {
        GoogleCredentials credentials = GoogleCredentials.create(
                new AccessToken(accessToken, new Date(System.currentTimeMillis() + 3600_000)));
        gmailService = new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("NemakiWare")
                .build();
    }

    /**
     * Summary of a Gmail message.
     */
    public record GmailMessageSummary(
            String id,
            String threadId,
            String subject,
            String from,
            long internalDate) {}

    /**
     * List messages matching a query.
     *
     * @param query Gmail search query (e.g. "in:inbox is:unread", "newer_than:1d")
     * @param maxResults max messages to return
     */
    public List<GmailMessageSummary> listMessages(String query, int maxResults) throws Exception {
        ListMessagesResponse response = gmailService.users().messages()
                .list(USER_ME)
                .setQ(query != null ? query : "in:inbox")
                .setMaxResults((long) maxResults)
                .execute();

        List<Message> messages = response.getMessages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<GmailMessageSummary> summaries = new ArrayList<>();
        for (Message msgRef : messages) {
            try {
                Message msg = gmailService.users().messages()
                        .get(USER_ME, msgRef.getId())
                        .setFormat("metadata")
                        .setMetadataHeaders(List.of("Subject", "From"))
                        .execute();

                String subject = null;
                String from = null;
                if (msg.getPayload() != null && msg.getPayload().getHeaders() != null) {
                    for (var header : msg.getPayload().getHeaders()) {
                        if ("Subject".equalsIgnoreCase(header.getName())) subject = header.getValue();
                        if ("From".equalsIgnoreCase(header.getName())) from = header.getValue();
                    }
                }
                summaries.add(new GmailMessageSummary(
                        msg.getId(), msg.getThreadId(), subject, from,
                        msg.getInternalDate() != null ? msg.getInternalDate() : 0));
            } catch (Exception e) {
                logger.warn("Failed to get Gmail message metadata {}: {}", msgRef.getId(), e.getMessage());
            }
        }
        return summaries;
    }

    /**
     * Fetch a single message as raw .eml bytes (RFC 2822).
     */
    public InputStream fetchRawMessage(String messageId) throws Exception {
        Message msg = gmailService.users().messages()
                .get(USER_ME, messageId)
                .setFormat("raw")
                .execute();

        String raw = msg.getRaw();
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("Gmail returned empty raw message for " + messageId);
        }
        // Gmail returns base64url-encoded RFC 2822 message
        byte[] emlBytes = Base64.getUrlDecoder().decode(raw);
        return new ByteArrayInputStream(emlBytes);
    }
}
