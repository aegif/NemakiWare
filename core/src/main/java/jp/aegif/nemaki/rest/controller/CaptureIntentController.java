/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.controller;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.rest.ingest.EvidenceMetadataHash;
import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The unresolved-capture listing: ingests that started changing things and never said they
 * finished.
 *
 * <p>Without this the whole boundary is invisible. A row in {@code UNRESOLVED} is the only trace
 * of an ingest that crashed between committing the content and committing the evidence, and the
 * existing journal listing cannot show it — that one selects {@code type == lineage_event}, and
 * these rows deliberately never become events.
 *
 * <h2>What an entry does and does not mean</h2>
 *
 * <p>It means: an intent was opened, and nothing completed it before the deadline. It does
 * <b>not</b> mean a document was created, or that one was not. Deciding that needs a stamp the
 * ingest does not write yet, so the sweeper does not go looking and neither does this endpoint —
 * showing a guess here would be worse than showing the attempt.
 */
@RestController
@RequestMapping("/v1/admin/capture-intents")
public class CaptureIntentController {

    private static final Logger logger = LoggerFactory.getLogger(CaptureIntentController.class);

    @Autowired(required = false)
    private CaptureMaintenanceStore maintenanceStore;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    /** For tests and for wiring without a servlet container. */
    public void setMaintenanceStore(CaptureMaintenanceStore maintenanceStore) {
        this.maintenanceStore = maintenanceStore;
    }

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("contentService")
    private ContentService contentService;

