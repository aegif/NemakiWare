package jp.aegif.nemaki.rest.ingest;

import java.util.List;

/**
 * CRUD service for connector definitions stored in CouchDB nemaki_conf.
 */
public interface ConnectorDefinitionService {
    ConnectorDefinition create(ConnectorDefinition def);
    ConnectorDefinition get(String connectorId);

    /**
     * As {@link #get(String)}, except that a read which could not be ANSWERED refuses.
     * {@code get} answers null for a failed read and for absence alike; that is unchanged
     * here, and its callers are outside this change (the design ledger lists the ones that
     * take the null as absence without an index-free check of their own). The webhook
     * receiver resolves its connector before any signature is verified and may not walk the
     * database for an unauthenticated request, so it has nothing to follow a null with —
     * this read refuses instead.
     *
     * <p>Null means: the deterministic-id read answered "no such row", AND the selector
     * answered (empty, or with rows it could read that were not this connector's). A selector
     * that failed leaves a legacy-id row unexcluded and refuses; a selector that shows a row
     * this node cannot read refuses. What remains as null is a row under a legacy generated
     * id that the selector answered WITHOUT — the startup migration rewrites such rows and
     * reports the ones it could not, but a row written after the last migration pass (an
     * older node's, during a rolling upgrade) is not reported until the next one, and whether
     * a rebuilding index leaves an existing row out is not measured. This read does not walk.
     *
     * <p>The pair refusal is OPPORTUNISTIC, and the failing selector is where that shows.
     * Two rows the selector SHOWS are refused. A pair of which the selector shows one row, or
     * shows none because it failed, is not seen: a readable row at the deterministic id is
     * then answered with a legacy twin unexcluded, so the receiver can run with the canonical
     * row's secret and enabled state while a twin holds others. That is the same value this
     * read gives when the selector ANSWERS that no twin exists, and it is a decision, not an
     * oversight — a review named it and it was kept. Why: the id-addressed read IS the
     * index-free path this batch exists to provide. A selector that THROWS is what CouchDB
     * does when no usable Mango index is there — a fresh node, a rebuilt database — and
     * refusing then would stop every webhook exactly in the window the batch was written to
     * keep working through, which is also the window in which the §62 twin was created. How
     * long that window lasts is NOT measured here (an earlier version of this note cited the
     * v3.3.0 runbook's hours-scale figure, which measures the Solr/CMIS reindex and says
     * nothing about a Mango index over the config database; a review caught the substitution).
     * The decision does not rest on that figure. What bounds the other side: an event signed
     * with the twin's secret still
     * fails verification, an operator's edit during the window is refused because the write
     * path counts rows index-free, and the twin only exists on an installation upgraded past
     * a row the migration has not yet retired. Excluding it here is not affordable — the only
     * way to find a legacy id is a walk, and this read serves unauthenticated callers.
     *
     * <p>What THIS READ's answer — 503 or not — discloses to an unauthenticated caller,
     * stated as the classes it actually separates. A 503 means one of: a row this read
     * refuses exists at the id (unreadable — deterministic or legacy, as the selector shows
     * it — or two or more rows), or the id-addressed read failed, or the selector failed and
     * no deterministic row exists; which of these, the answer does not say. A non-503 means
     * the id is absent, or this read found at least one readable row and did not see a
     * second (a pair of which the selector shows one row, or a legacy-id row the selector
     * leaves out, is not seen by it); what the receiver then answers (401 for a disabled row
     * or a failed signature; on the GET handshake 404 for a disabled or non-Dropbox connector
     * or a missing, blank or over-long challenge) does not separate absent from present by
     * this read alone. While the selector is FAILING, THIS READ's non-503 does mean a readable
     * deterministic-id row exists, because absence then refuses (a legacy row cannot be
     * excluded) — but the receiver no longer passes that on: in that window it answers a
     * disabled row, a failed signature and the GET handshake's 404 with the same status and
     * body as a read that could not be answered (R3, see {@link #resolveOrRefuse}), so an
     * unauthenticated caller cannot separate the two THROUGH THIS READ's answer — the protocol
     * handshakes below answer before any signature and still do separate them. The receiver's protocol handshakes
     * disclose more and always did,
     * independently of this read: an enabled Dropbox connector answers the GET challenge
     * (recorded on that GET), an enabled teams / m365_mail connector echoes
     * {@code validationToken} before any signature (recorded at the receiver's
     * {@code isMicrosoftGraphSubscriptionValidation}) — in this window as outside it, which
     * is why closing the 401/404 side does not make the window silent for a Graph or Dropbox
     * connector. Refusing whenever the selector fails would remove the window disclosure at
     * the price of every webhook while the index is down; answering as "could not be read"
     * during that window, to a failed signature and a disabled row and the GET handshake's
     * refusal, removes it without that price and is what the receiver now does (R3). A sender
     * holding the right secret is not stopped: its event dispatches exactly as before.
     *
     * @throws ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException when the row
     *         exists but could not be read as this connector, when the id-addressed read
     *         failed with anything other than "not found", when the selector failed and no
     *         deterministic row exists, when the selector shows a row that cannot be read,
     *         or when the selector shows two or more rows that define this connector
     */
    ConnectorDefinition getOrRefuse(String connectorId);

