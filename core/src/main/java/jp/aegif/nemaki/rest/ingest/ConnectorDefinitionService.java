package jp.aegif.nemaki.rest.ingest;

import java.util.List;

/**
 * CRUD service for connector definitions stored in CouchDB nemaki_conf.
 */
public interface ConnectorDefinitionService {
    ConnectorDefinition create(ConnectorDefinition def);
    ConnectorDefinition get(String connectorId);
    List<ConnectorDefinition> list();
    List<ConnectorDefinition> listByArchetype(SourceArchetype archetype);
    ConnectorDefinition update(ConnectorDefinition def);
    void delete(String connectorId);
    boolean exists(String connectorId);
}
