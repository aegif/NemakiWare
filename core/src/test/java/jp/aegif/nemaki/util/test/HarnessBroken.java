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
package jp.aegif.nemaki.util.test;

/**
 * A lock could not look at what it claims to look at.
 *
 * <h2>Why this is deliberately NOT an AssertionError</h2>
 *
 * <p>The negative-control runner decides whether a control FIRED by asking whether the named
 * test failed on its own assertion: a test that dies of an NPE or a broken fixture proves
 * nothing about the protection, because the sabotage broke the harness rather than tripping
 * the guard. That judgement was made by looking for {@code AssertionError} in the failure
 * stanza — and the two helpers that detect harness breakage ({@code JavaSource.methodBody}
 * and the reflection lookups that report a renamed method) threw {@code AssertionError} too.
 * So the exact case the judgement exists to exclude walked straight through it: rename the
 * method a control sabotages, and the control reports FIRED while measuring nothing.
 *
 * <p>Anything thrown from here means <em>the test is no longer looking at what it thinks it
 * is</em>. It must be loud — it fails the test — but the runner must be able to tell it apart,
 * so it is a plain {@link RuntimeException} and the runner rejects its name explicitly.
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
     * come here, and an {@code assertTrue} in a lock means the product broke its promise.
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
