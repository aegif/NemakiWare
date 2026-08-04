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
 * A durable record that a historical entity is <em>about to be</em> written.
 *
 * <h2>Why this exists before the write, not after it</h2>
 *
 * <p>A receipt written after publishing cannot survive the crash that happens during it. The
 * dangerous sequence is short:
 *
 * <pre>
 *   historical entity published to the catalog
 *   process dies
 *   the source is restored
 *   process restarts → the catalog holds the entity, the source exists, everything looks fine
 * </pre>
 *
 * <p>Nothing left behind says a tombstone was written, so nothing goes back for it. Writing the
 * intent first turns that into a state a scanner can find: the intent is {@code PLANNED}, the
 * catalog can be read back, and the machine converges either to {@code RESOLVED} or to a
 * compensation.
 *
 * <h2>Its own lease, not the obligation's token</h2>
 *
 * <p>The obligation claim authorises short work under a lease. An intent outlives it — it is
 * still there after the process that made it is gone. Reusing the claim token as long-lived
 * authorisation would mean a token that was fenced out could still advance the intent, so the
 * intent carries its own {@code _rev} CAS and its own owner/token.
 *
 * <h2>Identity</h2>
 *
 * <p>Bound to everything that decides what would be written, so replanning the same publish
 * reaches the same intent and a <em>different</em> plan cannot adopt it. Digests only: a raw
 * qualified name, an incarnation or a revision identifies and locates the object.
 */
public record LineageHistoricalPublishIntent(
        String rev,
        String intentId,
        String taskKey,
        String target,
        String repositoryId,
        EndpointKind endpointKind,
        String subjectDigest,
        String snapshotEvidenceDigest,
        String sourceEvidenceDigest,
        String plannedOperationDigest,
        int payloadSchemaVersion,
        State state,
        String owner,
        String token,
        long leaseUntilMs,
        int attempts,
        long createdAtMs,
        String reason) {

    /** Frozen: it keys the deterministic intent id. */
    public static final String IDENTITY_DOMAIN = "LINEAGE_HISTORICAL_INTENT_V1";

    public static final String DOCUMENT_ID_PREFIX = "lineage_historical_intent:";
    public static final String DOCUMENT_TYPE = "lineage_historical_intent";

    /**
     * Where the publish got to.
     *
     * <p>There is no failure state. A transient failure leaves the intent where it was, with
     * {@code attempts} incremented and a reason recorded — a state called FAILED would sooner
     * or later be read as terminal, and none of these failures are.
     */
    public enum State {
        /** Decided and recorded; the external write may or may not have happened yet. */
        PLANNED,
        /** The catalog holds it, confirmed by read-back. */
        PUBLISHED,
        /** The source was still purged afterwards; the obligation may resolve. */
        RESOLVED,
        /** The source came back. The written entity is wrong and must be replaced. */
        COMPENSATION_REQUIRED,
        /** The current authoritative entity has been re-published over it. */
        COMPENSATED;

        /** Whether a scanner should still be driving this one. */
        public boolean incomplete() {
            return this == PLANNED || this == PUBLISHED || this == COMPENSATION_REQUIRED;
        }
    }

    public LineageHistoricalPublishIntent {
        requireText(intentId, "intentId");
        requireText(taskKey, "taskKey");
        requireText(target, "target");
        requireText(repositoryId, "repositoryId");
        requireText(subjectDigest, "subjectDigest");
        requireText(snapshotEvidenceDigest, "snapshotEvidenceDigest");
        requireText(sourceEvidenceDigest, "sourceEvidenceDigest");
        requireText(plannedOperationDigest, "plannedOperationDigest");
        if (endpointKind == null || state == null) {
            throw new IllegalArgumentException("an intent needs its endpoint kind and state");
        }
        if (payloadSchemaVersion <= 0) {
            throw new IllegalArgumentException("payloadSchemaVersion must be positive");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    /**
     * The deterministic id: the same plan always reaches the same intent.
     *
     * <p>Includes both evidence digests, so a re-plan made from <em>different</em> material —
     * a newer snapshot, or a source verdict read after a restore — is a different intent and
     * cannot silently adopt the earlier one's state.
     */
    public static String intentId(String taskKey, String target, String repositoryId,
            EndpointKind kind, String subjectDigest, String snapshotEvidenceDigest,
            String sourceEvidenceDigest, String plannedOperationDigest,
            int payloadSchemaVersion) {
        return LineageCanonicalHash.hash(IDENTITY_DOMAIN, taskKey, target, repositoryId,
                kind == null ? null : kind.name(), subjectDigest, snapshotEvidenceDigest,
                sourceEvidenceDigest, plannedOperationDigest, (long) payloadSchemaVersion);
    }

    public String documentId() {
        return DOCUMENT_ID_PREFIX + intentId;
    }

    /** Whether an existing intent is the same plan as this one — every deciding field. */
    public boolean samePlanAs(LineageHistoricalPublishIntent other) {
        return other != null
                && intentId.equals(other.intentId)
                && taskKey.equals(other.taskKey)
                && target.equals(other.target)
                && repositoryId.equals(other.repositoryId)
                && endpointKind == other.endpointKind
                && subjectDigest.equals(other.subjectDigest)
                && snapshotEvidenceDigest.equals(other.snapshotEvidenceDigest)
                && sourceEvidenceDigest.equals(other.sourceEvidenceDigest)
                && plannedOperationDigest.equals(other.plannedOperationDigest)
                && payloadSchemaVersion == other.payloadSchemaVersion;
    }

    public boolean leaseExpired(long nowMs) {
        return token != null && leaseUntilMs <= nowMs;
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
    }

    /** Truncated digests only; no qualified name, no token, no revision. */
    @Override
    public String toString() {
        return "LineageHistoricalPublishIntent[" + intentId.substring(0, 12) + " " + state
                + " target=" + target + " kind=" + endpointKind + " attempts=" + attempts + "]";
    }
}
