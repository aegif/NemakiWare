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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @Mock private FetchSupport fetchSupport;

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
        // The receiver reads through resolveOrRefuse (the admin-gated subscription verbs
        // still use getOrRefuse); get() is left unstubbed so a receiver that went back to it
        // would find no connector here. selectorAnswered = true is the ordinary case — the
        // window tests below say otherwise, explicitly.
        when(connectorDefinitionService.getOrRefuse(id)).thenReturn(c);
        when(connectorDefinitionService.resolveOrRefuse(id))
                .thenReturn(new ConnectorDefinitionService.Resolution(c, true));
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
        when(connectorDefinitionService.resolveOrRefuse("c-unanswered")).thenThrow(
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
        when(connectorDefinitionService.resolveOrRefuse("c-nobody"))
                .thenReturn(new ConnectorDefinitionService.Resolution(null, true));

        mockMvc.perform(signedDropboxPost("c-nobody", "irrelevant"))
                .andExpect(status().isUnauthorized());
        verify(connectorDefinitionService, never()).existsIndexFree(any());
    }

    @Test
    void theHandshakeAnswers503WhenTheConnectorReadCouldNotBeAnswered() throws Exception {
        // The Dropbox URL-verification GET resolved the connector the same way and answered
        // 404 for a read that did not answer — failing the operator's verification as if the
        // id were wrong.
        when(connectorDefinitionService.resolveOrRefuse("c-unanswered")).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "connector c-unanswered could not be read"));

        assertDoesNotThrow(() -> mockMvc.perform(get("/v1/ingest-webhook/c-unanswered")
                        .param("challenge", "abc123"))
                        .andExpect(status().isServiceUnavailable()),
                "the handshake reported a read that did not answer as 404, or escaped");
    }

    @Test
    void theHandshakeStill404sAnAbsentConnector() throws Exception {
        when(connectorDefinitionService.resolveOrRefuse("c-nobody"))
                .thenReturn(new ConnectorDefinitionService.Resolution(null, true));

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
                        List.of(SourceArchetype.MESSAGE_CONTEXT), false, "retentionDays: not a number"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")));

        verify(schedulerService).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void aBrokenRowWhoseConnectorFieldsCannotBeReadButWhoseArchetypesExcludeTheConnectorDoesNotStopTheDispatch()
            throws Exception {
        // The connector fields cannot be read (the row may name anyone), but the archetype
        // list is readable and plainly excludes FILE_SHARE: a readable row with these fields
        // would have been filtered out on the archetype alone, whatever its connector fields
        // said. The first version folded both fields into one flag and refused; a review
        // found the over-throw.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        ImportProfileDefinition readable = profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        when(profileService.listOwnedIndexFree()).thenReturn(owned(List.of(readable), List.of(
                new ImportProfileDefinitionService.UninterpretableRow(
                        "import_profile_definition:p-mail-anyone", "p-mail-anyone", null, null,
                        List.of(SourceArchetype.MESSAGE_CONTEXT), true, "defaultConnectorId: not a string"))));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")));

        verify(schedulerService).authorizeDelegatedFetch(any(), any());
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

    // ── The deliveries this node accepted and did not fetch are RECORDED (R10 / R30) ──

    @Test
    void anAuthorisationThatCouldNotBeAskedIsRecordedAsAWebhookDeliveryRecord() throws Exception {
        // The row is marked by the service as a record — not by the shape of its
        // sourceObjectId, which is the caller's string (R10).
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        IngestSchedulerService.DelegatedAuthorization couldNotAsk =
                org.mockito.Mockito.mock(IngestSchedulerService.DelegatedAuthorization.class);
        when(couldNotAsk.isAllowed()).thenReturn(false);
        when(couldNotAsk.getDenialReason()).thenReturn(DenialReason.CREATOR_LOOKUP_FAILED);
        when(schedulerService.authorizeDelegatedFetch(any(), any())).thenReturn(couldNotAsk);
        when(fetchSupport.saveWebhookDeliveryRecordToDlq(any(), any())).thenReturn(true);

        mockMvc.perform(signedDropboxPost("c-dbx", secret)).andExpect(status().isOk());

        verify(fetchSupport).saveWebhookDeliveryRecordToDlq(any(),
                org.mockito.ArgumentMatchers.contains("could not be established"));
        verify(fetchSupport, never()).saveSourceNeverReadToDlq(any(), any());
    }

    @Test
    void aFetchThatCouldNotReadItsConfigurationIsRecorded() throws Exception {
        // The sender already has its 200. A fetch that could not read its own configuration
        // or checkpoint only logged; the authorisation arm records the same class (R30).
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        org.mockito.Mockito.doThrow(new jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                        .SettingUnreadableException("the checkpoint of p-c-dbx could not be read"))
                .when(schedulerService).executeFetch(any(), any(), any(), any());
        when(fetchSupport.saveWebhookDeliveryRecordToDlq(any(), any())).thenReturn(true);

        mockMvc.perform(signedDropboxPost("c-dbx", secret)).andExpect(status().isOk());

        verify(fetchSupport, org.mockito.Mockito.timeout(5000)).saveWebhookDeliveryRecordToDlq(
                any(), org.mockito.ArgumentMatchers.contains("could not read its configuration"));
    }

    @Test
    void anUnwiredPropertyManagerDoesNotAnswerNoAccessToken() throws Exception {
        // propertyManager is not a mock of this class, so @InjectMocks leaves it null — the
        // shape of a node without it. It answered null, and the caller said "No access
        // token" (400): a claim about the credential from a node that cannot read any (R28).
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("c-teams");
        c.setCredentialRef("secret.teams");
        java.lang.reflect.Method resolve = IngestWebhookController.class
                .getDeclaredMethod("resolveToken", ConnectorDefinition.class);
        resolve.setAccessible(true);

        java.lang.reflect.InvocationTargetException wrapped =
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> resolve.invoke(controller, c),
                        "an unwired node answered 'no access token'");
        org.junit.jupiter.api.Assertions.assertTrue(wrapped.getCause() instanceof
                        jp.aegif.nemaki.rest.controller.IntegrationSettingsService.SettingUnreadableException,
                "the refusal is not the typed one: " + wrapped.getCause());
    }

    // ── R2: uniqueness is established by the walk, AFTER the signature and the rate limit ──

    @Test
    void aPairHiddenFromTheIndexIsRefusedAfterTheSignature() throws Exception {
        // The index showed one row and the signature verified against it. The walk shows a
        // second row: the receiver must not run with the one the index happened to show.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        doThrow(new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                "connector c-dbx has 2 definition rows"))
                .when(connectorDefinitionService).refuseUnlessUniquelyDefined("c-dbx");

        assertDoesNotThrow(() -> mockMvc.perform(signedDropboxPost("c-dbx", secret))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(content().string(org.hamcrest.Matchers.not(
                                containsString("2 definition rows")))),
                "a pair the walk showed was run with, or the refusal escaped as a 500");
        verify(schedulerService, never()).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void theWalkIsMadeOnceTheSignatureVerified() throws Exception {
        // The over-throw guard and the placement lock in one: a verified event IS checked
        // (a receiver that dropped the call would pass every other test here, the mock's
        // default being "unique") and, checked clean, is dispatched.
        String secret = "dbxsecret";
        connector("c-dbx", "dropbox", secret);
        profileFor("c-dbx", Map.of("folderPath", "/Documents"));

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")));

        verify(connectorDefinitionService).refuseUnlessUniquelyDefined("c-dbx");
        verify(schedulerService).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void theWalkIsNotMadeForAnUnauthenticatedRequest() throws Exception {
        // dece81f7d: a walk of the configuration database per unauthenticated request is an
        // amplifier. A bad signature is answered 401 without the walk.
        connector("c-dbx", "dropbox", "dbxsecret");
        profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        when(httpRequest.getHeader("X-Dropbox-Signature")).thenReturn("not-a-signature");

        mockMvc.perform(post("/v1/ingest-webhook/c-dbx")
                        .contentType(MediaType.APPLICATION_JSON).content(DROPBOX_BODY))
                .andExpect(status().isUnauthorized());

        verify(connectorDefinitionService, never()).refuseUnlessUniquelyDefined(any());
    }

    private void callerIsAdmin() {
        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                org.mockito.Mockito.mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        when(ctx.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(httpRequest.getAttribute("CallContext")).thenReturn(ctx);
    }

    @Test
    void aSubscriptionIsNotMadeWithOneRowOfAPair() throws Exception {
        callerIsAdmin();
        connector("c-teams", "teams", "s");
        doThrow(new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                "connector c-teams has 2 definition rows"))
                .when(connectorDefinitionService).refuseUnlessUniquelyDefined("c-teams");

        assertDoesNotThrow(() -> mockMvc.perform(post("/v1/ingest-webhook/c-teams/subscribe")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isServiceUnavailable()),
                "a subscription was made with whichever row the index showed, or the refusal"
                        + " escaped as a 500");
    }

    @Test
    void aSubscriptionIsNotDeletedWithOneRowOfAPair() throws Exception {
        callerIsAdmin();
        connector("c-teams", "teams", "s");
        doThrow(new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                "connector c-teams has 2 definition rows"))
                .when(connectorDefinitionService).refuseUnlessUniquelyDefined("c-teams");

        assertDoesNotThrow(() -> mockMvc.perform(delete("/v1/ingest-webhook/c-teams/subscribe")
                        .param("subscriptionId", "sub-1"))
                        .andExpect(status().isServiceUnavailable()),
                "a subscription was deleted with whichever row the index showed, or the"
                        + " refusal escaped as a 500");
    }

    // ── R3: while the selector is down, every refusable answer is the SAME answer ──

    /** The connector was read while the Mango selector was down (R3's window). */
    private void readWhileTheSelectorWasDown(String id, ConnectorDefinition c) {
        when(connectorDefinitionService.resolveOrRefuse(id))
                .thenReturn(new ConnectorDefinitionService.Resolution(c, false));
    }

    /**
     * The answer a read that could not be answered produces. The window's answers have to be
     * THIS, body and all: a status that matches and a body that does not is still a pair an
     * unauthenticated caller can separate.
     */
    private String whatARefusedReadAnswers() throws Exception {
        when(connectorDefinitionService.resolveOrRefuse("c-refused")).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "connector c-refused could not be read"));
        return mockMvc.perform(signedDropboxPost("c-refused", "irrelevant"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
    }

    private String whatARefusedHandshakeAnswers() throws Exception {
        when(connectorDefinitionService.resolveOrRefuse("c-refused-get")).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "connector c-refused-get could not be read"));
        return mockMvc.perform(get("/v1/ingest-webhook/c-refused-get").param("challenge", "abc123"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aFailedSignatureIsTheRefusedReadsAnswerWhileTheSelectorIsDown() throws Exception {
        // (selector down, healthy deterministic row) answered 401 while (selector down, no
        // row) refused with 503, so one unauthenticated request told an id that has a
        // readable row from one that has none — the single thing this front door's
        // disclosure analysis keeps out of the answer (R3).
        String refused = whatARefusedReadAnswers();
        ConnectorDefinition c = connector("c-window", "dropbox", "the-real-secret");
        readWhileTheSelectorWasDown("c-window", c);
        when(httpRequest.getHeader("X-Dropbox-Signature")).thenReturn("not-the-signature");

        String answered = mockMvc.perform(post("/v1/ingest-webhook/c-window")
                        .contentType(MediaType.APPLICATION_JSON).content(DROPBOX_BODY))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertEquals(refused, answered,
                "the window's answer can be told apart from a read that could not be answered");
    }

    @Test
    void aDisabledConnectorIsTheRefusedReadsAnswerWhileTheSelectorIsDown() throws Exception {
        String refused = whatARefusedReadAnswers();
        ConnectorDefinition c = connector("c-off", "dropbox", "s");
        c.setEnabled(false);
        readWhileTheSelectorWasDown("c-off", c);

        String answered = mockMvc.perform(signedDropboxPost("c-off", "s"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertEquals(refused, answered,
                "a disabled row in the window can be told apart from a refused read");
    }

    @Test
    void aFailedSignatureIsStill401WhenTheSelectorAnswered() throws Exception {
        // The over-throw guard. Outside the window absence and a failed signature are both
        // 401 and nothing separates them; answering 503 there would tell every sender with a
        // stale secret to retry forever.
        connector("c-dbx", "dropbox", "the-real-secret");
        when(httpRequest.getHeader("X-Dropbox-Signature")).thenReturn("not-the-signature");

        mockMvc.perform(post("/v1/ingest-webhook/c-dbx")
                        .contentType(MediaType.APPLICATION_JSON).content(DROPBOX_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDisabledConnectorIsStill401WhenTheSelectorAnswered() throws Exception {
        ConnectorDefinition c = connector("c-off", "dropbox", "s");
        c.setEnabled(false);

        mockMvc.perform(signedDropboxPost("c-off", "s"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theRightSecretIsStillDispatchedWhileTheSelectorIsDown() throws Exception {
        // The price this closing does NOT pay: a sender holding the right secret goes through
        // the window exactly as before. Refusing the whole window would have stopped it.
        String secret = "dbxsecret";
        ConnectorDefinition c = connector("c-dbx", "dropbox", secret);
        profileFor("c-dbx", Map.of("folderPath", "/Documents"));
        readWhileTheSelectorWasDown("c-dbx", c);

        mockMvc.perform(signedDropboxPost("c-dbx", secret))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"accepted\"")));
        verify(schedulerService).authorizeDelegatedFetch(any(), any());
    }

    @Test
    void theHandshakeIsTheRefusedReadsAnswerWhileTheSelectorIsDown() throws Exception {
        // The GET side of the same window: its uniform 404 stood beside the read's 503 and
        // separated the same two classes. Closing only the POST would have left it open.
        String refused = whatARefusedHandshakeAnswers();
        ConnectorDefinition c = connector("c-not-dbx", "slack", "s");
        readWhileTheSelectorWasDown("c-not-dbx", c);

        String answered = mockMvc.perform(get("/v1/ingest-webhook/c-not-dbx")
                        .param("challenge", "abc123"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertEquals(refused, answered,
                "the handshake's window answer can be told apart from a refused read");
    }

    @Test
    void theHandshakeIsStill404WhenTheSelectorAnswered() throws Exception {
        // The over-throw guard for the handshake: outside the window a connector that is not
        // an enabled Dropbox one is still a plain 404, as the operator's URL verification and
        // every existing caller expect.
        connector("c-not-dbx", "slack", "s");

        mockMvc.perform(get("/v1/ingest-webhook/c-not-dbx").param("challenge", "abc123"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theHandshakeStillEchoesWhileTheSelectorIsDown() throws Exception {
        // The handshake's OWN disclosure is unchanged and predates this: an enabled Dropbox
        // connector answers the challenge in the window as outside it. Refusing it here would
        // fail the operator's URL verification for the length of an index rebuild.
        ConnectorDefinition c = connector("c-dbx", "dropbox", "s");
        readWhileTheSelectorWasDown("c-dbx", c);

        mockMvc.perform(get("/v1/ingest-webhook/c-dbx").param("challenge", "abc123"))
                .andExpect(status().isOk())
                .andExpect(content().string("abc123"));
    }
}