    /** For tests. */
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * Unresolved captures, newest first.
     *
     * @param repositoryId optional filter; absent means every repository
     * @param limit        page size, bounded by the store
     * @param offset       where to start
     */
    @GetMapping("/unresolved")
    public ResponseEntity<Map<String, Object>> listUnresolved(
            @RequestParam(required = false) String repositoryId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) {
            return forbidden;
        }
        if (maintenanceStore == null) {
            // Distinguished from an empty listing on purpose. "The boundary is not wired" and
            // "nothing is unresolved" look identical otherwise, and the second is the answer an
            // operator would act on.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "unavailable");
            body.put("message", "The capture boundary is not wired in this deployment, so "
                    + "whether anything is unresolved cannot be answered");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        try {
            CaptureMaintenanceStore.UnresolvedPage page =
                    maintenanceStore.listUnresolved(repositoryId, limit, offset);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("count", page.entries().size());
            body.put("limit", page.limit());
            body.put("offset", page.offset());
            body.put("hasMore", page.hasMore());
            body.put("entries", page.entries());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("Could not list unresolved captures: {}", e.toString(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "Could not read the unresolved captures: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    /**
     * How many capture rows exist in each state.
     *
     * <p>Retention is unlimited by default, so {@code captured} grows with ingest volume for
     * ever. The lineage database's own {@code doc_count} cannot answer this — it mixes journal
     * events, dead letters and v2 rows — so without this an operator running the documented
     * default has no way to see what is accumulating (external review).
     *
     * <p>{@code truncated} means a count reached the scan limit and is a lower bound, not the
     * total. It is never reported as an exact figure when it is not one.
     */
    @GetMapping("/counts")
    public ResponseEntity<Map<String, Object>> counts(
            @RequestParam(defaultValue = "10000") int scanLimit) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) {
            return forbidden;
        }
        if (maintenanceStore == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "unavailable");
            body.put("message", "The capture boundary is not wired in this deployment");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            CaptureMaintenanceStore.CaptureCounts counts =
                    maintenanceStore.countByState(scanLimit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "success");
            body.put("captureIntent", counts.captureIntent());
            body.put("unresolved", counts.unresolved());
            body.put("captured", counts.captured());
            body.put("truncated", counts.truncated());
            if (counts.truncated()) {
                body.put("message", "at least one count reached the scan limit of " + scanLimit
                        + ", so the figures are lower bounds rather than totals");
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("Could not count capture rows: {}", e.toString(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "Could not count the capture rows: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    /**
     * Recomputes the applied-metadata hashes for one object and compares them to the latest
     * hash-bearing completed capture row (design p1-1d-metadata-hash.md §4).
     *
     * <p>Three-valued per hash, deliberately: {@code MATCH} / {@code MISMATCH} /
     * {@code UNVERIFIABLE}. A would-be mismatch DOWNGRADES to {@code UNVERIFIABLE} when a later
     * row for the same source item exists without a hash — a partial failure's unresolved row,
     * or a wrapper that completed without hashing — because "an unrecorded change" must not be
     * claimed while a record of a later pass sits in the store. And a transient MISMATCH is
     * possible while a pass is in flight, so the operational rule is: re-verify once; two
     * consecutive mismatches with no pass between them are the real thing.
     */
    @GetMapping("/verify-metadata")
    public ResponseEntity<Map<String, Object>> verifyMetadata(
            @RequestParam String repositoryId,
            @RequestParam String objectId) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdminOrForbidden();
        if (forbidden != null) {
            return forbidden;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (maintenanceStore == null || contentService == null) {
            body.put("status", "unavailable");
            body.put("message", "The capture boundary is not wired in this deployment");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            java.util.List<Map<String, Object>> rows =
                    maintenanceStore.listCapturedForObject(repositoryId, objectId, 20);
            Map<String, Object> baseline = null;
            int rowsWithoutHash = 0;
            for (Map<String, Object> row : rows) {
                if (row.get("appliedChatEvidenceHash") != null
                        || row.get("appliedSourceIdentityHash") != null) {
                    baseline = row;
                    break;
                }
                rowsWithoutHash++;
            }
            if (baseline == null) {
                body.put("chatEvidence", "UNVERIFIABLE");
                body.put("sourceIdentity", "UNVERIFIABLE");
                body.put("reason", rows.isEmpty()
                        ? "no completed capture row exists for this object"
                        : "completed rows exist but none carries a metadata hash (recorded "
                                + "before hashing, or by a wrapper that does not hash yet)");
                return ResponseEntity.ok(body);
            }

            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                body.put("chatEvidence", "UNVERIFIABLE");
                body.put("sourceIdentity", "UNVERIFIABLE");
                body.put("reason", "the object could not be read");
                return ResponseEntity.ok(body);
            }
            EvidenceMetadataHash.AppliedHashes current =
                    EvidenceMetadataHash.compute(content.getAspects());

            // Activity on the same source item that could postdate the recorded hash — what
            // downgrades a mismatch. The view is keyed by intentOpenedAtMs, so a since-cutoff
            // query missed passes that opened before the baseline completed (external review);
            // and a single capped page hid rows, so a source with a long history could never
            // show MISMATCH again (final review P2). Hence: keyset-paginate through EVERY row
            // and judge each by state.
            long baselineAtMs = baseline.get("capturedAtMs") instanceof Number n
                    ? n.longValue() : 0L;
            Object sourceObjectId = baseline.get("sourceObjectId");
            Object baselineIntentId = baseline.get("intentId");
            boolean overlapping = sourceObjectId != null
                    && overlappingPassExists(repositoryId, String.valueOf(sourceObjectId),
                            baselineIntentId, baselineAtMs);
            boolean laterActivity = rowsWithoutHash > 0 || overlapping;

            body.put("chatEvidence", verdict(
                    (String) baseline.get("appliedChatEvidenceHash"),
                    current.chatEvidenceHash(), laterActivity));
            body.put("sourceIdentity", verdict(
                    (String) baseline.get("appliedSourceIdentityHash"),
                    current.sourceIdentityHash(), laterActivity));
            body.put("comparedIntentId", baseline.get("intentId"));
            body.put("comparedCapturedAtMs", baselineAtMs);
            if (laterActivity) {
                body.put("laterActivity", "a later pass for the same source item exists without "
                        + "a recorded hash; mismatches are downgraded to UNVERIFIABLE");
            }
            body.put("formula", baseline.get("metadataHashFormula"));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.warn("verify-metadata failed for {}/{}: {}", repositoryId, objectId,
                    e.getMessage());
            body.put("status", "error");
            body.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }

    /**
     * Whether any OTHER pass for this source could have written after the baseline hash.
     *
     * <p>Judged per state. Completed: overlaps only when it completed at/after the baseline
     * without recording a hash (completed-before is INSIDE the baseline hash — the hash reads
     * the applied state at completion). Swept ({@code unresolvedAtMs} set): the sweeper's own
     * operating assumption is that a past-deadline pass is dead, so its writes precede its
     * sweep — only a sweep at/after the baseline can overlap. Before this rule, ONE abandoned
     * intent suppressed MISMATCH for the source for ever (final review P2). Still open (no
     * timestamps): possibly live, possibly mid-write — cannot be ruled out, bounded in time by
     * the sweeper's next pass.
     *
     * <p>Pages by the last row's {@code intentOpenedAtMs} until a short page, so the store's
     * 100-row page cap bounds one round trip, not the judgment. The cursor re-includes ties on
     * the boundary millisecond (duplicates are re-judged, harmlessly); a page that cannot
     * advance — 100 rows sharing one millisecond — is treated as overlapping rather than
     * guessed at.
     */
    private boolean overlappingPassExists(String repositoryId, String sourceObjectId,
            Object baselineIntentId, long baselineAtMs) {
        long before = Long.MAX_VALUE;
        while (true) {
            java.util.List<Map<String, Object>> page =
                    maintenanceStore.listRowsForSourceBefore(repositoryId, sourceObjectId,
                            before, 100);
            for (Map<String, Object> row : page) {
                if (baselineIntentId != null && baselineIntentId.equals(row.get("intentId"))) {
                    continue;
                }
                Long rowCapturedAt = row.get("capturedAtMs") instanceof Number rc
                        ? rc.longValue() : null;
                if (rowCapturedAt != null) {
                    boolean rowHasHash = row.get("appliedChatEvidenceHash") != null
                            || row.get("appliedSourceIdentityHash") != null;
                    if (rowCapturedAt >= baselineAtMs && !rowHasHash) {
                        return true;
                    }
                    continue;
                }
                Long rowUnresolvedAt = row.get("unresolvedAtMs") instanceof Number ru
                        ? ru.longValue() : null;
                if (rowUnresolvedAt == null || rowUnresolvedAt >= baselineAtMs) {
                    return true;
                }
            }
            if (page.size() < 100) {
                return false;
            }
            Long lastOpened = page.get(page.size() - 1)
                    .get("intentOpenedAtMs") instanceof Number lo ? lo.longValue() : null;
            if (lastOpened == null || lastOpened + 1 >= before) {
                return true;
            }
            before = lastOpened + 1;
        }
    }

    /** One hash's verdict. Absent on either side is not a mismatch — it is unverifiable. */
    private static String verdict(String recorded, String current, boolean laterActivity) {
        if (recorded == null) {
            return "UNVERIFIABLE";
        }
        if (recorded.equals(current)) {
            return "MATCH";
        }
        return laterActivity ? "UNVERIFIABLE" : "MISMATCH";
    }

    private ResponseEntity<Map<String, Object>> requireAdminOrForbidden() {
        if (!isAdmin()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "error");
            response.put("message", "Admin access required");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }
        return null;
    }

    private boolean isAdmin() {
        if (httpRequest == null) {
            return false;
        }
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            return false;
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        return isAdmin != null && isAdmin;
    }
}
