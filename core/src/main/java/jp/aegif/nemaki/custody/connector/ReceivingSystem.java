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
package jp.aegif.nemaki.custody.connector;

import jp.aegif.nemaki.custody.CustodyReceipt;

import java.util.Locale;

/**
 * Which receiver a receipt is being assembled from, and what that costs to read correctly.
 *
 * <h2>Why this is an enum and not a string</h2>
 *
 * <p>Two receivers were measured (P3-4 §10 and §12) and <b>neither can be read by copying the
 * field that is nearest to hand</b>. Both mistakes are silent:
 *
 * <ul>
 *   <li><b>The wrong field.</b> RODA's {@code Report} carries {@code pluginState} AND
 *       {@code outcomeObjectState} in one response body; the second says {@code ACTIVE} for an
 *       accepted AIP, which is not in {@link CustodyReceipt#reportsSuccess()}'s vocabulary. A
 *       connector that took it would refuse every genuine acceptance.</li>
 *   <li><b>The wrong word.</b> Archivematica says {@code COMPLETE}, which is also not in that
 *       vocabulary. Same refusal, different cause.</li>
 * </ul>
 *
 * <p>So the choice of field and the translation of the word are receiver-specific knowledge, and
 * this is where it lives. Nothing here does I/O — that is
 * {@link SubmittedDigestRecovery}'s job, and keeping them apart is what lets the rules be
 * checked without a receiver.
 *
 * <h2>The vocabulary is NOT widened</h2>
 *
 * <p>Adding {@code ACTIVE} / {@code UPLOADED} / {@code COMPLETE} to
 * {@code reportsSuccess()} was considered and rejected (design §13.1): today an unknown word is
 * <i>not</i> success, so being wrong refuses a genuine receipt rather than accepting a bad one,
 * and a third receiver using one of those words differently would be let through. Mapping keeps
 * the failure direction and puts the decision somewhere a reader can see it.
 *
 * <h2>Which slot each word goes in</h2>
 *
 * <p>{@link CustodyReceipt#verificationOutcome()} is what the state machine reads —
 * {@code verifyReceipt} calls {@code reportsSuccess()} on it — so the MAPPED word goes there.
 * The receiver's own word goes in {@link CustodyReceipt#reportedOutcome()}, which is also what
 * the far end's signature covers. Putting them the other way round would stop a genuine
 * acceptance, which is the whole thing mapping exists to prevent.
 */
public enum ReceivingSystem {

    /**
     * RODA 6.3.0.
     *
     * <p>Read {@code Report.pluginState}. Do NOT read {@code Report.outcomeObjectState}: it
     * arrives in the same body and reads plausibly ({@code ACTIVE} is exactly what an accepted
     * AIP looks like), which is what makes it dangerous.
     */
    RODA {
        @Override
        public Outcome read(String word) {
            // "RODA reported no pluginState" used to stand here. The word arrives as an
            // ARGUMENT; a blank one says the caller passed nothing, not that the receiver said
            // nothing. That is verbatim the reasoning CustodyReceipt.limits() was corrected
            // with for the checksum field, applied to a different field one file over.
            return carry(word, measuredSuccessWord(),
                    "no pluginState was given for this RODA report");
        }

        @Override
        public String outcomeFieldName() {
            return "pluginState";
        }

        @Override
        String measuredSuccessWord() {
            return "SUCCESS";
        }
    },

    /**
     * Archivematica 1.18.0 (with Storage Service 0.24.0).
     *
     * <p>Read the Dashboard's transfer/SIP {@code status}. Do NOT read the Storage Service
     * package {@code status}: {@code UPLOADED} is about where the AIP now lives, not about
     * whether the deposit was accepted, and it is not in this product's vocabulary either.
     */
    ARCHIVEMATICA {
        @Override
        public Outcome read(String word) {
            return carry(word, measuredSuccessWord(),
                    "no status was given for this Archivematica transfer");
        }

        @Override
        public String outcomeFieldName() {
            return "status";
        }

        @Override
        String measuredSuccessWord() {
            return "COMPLETE";
        }
    };

    /**
     * The word this receiver was MEASURED to use when the deposit succeeded — and the only one
     * that may become a success here.
     */
    abstract String measuredSuccessWord();

