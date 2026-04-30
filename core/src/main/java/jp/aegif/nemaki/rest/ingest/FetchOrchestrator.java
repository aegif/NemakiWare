package jp.aegif.nemaki.rest.ingest;

import org.apache.chemistry.opencmis.commons.server.CallContext;

import java.util.Map;

/**
 * Adapter-specific fetch orchestrator.
 *
 * <p>Each implementation handles the complete fetch cycle for one source system:
 * connecting to the external API, iterating over items, importing via
 * CanonicalImportService, managing checkpoints, and collecting errors.
 */
public interface FetchOrchestrator {

    /** The source system this orchestrator handles (e.g. "box", "slack"). */
    String sourceSystem();

    /**
     * Execute a fetch cycle.
     *
     * @param callContext CMIS call context (may be null for scheduled runs)
     * @param profile     the import profile
     * @param connector   the connector definition
     * @param params      scheduler parameters from the profile
     * @param limit       maximum number of items to fetch
     * @return fetch result with counters and errors
     */
    FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                        ConnectorDefinition connector, Map<String, String> params, int limit);
}
