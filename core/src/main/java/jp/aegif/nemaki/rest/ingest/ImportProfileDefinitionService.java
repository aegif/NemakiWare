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
     * A row of the owned listing that could not be interpreted as a profile, with the raw
     * fields a caller needs to tell whether the row was addressed to it. The webhook
     * receiver asks {@link #addressedTo(String, SourceArchetype)}: a row that was addressed
     * to its connector and cannot be read is a recipient it cannot establish, not "no
     * recipient".
     *
     * <p>The two questions the receiver asks of a readable row — does it name the connector,
     * does it admit the connector's archetype — are asked of the raw fields one at a time,
     * and each field of unreadable shape leaves only ITS question open. A row whose connector
     * fields cannot be read but whose archetype list plainly excludes the connector's
     * archetype is not addressed to it, and neither is a row whose archetype list cannot be
     * read but whose connector fields name someone else: in both, a readable row with the
     * same fields would have been filtered out on the readable field. The first version
     * folded both fields into one flag and refused on either; a review found the over-throw.
     *
     * @param allowedArchetypes the raw {@code allowedArchetypes} entries when the field is a
     *                          list of strings (an empty list restricts none, as
     *                          {@link ImportProfileDefinition#isArchetypeAllowed} reads it);
     *                          null when the field is absent or of a shape this node cannot
     *                          read — both admit every archetype, which is the fail-closed
     *                          reading of a restriction that cannot be established, so the
     *                          two need no telling apart
     * @param addresseeUnknown true when {@code defaultConnectorId} or
     *                         {@code allowedConnectorIds} is present in the row but not in a
     *                         shape this node can read (a value that is not a string, a list
     *                         with non-string entries): whom the row names cannot be
     *                         established, so it names every connector. The archetype list
     *                         does not feed this flag — see {@code allowedArchetypes}
     * @param reason why the row could not be read (the deserialisation failure, or that the
     *               row has no profileId)
     */
    record UninterpretableRow(String docId, String profileId, String defaultConnectorId,
            List<String> allowedConnectorIds, List<String> allowedArchetypes,
            boolean addresseeUnknown, String reason) {
        /**
         * Whether the row names this connector — as far as its raw connector fields say, and
         * "yes" for every connector when those fields are there but cannot be read: a row
         * whose addressee cannot be established is not a row that addresses nobody.
         */
        public boolean namesConnector(String connectorId) {
            if (connectorId == null) {
                return false;
            }
            if (addresseeUnknown) {
                return true;
            }
            return connectorId.equals(defaultConnectorId)
                    || (allowedConnectorIds != null && allowedConnectorIds.contains(connectorId));
        }

        /**
         * Whether the row's raw {@code allowedArchetypes} admit this archetype, read the way
         * {@link ImportProfileDefinition#isArchetypeAllowed} reads a readable row: an absent
         * or empty list admits every archetype, and a connector with no archetype is admitted
         * by no restricting list. A list holding a name that is not an archetype this node
         * knows cannot be established to exclude anything (such a row would not have read at
         * all, whatever its author meant), so it admits every archetype, like a list of
         * unreadable shape. The connector fields play no part here.
         */
        public boolean admitsArchetype(SourceArchetype archetype) {
            if (allowedArchetypes == null || allowedArchetypes.isEmpty()) {
                return true;
            }
            for (String name : allowedArchetypes) {
                if (!isAnArchetype(name)) {
                    return true;
                }
            }
            return archetype != null && allowedArchetypes.contains(archetype.name());
        }

        /**
         * Whether the row was addressed to this connector: it names it AND admits its
         * archetype — the receiver's conjunction for a readable row, asked of the raw fields.
         * A readable row with the same fields would have been filtered out on whichever
         * question answers "no"; refusing on it would stop a dispatch that row could never
         * have received. A review found the receiver refusing on the name alone; the next
         * found it refusing on either field's unreadable shape.
         */
        public boolean addressedTo(String connectorId, SourceArchetype archetype) {
            return namesConnector(connectorId) && admitsArchetype(archetype);
        }

        private static boolean isAnArchetype(String name) {
            for (SourceArchetype known : SourceArchetype.values()) {
                if (known.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The owned listing: the rows the walk could read, and the rows it could not. A caller
     * that would answer "none" from {@code profiles()} alone has to look at
     * {@code uninterpretable()} first.
     */
    record OwnedProfiles(List<ImportProfileDefinition> profiles,
            List<UninterpretableRow> uninterpretable) {
    }

    /**
     * Every OWNED profile row of every repository — enabled or not — read from
     * {@code _all_docs}: the same walk and the same per-row policy as
     * {@link #listScheduledIndexFree()}, without the scheduler filter.
     *
     * <p>Written for the webhook receiver, which used to pick its recipients out of
     * {@link #list()} — a selector. When the index did not show a row the receiver saw no
     * profile, answered {@code no_profile} with a 200, and the event was gone (a listing
     * that could not be completed was a 500); and the selector's single page dropped every
     * row past the 200th the same way. Whether a rebuilding index shows an existing row as
     * absent has NOT been measured on a real CouchDB; the page cap needed no rebuild.
     *
     * <p>Rows that name no repository are not returned: they are not a wildcard, and the
     * import resolves no row for them in any repository. A row that cannot be interpreted is
     * logged and reported in {@code uninterpretable()} with its raw connector fields — not
     * dropped: the receiver refuses (503) when such a row names its connector, and ignores it
     * otherwise, so one broken row stops the webhooks of the connector it names and no other
     * — except a row whose connector fields themselves cannot be read, which names every
     * connector ({@link UninterpretableRow#addresseeUnknown()}): the trade is one such row
     * stopping every webhook until it is repaired, against an event to it being consumed as
     * "no profile". Rows whose raw {@code enabled} is {@code false} (the literal, or the
     * string) are not reported: they could not have been recipients. Throws
     * {@code ProfileIndexNotReadyException} when the walk cannot be completed — the caller
     * must not read that as an empty list.
     */
    OwnedProfiles listOwnedIndexFree();

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
