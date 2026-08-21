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
import java.util.LinkedHashMap;
import java.util.Map;

import jp.aegif.nemaki.rest.purview.ExternalAssetIdentity;

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
    public static final String FILESYSTEM_SOURCE_SYSTEM =
            ExternalAssetIdentity.FILESYSTEM_SOURCE_SYSTEM;

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
        String sourceSystem = (String) attributes.get("sourceSystem");
        Object externalPath = attributes.get("externalPath");

        if (FILESYSTEM_SOURCE_SYSTEM.equals(sourceSystem)) {
            String path = ExternalAssetIdentity.filesystemPathOf(canonical);
            if (path == null) {
                throw new IllegalArgumentException("a filesystem endpoint's stableKey must be "
                        + FILESYSTEM_SOURCE_SYSTEM + ":{absolute path}");
            }
            // the absolute/normalised rule is enforced by ExternalAssetIdentity.parse above
            // externalPath is the same value in another spelling, so it is derived rather than
            // trusted: two spellings of one path are two assets to whatever reads the attribute.
            if (externalPath != null && !path.equals(externalPath)) {
                throw new IllegalArgumentException("a filesystem endpoint's externalPath must be"
                        + " the path its stableKey names");
            }
        } else if (kind == EndpointKind.CLOUD_OBJECT) {
            // the key is {provider}:{fileId} and the provider is the sourceSystem, so the two
            // cannot describe different providers
            if (!canonical.startsWith(sourceSystem + ":")
                    || canonical.length() <= sourceSystem.length() + 1) {
                throw new IllegalArgumentException("a cloud endpoint's stableKey must be"
                        + " {sourceSystem}:{externalFileId}");
            }
        } else if (kind == EndpointKind.COLD_STORAGE) {
            // the sync writes externalPath = the reference itself
            if (externalPath != null && !canonical.equals(externalPath)) {
                throw new IllegalArgumentException("a cold-storage endpoint's externalPath must be"
                        + " its stableKey");
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

    /**
     * Enough to tell two values apart in a log without reproducing either.
     *
     * <p>The <i>redaction</i> kind in {@link LineageDigests}: truncated on purpose, so it is not
     * evidence of anything. Never persist it and never compare it with an evidence digest —
     * {@link EndpointAttribute#isEvidenceDigest} refuses it by width.
     */
    public static String shortDigest(String value) {
        return LineageDigests.redactionDigest(value);
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

    /**
     * The producer boundary (v2.3.26): raw domain data becomes an endpoint here, and §2's
     * attribute limits are applied here and ONLY here.
     *
     * <p>Not in the canonical constructor — the spool and v2 codecs rebuild endpoints through
     * it, so normalizing there would rewrite a stored record as it was read and its persisted
     * digest would stop verifying.
     */
    private static LineageEndpoint produced(EndpointKind kind, String catalogQualifiedName,
            String repositoryId, String objectId, String operationId,
            Map<String, Object> attributes) {
        return new LineageEndpoint(kind, catalogQualifiedName, repositoryId, objectId,
                operationId, EndpointAttributeLimits.normalize(kind, attributes));
    }

    public static LineageEndpoint document(String repositoryId, String objectId, String name) {
        return produced(EndpointKind.CMIS_DOCUMENT,
                objectQualifiedName(repositoryId, objectId), repositoryId, objectId, null,
                Map.of("name", nonBlank(name, "name")));
    }

    public static LineageEndpoint document(String repositoryId, String objectId,
                                           Map<String, Object> attributes) {
        return produced(EndpointKind.CMIS_DOCUMENT,
                objectQualifiedName(repositoryId, objectId), repositoryId, objectId, null,
                attributes);
    }

    /**
     * A folder, referenced through its DataSet proxy rather than {@code nemaki_folder} — see
     * {@link EndpointKind#CMIS_FOLDER}.
     */
    public static LineageEndpoint folder(String repositoryId, String objectId, String name) {
        return produced(EndpointKind.CMIS_FOLDER,
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
        return archive(repositoryId, archiveId, originalObjectId, archivedAt, null);
    }

    /**
     * The same archive endpoint with the optional display name.
     *
     * <p>Exists so that the retention scheduler, which has a name to record, goes through the
     * producer boundary instead of the canonical constructor — building one by hand is how an
     * over-long name would skip §2's limits entirely (v2.3.26).
     */
    public static LineageEndpoint archive(String repositoryId, String archiveId,
                                          String originalObjectId, long archivedAt,
                                          String name) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("archivedAt", archivedAt);
        attributes.put("originalObjectId", nonBlank(originalObjectId, "originalObjectId"));
        if (name != null && !name.isBlank()) {
            attributes.put("name", name);
        }
        return produced(EndpointKind.ARCHIVE,
                archiveQualifiedName(repositoryId, archiveId), repositoryId, archiveId, null,
                attributes);
    }

    public static LineageEndpoint externalAsset(String repositoryId, String stableKey,
                                                String sourceSystem) {
        return externalAsset(repositoryId, stableKey, sourceSystem, Map.of());
    }

    /**
     * An external asset with further facts about the source beside its identity.
     *
     * <p>{@code sourceSystem} and the stable key are always present; {@code extra} adds what the
     * key does not already state. Facts the key DOES state do not belong here — for a chat
     * message the key is
     * {@code {system}://org/{workspaceId}/channels/{channelId}/messages/{messageId}}, so those
     * three ids would be repeated rather than added (P1-1(b)).
     */
    public static LineageEndpoint externalAsset(String repositoryId, String stableKey,
                                                String sourceSystem, Map<String, Object> extra) {
        String key = canonicalStableKey(stableKey);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sourceSystem", nonBlank(sourceSystem, "sourceSystem"));
        attributes.put(ATTR_EXTERNAL_STABLE_KEY, key);
        if (extra != null) {
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                // Identity first: extra must not be able to redefine what the endpoint IS.
                if (!"sourceSystem".equals(entry.getKey())
                        && !ATTR_EXTERNAL_STABLE_KEY.equals(entry.getKey())) {
                    attributes.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return produced(EndpointKind.EXTERNAL_ASSET,
                externalAssetQualifiedName(repositoryId, key), repositoryId, null, null,
                attributes);
    }

    /**
     * An object in a cloud drive.
     *
     * <p>The stable key is {@link ExternalAssetIdentity#cloud}, which is what the catalog sync has
     * already written entities under. A different spelling here would name a second Atlas entity
     * for the same file, and the Process would point at whichever of the two nothing else uses.
     *
     * <p>The provider travels as {@code sourceSystem}, which is what
     * {@code nemaki_external_asset} declares. A separate {@code provider} attribute is an
     * increment-B schema addition; declaring it before the type has it would send a value Atlas
     * drops on arrival, which is the exact failure the allowlist exists to prevent.
     */
    public static LineageEndpoint cloudObject(String repositoryId, String provider,
                                              String cloudFileId) {
        String stableKey = ExternalAssetIdentity
                .cloud(nonBlank(provider, "provider"), nonBlank(cloudFileId, "cloudFileId"))
                .value();
        return produced(EndpointKind.CLOUD_OBJECT,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                Map.of("sourceSystem", provider, ATTR_EXTERNAL_STABLE_KEY, stableKey));
    }

    /**
     * An object moved to cold storage, named by the archive's own content reference.
     *
     * <p>The reference is opaque and is used as-is — {@code PurviewEntityPayloadFactory} takes
     * {@code archive.getContentRef().get("ref")} unchanged, so wrapping it in a scheme here would
     * name a different entity from the one the sync created.
     *
     * @param sourceSystem what {@code resolveArchiveSourceSystem} returned for this archive, or
     *                     {@link ExternalAssetIdentity#COLD_STORAGE_SOURCE_SYSTEM} when unknown.
     */
    public static LineageEndpoint coldStorage(String repositoryId, String contentRef,
                                              String sourceSystem) {
        String stableKey = ExternalAssetIdentity.opaque(nonBlank(contentRef, "contentRef")).value();
        return produced(EndpointKind.COLD_STORAGE,
                externalAssetQualifiedName(repositoryId, stableKey), repositoryId, null, null,
                Map.of("sourceSystem", nonBlank(sourceSystem, "sourceSystem"),
                        ATTR_EXTERNAL_STABLE_KEY, stableKey,
                        "externalPath", stableKey));
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
        String stableKey = ExternalAssetIdentity.filesystem(nonBlank(path, "path")).value();
        String normalised = ExternalAssetIdentity.filesystemPathOf(stableKey);
        return produced(EndpointKind.EXTERNAL_ASSET,
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
        return produced(EndpointKind.IMPORT_ARTIFACT,
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
        return produced(EndpointKind.EXPORT_ARTIFACT,
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

    // ------------------------------------------------------------------
    // Qualified names — every one is repository-scoped
    // ------------------------------------------------------------------

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

    /** The derivable part of an external asset's name; the rest is the encoded stable key. */
    public static String externalAssetQualifiedNamePrefix(String repositoryId) {
        return ExternalAssetIdentity.qualifiedNamePrefix(repositoryId);
    }

    /**
     * The repository-scoped name for anything outside the repository.
     *
     * <p>Matches what the catalog sync writes because both go through
     * {@link ExternalAssetIdentity}: the key is validated on the way in, so an unchecked String
     * cannot become a name on either path.
     */
    public static String externalAssetQualifiedName(String repositoryId, String stableKey) {
        return ExternalAssetIdentity.qualifiedName(repositoryId,
                ExternalAssetIdentity.parse(stableKey));
    }

    /**
     * A stable key validated by the one rule both this and the catalog sync use.
     *
     * @see ExternalAssetIdentity#parse
     */
    public static String canonicalStableKey(String stableKey) {
        return ExternalAssetIdentity.parse(stableKey).value();
    }

    /**
     * An absolute, normalised filesystem path.
     *
     * @see ExternalAssetIdentity#normalisedAbsolutePath
     */
    public static String canonicalFilesystemPath(String path) {
        return ExternalAssetIdentity.normalisedAbsolutePath(path);
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
