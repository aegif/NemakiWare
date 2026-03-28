package jp.aegif.nemaki.rest.purview.journal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CouchDB document model for a lineage journal event.
 *
 * <p>Document type: {@code "lineage_event"}.
 * Document key: {@code "lineage:{eventId}"}.
 *
 * <p>This is a plain POJO (not extending {@code CouchNodeBase}) because
 * lineage events are not CMIS objects. They are stored in the dedicated
 * {@code nemaki_lineage} database.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouchLineageEvent {

    private static final String TYPE = "lineage_event";
    static final String ID_PREFIX = "lineage:";

    @JsonProperty("_id")
    private String id;

    @JsonProperty("_rev")
    private String rev;

    @JsonProperty("type")
    private String type = TYPE;

    @JsonProperty("schemaVersion")
    private int schemaVersion;

    @JsonProperty("eventId")
    private String eventId;

    @JsonProperty("eventKey")
    private String eventKey;

    @JsonProperty("sequenceNumber")
    private long sequenceNumber;

    @JsonProperty("occurredAt")
    private String occurredAt;

    @JsonProperty("repositoryId")
    private String repositoryId;

    @JsonProperty("processType")
    private String processType;

    @JsonProperty("inputs")
    private List<String> inputs;

    @JsonProperty("outputs")
    private List<String> outputs;

    @JsonProperty("runId")
    private String runId;

    @JsonProperty("correlationId")
    private String correlationId;

    @JsonProperty("version")
    private int version;

    @JsonProperty("snapshotAttributes")
    private Map<String, String> snapshotAttributes;

    @JsonProperty("publishStatusByTarget")
    private Map<String, String> publishStatusByTarget;

    /** No-arg constructor for Jackson deserialization. */
    public CouchLineageEvent() {
    }

    /** Construct from a raw CouchDB document map. */
    @JsonCreator
    @SuppressWarnings("unchecked")
    public CouchLineageEvent(Map<String, Object> props) {
        if (props == null) return;
        this.id = (String) props.get("_id");
        this.rev = (String) props.get("_rev");
        this.type = props.getOrDefault("type", TYPE).toString();
        Object sv = props.get("schemaVersion");
        if (sv instanceof Number) this.schemaVersion = ((Number) sv).intValue();
        this.eventId = (String) props.get("eventId");
        this.eventKey = (String) props.get("eventKey");
        Object sn = props.get("sequenceNumber");
        if (sn instanceof Number) this.sequenceNumber = ((Number) sn).longValue();
        this.occurredAt = (String) props.get("occurredAt");
        this.repositoryId = (String) props.get("repositoryId");
        this.processType = (String) props.get("processType");
        Object in = props.get("inputs");
        if (in instanceof List) this.inputs = new ArrayList<>((List<String>) in);
        Object out = props.get("outputs");
        if (out instanceof List) this.outputs = new ArrayList<>((List<String>) out);
        this.runId = (String) props.get("runId");
        this.correlationId = (String) props.get("correlationId");
        Object v = props.get("version");
        if (v instanceof Number) this.version = ((Number) v).intValue();
        Object sa = props.get("snapshotAttributes");
        if (sa instanceof Map) this.snapshotAttributes = new HashMap<>((Map<String, String>) sa);
        Object ps = props.get("publishStatusByTarget");
        if (ps instanceof Map) this.publishStatusByTarget = new LinkedHashMap<>((Map<String, String>) ps);
    }

    /** Construct from a {@link LineageEvent} record. */
    public CouchLineageEvent(LineageEvent event) {
        this.id = ID_PREFIX + event.eventId();
        this.type = TYPE;
        this.schemaVersion = event.schemaVersion();
        this.eventId = event.eventId();
        this.eventKey = event.eventKey();
        this.sequenceNumber = event.sequenceNumber();
        this.occurredAt = event.occurredAt();
        this.repositoryId = event.repositoryId();
        this.processType = event.processType() != null ? event.processType().name() : null;
        this.inputs = new ArrayList<>(event.inputs());
        this.outputs = new ArrayList<>(event.outputs());
        this.runId = event.runId();
        this.correlationId = event.correlationId();
        this.version = event.version();
        this.snapshotAttributes = event.snapshotAttributes().isEmpty()
                ? null : new HashMap<>(event.snapshotAttributes());
        Map<String, String> statusMap = new LinkedHashMap<>();
        event.publishStatusByTarget().forEach((k, v) -> statusMap.put(k, v.name()));
        this.publishStatusByTarget = statusMap.isEmpty() ? null : statusMap;
    }

    /** Convert back to an immutable {@link LineageEvent} record. */
    public LineageEvent toLineageEvent() {
        LineageProcessType pt = null;
        if (processType != null && !processType.isEmpty()) {
            try {
                pt = LineageProcessType.valueOf(processType);
            } catch (IllegalArgumentException ignored) {
                // Unknown process type — leave null
            }
        }

        Map<String, LineagePublishStatus> statusMap = new LinkedHashMap<>();
        if (publishStatusByTarget != null) {
            publishStatusByTarget.forEach((k, v) -> {
                try {
                    statusMap.put(k, LineagePublishStatus.valueOf(v));
                } catch (IllegalArgumentException ignored) {
                    statusMap.put(k, LineagePublishStatus.PENDING);
                }
            });
        }

        return new LineageEvent(
                schemaVersion,
                eventId,
                eventKey,
                sequenceNumber,
                occurredAt,
                repositoryId,
                pt,
                inputs,
                outputs,
                runId,
                correlationId,
                version,
                snapshotAttributes,
                statusMap
        );
    }

    /** Convert this object to a mutable Map for CouchDB create/update operations. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (id != null) map.put("_id", id);
        if (rev != null) map.put("_rev", rev);
        map.put("type", type);
        map.put("schemaVersion", schemaVersion);
        map.put("eventId", eventId);
        map.put("eventKey", eventKey);
        map.put("sequenceNumber", sequenceNumber);
        map.put("occurredAt", occurredAt);
        map.put("repositoryId", repositoryId);
        map.put("processType", processType);
        map.put("inputs", inputs);
        map.put("outputs", outputs);
        map.put("runId", runId);
        map.put("correlationId", correlationId);
        map.put("version", version);
        if (snapshotAttributes != null) map.put("snapshotAttributes", snapshotAttributes);
        if (publishStatusByTarget != null) map.put("publishStatusByTarget", publishStatusByTarget);
        return map;
    }

    // --- Getters and Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRev() { return rev; }
    public void setRev(String rev) { this.rev = rev; }

    public String getType() { return type; }

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }

    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public String getOccurredAt() { return occurredAt; }
    public void setOccurredAt(String occurredAt) { this.occurredAt = occurredAt; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }

    public String getProcessType() { return processType; }
    public void setProcessType(String processType) { this.processType = processType; }

    public List<String> getInputs() { return inputs; }
    public void setInputs(List<String> inputs) { this.inputs = inputs; }

    public List<String> getOutputs() { return outputs; }
    public void setOutputs(List<String> outputs) { this.outputs = outputs; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public Map<String, String> getSnapshotAttributes() { return snapshotAttributes; }
    public void setSnapshotAttributes(Map<String, String> snapshotAttributes) { this.snapshotAttributes = snapshotAttributes; }

    public Map<String, String> getPublishStatusByTarget() { return publishStatusByTarget; }
    public void setPublishStatusByTarget(Map<String, String> publishStatusByTarget) { this.publishStatusByTarget = publishStatusByTarget; }
}
