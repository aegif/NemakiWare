package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

public class LineageDeadLetterSinkTest {

    @Test
    public void testToCompactJsonContainsAllKeyFields() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_COLD)
                .addInput("nemaki://bedroom/objects/doc1")
                .addOutput("nemaki://bedroom/objects/archive1")
                .runId("run-123")
                .correlationId("corr-456")
                .build();

        String json = LineageDeadLetterSink.toCompactJson(event, "CouchDB timeout");

        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"eventId\":\""));
        assertTrue(json.contains("\"eventKey\":\""));
        assertTrue(json.contains("\"repositoryId\":\"bedroom\""));
        assertTrue(json.contains("\"processType\":\"ARCHIVE_COLD\""));
        assertTrue(json.contains("\"runId\":\"run-123\""));
        assertTrue(json.contains("\"correlationId\":\"corr-456\""));
        assertTrue(json.contains("\"reason\":\"CouchDB timeout\""));
        assertTrue(json.contains("\"inputs\":[\"nemaki://bedroom/objects/doc1\"]"));
        assertTrue(json.contains("\"outputs\":[\"nemaki://bedroom/objects/archive1\"]"));
        assertTrue(json.contains("\"schemaVersion\":" + LineageEvent.CURRENT_SCHEMA_VERSION));
    }

    @Test
    public void testToCompactJsonEscapesSpecialCharacters() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bed\"room")
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .build();

        String json = LineageDeadLetterSink.toCompactJson(event, "error with \"quotes\"\nand newline");

        assertTrue(json.contains("bed\\\"room"));
        assertTrue(json.contains("error with \\\"quotes\\\"\\nand newline"));
    }

    @Test
    public void testToCompactJsonHandlesNullReason() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.EXPORT_FILESYSTEM)
                .build();

        String json = LineageDeadLetterSink.toCompactJson(event, null);

        assertTrue(json.contains("\"reason\":\"\""));
    }

    @Test
    public void testToCompactJsonMultipleInputsOutputs() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.IMPORT_FILESYSTEM)
                .addInput("nemaki://bedroom/objects/a")
                .addInput("nemaki://bedroom/objects/b")
                .addOutput("nemaki://bedroom/objects/x")
                .addOutput("nemaki://bedroom/objects/y")
                .addOutput("nemaki://bedroom/objects/z")
                .build();

        String json = LineageDeadLetterSink.toCompactJson(event, "test");

        assertTrue(json.contains("\"inputs\":[\"nemaki://bedroom/objects/a\",\"nemaki://bedroom/objects/b\"]"));
        assertTrue(json.contains("\"outputs\":[\"nemaki://bedroom/objects/x\",\"nemaki://bedroom/objects/y\",\"nemaki://bedroom/objects/z\"]"));
    }

    @Test
    public void testRecordNeverThrows() {
        // record() must be safe even with pathological inputs
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("test")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .build();

        // Should not throw
        LineageDeadLetterSink.record(event, "some failure");
        LineageDeadLetterSink.record(event, null);
    }
}