    /**
     * The shared rule, so the two receivers cannot drift apart in how they treat a word.
     *
     * <p>Three cases, and the third is the one that is easy to get wrong:
     *
     * <ol>
     *   <li>the measured success word → this product's {@code SUCCESS};</li>
     *   <li>any other word → carried through, so {@code reportsSuccess()} refuses it and the
     *       operator is shown what the receiver actually said;</li>
     *   <li><b>a word this receiver was never measured to use, which nevertheless happens to be
     *       in this product's success vocabulary</b> → carried in {@code reportedOutcome} and
     *       replaced by {@link #UNRECOGNISED}, so it cannot pass.</li>
     * </ol>
     *
     * <p>Case 3 is exactly the risk design §13.1 gave for not widening the vocabulary: a word
     * meaning one thing here and another there. Archivematica does not use {@code SUCCESS} for
     * a transfer status and RODA does not use {@code OK} for a plugin state, so either arriving
     * means something is not what it claims to be. Passing it through unchanged would let it
     * sail past {@code reportsSuccess()} on the strength of a coincidence.
     *
     * <p>Deliberately takes no receiver: everything that differs between them is already in
     * {@code successWord} and {@code nothingSaid}. It used to take one and never read it, which
     * made the per-receiver branch look as though it lived here.
     */
    private static Outcome carry(String word, String successWord, String nothingSaid) {
        String compared = normalise(word);
        if (compared == null) {
            return Outcome.unreadable(nothingSaid);
        }
        // The receiver's word is kept VERBATIM. Normalising is for comparison only: what goes
        // into reportedOutcome is what the far end signed, and ReceiptSignatureVerifier signs
        // that field. Upper-casing it here would mean a receiver emitting "Complete" had its
        // signature checked against "COMPLETE" -- every mapped receipt failing verification,
        // which is the failure §13.1 split the two slots to avoid.
        if (successWord.equals(compared)) {
            // Whether anything was translated is a property of the RECEIVER, not of the word
            // this particular response carried. Testing `compared` instead -- as an earlier
            // version did -- discarded the receiver's word whenever it happened to normalise to
            // "SUCCESS", so a RODA response saying "Success" was stored, and SIGNED AGAINST, as
            // our "SUCCESS". That is the failure the two slots exist to prevent, surviving in
            // the one branch nobody had a mixed-case test for.
            boolean receiverSpeaksOurWord = "SUCCESS".equals(successWord);
            return Outcome.of("SUCCESS", receiverSpeaksOurWord && "SUCCESS".equals(word)
                    ? null
                    : word);
        }
        if (acceptedByThisProduct(compared)) {
            return Outcome.of(UNRECOGNISED, word);
        }
        return Outcome.of(word, null);
    }

    /**
     * What replaces a success-looking word this receiver was never measured to use.
     *
     * <p>Deliberately not in {@code CustodyReceipt.reportsSuccess()}'s list, and deliberately
     * not something that reads like a receiver's own word.
     */
    public static final String UNRECOGNISED = "UNRECOGNISED_BY_CONNECTOR";

    private static boolean acceptedByThisProduct(String raw) {
        return CustodyReceipt.wouldReportSuccess(raw);
    }

    /**
     * The words a receiver is RECORDED to use when it turned the package down.
     *
     * <h2>"Recorded", not "measured" — the two are not the same here</h2>
     *
     * <p>{@link #measuredSuccessWord()} means observed on the wire. This set is weaker, and
     * saying so matters because the whole point of the branch it feeds is not to put words in a
     * receiver's mouth:
     *
     * <ul>
     *   <li>{@code FAILURE} (RODA) and {@code FAILED} (Archivematica) were <b>seen live</b> —
     *       design §10 追試 1 and §12.</li>
     *   <li>{@code REJECTED} (Archivematica) was <b>read out of the receiver's source</b>, never
     *       observed: §12 says plainly that what was seen was {@code COMPLETE} and
     *       {@code FAILED}.</li>
     * </ul>
     *
     * <p><b>{@code PARTIAL_SUCCESS} is deliberately NOT here.</b> An earlier version put it in,
     * reasoning that a partial acceptance is not an acceptance — which is true, and is why it
     * cannot pass {@code reportsSuccess()}. But this set does not decide what passes; it decides
     * what an operator is TOLD the receiver said, and RODA reporting {@code PARTIAL_SUCCESS} did
     * not say it turned the package down. The submission agreement (§1.4) leaves "is a partial
     * ingest an acceptance?" open for the parties to settle; converting that open question into
     * "the receiving system did not accept the package" is the product answering it for them.
     *
     * <p>The same discipline keeps {@code SKIPPED}, {@code RUNNING}, {@code PROCESSING} and
     * {@code USER_INPUT} out: none of them is a refusal, and the vocabulary is all that was
     * established about them.
     */
    private static final java.util.Set<String> RECORDED_REFUSALS =
            java.util.Set.of("FAILURE", "FAILED", "REJECTED");

