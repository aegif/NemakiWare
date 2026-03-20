package jp.aegif.nemaki.rest.purview;

import java.util.List;

public class PurviewStateOverview {

    private final String collection;
    private final PurviewSchemaState schemaState;
    private final List<PurviewJobState> jobs;
    private final List<PurviewCursorState> cursors;
    private final List<PurviewLockState> locks;
    private final List<PurviewTombstoneState> tombstones;

    public PurviewStateOverview(
            String collection,
            PurviewSchemaState schemaState,
            List<PurviewJobState> jobs,
            List<PurviewCursorState> cursors,
            List<PurviewLockState> locks,
            List<PurviewTombstoneState> tombstones) {
        this.collection = collection;
        this.schemaState = schemaState;
        this.jobs = jobs;
        this.cursors = cursors;
        this.locks = locks;
        this.tombstones = tombstones;
    }

    public String getCollection() {
        return collection;
    }

    public PurviewSchemaState getSchemaState() {
        return schemaState;
    }

    public List<PurviewJobState> getJobs() {
        return jobs;
    }

    public List<PurviewCursorState> getCursors() {
        return cursors;
    }

    public List<PurviewLockState> getLocks() {
        return locks;
    }

    public List<PurviewTombstoneState> getTombstones() {
        return tombstones;
    }
}
