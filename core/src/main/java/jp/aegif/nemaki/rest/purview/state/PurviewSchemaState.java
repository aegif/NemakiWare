package jp.aegif.nemaki.rest.purview.state;

public class PurviewSchemaState {

    private final String collection;
    private final String schemaVersion;
    private final String schemaHash;
    private final String lastAppliedAt;
    private final String lastAppliedBy;
    private final String lastDiffSummary;

    public PurviewSchemaState(
            String collection,
            String schemaVersion,
            String schemaHash,
            String lastAppliedAt,
            String lastAppliedBy,
            String lastDiffSummary) {
        this.collection = collection;
        this.schemaVersion = schemaVersion;
        this.schemaHash = schemaHash;
        this.lastAppliedAt = lastAppliedAt;
        this.lastAppliedBy = lastAppliedBy;
        this.lastDiffSummary = lastDiffSummary;
    }

    public String getCollection() {
        return collection;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getSchemaHash() {
        return schemaHash;
    }

    public String getLastAppliedAt() {
        return lastAppliedAt;
    }

    public String getLastAppliedBy() {
        return lastAppliedBy;
    }

    public String getLastDiffSummary() {
        return lastDiffSummary;
    }
}
