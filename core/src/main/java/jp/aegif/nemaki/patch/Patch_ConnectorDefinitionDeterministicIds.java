/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Moves legacy generated-id connector rows AND import-profile rows to their deterministic
 * ids (the §62 migration, both halves).
 *
 * <p>The import-profile half was added after a review found the default cloud-import
 * profile created per repository by {@code Patch_DefaultCloudDriveConnectorProfile} with the
 * exact shape the connectors had just been cured of: a selector-based existence check, a
 * generated id on miss, no id-addressed check, no migration. Same database, same rebuilding
 * index, same startup entrance. Both migrations run in this one pass so there is exactly one
 * ordering concern in the patch chain.
 *
 * <h2>Why this patch is always-run, historyless, and ungated — all three deliberately</h2>
 *
 * <p>The §62 window opens when a Mango index is rebuilding at startup: the selector answers
 * "no such connector", the duplicate check passes, and a second definition is written. A
 * generated-id row is invisible to the id-addressed check that closed the window for NEW
 * rows, so upgraded installations stay exposed until their rows are rewritten — which is
 * this migration's job.
 *
 * <ul>
 * <li><b>Ungated:</b> the CMIS-view gate protects patches whose existence checks read
 * views; a broken index would make those checks lie. This migration reads through
 * {@code _all_docs} and id-addressed gets ONLY — the primary index, which cannot
 * under-report — so the gate has nothing to protect here. Worse than useless, actually:
 * the gate refuses exactly on the broken startup, and the broken startup is when the
 * window this migration closes is OPEN. Gating it would stop it precisely when it is
 * needed.</li>
 * <li><b>Historyless:</b> {@code isApplied()} is a view-based read and
 * {@code createPathHistory()} writes under a generated id — the two reasons the WebAuthn
 * always-run override had to be gated. Using neither is what makes skipping the gate
 * sound. A once-applied patch would also freeze the "ran while something was broken"
 * outcome forever; re-running until clean is the correct semantics for a migration whose
 * per-row failures are retryable.</li>
 * <li><b>Always-run:</b> idempotent by construction (a fully migrated database yields a
 * no-op pass over a tiny config DB), and each startup retries whatever a previous pass
 * reported as failed.</li>
 * </ul>
 *
 * <p>Divergent twins — a legacy row and a deterministic row that DISAGREE — are reported at
 * ERROR on every startup and never auto-resolved: choosing a winner silently destroys the
 * other row's configuration, which is the very loss §62 is about.
 */
public class Patch_ConnectorDefinitionDeterministicIds extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_ConnectorDefinitionDeterministicIds.class);

    @Override
    public String getName() {
        return "connector-definition-deterministic-ids";
    }

    @Override
    public boolean apply() {
        try {
            var ctx = SpringContext.getApplicationContext();
            if (ctx == null) {
                log.error("[patch=" + getName() + "] ApplicationContext not available; the"
                        + " legacy connector rows stay unmigrated until the next startup");
                return false;
            }
            // Each half looks up its OWN bean, inside its own half. Fetching both first
            // meant a missing profile bean skipped the connector migration and vice versa —
            // the isolation this class documents, undone one line above where it is claimed.
            // A review found the contradiction.

            // Each half is attempted on its own. The first version evaluated the connector
            // pass as an argument, so an unanswered connector walk (one exception) skipped
            // the profile pass for that startup — a review named it: sharing a database
            // does not make one enumeration's failure the other's.
            boolean connectorsRan = runHalf("connectors",
                    () -> ctx.getBean(ConnectorDefinitionService.class).migrateLegacyGeneratedIds(),
                    "DELETE .../admin/connectors/{id}?docId=...");
            boolean profilesRan = runHalf("import profiles",
                    () -> ctx.getBean(ImportProfileDefinitionService.class).migrateLegacyGeneratedIds(),
                    "DELETE .../admin/import-profiles/{id}?docId=...");
            return connectorsRan && profilesRan;
        } catch (Exception e) {
            log.error("[patch=" + getName() + "] the migration pass itself failed; it will"
                    + " retry on the next startup", e);
            return false;
        }
    }

    /**
     * Runs one half and reports it. TRUE means "the pass ran" — which, for an always-run
     * migration, it did. A standing divergence is an administrator task, not a retryable
     * startup condition: returning false for it marked phase 2 failed on every startup for
     * ever, which re-triggered the fallback listener's full re-apply each time — noise that
     * buries the one ERROR that matters. Failed rows do not need the false either: the next
     * startup retries them because the patch always runs. False is reserved for "the pass
     * could not run at all".
     */
    private boolean runHalf(String what,
            java.util.function.Supplier<ConnectorDefinitionService.LegacyIdMigrationResult> pass,
            String resolver) {
        try {
            reportPass(what, pass.get(), resolver);
            return true;
        } catch (Exception e) {
            log.error("[patch=" + getName() + "] the " + what + " migration pass itself"
                    + " failed; it will retry on the next startup", e);
            return false;
        }
    }

    private void reportPass(String what,
            ConnectorDefinitionService.LegacyIdMigrationResult result, String resolver) {
        if (result.clean()) {
            // normalised counts too: a pass that rewrote a stored identity DID something, and
            // summarising it as "no legacy rows" was the one shape of this summary that could
            // read as "nothing was touched" while rows were written. A review found it.
            if (result.migrated == 0 && result.sweptDuplicates == 0 && result.normalised == 0) {
                log.debug("[patch=" + getName() + "] no legacy " + what + " rows");
            } else {
                log.info("[patch=" + getName() + "] " + what + ": " + result);
            }
            return;
        }
        // Loud on EVERY startup until a human resolves the divergent rows or the failed
        // steps stop failing. A single WARN at upgrade time is how §62 sat unnoticed in
        // the first place.
        log.error("[patch=" + getName() + "] the " + what + " migration did not complete"
                + " cleanly: " + result + ". Failed rows retry on the next startup;"
                + " divergent rows need an administrator to delete the unwanted row ("
                + resolver + ").");
    }

    @Override
    protected void applySystemPatch() {
        // Never called: apply() is overridden. The work is index-free by design; see the
        // class comment before routing it through the gated stages.
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        // Never called: connector definitions live in nemaki_conf, which belongs to no
        // repository.
    }
}
