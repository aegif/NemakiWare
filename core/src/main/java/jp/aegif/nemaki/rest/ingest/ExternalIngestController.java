package jp.aegif.nemaki.rest.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST endpoint for canonical external ingestion.
 *
 * <p>Supports two content types:
 * <ul>
 *   <li>{@code application/json} — metadata-only import (no file content)</li>
 *   <li>{@code multipart/form-data} — file import with {@code content} part + {@code request} JSON part</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/repo/{repositoryId}/ingest")
public class ExternalIngestController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private CanonicalImportService canonicalImportService;

    @Autowired
    private HttpServletRequest httpRequest;

    @Autowired(required = false)
    private ConnectorDefinitionService connectorDefinitionService;

    /** JSON-only ingest (metadata-only, no file content). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExternalIngestResult> ingestJson(
            @PathVariable String repositoryId,
            @RequestBody ExternalIngestRequest request) {
        return doIngest(repositoryId, request);
    }

    /** Multipart ingest with file content. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExternalIngestResult> ingestMultipart(
            @PathVariable String repositoryId,
            @RequestPart("request") String requestJson,
            @RequestPart(value = "content", required = false) MultipartFile content) {
        try {
            ExternalIngestRequest request = MAPPER.readValue(requestJson, ExternalIngestRequest.class);
            if (content != null && !content.isEmpty()) {
                // Guard against oversized uploads (100MB default)
                if (content.getSize() > 100 * 1024 * 1024) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(ExternalIngestResult.error("unknown", "File exceeds maximum size (100MB)"));
                }
                request.setContentStream(content.getInputStream());
                if (request.getFileName() == null || request.getFileName().isBlank()) {
                    request.setFileName(sanitizeFilename(content.getOriginalFilename()));
                }
                if (request.getMimeType() == null || request.getMimeType().isBlank()) {
                    request.setMimeType(content.getContentType());
                }
            }
            return doIngest(repositoryId, request);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ExternalIngestResult.error("unknown", "Invalid request"));
        }
    }

    private ResponseEntity<ExternalIngestResult> doIngest(String repositoryId, ExternalIngestRequest request) {
        CallContext callContext = getCallContext();
        if (callContext == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        request.setRepositoryId(repositoryId);

        // Dispatch to specialized import flow based on connector archetype first,
        // then sourceObjectType, with .eml extension as a hint only for MESSAGE_CONTEXT
        ExternalIngestResult result;
        String sourceObjectType = request.getSourceObjectType();
        boolean isEml = request.getFileName() != null && request.getFileName().toLowerCase().endsWith(".eml");

        // Check connector archetype first — it takes precedence over filename
        SourceArchetype connectorArchetype = resolveConnectorArchetype(request.getConnectorId());

        if (connectorArchetype != null) {
            // Connector archetype is known — dispatch by archetype, not filename
            result = switch (connectorArchetype) {
                case MESSAGE_CONTEXT -> canonicalImportService.executeMailImport(callContext, request);
                case CHAT_CONTEXT -> canonicalImportService.executeChatContextImport(callContext, request);
                case COMPOUND_NOTE -> canonicalImportService.executeNoteImport(callContext, request);
                case BUSINESS_RECORD -> canonicalImportService.executeBusinessRecordImport(callContext, request);
                case FILE_SHARE -> canonicalImportService.execute(callContext, request);
            };
        } else if (isEml && !"file".equals(sourceObjectType)) {
            // No connector context + .eml extension + not explicitly typed as "file" → mail parser
            result = canonicalImportService.executeMailImport(callContext, request);
        } else if ("page".equals(sourceObjectType)) {
            result = canonicalImportService.executeNoteImport(callContext, request);
        } else if ("record".equals(sourceObjectType)) {
            result = canonicalImportService.executeBusinessRecordImport(callContext, request);
        } else if ("chat_message".equals(sourceObjectType) || "thread".equals(sourceObjectType)) {
            result = canonicalImportService.executeChatContextImport(callContext, request);
        } else if ("message".equals(sourceObjectType)) {
            // "message" could be mail or chat — check connector archetype to decide
            // Only route to mail parser if we can confirm MESSAGE_CONTEXT archetype
            result = resolveMessageImport(callContext, request);
        } else {
            result = canonicalImportService.execute(callContext, request);
        }
        if (result.isSuccess() || result.skipped() || result.dryRun()) {
            return ResponseEntity.ok(result);
        }
        // Map validation/config errors to appropriate HTTP status
        HttpStatus errorStatus = classifyErrorStatus(result);
        return ResponseEntity.status(errorStatus).body(result);
    }

    /**
     * Fallback dispatch for "message" sourceObjectType when connector archetype
     * was not resolved by the primary dispatch (connectorId absent or lookup failed).
     * Defaults to mail parser since "message" without archetype context is most
     * likely an email.
     */
    private ExternalIngestResult resolveMessageImport(CallContext callContext, ExternalIngestRequest request) {
        // connectorArchetype was already checked in doIngest() and returned null,
        // so no point re-looking up — default to mail
        return canonicalImportService.executeMailImport(callContext, request);
    }

    /** Look up the connector's archetype, returning null if unavailable. */
    private SourceArchetype resolveConnectorArchetype(String connectorId) {
        if (connectorId == null) return null;
        try {
            if (connectorDefinitionService != null) {
                ConnectorDefinition connector = connectorDefinitionService.get(connectorId);
                if (connector != null) return connector.getSourceArchetype();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ExternalIngestController.class)
                    .warn("Connector lookup failed for {}: {}", connectorId, e.getMessage());
        }
        return null;
    }

    private static HttpStatus classifyErrorStatus(ExternalIngestResult result) {
        if (result.errors() == null || result.errors().isEmpty()) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String firstError = result.errors().get(0);
        if (firstError == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        firstError = firstError.toLowerCase();
        if (firstError.contains("not found")) return HttpStatus.NOT_FOUND;
        if (firstError.contains("not allowed") || firstError.contains("scoped to repository")) return HttpStatus.FORBIDDEN;
        if (firstError.contains("disabled") || firstError.contains("is required")
                || firstError.contains("no resolvable")) return HttpStatus.BAD_REQUEST;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** Strip path separators, control characters, and parent-directory traversal from uploaded filenames. */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return name;
        // Remove null bytes and control characters (prevent injection)
        name = name.replaceAll("[\\x00-\\x1f\\x7f]", "");
        // Extract basename (after last / or \)
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        // Remove leading dots to prevent hidden files / traversal
        name = name.replaceAll("^\\.+", "");
        return name.isBlank() ? "imported-file" : name;
    }

    private CallContext getCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
    }
}
