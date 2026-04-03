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

    /**
     * Auto-resolve connector and profile by sourceSystem and archetype, then execute.
     * Used by legacy code paths (e.g. CloudDriveResource) that don't have explicit
     * connector/profile IDs.
     *
     * @param callContext CMIS call context
     * @param request     the ingest request (profileId and connectorId may be null)
     * @param sourceSystem canonical source system name (e.g. "google", "microsoft")
     * @param archetype    source archetype for auto-resolution
     * @return result with created objectId, or error if auto-resolution fails
     */
    ExternalIngestResult executeWithAutoResolve(CallContext callContext, ExternalIngestRequest request,
                                                String sourceSystem, SourceArchetype archetype);

    /**
     * Import an .eml file: parse MIME, create message document + attachment documents,
     * apply nemaki:messageMetadata, create relationships.
     *
     * @param callContext CMIS context
     * @param request     ingest request with .eml contentStream
     * @return result for the message document (attachments imported as side effects)
     */
    ExternalIngestResult executeMailImport(CallContext callContext, ExternalIngestRequest request);
}
