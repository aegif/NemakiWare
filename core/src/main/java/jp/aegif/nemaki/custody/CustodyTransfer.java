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
package jp.aegif.nemaki.custody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One record's journey out of this repository, and what is known at each step (P3-4).
 *
 * <h2>Every move is refused or recorded — there is no third option</h2>
 *
 * <p>A transfer is the kind of thing that gets nudged: a status set because somebody knows it
 * arrived, a step skipped because the intermediate one is obvious. Each of those turns the
 * history into a summary of what the last person believed. So {@link #advance} takes only the
 * transitions {@link CustodyState#allowedNext} allows, and every accepted one leaves a
 * {@link Step} behind with its time and reason.
 *
 * <p>The history is the point. "It is at INGEST_ACCEPTED" answers less than "it reached
 * INGEST_ACCEPTED at 09:14 and has not moved since", and only the second tells an operator
 * whether to chase somebody.
 *
 * <h2>Reaching AIP_CREATED does not transfer custody</h2>
 *
 * <p>{@link #verifyReceipt} is the only route from {@link CustodyState#AIP_CREATED} onwards, and
 * it refuses a receipt that is not about this package. That refusal is the whole protection: a
 * receipt saying everything went well, for a different submission, would otherwise move custody
 * off this repository on the strength of a document about somebody else's record.
 *
 * <p>Design: {@code docs/design/p3-4-custody-transfer.md}.
 */
public final class CustodyTransfer {

    /** One accepted move, with why. */
    public record Step(CustodyState from, CustodyState to, String at, String reason) {}

    private final String transferId;
    private final String repositoryId;
    private final String objectId;
    private final String sipDigest;
    private final String receivingSystem;
    private final List<Step> history = new ArrayList<>();

    private CustodyState state = CustodyState.PACKAGE_CREATED;
    private CustodyReceipt receipt;

    /**
     * @param sipDigest the digest of the package that will be sent. Required: a transfer that
     *        does not know what it sent can never check a receipt, and the point at which that
     *        is discovered would be the point at which it mattered
     */
    public CustodyTransfer(String transferId, String repositoryId, String objectId,
            String sipDigest, String receivingSystem, String createdAt) {
        if (sipDigest == null || sipDigest.isBlank()) {
            throw new IllegalArgumentException(
                    "a custody transfer must record the digest of the package it sends; without "
                            + "it no receipt can ever be checked against it");
        }
        this.transferId = transferId;
        this.repositoryId = repositoryId;
        this.objectId = objectId;
        this.sipDigest = sipDigest;
        this.receivingSystem = receivingSystem;
        if (createdAt == null || createdAt.isBlank()) {
            // A history whose steps have no times is a list of claims in an order somebody
            // chose. The class says every move leaves a step with its time; this is where that
            // stops being a comment.
            throw new IllegalArgumentException("a transfer has to record when it was opened");
        }
        this.history.add(new Step(null, CustodyState.PACKAGE_CREATED, createdAt,
                "a package was built for this record"));
    }

    /**
     * Re-materialises a transfer that was persisted. <b>Not a way to reach a state.</b>
     *
     * <p>A store has to be able to hand back what it wrote, and replaying the moves would not
     * do it: the moves are refused or recorded by the state machine, and replaying a recorded
     * one would append it to the history a second time.
     *
     * <p>So this sets the fields directly — and then <b>checks the history is a legal walk</b>
     * before returning. Without that check this is a back door: anything that could write a row
     * could name any state, and {@code RECEIPT_VERIFIED} arrived at by editing a database row
     * is exactly the false diagnosis the machine exists to prevent. With it, a forged row is
     * refused at the point it is read, which is the first moment anyone could act on it.
     *
     * @throws IllegalArgumentException when the stored history does not walk, or does not end
     *         at the stored state
     */
    public static CustodyTransfer restore(String transferId, String repositoryId, String objectId,
            String sipDigest, String receivingSystem, CustodyState state, CustodyReceipt receipt,
            List<Step> history) {
        if (history == null || history.isEmpty()) {
            throw new IllegalArgumentException("a stored transfer with no history cannot be "
                    + "checked, and an unchecked one is a state somebody asserted");
        }
        CustodyTransfer restored = new CustodyTransfer(transferId, repositoryId, objectId,
                sipDigest, receivingSystem, history.get(0).at());
        restored.history.clear();
        CustodyState walked = null;
        for (Step step : history) {
            if (step.from() != walked) {
                throw new IllegalArgumentException("the stored history does not walk: a step "
                        + "leaves " + step.from() + " but the transfer was at " + walked);
            }
            if (step.at() == null || step.at().isBlank()) {
                throw new IllegalArgumentException("a stored step with no time cannot be placed "
                        + "in a sequence of events, and a handover nobody can time is not one");
            }
            if (!step.to().isReachableFrom(walked)) {
                throw new IllegalArgumentException("the stored history contains a move the "
                        + "state machine does not allow: " + walked + " -> " + step.to());
            }
            walked = step.to();
            restored.history.add(step);
        }
        if (walked != state) {
            throw new IllegalArgumentException("the stored state is " + state + " but its own "
                    + "history ends at " + walked + "; a state its history does not support is "
                    + "an assertion, not a record");
        }
        // Every state the history PASSED THROUGH, not just the one it stopped at. The first
        // version checked `state == RECEIPT_VERIFIED`, so a row saying CUSTODY_TRANSFERRED with
        // no receipt sailed through — custody passed, with nothing recording what was checked.
        // The state it ended at is the last place to look for a missing receipt, not the only
        // one.
        boolean everVerified = history.stream()
                .anyMatch(step -> step.to() == CustodyState.RECEIPT_VERIFIED);
        if (everVerified) {
            if (receipt == null) {
                throw new IllegalArgumentException("this transfer's history says a receipt was "
                        + "verified and no receipt is stored; a state that says 'we checked' "
                        + "with nothing to have checked is the false diagnosis this machine "
                        + "exists to prevent");
            }
            // The same checks verifyReceipt makes. A stored row is read back through the rules,
            // or the rules only ever applied to the live path — and the live path is not where
            // an attacker is.
            String refusal = receipt.refusalReasonFor(sipDigest);
            if (refusal != null) {
                throw new IllegalArgumentException("the stored receipt is not about this "
                        + "transfer's package: " + refusal);
            }
            if (!receipt.reportsSuccess()) {
                throw new IllegalArgumentException("the stored receipt reports '"
                        + receipt.verificationOutcome() + "', which is not an outcome that can "
                        + "reach RECEIPT_VERIFIED");
            }
            String missing = receipt.missingRequiredField();
            if (missing != null) {
                throw new IllegalArgumentException("the stored receipt does not carry '"
                        + missing + "', so it could not have been verified");
            }
        }
        restored.state = state;
        restored.receipt = receipt;
        return restored;
    }

    public String transferId() {
        return transferId;
    }

    public String repositoryId() {
        return repositoryId;
    }

    public String objectId() {
        return objectId;
    }

    /** Who the package went to. Named in the record because "somewhere" is not a handover. */
    public String receivingSystem() {
        return receivingSystem;
    }

    public String sipDigest() {
        return sipDigest;
    }

    public CustodyState state() {
        return state;
    }

    public CustodyReceipt receipt() {
        return receipt;
    }

    public List<Step> history() {
        return List.copyOf(history);
    }

    /** Whether a move was taken, and why not when it was not. */
    public record Moved(boolean accepted, CustodyState state, String refusedReason) {}

    /**
     * Moves to {@code next}, if the machine allows it.
     *
     * <p>Refuses a skip as firmly as a reversal. Jumping from SENT to AIP_CREATED because the
     * far end's first reply happened to mention an AIP would erase the fact that we never heard
     * it was received or validated — and those are exactly the states somebody looks at when
     * asking what went wrong later.
     */
    public Moved advance(CustodyState next, String at, String reason) {
        if (next == CustodyState.CUSTODY_TRANSFERRED) {
            // Refused HERE, not only in the service. The design document said "advance
            // explicitly rejects CUSTODY_TRANSFERRED" while only the service wrapper did — so
            // anything holding the domain object could pass custody without the ledger being
            // asked, which is the rule this whole increment exists to enforce.
            return new Moved(false, state, "custody does not pass by advancing to it. The "
                    + "handover has to be recorded first, and only then may the transfer move; "
                    + "CustodyTransferService.passCustody is what does that.");
        }
        return move(next, at, reason);
    }

    /**
     * The move to {@link CustodyState#CUSTODY_TRANSFERRED}, for the service that records first.
     *
     * <p>Package-private on purpose. It is the one door, and a door reachable from outside this
     * package is not one — {@code advance} refuses this state precisely so that nothing else
     * can take it.
     */
    Moved passCustody(String at, String reason) {
        return move(CustodyState.CUSTODY_TRANSFERRED, at, reason);
    }

    private Moved move(CustodyState next, String at, String reason) {
        if (next == null) {
            return new Moved(false, state, "no state was given");
        }
        if (next == CustodyState.CUSTODY_TRANSFERRED && receipt == null) {
            // UNREACHABLE today, and measured to be: removing this leaves every test green,
            // because the state machine gets there first — CUSTODY_TRANSFERRED is only
            // reachable from RECEIPT_VERIFIED, and reaching that sets the receipt. It stays as
            // the guard that would still hold if a later state gained an edge into
            // CUSTODY_TRANSFERRED, which is exactly the change that would otherwise let custody
            // pass unchecked. Not counted as a measured protection: the state machine is what
            // is measured.
            return new Moved(false, state, "custody cannot pass before a receipt has been "
                    + "verified: nothing has been checked, and the far end's word that an AIP "
                    + "exists is not a finding of this repository");
        }
        if (next == CustodyState.RECEIPT_VERIFIED) {
            // Not an ordinary move at all. "Verified" is a finding, and the only thing that can
            // make it is verifyReceipt — which refuses a receipt about another package. Reached
            // by advance(), the state would be named "we checked" with nothing checked.
            return new Moved(false, state, "RECEIPT_VERIFIED is not reached by advancing: it "
                    + "means a receipt was CHECKED, and only verifyReceipt can do that. A "
                    + "transfer walked into this state would be named 'verified' with no "
                    + "receipt in it.");
        }
        if (!state.allowedNext().contains(next)) {
            return new Moved(false, state, "a transfer at " + state + " cannot move to " + next
                    + "; the moves available are " + state.allowedNext()
                    + ". Skipping a step would erase the fact that it never happened, which is "
                    + "what somebody asking what went wrong looks for.");
        }
        if (at == null || at.isBlank()) {
            return new Moved(false, state, "a move has to say when it happened; a history whose "
                    + "steps have no times is a list of claims in an order somebody chose");
        }
        history.add(new Step(state, next, at, reason));
        state = next;
        return new Moved(true, state, null);
    }

    /**
     * Checks a receipt and, if it is about this package, moves to RECEIPT_VERIFIED.
     *
     * <p>The only way past {@link CustodyState#AIP_CREATED}. A receipt about another submission
     * is refused however positive it is: "everything went well" about a different record says
     * nothing about this one, and accepting it would move custody off this repository on the
     * strength of somebody else's document.
     */
    public Moved verifyReceipt(CustodyReceipt candidate, String at) {
        if (candidate == null) {
            return new Moved(false, state, "no receipt was given");
        }
        if (state.custodyHasPassed()) {
            // Custody has already moved on. Replacing the receipt now would rewrite the record
            // of a handover that is finished, and the history would show the second one as
            // though it were the one custody passed on.
            return new Moved(false, state, "custody has already passed; a receipt arriving now "
                    + "cannot change the record of the handover that happened");
        }
        if (!CustodyState.RECEIPT_VERIFIED.isReachableFrom(state)) {
            return new Moved(false, state, "a receipt can only be verified once the receiving "
                    + "system has reported an AIP; this transfer is at " + state);
        }
        String missing = candidate.missingRequiredField();
        if (missing != null) {
            // Before the digest check, because this is about the receipt being a receipt at
            // all. One that names our package and nothing else says the far end holds
            // something; it does not say who holds it or when they said so, and the state it
            // would unlock is one step from custody passing.
            return new Moved(false, state, "the receipt does not carry '" + missing + "'. A "
                    + "receipt has to name who is answerable for the copy and when they said "
                    + "so, or custody passes to nobody in particular and there is no later "
                    + "conversation to have about this record.");
        }
        String refusal = candidate.refusalReasonFor(sipDigest);
        if (refusal != null) {
            return new Moved(false, state, refusal);
        }
        if (!candidate.reportsSuccess()) {
            // The receipt is about our package AND says the far end did not accept it. Moving
            // to RECEIPT_VERIFIED would name the state "we checked" for a check that came back
            // negative, and the next state along passes custody.
            return new Moved(false, state, "the receipt is about this package and reports '"
                    + candidate.verificationOutcome() + "'. A receipt that says the receiving "
                    + "system did not accept the package is a reason to stop, not a step "
                    + "towards custody passing.");
        }
        this.receipt = candidate;
        history.add(new Step(state, CustodyState.RECEIPT_VERIFIED, at,
                "the receipt names this package (" + sipDigest + ")"));
        state = CustodyState.RECEIPT_VERIFIED;
        return new Moved(true, state, null);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transferId", transferId);
        body.put("repositoryId", repositoryId);
        body.put("objectId", objectId);
        body.put("sipDigest", sipDigest);
        body.put("receivingSystem", receivingSystem);
        body.put("state", state.name());
        // Straight after the state, because the state name is what a reader takes away.
        body.put("stateLimits", state.limits());
        body.put("custodyHasPassed", state.custodyHasPassed());
        body.put("receipt", receipt == null ? null : receipt.asMap());
        List<Map<String, Object>> steps = new ArrayList<>(history.size());
        for (Step step : history) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", step.from() == null ? null : step.from().name());
            row.put("to", step.to().name());
            row.put("at", step.at());
            row.put("reason", step.reason());
            steps.add(row);
        }
        body.put("history", steps);
        return body;
    }
}
