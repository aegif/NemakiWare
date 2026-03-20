package jp.aegif.nemaki.rest.purview;

public class PurviewTombstoneState {

    private final String repositoryId;
    private final String objectId;
    private final String typeName;
    private final String qualifiedName;
    private final String changeToken;
    private final String firstSeenAt;
    private final String dueAt;
    private final String status;

    public PurviewTombstoneState(
            String repositoryId,
            String objectId,
            String typeName,
            String qualifiedName,
            String changeToken,
            String firstSeenAt,
            String dueAt,
            String status) {
        this.repositoryId = repositoryId;
        this.objectId = objectId;
        this.typeName = typeName;
        this.qualifiedName = qualifiedName;
        this.changeToken = changeToken;
        this.firstSeenAt = firstSeenAt;
        this.dueAt = dueAt;
        this.status = status;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public String getChangeToken() {
        return changeToken;
    }

    public String getFirstSeenAt() {
        return firstSeenAt;
    }

    public String getDueAt() {
        return dueAt;
    }

    public String getStatus() {
        return status;
    }
}
