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
package jp.aegif.nemaki.evidence.anchor;

import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;
import jp.aegif.nemaki.rest.purview.anchor.AnchorTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three verbs of the trust ladder — seal and send, retry what holds nothing, upgrade what is
 * pending — as ONE implementation that the admin API and the scheduler both call
 * (design {@code docs/design/anchor-scheduler.md} §2).
 *
 * <p>The bodies were moved here from {@code AnchorController} unchanged. What stayed in the
 * controller is the mapping of a {@link Kind} to an HTTP status, so the existing responses are
 * byte-for-byte what they were; what the scheduler reads is the {@link Kind} and the
 * {@link AnchorService.Outcome}, never a status code. "The scheduler drives the existing API" means
 * it calls these methods — not that it sends HTTP to itself.
 *
 * <p>Stateless: built per call from the collaborators the caller holds, so a controller whose
 * fields a test replaces and a scheduler wired by Spring reach the same code.
 */
public final class AnchorRunService {

    private static final Logger logger = LoggerFactory.getLogger(AnchorRunService.class);

    /**
     * What every anchoring answer carries, refusals included. Moved here from the controller with
     * the bodies that carry it; the controller's other endpoints use the same sentence.
     */
    public static final String LIMITS = "unanchoredEntries counts entries not covered "
            + "by a CONFIRMED anchor receipt; entriesAfterLatestCheckpoint counts entries after "
            + "the last SEALED checkpoint, which may itself be unanchored. Entries not covered "
            + "by a confirmed anchor are held only by this "
            + "database. A confirmed anchor makes rewriting DETECTABLE from that point "
            + "back; it does not prevent it, and it says nothing about whether what was "
            + "recorded was complete or true.";

    /** How a run ended. The controller maps each to a status; the scheduler branches on it. */
    public enum Kind {
        /** Sealed (or retried, or upgraded) and nothing refused. */
        SUCCESS,
        /** Nothing to do, and nothing sent. Not a failure. */
        NOOP,
        /** {@code closeCheckpoint} reported an error: nothing was sealed and nothing was sent. */
        NOT_SEALED,
        /**
         * A read or a close threw. For checkpoint-and-anchor this includes the arm where a
         * checkpoint WAS sealed by the call and could not be read back, so nothing was anchored.
         */
        FAILED,
        /** Sealed, then the anchor was refused ({@link AnchorService.Outcome#refusedReason}). */
        REFUSED,
        /** The receipt store could not be asked (upgrade only). */
        UNAVAILABLE
    }

    /**
     * @param body the response body the controller returns verbatim
     * @param outcome the anchor outcome when one was produced, else null
     */
    public record Run(Kind kind, Map<String, Object> body, AnchorService.Outcome outcome) {
    }

    private final AnchorService anchorService;
    private final EvidenceLedgerService ledgerService;
    private final EvidenceLedgerStore ledgerStore;

    public AnchorRunService(AnchorService anchorService, EvidenceLedgerService ledgerService,
            EvidenceLedgerStore ledgerStore) {
        this.anchorService = anchorService;
        this.ledgerService = ledgerService;
        this.ledgerStore = ledgerStore;
    }

    /**
     * Closes a checkpoint over what the ledger holds now and anchors it at every configured rung.
     *
     * <p>Closing and anchoring are one call because the gap between them is the window in which
     * the ledger moves on and the root goes stale — and {@link AnchorService} would then refuse
     * it, leaving an unanchored checkpoint and an operator wondering why.
     *
     * <p>The caller checks that the anchor service and the ledger service are wired; this method
     * assumes both.
     */
    public Run checkpointAndAnchor(String repositoryId, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Before anything is attempted, so every one of this method's eight exits carries it.
        // /status and /upgrade-pending had it and these two endpoints had it on no exit at all,
        // which is the version of "one arm of a fan-out" that shows up between sibling methods
        // rather than inside one.
        body.put("limits", LIMITS);
        Map<String, Object> closed;
        try {
            closed = ledgerService.closeCheckpoint(repositoryId, now.toString());
        } catch (RuntimeException e) {
            logger.warn("Could not close a checkpoint for {}: {}", repositoryId, e.getMessage());
            body.put("status", "error");
            body.put("message", "the checkpoint could not be closed: " + e.getMessage());
            return new Run(Kind.FAILED, body, null);
        }
        body.put("checkpoint", closed);
        // closeCheckpoint reports expected failures in its RETURNED map, not by throwing. The
        // outer status used to say "success" over an inner "error" — and then anchored the
        // PREVIOUS checkpoint, so an operator saw 200 for a seal that did not happen (review).
        if ("error".equals(closed.get("status"))) {
            body.put("status", "error");
            body.put("message", "the checkpoint was not sealed, so nothing was anchored: "
                    + closed.get("message"));
            return new Run(Kind.NOT_SEALED, body, null);
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
            return new Run(Kind.NOOP, body, null);
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
            return new Run(Kind.FAILED, body, null);
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
            return new Run(Kind.FAILED, body, null);
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
            return new Run(Kind.REFUSED, body, outcome);
        }
        return new Run(Kind.SUCCESS, body, outcome);
    }

