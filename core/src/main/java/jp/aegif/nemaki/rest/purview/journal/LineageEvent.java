package jp.aegif.nemaki.rest.purview.journal;

import java.util.List;
import java.util.Map;

/**
 * Immutable envelope for a canonical lineage event.
 *
 * <h2>Envelope Fields (idempotency and ordering)</h2>
 * <ul>
 *   <li>{@code schemaVersion} — envelope format version (currently {@value #CURRENT_SCHEMA_VERSION}).
 *       Allows future migration of the event payload without breaking consumers.</li>
 *   <li>{@code eventId} — globally unique identifier (UUID).</li>
 *   <li>{@code eventKey} — deterministic idempotency key derived from business identity.
 *       Same eventKey = same logical lineage fact; targets skip duplicates.</li>
 *   <li>{@code sequenceNumber} — monotonically increasing per repository, assigned by
 *       {@link LineageJournalStore#append}. Projectors must process events in sequence
 *       order to preserve causal ordering (rename before export, archive before delete).</li>
 *   <li>{@code version} — mutation counter for the same logical lineage (e.g. re-import).
 *       Targets upsert by (eventKey, version).</li>
 * </ul>
 *
 * <h2>Canonical Payload Schema</h2>
 *
 * <h3>Asset Identity ({@code inputs} / {@code outputs})</h3>
 *
 * <p>Each entry in {@code inputs} and {@code outputs} is a <b>stable
 * qualified name</b> of the form {@code nemaki://{repositoryId}/objects/{objectId}}.
 * This is the sole identity carried per asset. Qualified names are
 * stable across renames (they use the immutable objectId, not the name).
 *
 * <p>Adapter-specific transformations (e.g. Purview qualifiedName format)
 * are performed by the target sink adapter, not stored in the event.
 *
 * <h3>Snapshot Attributes ({@code snapshotAttributes})</h3>
 *
 * <p>Optional, event-level metadata captured at emission time. These are
 * <b>point-in-time values</b> and may become stale if the object is later
 * modified. Defined keys (stable across adapters):
 *
 * <table>
 *   <tr><th>Key</th><th>Type</th><th>Description</th><th>Truncation</th></tr>
 *   <tr>
 *     <td>{@code name}</td><td>String</td>
 *     <td>Object display name (cmis:name)</td>
 *     <td>Max {@link LineageConfig#getSnapshotMaxNameLength()} chars.
 *         If truncated: {@code name_truncated=true}, {@code name_hash=SHA-256}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code folderPath}</td><td>String</td>
 *     <td>Parent folder path. Only captured when
 *         {@link LineageConfig#isSnapshotCapturePath()} is true.</td>
 *     <td>Max {@link LineageConfig#getSnapshotMaxPathLength()} chars.
 *         If truncated: {@code folderPath_truncated=true}, {@code folderPath_hash=SHA-256}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code cmis:objectTypeId}</td><td>String</td>
 *     <td>CMIS type ID (e.g. "cmis:document")</td>
 *     <td>None (always short)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code cmis:contentStreamMimeType}</td><td>String</td>
 *     <td>MIME type (e.g. "application/pdf")</td>
 *     <td>None</td>
 *   </tr>
 *   <tr>
 *     <td>{@code cmis:versionLabel}</td><td>String</td>
 *     <td>Version label at time of operation</td>
 *     <td>None</td>
 *   </tr>
 * </table>
 *
 * <p>All other keys are adapter-specific or custom and must not be
 * relied upon for cross-adapter compatibility. Target adapters must
 * tolerate missing keys (all snapshot attributes are optional).
 *
 * <p><b>Excluded by design:</b> no blob content, no file body, no
 * full property map. The journal captures <em>lineage identity and
 * provenance</em>, not a full metadata mirror.
 */
public record LineageEvent(
        int schemaVersion,
        String eventId,
        String eventKey,
        long sequenceNumber,
        String occurredAt,
        String repositoryId,
        LineageProcessType processType,
        List<String> inputs,
        List<String> outputs,
        String runId,
        String correlationId,
        int version,
        Map<String, String> snapshotAttributes,
        Map<String, LineagePublishStatus> publishStatusByTarget
) {

    /** Current envelope schema version. Increment on breaking changes. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Compact constructor with null-safe defaults. */
    public LineageEvent {
        eventId = eventId != null ? eventId : "";
        eventKey = eventKey != null ? eventKey : "";
        occurredAt = occurredAt != null ? occurredAt : "";
        repositoryId = repositoryId != null ? repositoryId : "";
        inputs = inputs != null ? List.copyOf(inputs) : List.of();
        outputs = outputs != null ? List.copyOf(outputs) : List.of();
        runId = runId != null ? runId : "";
        correlationId = correlationId != null ? correlationId : "";
        snapshotAttributes = snapshotAttributes != null ? Map.copyOf(snapshotAttributes) : Map.of();
        publishStatusByTarget = publishStatusByTarget != null ? Map.copyOf(publishStatusByTarget) : Map.of();
    }

    public static String qualifiedName(String repositoryId, String objectId) {
        return "nemaki://" + repositoryId + "/objects/" + objectId;
    }

    /**
     * Computes a deterministic idempotency key from business identity fields.
     *
     * <p>Format: {@code {repositoryId}:{processType}:{sortedInputsHash}:{sortedOutputsHash}}
     *
     * <p>This key is used by publish sinks to detect and skip duplicate events
     * during replay / republish, preventing duplicate processes or relationships
     * on Purview / Atlas / Dataplex.
     */
    public static String computeEventKey(String repositoryId, LineageProcessType processType,
                                          List<String> inputs, List<String> outputs) {
        long inputsHash = stableListHash(inputs);
        long outputsHash = stableListHash(outputs);
        return repositoryId + ":" + processType.name() + ":" + inputsHash + ":" + outputsHash;
    }

    private static long stableListHash(List<String> items) {
        if (items == null || items.isEmpty()) {
            return 0L;
        }
        List<String> sorted = items.stream().sorted().toList();
        long hash = 1L;
        for (String s : sorted) {
            hash = 31 * hash + (s == null ? 0 : s.hashCode());
        }
        return hash;
    }
}
