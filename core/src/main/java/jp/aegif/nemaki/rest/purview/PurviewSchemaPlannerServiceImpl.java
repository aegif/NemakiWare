package jp.aegif.nemaki.rest.purview;

import org.springframework.stereotype.Service;

@Service
public class PurviewSchemaPlannerServiceImpl implements PurviewSchemaPlannerService {

    private final PurviewConfig purviewConfig;
    private final PurviewSchemaStateService schemaStateService;
    private final PurviewSchemaManifestFactory schemaManifestFactory;

    public PurviewSchemaPlannerServiceImpl(
            PurviewConfig purviewConfig,
            PurviewSchemaStateService schemaStateService,
            PurviewSchemaManifestFactory schemaManifestFactory) {
        this.purviewConfig = purviewConfig;
        this.schemaStateService = schemaStateService;
        this.schemaManifestFactory = schemaManifestFactory;
    }

    @Override
    public PurviewSchemaState getCurrentSchemaState() {
        return schemaStateService.getSchemaState(purviewConfig.getCollection());
    }

    @Override
    public PurviewSchemaDiff getSchemaDiff() {
        PurviewSchemaManifest manifest = schemaManifestFactory.buildManifest();
        PurviewSchemaState currentState = getCurrentSchemaState();

        boolean applyRequired = !manifest.getSchemaVersion().equals(currentState.getSchemaVersion())
                || !manifest.getSchemaHash().equals(currentState.getSchemaHash());

        return new PurviewSchemaDiff(
                purviewConfig.getCollection(),
                currentState.getSchemaVersion(),
                currentState.getSchemaHash(),
                manifest.getSchemaVersion(),
                manifest.getSchemaHash(),
                applyRequired,
                manifest.getCustomTypeNames(),
                manifest.getRelationshipTypeNames(),
                manifest.getBusinessMetadataNames());
    }
}
