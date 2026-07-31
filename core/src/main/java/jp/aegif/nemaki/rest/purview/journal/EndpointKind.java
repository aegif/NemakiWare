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
            requiredText("name"), text("versionLabel"), text("folderPath")),

    /**
     * A CMIS folder, referenced through its DataSet proxy.
     *
     * <p>{@code nemaki_folder} extends {@code Referenceable}, which {@code Process.inputs} does
     * not accept, so the proxy is what appears in lineage. Increment B creates it; until then a
     * folder endpoint is well-formed here but will not resolve in Atlas.
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
            requiredCount("archivedAt"), requiredText("originalObjectId"), text("name"),
            text("versionLabel"), text("archiveState")),

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
            requiredText("sourceSystem"), requiredText("externalStableKey"), text("externalPath")),

    /** An object in a cloud drive; {@code sourceSystem} carries the provider. */
    CLOUD_OBJECT("nemaki_external_asset", Identity.STABLE_KEY, "externalStableKey",
            requiredText("sourceSystem"), requiredText("externalStableKey"), text("externalPath")),

    /**
     * An object moved to cold storage.
     *
     * <p>{@code sourceSystem} carries the <em>storage adapter type</em> — the same value the
     * catalog sync takes from {@code archive.getContentRef().get("type")} — not the storage class.
     * A storage class such as {@code GLACIER} is a different fact and is an increment-B attribute.
     */
    COLD_STORAGE("nemaki_external_asset", Identity.STABLE_KEY, "externalStableKey",
            requiredText("sourceSystem"), requiredText("externalStableKey"), text("externalPath")),

    /** What was fed into an import — the upload or the source directory. */
    IMPORT_ARTIFACT("nemaki_import_artifact", Identity.OPERATION_ID, null,
            requiredText("importMode"), count("byteLength"), text("contentHash"),
            text("originalFileName")),

    /** What an export produced — the zip or the target directory. */
    EXPORT_ARTIFACT("nemaki_export_artifact", Identity.OPERATION_ID, null,
            requiredText("artifactKind"), count("objectCount"), text("name"));

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
            declared.put(attribute.name(), attribute);
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
