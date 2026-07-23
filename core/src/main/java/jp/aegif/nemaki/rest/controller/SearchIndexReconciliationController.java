package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask;
import jp.aegif.nemaki.reconcile.SearchIndexReconciliationService;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST API for the search-index ACL reconciliation queue — observe / retry
 * / delete the durable tasks recorded when an asynchronous
 * {@code AclService} search-index refresh (readers / RAG / relationships) failed.
 *
 * <p>Under {@code /v1/admin/*} (Spring MVC), so admin-gated and CSRF-protected by
 * the shared {@code CsrfInterceptor}.
 */
@RestController
@RequestMapping("/v1/admin/search-index/reconcile")
public class SearchIndexReconciliationController {

    @Autowired(required = false)
    private SearchIndexReconciliationService reconciliationService;

    @Autowired(required = false)
    private AclService aclService;

    @Autowired
    private HttpServletRequest httpRequest;

    /** List reconciliation tasks (default all; {@code status=PENDING|FAILED} to filter). */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String status) {
        if (!isAdmin()) return forbidden();
        if (reconciliationService == null) return unavailable();
        List<SearchIndexAclReindexTask> all = reconciliationService.list(limit);
        List<SearchIndexAclReindexTask> filtered = (status == null || status.isBlank())
                ? all
                : all.stream().filter(t -> status.equalsIgnoreCase(t.getStatus())).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", filtered.size());
        body.put("tasks", filtered);
        return ResponseEntity.ok(body);
    }

    /** Force an immediate re-drive of one task; on a clean re-drive the task is removed. */
    @PostMapping("/{taskId}/retry")
    public ResponseEntity<?> retry(@PathVariable String taskId) {
        if (!isAdmin()) return forbidden();
        if (reconciliationService == null || aclService == null) return unavailable();
        SearchIndexAclReindexTask task = reconciliationService.getByTaskId(taskId);
        if (task == null) {
            return error(HttpStatus.NOT_FOUND, "Reconciliation task not found: " + taskId);
        }
        boolean clean;
        try {
            clean = aclService.reindexSearchIndexAclForObject(task.getRepositoryId(), task.getObjectId());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Re-drive failed: " + e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (clean) {
            reconciliationService.delete(taskId);
            body.put("status", "reconciled");
        } else {
            // reserveForRetry bumps attempts + pushes the backoff; keep the task.
            reconciliationService.reserveForRetry(task, 0L);
            body.put("status", "still-failing");
        }
        body.put("taskId", taskId);
        body.put("objectId", task.getObjectId());
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> delete(@PathVariable String taskId) {
        if (!isAdmin()) return forbidden();
        if (reconciliationService == null) return unavailable();
        reconciliationService.delete(taskId);
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    // ── Internal ───────────────────────────────────────────────────

    private boolean isAdmin() {
        if (httpRequest == null) return false;
        CallContext ctx = (CallContext) httpRequest.getAttribute("CallContext");
        if (ctx == null) return false;
        Boolean admin = (Boolean) ctx.get(CallContextKey.IS_ADMIN);
        return admin != null && admin;
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("status", "error", "message", "Admin access required"));
    }

    private ResponseEntity<?> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "error", "message", "Reconciliation service not available"));
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "error", "message", message));
    }
}