    /**
     * Anchors the rungs that hold nothing for the latest checkpoint.
     *
     * <p>The way back for a checkpoint that WAS sealed but whose anchor failed. Closing and
     * anchoring happen together, so once a checkpoint is sealed there is no second seal to
     * carry a retry, {@code upgrade-pending} only looks at PENDING rows, and every later run
     * has nothing new to seal — the rung would stay FAILED for ever.
     *
     * <p>The caller checks that the anchor service and the ledger store are wired.
     */
    public Run retryUnsettled(String repositoryId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", LIMITS);
        EvidenceCheckpoint latest;
        try {
            latest = ledgerStore.latestCheckpoint(repositoryId);
        } catch (RuntimeException e) {
            body.put("status", "error");
            body.put("message", "the latest checkpoint could not be read: " + e.getMessage());
            return new Run(Kind.FAILED, body, null);
        }
        if (latest == null) {
            body.put("status", "noop");
            body.put("message", "this repository has no checkpoint yet, so there is nothing to "
                    + "anchor. This is NOT a statement that anchoring failed.");
            return new Run(Kind.NOOP, body, null);
        }
        AnchorService.Outcome outcome = anchorService.retryUnsettled(latest);
        // A rung that refuses its configuration answers with a FAILED receipt it made without
        // asking anyone. Counted with the rungs that were asked, it was reported as "contacted
        // again" (c44, P1) — so the receipts are split by what the rung itself says.
        Map<String, String> notAsked = new LinkedHashMap<>();
        List<String> asked = new ArrayList<>();
        for (AnchorReceipt receipt : outcome.receipts()) {
            String refusal = refusalOf(receipt);
            if (refusal != null) {
                notAsked.put(receipt.kind().name(), refusal);
            } else {
                asked.add(receipt.kind().name());
            }
        }
        boolean onlyRefusals = outcome.refusedReason() == null && !notAsked.isEmpty() && asked.isEmpty();
        body.put("status", outcome.refusedReason() == null && !onlyRefusals ? "success"
                : onlyRefusals ? "refused" : "error");
        body.put("anchor", outcome.asMap());
        if (!notAsked.isEmpty()) {
            body.put("notAsked", notAsked);
        }
        // Said out loud, because an empty receipt list has two very different causes and the
        // list alone cannot tell them apart.
        body.put("message", outcome.refusedReason() != null
                ? "nothing was retried: " + outcome.refusedReason()
                : onlyRefusals
                        ? "nothing was retried: every rung that held nothing refuses its "
                                + "configuration and was not asked — " + notAsked
                        : outcome.receipts().isEmpty()
                                ? "no rung needed retrying: every configured rung already holds a "
                                        + "CONFIRMED or PENDING receipt for this checkpoint, or no rung "
                                        + "is configured. This is NOT a failure."
                                : "the rungs that held nothing were contacted again: " + asked
                                        + (notAsked.isEmpty() ? "" : "; not asked, because they refuse "
                                                + "their configuration: " + notAsked));
        return new Run(outcome.refusedReason() == null && !onlyRefusals ? Kind.SUCCESS : Kind.REFUSED,
                body, outcome);
    }

    /** The refusal a receipt carries when its rung refused without asking anyone, or null. */
    private String refusalOf(AnchorReceipt receipt) {
        if (receipt.status() != AnchorStatus.FAILED) {
            return null;
        }
        for (AnchorTarget target : anchorService.targets()) {
            if (target.kind() == receipt.kind() && target.refusal() != null
                    && target.refusal().equals(receipt.failureReason())) {
                return target.refusal();
            }
        }
        return null;
    }

    /**
     * Re-checks commitments made earlier. Safe to call as often as an operator likes.
     *
     * <p>The caller checks that the anchor service is wired.
     */
    public Run upgradePending(String repositoryId, int limit) {
        AnchorService.Upgraded result = anchorService.upgradePending(repositoryId, limit);
        List<AnchorReceipt> upgraded = result.upgraded();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limits", LIMITS);
        if (result.refused() != null) {
            // Not asked is neither "could not ask" nor "nothing had settled": the note below
            // tells an operator not to re-anchor, which is wrong advice for a rung that will
            // never be asked until its configuration changes (c44, P1).
            body.put("status", "refused");
            body.put("upgradedCount", upgraded.size());
            body.put("upgradedRungs", null);
            body.put("message", result.refused());
            return new Run(Kind.REFUSED, body, null);
        }
        if (result.unavailable() != null) {
            // "Could not ask" is not "nothing had settled". Telling an operator the second when
            // the first is true is worse than silence: the note below says "do not re-anchor",
            // so a deployment whose store is unreachable is advised to leave a commitment
            // unupgraded for ever.
            body.put("status", "unavailable");
            body.put("upgradedCount", 0);
            body.put("upgradedRungs", null);
            body.put("message", result.unavailable());
            return new Run(Kind.UNAVAILABLE, body, null);
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
        return new Run(Kind.SUCCESS, body, null);
    }
}
