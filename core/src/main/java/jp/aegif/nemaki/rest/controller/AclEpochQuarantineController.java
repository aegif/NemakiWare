package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.epoch.AclEffectiveEpochService;
import jp.aegif.nemaki.epoch.AclEpochFinalizationService;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin REST API for the ACL-epoch DURABLE QUARANTINE (design §5.1, wiring gate 4).
 *
 * <p>A document whose epoch fields are corrupt is moved to quarantine so the scanner makes
 * progress instead of retrying it forever. Quarantine is deliberately CONTAGIOUS: an effective-epoch
 * walk that reaches a quarantined ANCESTOR refuses to compute readers, so every descendant's
 * reconciliation is blocked too. §5.1 rejects the "just read the epoch from the quarantined
 * document" shortcut — a corrupt epoch is exactly what must not be trusted — so the ONLY exit is a
 * human repairing the one document. This API is that exit; without it the §5.1 contract would be a
 * capability with no operator in it.
 *
 * <p>Two operations, matching the two halves of §5.1:
 * <ul>
 *   <li>{@code GET} — WHICH document to repair. The blocked walks are counted and the distinct
 *       blocking ids listed, so an operator reads a handful of ids rather than a thousand identical
 *       failures.</li>
 *   <li>{@code POST .../repair} — repair that document: its epoch fields are normalized and the
 *       marker cleared in ONE {@code _rev} CAS. Tasks blocked by it were RETAINED under capped
 *       backoff, so they resume on their own — there is no re-enqueue step.</li>
 * </ul>
 *
 * <p><b>Reported counts are per-JVM and reset on restart</b> (they count what THIS replica's walks
 * hit), so in a multi-replica deployment poll every replica. The repair itself is durable and
 * global — it is a CouchDB write.
 *
 * <p><b>Scope.</b> This exposes the quarantine EXIT only. Driving the recovery scanner and the
 * repository-wide initial-epoch migration are a separate admin surface, still outstanding with
 * wiring gate 2; and because no production path calls the epoch walk yet, a healthy production
 * deployment reports zeros here today. That is the intended order: the operational contract must
 * exist BEFORE the ACL write paths are wired to it.
 *
 * <p>Under {@code /v1/admin/*} (Spring MVC), so admin-gated and CSRF-protected by the shared
 * {@code CsrfInterceptor}.
 */
@RestController
@RequestMapping("/v1/admin/acl-epoch/quarantine")
public class AclEpochQuarantineController {

    @Autowired(required = false)
    private AclEffectiveEpochService effectiveEpochService;

    @Autowired(required = false)
    private AclEpochFinalizationService finalizationService;

    @Autowired
    private HttpServletRequest httpRequest;

    /**
     * Which quarantined documents are blocking ACL-index refreshes, and how many they blocked.
     * Empty when nothing is blocked — the healthy case.
     */
    @GetMapping
    public ResponseEntity<?> blockers() {
        if (!isAdmin()) return forbidden();
        if (effectiveEpochService == null) return unavailable();
        Map<String, Object> body = new LinkedHashMap<>(effectiveEpochService.quarantineMetrics());
        body.put("perJvm", true);
        body.put("hint", "POST {repositoryId}/{docId}/repair for each id under quarantineBlockingIds; "
                + "blocked reconciliation tasks are retained and resume automatically");
        return ResponseEntity.ok(body);
    }

    /**
     * Repair ONE quarantined document (single CAS). Returns {@code repaired} or
     * {@code not-quarantined} (already repaired / never was — deliberately not a 404, since the
     * operator's goal state is reached either way).
     *
     * <p>On success the in-JVM blocker record for the id is forgotten, so a LATER re-quarantine of
     * the same document logs and counts afresh instead of being deduped against the repaired one.
     */
    @PostMapping("/{repositoryId}/{docId}/repair")
    public ResponseEntity<?> repair(@PathVariable String repositoryId, @PathVariable String docId) {
        if (!isAdmin()) return forbidden();
        if (finalizationService == null) return unavailable();
        AclEpochFinalizationService.RepairResult result;
        try {
            result = finalizationService.repairQuarantined(repositoryId, docId);
        } catch (AclEpochFinalizationService.AclEpochContentionException e) {
            // The marker is RETAINED — nothing was half-applied. Retryable, so 409 not 500.
            return error(HttpStatus.CONFLICT, "Repair did not converge under contention (the "
                    + "quarantine marker is retained, nothing was half-applied) — retry: " + e.getMessage());
        } catch (jp.aegif.nemaki.epoch.AclEpochWiringException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (result == AclEpochFinalizationService.RepairResult.REPAIRED
                && effectiveEpochService != null) {
            effectiveEpochService.forgetQuarantineBlocker(docId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result == AclEpochFinalizationService.RepairResult.REPAIRED
                ? "repaired" : "not-quarantined");
        body.put("repositoryId", repositoryId);
        body.put("docId", docId);
        return ResponseEntity.ok(body);
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
                .body(Map.of("status", "error", "message", "ACL-epoch service not available"));
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "error", "message", message));
    }
}
