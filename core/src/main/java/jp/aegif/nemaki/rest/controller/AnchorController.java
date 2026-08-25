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

import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore;
import jp.aegif.nemaki.evidence.anchor.AnchorService;
import jp.aegif.nemaki.evidence.validity.LongTermValidityService;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Driving the trust ladder by hand (P2-0).
 *
 * <p>Design: {@code docs/design/p2-0-anchor-targets.md}. There is no scheduler yet, deliberately:
 * anchoring frequency is the window in which the ledger can still be rewritten, and choosing it
 * for an operator would be choosing their risk. These endpoints let one be driven from cron, a
 * runbook, or a person, and the roadmap decides the default alongside the checkpoint schedule.
 *
 * <h2>Two verbs, and the second is not optional</h2>
 *
 * <p>{@code /anchor} sends a checkpoint's root. {@code /upgrade-pending} asks whether commitments
 * made earlier have settled. Rung 2 needs both: an OpenTimestamps commitment is PENDING for
 * hours, and a deployment that never calls the second endpoint holds anchors it can never prove.
 */
@RestController
@RequestMapping("/v1/admin/anchor")
public class AnchorController {

    private static final Logger logger = LoggerFactory.getLogger(AnchorController.class);

    @Autowired(required = false)
    private AnchorService anchorService;

    @Autowired(required = false)
    private EvidenceLedgerService ledgerService;

    @Autowired(required = false)
    private EvidenceLedgerStore ledgerStore;

    @Autowired(required = false)
    private AnchorReceiptStore receiptStore;

    @Autowired(required = false)
    private LongTermValidityService validityService;

    @Autowired(required = false)
    private jp.aegif.nemaki.evidence.EvidenceLedgerRecorder ledgerRecorder;

