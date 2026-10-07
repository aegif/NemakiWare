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

import java.util.List;
import java.util.Set;

/**
 * Where a transfer of custody has got to (P3-4).
 *
 * <h2>Storage tiering is not custody transfer</h2>
 *
 * <p>The existing retention path moves bytes — ACTIVE to ARCHIVED_LOCAL to ARCHIVED_COLD — and
 * <b>custody stays here</b> the whole way. This state machine is the other thing: responsibility
 * for the record passes to another organisation. The two are called "archiving" by everybody and
 * are not the same, which is why they are separate types rather than more values on one enum.
 *
 * <h2>Why nine states and not a boolean</h2>
 *
 * <p>Because the interesting failures live between them. A package that was SENT and never
 * RECEIVED, one RECEIVED but not VALIDATED, one VALIDATED but whose ingest was refused — each is
 * a different problem with a different owner, and a {@code transferred} flag turns all of them
 * into "not yet". The state an operator is stuck in IS the diagnosis.
 *
 * <p>In particular {@link #AIP_CREATED} and {@link #RECEIPT_VERIFIED} are kept apart. The first
 * is what was RECORDED; the second is what we CHECKED. Collapsing them would make this
 * repository's record depend on an unverified assertion — which is the one place in a custody
 * transfer where that must not happen.
 *
 * <p>"What the far end SAYS" is what this said, and {@link #limits()} was corrected for exactly
 * that: this release has no sending path, so every state before {@code RECEIPT_VERIFIED} is
 * reached by an operator calling {@code /advance} and nothing here has heard from a receiver.
 * The contrast is still the right one; until there is a sending path, its left-hand side is
 * "somebody recorded" rather than "they said".
 *
 * <h2>Local disposition is last, and it is a separate step</h2>
 *
 * <p>{@link #LOCAL_DISPOSITION} is not reached by succeeding at the previous state. Deleting the
 * local copy is an irreversible act that P3-3 governs, and it happens because somebody decided
 * to, after a receipt was verified — not automatically because a transfer completed.
 *
 * <p>Design: {@code docs/design/p3-4-custody-transfer.md}.
 */
public enum CustodyState {

    /**
     * SOMEBODY RECORDED that a package exists here, and named its digest. Nothing has left.
     *
     * <p>Not "a SIP exists here": a transfer is opened with a caller-supplied digest, and
     * nothing in this release builds a package on the way in or reads one to check the digest
     * against. The first arm of the switch in {@link #limits()}, and the last of its arms to
     * lose this attribution — four of the others were corrected a round earlier.
     */
    PACKAGE_CREATED,

    /**
     * SOMEBODY RECORDED that the package was handed to the receiving system.
     *
     * <p>Not "handed to the receiving system" as a finding: <b>this release has no sending
     * path</b>. Reaching this state is an operator calling {@code POST /advance}, exactly as for
     * the four states after it.
     */
    SENT,

    /**
     * SOMEBODY RECORDED that the receiving system has the package.
     *
     * <p>Not "the far end says it has the package": this release has no sending path, so this
     * state is reached by an operator calling {@code POST /advance} and nothing here has heard
     * from a receiver. {@link #limits()} was corrected for exactly this; leaving the javadoc
     * saying the stronger thing invites the next reader to treat the doc as canon and put the
     * old sentence back.
     */
    RECEIVED,

    /** SOMEBODY RECORDED that the receiving system accepted the package's structure. */
    VALIDATED,

    /** SOMEBODY RECORDED that ingest started and was not refused. */
    INGEST_ACCEPTED,

    /**
     * SOMEBODY RECORDED that an AIP exists, and named it.
     *
     * <p><b>An unchecked claim, not our finding.</b> The distinction is the reason the next state
     * exists — and it is wider than "theirs vs ours": this release has no sending path, so what
     * reached this state is an operator's {@code POST /advance}, and even whose claim it is has
     * not been established. {@link #limits()} says so; this javadoc used to say the stronger
     * thing beside it.
     */
    AIP_CREATED,

