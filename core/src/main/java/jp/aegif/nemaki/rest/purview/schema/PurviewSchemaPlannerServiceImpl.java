package jp.aegif.nemaki.rest.purview.schema;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifest;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaStateService;
import org.springframework.stereotype.Service;

@Service
public class PurviewSchemaPlannerServiceImpl implements PurviewSchemaPlannerService {

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewSchemaStateService schemaStateService;
    private final PurviewSchemaManifestFactory schemaManifestFactory;

    public PurviewSchemaPlannerServiceImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewSchemaStateService schemaStateService,
            PurviewSchemaManifestFactory schemaManifestFactory) {
        this.connectionResolver = connectionResolver;
        this.schemaStateService = schemaStateService;
        this.schemaManifestFactory = schemaManifestFactory;
    }

    @Override
    public PurviewSchemaState getCurrentSchemaState() {
        return schemaStateService.getSchemaState(connectionResolver.getCollection());
    }

    @Override
    public PurviewSchemaDiff getSchemaDiff() {
        PurviewSchemaManifest manifest = schemaManifestFactory.buildManifest();
        PurviewSchemaState currentState = getCurrentSchemaState();

        boolean applyRequired = !manifest.getSchemaVersion().equals(currentState.getSchemaVersion())
                || !manifest.getSchemaHash().equals(currentState.getSchemaHash());

        return new PurviewSchemaDiff(
                connectionResolver.getCollection(),
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
