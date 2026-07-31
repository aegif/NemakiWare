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
 * Cross-repository lineage is not allowed, and this is where that is enforced.
 *
 * <h2>Four layers, not one</h2>
 *
 * <p>Checking in the builder alone would only cover events the builder made. An event injected
 * straight into CouchDB, or mapped from a v1 record, never passes through it — so the store, the
 * legacy reader and the sink each call this too, and the call immediately before publish is the
 * last gate that can still stop a tenant boundary violation from reaching the catalog.
 *
 * <h2>Two checks per endpoint</h2>
 *
 * <p>The field and the name are checked separately. {@code repositoryId} could be set correctly
 * while the qualified name points somewhere else — that is exactly the shape a hand-crafted
 * document would take — so the canonical name has to agree as well.
 *
 * <p>The name check is not a prefix match. Every kind's name is a pure function of the repository
 * plus one identity value the endpoint carries, so it is recomputed and compared exactly. A
 * prefix match would accept {@code nemaki://bedroom/archives/a} on an endpoint declaring itself a
 * document, and the sink would then create a {@code nemaki_document} at an archive's name. For
 * the external kinds it would accept a name built from one stable key while the attribute held
 * another — and that is worse than it sounds, because the catalog resolves the entity by name
 * while a snapshot reader resolves it by attribute, so the two would silently describe different
 * things.
 *
 * <h2>What goes in the message</h2>
 *
 * <p>An external qualified name is reversible base64 of the stable key, so it is not repeated in
 * an exception. The design forbids credentials and signed URLs in a stable key and
 * {@link LineageEndpoint#canonicalStableKey} enforces it, but an exception message travelling to
 * a log file is exactly where that defence being wrong would hurt most.
 */
public final class LineageRepositoryScope {

    private LineageRepositoryScope() {
    }

    /** @throws IllegalArgumentException if any endpoint belongs to another repository. */
    public static void validate(String eventRepositoryId, java.util.List<LineageEndpoint> inputs,
                                java.util.List<LineageEndpoint> outputs) {
        requireList(inputs, "inputs");
        requireList(outputs, "outputs");
        for (LineageEndpoint endpoint : inputs) {
            validateEndpoint(eventRepositoryId, endpoint, "input");
        }
        for (LineageEndpoint endpoint : outputs) {
            validateEndpoint(eventRepositoryId, endpoint, "output");
        }
    }

    public static void validateEndpoint(String eventRepositoryId, LineageEndpoint endpoint,
                                        String side) {
        if (endpoint == null) {
            throw new IllegalArgumentException(side + " endpoint must not be null");
        }
        if (eventRepositoryId == null || eventRepositoryId.isBlank()) {
            throw new IllegalArgumentException("event repositoryId must not be blank");
        }
        if (!eventRepositoryId.equals(endpoint.repositoryId())) {
            throw new IllegalArgumentException(
                    "cross-repository lineage is not permitted: event repository '"
                            + eventRepositoryId + "' but " + side + " endpoint belongs to '"
                            + endpoint.repositoryId() + "'");
        }
        String expected = LineageEndpoint.expectedQualifiedName(endpoint, eventRepositoryId);
        if (!expected.equals(endpoint.catalogQualifiedName())) {
            throw new IllegalArgumentException(side + " endpoint qualified name does not match its"
                    + " own kind and identity (kind=" + endpoint.kind() + "): expected "
                    + describe(expected) + ", got " + describe(endpoint.catalogQualifiedName()));
        }
    }

    /**
     * A qualified name safe to put in a message.
     *
     * <p>External names decode back to the stable key, so they are reduced to their repository
     * scope and a digest — enough to tell two names apart in a log without reproducing either.
     */
    private static String describe(String qualifiedName) {
        int externalMarker = qualifiedName.indexOf("/external-assets/");
        if (externalMarker < 0) {
            return "'" + qualifiedName + "'";
        }
        return "'" + qualifiedName.substring(0, externalMarker + "/external-assets/".length())
                + "<redacted:" + shortDigest(qualifiedName) + ">'";
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /**
     * Artifact endpoints must belong to the operation the event is about.
     *
     * <p>{@code nemaki://{repo}/imports/{operationId}} is only the right artifact if that
     * operationId is this event's. A different value would let an event point at the artifact of
     * an unrelated operation in the same repository — same tenant, wrong provenance, and nothing
     * in the repository check would notice.
     */
    public static void validateArtifactOperation(String eventOperationId,
                                                 java.util.List<LineageEndpoint> inputs,
                                                 java.util.List<LineageEndpoint> outputs) {
        requireList(inputs, "inputs");
        requireList(outputs, "outputs");
        for (LineageEndpoint endpoint : inputs) {
            validateArtifactOperation(eventOperationId, endpoint, "input");
        }
        for (LineageEndpoint endpoint : outputs) {
            validateArtifactOperation(eventOperationId, endpoint, "output");
        }
    }

    public static void validateArtifactOperation(String eventOperationId, LineageEndpoint endpoint,
                                                 String side) {
        if (endpoint == null) {
            // Not "nothing to check". A null here is the same mapping error validateEndpoint
            // rejects, and returning quietly would report a clean check over a broken list.
            throw new IllegalArgumentException(side + " endpoint must not be null");
        }
        if (!endpoint.kind().isOperationIdRequired()) {
            return;
        }
        if (eventOperationId == null || eventOperationId.isBlank()) {
            throw new IllegalArgumentException(
                    "event operationId is required when an artifact endpoint is present ("
                            + side + ", kind=" + endpoint.kind() + ")");
        }
        if (!eventOperationId.equals(endpoint.operationId())) {
            throw new IllegalArgumentException(
                    "artifact " + side + " endpoint belongs to operation '"
                            + endpoint.operationId() + "' but the event is operation '"
                            + eventOperationId + "'");
        }
    }

    /**
     * A null endpoint list is a caller bug, not an empty one. Treating it as empty would let a
     * mapping error skip the boundary check entirely and report success.
     */
    private static void requireList(java.util.List<LineageEndpoint> endpoints, String side) {
        if (endpoints == null) {
            throw new IllegalArgumentException(side + " must not be null");
        }
    }
}
