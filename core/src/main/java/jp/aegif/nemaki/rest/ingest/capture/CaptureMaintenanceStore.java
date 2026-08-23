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
package jp.aegif.nemaki.rest.ingest.capture;

import java.util.List;
import java.util.Map;

/**
 * What runs over intent rows after the ingest has gone: sweeping, listing, deleting.
 *
 * <p>Separate from {@link CaptureIntentStore} because it has a different caller and a different
 * risk. The ingest seam is on the hot path and its whole job is to fail closed; this one runs on
 * a timer and from an admin endpoint, and its whole job is to never turn "we do not know" into
 * either "it worked" or "delete it".
 */
public interface CaptureMaintenanceStore {

    /**
     * Moves intents that outlived the deadline to {@link CaptureState#UNRESOLVED}. That is all.
     *
     * <p>Deliberately does not go looking for the document. Whether an object that exists came
     * from this attempt cannot be decided without a stamp the ingest does not yet write, so a
     * sweeper that searched would be guessing and recording the guess as a finding.
     *
     * <p>Every transition is a {@code _rev} CAS and is idempotent, so several replicas sweeping
     * at once produce the right answer. It is <b>not</b> protected by leader election: that is
     * off by default, and when off the election grants leadership to every replica.
     *
     * @param olderThanMs intents opened before this instant are swept
     * @param batchLimit  the most rows to touch in one pass; bounded because with leader election
     *                    off, N replicas each walk the same batch every interval
     * @return how many rows this call moved
     */
    int sweepExpiredIntents(long olderThanMs, int batchLimit);

    /**
     * Deletes unresolved rows that became unresolved before the cutoff.
     *
     * <p>Keyed on when the row became unresolved, not when the intent opened — keying on the
     * opening would make a long-running ingest eligible for deletion the instant it was swept.
     * A row still in {@link CaptureState#CAPTURE_INTENT} is unreachable from here by
     * construction: it is not in the view.
     */
    int purgeUnresolvedOlderThan(long cutoffMs, int batchLimit);

    /**
     * Completed rows for one captured object, newest first, raw as stored.
     *
     * <p>For metadata-hash verification (p1-1d-metadata-hash.md §4). Raw maps rather than a
     * typed record because the completion evidence is open-ended — the verifier reads the keys
     * it knows and shows the rest.
     */
    java.util.List<java.util.Map<String, Object>> listCapturedForObject(
            String repositoryId, String objectId, int limit);

    /**
     * Every row for one source item newer than {@code sinceMs}, any state, newest first.
     *
     * <p>What downgrades a would-be MISMATCH to UNVERIFIABLE: a partial failure left an
     * unresolved row, or another wrapper completed a row without a hash — either way a record
     * of a later pass exists and "an unrecorded change" must not be claimed.
     */
    java.util.List<java.util.Map<String, Object>> listRowsForSourceSince(
            String repositoryId, String sourceObjectId, long sinceMs, int limit);

    /** Deletes completed rows captured before the cutoff. Keyed on when they completed. */
    int purgeCapturedOlderThan(long cutoffMs, int batchLimit);

    /**
     * The unresolved listing an operator reads, newest first.
     *
     * <p>Returns whole rows: an intent id and a timestamp would not tell anyone what happened,
     * and this row is the only record of the attempt.
     */
    UnresolvedPage listUnresolved(String repositoryId, int limit, int offset);

    /**
     * How many rows exist in each state.
     *
     * <p>Retention is unlimited by default, which means the lineage database grows with ingest
     * volume for ever — and until this existed there was no way to see that happening. The
     * database's own {@code doc_count} mixes journal events, dead letters and v2 rows, so it
     * cannot say what is growing (external review).
     *
     * <p>Counting walks the views, so it is an admin diagnostic rather than something to poll.
     *
     * @param scanLimit the most rows to count per state; the answer says whether it stopped there
     */
    CaptureCounts countByState(int scanLimit);

    /**
     * @param captureIntent open intents; a number that does not fall is a stuck sweeper
     * @param unresolved    swept and awaiting an operator
     * @param captured      completed evidence; this is the one that grows without bound
     * @param truncated     whether any count hit {@code scanLimit} and is therefore a lower bound
     */
    record CaptureCounts(long captureIntent, long unresolved, long captured, boolean truncated) {}

    /**
     * @param entries the rows on this page
     * @param limit   the page size asked for
     * @param offset  where this page started
     * @param hasMore whether a further page exists. NOT a total: counting every row to answer a
     *                page request would walk the whole view.
     */
    record UnresolvedPage(List<Map<String, Object>> entries, int limit, int offset,
            boolean hasMore) {}
}
