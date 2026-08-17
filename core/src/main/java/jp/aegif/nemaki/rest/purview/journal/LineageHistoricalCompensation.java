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
 * A durable request to undo a historical entity that turned out to be wrong.
 *
 * <h2>The window this closes</h2>
 *
 * <pre>
 *   source re-checked → PURGED
 *   historical entity published
 *   source restored
 *   post-publish re-check → EXISTS
 * </pre>
 *
 * <p>Not resolving the obligation is not enough. The catalog now holds a tombstone for a live
 * object, and nothing in the obligation machine will ever go back for it — the obligation is
 * retried, the source is present, the retry releases, and the wrong entity stays there forever.
 *
 * <p>So a durable request is written instead. Durable, and in the same database as the
 * obligations: an in-memory queue would lose exactly this on the restart that a restore-heavy
 * incident is likely to involve.
 *
 * <h2>What it carries</h2>
 *
 * <p>Digests and identifiers, never values. It is stored, listed on admin routes and logged;
 * the qualified name of an external asset contains its stable key.
 *
 * @param taskId deterministic, so retrying one historical publish does not queue two
 *        compensations for it
 * @param publishedEvidenceDigest what the source said when the entity was written
 * @param observedEvidenceDigest what it said afterwards — the disagreement that caused this
 */
public record LineageHistoricalCompensation(
        String rev,
        String taskId,
        String target,
        String repositoryId,
        EndpointKind endpointKind,
        String subjectDigest,
        String operationDigest,
        String publishedEvidenceDigest,
        String observedEvidenceDigest,
        Reason reason,
        long createdAtMs,
        State state) {

    /** Frozen: it keys the deterministic task id. */
    public static final String IDENTITY_DOMAIN = "LINEAGE_HISTORICAL_COMPENSATION_V1";

    public static final String DOCUMENT_ID_PREFIX = "lineage_historical_compensation:";
    public static final String DOCUMENT_TYPE = "lineage_historical_compensation";

    public enum Reason {
        /** The source changed between the authorising check and the post-publish check. */
        SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH
    }

    public enum State {
        /** Not yet acted on. */
        PENDING,
        /** The current authoritative entity has been re-published over the historical one. */
        RESOLVED,
        /** Could not be acted on; kept so it is visible rather than lost. */
        FAILED
    }

    public LineageHistoricalCompensation {
        requireText(taskId, "taskId");
        requireText(target, "target");
        requireText(repositoryId, "repositoryId");
        requireText(subjectDigest, "subjectDigest");
        requireText(operationDigest, "operationDigest");
        if (endpointKind == null || reason == null || state == null) {
            throw new IllegalArgumentException(
                    "a compensation needs its kind, reason and state");
        }
    }

    /**
     * The deterministic id: one per (target, subject, publish operation).
     *
     * <p>Retrying the same historical publish reaches the same id, so the compensation is
     * created once however many times the retry runs.
     */
    public static String taskId(String target, String repositoryId, EndpointKind kind,
            String subjectDigest, String operationDigest) {
        return LineageCanonicalHash.hash(IDENTITY_DOMAIN, target, repositoryId,
                kind == null ? null : kind.name(), subjectDigest, operationDigest);
    }

    public String documentId() {
        return DOCUMENT_ID_PREFIX + taskId;
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
    }

    /** Digests only, and truncated at that. */
    @Override
    public String toString() {
        return "LineageHistoricalCompensation[" + taskId.substring(0, 12) + " " + state
                + " target=" + target + " kind=" + endpointKind + " reason=" + reason + "]";
    }
}
