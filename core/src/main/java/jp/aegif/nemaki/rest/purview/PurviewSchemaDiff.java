package jp.aegif.nemaki.rest.purview;

import java.util.List;

public class PurviewSchemaDiff {

    private final String collection;
    private final String currentSchemaVersion;
    private final String currentSchemaHash;
    private final String desiredSchemaVersion;
    private final String desiredSchemaHash;
    private final boolean applyRequired;
    private final List<String> customTypeNames;
    private final List<String> relationshipTypeNames;
    private final List<String> businessMetadataNames;

    public PurviewSchemaDiff(
            String collection,
            String currentSchemaVersion,
            String currentSchemaHash,
            String desiredSchemaVersion,
            String desiredSchemaHash,
            boolean applyRequired,
            List<String> customTypeNames,
            List<String> relationshipTypeNames,
            List<String> businessMetadataNames) {
        this.collection = collection;
        this.currentSchemaVersion = currentSchemaVersion;
        this.currentSchemaHash = currentSchemaHash;
        this.desiredSchemaVersion = desiredSchemaVersion;
        this.desiredSchemaHash = desiredSchemaHash;
        this.applyRequired = applyRequired;
        this.customTypeNames = customTypeNames;
        this.relationshipTypeNames = relationshipTypeNames;
        this.businessMetadataNames = businessMetadataNames;
    }

    public String getCollection() {
        return collection;
    }

    public String getCurrentSchemaVersion() {
        return currentSchemaVersion;
    }

    public String getCurrentSchemaHash() {
        return currentSchemaHash;
    }

    public String getDesiredSchemaVersion() {
        return desiredSchemaVersion;
    }

    public String getDesiredSchemaHash() {
        return desiredSchemaHash;
    }

    public boolean isApplyRequired() {
        return applyRequired;
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
