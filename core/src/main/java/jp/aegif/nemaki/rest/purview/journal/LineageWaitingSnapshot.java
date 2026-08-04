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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import jp.aegif.nemaki.rest.purview.payload.CatalogSecretBoundary;

/**
 * The endpoint material a historical entity can be rebuilt from — validated, typed, immutable.
 *
 * <h2>Why not the raw map</h2>
 *
 * <p>The historical builder writes an entity that will stand in for an object nobody can look at
 * any more. Handing it {@code Map<String,Object>} straight off a document would let it publish
 * on material that was never checked to be about the right subject: a map has no opinion about
 * whether it describes the target, repository, kind and qualified name the obligation names.
 *
 * <h2>The evidence digest covers everything a verdict depends on</h2>
 *
 * <p>Including {@link LineageSourceDisposition}. The first version left it out, so two candidate
 * snapshots with identical attributes but opposite dispositions — one saying the source exists,
 * one saying it was purged — produced the <em>same</em> digest and compared equal. A resolver
 * comparing digests would call that agreement, and a tombstone could be built for a live object.
 * The domain tag is versioned ({@code …_V2}) rather than silently redefined, so an evidence
 * digest computed under the old formula can never be mistaken for one computed under this.
 *
 * <p>The digest exists to <b>bind a verdict to the material it was reached from</b>, not to be
 * shown. If two candidates disagree, the digests differ and the disagreement is visible without
 * printing either.
 *
 * <h2>The canonical constructor validates; the factory is not a promise</h2>
 *
 * <p>Every invariant below is checked in the canonical constructor, so no path — including
 * deserialization and a hand-written {@code new} — can produce a snapshot the builder would
 * trust wrongly. In particular the digest is <em>recomputed</em> and compared, so a forged one
 * is refused rather than believed.
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

    /**
     * The digest domain.
     *
     * <p>{@code V2} because {@code V1} omitted {@link #sourceDisposition}. Bumped rather than
     * redefined: a stored digest under the old formula must not silently validate under this
     * one, whether or not any such digest exists today.
     */
    static final String EVIDENCE_DOMAIN = "LINEAGE_WAITING_SNAPSHOT_V2";

    /** Snapshot schema versions this build can interpret. */
    static final int MIN_SNAPSHOT_SCHEMA_VERSION = 1;
    static final int MAX_SNAPSHOT_SCHEMA_VERSION = 2;

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
        if (snapshotSchemaVersion < MIN_SNAPSHOT_SCHEMA_VERSION
                || snapshotSchemaVersion > MAX_SNAPSHOT_SCHEMA_VERSION) {
            // A version outside the supported range is not "a snapshot missing a field" — this
            // build cannot say what its contents mean at all.
            throw new IllegalArgumentException("unsupported snapshot schema version "
                    + snapshotSchemaVersion);
        }
        attributes = validatedAttributes(endpointKind, attributes);
        String recomputed = digestOf(target, repositoryId, endpointKind, catalogQualifiedName,
                attributes, sourceDisposition, snapshotSchemaVersion);
        requireWellFormedDigest(evidenceDigest);
        if (!constantTimeEquals(recomputed, evidenceDigest)) {
            // Refused, not repaired: a snapshot whose digest does not describe it is either
            // forged or assembled by a path that did not go through this validation, and both
            // are reasons to refuse rather than to recompute and carry on.
            throw new IllegalArgumentException(
                    "the evidence digest does not describe this snapshot");
        }
    }

    /** Builds a snapshot and computes its evidence digest from the same material. */
    public static LineageWaitingSnapshot of(String target, String repositoryId,
            EndpointKind endpointKind, String catalogQualifiedName,
            Map<String, Object> attributes, LineageSourceDisposition sourceDisposition,
            int snapshotSchemaVersion) {
        Map<String, Object> validated = validatedAttributes(endpointKind, attributes);
        String digest = digestOf(target, repositoryId, endpointKind, catalogQualifiedName,
                validated, sourceDisposition, snapshotSchemaVersion);
        return new LineageWaitingSnapshot(target, repositoryId, endpointKind,
                catalogQualifiedName, validated, sourceDisposition, snapshotSchemaVersion,
                digest);
    }

    /**
     * The one formula. Domain-tagged, and covering every field a verdict can depend on.
     *
     * <p>Attributes go in as a sorted map, so the digest is a function of the content and not
     * of the order a document happened to serialise it in.
     */
    private static String digestOf(String target, String repositoryId, EndpointKind kind,
            String catalogQualifiedName, Map<String, Object> attributes,
            LineageSourceDisposition disposition, int snapshotSchemaVersion) {
        if (kind == null || disposition == null) {
            throw new IllegalArgumentException("kind and disposition are part of the digest");
        }
        return LineageCanonicalHash.hash(EVIDENCE_DOMAIN, target, repositoryId, kind.name(),
                catalogQualifiedName, (long) snapshotSchemaVersion, disposition.name(),
                new TreeMap<>(attributes));
    }

    /**
     * Attributes as a snapshot may hold them: allowlisted, scalar, and secret-free.
     *
     * <p>{@code Map.copyOf} alone is a shallow copy — a {@code List} or {@code Map} value would
     * still be mutable through the caller's reference, so an "immutable" snapshot could change
     * under a digest that no longer described it. Rejecting non-scalars removes the problem
     * instead of deep-copying it, and §2 already forbids them in endpoint attributes for the
     * same reason.
     */
    private static Map<String, Object> validatedAttributes(EndpointKind kind,
            Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a snapshot attribute has no name");
            }
            if (kind != null && !kind.isAllowedAttribute(name)) {
                // An attribute outside the kind's allowlist would be dropped by the catalog
                // anyway; carrying it here would put it in the digest and make two snapshots
                // differ over something that can never travel.
                throw new IllegalArgumentException(
                        "'" + name + "' is not an attribute of " + kind);
            }
            Object value = entry.getValue();
            if (value != null && !isPermittedScalar(value)) {
                throw new IllegalArgumentException("snapshot attribute '" + name
                        + "' is not a scalar — lists, maps and arrays cannot be held immutably"
                        + " and are already forbidden in endpoint attributes");
            }
            validated.put(name, value);
        }
        // The same gate every entity payload passes: a snapshot is published material too, and
        // a value that may not reach the catalog may not be carried here waiting to.
        CatalogSecretBoundary.sealed(validated);
        return Map.copyOf(validated);
    }

    private static boolean isPermittedScalar(Object value) {
        return value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Short;
    }

    private static void requireWellFormedDigest(String digest) {
        if (digest == null || digest.length() != 64) {
            throw new IllegalArgumentException("an evidence digest is 64 lowercase hex digits");
        }
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException(
                        "an evidence digest is 64 lowercase hex digits");
            }
        }
    }

    /**
     * Length-independent comparison.
     *
     * <p>The digest authorises a historical publish, so it is compared the way a credential is:
     * without an early exit that would leak how much of a guess was right.
     */
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
    public boolean hasAll(Collection<String> mandatory) {
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
