package jp.aegif.nemaki.rest.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of FetchOrchestrator instances keyed by sourceSystem.
 *
 * <p>Separate from AdapterRegistry (which holds static metadata).
 * This registry holds live bean instances with DI dependencies,
 * and is injected into IngestSchedulerService to replace the
 * hardcoded switch statement in executeFetch.
 */
public class FetchOrchestratorRegistry {

    private Map<String, FetchOrchestrator> orchestrators = new LinkedHashMap<>();

    /** Set the orchestrator map (injected via Spring XML). */
    public void setOrchestrators(Map<String, FetchOrchestrator> orchestrators) {
        this.orchestrators = orchestrators;
    }

    /** Look up an orchestrator by source system name. */
    public FetchOrchestrator get(String sourceSystem) {
        return orchestrators.get(sourceSystem);
    }

    /** Check if an orchestrator is registered for the given source system. */
    public boolean isRegistered(String sourceSystem) {
        return orchestrators.containsKey(sourceSystem);
    }
}
