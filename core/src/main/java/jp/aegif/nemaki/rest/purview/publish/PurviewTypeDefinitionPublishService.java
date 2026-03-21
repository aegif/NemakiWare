package jp.aegif.nemaki.rest.purview.publish;


import jp.aegif.nemaki.rest.purview.sync.PurviewTypeDefinitionSyncResult;
public interface PurviewTypeDefinitionPublishService {

    int publishRepositoryTypeDefinitions(String repositoryId);

    String buildRepositoryTypeDefinitionSnapshot(String repositoryId);

    PurviewTypeDefinitionSyncResult syncRepositoryTypeDefinitionsIfChanged(String repositoryId, String previousSnapshot);
}