    /**
     * Whether some receiver this product knows is recorded as using this word for a refusal.
     *
     * <p>Receiver-agnostic for the same reason {@link #isDerivableMapping} is: the transfer's
     * {@code receivingSystem} is a free-form string an operator types, and matching it to this
     * enum would be a guess. The cost of being loose here is only in the wording of a refusal
     * message — no decision turns on it — and the direction is safe: a word not in this set is
     * described as "not an outcome that can pass" rather than as a rejection.
     */
    public static boolean isRecordedRefusal(String word) {
        String compared = normalise(word);
        return compared != null && RECORDED_REFUSALS.contains(compared);
    }

    /**
     * Whether {@code (reportedOutcome → verificationOutcome)} is a pair some receiver here
     * actually produces.
     *
     * <p><b>This is what stops a mapped word being forged.</b> Splitting the two slots — the
     * mapped word where the state machine reads it, the raw word where the far end's signature
     * covers it — created a gap: a request carrying
     * {@code verificationOutcome=SUCCESS, reportedOutcome=FAILED} would be signed over
     * {@code FAILED} and judged on {@code SUCCESS}. The signature would verify. Nothing in the
     * receipt itself contradicts it.
     *
     * <p>So the pair has to be re-derivable. {@code null} means no mapping was claimed, which is
     * always allowed — the raw word IS the judged word, and the signature covers it.
     *
     * <p><b>The scope is wider than "catches forgeries", and that is worth knowing.</b> The rule
     * refuses any pair it cannot re-derive, which includes an honest but redundant receipt that
     * puts the SAME word in both slots. {@code (SUCCESS, SUCCESS)} is refused: when nothing was
     * translated the second slot must be null, because two representations of "nothing was
     * mapped" is the shape a forger hides in. Fail-closed, and the refusal says so.
     */
    public static boolean isDerivableMapping(String verificationOutcome, String reportedOutcome) {
        if (reportedOutcome == null || reportedOutcome.isBlank()) {
            return true;
        }
        for (ReceivingSystem receiver : values()) {
            Outcome derived = receiver.read(reportedOutcome);
            if (derived.readable()
                    && java.util.Objects.equals(derived.verificationOutcome(), verificationOutcome)
                    && java.util.Objects.equals(derived.reportedOutcome(), reportedOutcome)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What this receiver's word means in this product's vocabulary.
     *
     * @param word the receiver's own word, from the field {@link #outcomeFieldName()} names
     */
    public abstract Outcome read(String word);

    /** The field a connector must read on this receiver. Named so a reader can check it. */
    public abstract String outcomeFieldName();

    private static String normalise(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        return word.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The pair of words a receipt carries, or the reason there is no pair.
     *
     * @param verificationOutcome what the state machine judges — the MAPPED word
     * @param reportedOutcome the receiver's own word, or null when nothing was mapped
     * @param unreadable non-null when the receiver said nothing usable, in which case no
     *        receipt should be assembled at all
     */
    public record Outcome(String verificationOutcome, String reportedOutcome, String unreadable) {

        static Outcome of(String verificationOutcome, String reportedOutcome) {
            return new Outcome(verificationOutcome, reportedOutcome, null);
        }

        static Outcome unreadable(String why) {
            return new Outcome(null, null, why);
        }

        public boolean readable() {
            return unreadable == null;
        }
    }
}
