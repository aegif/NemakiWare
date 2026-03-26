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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PurviewSchemaApplyServiceImpl implements PurviewSchemaApplyService {

    private static final Logger logger = LoggerFactory.getLogger(PurviewSchemaApplyServiceImpl.class);
    static final String ATLAS_PARTIAL_SUFFIX = ":atlas-partial";

    private final PurviewConfig purviewConfig;
    private final PurviewSchemaPlannerService schemaPlannerService;
    private final PurviewSchemaStateService schemaStateService;
    private final PurviewSchemaManifestFactory schemaManifestFactory;
    private final PurviewSchemaPayloadFactory schemaPayloadFactory;
    private final PurviewSchemaRegistryClient schemaRegistryClient;
    private final AtlasSchemaCompatHelper atlasSchemaCompatHelper;

    public PurviewSchemaApplyServiceImpl(
            PurviewConfig purviewConfig,
            PurviewSchemaPlannerService schemaPlannerService,
            PurviewSchemaStateService schemaStateService,
            PurviewSchemaManifestFactory schemaManifestFactory,
            PurviewSchemaPayloadFactory schemaPayloadFactory,
            PurviewSchemaRegistryClient schemaRegistryClient,
            AtlasSchemaCompatHelper atlasSchemaCompatHelper) {
        this.purviewConfig = purviewConfig;
        this.schemaPlannerService = schemaPlannerService;
        this.schemaStateService = schemaStateService;
        this.schemaManifestFactory = schemaManifestFactory;
        this.schemaPayloadFactory = schemaPayloadFactory;
        this.schemaRegistryClient = schemaRegistryClient;
        this.atlasSchemaCompatHelper = atlasSchemaCompatHelper;
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
                purviewConfig.getAuthType(),
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getBasicUsername(),
                purviewConfig.getBasicPassword(),
                purviewConfig.getConnectTimeoutMs(),
                purviewConfig.getReadTimeoutMs());

        try {
            // Atlas mode: patch businessMetadataDefs for compatibility
            if (purviewConfig.isAtlasOnPrem()) {
                payload = atlasSchemaCompatHelper.patchForAtlas(payload);
            }

            boolean usedAtlasFallback = false;
            PurviewSchemaPublishResult publishResult = schemaRegistryClient.applySchema(request, payload);

            // Atlas fallback: retry without businessMetadataDefs if first attempt failed
            if (!publishResult.isSuccess() && purviewConfig.isAtlasOnPrem()) {
                logger.warn("Atlas schema apply failed, retrying without businessMetadataDefs: {}",
                        publishResult.getMessage());
                Map<String, Object> fallback = atlasSchemaCompatHelper.removeBusinessMetadataDefs(payload);
                publishResult = schemaRegistryClient.applySchema(request, fallback);
                if (publishResult.isSuccess()) {
                    usedAtlasFallback = true;
                }
            }

            if (!publishResult.isSuccess()) {
                return new PurviewSchemaApplyResult(false, publishResult.getMessage(), currentState, diff);
            }

            // When Atlas fallback was used, save a partial hash so the next diff
            // check still triggers re-apply (the full schema was not installed).
            String schemaHash = usedAtlasFallback
                    ? manifest.getSchemaHash() + ATLAS_PARTIAL_SUFFIX
                    : manifest.getSchemaHash();
            String diffSummary = usedAtlasFallback
                    ? buildDiffSummary(diff) + " [atlas-partial: businessMetadataDefs skipped]"
                    : buildDiffSummary(diff);
            if (usedAtlasFallback) {
                logger.info("Atlas schema partially applied (businessMetadataDefs skipped). "
                        + "Will re-attempt full schema on next startup.");
            }

            PurviewSchemaState nextState = schemaStateService.saveSchemaState(new PurviewSchemaState(
                    purviewConfig.getCollection(),
                    manifest.getSchemaVersion(),
                    schemaHash,
                    Instant.now().toString(),
                    normalizeAppliedBy(appliedBy),
                    diffSummary));
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
