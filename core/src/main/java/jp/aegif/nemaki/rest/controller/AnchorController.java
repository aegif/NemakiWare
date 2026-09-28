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
import jp.aegif.nemaki.evidence.anchor.AnchorRunService;
import jp.aegif.nemaki.evidence.anchor.AnchorScheduleSettings;
import jp.aegif.nemaki.evidence.anchor.AnchorScheduler;
import jp.aegif.nemaki.evidence.anchor.AnchorService;
import jp.aegif.nemaki.evidence.validity.LongTermValidityService;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorTarget;
import jp.aegif.nemaki.rest.purview.anchor.OpenTimestampsAnchorTarget;
import jp.aegif.nemaki.rest.purview.anchor.Rfc3161AnchorTarget;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Driving the trust ladder by hand (P2-0).
 *
 * <p>Design: {@code docs/design/p2-0-anchor-targets.md}. Anchoring frequency is the window in
 * which the ledger can still be rewritten, and choosing it for an operator would be choosing their
 * risk — so nothing runs on its own by default. These endpoints let one be driven from cron, a
 * runbook, or a person; since 3.4.0 an operator can also turn on {@code AnchorScheduler} per
 * repository ({@code GET/PUT /schedule} below, design {@code docs/design/anchor-scheduler.md}),
 * which runs the same code these endpoints run ({@code AnchorRunService}). It is off until
 * someone sets it.
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

    @Autowired(required = false)
    private AnchorScheduler anchorScheduler;

    @Autowired(required = false)
    private IntegrationSettingsService integrationSettingsService;

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
        // The body moved to AnchorRunService unchanged, so the scheduler runs the same code; the
        // status mapping stays here (design anchor-scheduler.md §2).
        AnchorRunService.Run run = runService().checkpointAndAnchor(repositoryId, Instant.now());
        return ResponseEntity.status(switch (run.kind()) {
            case SUCCESS, NOOP -> HttpStatus.OK;
            case NOT_SEALED, REFUSED -> HttpStatus.CONFLICT;
            case FAILED, UNAVAILABLE -> HttpStatus.INTERNAL_SERVER_ERROR;
        }).body(run.body());
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
        AnchorRunService.Run run = runService().retryUnsettled(repositoryId);
        return ResponseEntity.status(switch (run.kind()) {
            case SUCCESS, NOOP -> HttpStatus.OK;
            case REFUSED, NOT_SEALED -> HttpStatus.CONFLICT;
            case FAILED, UNAVAILABLE -> HttpStatus.INTERNAL_SERVER_ERROR;
        }).body(run.body());
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
        AnchorRunService.Run run = runService().upgradePending(repositoryId, limit);
        return ResponseEntity.status(run.kind() == AnchorRunService.Kind.UNAVAILABLE
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).body(run.body());
    }

    /** Built per call from this controller's collaborators — the same code the scheduler runs. */
    private AnchorRunService runService() {
        return new AnchorRunService(anchorService, ledgerService, ledgerStore);
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

    /** The same sentence the moved bodies carry — one text, one place (AnchorRunService). */
    private static final String STATUS_LIMITS = AnchorRunService.LIMITS;

    /**
     * The per-repository schedule: what is set, what runs, and what the last tick did
     * (design anchor-scheduler.md §5). Read-only; a node that is not the leader says so.
     */
    @GetMapping("/schedule")
    public ResponseEntity<Map<String, Object>> schedule(@RequestParam String repositoryId) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (anchorScheduler == null) {
            return unavailable("the anchor scheduler is not wired on this node");
        }
        ResponseEntity<Map<String, Object>> unknown = unknownDomain(repositoryId);
        if (unknown != null) {
            return unknown;
        }
        return ResponseEntity.ok(scheduleBody(repositoryId));
    }

    /**
     * Saves {@code anchor.schedule.*} for one repository; the next tick reads it.
     *
     * <p>Only those keys. The destinations ({@code anchor.rfc3161.*},
     * {@code anchor.opentimestamps.sidecar.url}) are refused by name: they are start-up system
     * properties, and the POSTs to them carry no SSRF check, so a screen must not be able to point
     * them elsewhere (design §4.2). The whole body is checked with the same rules the scheduler
     * applies before anything is written; one bad value writes nothing.
     */
    @PutMapping("/schedule")
    public ResponseEntity<Map<String, Object>> updateSchedule(@RequestParam String repositoryId,
            @RequestBody(required = false) Map<String, Object> request) {
        ResponseEntity<Map<String, Object>> forbidden = requireAdmin();
        if (forbidden != null) {
            return forbidden;
        }
        if (anchorScheduler == null || integrationSettingsService == null) {
            return unavailable("the anchor scheduler settings are not wired on this node");
        }
        ResponseEntity<Map<String, Object>> unknown = unknownDomain(repositoryId);
        if (unknown != null) {
            return unknown;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", STATUS_LIMITS);
        if (request == null || request.isEmpty()) {
            body.put("status", "error");
            body.put("message", "the body names no setting; send the anchor.schedule.* keys to change");
            return ResponseEntity.badRequest().body(body);
        }
        Map<String, String> updates = new LinkedHashMap<>();
        List<String> refusedKeys = new ArrayList<>();
        for (Map.Entry<String, Object> e : request.entrySet()) {
            Object v = e.getValue();
            if (!AnchorScheduleSettings.KEYS.contains(e.getKey())
                    || !(v == null || v instanceof String || v instanceof Number || v instanceof Boolean)) {
                refusedKeys.add(e.getKey());
                continue;
            }
            updates.put(e.getKey(), v == null ? "" : String.valueOf(v).trim());
        }
        if (!refusedKeys.isEmpty()) {
            body.put("status", "error");
            body.put("message", "only these keys can be set here: " + AnchorScheduleSettings.KEYS
                    + ". Where anchors are sent (anchor.rfc3161.*, anchor.opentimestamps.sidecar.url) "
                    + "is a start-up system property and is not changed from a screen");
            body.put("refusedKeys", refusedKeys);
            return ResponseEntity.badRequest().body(body);
        }
        Map<String, String> current;
        try {
            current = anchorScheduler.settings(repositoryId);
        } catch (RuntimeException e) {
            // Validation merges the body into what is set now; without that, a body that looks
            // valid alone could complete an invalid whole. Refused rather than guessed.
            logger.warn("Could not read the anchor schedule of {} before saving: {}", repositoryId, e.toString());
            body.put("status", "error");
            body.put("message", "the current settings could not be read, so the change could not be "
                    + "checked against them; nothing was saved");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        Map<String, String> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        for (Map.Entry<String, String> e : updates.entrySet()) {
            // A cleared value is judged as "not set" — the conservative reading: a fallback the
            // clear would expose is not assumed to make the result valid.
            merged.put(e.getKey(), e.getValue().isEmpty() ? null : e.getValue());
        }
        AnchorScheduleSettings.Parsed parsed = AnchorScheduleSettings.parse(merged);
        if (!parsed.valid()) {
            body.put("status", "error");
            body.put("message", "nothing was saved: " + parsed.errors().size() + " setting(s) are not valid");
            body.put("errors", parsed.errors());
            return ResponseEntity.badRequest().body(body);
        }
        try {
            integrationSettingsService.writeRepositorySettings(repositoryId, updates);
        } catch (RuntimeException e) {
            // The #1410 rule: the store's words go to the log under an id, not to the client.
            String incidentId = UUID.randomUUID().toString();
            logger.error("Could not save the anchor schedule of {} [incident {}]: {}", repositoryId,
                    incidentId, e.getMessage(), e);
            body.put("status", "error");
            body.put("message", "the settings could not be saved; the details were logged under incidentId. "
                    + "Some keys may have been written before the failure — read the schedule back");
            body.put("incidentId", incidentId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
        logger.info("Anchor schedule of {} updated: {}", repositoryId, updates.keySet());
        return ResponseEntity.ok(scheduleBody(repositoryId));
    }

    private Map<String, Object> scheduleBody(String repositoryId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("limits", STATUS_LIMITS);
        body.put("scheduleLimits", SCHEDULE_LIMITS);
        body.put("repositoryId", repositoryId);
        Map<String, String> raw;
        String unreadable = null;
        try {
            raw = anchorScheduler.settings(repositoryId);
            if (raw == null) {
                unreadable = "the configuration is not wired on this node";
            }
        } catch (RuntimeException e) {
            logger.warn("Could not read the anchor schedule of {}: {}", repositoryId, e.toString());
            raw = null;
            unreadable = "the saved settings could not be read. This is NOT a statement that none are set";
        }
        if (raw == null) {
            // Not defaults: "could not read" must not arrive looking like "disabled".
            body.put("settings", null);
            body.put("settingsUnavailable", unreadable);
        } else {
            body.put("settings", raw);
            AnchorScheduleSettings.Parsed parsed = AnchorScheduleSettings.parse(raw);
            body.put("effective", parsed.effective().asMap());
            body.put("errors", parsed.errors());
        }
        List<Map<String, Object>> rungs = new ArrayList<>();
        Map<String, Object> destinations = new LinkedHashMap<>();
        destinations.put("tsaUrl", null);
        destinations.put("policyOid", null);
        destinations.put("trustAnchorConfigured", false);
        destinations.put("otsSidecarUrl", null);
        if (anchorService != null) {
            for (AnchorTarget target : anchorService.targets()) {
                Map<String, Object> rung = new LinkedHashMap<>();
                rung.put("kind", target.kind().name());
                rung.put("configured", target.isConfigured());
                rungs.add(rung);
                if (target instanceof Rfc3161AnchorTarget tsa) {
                    destinations.put("tsaUrl", withoutUserInfo(tsa.tsaUrl()));
                    destinations.put("policyOid", tsa.requestedPolicyOid());
                    // Whether one is configured — never the certificate itself.
                    destinations.put("trustAnchorConfigured", tsa.hasTrustAnchor());
                } else if (target instanceof OpenTimestampsAnchorTarget ots) {
                    destinations.put("otsSidecarUrl", withoutUserInfo(ots.sidecarUrl()));
                }
            }
        }
        body.put("rungs", rungs);
        body.put("destinations", destinations);
        body.put("runtime", anchorScheduler.runtime(repositoryId));
        body.put("unanchored", anchorScheduler.observation(repositoryId));
        return body;
    }

    /** 400 unless the id is a repository or a ledger domain this node's scheduler visits. */
    private ResponseEntity<Map<String, Object>> unknownDomain(String repositoryId) {
        if (repositoryId != null && anchorScheduler.domains().contains(repositoryId)) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", "no repository or ledger domain '" + repositoryId + "' here; the schedule "
                + "can be set for " + anchorScheduler.domains());
        body.put("limits", STATUS_LIMITS);
        return ResponseEntity.badRequest().body(body);
    }

    /** A URL for display, with any user:password removed. */
    static String withoutUserInfo(String url) {
        if (url == null) {
            return null;
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            if (uri.getUserInfo() == null) {
                return url;
            }
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(),
                    uri.getQuery(), uri.getFragment()).toString();
        } catch (java.net.URISyntaxException e) {
            return "(a URL that could not be parsed for display)";
        }
    }

    private static final String SCHEDULE_LIMITS = "The interval is when a seal is TRIED, not a bound on "
            + "how long entries stay unanchored: a refused anchor, a store that does not answer, or a node "
            + "that is not the leader all leave the window open, and runtime says which. With leader "
            + "election off every replica acts as the leader and sends — set "
            + "lineage.leader-election.enabled=true on a multi-replica deployment. Where anchors are sent "
            + "is a start-up system property and is shown here, not changed.";

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
