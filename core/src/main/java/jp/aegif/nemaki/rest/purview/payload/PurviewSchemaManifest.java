package jp.aegif.nemaki.rest.purview.payload;

import java.util.List;

public class PurviewSchemaManifest {

    private final String schemaVersion;
    private final String schemaHash;
    private final List<String> customTypeNames;
    private final List<String> relationshipTypeNames;
    private final List<String> businessMetadataNames;

    public PurviewSchemaManifest(
            String schemaVersion,
            String schemaHash,
            List<String> customTypeNames,
            List<String> relationshipTypeNames,
            List<String> businessMetadataNames) {
        this.schemaVersion = schemaVersion;
        this.schemaHash = schemaHash;
        this.customTypeNames = customTypeNames;
        this.relationshipTypeNames = relationshipTypeNames;
        this.businessMetadataNames = businessMetadataNames;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getSchemaHash() {
        return schemaHash;
    }

    public List<String> getCustomTypeNames() {
        return customTypeNames;
    }

    public List<String> getRelationshipTypeNames() {
        return relationshipTypeNames;
    }

    public List<String> getBusinessMetadataNames() {
        return businessMetadataNames;
    }
}
