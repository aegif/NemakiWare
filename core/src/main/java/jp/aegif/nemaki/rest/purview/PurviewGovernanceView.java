package jp.aegif.nemaki.rest.purview;

import java.util.List;
import java.util.Map;

public class PurviewGovernanceView {

    private final boolean featureEnabled;
    private final boolean available;
    private final boolean supportedObjectType;
    private final boolean entityFound;
    private final String repositoryId;
    private final String objectId;
    private final String objectBaseType;
    private final String entityTypeName;
    private final String qualifiedName;
    private final String atlasBasePath;
    private final String message;
    private final List<Map<String, Object>> classifications;
    private final List<Map<String, Object>> glossaryTerms;
    private final List<String> labels;
    private final Map<String, Map<String, Object>> businessMetadata;

    public PurviewGovernanceView(
            boolean featureEnabled,
            boolean available,
            boolean supportedObjectType,
            boolean entityFound,
            String repositoryId,
            String objectId,
            String objectBaseType,
            String entityTypeName,
            String qualifiedName,
            String atlasBasePath,
            String message,
            List<Map<String, Object>> classifications,
            List<Map<String, Object>> glossaryTerms,
            List<String> labels,
            Map<String, Map<String, Object>> businessMetadata) {
        this.featureEnabled = featureEnabled;
        this.available = available;
        this.supportedObjectType = supportedObjectType;
        this.entityFound = entityFound;
        this.repositoryId = repositoryId;
        this.objectId = objectId;
        this.objectBaseType = objectBaseType;
        this.entityTypeName = entityTypeName;
        this.qualifiedName = qualifiedName;
        this.atlasBasePath = atlasBasePath;
        this.message = message;
        this.classifications = classifications;
        this.glossaryTerms = glossaryTerms;
        this.labels = labels;
        this.businessMetadata = businessMetadata;
    }

    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isSupportedObjectType() {
        return supportedObjectType;
    }

    public boolean isEntityFound() {
        return entityFound;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getObjectBaseType() {
        return objectBaseType;
    }

    public String getEntityTypeName() {
        return entityTypeName;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public String getAtlasBasePath() {
        return atlasBasePath;
    }

    public String getMessage() {
        return message;
    }

    public List<Map<String, Object>> getClassifications() {
        return classifications;
    }

    public List<Map<String, Object>> getGlossaryTerms() {
        return glossaryTerms;
    }

    public List<String> getLabels() {
        return labels;
    }

    public Map<String, Map<String, Object>> getBusinessMetadata() {
        return businessMetadata;
    }
}
