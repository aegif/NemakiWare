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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The endpoint material a historical entity can be rebuilt from — validated, typed, immutable.
 *
 * <h2>Why not the raw map</h2>
 *
 * <p>The historical builder writes an entity that will stand in for an object nobody can look at
 * any more. Handing it {@code Map<String,Object>} straight off a document would let it publish
 * on material that was never checked to be about the right subject: a map has no opinion about
 * whether it describes the target, repository, kind and qualified name the obligation names.
 * Every one of those mismatches is corruption, and corruption must not look like a snapshot that
 * merely lacks a field.
 *
 * <h2>The evidence digest</h2>
 *
 * <p>{@link #evidenceDigest()} exists to <em>bind a verdict to the material it was reached
 * from</em>, not to be shown. If two candidate snapshots for one task disagree, the digests
 * differ and the disagreement is visible without printing either. It is a plain SHA-256 over the
 * canonical encoding of the subject and the allowlisted attributes — the evidence kind in
 * {@code LineageDigests}, so it is comparable but carries nothing readable.
 *
 * <h2>Nothing here is printable</h2>
 *
 * <p>{@link #toString()} carries the subject as digests. The qualified name of an external asset
 * contains its stable key, and attribute values are the object's own content.
 */
public record LineageWaitingSnapshot(
        String target,
        String repositoryId,
        EndpointKind endpointKind,
        String catalogQualifiedName,
        Map<String, Object> attributes,
        LineageSourceDisposition sourceDisposition,
        int snapshotSchemaVersion,
        String evidenceDigest) {

    /** The domain tag for the evidence digest. Frozen: it keys stored comparisons. */
    static final String EVIDENCE_DOMAIN = "LINEAGE_WAITING_SNAPSHOT_V1";

    public LineageWaitingSnapshot {
        require(target, "target");
        require(repositoryId, "repositoryId");
        require(catalogQualifiedName, "catalogQualifiedName");
        if (endpointKind == null) {
            throw new IllegalArgumentException("endpointKind must not be null");
        }
        if (sourceDisposition == null) {
            throw new IllegalArgumentException("sourceDisposition must not be null");
        }
        if (snapshotSchemaVersion <= 0) {
            throw new IllegalArgumentException("snapshotSchemaVersion must be positive");
        }
        // Immutable: the builder must not be able to add a field on its way to publishing and
        // then have the evidence digest describe something else.
        attributes = attributes == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    /**
     * Builds a snapshot and computes its evidence digest from the same material.
     *
     * <p>The only constructor callers should use: computing the digest elsewhere would let the
     * two drift, and a digest that does not describe the attributes is worse than none.
     */
    public static LineageWaitingSnapshot of(String target, String repositoryId,
            EndpointKind endpointKind, String catalogQualifiedName,
            Map<String, Object> attributes, LineageSourceDisposition sourceDisposition,
            int snapshotSchemaVersion) {
        Map<String, Object> copy = attributes == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(attributes));
        String digest = LineageCanonicalHash.hash(EVIDENCE_DOMAIN, target, repositoryId,
                endpointKind.name(), catalogQualifiedName, snapshotSchemaVersion,
                new java.util.TreeMap<>(copy));
        return new LineageWaitingSnapshot(target, repositoryId, endpointKind,
                catalogQualifiedName, copy, sourceDisposition, snapshotSchemaVersion, digest);
    }

    /**
     * Whether this snapshot is about the obligation's subject — all four parts, exactly.
     *
     * <p>A near miss is not a weaker match: an entity rebuilt from a different repository's
     * snapshot would be wrong in a way nothing downstream could detect.
     */
    public boolean describesSubject(LineageCatalogObligation obligation) {
        return obligation != null
                && target.equals(obligation.target())
                && repositoryId.equals(obligation.repositoryId())
                && endpointKind == obligation.endpointKind()
                && catalogQualifiedName.equals(obligation.catalogQualifiedName());
    }

    /** Whether every named attribute is present and non-blank. Structural, not semantic. */
    public boolean hasAll(java.util.Collection<String> mandatory) {
        if (mandatory == null) {
            return true;
        }
        for (String name : mandatory) {
            Object value = attributes.get(name);
            if (value == null || (value instanceof String s && s.isBlank())) {
                return false;
            }
        }
        return true;
    }

    private static void require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
    }

    /** Subject as digests; no qualified name, no attribute value. */
    @Override
    public String toString() {
        return "LineageWaitingSnapshot[" + endpointKind + " target=" + target
                + " repo=" + repositoryId
                + " qn=<redacted:" + LineageEndpoint.shortDigest(catalogQualifiedName) + ">"
                + " attrs=" + attributes.size()
                + " disposition=" + sourceDisposition
                + " evidence=" + evidenceDigest.substring(0, 12) + "]";
    }
}
