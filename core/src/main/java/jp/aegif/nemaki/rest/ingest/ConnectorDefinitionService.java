package jp.aegif.nemaki.rest.ingest;

import java.util.List;

/**
 * CRUD service for connector definitions stored in CouchDB nemaki_conf.
 */
public interface ConnectorDefinitionService {
    ConnectorDefinition create(ConnectorDefinition def);
    ConnectorDefinition get(String connectorId);
    List<ConnectorDefinition> list();
    List<ConnectorDefinition> listByArchetype(SourceArchetype archetype);
    ConnectorDefinition update(ConnectorDefinition def);
    void delete(String connectorId);
    boolean exists(String connectorId);

    /**
     * Deletes ONE definition row of {@code connectorId}, addressed by its raw document id.
     *
     * <p>This exists for divergent twins: the migration refuses to choose between a legacy
     * row and a deterministic row that disagree, and {@link #delete(String)} removes every
     * selector match — so "delete the one you do not want" had no API that could do it. The
     * row is verified to actually define {@code connectorId} before it is removed; anything
     * else refuses.
     */
    void delete(String connectorId, String docId);

    /**
     * Finds the first enabled connector matching the given sourceSystem and archetype.
     * Used for auto-resolution when the caller does not explicitly specify a connectorId.
     *
     * @return matching connector, or null if none found
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