    /**
     * We checked the receipt: it is about OUR package, and it says what we require.
     *
     * <p>This is the first state in which this repository CHECKED something rather than simply
     * recording what it was told: the receipt names the package we sent.
     *
     * <p><b>Not "established rather than reported", which is what this said.</b> Without a
     * verified signature, nothing establishes that the receipt came from the receiving system at
     * all — {@link #limits()} says so on the same enum constant, and the two disagreed. What was
     * checked is the tie between a report and our package; who made the report is a separate
     * question with its own answer.
     */
    RECEIPT_VERIFIED,

    /**
     * Custody has passed. The other organisation is answerable for the record.
     *
     * <p>Reaching this does NOT mean the local copy is gone — see {@link #LOCAL_DISPOSITION}.
     */
    CUSTODY_TRANSFERRED,

    /** The local copy was disposed of, under P3-3's rules, as a deliberate later step. */
    LOCAL_DISPOSITION,

    /**
     * The transfer stopped and will not continue without somebody deciding what to do.
     *
     * <p>Not a step in the sequence: reachable from anywhere before custody passes. A state
     * machine with no failure state forces every real failure to be recorded as "still at the
     * previous step", which is how a stalled transfer becomes invisible.
     */
    FAILED;

    /**
     * Whether {@code next} is reachable from {@code from} by any means, including
     * {@link CustodyTransfer#verifyReceipt}.
     *
     * <p>Separate from {@link #allowedNext} because two different questions get asked: "what may
     * a caller ASK for" and "what can happen at all". Folding them together is what let
     * RECEIPT_VERIFIED be walked into by an ordinary advance.
     */
    public boolean isReachableFrom(CustodyState from) {
        if (from == null) {
            return this == PACKAGE_CREATED;
        }
        return from.allowedNext().contains(this)
                || (from == AIP_CREATED && this == RECEIPT_VERIFIED);
    }

    /**
     * The states this one may move to.
     *
     * <p>Declared rather than computed from the ordinal, because the sequence is not the whole
     * rule: {@link #FAILED} is reachable from most places and from nowhere afterwards, and
     * {@link #CUSTODY_TRANSFERRED} does not lead to disposition by itself.
     */
    public Set<CustodyState> allowedNext() {
        return switch (this) {
            case PACKAGE_CREATED -> Set.of(SENT, FAILED);
            case SENT -> Set.of(RECEIVED, FAILED);
            case RECEIVED -> Set.of(VALIDATED, FAILED);
            case VALIDATED -> Set.of(INGEST_ACCEPTED, FAILED);
            case INGEST_ACCEPTED -> Set.of(AIP_CREATED, FAILED);
            // NOT RECEIPT_VERIFIED. That state means "we CHECKED", and the only thing that
            // can check is verifyReceipt — so it is not an ordinary move and advance() must not
            // offer it. Leaving it here let a caller walk the sequence and arrive at a state
            // named "verified" with no receipt in it: the machine whose whole claim is that the
            // state you are stuck in IS the diagnosis, giving a false diagnosis.
            case AIP_CREATED -> Set.of(FAILED);
            case RECEIPT_VERIFIED -> Set.of(CUSTODY_TRANSFERRED, FAILED);
            case CUSTODY_TRANSFERRED -> Set.of(LOCAL_DISPOSITION);
            // Both are ends. A transfer that has been disposed of locally cannot be re-driven,
            // and a failed one is re-driven by starting a new transfer rather than by editing
            // this one — otherwise the record of what went wrong is overwritten by the retry.
            case LOCAL_DISPOSITION, FAILED -> Set.of();
        };
    }

    /** Whether this transfer can still move. */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /**
     * Whether custody has actually passed.
     *
     * <p>Only two states qualify, and {@link #AIP_CREATED} is not one of them: a RECORD that an
     * AIP exists is not the same as our having checked that it is about our package. (Not "the
     * far end saying" — this release has no sending path, so that state is reached by an
     * operator calling {@code /advance}. The third javadoc in this one file to carry that
     * attribution after {@link #limits()} was corrected for it.)
     */
    public boolean custodyHasPassed() {
        return this == CUSTODY_TRANSFERRED || this == LOCAL_DISPOSITION;
    }

