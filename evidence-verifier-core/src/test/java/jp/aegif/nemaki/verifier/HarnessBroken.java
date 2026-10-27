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
package jp.aegif.nemaki.verifier;

/**
 * A lock in evidence-verifier-core could not look at what it claims to look at.
 *
 * <p>The same type, by name, as core's {@code jp.aegif.nemaki.util.test.HarnessBroken}: this module's tests do not see
 * core's test classes, and the negative-control runner reads breakage from the thrown type's simple name
 * ({@code _harness_broke} in {@code tools/negative-controls/run_negative_controls.py}). Deliberately not an
 * {@code AssertionError} — the runner scores those as a control firing. Added for residual R191 (3.4.1, closing
 * batch 7).
 */
public class HarnessBroken extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HarnessBroken(String message) {
        super(message);
    }

    public HarnessBroken(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * For the common shape: a helper that cannot do its job because something it called threw.
     *
     * <p>Added when the locks that said this with {@code throw new AssertionError(e)} were converted
     * (owner's decision 2026-10-16, residual R189): the runner scores a thrown AssertionError as a
     * control firing, so breakage dressed as an assertion was recorded as "the guard worked".
     */
    public HarnessBroken(Throwable cause) {
        super(String.valueOf(cause), cause);
    }

    /**
     * The one way to say "this lock could not look" as a check: throws unless {@code looked} holds.
     *
     * <p>Added for residual R191 (3.4.1, closing batch 7). A lock that said its breakage with {@code assertTrue(found,
     * "… is gone — this lock no longer reads it")} failed with the same type as a lock catching the product, so the
     * control runner recorded "the guard worked" when the sabotage had only moved what the lock reads. Those checks now
     * come here, so that an assertion in a lock says only that the product broke its promise. Which checks were swept,
     * and by what reading, is R191's row in docs/design/fail-closed-reads.md (closing batches 7 and 8).
     */
    public static void unless(boolean looked, String message) {
        if (!looked) {
            throw new HarnessBroken(message);
        }
    }

    /** {@link #unless(boolean, String)} with a message built only when it is needed. */
    public static void unless(boolean looked, java.util.function.Supplier<String> message) {
        if (!looked) {
            throw new HarnessBroken(message.get());
        }
    }

    /** {@link #unless(boolean, String)} for a count or a shape the lock needs to be what it expects. */
    public static void unlessEqual(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new HarnessBroken(message + " ==> expected: <" + expected + "> but was: <" + actual + ">");
        }
    }

    /** {@link #unlessEqual(Object, Object, String)} for whole numbers, compared by value whatever their width. */
    public static void unlessEqual(long expected, long actual, String message) {
        if (expected != actual) {
            throw new HarnessBroken(message + " ==> expected: <" + expected + "> but was: <" + actual + ">");
        }
    }
}
