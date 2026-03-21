package jp.aegif.nemaki.rest.purview.schema;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifest;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewSchemaPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewSchemaRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaStateService;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class PurviewSchemaApplyServiceImpl implements PurviewSchemaApplyService {

    private final PurviewConfig purviewConfig;
    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewSchemaStateService schemaStateService;
    private final PurviewSchemaManifestFactory schemaManifestFactory;
    private final PurviewSchemaPayloadFactory schemaPayloadFactory;
    private final PurviewSchemaRegistryClient schemaRegistryClient;

    public PurviewSchemaApplyServiceImpl(
            PurviewConfig purviewConfig,
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewSchemaStateService schemaStateService,
            PurviewSchemaManifestFactory schemaManifestFactory,
            PurviewSchemaPayloadFactory schemaPayloadFactory,
            PurviewSchemaRegistryClient schemaRegistryClient) {
        this.purviewConfig = purviewConfig;
        this.schemaPlannerService = schemaPlannerService;
        this.schemaStateService = schemaStateService;
        this.schemaManifestFactory = schemaManifestFactory;
        this.schemaPayloadFactory = schemaPayloadFactory;
        this.schemaRegistryClient = schemaRegistryClient;
    }

    @Override
    public PurviewSchemaApplyResult applySchema(String appliedBy) {
        PurviewSchemaState currentState = schemaPlannerService.getCurrentSchemaState();
        PurviewSchemaDiff diff = schemaPlannerService.getSchemaDiff();
        if (!diff.isApplyRequired()) {
            return new PurviewSchemaApplyResult(false, "up to date", currentState, diff);
        }

        PurviewSchemaManifest manifest = schemaManifestFactory.buildManifest();
        Map<String, Object> payload = schemaPayloadFactory.buildTypeDefinitionsPayload(manifest);
        PurviewConnectionRequest request = new PurviewConnectionRequest(
                purviewConfig.getEndpoint(),
                purviewConfig.getAtlasBasePath(),
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getConnectTimeoutMs(),
                purviewConfig.getReadTimeoutMs());

        try {
            PurviewSchemaPublishResult publishResult = schemaRegistryClient.applySchema(request, payload);
            if (!publishResult.isSuccess()) {
                return new PurviewSchemaApplyResult(false, publishResult.getMessage(), currentState, diff);
            }

            PurviewSchemaState nextState = schemaStateService.saveSchemaState(new PurviewSchemaState(
                    purviewConfig.getCollection(),
                    manifest.getSchemaVersion(),
                    manifest.getSchemaHash(),
                    Instant.now().toString(),
                    normalizeAppliedBy(appliedBy),
                    buildDiffSummary(diff)));
            return new PurviewSchemaApplyResult(true, publishResult.getMessage(), nextState, diff);
        } catch (PurviewClientException e) {
            return new PurviewSchemaApplyResult(false, e.getMessage(), currentState, diff);
        }
    }

    private String normalizeAppliedBy(String appliedBy) {
        return appliedBy == null || appliedBy.isBlank() ? "system" : appliedBy;
    }

    private String buildDiffSummary(PurviewSchemaDiff diff) {
        return "customTypes=" + diff.getCustomTypeNames().size()
                + ", relationshipTypes=" + diff.getRelationshipTypeNames().size()
                + ", businessMetadataDefs=" + diff.getBusinessMetadataNames().size();
    }
}