    /** The happy path, in order. Excludes {@link #FAILED}, which is not a step. */
    public static List<CustodyState> sequence() {
        return List.of(PACKAGE_CREATED, SENT, RECEIVED, VALIDATED, INGEST_ACCEPTED, AIP_CREATED,
                RECEIPT_VERIFIED, CUSTODY_TRANSFERRED, LOCAL_DISPOSITION);
    }

    /** What this state does and does not establish, in the words an operator needs. */
    public String limits() {
        return switch (this) {
            // These two are the first arms of this switch and the last to keep the
            // attribution the other four lost. A transfer is OPENED with a digest its caller
            // supplies; nothing builds a package, and nothing sends one. So neither "a package
            // exists" nor "it was handed over" is established here either.
            case PACKAGE_CREATED -> "SOMEBODY RECORDED that a package exists for this record and "
                    + "named its digest. Nothing here built or read that package, nothing has "
                    + "been sent, and no other party knows about it.";
            case SENT -> "SOMEBODY RECORDED that the package was handed over. This release has "
                    + "no sending path, so nothing here did the handing over; and it is NOT a "
                    + "statement that it arrived — nothing has been heard back.";
            // These three used to say "The receiving system says / accepted / REPORTS ...".
            // Nothing establishes any of that: every one of these states is reached by an
            // operator calling POST /advance, and this release has no sending path, so the
            // product never hears from a receiver at all. RECEIPT_VERIFIED's text was corrected
            // for exactly this and its neighbours in the same switch were not — the claim's
            // next exits were three case arms away.
            case RECEIVED -> "SOMEBODY RECORDED that the receiving system has the package. "
                    + "Nothing here checked that, and it says nothing about whether the contents "
                    + "are what we sent, or whether they will accept them.";
            case VALIDATED -> "SOMEBODY RECORDED that the receiving system accepted the "
                    + "package's structure against its own rules. Those rules are theirs, "
                    + "passing them is not a statement that the record is intact, and this "
                    + "repository did not witness it.";
            case INGEST_ACCEPTED -> "SOMEBODY RECORDED that ingest started and was not refused. "
                    + "No preservation copy is known to exist yet.";
            case AIP_CREATED -> "SOMEBODY RECORDED that a preservation copy exists and named it. "
                    + "This repository has not checked that — neither the claim nor who made it "
                    + "— and custody has NOT passed on the strength of it.";
            // "This establishes that the far end received and processed OUR package" used to
            // stand here. It is the SAME claim CustodyReceipt.limits() was weakened to remove,
            // and it survived because grep found the receipt's wording and not this one -- split
            // across a string concatenation, in a file the change never touched. Both texts go
            // into ONE response body (stateLimits/stateMeans beside receipt.limits), so a reader
            // was handed the retracted claim and its retraction together.
            //
            // Why the weaker sentence is the true one: on the Archivematica route the recovered
            // value is a line from a manifest THIS product wrote and the receiver merely stored
            // (design §17), and an unsigned receipt posted to the REST endpoint establishes
            // nothing about the far end at all.
            case RECEIPT_VERIFIED -> "The receipt was checked: it refers to the package we sent "
                    + "and carries what we require. That ties the report to our package. It is "
                    + "NOT a finding that the far end holds it — an unsigned receipt is an "
                    + "unauthenticated statement — and it does NOT establish that their copy is "
                    + "intact now, or that they will keep it.";
            case CUSTODY_TRANSFERRED -> "Responsibility for the record has passed to the "
                    + "receiving organisation. The local copy may still exist; this says "
                    + "nothing about whether it does.";
            case LOCAL_DISPOSITION -> "The local copy was disposed of, as a deliberate step "
                    + "after the receipt was verified. What this repository holds about the "
                    + "record from here on is the evidence, not the record.";
            case FAILED -> "The transfer stopped and needs a decision. Nothing about custody "
                    + "changed: it is still here. This is NOT a statement that the receiving "
                    + "system is at fault.";
        };
    }
}
