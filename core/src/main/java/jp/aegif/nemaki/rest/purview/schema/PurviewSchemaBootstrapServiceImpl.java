package jp.aegif.nemaki.rest.purview.schema;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
import jp.aegif.nemaki.rest.purview.state.PurviewJobStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewLockStateService;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class PurviewSchemaBootstrapServiceImpl implements PurviewSchemaBootstrapService {

    private static final String JOB_KIND = "TYPE_BOOTSTRAP";
    private static final String COLLECTION_SCOPE_PREFIX = "collection:";

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewSchemaApplyService schemaApplyService;
    private final PurviewJobStateService jobStateService;
    private final PurviewLockStateService lockStateService;

    public PurviewSchemaBootstrapServiceImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewSchemaApplyService schemaApplyService,
            PurviewJobStateService jobStateService,
            PurviewLockStateService lockStateService) {
        this.connectionResolver = connectionResolver;
        this.schemaApplyService = schemaApplyService;
        this.jobStateService = jobStateService;
        this.lockStateService = lockStateService;
    }

    @Override
    public PurviewSchemaBootstrapResult startTypeBootstrap(String requestedBy) {
        String now = Instant.now().toString();
        String jobId = UUID.randomUUID().toString();
        String collectionScope = buildCollectionScope();

        if (!lockStateService.tryAcquireRepositoryLock(collectionScope, JOB_KIND, jobId, now)) {
            PurviewJobState rejectedJob = saveJob(new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    collectionScope,
                    "REJECTED",
                    now,
                    now,
                    0,
                    0,
                    "",
                    "Purview schema bootstrap is already running for collection " + connectionResolver.getCollection()));
            PurviewSchemaApplyResult rejectedApply = new PurviewSchemaApplyResult(
                    false,
                    rejectedJob.getErrorSummary(),
                    null,
                    null);
            return new PurviewSchemaBootstrapResult(rejectedJob, rejectedApply);
        }

        try {
            PurviewSchemaApplyResult applyResult = schemaApplyService.applySchema(requestedBy);
            PurviewJobState jobState = saveJob(buildJobState(jobId, collectionScope, now, applyResult));
            return new PurviewSchemaBootstrapResult(jobState, applyResult);
        } finally {
            lockStateService.releaseRepositoryLock(collectionScope, JOB_KIND, jobId);
        }
    }

    private PurviewJobState buildJobState(
            String jobId,
            String collectionScope,
            String now,
            PurviewSchemaApplyResult applyResult) {
        if (applyResult.isApplied()) {
            return new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    collectionScope,
                    "COMPLETED",
                    now,
                    now,
                    countDefinitions(applyResult.getSchemaDiff()),
                    0,
                    resolveCheckpoint(applyResult),
                    "");
        }

        if (isUpToDate(applyResult)) {
            return new PurviewJobState(
                    jobId,
                    JOB_KIND,
                    collectionScope,
                    "COMPLETED",
                    now,
                    now,
                    0,
                    0,
                    resolveCheckpoint(applyResult),
                    "");
        }

        return new PurviewJobState(
                jobId,
                JOB_KIND,
                collectionScope,
                "FAILED",
                now,
                now,
                0,
                1,
                resolveCheckpoint(applyResult),
                applyResult.getMessage());
    }

    private PurviewJobState saveJob(PurviewJobState jobState) {
        return jobStateService.saveJobState(jobState);
    }

    private boolean isUpToDate(PurviewSchemaApplyResult applyResult) {
        return applyResult != null
                && !applyResult.isApplied()
                && applyResult.getSchemaDiff() != null
                && !applyResult.getSchemaDiff().isApplyRequired();
    }

    private int countDefinitions(PurviewSchemaDiff diff) {
        if (diff == null) {
            return 0;
        }
        return diff.getCustomTypeNames().size()
                + diff.getRelationshipTypeNames().size()
                + diff.getBusinessMetadataNames().size();
    }

    private String resolveCheckpoint(PurviewSchemaApplyResult applyResult) {
        if (applyResult == null) {
            return "";
        }
        if (applyResult.getSchemaState() != null
                && applyResult.getSchemaState().getSchemaHash() != null
                && !applyResult.getSchemaState().getSchemaHash().isBlank()) {
            return applyResult.getSchemaState().getSchemaHash();
        }
        if (applyResult.getSchemaDiff() != null
                && applyResult.getSchemaDiff().getCurrentSchemaHash() != null) {
            return applyResult.getSchemaDiff().getCurrentSchemaHash();
        }
        return "";
    }

    private String buildCollectionScope() {
        return COLLECTION_SCOPE_PREFIX + connectionResolver.getCollection();
    }
}
