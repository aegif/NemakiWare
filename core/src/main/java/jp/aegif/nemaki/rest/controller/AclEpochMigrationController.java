package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.epoch.AclEpochMigrationService;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin REST API for the repository-wide initial {@code effective_acl_epoch} stamp — wiring gate 2's
 * operational half (design §8).
 *
 * <p><b>Order matters.</b> Run this AFTER the mandatory v3.3.0 full reindex, never before: the
 * reindex rebuilds every document, and on a freshly-rebuilt index the content writer's fence has no
 * stored ACL group to preserve, so a stamp applied earlier is simply discarded.
 *
 * <p><b>Read the {@code verdict}, not {@code remainingUnfenced}</b> (increment 10a). "The count
 * reached zero" is NOT the completion criterion, because there are three ways to be at zero and only
 * one of them means done: an unknown repository matches nothing (now a 404), an index that has not
 * been reindexed yet is empty ({@code EMPTY_INDEX}), and a fully-stamped repository still holds
 * ORPHANED entries whose CouchDB content was deleted, so its count never reaches zero at all
 * ({@code COMPLETE_EXCEPT_ORPHANS}). Only {@code COMPLETE} and {@code COMPLETE_EXCEPT_ORPHANS} mean
 * the gate is closed for this repository.
 *
 * <p>Under {@code /v1/admin/*} (Spring MVC), so admin-gated and CSRF-protected by the shared
 * {@code CsrfInterceptor}.
 */
@RestController
@RequestMapping("/v1/admin/acl-epoch/migration")
public class AclEpochMigrationController {

    @Autowired(required = false)
    private AclEpochMigrationService migrationService;

    @Autowired
    private HttpServletRequest httpRequest;

    // Setters so the confirm gate can be unit-tested without a Spring context.
    public void setMigrationService(AclEpochMigrationService s) { this.migrationService = s; }
    public void setHttpRequest(HttpServletRequest r) { this.httpRequest = r; }

    /**
     * Start the stamp for one repository (asynchronous). 409 if a run is already in flight for it —
     * concurrent runs are safe (every write is a {@code _version_} CAS) but would make the progress
     * counters meaningless, and reading them is the point.
     */
    @PostMapping("/{repositoryId}")
    public ResponseEntity<?> start(@PathVariable String repositoryId) {
        if (!isAdmin()) return forbidden();
        if (migrationService == null) return unavailable();
        boolean started;
        try {
            started = migrationService.start(repositoryId);
        } catch (jp.aegif.nemaki.epoch.AclEpochWiringException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (IllegalArgumentException e) {
            // An unknown repository id is NOT_FOUND, not a completed migration — see
            // AclEpochMigrationService#requireKnownRepository for why that distinction matters.
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (!started) {
            return error(HttpStatus.CONFLICT,
                    "An ACL-epoch migration is already running for " + repositoryId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "started");
        body.put("repositoryId", repositoryId);
        body.put("note", "Run this AFTER the mandatory full reindex — a later reindex leaves the "
                + "documents unfenced again. Poll GET for progress.");
        return ResponseEntity.ok(body);
    }

    /**
     * Progress for the last run, plus {@code remainingUnfenced} read live from Solr — an INDEPENDENT
     * check rather than a restatement of the run's own counters, so a run that reported success while
     * skipping documents is still visible as a non-zero remainder.
     */
    @GetMapping("/{repositoryId}")
    public ResponseEntity<?> status(@PathVariable String repositoryId) {
        if (!isAdmin()) return forbidden();
        if (migrationService == null) return unavailable();
        Map<String, Object> body = new LinkedHashMap<>();
        AclEpochMigrationService.Progress p = migrationService.status(repositoryId);
        body.put("run", p == null ? null : p.toMap());
        try {
            long remaining = migrationService.remainingUnfenced(repositoryId);
            long indexed = migrationService.indexedCmisObjects(repositoryId);
            body.put("remainingUnfenced", remaining);
            body.put("indexedCmisObjects", indexed);
            // NOT `remaining == 0`: that is unreachable whenever the index holds an entry whose
            // CouchDB content has been deleted, and reporting such a repository as never-finished
            // would be wrong in the one direction that matters here.
            AclEpochMigrationService.Verdict v =
                    migrationService.verdict(repositoryId, remaining, indexed);
            body.put("verdict", v.name());
            body.put("fenced", v == AclEpochMigrationService.Verdict.COMPLETE
                    || v == AclEpochMigrationService.Verdict.COMPLETE_EXCEPT_ORPHANS);
            if (v == AclEpochMigrationService.Verdict.PARTIALLY_FENCED_NO_RUN_RECORD) {
                body.put("note", "Part of the index carries epochs and part does not, and this JVM "
                        + "has no record of a stamp run (the record is in-memory and is lost on "
                        + "restart). A partial population is equally consistent with an unfinished "
                        + "migration, a partial reindex, or ordinary ACL writes bootstrapping "
                        + "documents one at a time — all of which want the same next step: run the "
                        + "stamp. Reindex FIRST if a reindex is pending, THEN stamp.");
            }
            if (v == AclEpochMigrationService.Verdict.EMPTY_INDEX) {
                body.put("note", "This repository has NO CMIS objects in the index. Nothing was "
                        + "stamped because there was nothing to stamp — most likely the mandatory "
                        + "full reindex has not been run yet. Reindex first, THEN run this.");
            }
            if (v == AclEpochMigrationService.Verdict.COMPLETE_EXCEPT_ORPHANS) {
                body.put("note", remaining + " index entries have no CouchDB content (deleted objects "
                        + "whose Solr documents were left behind). They can never be stamped and can "
                        + "never be the target of an ACL write, so they do not block wiring — remove them with "
                        + "DELETE .../migration/{repo}/orphans?confirm=true. This verdict is measured against the LAST run's "
                        + "skippedDeleted count, so if you remove some of those entries by hand the "
                        + "reading turns INCOMPLETE until you run the stamp again; that re-run is "
                        + "cheap (already-stamped documents are skipped without recomputation).");
            }
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (jp.aegif.nemaki.epoch.AclEpochWiringException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            // Fail SOFT on the live count: a Solr blip must not hide the run's own progress, which
            // is in-memory and still accurate. But fail CLOSED on the verdict — without the count
            // there is no verdict, and leaving the key ABSENT would make a script's `if fenced`
            // read as false only by accident, or throw. Say it.
            body.put("remainingUnfenced", null);
            body.put("indexedCmisObjects", null);
            body.put("verdict", "UNKNOWN");
            body.put("fenced", false);
            body.put("remainingUnfencedError", e.getMessage());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * DRY RUN: the orphaned index entries (unfenced Solr documents whose CouchDB content is a
     * definitive 404) — the residual behind {@code COMPLETE_EXCEPT_ORPHANS}. Read-only.
     */
    @GetMapping("/{repositoryId}/orphans")
    public ResponseEntity<?> orphans(@PathVariable String repositoryId,
            @RequestParam(defaultValue = "1000") int limit) {
        if (!isAdmin()) return forbidden();
        if (migrationService == null) return unavailable();
        try {
            AclEpochMigrationService.OrphanScan scan = migrationService.scanOrphans(repositoryId, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("repositoryId", repositoryId);
            body.put("verifiedOrphans", scan.verifiedOrphans.size());
            body.put("unverifiable", scan.unverifiable);
            body.put("liveUnfenced", scan.liveUnfenced);
            body.put("orphanIds", scan.verifiedOrphans.stream()
                    .map(AclEpochMigrationService.OrphanRef::getId).limit(50).toList());
            body.put("hint", "DELETE /v1/admin/acl-epoch/migration/" + repositoryId
                    + "/orphans?confirm=true removes the VERIFIED ones (each delete is a Solr "
                    + "_version_ CAS, so a concurrently restored object is never deleted over)");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (jp.aegif.nemaki.epoch.AclEpochWiringException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /**
     * DESTRUCTIVE: delete the verified orphans. Requires the EXPLICIT {@code ?confirm=true} —
     * this endpoint removes index documents, and a URL an operator can paste by accident must not
     * be able to do that. Without it, nothing is touched and the response says exactly what to
     * run. Fail-closed per entry: only a definitive CouchDB 404 qualifies, and each delete is
     * conditional on the {@code _version_} read during verification.
     */
    @DeleteMapping("/{repositoryId}/orphans")
    public ResponseEntity<?> deleteOrphans(@PathVariable String repositoryId,
            @RequestParam(defaultValue = "false") boolean confirm,
            @RequestParam(defaultValue = "1000") int limit) {
        if (!isAdmin()) return forbidden();
        if (migrationService == null) return unavailable();
        if (!confirm) {
            return error(HttpStatus.BAD_REQUEST, "This DELETES index documents. Re-run with "
                    + "?confirm=true after reviewing GET /v1/admin/acl-epoch/migration/"
                    + repositoryId + "/orphans");
        }
        try {
            AclEpochMigrationService.OrphanDeleteResult r =
                    migrationService.deleteOrphans(repositoryId, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("repositoryId", repositoryId);
            body.put("deleted", r.deleted);
            body.put("skippedConcurrentlyChanged", r.skippedConcurrentlyChanged);
            body.put("unverifiable", r.unverifiable);
            body.put("liveUnfenced", r.liveUnfenced);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (jp.aegif.nemaki.epoch.AclEpochWiringException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
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
                .body(Map.of("status", "error", "message", "ACL-epoch migration service not available"));
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "error", "message", message));
    }
}
