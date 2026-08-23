package jp.aegif.nemaki.rest.purview.payload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PurviewSchemaManifestFactory {

    // 14: increment B adds nemaki_import_artifact / nemaki_export_artifact.
    // 15: increment B adds nemaki_folder_dataset and nemaki_folder_has_dataset.
    //
    // The version moves with the type list because the manifest hash is what tells a deployment
    // that its catalog is missing a type rather than merely out of date. It only ever increases,
    // and every step is additive: applying 15 over a catalog at 13 creates what 14 and 15 add
    // and touches nothing that exists, so the upgrade is safe to re-run.
    /**
     * Bumped to 16 for P1-1(d) R3's {@code contentHashSubject} on {@code nemaki_document}.
     *
     * <p><b>Bumping is not optional when an attribute is added.</b> The manifest hash below is
     * computed over the version, the type NAMES, the relationship names, the business-metadata
     * names and the property-mapping fingerprint — <b>not over the attribute lists</b>. So a
     * deployment that already applied the schema sees an unchanged hash, the planner reports
     * "up to date", and the new attribute is never created on the catalogue type. The sink then
     * sends it and the destination drops it on arrival, silently. Adding three attributes under
     * an unchanged version has happened before in this file's history; this note is here so the
     * next attribute does not repeat it (external review).
     */
    private static final String SCHEMA_VERSION = "17";
    private static final List<String> CUSTOM_TYPE_NAMES = List.of(
            "nemaki_repository",
            "nemaki_folder",
            "nemaki_document",
            "nemaki_type_definition",
            "nemaki_archive",
            "nemaki_external_asset",
            "nemaki_archive_process",
            "nemaki_cloud_sync_process",
            "nemaki_import_process",
            "nemaki_export_process",
            "nemaki_import_artifact",
            "nemaki_export_artifact",
            "nemaki_folder_dataset");
    private static final List<String> RELATIONSHIP_TYPE_NAMES = List.of(
            "nemaki_repository_contains_folder",
            "nemaki_folder_contains_folder",
            "nemaki_folder_contains_document",
            "nemaki_document_has_type_definition",
            "nemaki_document_has_archive",
            "nemaki_folder_has_dataset");
    private static final List<String> BUSINESS_METADATA_NAMES = List.of();

    private CatalogPropertyMappingResolver propertyMappingResolver;

    @Autowired(required = false)
    public void setPropertyMappingResolver(CatalogPropertyMappingResolver propertyMappingResolver) {
        this.propertyMappingResolver = propertyMappingResolver;
    }

    public PurviewSchemaManifest buildManifest() {
        String mappingFingerprint = propertyMappingResolver != null
                ? propertyMappingResolver.computeMappingFingerprintAllRepositories()
                : "";
        String canonical = String.join("\n",
                "schemaVersion=" + SCHEMA_VERSION,
                "customTypes=" + String.join(",", CUSTOM_TYPE_NAMES),
                "relationshipTypes=" + String.join(",", RELATIONSHIP_TYPE_NAMES),
                "businessMetadata=" + String.join(",", BUSINESS_METADATA_NAMES),
                "propertyMappings=" + mappingFingerprint);
        String schemaHash = sha256(canonical);
        return new PurviewSchemaManifest(
                SCHEMA_VERSION,
                schemaHash,
                CUSTOM_TYPE_NAMES,
                RELATIONSHIP_TYPE_NAMES,
                BUSINESS_METADATA_NAMES);
    }

    /**
     * The schema manifest hash: plain SHA-256 over the canonical schema text.
     *
     * <p>Not domain-separated, and it does not need to be — its input is one specific
     * serialisation of one specific document, never a concatenation of caller-supplied parts.
     * That is the distinction {@code LineageDigests} draws; this class keeps its own copy only
     * because that primitive is package-private to the journal package, and widening it to
     * share four lines would be the worse trade.
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
