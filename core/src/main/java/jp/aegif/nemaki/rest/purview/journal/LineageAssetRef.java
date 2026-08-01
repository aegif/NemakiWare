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

import java.util.Map;

/**
 * One end of a lineage relation, as much as is known about it.
 *
 * <h2>Why not a nullable {@link LineageEndpoint}</h2>
 *
 * <p>A v1 event carries bare qualified-name strings and no kind: {@code objects/{id}} is written
 * for both a document and a folder, so the kind is genuinely not recoverable from the string. The
 * obvious move — put a {@code LineageEndpoint} on the reference and leave it null for v1 — brings
 * back the invalid state A-1 removed. Every consumer would carry a null check, and worse, null
 * would mean two different things: "this came from v1 and has no kind" and "resolution ran and
 * failed". Those need different handling, and a null cannot tell them apart.
 *
 * <p>So the three cases are three types.
 *
 * <ul>
 *   <li>{@link Typed} — a validated endpoint. Every v2 asset, and a v1 asset the legacy reader has
 *       classified.</li>
 *   <li>{@link LegacyName} — a v1 qualified name, not yet classified. The name is usable; the kind
 *       is not known and must not be guessed.</li>
 *   <li>{@link Unresolved} — classification was attempted and did not succeed. Carries why, so
 *       §6's durable-unresolved path has something to record.</li>
 * </ul>
 *
 * <h2>The qualified name does not go in a log</h2>
 *
 * <p>An external asset's qualified name is reversible base64 of its stable key (§4), so
 * {@code toString} renders it through {@link LineageEndpoint#describeQualifiedName}, which
 * redacts. {@link #qualifiedName()} still returns the real value — the catalog sinks need it —
 * but nothing prints a reference by accident.
 */
public sealed interface LineageAssetRef
        permits LineageAssetRef.Typed, LineageAssetRef.LegacyName, LineageAssetRef.Unresolved {

    /** The catalog qualified name. Always known: it is the one thing every version carries. */
    String qualifiedName();

    /** Snapshot attributes for this asset. Empty unless the kind is known. */
    Map<String, Object> attributes();

    /** A validated, kind-bearing endpoint. */
    record Typed(LineageEndpoint endpoint) implements LineageAssetRef {

        public Typed {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint must not be null — use LegacyName"
                        + " when the qualified name is known but the kind is not");
            }
        }

        public EndpointKind kind() {
            return endpoint.kind();
        }

        @Override
        public String qualifiedName() {
            return endpoint.catalogQualifiedName();
        }

        @Override
        public Map<String, Object> attributes() {
            return endpoint.attributes();
        }

        @Override
        public String toString() {
            return "Typed[" + endpoint.describeQualifiedName() + "]";
        }
    }

    /**
     * A v1 qualified name whose kind has not been established.
     *
     * <p>Deliberately without a {@code kind()}. Deriving one from the string would be a second
     * naming contract next to A-1's, and it would be wrong: v1 writes {@code objects/{id}} for
     * documents and folders alike.
     */
    record LegacyName(String qualifiedName) implements LineageAssetRef {

        public LegacyName {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                throw new IllegalArgumentException("qualifiedName must not be null or blank");
            }
        }

        @Override
        public Map<String, Object> attributes() {
            return Map.of();
        }

        @Override
        public String toString() {
            return "LegacyName[" + LineageEndpoint.shortDigest(qualifiedName) + "]";
        }
    }

    /**
     * Classification ran and did not produce an endpoint.
     *
     * @param reason why, in terms an operator can act on. Not the qualified name.
     */
    record Unresolved(String qualifiedName, String reason) implements LineageAssetRef {

        public Unresolved {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                throw new IllegalArgumentException("qualifiedName must not be null or blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be null or blank — an"
                        + " unresolved asset with no reason cannot be acted on");
            }
        }

        @Override
        public Map<String, Object> attributes() {
            return Map.of();
        }

        @Override
        public String toString() {
            return "Unresolved[" + LineageEndpoint.shortDigest(qualifiedName)
                    + ", reason=" + reason + "]";
        }
    }
}
