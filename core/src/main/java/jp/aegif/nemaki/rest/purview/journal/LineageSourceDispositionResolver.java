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
     * What the repository says, and the evidence it said it from.
     *
     * @param incarnation which instantiation of the object this is about — a restore makes a
     *        new one, so evidence gathered before a restore stops matching after it
     * @param revision the object or tombstone revision the verdict was read from
     * @param checkedAtMs when, so a caller can tell a fresh answer from a remembered one
     * @param evidenceDigest binds the verdict to what was read, without carrying it
     */
    record SourceEvidence(LineageSourceDisposition disposition, String incarnation,
            String revision, long checkedAtMs, String evidenceDigest) {

        /** The evidence's domain tag; frozen because it keys stored comparisons. */
        public static final String EVIDENCE_DOMAIN = "LINEAGE_SOURCE_EVIDENCE_V1";

        public SourceEvidence {
            if (disposition == null) {
                throw new IllegalArgumentException("a source verdict needs a disposition");
            }
            if (disposition == LineageSourceDisposition.SOURCE_PURGED
                    && (isBlank(incarnation) || isBlank(revision))) {
                // A purge verdict with nothing to point at cannot be re-verified later, and
                // re-verification is what closes the restore-during-publish window.
                throw new IllegalArgumentException(
                        "a PURGED verdict must carry the incarnation and revision it was read"
                                + " from, or it cannot be re-checked before publishing");
            }
        }

        /** Unknown, with nothing to point at. The answer for every failure. */
        public static SourceEvidence unknown(long checkedAtMs) {
            return new SourceEvidence(LineageSourceDisposition.SOURCE_UNKNOWN, null, null,
                    checkedAtMs, null);
        }

        /**
         * Whether a later reading is the same fact as this one.
         *
         * <p>A changed incarnation means the object was restored — the evidence is about a
         * previous life of it and may not authorise anything about this one.
         */
        public boolean stillMatches(SourceEvidence later) {
            return later != null
                    && later.disposition == disposition
                    && java.util.Objects.equals(later.incarnation, incarnation)
                    && java.util.Objects.equals(later.revision, revision);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        /** No revision, no incarnation, no path: those identify and locate the object. */
        @Override
        public String toString() {
            return "SourceEvidence[" + disposition + " checkedAt=" + checkedAtMs
                    + (evidenceDigest == null ? ""
                            : " evidence=" + evidenceDigest.substring(0,
                                    Math.min(12, evidenceDigest.length()))) + "]";
        }
    }

    /**
     * @return never {@code null}; every failure is {@code SOURCE_UNKNOWN}
     */
    SourceEvidence dispositionOf(String repositoryId, EndpointKind kind,
            String catalogQualifiedName);
}
