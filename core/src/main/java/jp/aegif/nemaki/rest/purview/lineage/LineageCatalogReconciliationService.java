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
package jp.aegif.nemaki.rest.purview.lineage;

/**
 * Checking that what lineage points at is actually in the catalog, and repairing what is not.
 *
 * <h2>Why lineage has its own reconciliation</h2>
 *
 * <p>{@code SearchIndexReconciliationService} reconciles Solr, ACL and RAG. Its completion
 * condition is "the index matches the repository". This one's is different: an entity may be
 * legitimately absent from the repository and still have to exist in the catalog, because a
 * Process that ran last year refers to a folder that was purged since. Generalising the existing
 * service to cover both would put that rule inside the path that reindexes security data.
 *
 * <h2>What it decides</h2>
 *
 * <table border="1">
 * <caption>Folder and companion, and what each combination means</caption>
 * <tr><th>folder</th><th>companion</th><th>verdict</th><th>repair</th></tr>
 * <tr><td>exists</td><td>exists</td><td>{@code IN_SYNC}</td><td>none</td></tr>
 * <tr><td>exists</td><td>missing</td><td>{@code COMPANION_MISSING}</td><td>publish it</td></tr>
 * <tr><td>gone</td><td>exists</td><td>{@code SOURCE_MISSING}</td><td>mark {@code ORPHAN}</td></tr>
 * <tr><td>—</td><td>unreadable</td><td>{@code UNDETERMINED}</td><td><b>none</b></td></tr>
 * </table>
 *
 * <p><b>{@code UNDETERMINED} is not {@code IN_SYNC}.</b> A catalog that did not answer has told
 * us nothing, and a reconciliation that counts silence as agreement reports a healthy deployment
 * it never examined. It is counted separately and it keeps the report from being clean.
 *
 * <p><b>A companion is never deleted here.</b> {@code SOURCE_MISSING} means the folder is gone,
 * which is the ordinary end of a folder's life, and the companion is what lets last year's
 * Process still resolve. Marking it {@code ORPHAN} says so; deleting it would erase the lineage
 * that was the reason for creating it.
 *
 * <h2>What is deliberately not here yet</h2>
 *
 * <p>§2's obligation machine — {@code PENDING} / {@code CLAIMED(owner, token, lease)} /
 * {@code RESOLVED} / {@code UNRESOLVED}, and the projector's {@code WAITING_FOR_CATALOG} — is
 * not implemented. Its whole purpose is to park a projection while an entity is fetched, and the
 * projector that would park is inactive in this build (the writer is still v1 and every D-rest
 * driver is off). Building the queue now would add a state machine with no consumer, which is
 * the accumulation this increment was also meant to avoid. It belongs with the work that turns
 * the projector on.
 */
public interface LineageCatalogReconciliationService {

    /** What a folder and its companion say about each other. */
    enum Verdict {
        /** Both present. */
        IN_SYNC,
        /** The folder is there and the companion is not — a publish that did not land. */
        COMPANION_MISSING,
        /** The companion is there and the folder is not — ordinary, and marked {@code ORPHAN}. */
        SOURCE_MISSING,
        /** The catalog did not answer. Not agreement; not a repair either. */
        UNDETERMINED
    }

    /**
     * Counts from one reconciliation pass. Counts only — nothing here can carry a value.
     *
     * @param repaired companions published because the folder was there and they were not
     * @param markedOrphan companions marked because their folder is gone
     */
    record Report(String repositoryId, long checked, long inSync, long companionMissing,
            long sourceMissing, long undetermined, long repaired, long markedOrphan) {

        /**
         * Clean only when everything was determined and nothing needed repair.
         *
         * <p>{@code undetermined} counts against it deliberately: the point of the pass is to
         * establish a state, and a pass that could not is not a pass that found nothing wrong.
         */
        public boolean clean() {
            return undetermined == 0 && companionMissing == 0 && sourceMissing == 0;
        }
    }

    /**
     * Reconciles up to {@code maxFolders} folders, repairing what it safely can.
     *
     * <p>Bounded like the backfill, and for the same reason: an operator decides how much of a
     * maintenance window this gets.
     */
    Report reconcile(String repositoryId, int maxFolders, boolean repair);
}
