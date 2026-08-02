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
package jp.aegif.nemaki.rest.purview.journal;

/**
 * One target's §8-b v2 projection lifecycle on a v2 journal row — the typed form of
 * {@code publishStatusByTarget[target]} + {@code v2ClaimByTarget[target]} +
 * {@code v2TerminalReasonByTarget[target]}.
 *
 * <p>The claim audit bundle ({@code claimToken}, {@code claimedAtMs}, {@code retryCount}) is
 * all-or-nothing: it exists iff a claim ever happened for this target, and once present it is
 * never removed — only {@code FAILED→PROJECTING} (a fresh attempt) replaces token/claimedAt and
 * clears the per-attempt {@code verifyingSinceMs} marker. {@code retryCount} starts at 0 on
 * first claim, increments only on an observed publish failure (PROJECTING→FAILED), and is never
 * reset. All timestamps are epoch millis (numeric, exact-integral) — never ISO strings, so v2
 * range-sorted view keys cannot repeat v1's variable-fraction-width ordering defect.
 *
 * <p>State-dependent shape (enforced at construction — a contradiction never becomes a value):
 * <ul>
 *   <li>PENDING / WAITING_FOR_CATALOG: no bundle, no lease, no verifyingSince, no reason
 *       (UNRESOLVED additionally requires the reason; see below);</li>
 *   <li>PROJECTING: bundle + live lease; verifyingSince absent (cleared at claim);</li>
 *   <li>VERIFYING: bundle + live lease + verifyingSince;</li>
 *   <li>FAILED: bundle, no lease; verifyingSince present iff the failed attempt reached
 *       VERIFYING (the stage marker);</li>
 *   <li>PUBLISHED: bundle + verifyingSince (came through VERIFYING), no lease;</li>
 *   <li>UNPROJECTABLE: bundle + verifyingSince + reason, no lease;</li>
 *   <li>REJECTED: reason required; two legal provenances distinguished by the bundle —
 *       gate-REJECTED (bundle present, verifyingSince absent) and creation-time REJECTED
 *       (no bundle);</li>
 *   <li>UNRESOLVED: no bundle, reason required;</li>
 *   <li>DISCARDED: bundle optional (two entry paths); if present it is complete; no lease;</li>
 *   <li>SKIPPED: never legal on a v2 row.</li>
 * </ul>
 */
public record LineageTargetLifecycle(
        LineagePublishStatus status,
        String claimToken,
        Long claimedAtMs,
        Long leaseExpiresAtMs,
        Long verifyingSinceMs,
        Long retryCount,
        TerminalReason terminalReason
) {

    /** Durable reason payload for UNPROJECTABLE / REJECTED / UNRESOLVED (§8-b v2.3). */
    public record TerminalReason(String reason, String detail, long atMs) {

        /** Detail is evidence, not a log dump — bounded so a pathological message cannot
         * bloat the row (F8). Truncation is explicit in the stored text. */
        public static final int MAX_DETAIL_LENGTH = 500;
        public static final int MAX_REASON_LENGTH = 100;

        public TerminalReason {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("terminal reason must not be blank");
            }
            if (reason.length() > MAX_REASON_LENGTH) {
                throw new IllegalArgumentException("terminal reason must be a short code"
                        + " (<= " + MAX_REASON_LENGTH + " chars), got " + reason.length());
            }
            if (detail == null) {
                throw new IllegalArgumentException("terminal reason detail must not be null"
                        + " (empty is allowed; absent is not — the reason is the evidence)");
            }
            if (detail.length() > MAX_DETAIL_LENGTH) {
                String marker = "…[truncated]";
                detail = detail.substring(0, MAX_DETAIL_LENGTH - marker.length()) + marker;
            }
            if (atMs <= 0) {
                throw new IllegalArgumentException("terminal reason atMs must be positive");
            }
        }
    }

    public LineageTargetLifecycle {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        boolean hasToken = claimToken != null && !claimToken.isBlank();
        boolean hasClaimedAt = claimedAtMs != null;
        boolean hasRetry = retryCount != null;
        boolean bundle = hasToken && hasClaimedAt && hasRetry;
        if ((hasToken || hasClaimedAt || hasRetry) && !bundle) {
            throw new IllegalArgumentException("the claim audit bundle (claimToken, claimedAtMs,"
                    + " retryCount) is all-or-nothing; got a partial bundle in " + status);
        }
        if (bundle) {
            if (claimedAtMs <= 0) {
                throw new IllegalArgumentException("claimedAtMs must be positive");
            }
            if (retryCount < 0) {
                throw new IllegalArgumentException("retryCount must be >= 0");
            }
        }
        boolean lease = leaseExpiresAtMs != null;
        if (lease && leaseExpiresAtMs <= 0) {
            throw new IllegalArgumentException("leaseExpiresAtMs must be positive");
        }
        boolean verifying = verifyingSinceMs != null;
        if (verifying && verifyingSinceMs <= 0) {
            throw new IllegalArgumentException("verifyingSinceMs must be positive");
        }
        if (verifying && !bundle) {
            throw new IllegalArgumentException("verifyingSinceMs requires the claim bundle —"
                    + " VERIFYING is only reachable through a claim");
        }
        boolean reason = terminalReason != null;
        switch (status) {
            case PENDING, WAITING_FOR_CATALOG -> require(!bundle && !lease && !verifying && !reason,
                    status + " carries no claim bundle, lease, verify marker, or reason");
            case PROJECTING -> require(bundle && lease && !verifying && !reason,
                    "PROJECTING requires a live claim (bundle + lease) and no verify marker");
            case VERIFYING -> require(bundle && lease && verifying && !reason,
                    "VERIFYING requires a live claim and verifyingSinceMs");
            case FAILED -> require(bundle && !lease && !reason,
                    "FAILED requires the bundle, no live lease, no reason"
                            + " (verifyingSinceMs optional: the stage marker)");
            case PUBLISHED -> require(bundle && !lease && verifying && !reason,
                    "PUBLISHED requires the bundle + verifyingSinceMs (it came through"
                            + " VERIFYING) and no live lease");
            case UNPROJECTABLE -> require(bundle && !lease && verifying && reason,
                    "UNPROJECTABLE requires the bundle, verifyingSinceMs, a durable reason,"
                            + " and no live lease");
            case REJECTED -> require(reason && !lease && !verifying,
                    "REJECTED requires a durable reason, no lease, no verify marker"
                            + " (bundle present iff gate-rejected after a claim)");
            case UNRESOLVED -> require(!bundle && !lease && !verifying && reason,
                    "UNRESOLVED requires a durable reason and no claim fields");
            case DISCARDED -> require(!lease && !reason,
                    "DISCARDED carries no live lease and no reason (bundle optional; if"
                            + " present, complete)");
            case SKIPPED -> throw new IllegalArgumentException(
                    "SKIPPED is v1-only and never legal on a v2 row");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** True while this target holds a live claim (token + lease present). */
    public boolean hasLiveClaim() {
        return status == LineagePublishStatus.PROJECTING
                || status == LineagePublishStatus.VERIFYING;
    }
}
