package jp.aegif.nemaki.rest.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
}
