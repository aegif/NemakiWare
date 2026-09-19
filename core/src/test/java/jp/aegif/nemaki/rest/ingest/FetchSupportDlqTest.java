package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FetchSupport#saveToDlq} — the helper orchestrators use to
 * dead-letter an item whose download fails BEFORE it reaches
 * {@code CanonicalImportService.execute()} (which has its own DLQ net). This
 * prevents the scheduler's high-water checkpoint from silently losing a failed
 * item when a newer item in the same batch advances the checkpoint past it.
 */
public class FetchSupportDlqTest {

    @Test
    public void saveToDlqDelegatesToJobService() {
        IngestJobService jobService = mock(IngestJobService.class);
        FetchSupport fetchSupport = new FetchSupport();
        fetchSupport.setIngestJobService(jobService);

        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setSourceObjectId("file-1");
        byte[] content = null;

        fetchSupport.saveToDlq(req, "download failed", content);

        verify(jobService).saveToDlq(eq(req), eq("download failed"), eq(content));
    }

    @Test
    public void saveToDlqIsNullSafeWithoutJobService() {
        // No ingestJobService wired (e.g. minimal/test context) — must not throw.
        FetchSupport fetchSupport = new FetchSupport();
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setSourceObjectId("file-1");

        assertDoesNotThrow(() -> fetchSupport.saveToDlq(req, "download failed", null));
    }

    @Test
    public void saveToDlqSwallowsJobServiceException() {
        // DLQ persistence is best-effort; an exception must not break the fetch loop.
        IngestJobService jobService = mock(IngestJobService.class);
        doThrow(new RuntimeException("couch down")).when(jobService).saveToDlq(any(), any(), any());
        FetchSupport fetchSupport = new FetchSupport();
        fetchSupport.setIngestJobService(jobService);

        ExternalIngestRequest req = new ExternalIngestRequest();
        assertDoesNotThrow(() -> fetchSupport.saveToDlq(req, "boom", null));
    }

    @Test
    public void saveSourceReadToDlqReportsTheServicesAnswer() {
        // The IDLE monitor claims "the miss was recorded" on this boolean. A helper that
        // returned true whenever the void call returned would make that claim unconditional.
        IngestJobService jobService = mock(IngestJobService.class);
        when(jobService.saveToDlqReporting(any(), any(), isNull(), eq(false), eq(true)))
                .thenReturn(false);
        FetchSupport fetchSupport = new FetchSupport();
        fetchSupport.setIngestJobService(jobService);
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setSourceObjectId("mail-7");

        assertFalse(fetchSupport.saveSourceReadToDlq(req, "not imported"),
                "the helper claimed a record the service said it did not write");

        when(jobService.saveToDlqReporting(any(), any(), isNull(), eq(false), eq(true)))
                .thenReturn(true);
        assertTrue(fetchSupport.saveSourceReadToDlq(req, "not imported"));
    }

    @Test
    public void saveWebhookDeliveryRecordToDlqReportsTheServicesAnswer() {
        IngestJobService jobService = mock(IngestJobService.class);
        when(jobService.saveWebhookDeliveryRecordToDlq(any(), any())).thenReturn(false);
        FetchSupport fetchSupport = new FetchSupport();
        fetchSupport.setIngestJobService(jobService);
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setSourceObjectId("webhook-deliveries:p1:c1");

        assertFalse(fetchSupport.saveWebhookDeliveryRecordToDlq(req, "not fetched"),
                "the helper claimed a record the service said it did not write");

        when(jobService.saveWebhookDeliveryRecordToDlq(any(), any())).thenReturn(true);
        assertTrue(fetchSupport.saveWebhookDeliveryRecordToDlq(req, "not fetched"));
    }

    // ── R4: the after-import link carries its authority, or is not made ──

    @Test
    public void createRelationshipSafePassesWhatAuthorisesTheLink() {
        // The orchestrators hold the profile they are running and the request whose import
        // produced the object. Passing neither is what left the link unauthorised (R4), and a
        // helper that dropped them on the floor would look exactly like one that passes them.
        CanonicalImportService importService = mock(CanonicalImportService.class);
        FetchSupport fetchSupport = new FetchSupport();
        fetchSupport.setCanonicalImportService(importService);
        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setConnectorId("c1");
        java.util.List<String> errors = new java.util.ArrayList<>();

        fetchSupport.createRelationshipSafe(null, "bedroom", "src-1", "tgt-1", profile, req, errors);

        verify(importService).createDirectRelationship(isNull(), eq("bedroom"), eq("src-1"),
                eq("tgt-1"), eq(profile), eq(req));
        assertTrue(errors.isEmpty(), "an authorised link was reported as an error: " + errors);
    }

    @Test
    public void createRelationshipSafeRefusesWithNothingToAuthoriseAgainst() {
        // A caller with neither has nothing to re-ask, and a link written on no authority is
        // the thing this closes. Refused and reported, not created quietly.
        CanonicalImportService importService = mock(CanonicalImportService.class);
        FetchSupport fetchSupport = new FetchSupport();
        fetchSupport.setCanonicalImportService(importService);
        java.util.List<String> errors = new java.util.ArrayList<>();

        fetchSupport.createRelationshipSafe(null, "bedroom", "src-1", "tgt-1", null, null, errors);

        verify(importService, never()).createDirectRelationship(any(), any(), any(), any(),
                any(), any());
        assertEquals(1, errors.size(), "the refusal was not reported: " + errors);
        assertTrue(errors.get(0).contains("authorise"),
                "the refusal does not say why: " + errors);
    }
}
