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

import java.util.List;
import java.util.Set;

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
 * <p>Each kind fixes three things that the sink and the verifier both need: which Atlas type the
 * endpoint resolves to, which identity fields must be present, and which snapshot attributes may
 * travel with it.
 */
public enum EndpointKind {

    /** A CMIS document. {@code nemaki_document} extends {@code DataSet}. */
    CMIS_DOCUMENT("nemaki_document", true, false, Set.of("name"),
            List.of("name", "mimeType", "contentLength", "versionLabel")),

    /**
     * A CMIS folder, referenced through its DataSet proxy.
     *
     * <p>{@code nemaki_folder} extends {@code Referenceable}, which {@code Process.inputs} does
     * not accept, so the proxy is what appears in lineage. Increment B creates it; until then a
     * folder endpoint is well-formed here but will not resolve in Atlas.
     */
    CMIS_FOLDER("nemaki_folder_dataset", true, false, Set.of("name"),
            List.of("name", "path")),

    /** An archived object. {@code nemaki_archive} extends {@code DataSet}. */
    ARCHIVE("nemaki_archive", true, false, Set.of("archivedAt"),
            List.of("name", "originalId", "archivedAt")),

    /** Something outside the repository reached through an ingest connector. */
    EXTERNAL_ASSET("nemaki_external_asset", false, false, Set.of("sourceSystem"),
            List.of("sourceSystem", "externalStableKey", "tenantId")),

    /** An object in a cloud drive. */
    CLOUD_OBJECT("nemaki_external_asset", false, false, Set.of("provider"),
            List.of("provider", "externalStableKey")),

    /** An object moved to cold storage. */
    COLD_STORAGE("nemaki_external_asset", false, false, Set.of("storageClass"),
            List.of("storageClass", "externalStableKey")),

    /** What was fed into an import — the upload or the source directory. */
    IMPORT_ARTIFACT("nemaki_import_artifact", false, true, Set.of("importMode"),
            List.of("importMode", "byteLength", "contentHash", "originalFileName")),

    /** What an export produced — the zip or the target directory. */
    EXPORT_ARTIFACT("nemaki_export_artifact", false, true, Set.of("artifactKind"),
            List.of("artifactKind", "objectCount", "name"));

    private final String atlasTypeName;
    private final boolean objectIdRequired;
    private final boolean operationIdRequired;
    private final Set<String> requiredAttributes;
    private final List<String> allowedAttributes;

    EndpointKind(String atlasTypeName, boolean objectIdRequired, boolean operationIdRequired,
                 Set<String> requiredAttributes, List<String> allowedAttributes) {
        this.atlasTypeName = atlasTypeName;
        this.objectIdRequired = objectIdRequired;
        this.operationIdRequired = operationIdRequired;
        this.requiredAttributes = requiredAttributes;
        this.allowedAttributes = allowedAttributes;
    }

    /** The concrete Atlas type. Never {@code DataSet} itself — a shell would satisfy that. */
    public String atlasTypeName() {
        return atlasTypeName;
    }

    /** Kinds identified by a CMIS object; the rest are identified by stable key or operation. */
    public boolean isObjectIdRequired() {
        return objectIdRequired;
    }

    /** Artifact kinds carry the operation they belong to; their qualified name is built from it. */
    public boolean isOperationIdRequired() {
        return operationIdRequired;
    }

    /** Attributes without which the entity would be indistinguishable from an Atlas shell. */
    public Set<String> requiredAttributes() {
        return requiredAttributes;
    }

    /**
     * The only attributes this kind may carry.
     *
     * <p>An allowlist rather than a free map: the catalog silently drops attributes its schema
     * does not declare, so anything outside this set would be sent, discarded, and never missed.
     */
    public List<String> allowedAttributes() {
        return allowedAttributes;
    }

    public boolean isAllowedAttribute(String key) {
        return allowedAttributes.contains(key);
    }
}
