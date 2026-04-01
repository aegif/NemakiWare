package jp.aegif.nemaki.rest.ingest;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoint for canonical external ingestion.
 * Accepts an {@link ExternalIngestRequest} and delegates to {@link CanonicalImportService}.
 */
@RestController
@RequestMapping("/v1/repo/{repositoryId}/ingest")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ExternalIngestController {

    @Autowired
    private CanonicalImportService canonicalImportService;

    @Autowired
    private HttpServletRequest httpRequest;

    @PostMapping
    public ResponseEntity<ExternalIngestResult> ingest(
            @PathVariable String repositoryId,
            @RequestBody ExternalIngestRequest request) {
        CallContext callContext = getCallContext();
        if (callContext == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        request.setRepositoryId(repositoryId);
        ExternalIngestResult result = canonicalImportService.execute(callContext, request);

        if (result.isSuccess() || result.skipped() || result.dryRun()) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
    }

    private CallContext getCallContext() {
        if (httpRequest == null) return null;
        return (CallContext) httpRequest.getAttribute("CallContext");
    }
}
