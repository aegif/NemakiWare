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

    private static final String SCHEMA_VERSION = "13";
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
            "nemaki_export_process");
    private static final List<String> RELATIONSHIP_TYPE_NAMES = List.of(
            "nemaki_repository_contains_folder",
            "nemaki_folder_contains_folder",
            "nemaki_folder_contains_document",
            "nemaki_document_has_type_definition",
            "nemaki_document_has_archive");
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
