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
 * is what the far end SAYS; the second is what we CHECKED. Collapsing them would make this
 * repository's record depend on an unverified assertion by the party taking over — which is the
 * one place in a custody transfer where that must not happen.
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

    /** A SIP exists here. Nothing has left. */
    PACKAGE_CREATED,

    /** Handed to the receiving system. We do not know that it arrived. */
    SENT,

    /** The far end says it has the package. Still says nothing about its contents. */
    RECEIVED,

    /** The far end checked the package against its own rules and accepted the structure. */
    VALIDATED,

    /** The far end started ingest and did not refuse it. */
    INGEST_ACCEPTED,

    /**
     * The far end says an AIP exists, and names it.
     *
     * <p><b>Their claim, not our finding.</b> The distinction is the reason the next state
     * exists.
     */
    AIP_CREATED,

    /**
     * We checked the receipt: it is about OUR package, and it says what we require.
     *
     * <p>This is the first state in which anything has been established rather than reported.
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
            case AIP_CREATED -> Set.of(RECEIPT_VERIFIED, FAILED);
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
     * <p>Only two states qualify, and {@link #AIP_CREATED} is not one of them: the far end
     * saying an AIP exists is not the same as our having checked that it is about our package.
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
            case PACKAGE_CREATED -> "A package exists here. Nothing has been sent and no other "
                    + "party knows about it.";
            case SENT -> "The package was handed over. This is NOT a statement that it arrived: "
                    + "nothing has been heard back.";
            case RECEIVED -> "The receiving system says it has the package. That says nothing "
                    + "about whether the contents are what we sent, or whether it will accept "
                    + "them.";
            case VALIDATED -> "The receiving system accepted the package's structure against "
                    + "its own rules. Those rules are theirs, and passing them is not a "
                    + "statement that the record is intact.";
            case INGEST_ACCEPTED -> "Ingest started and was not refused. No preservation copy "
                    + "is known to exist yet.";
            case AIP_CREATED -> "The receiving system REPORTS that a preservation copy exists "
                    + "and names it. This repository has not checked that claim — it is their "
                    + "assertion, and custody has NOT passed on the strength of it.";
            case RECEIPT_VERIFIED -> "The receipt was checked: it refers to the package we sent "
                    + "and carries what we require. This establishes that the far end received "
                    + "and processed OUR package. It does NOT establish that their copy is "
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
