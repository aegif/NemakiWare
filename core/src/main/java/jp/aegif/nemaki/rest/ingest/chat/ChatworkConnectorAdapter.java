package jp.aegif.nemaki.rest.ingest.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Chatwork API connector adapter — fetches room messages and files.
 *
 * <p>Uses Chatwork API v2 with API token ({@code X-ChatWorkToken} header).
 *
 * <p><b>Limitations:</b>
 * <ul>
 *   <li>Message history: max 100 messages per request, no pagination</li>
 *   <li>{@code force=0}: returns only new messages since last API call (differential)</li>
 *   <li>{@code force=1}: returns latest 100 messages (for initial sync)</li>
 *   <li>No timestamp-based filtering — checkpoint relies on message ID</li>
 *   <li>No thread/reply concept — all messages are flat</li>
 * </ul>
 *
 * @see <a href="https://developer.chatwork.com/reference">Chatwork API Reference</a>
 */
public class ChatworkConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ChatworkConnectorAdapter.class);
    private static final String DEFAULT_API = "https://api.chatwork.com/v2";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiToken;
    private final String apiBase;
    private final HttpClient httpClient;

    public ChatworkConnectorAdapter(String apiToken) {
        this(apiToken, DEFAULT_API);
    }

    public ChatworkConnectorAdapter(String apiToken, String apiBase) {
        this.apiToken = apiToken;
        this.apiBase = apiBase;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record ChatworkRoom(String id, String name, String type, int messageNum, int fileNum) {}

    public record ChatworkMessage(String messageId, String accountId, String accountName,
                                   String body, long sendTime, long updateTime) {}

    public record ChatworkFile(String fileId, String accountId, String name,
                                long size, String downloadUrl) {}

    /**
     * List rooms the user has access to.
     */
    public List<ChatworkRoom> listRooms() throws Exception {
        HttpResponse<String> response = get("/rooms");
        JsonNode root = MAPPER.readTree(response.body());
        if (!root.isArray()) return List.of();

        List<ChatworkRoom> rooms = new ArrayList<>();
        for (JsonNode room : root) {
            rooms.add(new ChatworkRoom(
                    room.path("room_id").asText(),
                    room.path("name").asText(),
                    room.path("type").asText(),
                    room.path("message_num").asInt(0),
                    room.path("file_num").asInt(0)
            ));
        }
        return rooms;
    }

    /**
     * Get messages from a room.
     *
     * @param roomId Chatwork room ID
     * @param force  true = latest 100 messages; false = new messages since last call
     * @return list of messages (max 100)
     */
    public List<ChatworkMessage> getMessages(String roomId, boolean force) throws Exception {
        String url = "/rooms/" + roomId + "/messages?force=" + (force ? "1" : "0");
        HttpResponse<String> response = get(url);

        // Chatwork returns 204 No Content when there are no new messages
        if (response.statusCode() == 204) return List.of();

        JsonNode root = MAPPER.readTree(response.body());
        if (!root.isArray()) return List.of();

        List<ChatworkMessage> messages = new ArrayList<>();
        for (JsonNode msg : root) {
            JsonNode account = msg.path("account");
            messages.add(new ChatworkMessage(
                    msg.path("message_id").asText(),
                    account.path("account_id").asText(),
                    account.path("name").asText(),
                    msg.path("body").asText(""),
                    msg.path("send_time").asLong(0),
                    msg.path("update_time").asLong(0)
            ));
        }
        return messages;
    }

    /**
     * List files uploaded to a room.
     *
     * @param roomId Chatwork room ID
     * @return list of files (max 100)
     */
    public List<ChatworkFile> listFiles(String roomId) throws Exception {
        String url = "/rooms/" + roomId + "/files";
        HttpResponse<String> response = get(url);

        if (response.statusCode() == 204) return List.of();

        JsonNode root = MAPPER.readTree(response.body());
        if (!root.isArray()) return List.of();

        List<ChatworkFile> files = new ArrayList<>();
        for (JsonNode file : root) {
            files.add(new ChatworkFile(
                    file.path("file_id").asText(),
                    file.path("account").path("account_id").asText(),
                    file.path("filename").asText(),
                    file.path("filesize").asLong(0),
                    null // download URL requires separate call
            ));
        }
        return files;
    }

    /**
     * Get a file's download URL.
     *
     * @param roomId Chatwork room ID
     * @param fileId file ID
     * @return temporary download URL
     */
    public String getFileDownloadUrl(String roomId, String fileId) throws Exception {
        String url = "/rooms/" + roomId + "/files/" + fileId + "?create_download_url=1";
        HttpResponse<String> response = get(url);
        JsonNode root = MAPPER.readTree(response.body());
        return root.path("download_url").asText(null);
    }

    /**
     * Download a file by its temporary URL.
     */
    public InputStream downloadFile(String downloadUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Chatwork file download error " + response.statusCode());
        }
        return response.body();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .header("X-ChatWorkToken", apiToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw new RuntimeException("Chatwork API error " + response.statusCode() + ": " + response.body());
        }
        return response;
    }
}
