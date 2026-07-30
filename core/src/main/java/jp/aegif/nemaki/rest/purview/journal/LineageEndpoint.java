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
        if (kind.isObjectIdRequired() && isBlank(objectId)) {
            throw new IllegalArgumentException(
                    "endpoint objectId is required for kind=" + kind);
        }
        if (kind.isOperationIdRequired() && isBlank(operationId)) {
            throw new IllegalArgumentException(
                    "endpoint operationId is required for kind=" + kind);
        }
        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException(
                            "attribute key must not be null (kind=" + kind + ")");
                }
                // Map.copyOf would raise a bare NullPointerException here, which reads as an
                // internal fault rather than as the producer bug it is.
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("attribute '" + entry.getKey()
                            + "' must not be null (kind=" + kind + ")");
                }
            }
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        for (String key : attributes.keySet()) {
            if (!kind.isAllowedAttribute(key)) {
                throw new IllegalArgumentException(
                        "attribute '" + key + "' is not in the allowlist for kind=" + kind
                                + " (allowed: " + kind.allowedAttributes() + ")");
            }
        }
        for (String required : kind.requiredAttributes()) {
            Object value = attributes.get(required);
            if (value == null || (value instanceof String s && s.isBlank())) {
                throw new IllegalArgumentException(
                        "attribute '" + required + "' is required for kind=" + kind
                                + " — without it the entity is indistinguishable from a shell");
            }
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

    public static LineageEndpoint archive(String repositoryId, String archiveId,
                                          String originalId, String archivedAt) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("archivedAt", nonBlank(archivedAt, "archivedAt"));
        if (originalId != null && !originalId.isBlank()) {
            attributes.put("originalId", originalId);
        }
        return new LineageEndpoint(EndpointKind.ARCHIVE,
                archiveQualifiedName(repositoryId, archiveId), repositoryId, archiveId, null,
                attributes);
    }

    public static LineageEndpoint externalAsset(String repositoryId, String stableKey,
                                                String sourceSystem, String tenantId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sourceSystem", nonBlank(sourceSystem, "sourceSystem"));
        attributes.put(ATTR_EXTERNAL_STABLE_KEY, nonBlank(stableKey, "stableKey"));
        if (tenantId != null && !tenantId.isBlank()) {
            attributes.put("tenantId", tenantId);
        }
        return new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                attributes);
    }

    public static LineageEndpoint cloudObject(String repositoryId, String provider,
                                              String cloudFileId) {
        String stableKey = "cloud://" + nonBlank(provider, "provider") + "/"
                + nonBlank(cloudFileId, "cloudFileId");
        return new LineageEndpoint(EndpointKind.CLOUD_OBJECT,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                Map.of("provider", provider, ATTR_EXTERNAL_STABLE_KEY, stableKey));
    }

    public static LineageEndpoint coldStorage(String repositoryId, String storageRef,
                                              String storageClass) {
        String stableKey = "cold://" + nonBlank(storageRef, "storageRef");
        return new LineageEndpoint(EndpointKind.COLD_STORAGE,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                Map.of("storageClass", nonBlank(storageClass, "storageClass"),
                        ATTR_EXTERNAL_STABLE_KEY, stableKey));
    }

    /**
     * A filesystem path, as an external asset.
     *
     * <p>Not its own kind: {@code file://} is a resource outside the repository exactly as
     * {@code cloud://} and {@code cold://} are, and it takes the same qualified name rule.
     */
    public static LineageEndpoint filesystemPath(String repositoryId, String path) {
        String stableKey = "file://" + nonBlank(path, "path");
        return new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                Map.of("sourceSystem", "filesystem", ATTR_EXTERNAL_STABLE_KEY, stableKey));
    }

    public static LineageEndpoint importArtifact(String repositoryId, String operationId,
                                                 String importMode,
                                                 Map<String, Object> extraAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("importMode", nonBlank(importMode, "importMode"));
        if (extraAttributes != null) {
            attributes.putAll(extraAttributes);
        }
        return new LineageEndpoint(EndpointKind.IMPORT_ARTIFACT,
                importArtifactQualifiedName(repositoryId, operationId), repositoryId, null,
                nonBlank(operationId, "operationId"), attributes);
    }

    public static LineageEndpoint exportArtifact(String repositoryId, String operationId,
                                                 String artifactKind, String name,
                                                 Integer objectCount) {
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
     * The qualified name a well-formed endpoint of this shape must carry, or {@code null} when the
     * name cannot be derived from the endpoint's own fields.
     *
     * <p>For every CMIS and artifact kind the name is a pure function of the repository and the
     * identity field, so a verifier can recompute it and compare. The external kinds are the
     * exception: their name encodes a {@code stableKey} that the endpoint does not keep as a
     * field, so only the prefix is derivable — see {@link #externalAssetQualifiedNamePrefix}.
     *
     * <p>The switch has no {@code default} on purpose: a new kind should fail to compile here
     * rather than silently fall through to "cannot be derived" and lose the check.
     */
    public static String expectedQualifiedName(EndpointKind kind, String repositoryId,
                                               String objectId, String operationId) {
        return switch (kind) {
            case CMIS_DOCUMENT -> objectQualifiedName(repositoryId, objectId);
            case CMIS_FOLDER -> folderProxyQualifiedName(repositoryId, objectId);
            case ARCHIVE -> archiveQualifiedName(repositoryId, objectId);
            case IMPORT_ARTIFACT -> importArtifactQualifiedName(repositoryId, operationId);
            case EXPORT_ARTIFACT -> exportArtifactQualifiedName(repositoryId, operationId);
            case EXTERNAL_ASSET, CLOUD_OBJECT, COLD_STORAGE -> null;
        };
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
