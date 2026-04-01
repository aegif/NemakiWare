package jp.aegif.nemaki.rest.ingest;

import java.util.List;

/**
 * CRUD service for import profile definitions stored in CouchDB nemaki_conf.
 */
public interface ImportProfileDefinitionService {
    ImportProfileDefinition create(ImportProfileDefinition def);
    ImportProfileDefinition get(String profileId);
    List<ImportProfileDefinition> list();
    List<ImportProfileDefinition> listByRepository(String repositoryId);
    ImportProfileDefinition update(ImportProfileDefinition def);
    void delete(String profileId);
    boolean exists(String profileId);
}
