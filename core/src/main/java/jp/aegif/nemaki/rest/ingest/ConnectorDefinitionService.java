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
     * {@code get} answers null for a failed id-addressed read and for absence alike, and its
     * callers follow a null with an index-free check of their own; a caller that may not
     * afford that walk — the webhook receiver, before any signature is verified — reported
     * the first as the second. Null here means the selector and the deterministic-id read
     * both answered "no such row". A row saved under a legacy generated id that the startup
     * migration could not rewrite still answers null: it is reported at every startup, and
     * this read does not walk.
     *
     * @throws ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException when the row
     *         exists but could not be read as this connector, or when the id-addressed read
     *         failed with anything other than "not found"
     */
    ConnectorDefinition getOrRefuse(String connectorId);

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
        /** Legacy rows whose deterministic twin already held IDENTICAL content — leftovers of
         *  an interrupted earlier pass — retired without a new write. */
        public int sweptDuplicates;
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
                    + ", divergent=" + divergent + ", failures=" + failures;
        }
    }
}
