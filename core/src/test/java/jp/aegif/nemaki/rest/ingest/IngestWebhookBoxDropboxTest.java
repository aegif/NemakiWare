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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
        // The receiver reads through getOrRefuse; get() is left unstubbed so a receiver that
        // went back to it would find no connector here.
        when(connectorDefinitionService.getOrRefuse(id)).thenReturn(c);
        return c;
    }

    private ImportProfileDefinition profileFor(String connId, Map<String, String> schedulerParams) {
        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId("p-" + connId);
        p.setRepositoryId("bedroom");
        p.setEnabled(true);
        p.setDefaultConnectorId(connId);
        p.setSchedulerParams(schedulerParams);
        // The receiver reads its recipients from the _all_docs walk; list() — the selector —
        // is deliberately left unstubbed (an empty answer), so a receiver that went back to
        // it would find no profile here.
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(p), List.of()));
        return p;
    }

    private static ImportProfileDefinitionService.OwnedProfiles owned(
            List<ImportProfileDefinition> profiles,
            List<ImportProfileDefinitionService.UninterpretableRow> uninterpretable) {
        return new ImportProfileDefinitionService.OwnedProfiles(profiles, uninterpretable);
    }

    private static final String DROPBOX_BODY =
            "{\"list_folder\":{\"accounts\":[\"dbid:AAA\"]},\"delta\":{\"users\":[1]}}";

    /** A signed Dropbox notification for {@code connectorId}, ready to perform. */
    private org.springframework.test.web.servlet.RequestBuilder signedDropboxPost(
            String connectorId, String secret) throws Exception {
        when(httpRequest.getHeader("X-Dropbox-Signature")).thenReturn(hmacHex(secret, DROPBOX_BODY));
        return post("/v1/ingest-webhook/" + connectorId)
                .contentType(MediaType.APPLICATION_JSON).content(DROPBOX_BODY);
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

    // ── Recipients are read without the index ──

    @Test
    void theRecipientsAreReadFromTheWalkNotTheSelector() throws Exception {
        // list() is a Mango selector: whenever the index did not show a row (its single page
        // showed none past the 200th; whether a rebuild hides existing rows is unmeasured)
        // the receiver said no_profile with a 200, and the sender's event was consumed with
        // nothing captured. Here the selector shows NOTHING and only the walk knows the
        // profile: a receiver that still asks the selector answers no_profile.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        ImportProfileDefinition p = profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        when(profileService.list()).thenReturn(List.of());
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(p), List.of()));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")));

        verify(profileService, never()).list();
        verify(schedulerService).authorizeDelegatedFetch(any(), any());
    }

    // ── A recipient the walk could not read is refused, not left out ──

    @Test
    void aRecipientRowThatCannotBeInterpretedIsA503NotNoProfile() throws Exception {
        // The walk skipped a row it could not read as a profile. That row names THIS
        // connector as its default: answering from the readable rows alone would say
        // no_profile (200) — the sender's event delivered to nobody.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(), List.of(
                new ImportProfileDefinitionService.UninterpretableRow(
                        "import_profile_definition:p-broken", "p-broken", "c-dbx", null, null, false,
                        "retentionDays: not a number"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("could not be read")));

        verify(schedulerService, never()).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void aRecipientRowNamedThroughAllowedConnectorsIsRefusedToo() throws Exception {
        // The other way a profile names a connector: allowedConnectorIds. A row whose
        // default is someone else but whose allowed list has this connector is a recipient.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(), List.of(
                new ImportProfileDefinitionService.UninterpretableRow(
                        "import_profile_definition:p-broken", "p-broken", "c-other",
                        List.of("c-a", "c-dbx"), null, false, "retentionDays: not a number"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isServiceUnavailable());

        verify(schedulerService, never()).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void aBrokenRowOfAnotherConnectorDoesNotStopTheDispatch() throws Exception {
        // The control: one broken row must stop the webhooks of the connector it names and
        // no other. Refusing every dispatch over any broken row is the outage shape.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        ImportProfileDefinition p = profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(p), List.of(
                new ImportProfileDefinitionService.UninterpretableRow(
                        "import_profile_definition:p-theirs", "p-theirs", "c-other",
                        List.of("c-other"), null, false, "retentionDays: not a number"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")));

        verify(schedulerService).authorizeDelegatedFetch(any(), any());
    }

    // ── The connector read itself: a read that did not answer is not absence ──

    @Test
    void aConnectorReadThatCouldNotBeAnsweredIsA503NotA401() throws Exception {
        // getOrRefuse throws when the id-addressed read failed, or when the row exists but
        // could not be read as this connector. The receiver used get(), whose null covers a
        // failed read too, and answered 401 — "your signature is wrong" — for a read that
        // did not answer; and a read that threw sat outside the guarded try (500). No
        // index-free walk is made here: a walk per unauthenticated request is an amplifier.
        when(connectorDefinitionService.getOrRefuse("c-unanswered")).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "connector c-unanswered could not be read, so whether it exists cannot"
                                + " be established"));

        // assertDoesNotThrow: the failure under measurement is an exception escaping the
        // controller, and a test that dies on it is "harness broken", not a firing.
        assertDoesNotThrow(() -> mockMvc.perform(signedDropboxPost("c-unanswered", "irrelevant"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(content().string(containsString("retry"))),
                "a connector read that could not be answered was reported as absence (401) "
                        + "or escaped as a 500");
        verify(connectorDefinitionService, never()).existsIndexFree(any());
    }

    @Test
    void anAbsentConnectorIsStill401() throws Exception {
        // The control: a connector that both reads answered "no such row" for keeps the
        // uniform 401, so the status code still does not enumerate ids — and no walk is
        // made to second-guess the answer.
        when(connectorDefinitionService.getOrRefuse("c-nobody")).thenReturn(null);

        mockMvc.perform(signedDropboxPost("c-nobody", "irrelevant"))
                .andExpect(status().isUnauthorized());
        verify(connectorDefinitionService, never()).existsIndexFree(any());
    }

    @Test
    void theHandshakeAnswers503WhenTheConnectorReadCouldNotBeAnswered() throws Exception {
        // The Dropbox URL-verification GET resolved the connector the same way and answered
        // 404 for a read that did not answer — failing the operator's verification as if the
        // id were wrong.
        when(connectorDefinitionService.getOrRefuse("c-unanswered")).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "connector c-unanswered could not be read"));

        assertDoesNotThrow(() -> mockMvc.perform(get("/v1/ingest-webhook/c-unanswered")
                        .param("challenge", "abc123"))
                        .andExpect(status().isServiceUnavailable()),
                "the handshake reported a read that did not answer as 404, or escaped");
    }

    @Test
    void theHandshakeStill404sAnAbsentConnector() throws Exception {
        when(connectorDefinitionService.getOrRefuse("c-nobody")).thenReturn(null);

        mockMvc.perform(get("/v1/ingest-webhook/c-nobody").param("challenge", "abc123"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBrokenRecipientRowRefusesTheDispatchEvenBesideReadableOnes() throws Exception {
        // The documented rule is "refuse the dispatch of the connector the broken row
        // names" — not "dispatch to the readable rows and leave this one out". Every other
        // broken-row lock had no readable recipient beside it, so a receiver that refused
        // only when nothing else was readable passed them all. A review found the gap.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        ImportProfileDefinition readable = profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(readable), List.of(
                new ImportProfileDefinitionService.UninterpretableRow(
                        "import_profile_definition:p-broken", "p-broken", "c-dbx", null, null, false,
                        "retentionDays: not a number"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isServiceUnavailable());

        verify(schedulerService, never()).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void aRowWhoseAddresseeCannotBeReadRefusesEveryConnectorsDispatch() throws Exception {
        // The row's connector fields could not be read, so whom it addresses is unknown: the
        // receiver must not read "names nobody I can see" as "does not name me". This is the
        // receiver-side lock; the service-side one only shows the record answering true.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        ImportProfileDefinition readable = profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(readable), List.of(
                new ImportProfileDefinitionService.UninterpretableRow(
                        "import_profile_definition:p-odd", "p-odd", null, null, null, true,
                        "retentionDays: not a number"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isServiceUnavailable());

        verify(schedulerService, never()).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void aBrokenRowWhoseArchetypesExcludeTheConnectorDoesNotStopTheDispatch() throws Exception {
        // The broken row names this connector, but its raw allowedArchetypes plainly exclude
        // the connector's archetype (FILE_SHARE): a readable row with the same list would be
        // filtered out by the receiver, so refusing on the name alone stopped a dispatch the
        // row could never have received. A review found the over-throw.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        ImportProfileDefinition readable = profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(readable), List.of(
                new ImportProfileDefinitionService.UninterpretableRow(
                        "import_profile_definition:p-mail-only", "p-mail-only", "c-dbx", null,
                        List.of("MESSAGE_CONTEXT"), false, "retentionDays: not a number"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")));

        verify(schedulerService).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void aBrokenRowWithAnUnknownArchetypeNameStillStopsTheDispatch() throws Exception {
        // "file_share" is not an archetype this node knows (the enum reads its exact names),
        // so the row cannot be established to exclude the connector — whatever its author
        // meant, a readable row would never have carried it. Letting it through would be the
        // same silent loss the name-only refusal exists to prevent, one field down.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        ImportProfileDefinition readable = profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(readable), List.of(
                new ImportProfileDefinitionService.UninterpretableRow(
                        "import_profile_definition:p-miscased", "p-miscased", "c-dbx", null,
                        List.of("file_share"), false, "allowedArchetypes: not an archetype"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isServiceUnavailable());

        verify(schedulerService, never()).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void anUnwiredProfileServiceIsA503NotNoProfile() throws Exception {
        // The receiver answered "no profile" (200) when its profile service was not wired —
        // "could not ask" with the value of "asked, none", the arm this batch closes
        // everywhere else.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "profileService", null);

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isServiceUnavailable());

        verify(schedulerService, never()).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void aListingThatCannotBeCompletedIsA503NotNoProfile() throws Exception {
        // The walk refused: a row could not be read. That is neither "no profile" (200, the
        // sender takes the event as delivered) nor the generic 500 (our bug, nothing to wait
        // for) — 503 leaves the sender room to retry.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        when(profileService.listOwnedIndexFree()).thenThrow(
                new ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException(
                        "the profiles cannot be listed: a row of 'nemaki_conf' could not be read"));
        String body = "{\"list_folder\":{\"accounts\":[\"dbid:AAA\"]},\"delta\":{\"users\":[1]}}";
        when(httpRequest.getHeader("X-Dropbox-Signature")).thenReturn(hmacHex(secret, body));

        mockMvc.perform(post("/v1/ingest-webhook/c-dbx")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("retry")));

        verify(schedulerService, never()).authorizeDelegatedFetch(any(), any());
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
