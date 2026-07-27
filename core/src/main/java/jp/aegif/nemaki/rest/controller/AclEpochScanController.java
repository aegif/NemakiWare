package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.epoch.AclEpochFinalizationService;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin REST API for driving the ACL-epoch OUTBOX by hand — the crash-recovery scanner (design §3)
 * and the direct Phase-2 finalizer (§2.2).
 *
 * <p><b>Why this exists before wiring.</b> The outbox is what makes an ACL mutation's epoch survive
 * a crash: Phase 2 allocates the epoch post-commit, and the scanner sweeps up whatever a crash left
 * behind — a {@code PENDING_EPOCH} never finalized, a {@code FINALIZED_NEEDS_RECONCILE} whose
 * obligation was never made durable, a document whose state was lost while its mutation id survived.
 * Without something driving it, those states are permanent. Wiring the writer without a way to run
 * the scanner would therefore ship a mechanism whose recovery half has no operator in it — the same
 * gap the quarantine repair and the migration runner already closed for their halves.
 *
 * <p><b>ONE endpoint covers all three operations</b> the outbox needs: {@code scan} runs the
 * finalize pass, the state-lost pass, the ACK pass and the terminal audit in one bounded sweep, so
 * {@code finalizePending} and {@code ackFinalized} are both exercised by it. The per-document
 * finalize is offered separately because an operator investigating ONE stuck document should not
 * have to sweep a repository to act on it.
 *
 * <p><b>Deliberately NOT here:</b>
 * <ul>
 *   <li><b>No cron, no init-method, no scheduler.</b> Every run is an explicit admin request. A
 *       background sweeper mutating content documents belongs with the wiring increment, where it
 *       can be designed against a system that actually produces epoch state.</li>
 *   <li><b>No endpoint for {@code clearMarkerAfterReconcile}.</b> It stays a capability with no
 *       caller: clearing a marker belongs to the reconcile COMPLETION path, and connecting it there
 *       is wiring, not operations.</li>
 * </ul>
 *
 * <p><b>What a scan does on today's production system: nothing.</b> No production path creates epoch
 * state, so every selector is empty and the summary is all zeros. That is the point — the operator
 * surface is in place and observable BEFORE the state it manages starts existing.
 *
 * <p>Under {@code /v1/admin/*} (Spring MVC), so admin-gated and CSRF-protected by the shared
 * {@code CsrfInterceptor}.
 */
@RestController
@RequestMapping("/v1/admin/acl-epoch")
public class AclEpochScanController {

    @Autowired(required = false)
    private AclEpochFinalizationService finalizationService;

    @Autowired(required = false)
    private jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repositoryInfoMap;

    @Autowired
    private HttpServletRequest httpRequest;

    // Setters so the guards can be unit-tested without a Spring context (the idiom used by
    // PurviewAdminController).
    public void setFinalizationService(AclEpochFinalizationService s) { this.finalizationService = s; }
    public void setRepositoryInfoMap(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap m) {
        this.repositoryInfoMap = m;
    }
    public void setHttpRequest(HttpServletRequest r) { this.httpRequest = r; }

    /** Repositories with a scan in flight — a second concurrent sweep is safe but reports nonsense. */
    private final Set<String> scanning = ConcurrentHashMap.newKeySet();

    /** The last summary per repository, so a completed sweep stays readable. */
    private final Map<String, Map<String, Object>> lastScan = new ConcurrentHashMap<>();

    /**
     * Run ONE bounded recovery sweep. Synchronous: the pass budget bounds it, and {@code more=true}
     * in the response is the signal to run it again rather than a reason to make it asynchronous.
     */
    @PostMapping("/scan/{repositoryId}")
    public ResponseEntity<?> scan(@PathVariable String repositoryId,
            @RequestParam(defaultValue = "0") int maxDocsPerPass) {
        if (!isAdmin()) return forbidden();
        if (finalizationService == null) return unavailable();
        ResponseEntity<?> bad = rejectUnknownRepository(repositoryId);
        if (bad != null) return bad;
        if (!scanning.add(repositoryId)) {
            return error(HttpStatus.CONFLICT, "A scan is already running for " + repositoryId);
        }
        try {
            AclEpochFinalizationService.ScanSummary s =
                    finalizationService.scan(repositoryId, maxDocsPerPass);
            Map<String, Object> body = summaryToMap(repositoryId, s);
            lastScan.put(repositoryId, body);
            return ResponseEntity.ok(body);
        } catch (jp.aegif.nemaki.epoch.AclEpochWiringException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (IllegalStateException e) {
            // A missing Mango index — the scan REFUSES to run rather than let CouchDB silently
            // full-scan, so this is a deployment fault, not a server error.
            return error(HttpStatus.PRECONDITION_FAILED, e.getMessage());
        } finally {
            scanning.remove(repositoryId);
        }
    }

    /** The last sweep's summary for this repository (this JVM), or {@code null}. */
    @GetMapping("/scan/{repositoryId}")
    public ResponseEntity<?> lastScan(@PathVariable String repositoryId) {
        if (!isAdmin()) return forbidden();
        if (finalizationService == null) return unavailable();
        ResponseEntity<?> bad = rejectUnknownRepository(repositoryId);
        if (bad != null) return bad;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repositoryId", repositoryId);
        body.put("running", scanning.contains(repositoryId));
        body.put("lastScan", lastScan.get(repositoryId));
        return ResponseEntity.ok(body);
    }

    /**
     * Finalize ONE document's pending epoch directly — the Phase-2 step, for an operator acting on a
     * single stuck object rather than sweeping the repository.
     */
    @PostMapping("/finalize/{repositoryId}/{docId}")
    public ResponseEntity<?> finalizeOne(@PathVariable String repositoryId, @PathVariable String docId) {
        if (!isAdmin()) return forbidden();
        if (finalizationService == null) return unavailable();
        ResponseEntity<?> bad = rejectUnknownRepository(repositoryId);
        if (bad != null) return bad;
        AclEpochFinalizationService.FinalizeOutcome o;
        try {
            o = finalizationService.finalizePending(repositoryId, docId);
        } catch (jp.aegif.nemaki.epoch.AclEpochFinalizationService.AclEpochContentionException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        } catch (jp.aegif.nemaki.epoch.AclEpochAnomalyException e) {
            // The document is corrupt. It is NOT quarantined here: quarantine is the scanner's
            // decision, made with a re-read and a re-validation, and a one-shot endpoint that
            // quarantined on a single observation would isolate documents a concurrent repair had
            // already fixed.
            return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()
                    + " — run a scan to have it quarantined, then repair it");
        } catch (jp.aegif.nemaki.epoch.AclEpochWiringException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repositoryId", repositoryId);
        body.put("docId", docId);
        body.put("result", o.result.name());
        body.put("epoch", o.epoch);
        return ResponseEntity.ok(body);
    }

    // ── Internal ───────────────────────────────────────────────────

    private static Map<String, Object> summaryToMap(String repositoryId,
            AclEpochFinalizationService.ScanSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repositoryId", repositoryId);
        m.put("scanned", s.scanned);
        m.put("finalized", s.finalized);
        m.put("acked", s.acked);
        m.put("awaitingReconcile", s.awaitingReconcile);
        m.put("enqueued", s.enqueued);
        m.put("quarantined", s.quarantined);
        m.put("quarantineFailures", s.quarantineFailures);
        m.put("contended", s.contended);
        m.put("cursorFailures", s.cursorFailures);
        m.put("more", s.more);
        m.put("errors", s.errors);
        if (s.more) {
            m.put("note", "more=true: this pass hit its budget, or a quarantine/cursor write failed, "
                    + "or a valid document was contended. Run the scan again — progress is durable, "
                    + "so a repeat picks up where this one stopped.");
        }
        return m;
    }

    /**
     * An unknown repository id must be a 404, never an empty-looking success.
     *
     * <p>Increment 10a found the same class of bug in the migration runner: a typo matched no
     * document, the run "completed", and the endpoint reported the work as done. A scan of a
     * nonexistent repository would likewise report a summary of all zeros — indistinguishable from a
     * clean sweep.
     */
    private ResponseEntity<?> rejectUnknownRepository(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "repositoryId is required");
        }
        if (repositoryInfoMap == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "repositoryInfoMap not available — without "
                    + "it an unknown repository id would report an all-zero sweep as a clean one");
        }
        if (!repositoryInfoMap.contains(repositoryId)) {
            return error(HttpStatus.NOT_FOUND, "unknown repository '" + repositoryId
                    + "' — configured: " + repositoryInfoMap.keys());
        }
        return null;
    }

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
                .body(Map.of("status", "error", "message", "ACL-epoch finalization service not available"));
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "error", "message", message));
    }
}
