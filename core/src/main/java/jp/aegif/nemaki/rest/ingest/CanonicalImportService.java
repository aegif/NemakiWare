package jp.aegif.nemaki.rest.ingest;

import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Canonical import pipeline for external content ingestion.
 *
 * <p>All entry points (UI, API, scheduler) converge through this service.
 * The pipeline handles:
 * <ol>
 *   <li>Profile + connector resolution and validation</li>
 *   <li>Dedupe check (sourceSystem + sourceObjectType + sourceObjectId)</li>
 *   <li>Document creation or version update</li>
 *   <li>Secondary type attachment (externalIntegration + archetype-specific)</li>
 *   <li>Relationship generation (if applicable)</li>
 *   <li>Lineage event emission</li>
 * </ol>
 */
public interface CanonicalImportService {

    /**
     * Execute the canonical import pipeline.
     *
     * @param callContext CMIS call context for authentication/authorization
     * @param request     the ingest request
     * @return result with created objectId, lineage eventId, or errors
     */
    ExternalIngestResult execute(CallContext callContext, ExternalIngestRequest request);
}
