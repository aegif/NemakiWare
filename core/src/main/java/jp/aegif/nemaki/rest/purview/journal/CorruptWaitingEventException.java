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
package jp.aegif.nemaki.rest.purview.journal;

/**
 * A waiting row that belongs to this task and cannot be believed.
 *
 * <h2>Why this is a type and not a message</h2>
 *
 * <p>The resolver has to tell two failures apart, and they need opposite responses. A row whose
 * contents contradict themselves will contradict themselves again on every future pass — it is
 * {@code CORRUPT}, and an operator has to look at it. A view that is missing or a CouchDB that
 * did not answer says nothing about the data at all — it is {@code INDETERMINATE}, and the next
 * pass may well succeed.
 *
 * <p>Catching a bare {@code RuntimeException} collapsed both into INDETERMINATE, which is the
 * safe direction but the unhelpful one: a genuinely broken row would be retried for ever and
 * never reported. The distinction has to be carried by the type, because by the time the
 * resolver sees it the only other evidence is a message, and messages are exactly what must not
 * carry document values.
 *
 * <h2>Fixed wording only</h2>
 *
 * <p>Every reason is a constant chosen from {@link Reason}. Nothing from the document reaches
 * the message — not the task key, not the qualified name, not an attribute — because this is
 * logged, returned in a resolver verdict and read back on admin routes, and an external asset's
 * qualified name contains its stable key.
 */
public final class CorruptWaitingEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What was wrong. Fixed wording; no document value ever appears in one. */
    public enum Reason {
        /** The view emitted a row and {@code include_docs} returned no document for it. */
        ROW_WITHOUT_DOCUMENT("a waiting row carried no document"),
        /** The waiting metadata disagrees with itself. */
        INCONSISTENT_WAITING_METADATA("a waiting row's metadata is inconsistent"),
        /** A waiting row with nothing to wait on, which the view is written not to emit. */
        EMPTY_TASK_KEYS("a waiting row carries no task keys"),
        /** The v2 row cannot be decoded by this binary. */
        UNDECODABLE_ROW("a waiting v2 row is undecodable"),
        /** The snapshot's own constructor refused it, or its digest does not verify. */
        SNAPSHOT_SELF_VERIFICATION_FAILED("a waiting row's snapshot does not verify"),
        /** The row's subject is not the one the task names. */
        SUBJECT_TASK_MISMATCH("a waiting row's subject does not match its task"),
        /** Two rows claim the same observation coordinate with different contents. */
        CONFLICTING_OBSERVATION("two waiting rows disagree at one observation coordinate"),
        /** A replay or repair whose origin evidence does not match what it carries. */
        ORIGIN_EVIDENCE_MISMATCH("a re-delivery disagrees with its origin's evidence"),
        /** A re-delivery chain that loops, leaves its repository, or runs too deep. */
        BROKEN_ORIGIN_CHAIN("a re-delivery's origin chain cannot be followed safely");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final transient Reason reason;

    public CorruptWaitingEventException(Reason reason) {
        // No cause: a cause's message can quote the document that caused it.
        super(reason.message());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
