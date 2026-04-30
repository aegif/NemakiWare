package jp.aegif.nemaki.rest.ingest.record;

import jp.aegif.nemaki.rest.ingest.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch orchestrator for Salesforce business-record adapter.
 */
public class SalesforceFetchOrchestrator implements FetchOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(SalesforceFetchOrchestrator.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private FetchSupport fetchSupport;
    private CheckpointManager checkpointManager;
    private CanonicalImportService canonicalImportService;

    public void setFetchSupport(FetchSupport fetchSupport) { this.fetchSupport = fetchSupport; }
    public void setCheckpointManager(CheckpointManager checkpointManager) { this.checkpointManager = checkpointManager; }
    public void setCanonicalImportService(CanonicalImportService canonicalImportService) { this.canonicalImportService = canonicalImportService; }

    @Override
    public String sourceSystem() { return "salesforce"; }

    @Override
    public FetchResult execute(CallContext callContext, ImportProfileDefinition profile,
                               ConnectorDefinition connector, Map<String, String> params, int limit) {
        String soql = params.getOrDefault("soql", "SELECT Id,Name,LastModifiedDate FROM Account LIMIT " + limit);

        String token = fetchSupport.resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Salesforce connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0, skipped = 0;
        try {
            var sf = new SalesforceConnectorAdapter(connector.getEndpoint(), token);
            // Ensure LastModifiedDate is in the SELECT clause for checkpoint tracking
            if (!soql.toLowerCase().contains("lastmodifieddate")) {
                int selectEnd = soql.toLowerCase().indexOf("select ") + 7;
                soql = soql.substring(0, selectEnd) + "LastModifiedDate, " + soql.substring(selectEnd);
                logger.info("Auto-added LastModifiedDate to SOQL for checkpoint tracking");
            }
            // Inject checkpoint filter into SOQL
            String lastModified = checkpointManager.loadSimpleCheckpoint(profile.getProfileId(), "salesforce");
            String effectiveSoql = soql;
            if (lastModified != null) {
                if (!lastModified.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z?")) {
                    logger.warn("Invalid Salesforce checkpoint format '{}', ignoring", lastModified);
                    lastModified = null;
                }
            }
            if (lastModified != null) {
                String lower = soql.toLowerCase();
                if (!lower.contains("where")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "\\b(limit|order\\s+by|group\\s+by)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(soql);
                    String whereClause = " WHERE LastModifiedDate > " + lastModified;
                    if (m.find()) {
                        effectiveSoql = soql.substring(0, m.start()) + whereClause + " " + soql.substring(m.start());
                    } else {
                        effectiveSoql = soql + whereClause;
                    }
                }
            }
            List<SalesforceConnectorAdapter.SalesforceRecord> records = sf.query(effectiveSoql);
            fetched = records.size();
            long throttleMs = FetchSupport.calculateThrottleDelayMs(connector);
            String highWaterModified = lastModified;

            for (var rec : records) {
                fetchSupport.throttle(throttleMs);
                try {
                    String json = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rec.fields());

                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(rec.id());
                    req.setSourceObjectType("record");
                    req.setFileName(FetchSupport.sanitizeSubject(rec.name()) + ".json");
                    req.setMimeType("application/json");
                    req.setContentStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                    req.setExecutionMode("scheduled");

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("recordType", rec.type());
                    metadata.put("recordId", rec.id());
                    metadata.put("recordUrl", connector.getEndpoint() + "/" + rec.id());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.executeBusinessRecordImport(callContext, req);
                    if (result.isSuccess() || result.skipped()) {
                        Object lmd = rec.fields().get("LastModifiedDate");
                        if (lmd instanceof String ts && (highWaterModified == null || ts.compareTo(highWaterModified) > 0)) {
                            highWaterModified = ts;
                        }
                        if (result.isSuccess()) imported++; else skipped++;
                    } else {
                        FetchSupport.addError(errors, "SF " + rec.id() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    FetchSupport.addError(errors, "SF record " + rec.id() + ": " + e.getMessage());
                }
            }
            if (highWaterModified != null && !highWaterModified.equals(lastModified)) {
                checkpointManager.saveSimpleCheckpoint(profile.getProfileId(), "salesforce", highWaterModified);
            }
        } catch (Exception e) {
            FetchSupport.addError(errors, "Salesforce connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, skipped, errors);
    }
}
