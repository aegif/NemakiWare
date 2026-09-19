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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.init;

/**
 * Whether the process is inside the database-provisioning window.
 *
 * <h2>Why this replaced a thread-name guess</h2>
 *
 * <p>The store layer is deliberately lenient in one place: while the databases and design
 * documents are being created, a missing view or a failed read is expected and must not stop
 * provisioning. Everywhere else the same answer has to be a refusal — that is the whole point
 * of the fail-closed work.
 *
 * <p>Which of the two it is used to be decided by {@code Thread.currentThread().getName()}
 * containing "main", "startup" or "init". That is a guess about names nobody controls: a
 * request thread named by a container ("main-worker-3") gets the provisioning grace and
 * answers a caller with "no data" for a failure, while provisioning running on a differently
 * named executor gets none. A review flagged it as an unverified hypothesis, and it cannot be
 * verified — the names are not ours.
 *
 * <p>So the window is declared instead of inferred: {@link #begin()} at the start of
 * provisioning, {@link #end()} in its {@code finally}. Outside that window — including in a
 * process that never provisions, and in every unit test — the answer is "not startup", which
 * is the strict side. A leaked {@code begin()} without its {@code end()} would extend the
 * grace, so the two live in the same try/finally.
 */
public final class StartupPhase {

    /**
     * How many windows are open. Zero — the initial value — is the strict side.
     *
     * <p>A counter rather than a flag because the windows NEST: the type registry declares one
     * around its first initialization, and that initialization can run inside the
     * provisioning window that {@code DatabasePreInitializer} opened. With a boolean, the
     * inner {@code end()} closed the OUTER window, and the rest of provisioning ran strict —
     * a missing view then refused during the very work that creates the views.
     */
    private static final java.util.concurrent.atomic.AtomicInteger openWindows =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private StartupPhase() {
    }

    /** Marks the beginning of the provisioning window. Pair with {@link #end()} in a finally. */
    public static void begin() {
        openWindows.incrementAndGet();
    }

    /** Ends the provisioning window. Safe to call when it was never begun. */
    public static void end() {
        openWindows.updateAndGet(depth -> depth > 0 ? depth - 1 : 0);
    }

    /** Whether database provisioning is running right now. Defaults to false (strict). */
    public static boolean isProvisioning() {
        return openWindows.get() > 0;
    }
}
