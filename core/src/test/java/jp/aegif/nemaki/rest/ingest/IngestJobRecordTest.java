package jp.aegif.nemaki.rest.ingest;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Unit tests for IngestJobRecord and IngestDeadLetterRecord serialization.
 */
public class IngestJobRecordTest {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    @Test
    public void testJobRecordRoundTrip() throws Exception {
        IngestJobRecord job = new IngestJobRecord();
        job.setJobId("job-1234");
        job.setProfileId("profile-a");
        job.setConnectorId("conn-imap");
        job.setRepositoryId("bedroom");
        job.setStatus(IngestJobRecord.Status.COMPLETED);
        job.setFetched(100);
        job.setImported(80);
        job.setSkipped(15);
        job.setFailed(5);
        job.setErrors(List.of("error1", "error2"));

        String json = MAPPER.writeValueAsString(job);
        IngestJobRecord parsed = MAPPER.readValue(json, IngestJobRecord.class);

        assertEquals("job-1234", parsed.getJobId());
        assertEquals(IngestJobRecord.Status.COMPLETED, parsed.getStatus());
        assertEquals(100, parsed.getFetched());
        assertEquals(80, parsed.getImported());
        assertEquals(15, parsed.getSkipped());
        assertEquals(5, parsed.getFailed());
        assertEquals(2, parsed.getErrors().size());
    }

    @Test
    public void testDlqRecordRoundTrip() throws Exception {
        IngestDeadLetterRecord dlq = new IngestDeadLetterRecord();
        dlq.setDlqId("dlq-5678");
        dlq.setProfileId("profile-b");
        dlq.setSourceObjectId("src-123");
        dlq.setSourceObjectType("message");
        dlq.setErrorMessage("Connection refused");
        dlq.setRetryCount(3);
        dlq.setHasContent(true);

        String json = MAPPER.writeValueAsString(dlq);
        IngestDeadLetterRecord parsed = MAPPER.readValue(json, IngestDeadLetterRecord.class);

        assertEquals("dlq-5678", parsed.getDlqId());
        assertEquals("message", parsed.getSourceObjectType());
        assertEquals("Connection refused", parsed.getErrorMessage());
        assertEquals(3, parsed.getRetryCount());
        assertTrue(parsed.isHasContent());
    }

    @Test
    public void testStatusEnum() {
        // By NAME, not by count. The count said 4 and the enum has had STUCK for a while;
        // the class never ran (JUnit 4 with no vintage engine), so the drift was invisible.
        // A set comparison catches a rename as well as an addition, and says which.
        assertEquals(java.util.Set.of("RUNNING", "COMPLETED", "FAILED", "PARTIAL", "STUCK"),
                java.util.Arrays.stream(IngestJobRecord.Status.values())
                        .map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }
}