    /**
     * What {@link #getOrRefuse} answered, plus the one fact its caller cannot recover
     * afterwards: whether the Mango SELECTOR answered this read.
     *
     * <p>It decides an answer, not a row. While the selector is down, absence refuses (a
     * legacy-id row cannot be excluded), so a 401 standing next to that 503 told an
     * unauthenticated caller that a readable deterministic-id row exists at the id — the one
     * thing this front door's disclosure analysis keeps out of the answer. The receiver makes
     * the two answers one for as long as this is {@code false} (R3).
     *
     * @param connector what {@code getOrRefuse} answers (the row, or null for absence)
     * @param selectorAnswered whether the selector answered this read. FALSE also when no
     *        read was made at all (a null id): there is no selector answer to lean on, and
     *        "could not ask" must not be handed out with the value of "asked, and the answer
     *        was no".
     */
    record Resolution(ConnectorDefinition connector, boolean selectorAnswered) {}

    /** {@link #getOrRefuse}, with {@link Resolution#selectorAnswered()} alongside it (R3). */
    Resolution resolveOrRefuse(String connectorId);

    /**
     * Establishes from {@code _all_docs} — not the Mango index, so it answers while that
     * index rebuilds — that exactly one row defines this connector: the row
     * {@link #getOrRefuse} answered. A pair of which the index showed one row, or a legacy-id
     * row beside the deterministic one, REFUSES: running with whichever row a read happened
     * to find, its secret and enabled state chosen by index order, is the choice the
     * service's own rule forbids (R2). {@code getOrRefuse} itself does not walk: the receiver
     * reads it before the signature is verified, and a walk of the configuration database per
     * unauthenticated request is an amplifier (a walk placed there was withdrawn in
     * {@code dece81f7d}), so callers make this call only once the request is authenticated
     * and rate limited. One walk of nemaki_conf per call.
     *
     * @throws ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException when two or more
     *         rows define the connector, when the walk shows none (the index and the walk
     *         disagree, and nothing establishes which is right), or when the walk could not
     *         be completed or a row of it could not be read
     */
    void refuseUnlessUniquelyDefined(String connectorId);

    List<ConnectorDefinition> list();
    List<ConnectorDefinition> listByArchetype(SourceArchetype archetype);
    ConnectorDefinition update(ConnectorDefinition def);
    void delete(String connectorId);
    boolean exists(String connectorId);

    /**
     * Whether ANY row defines {@code connectorId}, read without the Mango index — the twin
     * of {@link ImportProfileDefinitionService#existsIndexFree}. Lets a caller tell "the
     * index cannot show it" (503) from "there is no such connector" (404). Throws
     * {@code ConnectorIndexNotReadyException} when a row could not be classified: that is
     * "could not ask", which must not be answered as "no".
     */
    boolean existsIndexFree(String connectorId);

