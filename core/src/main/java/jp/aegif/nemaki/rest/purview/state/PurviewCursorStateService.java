package jp.aegif.nemaki.rest.purview.state;

public interface PurviewCursorStateService {

    PurviewCursorState saveCursorState(PurviewCursorState cursorState);

    PurviewCursorState getCursorState(String repositoryId, String streamKind);

    /**
     * The 4b preflight's cursor check: every repository that HAS a stored
     * {@code cloud-metadata-snapshot} cursor, or is in {@code configuredRepositoryIds},
     * inspected strictly and returned as verdicts only.
     *
     * <p>The union matters: a repository dropped from configuration keeps its stored cursor,
     * and checking only the configured set would step over exactly that residue.
     *
     * @param configuredRepositoryIds the repositories configuration knows about
     * @return one verdict per repository in the union; never the stored values
     */
    java.util.List<CloudMetadataCursorInspection> inspectCloudMetadataCursors(
            java.util.Collection<String> configuredRepositoryIds);
}
