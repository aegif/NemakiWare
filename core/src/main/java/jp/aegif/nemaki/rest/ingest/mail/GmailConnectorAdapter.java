package jp.aegif.nemaki.rest.ingest.mail;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Gmail API connector adapter — lists the messages a search matches, one time window at a time,
 * and reads each message's internal date and its raw RFC 2822 form.
 *
 * <p>The listing is NOT taken to be in any order. Gmail does not document one, and it is not
 * newest first in practice either (googleapis/google-api-nodejs-client#3508 shows dates out of
 * order); the orchestrator sorts by the internal date it reads per message.
 *
 * <p>Uses the same Google API client initialization pattern as CloudDriveServiceImpl.
 * Access token is passed from the UI OAuth flow.
 */
public class GmailConnectorAdapter {

    private static final String USER_ME = "me";
    /** Gmail's own maximum page size for messages.list. */
    static final int MAX_PAGE_SIZE = 500;
    /** Default cap on the listing requests one poll makes. */
    public static final int DEFAULT_MAX_LIST_REQUESTS = 50;
    /** How long one request is asked again on 429 / 503 before its failure is the caller's. */
    static final int RETRY_MAX_ELAPSED_MILLIS = 120_000;

    private final Gmail gmailService;

    /**
     * Create a Gmail adapter with an access token.
     *
     * <p>The token expiry is set to 1 hour from now as a safety bound.
     * For scheduled fetches, the scheduler creates a fresh adapter instance
     * each cycle, so tokens are renewed per cycle via {@code resolvePassword()}.
     * For service accounts with domain-wide delegation, the underlying Google
     * HTTP transport handles token refresh automatically.
     *
     * @param accessToken OAuth2 access token or service account credential
     */
    public GmailConnectorAdapter(String accessToken) throws Exception {
        this(accessToken, null);
    }

    /** Tests point the adapter at a local stub of the Gmail API; null is Gmail itself. */
    GmailConnectorAdapter(String accessToken, String rootUrl) throws Exception {
        GoogleCredentials credentials = GoogleCredentials.create(
                new AccessToken(accessToken, new Date(System.currentTimeMillis() + 3600_000)));
        HttpCredentialsAdapter auth = new HttpCredentialsAdapter(credentials);
        // Rate limited (429) or unavailable (503): asked again after a growing wait, as the other
        // adapters' shared client does. The Google client asks nothing again by default, so a
        // window's summary reads turned each 429 into a failure — a message dead-lettered for a
        // pause Gmail asked for. A 401 is answered as the failure it is: the token is a fixed
        // access token, and nothing here can refresh it.
        HttpRequestInitializer init = request -> {
            auth.initialize(request);
            request.setUnsuccessfulResponseHandler(new HttpBackOffUnsuccessfulResponseHandler(
                    new ExponentialBackOff.Builder().setMaxElapsedTimeMillis(RETRY_MAX_ELAPSED_MILLIS).build())
                    .setBackOffRequired(response -> response.getStatusCode() == 429 || response.getStatusCode() == 503));
        };
        Gmail.Builder builder = new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                init)
                .setApplicationName("NemakiWare");
        if (rootUrl != null) builder.setRootUrl(rootUrl);
        gmailService = builder.build();
    }

    /** One page of a listing: the ids on it, and the token of the next page (null: this is the last). */
    public record ListPage(List<String> ids, String nextPageToken) {}

    /**
     * Summary of a Gmail message.
     *
     * @param internalDate epoch milliseconds, or null when the message carried none — the
     *                     caller decides what a message it cannot place means
     */
    public record GmailMessageSummary(
            String id,
            String threadId,
            String subject,
            String from,
            Long internalDate) {}

    /**
     * The search Gmail is asked for: the profile's query, parenthesised so that an {@code OR} in it
     * does not take the time bounds into one of its arms, and the bounds in epoch SECONDS — a date
     * is read as midnight Pacific time. Neither bound is documented as inclusive or exclusive; the
     * caller chooses bounds that list what it needs under either reading.
     *
     * @param afterSeconds the lower bound, or null for none
     */
    static String search(String query, Long afterSeconds, long beforeSeconds) {
        StringBuilder q = new StringBuilder();
        if (query != null && !query.isBlank()) q.append('(').append(query.trim()).append(") ");
        if (afterSeconds != null) q.append("after:").append(afterSeconds).append(' ');
        q.append("before:").append(beforeSeconds);
        return q.toString();
    }

    /**
     * One page of the messages the search matches. A message without an id is not read around:
     * it cannot be fetched, skipped or named, so the page is refused.
     */
    public ListPage listPage(String query, Long afterSeconds, long beforeSeconds, int pageSize, String pageToken)
            throws IOException {
        var req = gmailService.users().messages()
                .list(USER_ME)
                .setQ(search(query, afterSeconds, beforeSeconds))
                .setMaxResults((long) Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE)));
        if (pageToken != null) req.setPageToken(pageToken);
        ListMessagesResponse response = req.execute();
        List<String> ids = new ArrayList<>();
        // No `messages` is how an empty result reads: Gmail omits the field rather than sending [].
        if (response.getMessages() != null) {
            for (Message ref : response.getMessages()) {
                if (ref == null || ref.getId() == null || ref.getId().isBlank()) {
                    throw new IllegalStateException("Gmail listed a message without an id");
                }
                ids.add(ref.getId());
            }
        }
        String next = response.getNextPageToken();
        return new ListPage(ids, next == null || next.isBlank() ? null : next);
    }

    /** A message's internal date and the headers the import request is named from. */
    public GmailMessageSummary summary(String messageId) throws IOException {
        Message msg = gmailService.users().messages()
                .get(USER_ME, messageId)
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
        // The id asked for, not the one answered: the listing named the message by it.
        return new GmailMessageSummary(messageId, msg.getThreadId(), subject, from, msg.getInternalDate());
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