    private HttpServletRequest httpRequest;

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    /**
     * Closes a checkpoint over what the ledger holds now and anchors it at every configured rung.
     *
     * <p>Closing and anchoring are one call because the gap between them is the window in which
     * the ledger moves on and the root goes stale — and {@link AnchorService} would then refuse
     * it, leaving an unanchored checkpoint and an operator wondering why.
     */
    @PostMapping("/checkpoint-and-anchor")
    public ResponseEntity<Map<String, Object>> checkpointAndAnchor(
            @RequestParam String repositoryId) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (anchorService == null || ledgerService == null) {
            return unavailable("the anchor service is not wired on this node");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> closed;
        try {
            closed = ledgerService.closeCheckpoint(repositoryId, Instant.now().toString());
        } catch (RuntimeException e) {
            logger.warn("Could not close a checkpoint for {}: {}", repositoryId, e.getMessage());
            body.put("status", "error");
            body.put("message", "the checkpoint could not be closed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        body.put("checkpoint", closed);
        // closeCheckpoint reports expected failures in its RETURNED map, not by throwing. The
        // outer status used to say "success" over an inner "error" — and then anchored the
        // PREVIOUS checkpoint, so an operator saw 200 for a seal that did not happen (review).
        if ("error".equals(closed.get("status"))) {
            body.put("status", "error");
            body.put("message", "the checkpoint was not sealed, so nothing was anchored: "
                    + closed.get("message"));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        if ("noop".equals(closed.get("status"))) {
            // Nothing new to seal, and nothing sent. Retrying a failed rung from here was tried
            // and taken back out: this endpoint is the one a cron drives, and a retry on a
            // one-minute timer contacts an unconfigured rung for ever and buys a TSA token
            // every minute. The way back for a checkpoint whose anchor failed is
            // /retry-unsettled below, which an operator calls on purpose.
            body.put("status", "noop");
            body.put("message", "no entries since the last checkpoint, so nothing was sealed "
                    + "and nothing was anchored. This is NOT a failure. A checkpoint whose "
                    + "anchor failed earlier is retried by POST /retry-unsettled, not here.");
            return ResponseEntity.ok(body);
        }
        body.put("status", "success");

        EvidenceCheckpoint checkpoint = ledgerStore == null ? null
                : ledgerStore.latestCheckpoint(repositoryId);
        if (checkpoint == null) {
            // Nothing was closed — an empty ledger, most likely. Say so rather than reporting an
            // anchor outcome over a checkpoint that does not exist.
            body.put("anchored", false);
            body.put("message", "no checkpoint exists for this repository, so nothing was "
                    + "anchored. This is NOT a statement that anchoring failed.");
            return ResponseEntity.ok(body);
        }
        body.put("anchor", anchorService.anchor(checkpoint).asMap());
        return ResponseEntity.ok(body);
    }

    /**
     * Anchors the rungs that hold nothing for the latest checkpoint.
     *
     * <p>The way back for a checkpoint that WAS sealed but whose anchor failed. Closing and
     * anchoring happen together, so once a checkpoint is sealed there is no second seal to
     * carry a retry, {@code upgrade-pending} only looks at PENDING rows, and every later run
     * has nothing new to seal — the rung would stay FAILED for ever.
     *
     * <p><b>Deliberately not on a timer.</b> Each call can mint a commitment and, on rung 3,
     * buy a timestamp token. {@link AnchorService#retryUnsettled} skips whatever already holds
     * a CONFIRMED or PENDING receipt and whatever is not configured, but it has no backoff:
     * a rung that keeps failing is contacted once per call, so the caller sets the pace.
     */
    @PostMapping("/retry-unsettled")
    public ResponseEntity<Map<String, Object>> retryUnsettled(
            @RequestParam String repositoryId) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (anchorService == null || ledgerStore == null) {
            return unavailable("the anchor service is not wired on this node");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        EvidenceCheckpoint latest;
        try {
            latest = ledgerStore.latestCheckpoint(repositoryId);
        } catch (RuntimeException e) {
            body.put("status", "error");
            body.put("message", "the latest checkpoint could not be read: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        if (latest == null) {
            body.put("status", "noop");
            body.put("message", "this repository has no checkpoint yet, so there is nothing to "
                    + "anchor. This is NOT a statement that anchoring failed.");
            return ResponseEntity.ok(body);
        }
        AnchorService.Outcome outcome = anchorService.retryUnsettled(latest);
        body.put("status", outcome.refusedReason() == null ? "success" : "error");
        body.put("anchor", outcome.asMap());
        // Said out loud, because an empty receipt list has two very different causes and the
        // list alone cannot tell them apart.
        body.put("message", outcome.refusedReason() != null
                ? "nothing was retried: " + outcome.refusedReason()
                : outcome.receipts().isEmpty()
                        ? "no rung needed retrying: every configured rung already holds a "
                                + "CONFIRMED or PENDING receipt for this checkpoint, or no rung "
                                + "is configured. This is NOT a failure."
                        : "the rungs that held nothing were contacted again");
        return ResponseEntity.status(outcome.refusedReason() == null
                ? HttpStatus.OK : HttpStatus.CONFLICT).body(body);
    }

    /** Re-checks commitments made earlier. Safe to call as often as an operator likes. */
    @PostMapping("/upgrade-pending")
    public ResponseEntity<Map<String, Object>> upgradePending(
            @RequestParam String repositoryId,
            @RequestParam(defaultValue = "100") int limit) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (anchorService == null) {
            return unavailable("the anchor service is not wired on this node");
        }
        List<AnchorReceipt> upgraded = anchorService.upgradePending(repositoryId, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("upgradedCount", upgraded.size());
        // An empty result is the ORDINARY answer during the hours a Bitcoin block takes. Saying
        // so keeps an operator from reading zero as a fault and re-stamping, which would leave
        // a second commitment nobody needs.
        body.put("note", upgraded.isEmpty()
                ? "nothing had settled yet. That is the ordinary answer while a commitment is "
                        + "waiting on confirmation (hours), not a failure — do not re-anchor."
                : "these commitments settled and their proofs were stored");
        List<String> rungs = new ArrayList<>(upgraded.size());
        for (AnchorReceipt receipt : upgraded) {
            rungs.add(receipt.kind().name());
        }
        body.put("upgradedRungs", rungs);
        return ResponseEntity.ok(body);
    }

    /** What a checkpoint's anchoring currently amounts to. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestParam String repositoryId) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (ledgerStore == null) {
            return unavailable("the evidence ledger is not wired on this node");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        EvidenceCheckpoint latest = ledgerStore.latestCheckpoint(repositoryId);
        if (latest == null) {
            body.put("checkpoint", null);
            body.put("message", "this repository has no checkpoint yet, so there is nothing "
                    + "anchored and nothing to anchor against");
            return ResponseEntity.ok(body);
        }
        body.put("checkpoint", Map.of("toSequence", latest.toSequence(),
                "merkleRoot", latest.merkleRoot(), "createdAt", latest.createdAt()));
        long highest = ledgerStore.highestSequence(repositoryId);
        body.put("ledgerHighestSequence", highest);
        // The gap IS the exposure: entries after the last anchored checkpoint are held only by
        // this database, so an operator should be able to see it without computing it.
        body.put("unanchoredEntries", Math.max(0, highest - latest.toSequence()));
        if (ledgerRecorder != null) {
            // Captures that completed but never reached the chain. Counted in memory, so it is
            // per-replica and per-restart — said in the field name, because a number that looks
            // repository-wide and is not would understate the hole on a multi-replica
            // deployment. Without this the count had no reader outside its own test, while the
            // design document claimed an operator could see it.
            body.put("chainGapsOnThisReplicaSinceStartup", ledgerRecorder.gapsSinceStartup());
        }
        List<Map<String, Object>> receipts = new ArrayList<>();
        if (receiptStore == null || !receiptStore.isActive()) {
            // "We could not ask" is not "there are none". An empty list beside status:success
            // reads as "this checkpoint was never anchored", which is a claim about the
            // deployment made on the strength of a missing bean (review).
            body.put("receipts", null);
            body.put("receiptsUnavailable", receiptStore == null
                    ? "the anchor receipt store is not wired on this node"
                    : "the anchor receipt store could not be reached");
            body.put("limits", STATUS_LIMITS);
            return ResponseEntity.ok(body);
        }
        // No null guard here: the branch above returns whenever the store is missing or
        // unreachable. A second check would suggest to a reader that there is another way
        // through, and the one that matters has already been made.
        for (AnchorReceipt receipt
                : receiptStore.forCheckpoint(repositoryId, latest.toSequence())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rung", receipt.kind().name());
            row.put("status", receipt.status().name());
            row.put("claimLimits", AnchorService.claimLimitsFor(receipt));
            row.put("anchoredAt", receipt.anchoredAt() == null ? null : receipt.anchoredAt().toString());
            receipts.add(row);
        }
        body.put("receipts", receipts);
        body.put("limits", STATUS_LIMITS);
        return ResponseEntity.ok(body);
    }

    private static final String STATUS_LIMITS = "Entries after the last anchored checkpoint are "
            + "held only by this "
            + "database. A confirmed anchor makes rewriting DETECTABLE from that point "
            + "back; it does not prevent it, and it says nothing about whether what was "
            + "recorded was complete or true.";

    /**
     * What is going stale, and which renewal it needs (P2-3).
     *
     * @param asOf ISO date to judge against; defaults to today. A parameter because the only
     *             useful question is the forward-looking one: renewal applied after a break
     *             re-dates the evidence to the renewal and cannot recover the original time.
     */
    @GetMapping("/long-term-validity")
    public ResponseEntity<Map<String, Object>> longTermValidity(
            @RequestParam String repositoryId,
            @RequestParam(required = false) String asOf) {

        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (validityService == null) {
            return unavailable("the long-term validity service is not wired on this node");
        }
        LocalDate when;
        try {
            when = asOf == null || asOf.isBlank() ? LocalDate.now() : LocalDate.parse(asOf);
        } catch (RuntimeException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "error");
            body.put("message", "asOf must be an ISO date (yyyy-MM-dd); got '" + asOf + "'");
            return ResponseEntity.badRequest().body(body);
        }
        return ResponseEntity.ok(validityService.assess(repositoryId, when));
    }

    private ResponseEntity<Map<String, Object>> unavailable(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private ResponseEntity<Map<String, Object>> requireAdmin() {
        boolean admin = false;
        if (httpRequest != null) {
            Object ctx = httpRequest.getAttribute("CallContext");
            admin = ctx instanceof CallContext callContext
                    && Boolean.TRUE.equals(callContext.get(CallContextKey.IS_ADMIN));
        }
        if (admin) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", "Admin access required");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
