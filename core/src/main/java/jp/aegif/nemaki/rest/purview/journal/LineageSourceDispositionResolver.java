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

import java.util.Objects;

/**
 * Asks the <em>repository</em> what became of a source — never the catalog.
 *
 * <h2>Why this is a separate question</h2>
 *
 * <p>A waiting snapshot says what someone observed at some point. The catalog says whether it
 * holds an entity. Neither answers "is this object gone <em>now</em>", and a historical entity
 * is a permanent statement that it is. Two independent things have to agree before one is
 * written: the snapshot the material comes from, and the repository itself.
 *
 * <p>Catalog results are never reused here. {@code ABSENT} means the catalog has not got it,
 * which is the ordinary state of an entity the authoritative publisher has not reached yet.
 *
 * <h2>What counts as PURGED</h2>
 *
 * <p>Only positive evidence: an authoritative tombstone, an archive record marked purged, a
 * purge marker. A 404 from a source API counts only where that API guarantees 404 means purged
 * rather than "not visible to you" — permission errors, timeouts, 5xx, an unknown repository and
 * decode failures are all {@link LineageSourceDisposition#SOURCE_UNKNOWN}.
 */
public interface LineageSourceDispositionResolver {

    /**
     * What the repository says, bound to the subject it says it about.
     *
     * <h2>Subject-bound, because a verdict is not transferable</h2>
     *
     * <p>The first version carried a disposition, an incarnation and a revision, and nothing
     * that said which object they were about. Evidence gathered for one document would then
     * authorise a historical entity for another — the caller was trusted to keep them together,
     * and a caller that mixed them up would produce a tombstone for a live object with every
     * field looking correct.
     *
     * <h2>Self-verifying, because it authorises a permanent write</h2>
     *
     * <p>{@link #evidenceDigest} is recomputed in the canonical constructor and compared in
     * constant time, exactly as {@code LineageWaitingSnapshot} does. Anything that can license
     * a tombstone is checked to the same standard as the material the tombstone is built from.
     *
     * @param subjectDigest the endpoint this verdict is about, as a digest — the qualified name
     *        of an external asset contains its stable key and does not belong in a record that
     *        is logged and stored
     * @param incarnation which instantiation of the object this is about; a restore makes a new
     *        one, so evidence from before a restore stops matching after it
     * @param revision the object or tombstone revision the verdict was read from
     * @param markerDigest the resolver's own tombstone/purge marker, digested; {@code null}
     *        where a resolver has none to offer
     * @param checkedAtMs when. <b>Deliberately not in the digest</b>: re-verification has to be
     *        able to produce evidence that matches, and it necessarily happens later.
     */
    record SourceEvidence(String repositoryId, EndpointKind endpointKind, String subjectDigest,
            LineageSourceDisposition disposition, String incarnation, String revision,
            String markerDigest, long checkedAtMs, String evidenceDigest) {

        /** The evidence's domain tag; frozen because it keys stored comparisons. */
        public static final String EVIDENCE_DOMAIN = "LINEAGE_SOURCE_EVIDENCE_V1";

        /** The subject digest's domain, kept apart from the evidence's. */
        public static final String SUBJECT_DOMAIN = "LINEAGE_SOURCE_SUBJECT_V1";

        public SourceEvidence {
            if (disposition == null) {
                throw new IllegalArgumentException("a source verdict needs a disposition");
            }
            if (disposition != LineageSourceDisposition.SOURCE_UNKNOWN) {
                // UNKNOWN may legitimately have no subject — the resolver may not have got far
                // enough to identify one. Anything that can be acted on must name its subject.
                requireText(repositoryId, "repositoryId");
                requireText(subjectDigest, "subjectDigest");
                if (endpointKind == null) {
                    throw new IllegalArgumentException(
                            "a source verdict that can be acted on needs its endpoint kind");
                }
            }
            if (disposition == LineageSourceDisposition.SOURCE_PURGED) {
                // A purge verdict with nothing to point at cannot be re-verified later, and
                // re-verification is what closes the restore-during-publish window.
                requireText(incarnation, "incarnation");
                requireText(revision, "revision");
            }
            // UNKNOWN with no digest is the one shape that binds nothing: it is allowed, and
            // authorisesHistorical() refuses it. Everything else must prove it describes itself.
            boolean unboundUnknown = disposition == LineageSourceDisposition.SOURCE_UNKNOWN
                    && evidenceDigest == null;
            if (!unboundUnknown) {
                requireWellFormedDigest(evidenceDigest);
                String recomputed = digestOf(repositoryId, endpointKind, subjectDigest,
                        disposition, incarnation, revision, markerDigest);
                if (!constantTimeEquals(recomputed, evidenceDigest)) {
                    throw new IllegalArgumentException(
                            "the evidence digest does not describe this verdict");
                }
            }
        }

        /** The subject an endpoint names, as a digest. */
        public static String subjectDigest(String repositoryId, EndpointKind kind,
                String catalogQualifiedName) {
            return LineageCanonicalHash.hash(SUBJECT_DOMAIN, repositoryId,
                    kind == null ? null : kind.name(), catalogQualifiedName);
        }

        /** Builds a verdict and its digest from the same material. */
        public static SourceEvidence of(String repositoryId, EndpointKind kind,
                String catalogQualifiedName, LineageSourceDisposition disposition,
                String incarnation, String revision, String markerDigest, long checkedAtMs) {
            String subject = subjectDigest(repositoryId, kind, catalogQualifiedName);
            String digest = digestOf(repositoryId, kind, subject, disposition, incarnation,
                    revision, markerDigest);
            return new SourceEvidence(repositoryId, kind, subject, disposition, incarnation,
                    revision, markerDigest, checkedAtMs, digest);
        }

        /** Unknown, with nothing to point at. The answer for every failure. */
        public static SourceEvidence unknown(long checkedAtMs) {
            return new SourceEvidence(null, null, null, LineageSourceDisposition.SOURCE_UNKNOWN,
                    null, null, null, checkedAtMs, null);
        }

        private static String digestOf(String repositoryId, EndpointKind kind,
                String subjectDigest, LineageSourceDisposition disposition, String incarnation,
                String revision, String markerDigest) {
            // checkedAtMs is NOT here: a re-check happens later by definition, and evidence
            // that could never match its own re-reading would make the TOCTOU guard useless.
            return LineageCanonicalHash.hash(EVIDENCE_DOMAIN, repositoryId,
                    kind == null ? null : kind.name(), subjectDigest, disposition.name(),
                    incarnation, revision, markerDigest);
        }

        /** Whether this verdict is about the endpoint an obligation names. */
        public boolean describesSubject(String repositoryId, EndpointKind kind,
                String catalogQualifiedName) {
            return subjectDigest != null
                    && subjectDigest.equals(subjectDigest(repositoryId, kind,
                            catalogQualifiedName))
                    && java.util.Objects.equals(this.repositoryId, repositoryId)
                    && this.endpointKind == kind;
        }

        /**
         * Whether a later reading is the same fact as this one.
         *
         * <p>Compares everything that identifies the fact, including the digest. Deliberately
         * <em>not</em> {@code checkedAtMs}: the whole point is to compare two readings taken at
         * different times, so requiring the times to match would make it always false.
         */
        public boolean stillMatches(SourceEvidence later) {
            return later != null
                    && later.disposition == disposition
                    && Objects.equals(later.repositoryId, repositoryId)
                    && later.endpointKind == endpointKind
                    && Objects.equals(later.subjectDigest, subjectDigest)
                    && Objects.equals(later.incarnation, incarnation)
                    && Objects.equals(later.revision, revision)
                    && Objects.equals(later.evidenceDigest, evidenceDigest);
        }

        /** Whether this verdict can license a historical entity. */
        public boolean authorisesHistorical() {
            return disposition == LineageSourceDisposition.SOURCE_PURGED
                    && subjectDigest != null && evidenceDigest != null;
        }

        private static void requireText(String value, String what) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(what + " must not be blank");
            }
        }

        private static void requireWellFormedDigest(String digest) {
            if (digest == null || digest.length() != 64) {
                throw new IllegalArgumentException(
                        "an evidence digest is 64 lowercase hex digits");
            }
            for (int i = 0; i < digest.length(); i++) {
                char c = digest.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                    throw new IllegalArgumentException(
                            "an evidence digest is 64 lowercase hex digits");
                }
            }
        }

        private static boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null || a.length() != b.length()) {
                return false;
            }
            int difference = 0;
            for (int i = 0; i < a.length(); i++) {
                difference |= a.charAt(i) ^ b.charAt(i);
            }
            return difference == 0;
        }

        /** No revision, no incarnation, no subject: those identify and locate the object. */
        @Override
        public String toString() {
            return "SourceEvidence[" + disposition + " kind=" + endpointKind
                    + " checkedAt=" + checkedAtMs
                    + (evidenceDigest == null ? ""
                            : " evidence=" + evidenceDigest.substring(0, 12)) + "]";
        }
    }

    /**
     * @return never {@code null}; every failure is {@code SOURCE_UNKNOWN}
     */
    SourceEvidence dispositionOf(String repositoryId, EndpointKind kind,
            String catalogQualifiedName);

    /**
     * A verdict and, when the subject is live and projectable, the catalog entity from the
     * <em>same</em> read.
     *
     * <h2>Why both have to come from one read</h2>
     *
     * <p>The live-source route publishes an entity and records that it did. Building that entity
     * from the event's observation while authorising the write with a freshly-read verdict binds
     * nothing: the event may have observed revision R1 while the verdict describes R2, and
     * {@code LineageWaitingSnapshot} carries no revision to compare against. The write then
     * succeeds, reads back, and resolves the obligation — leaving content in the catalog that
     * describes an instance the verdict was never about, on a route that exists precisely because
     * the authoritative publisher may never come along to correct it.
     *
     * <p>Taking the attributes from the same object the verdict was made from does not make the
     * entity permanently current — nothing here can promise that. It makes the one assertion the
     * machine actually gets to make a true one: this is what the repository held at the moment
     * this execution read it.
     *
     * <h2>Why a default that projects nothing</h2>
     *
     * <p>A resolver for a source NemakiWare does not own cannot project one — the external system
     * owns the attributes, and inventing them here would publish content this node never observed.
     * Those resolvers keep answering the disposition question and decline the projection, and the
     * caller is required to treat a missing projection as "do not write", never as "write the
     * event's copy instead".
     *
     * @param projection null when this resolver cannot build one; never a guess
     */
    record LiveSourceObservation(SourceEvidence evidence,
            java.util.Map<String, Object> projection) {

        public LiveSourceObservation {
            if (evidence == null) {
                throw new IllegalArgumentException("a live observation needs its verdict");
            }
            // A projection without a positive verdict is content nobody established was there.
            if (projection != null
                    && evidence.disposition() != LineageSourceDisposition.SOURCE_EXISTS) {
                throw new IllegalArgumentException(
                        "only a live source may carry a catalog projection");
            }
            projection = projection == null ? null : java.util.Map.copyOf(projection);
        }

        /** Whether this observation can license a current-entity write. */
        public boolean publishable() {
            return projection != null && !projection.isEmpty();
        }
    }

    /**
     * The disposition alone, for resolvers that cannot project. Overridden by those that can, so
     * the projection costs no extra read and no extra budgeted operation.
     */
    default LiveSourceObservation observeLive(String repositoryId, EndpointKind kind,
            String catalogQualifiedName) {
        return new LiveSourceObservation(
                dispositionOf(repositoryId, kind, catalogQualifiedName), null);
    }
}
