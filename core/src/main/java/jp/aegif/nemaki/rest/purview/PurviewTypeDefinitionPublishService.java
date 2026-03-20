package jp.aegif.nemaki.rest.purview;

public interface PurviewTypeDefinitionPublishService {

    int publishRepositoryTypeDefinitions(String repositoryId);

    String buildRepositoryTypeDefinitionSnapshot(String repositoryId);

    PurviewTypeDefinitionSyncResult syncRepositoryTypeDefinitionsIfChanged(String repositoryId, String previousSnapshot);
}
