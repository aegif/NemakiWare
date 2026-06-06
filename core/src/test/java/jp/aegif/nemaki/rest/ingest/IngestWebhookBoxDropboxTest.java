package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Box (Webhooks V2) and Dropbox webhook receive paths: GET challenge handshake,
 * signature verification, and event dispatch to an incremental fetch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestWebhookBoxDropboxTest {

    @Mock private ConnectorDefinitionService connectorDefinitionService;
    @Mock private IngestSchedulerService schedulerService;
    @Mock private ImportProfileDefinitionService profileService;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks private IngestWebhookController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        // Allow any delegated fetch so the dispatch path reaches "accepted".
        IngestSchedulerService.DelegatedAuthorization auth =
                org.mockito.Mockito.mock(IngestSchedulerService.DelegatedAuthorization.class);
        when(auth.isAllowed()).thenReturn(true);
        when(auth.getCallContext()).thenReturn(null);
        when(schedulerService.authorizeDelegatedFetch(any(), any())).thenReturn(auth);
    }

    private ConnectorDefinition connector(String id, String system, String secret) {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId(id);
        c.setEnabled(true);
        c.setSourceSystem(system);
        c.setSourceArchetype(SourceArchetype.FILE_SHARE);
        c.setWebhookSecret(secret);
        when(connectorDefinitionService.get(id)).thenReturn(c);
        return c;
    }

    private ImportProfileDefinition profileFor(String connId, Map<String, String> schedulerParams) {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId("p-" + connId);
        p.setRepositoryId("bedroom");
        p.setEnabled(true);
        p.setDefaultConnectorId(connId);
        p.setSchedulerParams(schedulerParams);
        when(profileService.list()).thenReturn(List.of(p));
        return p;
    }

    private static String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hmacBase64(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    // ── Dropbox GET challenge ──

    @Test
    void dropboxChallenge_echoesChallenge() throws Exception {
        connector("c-dbx", "dropbox", "s");
        mockMvc.perform(get("/v1/ingest-webhook/c-dbx").param("challenge", "abc123"))
                .andExpect(status().isOk())
                .andExpect(content().string("abc123"));
    }

    @Test
    void dropboxChallenge_setsTextPlainAndNosniff() throws Exception {
        connector("c-dbx", "dropbox", "s");
        mockMvc.perform(get("/v1/ingest-webhook/c-dbx").param("challenge", "abc123"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void dropboxChallenge_tooLong_returns404() throws Exception {
        connector("c-dbx", "dropbox", "s");
        String huge = "a".repeat(1025);
        mockMvc.perform(get("/v1/ingest-webhook/c-dbx").param("challenge", huge))
                .andExpect(status().isNotFound());
    }

    @Test
    void dropboxChallenge_nonDropboxConnector_returns404() throws Exception {
        connector("c-box", "box", "s");
        mockMvc.perform(get("/v1/ingest-webhook/c-box").param("challenge", "abc123"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dropboxChallenge_missingChallenge_returns404() throws Exception {
        connector("c-dbx", "dropbox", "s");
        mockMvc.perform(get("/v1/ingest-webhook/c-dbx"))
                .andExpect(status().isNotFound());
    }

    // ── Dropbox notification ──

    @Test
    void dropboxNotification_validSignature_triggersFetch() throws Exception {
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        String body = "{\"list_folder\":{\"accounts\":[\"dbid:AAA\"]},\"delta\":{\"users\":[1]}}";
        when(httpRequest.getHeader("X-Dropbox-Signature")).thenReturn(hmacHex(secret, body));

        mockMvc.perform(post("/v1/ingest-webhook/c-dbx")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")));

        verify(schedulerService).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void dropboxNotification_invalidSignature_returns401() throws Exception {
        connector("c-dbx", "dropbox", "dbxsecret");
        String body = "{\"list_folder\":{\"accounts\":[\"dbid:AAA\"]}}";
        when(httpRequest.getHeader("X-Dropbox-Signature")).thenReturn("deadbeef");

        mockMvc.perform(post("/v1/ingest-webhook/c-dbx")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    // ── Box notification ──

    private void stubBoxHeaders(String secret, String body, String ts) throws Exception {
        when(httpRequest.getHeader("BOX-SIGNATURE-VERSION")).thenReturn("1");
        when(httpRequest.getHeader("BOX-SIGNATURE-ALGORITHM")).thenReturn("HmacSHA256");
        when(httpRequest.getHeader("BOX-DELIVERY-TIMESTAMP")).thenReturn(ts);
        when(httpRequest.getHeader("BOX-SIGNATURE-PRIMARY")).thenReturn(hmacBase64(secret, body + ts));
    }

    @Test
    void boxEvent_fileUploaded_validSignature_triggersFetch() throws Exception {
        String secret = "boxsecret";
        connector("c-box", "box", secret);
        profileFor("c-box", Map.of("folderId", "123"));
        String body = "{\"trigger\":\"FILE.UPLOADED\",\"source\":{\"type\":\"file\",\"id\":\"f1\",\"parent\":{\"id\":\"123\"}}}";
        String ts = OffsetDateTime.now(ZoneOffset.UTC).toString();
        stubBoxHeaders(secret, body, ts);

        mockMvc.perform(post("/v1/ingest-webhook/c-box")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")))
                .andExpect(content().string(containsString("\"folderId\":\"123\"")));

        verify(schedulerService).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void boxEvent_invalidSignature_returns401() throws Exception {
        connector("c-box", "box", "boxsecret");
        String body = "{\"trigger\":\"FILE.UPLOADED\",\"source\":{\"type\":\"file\",\"id\":\"f1\",\"parent\":{\"id\":\"123\"}}}";
        String ts = OffsetDateTime.now(ZoneOffset.UTC).toString();
        when(httpRequest.getHeader("BOX-SIGNATURE-VERSION")).thenReturn("1");
        when(httpRequest.getHeader("BOX-SIGNATURE-ALGORITHM")).thenReturn("HmacSHA256");
        when(httpRequest.getHeader("BOX-DELIVERY-TIMESTAMP")).thenReturn(ts);
        when(httpRequest.getHeader("BOX-SIGNATURE-PRIMARY")).thenReturn("d2hhdGV2ZXI=");

        mockMvc.perform(post("/v1/ingest-webhook/c-box")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void boxEvent_staleTimestamp_returns401() throws Exception {
        String secret = "boxsecret";
        connector("c-box", "box", secret);
        String body = "{\"trigger\":\"FILE.UPLOADED\",\"source\":{\"type\":\"file\",\"id\":\"f1\",\"parent\":{\"id\":\"123\"}}}";
        // 30 minutes ago — outside the 10-minute replay window.
        String ts = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30).toString();
        stubBoxHeaders(secret, body, ts);

        mockMvc.perform(post("/v1/ingest-webhook/c-box")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void boxEvent_nonFileTrigger_ignored() throws Exception {
        String secret = "boxsecret";
        connector("c-box", "box", secret);
        String body = "{\"trigger\":\"FOLDER.CREATED\",\"source\":{\"type\":\"folder\",\"id\":\"123\"}}";
        String ts = OffsetDateTime.now(ZoneOffset.UTC).toString();
        stubBoxHeaders(secret, body, ts);

        mockMvc.perform(post("/v1/ingest-webhook/c-box")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"ignored\"")));
    }
}
