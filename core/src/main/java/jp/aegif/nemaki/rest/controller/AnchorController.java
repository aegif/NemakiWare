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

    @Autowired(required = false)
    private jp.aegif.nemaki.evidence.FormatDuplicationRecorder duplicationRecorder;

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
        // Before anything is attempted, so every one of this method's eight exits carries it.
        // /status and /upgrade-pending had it and these two endpoints had it on no exit at all,
        // which is the version of "one arm of a fan-out" that shows up between sibling methods
        // rather than inside one.
        body.put("limits", STATUS_LIMITS);
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

        EvidenceCheckpoint checkpoint;
        try {
            checkpoint = ledgerStore == null ? null : ledgerStore.latestCheckpoint(repositoryId);
        } catch (RuntimeException e) {
            // The third of three sites, and the one where losing the message costs most: the
            // arm below exists to tell an operator "the sealed checkpoint is NOT lost — retry
            // the anchor rather than sealing again", and the seal has ALREADY happened by the
            // time we get here. Unwrapped, that instruction is replaced by a generic 500, and
            // /retry-unsettled only ever looks at the LATEST checkpoint — so once the next one
            // is sealed, this one can never be retried through the API at all.
            body.put("status", "error");
            body.put("message", "a checkpoint was sealed by this call and the ledger could not "
                    + "then be read (" + e.getMessage() + "), so nothing was anchored. The "
                    + "sealed checkpoint is NOT lost — retry the anchor with POST "
                    + "/retry-unsettled rather than sealing again.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        if (checkpoint == null) {
            // "No checkpoint exists" is KNOWN TO BE FALSE here. The error and noop arms have
            // already returned, so closed.get("status") is "success" — a checkpoint was sealed
            // seconds ago by this very call. What happened is that the read back did not find
            // it, and saying "there is none" turns a failed read into a fact about the world,
            // then hangs "this is NOT a statement that anchoring failed" off it. The one state
            // that needs /retry-unsettled is precisely a sealed-but-unanchored checkpoint, and
            // this arm told the operator there was nothing to retry.
            //
            // Named for what it is: a fact about THIS CALL, not a property of a checkpoint.
            // The bare word "anchored" is the one AnchorService refuses to emit, because it
            // flattens three rungs with different meanings into one flag — and the same word
            // was, until now, also stamped onto every checkpoint row as a hard-coded false.
            body.put("status", "error");
            body.put("anchoredAnything", false);
            body.put("message", "a checkpoint was sealed by this call and then could not be read "
                    + "back, so nothing was anchored. The sealed checkpoint is NOT lost and this "
                    + "is NOT a statement that it does not exist — retry the anchor with POST "
                    + "/retry-unsettled rather than sealing again.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        // The outer status FOLLOWS the inner outcome. `status: "success"` is written near the
        // top of this method, before anything is attempted, and anchoring reports refusal in
        // its RETURNED Outcome rather than by throwing -- so a refused anchor came back as
        // 200 success. The comment further up claims this defect was already fixed, and it was:
        // for closeCheckpoint's returned map, sixteen lines above. The second producer in the
        // same method, following the same "failure lives in the return value" convention, was
        // not. The sibling endpoint below has always mapped it (refusedReason == null ? OK :
        // CONFLICT); this one now does the same.
        AnchorService.Outcome outcome = anchorService.anchor(checkpoint);
        body.put("anchor", outcome.asMap());
        if (outcome.refusedReason() != null) {
            body.put("status", "refused");
            body.put("message", "the checkpoint was sealed and the anchor was refused: "
                    + outcome.refusedReason());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
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
        body.put("limits", STATUS_LIMITS);
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
        AnchorService.Upgraded result = anchorService.upgradePending(repositoryId, limit);
        List<AnchorReceipt> upgraded = result.upgraded();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", STATUS_LIMITS);
        if (result.unavailable() != null) {
            // "Could not ask" is not "nothing had settled". Telling an operator the second when
            // the first is true is worse than silence: the note below says "do not re-anchor",
            // so a deployment whose store is unreachable is advised to leave a commitment
            // unupgraded for ever.
            body.put("status", "unavailable");
            body.put("upgradedCount", 0);
            body.put("upgradedRungs", null);
            body.put("message", result.unavailable());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
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
        // BEFORE the branch. It was repeated on each of the three arms below, which is how the
        // 403 and the 503 came to have none: a line copied per arm is a line the next arm
        // forgets. Set once here it covers every exit this method can take.
        body.put("limits", STATUS_LIMITS);
        EvidenceCheckpoint latest;
        try {
            latest = ledgerStore.latestCheckpoint(repositoryId);
        } catch (RuntimeException e) {
            // The store was changed to refuse a read it could not make; this method never
            // wrapped it, so the refusal became a 500 whose body carries neither `limits` nor
            // the reason — on the one endpoint whose whole job is to say what is and is not
            // anchored. Its sibling /retry-unsettled has wrapped the same call all along.
            body.put("status", "error");
            body.put("message", "the latest checkpoint could not be read: " + e.getMessage()
                    + ". This is NOT a statement that there is none.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        if (latest == null) {
            body.put("checkpoint", null);
            body.put("message", "this repository has no checkpoint yet, so there is nothing "
                    + "anchored and nothing to anchor against");
            // Emitted here too. Omitting it was the silent absence this same method forbids
            // further down: a caller reading `unanchoredEntries` gets no key at all and has to
            // know that means something different from zero.
            long highestWithoutCheckpoint;
            try {
                highestWithoutCheckpoint = ledgerStore.highestSequence(repositoryId);
            } catch (RuntimeException e) {
                body.put("status", "error");
                body.put("message", "the ledger head could not be read: " + e.getMessage()
                        + ". This is NOT a statement that the chain is empty.");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
            }
            body.put("unanchoredEntries", Math.max(0, highestWithoutCheckpoint + 1));
            body.put("unanchoredEntriesRung", null);
            body.put("unanchoredEntriesNote", "no checkpoint has been sealed, so nothing is "
                    + "anchored and every entry is held only by this database");
            // The other arm carries it and this one did not, so a caller comparing two responses
            // saw the key appear and disappear. With no checkpoint, EVERY entry is after the
            // latest one — there isn't a latest one.
            body.put("entriesAfterLatestCheckpoint", Math.max(0, highestWithoutCheckpoint + 1));
            return ResponseEntity.ok(body);
        }
        body.put("checkpoint", Map.of("toSequence", latest.toSequence(),
                "merkleRoot", latest.merkleRoot(), "createdAt", latest.createdAt()));
        long highest;
        try {
            highest = ledgerStore.highestSequence(repositoryId);
        } catch (RuntimeException e) {
            body.put("status", "error");
            body.put("message", "the ledger head could not be read: " + e.getMessage()
                    + ". This is NOT a statement that the chain is empty.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        body.put("ledgerHighestSequence", highest);
        // NOT "unanchoredEntries". `latest` is the last SEALED checkpoint, which says nothing
        // about whether anything anchored it: on a deployment with no rung configured, or whose
        // only rung FAILED, this arithmetic answered 0 while every entry was unanchored. That is
        // the single number an operator uses to size the window in which the ledger is still
        // quietly rewritable (p2-0 §0), and it read "no exposure" at total exposure.
        //
        // The honest number needs the newest checkpoint holding a CONFIRMED receipt, and the
        // receipt store may not be answerable — so it is computed below, after the store has
        // been consulted, and is ABSENT with a reason rather than 0 when it cannot be had.
        body.put("entriesAfterLatestCheckpoint", Math.max(0, highest - latest.toSequence()));
        if (ledgerRecorder != null) {
            // Captures that completed but never reached the chain. Counted in memory, so it is
            // per-replica and per-restart — said in the field name, because a number that looks
            // repository-wide and is not would understate the hole on a multi-replica
            // deployment. Without this the count had no reader outside its own test, while the
            // design document claimed an operator could see it.
            body.put("chainGapsOnThisReplicaSinceStartup", ledgerRecorder.gapsSinceStartup());
        }
        if (duplicationRecorder != null) {
            // The SAME number for the other fail-open producer. Capture counted its gaps
            // and surfaced them here; a format duplication that failed to reach the chain
            // was logged and nowhere else, and its caller returns a Rendition with no room
            // for a warning. Three fail-open recorders, three destinations for the gap.
            body.put("duplicationChainGapsOnThisReplicaSinceStartup",
                    duplicationRecorder.gapsSinceStartup());
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
            // Not 0, and not omitted silently. "We could not ask" must not read as "nothing is
            // exposed" -- the same rule the receipts list above already follows.
            body.put("unanchoredEntries", null);
            body.put("unanchoredEntriesUnavailable", "the anchor receipt store could not be "
                    + "asked, so how far back a CONFIRMED anchor reaches is unknown. This is "
                    + "NOT a finding that no entry is exposed");
            return ResponseEntity.ok(body);
        }
        // No null guard here: the branch above returns whenever the store is missing or
        // unreachable. A second check would suggest to a reader that there is another way
        // through, and the one that matters has already been made.
        // Read ONCE. The strongest-rung pass below used to call forCheckpoint again, right
        // after this loop -- two answers to one question, from a store that can change between
        // them, and a second round trip for data already in hand.
        List<AnchorReceipt> settled;
        try {
            settled = receiptStore.forCheckpoint(repositoryId, latest.toSequence());
        } catch (RuntimeException e) {
            // isActive() above does NOT cover this: it asks whether a client object exists, and
            // the store's own comment says so — "a reachable database with an unusable view
            // passes every guard above this line". Two of this method's four throwing reads
            // were wrapped in the last pass and these two were not.
            body.put("status", "error");
            body.put("message", "the anchor receipts for this checkpoint could not be read ("
                    + e.getMessage() + "). This is NOT a finding that nothing is anchored.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        for (AnchorReceipt receipt : settled) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rung", receipt.kind().name());
            row.put("status", receipt.status().name());
            row.put("claimLimits", AnchorService.claimLimitsFor(receipt));
            row.put("anchoredAt", receipt.anchoredAt() == null ? null : receipt.anchoredAt().toString());
            receipts.add(row);
        }
        body.put("receipts", receipts);
        // A row the store could not decode is dropped before this loop sees it, and this list
        // then presents itself as the complete set of receipts for the checkpoint. The store
        // counts what it dropped for exactly this reason, AnchorService consults that count in
        // both of its verbs, and this — the endpoint an operator actually reads — did not. The
        // arm sixteen lines above already refuses to let "we could not ask" read as "nothing is
        // exposed"; this is the same rule applied to a read that PARTLY succeeded.
        int undecodable = receiptStore.unreadableCount();
        if (undecodable > 0) {
            // The machine-readable count is withheld when the query failed: the 1 is a
            // sentinel meaning "at least something", and a dashboard summing it would count a
            // receipt nobody established. The prose beside it says which case this is.
            if (!receiptStore.lastQueryFailed()) {
                body.put("receiptsUnreadable", undecodable);
            }
            body.put("receiptsUnavailable", (receiptStore.lastQueryFailed()
                    ? "the anchor receipts for this checkpoint could NOT BE QUERIED — how many "
                            + "exist is unknown, and this is not a finding that any does"
                    : undecodable + " anchor receipt row(s) for this "
                    + "checkpoint could not be read and are NOT in the list above. This is NOT a "
                    + "finding that they are absent") + ", and a rung whose receipt was dropped here "
                    + "looks unanchored below.");
        }
        // Measured from a CONFIRMED receipt, not from the seal. PENDING and FAILED do not
        // count: a receipt that has not settled anchors nothing yet, and p2-0 §4 forbids
        // collapsing the rungs into the single word "anchored" -- so the rung that supplies
        // the number is named beside it.
        // Across ALL checkpoints, not just the latest. An older checkpoint whose receipt is
        // CONFIRMED still covers its own span, so measuring only the latest reported every
        // entry as exposed whenever the newest seal had not settled -- e.g. checkpoint 5
        // confirmed, checkpoint 10 sealed and pending, head 12: the exposure is 6..12, and this
        // answered 13. Wrong in the SAFE direction, but wrong against the field's own
        // definition ("entries not covered by a CONFIRMED anchor receipt"), and it never
        // shrinks when an older anchor settles -- which reads as anchoring not working.
        //
        // An earlier version of this comment argued the opposite and called the conservative
        // number deliberate. It was deliberate; it was also not what the field says it counts.
        Covered covered;
        try {
            covered = coveredByAnyConfirmed(repositoryId, settled, latest);
        } catch (RuntimeException e) {
            // The fourth throwing read: coveredByAnyConfirmed goes back to the store for older
            // checkpoints when the latest has nothing confirmed. Same store, same view, same
            // refusal — and the number it feeds is `unanchoredEntries`, which an operator sizes
            // the rewritable window by. A bare 500 there says nothing about what is exposed.
            body.put("status", "error");
            body.put("message", "the confirmed anchor receipts could not be read ("
                    + e.getMessage() + "), so how far back an anchor reaches is unknown. This "
                    + "is NOT a finding that no entry is exposed.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        AnchorReceipt confirmed = covered.receipt();
        if (confirmed == null) {
            // highest + 1, because sequences are 0-BASED: the first checkpoint starts at
            // from = 0 and highestSequence answers -1 for an empty domain, so a ledger whose
            // highest sequence is 9 holds TEN entries. Reporting `highest` undercounted the
            // exposure by one -- in the understating direction, on the one number an operator
            // uses to size it -- and emitted -1 for an empty ledger, where the sibling branch
            // below has carried Math.max(0, ...) all along.
            body.put("unanchoredEntries", Math.max(0, highest + 1));
            body.put("unanchoredEntriesRung", null);
            body.put("unanchoredEntriesNote", "no CONFIRMED anchor receipt was found for this "
                    + "checkpoint, so EVERY entry is held only by this database — including the "
                    + "ones the checkpoint seals");
        } else {
            body.put("unanchoredEntries", Math.max(0, highest - covered.throughSequence()));
            body.put("unanchoredEntriesRung", confirmed.kind().name());
            body.put("unanchoredEntriesThroughSequence", covered.throughSequence());
            body.put("unanchoredEntriesNote", AnchorService.claimLimitsFor(confirmed));
            // The cap, said out loud. The scan reads at most CONFIRMED_SCAN_LIMIT receipts in
            // ASCENDING order, so on a repository with more than that the furthest confirmed
            // checkpoint FOUND is not the furthest one there is, and this number stays too high
            // — permanently, and growing. It errs safe, but an operator watching a figure that
            // never falls concludes anchoring is not working. claimLimitsFor says what the rung
            // means in time; it says nothing about how far the scan looked.
            body.put("unanchoredEntriesScannedReceipts", CONFIRMED_SCAN_LIMIT);
            body.put("unanchoredEntriesScanNote", "at most " + CONFIRMED_SCAN_LIMIT
                    + " confirmed receipts were read, oldest first. If this repository holds "
                    + "more, a newer confirmed checkpoint may exist that was not read, and this "
                    + "count is then too HIGH rather than too low.");
        }
        return ResponseEntity.ok(body);
    }

    /** How far a CONFIRMED anchor reaches, and which receipt says so. */
    private record Covered(AnchorReceipt receipt, long throughSequence) {}

    /**
     * The furthest-reaching CONFIRMED anchor, over every checkpoint — not only the newest.
     *
     * <p>The latest checkpoint's own receipts are already in hand, so they are used directly and
     * win ties: they cover the most. Only when none of them has settled does this ask the store
     * for confirmed receipts on older checkpoints, which is the case the number was getting
     * wrong.
     */
    private Covered coveredByAnyConfirmed(String repositoryId, List<AnchorReceipt> settled,
            EvidenceCheckpoint latest) {
        AnchorReceipt onLatest = strongestConfirmed(settled);
        if (onLatest != null) {
            return new Covered(onLatest, latest.toSequence());
        }
        // Furthest first, then STRONGEST among the receipts on that same checkpoint. Picking by
        // toSequence alone let the first row on the furthest checkpoint win, so with two rungs
        // confirmed there it could name ATLAS_CATALOG -- the very outcome the rule above exists
        // to prevent, surviving in the fallback arm because the strongest-rung rule was applied
        // only to the primary one.
        long through = -1;
        List<AnchorReceipt> onFurthest = new ArrayList<>();
        List<AnchorReceiptStore.PendingReceipt> confirmedRows =
                receiptStore.confirmed(repositoryId, CONFIRMED_SCAN_LIMIT);
        // The fifth read of this store in this file, and the one that was left folding
        // "could not ask" into "found none": rows() returns [] for an unanswered view (the
        // store flags it), and the caller's confirmed==null branch then states "no CONFIRMED
        // anchor receipt was found ... EVERY entry is held only by this database" — a verdict,
        // from a question that never got through. Thrown here so it lands in the caller's
        // existing catch, which already words the refusal correctly.
        if (receiptStore.lastQueryFailed()) {
            throw new IllegalStateException("the confirmed anchor receipts could not be "
                    + "queried, so which checkpoints hold a confirmed anchor is unknown");
        }
        for (AnchorReceiptStore.PendingReceipt row : confirmedRows) {
            if (row.toSequence() > through) {
                through = row.toSequence();
                onFurthest.clear();
            }
            if (row.toSequence() == through) {
                onFurthest.add(row.receipt());
            }
        }
        return new Covered(strongestConfirmed(onFurthest), through);
    }

    /**
     * How far back this looks for an older confirmed anchor.
     *
     * <p>Bounded because the query is unbounded otherwise and this runs on a status endpoint.
     * If a repository has more confirmed checkpoints than this, the number is reported against
     * the furthest one FOUND, which overstates the exposure — the safe direction, and the note
     * beside it names the checkpoint so a reader can tell.
     */
    private static final int CONFIRMED_SCAN_LIMIT = 200;

    /**
     * The CONFIRMED receipt whose claim is STRONGEST, or null when none has settled.
     *
     * <p>Not "newest", which is what this was called and could not deliver: the store's view is
     * keyed by {@code (domain, toSequence)} with no time ordering, so the first CONFIRMED row it
     * yields is arbitrary. With two rungs settled it could name {@code ATLAS_CATALOG} — whose
     * own enum comment says it "must not be presented as a time proof at all" — as the rung
     * backing {@code unanchoredEntries}.
     *
     * <p>Strength is the property that actually matters here: the number says how much is NOT
     * covered, so the rung quoted beside it should be the best cover there is.
     *
     * <p>Takes the rows already read by the caller rather than querying again. The first
     * version called {@code forCheckpoint} a second time, immediately after the loop that built
     * {@code receipts} — two answers to one question, from a store that can change between
     * them. ({@link #coveredByAnyConfirmed} does go back to the store, but only for the older
     * checkpoints these rows cannot speak for.)
     *
     * <p>Ties go to the first seen: with two rungs of equal strength the number is the same
     * either way, and inventing a tiebreak would be a rule nobody asked for.
     */
    private AnchorReceipt strongestConfirmed(List<AnchorReceipt> settled) {
        AnchorReceipt best = null;
        for (AnchorReceipt receipt : settled) {
            if (receipt.status() != jp.aegif.nemaki.rest.purview.anchor.AnchorStatus.CONFIRMED) {
                continue;
            }
            // Replaced only when STRICTLY stronger, so a tie really does go to the first
            // seen. `strongerOf(a, a)` returns the second argument, so the earlier form
            // replaced `best` on a tie -- last-seen-wins, which is as arbitrary as the
            // first-seen-wins it was written to remove, and the comment above claimed the
            // opposite of what the code did.
            if (best == null
                    || (best.timeSemantics() != receipt.timeSemantics()
                        && jp.aegif.nemaki.rest.purview.anchor.AnchorKind.TimeSemantics
                            .strongerOf(best.timeSemantics(), receipt.timeSemantics())
                        == receipt.timeSemantics())) {
                best = receipt;
            }
        }
        return best;
    }

    private static final String STATUS_LIMITS = "unanchoredEntries counts entries not covered "
            + "by a CONFIRMED anchor receipt; entriesAfterLatestCheckpoint counts entries after "
            + "the last SEALED checkpoint, which may itself be unanchored. Entries not covered "
            + "by a confirmed anchor are held only by this "
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
        // status FIRST, then the assessment. Its three error arms above all carry one, and so
        // does every other endpoint in this class — the success arm was the only body in the
        // controller with no `status` at all, so a client that switches on it saw the key vanish
        // exactly when the call worked.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("limits", STATUS_LIMITS);
        body.putAll(validityService.assess(repositoryId, when));
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> unavailable(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        // The caveat travels with the refusal too. Both shared helpers returned without it while
        // every arm that called them had just set it, so the exits that bypassed the promise
        // were the two shared ones — the same shape as FixityController.requireAdmin.
        body.put("limits", STATUS_LIMITS);
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
        body.put("limits", STATUS_LIMITS);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
