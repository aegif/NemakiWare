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
 * One catalog entity a projection is waiting for (§2).
 *
 * <h2>Why this exists before the projector needs it</h2>
 *
 * <p>4b is a flag flip, not a deployment. Everything activation will need — the producer that
 * creates obligations, the consumer that resolves them, and the recovery that reclaims expired
 * claims — has to be distributed and verified <em>while inactive</em>. A design that builds this
 * after turning D-rest on is a design that discovers what is missing with v2 writes already open.
 *
 * <h2>Identity</h2>
 *
 * <p>{@link #taskKey} is a canonical hash of the four things that make one obligation: which
 * target is waiting, which repository, which kind of endpoint, and which catalog name. It does
 * not depend on when the obligation was created or on the order the parts were supplied — a
 * restart, a replay and a duplicate delivery all converge on the same document rather than
 * creating three that wait for the same entity.
 *
 * <p>Domain-tagged, so it cannot collide with any other identity hash in the system. See
 * {@code LineageDigests} for why the tag matters.
 */
public record LineageCatalogObligation(
        String rev,
        String taskKey,
        String target,
        String repositoryId,
        EndpointKind endpointKind,
        String catalogQualifiedName,
        State state,
        String owner,
        String token,
        long leaseUntilMs,
        int attempts,
        long createdAtMs,
        Outcome outcome,
        String reason,
        String evidence) {

    /** The hash domain. Frozen: changing it re-keys every obligation ever created. */
    public static final String IDENTITY_DOMAIN = "LINEAGE_CATALOG_OBLIGATION_V1";

    /** The CouchDB document id prefix, and the type marker inside it. */
    public static final String DOCUMENT_ID_PREFIX = "lineage_catalog_obligation:";

    /** The {@code type} field every obligation document carries. */
    public static final String DOCUMENT_TYPE = "lineage_catalog_obligation";

    public enum State {
        /** Nobody is working on it. The only state a claim may start from. */
        PENDING,
        /** A worker holds it under {@code owner}/{@code token} until {@code leaseUntilMs}. */
        CLAIMED,
        /** The catalog entity is there. Waiting events may resume. */
        RESOLVED,
        /**
         * Terminal, and only for a reason that cannot improve by waiting.
         *
         * <p>A catalog that was unreachable is NOT this — that is retryable, and burning it as
         * terminal would turn a five-minute outage into a permanently unprojectable event.
         */
        UNRESOLVED
    }

    /** Why an obligation ended, or why it will be tried again. */
    public enum Outcome {
        /** Not finished. */
        NONE,
        /** The entity is in the catalog; the authoritative publisher has it. */
        SOURCE_EXISTS,
        /** The source is gone; a historical entity was built from the endpoint snapshot. */
        SOURCE_PURGED,
        /** The catalog did not answer. <b>Retryable</b>, with capped backoff. */
        SOURCE_ERROR,
        /** The snapshot cannot reconstruct the entity. The only terminal failure. */
        SNAPSHOT_INCOMPLETE
    }

    public LineageCatalogObligation {
        requireText(taskKey, "taskKey");
        requireText(target, "target");
        requireText(repositoryId, "repositoryId");
        requireText(catalogQualifiedName, "catalogQualifiedName");
        if (endpointKind == null) {
            throw new IllegalArgumentException("endpointKind must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (outcome == null) {
            outcome = Outcome.NONE;
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        // A claim without an owner or a token is a claim nobody can be fenced against.
        if (state == State.CLAIMED && (isBlank(owner) || isBlank(token))) {
            throw new IllegalArgumentException(
                    "a CLAIMED obligation must carry both an owner and a token");
        }
        // §2: RESOLVED and UNRESOLVED are bound to a reason and its evidence. An UNRESOLVED
        // with no reason is a verdict nobody can review, and the event it terminates is gone.
        if ((state == State.RESOLVED || state == State.UNRESOLVED)
                && (outcome == Outcome.NONE || isBlank(reason))) {
            throw new IllegalArgumentException(
                    "a " + state + " obligation must carry an outcome and a reason");
        }
        if (state == State.UNRESOLVED && outcome == Outcome.SOURCE_ERROR) {
            throw new IllegalArgumentException(
                    "SOURCE_ERROR is retryable and must never be recorded as UNRESOLVED —"
                            + " a transient catalog failure would become permanent");
        }
    }

    /**
     * The obligation's identity: the same four facts always produce the same key.
     *
     * <p>Order-independent because the parts are named positions in a typed encoding rather
     * than a concatenation, and time-independent because no clock is an input.
     */
    public static String taskKey(String target, String repositoryId, EndpointKind endpointKind,
            String catalogQualifiedName) {
        requireText(target, "target");
        requireText(repositoryId, "repositoryId");
        requireText(catalogQualifiedName, "catalogQualifiedName");
        if (endpointKind == null) {
            throw new IllegalArgumentException("endpointKind must not be null");
        }
        return LineageCanonicalHash.hash(IDENTITY_DOMAIN, target, repositoryId,
                endpointKind.name(), catalogQualifiedName);
    }

    /** The document id this obligation lives under. */
    public String documentId() {
        return DOCUMENT_ID_PREFIX + taskKey;
    }

    /** Whether a lease has run out, so the obligation may be reclaimed. */
    public boolean leaseExpired(long nowMs) {
        return state == State.CLAIMED && leaseUntilMs <= nowMs;
    }

    /** Terminal states do not change again except by administrative action. */
    public boolean terminal() {
        return state == State.RESOLVED || state == State.UNRESOLVED;
    }

    /**
     * Whether {@code other} describes the same obligation as this one.
     *
     * <p>Used by create-if-absent: finding a document already there is only success if it means
     * the same thing. A key collision with different content is a bug or a tampered document,
     * and treating it as "already done" would silently wait for the wrong entity.
     */
    public boolean sameSubjectAs(LineageCatalogObligation other) {
        return other != null
                && taskKey.equals(other.taskKey)
                && target.equals(other.target)
                && repositoryId.equals(other.repositoryId)
                && endpointKind == other.endpointKind
                && catalogQualifiedName.equals(other.catalogQualifiedName);
    }

    private static void requireText(String value, String what) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * A description safe to log.
     *
     * <p>The catalog qualified name is redacted: an external asset's name contains its stable
     * key, which is operator-configured infrastructure and not something to scatter through
     * logs and dead letters. The task key is already a hash and identifies the obligation.
     */
    @Override
    public String toString() {
        return "LineageCatalogObligation[" + taskKey.substring(0, 12) + " " + state
                + " kind=" + endpointKind + " qn=<redacted:"
                + LineageEndpoint.shortDigest(catalogQualifiedName) + ">"
                + (outcome == Outcome.NONE ? "" : " outcome=" + outcome) + "]";
    }
}
