package jp.aegif.nemaki.rest.purview.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LineageEventBuilder {

    // --- Stable snapshot attribute keys (see LineageEvent javadoc) ---
    /** Object display name (cmis:name). */
    public static final String SNAP_NAME = "name";
    /** Parent folder path. */
    public static final String SNAP_FOLDER_PATH = "folderPath";
    /** CMIS type ID (e.g. "cmis:document"). */
    public static final String SNAP_OBJECT_TYPE_ID = "cmis:objectTypeId";
    /** MIME type (e.g. "application/pdf"). */
    public static final String SNAP_MIME_TYPE = "cmis:contentStreamMimeType";
    /** Version label at time of operation. */
    public static final String SNAP_VERSION_LABEL = "cmis:versionLabel";

    private String repositoryId;
    private LineageProcessType processType;
    private final List<String> inputs = new ArrayList<>();
    private final List<String> outputs = new ArrayList<>();
    private String runId;
    private String correlationId;
    private int version = 1;
    private long sequenceNumber = 0;
    private final Map<String, String> snapshotAttributes = new HashMap<>();
    private final Map<String, LineagePublishStatus> publishStatusByTarget = new LinkedHashMap<>();

    public LineageEventBuilder repositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
        return this;
    }

    public LineageEventBuilder processType(LineageProcessType processType) {
        this.processType = processType;
        return this;
    }

    public LineageEventBuilder addInputObject(String repoId, String objectId) {
        inputs.add(LineageEvent.qualifiedName(repoId, objectId));
        return this;
    }

    public LineageEventBuilder addOutputObject(String repoId, String objectId) {
        outputs.add(LineageEvent.qualifiedName(repoId, objectId));
        return this;
    }

    public LineageEventBuilder addInput(String qualifiedName) {
        inputs.add(qualifiedName);
        return this;
    }

    public LineageEventBuilder addOutput(String qualifiedName) {
        outputs.add(qualifiedName);
        return this;
    }

    public LineageEventBuilder runId(String runId) {
        this.runId = runId;
        return this;
    }

    public LineageEventBuilder correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    /**
     * Sets the version for this event (default: 1).
     * Increment for successive mutations of the same logical lineage
     * (e.g. re-import overwriting the same file).
     */
    public LineageEventBuilder version(int version) {
        this.version = version;
        return this;
    }

    /**
     * Sets the per-repository sequence number.
     *
     * <p><b>Journaled mode:</b> this value is <em>ignored</em> by
     * {@link LineageJournalStore#append}, which overwrites it with the
     * next DB-side sequence (CouchDB compare-and-set). Callers should
     * leave it at the default (0).
     *
     * <p><b>Direct mode:</b> not used (no store, no ordering guarantee).
     * The value remains 0.
     *
     * <p><b>Test only:</b> explicit values are useful in unit tests to
     * verify projector ordering logic.
     */
    public LineageEventBuilder sequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    public LineageEventBuilder snapshotAttribute(String key, String value) {
        snapshotAttributes.put(key, value);
        return this;
    }

    /**
     * Adds a snapshot name attribute, truncated to the configured max length.
     *
     * <p>If truncation occurs, two audit trail attributes are added:
     * <ul>
     *   <li>{@code name_truncated} = {@code "true"}</li>
     *   <li>{@code name_hash} = SHA-256 hex of the original value</li>
     * </ul>
     *
     * @param name the document/object name
     * @param config the lineage configuration (for size limits)
     */
    public LineageEventBuilder snapshotName(String name, LineageConfig config) {
        if (name != null) {
            int maxLen = config.getSnapshotMaxNameLength();
            if (name.length() > maxLen && maxLen > 0) {
                snapshotAttributes.put("name", name.substring(0, maxLen));
                snapshotAttributes.put("name_truncated", "true");
                snapshotAttributes.put("name_hash", sha256Hex(name));
            } else {
                snapshotAttributes.put("name", name);
            }
        }
        return this;
    }

    /**
     * Adds a snapshot folderPath attribute if path capture is enabled,
     * truncated to the configured max length.
     *
     * <p>If truncation occurs, two audit trail attributes are added:
     * <ul>
     *   <li>{@code folderPath_truncated} = {@code "true"}</li>
     *   <li>{@code folderPath_hash} = SHA-256 hex of the original value</li>
     * </ul>
     *
     * @param folderPath the folder path
     * @param config the lineage configuration (for size limits and opt-in/out)
     */
    public LineageEventBuilder snapshotFolderPath(String folderPath, LineageConfig config) {
        if (folderPath != null && config.isSnapshotCapturePath()) {
            int maxLen = config.getSnapshotMaxPathLength();
            if (folderPath.length() > maxLen && maxLen > 0) {
                snapshotAttributes.put("folderPath", folderPath.substring(0, maxLen));
                snapshotAttributes.put("folderPath_truncated", "true");
                snapshotAttributes.put("folderPath_hash", sha256Hex(folderPath));
            } else {
                snapshotAttributes.put("folderPath", folderPath);
            }
        }
        return this;
    }

    static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    public LineageEventBuilder targets(List<String> targets) {
        if (targets != null) {
            for (String target : targets) {
                publishStatusByTarget.put(target, LineagePublishStatus.PENDING);
            }
        }
        return this;
    }

    public LineageEvent build() {
        String eventKey = LineageEvent.computeEventKey(repositoryId, processType, inputs, outputs);
        return new LineageEvent(
                LineageEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                eventKey,
                sequenceNumber,
                Instant.now().toString(),
                repositoryId,
                processType,
                inputs,
                outputs,
                runId,
                correlationId,
                version,
                snapshotAttributes,
                publishStatusByTarget
        );
    }
}
