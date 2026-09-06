package jp.aegif.nemaki.rest.ingest;

import java.util.List;

/**
 * CRUD service for import profile definitions stored in CouchDB nemaki_conf.
 */
public interface ImportProfileDefinitionService {
    ImportProfileDefinition create(ImportProfileDefinition def);
    ImportProfileDefinition get(String profileId);
    List<ImportProfileDefinition> list();
    List<ImportProfileDefinition> listByRepository(String repositoryId);
    ImportProfileDefinition update(ImportProfileDefinition def);
    /**
     * Removes every row of {@code profileId} in {@code repositoryId} and returns how many
     * rows of that profileId remain in ANY repository. The scheduler is keyed by profileId
     * alone, so stopping it after a repository-confined delete would cut another
     * repository's live capture; the caller stops it only when this answers 0. -1 means the
     * count could not answer, which is not "none remain".
     */
    int delete(String profileId, String repositoryId);
    boolean exists(String profileId);

    /**
     * The ONE enabled profile of this repository that allows the archetype and the connector,
     * or null when there is none. Several matches are refused, not resolved by order: the
     * preference passes (explicit defaultConnectorId, then defaultProfile, then any
     * compatible one) each require exactly one match.
     */
    ImportProfileDefinition findDefaultForRepository(String repositoryId, SourceArchetype archetype, String connectorId);

    /**
     * Removes the row addressed by {@code docId} and returns how many rows of this profileId
     * remain in ANY repository. The count is global on purpose: the caller decides from it
     * whether to stop a scheduler that is keyed by profileId alone. 0 means another caller
     * removed the other twin concurrently, so the profile is gone through a path that assumes
     * a survivor and the caller has to finish that work. -1 means the count could not answer;
     * that is "unknown", never "a row survives".
     *
     * <p>The check BEFORE the delete is repository-confined instead: this operation resolves
     * a divergent pair within one repository, and it refuses (409) when the addressed row is
     * that repository's only one.
     */
    int delete(String profileId, String docId, String repositoryId);



    /**
     * Does a row of {@code repositoryId} define {@code profileId}, answered from
     * {@code _all_docs} only — so it holds while the Mango index that {@link #get(String)}
     * depends on is rebuilding. The controllers consult it before answering 404: a profile
     * the selector cannot show is "retry", never "not found". A row that cannot be read
     * throws {@code ProfileIndexNotReadyException} rather than answering either way.
     *
     * <p>Deliberately CONFINED to {@code repositoryId}: a row hidden in another repository
     * must not turn this repository's 404 into a 503, which would disclose that the other
     * row exists. {@code false} therefore means "not in this repository", not "nowhere".
     * (The wording said "any row" while the implementation was already confined.)
     */
    boolean existsIndexFree(String profileId, String repositoryId);

    /**
     * The row of {@code profileId} that belongs to {@code repositoryId}, read without the
     * Mango index. {@link #get} selects on profileId alone, so with the same id in two
     * repositories it hands back an arbitrary twin and the caller's own row becomes
     * unreachable. Null when this repository has none; refuses (index-not-ready) when a row
     * could not be classified, and refuses with {@code ProfileHasTwinRowsException} when this
     * repository has MORE THAN ONE row of the id — the caller must not be handed one of a
     * pair to authorise from. Both are unchecked; a caller that lets the pair refusal escape
     * answers 500 for a state an administrator has to resolve. (The second was undocumented
     * although callers already catch it.)
     */
    ImportProfileDefinition getForRepository(String profileId, String repositoryId);

    /**
     * The unique owned row of {@code profileId}, read without the Mango index. IMAP IDLE is
     * keyed by profileId alone and has no repository on the start verb, so a selector miss
     * used to answer "not found" and never reached {@link #getForRepository}. Unowned rows
     * are ignored (they are not a wildcard). More than one owned row refuses; an unreadable
     * row refuses rather than answering.
     */
    ImportProfileDefinition getOwnedRowIndexFree(String profileId);

    /**
     * Every enabled, scheduler-enabled profile of every repository, read from
     * {@code _all_docs} — one walk, no Mango index.
     *
     * <p>The scheduler used to enumerate through a selector, so while that index rebuilt the
     * poll saw an empty list and skipped every scheduled capture, indistinguishable from a
     * genuinely empty schedule. Nothing recorded that it had happened.
     *
     * <p>Throws {@code ProfileIndexNotReadyException} when the walk cannot be completed: the
     * caller must not read that as "nothing is scheduled". A row that exists but cannot be
     * interpreted is logged and skipped — no retry repairs it, and refusing the whole poll
     * would let one broken row stop every capture.
     */
    List<ImportProfileDefinition> listScheduledIndexFree();

    /**
     * Every OWNED profile row of every repository — enabled or not — read from
     * {@code _all_docs}: the same walk and the same per-row policy as
     * {@link #listScheduledIndexFree()}, without the scheduler filter.
     *
     * <p>Written for the webhook receiver, which used to pick its recipients out of
     * {@link #list()} — a selector. While that index rebuilt the receiver saw no profiles,
     * answered {@code no_profile} with a 200, and the event was gone; the selector's page cap
     * dropped the same way past its first page. Neither is what "no profile" means.
     *
     * <p>Rows that name no repository are not returned: they are not a wildcard, and the
     * runtime gate refuses them for every repository. A row that cannot be interpreted is
     * logged and skipped, as in the scheduled listing. Throws
     * {@code ProfileIndexNotReadyException} when the walk cannot be completed — the caller
     * must not read that as an empty list.
     */
    List<ImportProfileDefinition> listOwnedIndexFree();

    /**
     * Rewrites every legacy import-profile row saved under a CouchDB-generated id to its
     * deterministic id ({@code import_profile_definition:<profileId>}) — the import-profile
     * half of the §62 closure. Same window, same database, same startup patch entrance as
     * the connectors: the default cloud-import profile is created per repository by a patch
     * whose existence check is a Mango selector. Reads through {@code _all_docs} and
     * id-addressed gets only; idempotent; safe on every startup.
     */
    ConnectorDefinitionService.LegacyIdMigrationResult migrateLegacyGeneratedIds();
}
