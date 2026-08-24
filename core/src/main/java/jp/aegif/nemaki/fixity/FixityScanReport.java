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
package jp.aegif.nemaki.fixity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one fixity pass looked at, and what it found.
 *
 * <h2>Why the verdict is separate from the counts</h2>
 *
 * <p>Design: {@code docs/design/p1-2-fixity.md} §2.1. "MISMATCH: 0" is not "nothing is
 * corrupted" — it is "nothing I looked at is corrupted", and a report that does not say how much
 * it looked at turns the first into the second. That is the {@code COMPLETE} lesson from the
 * ACL-epoch migration, where "every document in the index is stamped" was read as "every
 * document is in the index".
 *
 * <p>So a pass that stopped at its limit is {@link Verdict#PARTIAL}, never {@code COMPLETE},
 * however clean its counts are.
 */
public record FixityScanReport(Verdict verdict, String repositoryId, long scanned,
                               long match, long mismatch, long unverifiable, long notRecorded,
                               List<Finding> findings, String note) {

    /** What the pass as a whole did — distinct from what it found. */
    public enum Verdict {
        /** No pass has run in this JVM. */
        NOT_RUN,
        /** A pass is in flight. */
        RUNNING,
        /** The pass reached the end of its scope. */
        COMPLETE,
        /**
         * The pass stopped early — a limit, a deadline, an interruption.
         *
         * <p>Never rounded up to {@code COMPLETE}. The counts below are then a statement about
         * a sample, and calling that complete is the difference between "we checked" and "we
         * checked some".
         */
        PARTIAL,
        /**
         * The pass itself failed. Nothing can be concluded from the counts, including zeros.
         */
        FAILED
    }

    /**
     * One object worth an operator's attention.
     *
     * <p>Only mismatches and unverifiables are listed. Matches are counted, not listed: a report
     * that enumerates every intact object buries the handful that are not, and the counts
     * already say how many were fine.
     */
    public record Finding(String objectId, FixityOutcome outcome, String recordedDigest,
                          String computedDigest, String reason) {
    }

    public static FixityScanReport notRun(String repositoryId) {
        return new FixityScanReport(Verdict.NOT_RUN, repositoryId, 0, 0, 0, 0, 0, List.of(),
                "no fixity pass has run in this JVM");
    }

    public static FixityScanReport failed(String repositoryId, String reason) {
        return new FixityScanReport(Verdict.FAILED, repositoryId, 0, 0, 0, 0, 0, List.of(),
                reason);
    }

    /** The wire shape, with the verdict first because it governs how the counts read. */
    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verdict", verdict.name());
        body.put("repositoryId", repositoryId);
        body.put("scanned", scanned);
        body.put("match", match);
        body.put("mismatch", mismatch);
        body.put("unverifiable", unverifiable);
        body.put("notRecorded", notRecorded);
        body.put("findings", findings.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objectId", f.objectId());
            m.put("outcome", f.outcome().name());
            m.put("recordedDigest", f.recordedDigest());
            m.put("computedDigest", f.computedDigest());
            m.put("reason", f.reason());
            return m;
        }).toList());
        body.put("algorithm", FixityVerifier.ALGORITHM);
        body.put("subject", FixityVerifier.SUBJECT_STORED_REVERIFIED);
        if (note != null) {
            body.put("note", note);
        }
        // Said on every response, not only when something is wrong. A fixity report is the kind
        // of artefact that gets forwarded and quoted, and the limit of what it establishes has
        // to travel with it.
        body.put("limits", "A mismatch means the stored bytes are not what this repository "
                + "recorded — not that they were tampered with: the digest is an ordinary "
                + "stored property, so anything with direct database access can change both "
                + "and keep them agreeing. NOT_RECORDED objects were not checked at all. A "
                + "PARTIAL verdict means these counts describe a sample.");
        return body;
    }
}
