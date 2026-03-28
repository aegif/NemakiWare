package jp.aegif.nemaki.rest.purview.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * File-based dead-letter sink for lineage events that failed to persist.
 *
 * <h2>Design Rationale</h2>
 *
 * <p>When the journal store (CouchDB) is unavailable, writing the failed
 * event to the <em>same</em> CouchDB would also fail. The existing Purview
 * dead-letter ({@code PurviewDeadLetterStateService}) uses CouchDB and is
 * therefore not suitable as a fallback for CouchDB outages.
 *
 * <p>This sink writes a structured JSON representation of the failed event
 * to a dedicated SLF4J logger ({@code LINEAGE_DEAD_LETTER}), which can be
 * routed to a separate log file via logback configuration. This approach:
 * <ul>
 *   <li>Does not depend on CouchDB availability</li>
 *   <li>Survives JVM restart (log files are durable)</li>
 *   <li>Is operationally familiar (log rotation, shipping to ELK/Splunk)</li>
 *   <li>Adds zero new runtime dependencies</li>
 * </ul>
 *
 * <h2>Log Format</h2>
 *
 * <p>Each dead-letter entry is logged at {@code ERROR} level with the
 * marker {@code LINEAGE_DEAD_LETTER}. The message is a single-line JSON
 * object containing the event's key fields and the failure reason.
 * Operators can grep for the marker to extract dead-letter events.
 *
 * <h2>Recovery</h2>
 *
 * <p>To replay dead-letter events after the store recovers:
 * <ol>
 *   <li>Extract JSON lines from the dead-letter log file</li>
 *   <li>Reconstruct {@link LineageEvent} objects from the JSON</li>
 *   <li>Call {@link LineageJournalStore#append} — idempotency by
 *       {@code eventKey} prevents duplicates if the event was partially
 *       written before failure</li>
 * </ol>
 *
 * <p>Automated replay is a Phase 3 concern. Initial implementation
 * focuses on not losing data silently.
 */
public final class LineageDeadLetterSink {

    /**
     * Dedicated logger for dead-letter events.
     * Configure in logback.xml to route to a separate file, e.g.:
     * <pre>{@code
     * <appender name="LINEAGE_DLQ" class="ch.qos.logback.core.rolling.RollingFileAppender">
     *   <file>logs/lineage-dead-letter.log</file>
     *   ...
     * </appender>
     * <logger name="LINEAGE_DEAD_LETTER" level="ERROR" additivity="false">
     *   <appender-ref ref="LINEAGE_DLQ" />
     * </logger>
     * }</pre>
     */
    private static final Logger deadLetterLogger = LoggerFactory.getLogger("LINEAGE_DEAD_LETTER");
    private static final Marker DLQ_MARKER = MarkerFactory.getMarker("LINEAGE_DEAD_LETTER");

    /** Static holder for metrics — set by LineageProjectionLoop at startup. */
    private static volatile LineageMetrics metrics;

    /** Static holder for persistent dead-letter store. */
    private static volatile LineageDeadLetterStore store;

    private LineageDeadLetterSink() {
        // utility class
    }

    /**
     * Sets the metrics instance. Called once at startup.
     */
    public static void setMetrics(LineageMetrics metricsInstance) {
        metrics = metricsInstance;
    }

    /**
     * Sets the persistent dead-letter store. Called once at startup.
     */
    public static void setStore(LineageDeadLetterStore storeInstance) {
        store = storeInstance;
    }

    /**
     * Writes a failed event to the dead-letter log.
     *
     * <p>This method never throws. If even logging fails, the event is
     * silently lost (last-resort fail-open).
     *
     * @param event the event that could not be persisted
     * @param reason the failure description
     */
    public static void record(LineageEvent event, String reason) {
        try {
            String json = toCompactJson(event, reason);
            deadLetterLogger.error(DLQ_MARKER, json);
            if (metrics != null) metrics.recordDeadLetter();
            // Also persist to CouchDB store if available
            if (store != null) {
                try {
                    store.record(event, reason);
                } catch (Exception storeEx) {
                    // Store persistence is best-effort; log file is the primary record
                    deadLetterLogger.debug("Dead-letter store persistence failed: {}", storeEx.getMessage());
                }
            }
        } catch (Exception suppressed) {
            // Last-resort: even logging failed. Nothing more we can do.
        }
    }

    /**
     * Builds a single-line JSON representation for the dead-letter log.
     *
     * <p>Uses manual string building to avoid Jackson dependency in this
     * low-level failure path. Fields are intentionally minimal — enough
     * to replay the event but not so large that log files explode.
     */
    static String toCompactJson(LineageEvent event, String reason) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        appendField(sb, "eventId", event.eventId());
        sb.append(',');
        appendField(sb, "eventKey", event.eventKey());
        sb.append(',');
        appendField(sb, "repositoryId", event.repositoryId());
        sb.append(',');
        appendField(sb, "processType", event.processType() != null ? event.processType().name() : "");
        sb.append(',');
        appendField(sb, "occurredAt", event.occurredAt());
        sb.append(',');
        sb.append("\"schemaVersion\":").append(event.schemaVersion());
        sb.append(',');
        sb.append("\"version\":").append(event.version());
        sb.append(',');
        appendField(sb, "runId", event.runId());
        sb.append(',');
        appendField(sb, "correlationId", event.correlationId());
        sb.append(',');
        appendJsonArray(sb, "inputs", event.inputs());
        sb.append(',');
        appendJsonArray(sb, "outputs", event.outputs());
        sb.append(',');
        appendField(sb, "reason", reason != null ? reason : "");
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static void appendJsonArray(StringBuilder sb, String key, java.util.List<String> items) {
        sb.append('"').append(key).append("\":[");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(items.get(i))).append('"');
            }
        }
        sb.append(']');
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                     .replace("\"", "\\\"")
                     .replace("\n", "\\n")
                     .replace("\r", "\\r")
                     .replace("\t", "\\t");
    }
}
