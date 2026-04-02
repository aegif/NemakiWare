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

    /**
     * Finds the first enabled profile for the given repository that allows the specified archetype.
     * Used for auto-resolution when the caller does not explicitly specify a profileId.
     *
     * @return matching profile, or null if none found
     */
    /**
     * Finds the first enabled profile for the given repository that allows the specified
     * archetype AND the specified connector.
     */
    ImportProfileDefinition findDefaultForRepository(String repositoryId, SourceArchetype archetype, String connectorId);
}
