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

import static jp.aegif.nemaki.rest.purview.journal.EndpointAttribute.count;
import static jp.aegif.nemaki.rest.purview.journal.EndpointAttribute.requiredCount;
import static jp.aegif.nemaki.rest.purview.journal.EndpointAttribute.requiredText;
import static jp.aegif.nemaki.rest.purview.journal.EndpointAttribute.displayText;
import static jp.aegif.nemaki.rest.purview.journal.EndpointAttribute.text;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a lineage endpoint is, and therefore how the catalog must reference it.
 *
 * <p>Before this existed, endpoints were bare strings and {@code AtlasLineageSink} referenced
 * every one of them as {@code DataSet}. Of what producers actually emit, only documents are:
 * {@code nemaki_folder} extends {@code Referenceable}, and {@code upload://}, {@code file://},
 * {@code cloud://} and {@code cold://} have no entity at all. The type was knowable — the
 * producer always knew whether it held a folder or a document — and was being discarded at the
 * boundary.
 *
 * <p>Each kind fixes what the sink and the verifier both need: which Atlas type the endpoint
 * resolves to, which identity fields must be present, and which snapshot attributes may travel
 * with it, with the type each of those must have.
 *
 * <h2>How the qualified name is identified</h2>
 *
 * <p>Every kind's qualified name is a pure function of the repository plus exactly one identity
 * value, named here by {@link #identityAttribute()} for the external kinds and by
 * {@code objectId} / {@code operationId} for the rest. That is what lets
 * {@link LineageRepositoryScope} recompute the name and compare it exactly instead of settling
 * for a prefix.
 */
public enum EndpointKind {

    /** A CMIS document. {@code nemaki_document} extends {@code DataSet}. */
    CMIS_DOCUMENT("nemaki_document", Identity.OBJECT_ID, null,
            requiredText("name"), displayText("versionLabel"), displayText("folderPath"),
            // §2's evidence companions (v2.3.26): declared here so nothing is dropped, and
            // present in the Atlas type so the sink is not handing over something discarded.
            text("versionLabelOriginalSha256"), text("folderPathOriginalSha256"),
            // Which version series this document belongs to. In the Atlas schema today (unlike
            // versionObjectId / changeToken / contentHash, which are increment-B additions), so
            // declaring it costs nothing and the version-identity question (§3 v2.3.13) starts
            // being answerable as soon as a producer supplies it.
            text("versionSeriesId"),
            // 増分 B (v2.3.31): content facts and version identity. All identifiers, digests
            // and counts, so all PRESERVE — a shortened contentHash names no content, and a
            // shortened changeToken names no version.
            text("mimeType"), count("contentLength"),
            text("versionObjectId"), text("changeToken"), text("contentHash")),

    /**
     * A CMIS folder, referenced through its DataSet proxy.
     *
     * <p>{@code nemaki_folder} extends {@code Referenceable}, which {@code Process.inputs} does
     * not accept, so the proxy is what appears in lineage. Increment B created it (v2.3.30):
     * {@code nemaki_folder_dataset}, tied 1:1 to the folder and never deleted when the folder
     * is.
     */
    CMIS_FOLDER("nemaki_folder_dataset", Identity.OBJECT_ID, null,
            requiredText("name")),

    /**
     * An archived object. {@code nemaki_archive} extends {@code DataSet}.
     *
     * <p>{@code archivedAt} is epoch milliseconds because the Atlas type declares it {@code long};
     * a formatted timestamp would be dropped. {@code originalObjectId} — not {@code originalId} —
     * is the attribute the type actually has, and it is mandatory there.
     */
    ARCHIVE("nemaki_archive", Identity.OBJECT_ID, null,
            requiredCount("archivedAt"), requiredText("originalObjectId"),
            displayText("name"), displayText("versionLabel"),
            text("nameOriginalSha256"), text("versionLabelOriginalSha256"),
            // archiveState is machine-interpreted STATE and versionSeriesId IDENTIFIES the
            // version lineage — a shortened one of either names something that does not exist,
            // so both stay whole (§2 is about display values).
            text("archiveState"), text("versionSeriesId")),

    /**
     * Something outside the repository reached through an ingest connector.
     *
     * <p>All three external kinds declare exactly what {@code nemaki_external_asset} has:
     * {@code externalStableKey} and {@code sourceSystem}, both mandatory on the type, plus the
     * optional {@code externalPath}. {@code provider}, {@code storageClass} and {@code tenantId}
     * are increment-B schema additions and are not declared here — an attribute the type does not
     * have is dropped on arrival, which is the failure the allowlist exists to prevent, and
     * declaring it would have made the allowlist the source of the problem it solves.
     */
    EXTERNAL_ASSET("nemaki_external_asset", Identity.STABLE_KEY, "externalStableKey",
            requiredText("sourceSystem"), requiredText("externalStableKey"), text("externalPath"),
            // 増分 B (v2.3.31). tenantId only here: a cloud object's tenancy is already inside
            // its stable key, and cold storage has no tenant.
            text("tenantId"),
            text("sourceRevision"), count("sourceModifiedAt"),
            text("sourceContentHash"), count("sourceContentLength")),

    /**
     * An object in a cloud drive; {@code sourceSystem} carries the provider.
     *
     * <p>No {@code externalPath}. For a cloud object the natural value would be the drive URL,
     * and a drive URL legitimately carries query parameters — which is also where sharing tokens
     * live ({@code ?authkey=…}). Until increment B defines a provider-canonical URL rebuilt from
     * the file id, there is nothing safe to put here, so the attribute is unrepresentable rather
     * than merely unused. Identity does not need it: the stable key is already
     * {@code {provider}:{fileId}}.
     */
    CLOUD_OBJECT("nemaki_external_asset", Identity.STABLE_KEY, "externalStableKey",
            requiredText("sourceSystem"), requiredText("externalStableKey"),
            // 増分 B (v2.3.31). provider is the drive vendor as its own field, so a query does
            // not have to parse it back out of sourceSystem. Still no externalPath: B defines
            // no provider-canonical URL, so there remains nothing safe to put there.
            text("provider"),
            text("sourceRevision"), count("sourceModifiedAt"),
            text("sourceContentHash"), count("sourceContentLength")),

    /**
     * An object moved to cold storage.
     *
     * <p>{@code sourceSystem} carries the <em>storage adapter type</em> — the same value the
     * catalog sync takes from {@code archive.getContentRef().get("type")} — not the storage class.
     * A storage class such as {@code GLACIER} is a different fact and is an increment-B attribute.
     */
    COLD_STORAGE("nemaki_external_asset", Identity.STABLE_KEY, "externalStableKey",
            requiredText("sourceSystem"), requiredText("externalStableKey"), text("externalPath"),
            // 増分 B (v2.3.31). The storage class within the adapter — GLACIER and friends —
            // which is a different fact from the adapter type in sourceSystem.
            text("storageClass")),

    /**
     * What was fed into an import — the upload or the source directory.
     *
     * <p>No source path or URL attribute, deliberately: where the bytes came from belongs to the
     * operation ({@code nemaki_import_process.sourceDescription}), and an operator-supplied path
     * is exactly where §4's sharing links keep their tokens. {@code originalFileName} is a leaf
     * name. {@code EndpointKindSchemaAlignmentTest} asserts this rather than trusting it.
     *
     * <p>{@code originalFileName} is the only display value, so it is the only one under §2's
     * limit; {@code importMode} is machine state and {@code contentHash} is a digest, and a
     * shortened one of either would name something that does not exist.
     */
    IMPORT_ARTIFACT("nemaki_import_artifact", Identity.OPERATION_ID, null,
            requiredText("importMode"), count("byteLength"), text("contentHash"),
            displayText("originalFileName"), text("originalFileNameOriginalSha256")),

    /**
     * What an export produced — the zip or the target directory.
     *
     * <p>No target path or URL attribute, mirroring the import side; the destination is
     * {@code nemaki_export_process.targetDescription}. {@code name} is the export's display name
     * and is the only value here under §2's limit.
     */
    EXPORT_ARTIFACT("nemaki_export_artifact", Identity.OPERATION_ID, null,
            requiredText("artifactKind"), count("objectCount"),
            displayText("name"), text("nameOriginalSha256"));

    /** Which value the qualified name is built from. Exactly one per kind. */
    public enum Identity {
        /** A CMIS object; {@code objectId} is required and {@code operationId} must be absent. */
        OBJECT_ID,
        /** An operation's artifact; {@code operationId} is required and {@code objectId} absent. */
        OPERATION_ID,
        /** Something outside the repository, named by an attribute; both fields must be absent. */
        STABLE_KEY
    }

    private final String atlasTypeName;
    private final Identity identity;
    private final String identityAttribute;
    private final Map<String, EndpointAttribute> attributes;

    EndpointKind(String atlasTypeName, Identity identity, String identityAttribute,
                 EndpointAttribute... attributes) {
        this.atlasTypeName = atlasTypeName;
        this.identity = identity;
        this.identityAttribute = identityAttribute;
        Map<String, EndpointAttribute> declared = new LinkedHashMap<>();
        for (EndpointAttribute attribute : attributes) {
            // A duplicate used to overwrite silently, which would let a generated evidence
            // companion quietly replace a declared attribute of the same name (v2.3.26).
            if (declared.put(attribute.name(), attribute) != null) {
                throw new IllegalStateException("attribute '" + attribute.name()
                        + "' is declared twice on " + atlasTypeName);
            }
        }
        for (EndpointAttribute attribute : declared.values()) {
            if (attribute.policy() == EndpointAttribute.Policy.TRUNCATE_WITH_EVIDENCE
                    && !declared.containsKey(attribute.evidenceName())) {
                throw new IllegalStateException("'" + attribute.name() + "' truncates but its"
                        + " companion '" + attribute.evidenceName() + "' is not declared on "
                        + atlasTypeName + " — the evidence would be dropped on arrival");
            }
        }
        // Not Map.copyOf: that loses insertion order, and both allowedAttributes() and the
        // "allowed: [...]" in a rejection message are meant to read in declaration order.
        this.attributes = java.util.Collections.unmodifiableMap(declared);
    }

    /** The concrete Atlas type. Never {@code DataSet} itself — a shell would satisfy that. */
    public String atlasTypeName() {
        return atlasTypeName;
    }

    public Identity identity() {
        return identity;
    }

    /**
     * The attribute the qualified name is built from, for kinds identified that way.
     *
     * <p>{@code null} for the CMIS and artifact kinds, whose name comes from {@code objectId} or
     * {@code operationId} instead.
     */
    public String identityAttribute() {
        return identityAttribute;
    }

    /** Artifact kinds carry the operation they belong to; their qualified name is built from it. */
    public boolean isOperationIdRequired() {
        return identity == Identity.OPERATION_ID;
    }

    /**
     * The only attributes this kind may carry, in declaration order.
     *
     * <p>An allowlist rather than a free map: the catalog silently drops attributes its schema
     * does not declare, so anything outside this set would be sent, discarded, and never missed.
     */
    public List<String> allowedAttributes() {
        return List.copyOf(attributes.keySet());
    }

    /** Attributes without which the entity would be indistinguishable from an Atlas shell. */
    public List<String> requiredAttributes() {
        return attributes.values().stream().filter(EndpointAttribute::required)
                .map(EndpointAttribute::name).toList();
    }

    public boolean isAllowedAttribute(String key) {
        return attributes.containsKey(key);
    }

    /** @return the declaration, or {@code null} if this kind does not allow that attribute. */
    public EndpointAttribute attribute(String key) {
        return attributes.get(key);
    }

    /** @throws IllegalArgumentException if the map is not a valid attribute set for this kind. */
    public void validateAttributes(Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("attribute key must not be null (kind=" + this + ")");
            }
            EndpointAttribute declared = attributes.get(entry.getKey());
            if (declared == null) {
                throw new IllegalArgumentException("attribute '" + entry.getKey() + "' is not in"
                        + " the allowlist for kind=" + this + " (allowed: " + allowedAttributes()
                        + ")");
            }
            declared.validate(entry.getValue(), this);
        }
        for (EndpointAttribute declared : attributes.values()) {
            if (declared.required() && !values.containsKey(declared.name())) {
                throw new IllegalArgumentException("attribute '" + declared.name() + "' is required"
                        + " for kind=" + this
                        + " — without it the entity is indistinguishable from a shell");
            }
        }
    }
}
