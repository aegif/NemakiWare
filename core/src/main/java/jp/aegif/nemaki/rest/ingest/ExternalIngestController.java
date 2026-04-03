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
@CrossOrigin(origins = "*", maxAge = 3600)
public class ExternalIngestController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private CanonicalImportService canonicalImportService;

    @Autowired
    private HttpServletRequest httpRequest;

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
                    .body(ExternalIngestResult.error("unknown", "Invalid request: " + e.getMessage()));
        }
    }

    private ResponseEntity<ExternalIngestResult> doIngest(String repositoryId, ExternalIngestRequest request) {
        CallContext callContext = getCallContext();
        if (callContext == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        request.setRepositoryId(repositoryId);

        // Auto-detect specialized import flows
        ExternalIngestResult result;
        String sourceObjectType = request.getSourceObjectType();
        boolean hasContent = request.getContentStream() != null;
        boolean isEml = request.getFileName() != null && request.getFileName().toLowerCase().endsWith(".eml");

        if (isEml || ("message".equals(sourceObjectType) && hasContent)) {
            result = canonicalImportService.executeMailImport(callContext, request);
        } else if ("page".equals(sourceObjectType)) {
            result = canonicalImportService.executeNoteImport(callContext, request);
        } else if ("record".equals(sourceObjectType)) {
            result = canonicalImportService.executeBusinessRecordImport(callContext, request);
        } else if ("chat_message".equals(sourceObjectType) || "thread".equals(sourceObjectType)) {
            result = canonicalImportService.executeChatContextImport(callContext, request);
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

    private static HttpStatus classifyErrorStatus(ExternalIngestResult result) {
        if (result.errors() == null || result.errors().isEmpty()) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String firstError = result.errors().get(0).toLowerCase();
        if (firstError.contains("not found")) return HttpStatus.NOT_FOUND;
        if (firstError.contains("not allowed") || firstError.contains("scoped to repository")) return HttpStatus.FORBIDDEN;
        if (firstError.contains("disabled") || firstError.contains("is required")
                || firstError.contains("no resolvable")) return HttpStatus.BAD_REQUEST;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** Strip path separators and parent-directory traversal from uploaded filenames. */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return name;
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
