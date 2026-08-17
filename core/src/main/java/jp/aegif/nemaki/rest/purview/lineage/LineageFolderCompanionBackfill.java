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

import java.util.List;

/**
 * Giving every folder that already exists the DataSet companion §3 requires (増分 B).
 *
 * <p>A folder created from now on gets its companion in the same bulk as the folder itself.
 * Every folder created before this increment has none, so a lineage endpoint that names one
 * resolves to nothing. This is the one-off that closes that gap — and, because a catalog can be
 * rebuilt or a repository restored, it has to stay re-runnable rather than being a migration
 * anyone runs once.
 *
 * <h2>What "resumable" has to mean here</h2>
 *
 * <p>There is no way to enumerate a repository's folders by id: the content DAO offers children
 * of a folder, so the enumeration is a walk. A walk cannot be resumed from a single "last id"
 * cursor, because the position in a tree is a frontier and not a point. So the frontier itself
 * is what is persisted — the folder ids discovered but not yet processed — and a resumed run
 * continues from exactly where the previous one stopped rather than starting the walk again.
 *
 * <p>The frontier is bounded. If it would exceed {@link #DEFAULT_MAX_FRONTIER} the run stops
 * and says so; it does not drop ids to fit. A truncated frontier would silently skip whole
 * subtrees and then report completion, which is the specific failure this design has to avoid.
 */
public interface LineageFolderCompanionBackfill {

    /** Folders per Atlas bulk call. Bounded so one batch is a unit of progress, not a run. */
    int DEFAULT_BATCH_SIZE = 100;

    /**
     * Frontier ids held in the resume document before a run refuses to continue.
     *
     * <p>Chosen so the document stays a few megabytes at most. Exceeding it is reported, never
     * absorbed by dropping ids.
     */
    int DEFAULT_MAX_FRONTIER = 50_000;

    /** Where a run stopped, and whether that is a stopping place or a wall. */
    enum State {
        /** Nothing has been recorded for this repository. */
        NOT_STARTED,
        /** Batches remain; call again to continue from the persisted frontier. */
        PAUSED,
        /** The walk finished and every folder was accounted for. */
        COMPLETE,
        /**
         * Stopped without completing. The frontier is preserved, so a later run resumes; what
         * must not happen is this being read as done.
         */
        FAILED
    }

    /** Why a run refused to start, or stopped early. Never carries a value from the catalog. */
    enum Refusal {
        NONE,
        /** The catalog's schema does not have the companion type yet. */
        SCHEMA_NOT_READY,
        /** The catalog did not answer. Nothing was attempted. */
        CATALOG_UNREACHABLE,
        /** The repository has no root folder to walk from. */
        REPOSITORY_UNAVAILABLE,
        /** The walk found more unprocessed folders than the resume document may hold. */
        FRONTIER_TOO_LARGE,
        /** At least one folder could not be published. Details are counted, not narrated. */
        PUBLISH_FAILED
    }

    /**
     * What a run would do, without doing any of it.
     *
     * <p>{@code folderCount} is a real count from the repository, not an estimate: an operator
     * deciding whether to run this against production is entitled to know the size, and a
     * "roughly" would make the completion count unverifiable afterwards.
     */
    record Plan(String repositoryId, long folderCount, long alreadyPresent, boolean schemaReady,
            boolean catalogReachable, Refusal refusal) {

        public boolean runnable() {
            return refusal == Refusal.NONE;
        }
    }

    /** Where a repository's backfill stands. Counts only; nothing here can carry a secret. */
    record Progress(String repositoryId, State state, long processed, long created,
            long alreadyPresent, long failed, int pendingFrontier, Refusal refusal) {

        /**
         * Complete <em>and</em> nothing failed.
         *
         * <p>Kept separate from {@code state == COMPLETE} so that a run which finished the walk
         * with failures cannot be read as a success by a caller checking one field.
         */
        public boolean successful() {
            return state == State.COMPLETE && failed == 0 && refusal == Refusal.NONE;
        }
    }

    /** Counts and readiness, with no writes to the catalog or to the resume document. */
    Plan plan(String repositoryId);

    /**
     * Runs at most {@code maxBatches} batches, then returns with the frontier persisted.
     *
     * <p>Bounded on purpose: an operator can give it a slice of a maintenance window and come
     * back, and a run that is going badly stops at a batch boundary rather than after the whole
     * repository.
     */
    Progress run(String repositoryId, int maxBatches);

    /** The persisted position, without touching the catalog. */
    Progress progress(String repositoryId);

    /** Repositories with a resume document, finished or not. */
    List<String> repositoriesWithProgress();
}