    /**
     * How many rows define {@code connectorId}, answered from {@code _all_docs}.
     * {@link #get} returns the selector's first row when several exist; the runtime
     * must refuse a pair rather than run with whichever credential the index listed
     * first. Unreadable rows throw {@code ConnectorIndexNotReadyException}.
     */
    int countIndexFree(String connectorId);

    /**
     * The one enabled connector whose {@code sourceSystem} is any of {@code sourceSystems}
     * and whose archetype matches. The list is an ORDERED preference (the spelling the
     * request used, then its aliases); a tie WITHIN one key is refused, because there the
     * old code returned whichever row the index handed back. One index-free walk serves the
     * whole list — walking per key multiplied the cost by the number of aliases.
     */
    ConnectorDefinition findBySystemsAndArchetype(java.util.List<String> sourceSystems,
            SourceArchetype archetype);

    /**
     * Removes the row addressed by {@code docId} and returns how many rows of this connector
     * remain. 0 means another caller removed the other twin concurrently, so the connector is
     * gone through a path that assumes a survivor; -1 means the count could not answer, which
     * is "unknown", never "a row survives".
     */
    int delete(String connectorId, String docId);



    /**
     * Finds the one enabled connector matching the given sourceSystem and archetype.
     * Used for auto-resolution when the caller does not explicitly specify a connectorId.
     *
     * @return matching connector, or null if none found
     * @throws IllegalStateException when several enabled connectors match one key — the
     *         choice is refused rather than made by storage order.
     */
    ConnectorDefinition findBySystemAndArchetype(String sourceSystem, SourceArchetype archetype);

    /**
     * Rewrites every legacy connector row saved under a CouchDB-generated id to its
     * deterministic id ({@code connector_definition:<connectorId>}), closing the §62 window:
     * a generated-id row is invisible to the id-addressed duplicate check, so "Mango selector
     * answers empty while its index rebuilds" could produce a second definition — once per
     * legacy row, and only on upgraded installations.
     *
     * <p>Reads through {@code _all_docs} and id-addressed gets ONLY — no view and no Mango
     * selector — so it works exactly when the window opens: while indexes are rebuilding.
     * Idempotent; safe to run on every startup.
     */
    LegacyIdMigrationResult migrateLegacyGeneratedIds();

    /** What one migration pass did. Divergent rows are reported, never auto-resolved. */
    final class LegacyIdMigrationResult {
        /** Legacy rows rewritten under their deterministic id (copy verified, then retired). */
        public int migrated;
        /** Legacy rows whose deterministic twin already held the same content — leftovers of
         *  an interrupted earlier pass — retired without a new write. "The same" means equal
         *  APART FROM HOW THE IDENTITY IS STORED: only the identity field is read through the
         *  mapper before comparing (the number 42 and the string "42" are the same identity;
         *  a value the mapper refuses stays as it is stored), and every other field is
         *  compared as stored — {@code retentionDays: 30} and {@code "30"} are still different
         *  content — except {@code _id}, {@code _rev} and {@code _attachments}, which are not
         *  content and are dropped before the comparison. The copy this migration writes
         *  carries the read identity, so an interrupted pass has to be able to recognise its
         *  own leftover. */
        public int sweptDuplicates;
        /** Rows already under their deterministic id whose stored identity was rewritten as
         *  the string this node reads it as. Until that happens the type-strict Mango
         *  selector cannot match the row while every index-free walk counts it, so its
         *  updates answer 503. */
        public int normalised;
        /** connectorIds where the legacy row and the deterministic row DISAGREE. Neither row
         *  is touched: choosing silently is exactly the data loss §62 is about. */
        public final java.util.List<String> divergent = new java.util.ArrayList<>();
        /** Rows the pass could not finish (a failed write, a conflicting delete, an
         *  unclassifiable document). Each retries on the next startup. */
        public final java.util.List<String> failures = new java.util.ArrayList<>();

        public boolean clean() {
            return divergent.isEmpty() && failures.isEmpty();
        }

        @Override
        public String toString() {
            return "migrated=" + migrated + ", sweptDuplicates=" + sweptDuplicates
                    + ", normalised=" + normalised
                    + ", divergent=" + divergent + ", failures=" + failures;
        }
    }
}
