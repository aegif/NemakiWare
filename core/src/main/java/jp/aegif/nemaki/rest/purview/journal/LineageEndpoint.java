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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One end of a lineage relationship, with the type and identity the catalog needs.
 *
 * <h2>repositoryId is required on every kind</h2>
 *
 * <p>Including the external ones. An external asset is outside the repository, but the claim
 * "this repository imported that thing" belongs to a repository, and the canonical qualified
 * name embeds it. Leaving it null on external endpoints while the qualified name and the
 * verifier both used it for identity meant an injected event could reference another
 * repository's external asset and pass every check.
 *
 * <p>{@code objectId} is the only nullable field, and only for the kinds whose identity is
 * carried by {@code externalStableKey} or {@code operationId} instead.
 *
 * <h2>attributes is a point-in-time snapshot</h2>
 *
 * <p>Per-endpoint rather than per-event: one event can reference several endpoints, and an
 * event-level attribute map cannot say which one it describes. It is also what lets a deleted
 * document be reconstructed as a historical entity during replay, so it is captured at emission
 * and never updated afterwards.
 */
public record LineageEndpoint(
        EndpointKind kind,
        String catalogQualifiedName,
        String repositoryId,
        String objectId,
        String operationId,
        Map<String, Object> attributes
) {

    /** Attribute key holding the raw external URI. Never logged; see the design's §4. */
    public static final String ATTR_EXTERNAL_STABLE_KEY = "externalStableKey";

    /** {@code sourceSystem} value that marks a stable key as a local filesystem path. */
    public static final String FILESYSTEM_SOURCE_SYSTEM = "filesystem";

    private static final String FILE_SCHEME = "file://";

    public LineageEndpoint {
        if (kind == null) {
            throw new IllegalArgumentException("endpoint kind must not be null");
        }
        if (isBlank(repositoryId)) {
            throw new IllegalArgumentException(
                    "endpoint repositoryId must not be blank (kind=" + kind + ")");
        }
        if (isBlank(catalogQualifiedName)) {
            throw new IllegalArgumentException(
                    "endpoint catalogQualifiedName must not be blank (kind=" + kind + ")");
        }
        // Present iff the kind is identified that way. A stray objectId on an external endpoint
        // is not harmless spare data: nothing reads it, so it would sit in the journal looking
        // like identity while the qualified name was built from something else.
        requireIdentityField(kind, "objectId", objectId,
                kind.identity() == EndpointKind.Identity.OBJECT_ID);
        requireIdentityField(kind, "operationId", operationId,
                kind.identity() == EndpointKind.Identity.OPERATION_ID);

        // Before Map.copyOf, which rejects nulls with a bare NullPointerException — that reads
        // as an internal fault rather than as the producer bug it is.
        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException(
                            "attribute key must not be null (kind=" + kind + ")");
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("attribute '" + entry.getKey()
                            + "' must not be null (kind=" + kind + ")");
                }
            }
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        kind.validateAttributes(attributes);
        requireCanonicalStableKey(kind, attributes);
    }

    /**
     * The stable-key contract, enforced here rather than only in the factories.
     *
     * <p>The factories are not the only way an endpoint appears. A record mapped from CouchDB, a
     * v1 legacy reader and the repair path all call the canonical constructor, and
     * {@link LineageRepositoryScope} rebuilds the qualified name <em>from this attribute</em> — so
     * a dangerous key present in both the name and the attribute is self-consistent and passes
     * every later check. The contract has to hold at construction or it does not hold at all.
     *
     * <p>Rejected rather than rewritten. Silently canonicalising would mean the endpoint's key no
     * longer equals what the caller stored elsewhere, and two records that should share an
     * identity would stop matching.
     */
    private static void requireCanonicalStableKey(EndpointKind kind,
                                                  Map<String, Object> attributes) {
        if (kind.identity() != EndpointKind.Identity.STABLE_KEY) {
            return;
        }
        String stored = (String) attributes.get(kind.identityAttribute());
        String canonical = canonicalStableKey(stored);
        if (!canonical.equals(stored)) {
            throw new IllegalArgumentException("stableKey is not canonical for kind=" + kind
                    + "; it must be stored exactly as canonicalStableKey would return it");
        }
        if (FILESYSTEM_SOURCE_SYSTEM.equals(attributes.get("sourceSystem"))) {
            if (!canonical.startsWith(FILE_SCHEME)) {
                throw new IllegalArgumentException(
                        "a filesystem endpoint's stableKey must begin with " + FILE_SCHEME);
            }
            String path = canonical.substring(FILE_SCHEME.length());
            if (!canonicalFilesystemPath(path).equals(path)) {
                throw new IllegalArgumentException("a filesystem endpoint's path must be absolute"
                        + " and normalised");
            }
        }
    }

    /**
     * A description safe to put in a log line or an exception.
     *
     * <p>The record's generated {@code toString} prints every component, and for an external
     * endpoint two of those — the stable key and the qualified name that base64-encodes it —
     * are the value the design forbids storing in plain sight. One stray log statement is enough.
     */
    @Override
    public String toString() {
        return "LineageEndpoint[kind=" + kind + ", repositoryId=" + repositoryId
                + ", qualifiedName=" + describeQualifiedName(kind, catalogQualifiedName)
                + (objectId == null ? "" : ", objectId=" + objectId)
                + (operationId == null ? "" : ", operationId=" + operationId)
                + ", attributes=" + describeAttributes(kind, attributes) + "]";
    }

    /** {@link #toString()}'s view of this endpoint's name, for callers that only hold the name. */
    public String describeQualifiedName() {
        return describeQualifiedName(kind, catalogQualifiedName);
    }

    /**
     * Redaction decided by the kind, not by the shape of the string.
     *
     * <p>A hand-crafted name need not contain {@code /external-assets/} at all, and keying off
     * that substring meant a name of {@code RAW_SECRET} was printed in full — exactly the input
     * that most needs hiding.
     */
    public static String describeQualifiedName(EndpointKind kind, String qualifiedName) {
        if (kind == null || kind.identity() != EndpointKind.Identity.STABLE_KEY) {
            return qualifiedName;
        }
        return "external:<redacted:" + shortDigest(qualifiedName) + ">";
    }

    private static Map<String, Object> describeAttributes(EndpointKind kind,
                                                          Map<String, Object> attributes) {
        if (kind == null || kind.identity() != EndpointKind.Identity.STABLE_KEY) {
            return attributes;
        }
        Map<String, Object> safe = new LinkedHashMap<>(attributes);
        // required on every STABLE_KEY kind, so there is no absent case to guard
        safe.put(kind.identityAttribute(),
                "<redacted:" + shortDigest(String.valueOf(safe.get(kind.identityAttribute()))) + ">");
        // externalPath is the same value in another spelling
        if (safe.containsKey("externalPath")) {
            safe.put("externalPath",
                    "<redacted:" + shortDigest(String.valueOf(safe.get("externalPath"))) + ">");
        }
        return safe;
    }

    /** Enough to tell two values apart in a log without reproducing either. */
    public static String shortDigest(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /** Identity fields are required exactly when the kind uses them, and forbidden otherwise. */
    private static void requireIdentityField(EndpointKind kind, String what, String value,
                                             boolean required) {
        if (required && isBlank(value)) {
            throw new IllegalArgumentException(
                    "endpoint " + what + " is required for kind=" + kind);
        }
        if (!required && value != null) {
            throw new IllegalArgumentException("endpoint " + what + " must not be set for kind="
                    + kind + ", which is identified by " + kind.identity());
        }
    }

    // ------------------------------------------------------------------
    // Factories — the only supported way to build an endpoint, so the
    // qualified-name rules live in exactly one place.
    // ------------------------------------------------------------------

    public static LineageEndpoint document(String repositoryId, String objectId, String name) {
        return new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                objectQualifiedName(repositoryId, objectId), repositoryId, objectId, null,
                Map.of("name", nonBlank(name, "name")));
    }

    public static LineageEndpoint document(String repositoryId, String objectId,
                                           Map<String, Object> attributes) {
        return new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                objectQualifiedName(repositoryId, objectId), repositoryId, objectId, null,
                attributes);
    }

    /**
     * A folder, referenced through its DataSet proxy rather than {@code nemaki_folder} — see
     * {@link EndpointKind#CMIS_FOLDER}.
     */
    public static LineageEndpoint folder(String repositoryId, String objectId, String name) {
        return new LineageEndpoint(EndpointKind.CMIS_FOLDER,
                folderProxyQualifiedName(repositoryId, objectId), repositoryId, objectId, null,
                Map.of("name", nonBlank(name, "name")));
    }

    /**
     * An archived object, identified by the archive's own id.
     *
     * <p>Not by the original's: {@code nemaki://{repo}/archives/{archiveId}} is what the existing
     * catalog sync already writes, and one document archived twice has two archives.
     * {@code originalObjectId} is mandatory on {@code nemaki_archive}, so it is required here
     * rather than optional — an archive without it cannot be created in Atlas at all.
     *
     * @param archivedAt epoch milliseconds; {@code nemaki_archive.archivedAt} is a {@code long}.
     */
    public static LineageEndpoint archive(String repositoryId, String archiveId,
                                          String originalObjectId, long archivedAt) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("archivedAt", archivedAt);
        attributes.put("originalObjectId", nonBlank(originalObjectId, "originalObjectId"));
        return new LineageEndpoint(EndpointKind.ARCHIVE,
                archiveQualifiedName(repositoryId, archiveId), repositoryId, archiveId, null,
                attributes);
    }

    public static LineageEndpoint externalAsset(String repositoryId, String stableKey,
                                                String sourceSystem) {
        String key = canonicalStableKey(stableKey);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sourceSystem", nonBlank(sourceSystem, "sourceSystem"));
        attributes.put(ATTR_EXTERNAL_STABLE_KEY, key);
        return new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                externalAssetQualifiedName(repositoryId, key), repositoryId, null, null,
                attributes);
    }

    /**
     * An object in a cloud drive.
     *
     * <p>The provider travels as {@code sourceSystem}, which is what
     * {@code nemaki_external_asset} declares. A separate {@code provider} attribute is an
     * increment-B schema addition; declaring it before the type has it would send a value Atlas
     * drops on arrival, which is the exact failure the allowlist exists to prevent.
     */
    public static LineageEndpoint cloudObject(String repositoryId, String provider,
                                              String cloudFileId) {
        String stableKey = canonicalStableKey("cloud://" + nonBlank(provider, "provider") + "/"
                + nonBlank(cloudFileId, "cloudFileId"));
        return new LineageEndpoint(EndpointKind.CLOUD_OBJECT,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                Map.of("sourceSystem", provider, ATTR_EXTERNAL_STABLE_KEY, stableKey));
    }

    /** As {@link #cloudObject}: the storage class travels as {@code sourceSystem} until B. */
    public static LineageEndpoint coldStorage(String repositoryId, String storageRef,
                                              String storageClass) {
        String stableKey = canonicalStableKey("cold://" + nonBlank(storageRef, "storageRef"));
        return new LineageEndpoint(EndpointKind.COLD_STORAGE,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                Map.of("sourceSystem", nonBlank(storageClass, "storageClass"),
                        ATTR_EXTERNAL_STABLE_KEY, stableKey));
    }

    /**
     * A filesystem path, as an external asset.
     *
     * <p>Not its own kind: {@code file://} is a resource outside the repository exactly as
     * {@code cloud://} and {@code cold://} are, and it takes the same qualified name rule.
     *
     * <p>The path is normalised and must be absolute. {@code /srv/in/./a.pdf} and
     * {@code /srv/in/b/../a.pdf} are the same file and must not become two assets, and a relative
     * path is not an identity at all — it means a different file depending on the working
     * directory of whichever process emitted it.
     */
    public static LineageEndpoint filesystemPath(String repositoryId, String path) {
        String normalised = canonicalFilesystemPath(nonBlank(path, "path"));
        // through canonicalStableKey like every other external key: a path can carry "?" or "#"
        // as ordinary characters, and this factory used to be the one way past that check
        String stableKey = canonicalStableKey(FILE_SCHEME + normalised);
        return new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                Map.of("sourceSystem", FILESYSTEM_SOURCE_SYSTEM,
                        ATTR_EXTERNAL_STABLE_KEY, stableKey,
                        "externalPath", normalised));
    }

    public static LineageEndpoint importArtifact(String repositoryId, String operationId,
                                                 String importMode,
                                                 Map<String, Object> extraAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("importMode", nonBlank(importMode, "importMode"));
        if (extraAttributes != null) {
            // Merged, not overlaid: an extras map carrying "importMode" would silently replace
            // the argument the caller passed alongside it, and only one of the two is the truth.
            for (Map.Entry<String, Object> extra : extraAttributes.entrySet()) {
                if (attributes.containsKey(extra.getKey())) {
                    throw new IllegalArgumentException("attribute '" + extra.getKey()
                            + "' is set by this factory and must not be passed in extras");
                }
                attributes.put(extra.getKey(), extra.getValue());
            }
        }
        return new LineageEndpoint(EndpointKind.IMPORT_ARTIFACT,
                importArtifactQualifiedName(repositoryId, operationId), repositoryId, null,
                nonBlank(operationId, "operationId"), attributes);
    }

    public static LineageEndpoint exportArtifact(String repositoryId, String operationId,
                                                 String artifactKind, String name,
                                                 Long objectCount) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("artifactKind", nonBlank(artifactKind, "artifactKind"));
        if (name != null && !name.isBlank()) {
            attributes.put("name", name);
        }
        if (objectCount != null) {
            attributes.put("objectCount", objectCount);
        }
        return new LineageEndpoint(EndpointKind.EXPORT_ARTIFACT,
                exportArtifactQualifiedName(repositoryId, operationId), repositoryId, null,
                nonBlank(operationId, "operationId"), attributes);
    }

    // ------------------------------------------------------------------
    // Qualified names — every one is repository-scoped
    // ------------------------------------------------------------------

    /**
     * The qualified name a well-formed endpoint must carry, recomputed from its own contents.
     *
     * <p>Every kind's name is a pure function of the repository plus one identity value, so a
     * verifier can rebuild it and compare exactly. The external kinds are not an exception: their
     * name encodes a stable key, and the endpoint keeps that key in
     * {@value #ATTR_EXTERNAL_STABLE_KEY} — so a name built from one key while the attribute holds
     * another is detectable, and it matters, because the catalog resolves the entity by name
     * while a snapshot reader resolves it by attribute.
     *
     * <p>The switch has no {@code default} on purpose: a new kind should fail to compile here
     * rather than fall through and lose the check.
     */
    public static String expectedQualifiedName(LineageEndpoint endpoint, String repositoryId) {
        EndpointKind kind = endpoint.kind();
        return switch (kind.identity()) {
            case OBJECT_ID -> switch (kind) {
                case CMIS_DOCUMENT -> objectQualifiedName(repositoryId, endpoint.objectId());
                case CMIS_FOLDER -> folderProxyQualifiedName(repositoryId, endpoint.objectId());
                case ARCHIVE -> archiveQualifiedName(repositoryId, endpoint.objectId());
                default -> throw new IllegalStateException("unmapped object kind " + kind);
            };
            case OPERATION_ID -> switch (kind) {
                case IMPORT_ARTIFACT ->
                        importArtifactQualifiedName(repositoryId, endpoint.operationId());
                case EXPORT_ARTIFACT ->
                        exportArtifactQualifiedName(repositoryId, endpoint.operationId());
                default -> throw new IllegalStateException("unmapped artifact kind " + kind);
            };
            case STABLE_KEY -> externalAssetQualifiedName(repositoryId,
                    (String) endpoint.attributes().get(kind.identityAttribute()));
        };
    }

    /**
     * A stable key with the parts the design forbids, rejected rather than encoded.
     *
     * <p>The qualified name is reversible base64, so anything in the key is recoverable by anyone
     * who can read a catalog entity or a log line. A signed URL puts a working credential there,
     * and a query string or fragment puts a value that is not part of the resource's identity —
     * the same object fetched twice with different query parameters would become two assets.
     *
     * <p>The design makes stripping these the producer's job. This is the check that a producer
     * did it, placed here because it is the one place every stable key passes through.
     */
    public static String canonicalStableKey(String stableKey) {
        String key = nonBlank(stableKey, "stableKey");
        // Not trimmed. A stable key may be an opaque connector id, where a leading space is part
        // of the id or it is not — and quietly removing it would merge two different assets into
        // one. Rejecting says which of the two the producer meant to send.
        if (!key.equals(key.strip())) {
            throw new IllegalArgumentException(
                    "stableKey must not begin or end with whitespace");
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                throw new IllegalArgumentException(
                        "stableKey must not contain control characters");
            }
        }
        if (key.indexOf('?') >= 0) {
            throw new IllegalArgumentException("stableKey must not contain a query string —"
                    + " strip it in the producer; it is not part of the resource's identity");
        }
        if (key.indexOf('#') >= 0) {
            throw new IllegalArgumentException("stableKey must not contain a fragment —"
                    + " strip it in the producer; it is not part of the resource's identity");
        }
        int schemeEnd = key.indexOf("://");
        if (schemeEnd >= 0) {
            int authorityEnd = key.indexOf('/', schemeEnd + 3);
            // == -1 rather than < 0: indexOf returns exactly -1, and "< 0" invites a boundary
            // question that cannot arise, since the search starts at schemeEnd + 3.
            String authority = authorityEnd == -1 ? key.substring(schemeEnd + 3)
                    : key.substring(schemeEnd + 3, authorityEnd);
            if (authority.indexOf('@') >= 0) {
                throw new IllegalArgumentException("stableKey must not carry userinfo —"
                        + " credentials must never reach a qualified name");
            }
        }
        return key;
    }

    /**
     * An absolute, normalised filesystem path.
     *
     * @throws IllegalArgumentException if the path is relative or cannot be parsed.
     */
    public static String canonicalFilesystemPath(String path) {
        java.nio.file.Path parsed;
        try {
            parsed = java.nio.file.Path.of(path);
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("filesystem path is not a valid path: "
                    + e.getReason(), e);
        }
        if (!parsed.isAbsolute()) {
            throw new IllegalArgumentException("filesystem path must be absolute: a relative path"
                    + " names a different file depending on who emitted it");
        }
        return parsed.normalize().toString();
    }

    /** The derivable part of an external asset's name; the rest is the encoded stable key. */
    public static String externalAssetQualifiedNamePrefix(String repositoryId) {
        return "nemaki://" + repositoryId + "/external-assets/";
    }

    public static String objectQualifiedName(String repositoryId, String objectId) {
        return "nemaki://" + repositoryId + "/objects/" + objectId;
    }

    public static String folderProxyQualifiedName(String repositoryId, String objectId) {
        return "nemaki://" + repositoryId + "/folders/" + objectId + "/dataset";
    }

    public static String archiveQualifiedName(String repositoryId, String archiveId) {
        return "nemaki://" + repositoryId + "/archives/" + archiveId;
    }

    public static String importArtifactQualifiedName(String repositoryId, String operationId) {
        return "nemaki://" + repositoryId + "/imports/" + operationId;
    }

    public static String exportArtifactQualifiedName(String repositoryId, String operationId) {
        return "nemaki://" + repositoryId + "/exports/" + operationId;
    }

    /**
     * The repository-scoped name for anything outside the repository.
     *
     * <p>Matches {@code PurviewEntityPayloadFactory.buildExternalAssetQualifiedName} so that the
     * catalog sync path and the lineage path name the same asset the same way; a raw URI here
     * would have split one asset into two entities.
     *
     * <p>The encoding is reversible. It is not protection — see the design's §4: the stable key
     * must never contain credentials, signed URLs, query strings or fragments.
     */
    public static String externalAssetQualifiedName(String repositoryId, String stableKey) {
        return externalAssetQualifiedNamePrefix(repositoryId)
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(stableKey.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nonBlank(String value, String what) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }
}
