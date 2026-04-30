package jp.aegif.nemaki.rest.ingest;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Describes the contract for an external ingest adapter.
 *
 * <p>Each supported source system (slack, teams, gmail_mail, etc.) has one
 * descriptor that declares its identity, required/optional scheduler parameters,
 * webhook scope keys, and estimated API calls per item.
 *
 * <p>This replaces the free-text sourceSystem input and ad-hoc parameter
 * validation with a type-safe, schema-driven contract.  The registry is the
 * single source of truth consumed by:
 * <ul>
 *   <li>{@link ImportProfileDefinitionServiceImpl} — save-time validation</li>
 *   <li>{@link IngestWebhookController} — profile scope matching</li>
 *   <li>{@link IngestSchedulerService} — rate-limit callsPerItem</li>
 *   <li>UI (via REST API) — dynamic form generation</li>
 *   <li>Help page (via REST API) — parameter documentation</li>
 * </ul>
 */
public record AdapterDescriptor(
        /** Source system identifier (e.g., "slack", "teams", "gmail_mail"). */
        String sourceSystem,

        /** Display name for UI/Help (e.g., "Slack", "Microsoft Teams"). */
        String displayName,

        /** Archetype this adapter handles. */
        SourceArchetype archetype,

        /** Scheduler params that MUST be set when schedulerEnabled=true. */
        Set<String> requiredParams,

        /** Scheduler params that are optional (have defaults). */
        Set<String> optionalParams,

        /**
         * Keys in schedulerParams used for webhook scope matching.
         * When a webhook notification arrives, these keys are compared against
         * the notification resource to determine which profiles to trigger.
         */
        List<String> webhookScopeKeys,

        /** Estimated API calls per item for rate-limit calculation. */
        int apiCallsPerItem,

        /** Example schedulerParams JSON for documentation. */
        String paramsExample
) {

    /** All known param keys (required + optional). */
    public Set<String> allParams() {
        var all = new java.util.LinkedHashSet<>(requiredParams);
        all.addAll(optionalParams);
        return Set.copyOf(all);
    }

    /**
     * Validate that required params are present in the given map.
     *
     * @param params schedulerParams from the profile
     * @return list of validation errors (empty if valid)
     */
    public List<String> validateParams(Map<String, String> params) {
        if (requiredParams.isEmpty()) return List.of();
        var p = params != null ? params : Map.<String, String>of();
        var errors = new java.util.ArrayList<String>();
        for (String key : requiredParams) {
            String val = p.get(key);
            if (val == null || val.isBlank()) {
                errors.add("schedulerParams." + key + " is required for " + displayName + " adapter");
            }
        }
        return errors;
    }
}
