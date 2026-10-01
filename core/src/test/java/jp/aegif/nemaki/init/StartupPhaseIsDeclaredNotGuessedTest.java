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
package jp.aegif.nemaki.init;

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provisioning window is DECLARED, never inferred from a thread name.
 *
 * <h2>What the guess cost</h2>
 *
 * <p>The store layer is lenient in exactly one situation — while the databases and design
 * documents are being created, a missing view is expected. Everywhere else that same answer
 * must be a refusal, which is the whole point of the fail-closed work. Which situation it was
 * used to be decided by whether the current thread's NAME contained "main", "startup" or
 * "init": a container thread called "main-worker-3" received the grace and answered a caller
 * with "no data" for a real failure, while provisioning on a differently named executor
 * received none. A review flagged it as an unverified hypothesis — and it cannot be verified,
 * because the names belong to the container, not to us.
 *
 * <p>Default is "not provisioning", the strict side: a process that never provisions, and
 * every test, gets the refusals.
 */
class StartupPhaseIsDeclaredNotGuessedTest {

    @AfterEach
    void ensureWindowClosed() {
        StartupPhase.end();
    }

    @Test
    @DisplayName("the default is strict — pinned in source, because it is observable once")
    void theDefaultIsStrict() throws Exception {
        // The first version of this asserted isProvisioning() at runtime, which the runner
        // showed protects nothing: any earlier test's @AfterEach end() has already written
        // the static, so the DECLARED default is unobservable after the first test in the
        // class. The initializer is the invariant, so the initializer is what is pinned.
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/init/StartupPhase.java"));
        assertTrue(source.contains("new java.util.concurrent.atomic.AtomicInteger(0)"),
                "the provisioning grace no longer defaults to OFF — a process that never "
                        + "provisions, and every test, would answer failures as 'no data'");
    }

    @Test
    @DisplayName("the window opens and closes explicitly")
    void theWindowIsExplicit() {
        StartupPhase.begin();
        assertTrue(StartupPhase.isProvisioning());
        StartupPhase.end();
        assertFalse(StartupPhase.isProvisioning());
    }

    @Test
    @DisplayName("the windows nest — an inner end() does not close the outer one")
    void theWindowsNest() {
        // The registry declares a window around its first initialization, and that
        // initialization can run INSIDE the provisioning window DatabasePreInitializer
        // opened. While this was a boolean, the inner end() closed the outer window and the
        // rest of provisioning ran strict — a missing view refusing during the very work
        // that creates the views.
        StartupPhase.begin();          // provisioning
        StartupPhase.begin();          // the registry's first init, inside it
        StartupPhase.end();            // the registry finishes
        assertTrue(StartupPhase.isProvisioning(),
                "the inner window's end() closed the outer one, so provisioning lost the "
                        + "grace it had declared for itself");
        StartupPhase.end();            // provisioning finishes
        assertFalse(StartupPhase.isProvisioning());
    }

    @Test
    @DisplayName("an unmatched end() cannot drive the window negative")
    void anUnmatchedEndIsHarmless() {
        StartupPhase.end();
        StartupPhase.end();
        StartupPhase.begin();
        assertTrue(StartupPhase.isProvisioning(),
                "stray end() calls left the counter below zero, so a later begin() no longer "
                        + "opens the window");
        StartupPhase.end();
        assertFalse(StartupPhase.isProvisioning());
    }

    @Test
    @DisplayName("a thread named like the old heuristic gets no grace by itself")
    void aThreadNameGrantsNothing() throws Exception {
        // The exact shape of the old bug: this thread's name contains "main", which used to
        // be enough to turn a failed read into "no data".
        final boolean[] provisioning = new boolean[1];
        Thread t = new Thread(() -> provisioning[0] = StartupPhase.isProvisioning(),
                "main-worker-3");
        t.start();
        t.join(10_000);

        assertFalse(provisioning[0],
                "a request thread whose name merely contains 'main' was treated as startup");
        // Explicitly outside a window: this is what a request thread sees in production.
        assertFalse(StartupPhase.isProvisioning());
    }

    @Test
    @DisplayName("the store layer asks StartupPhase, not the thread name")
    void theStoreLayerAsksStartupPhase() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/dao/impl/couch/connector/CloudantClientWrapper.java"));
        // The METHOD BODY, not the absence of one spelling. Asserting only that the old
        // spelling is gone let `isProvisioning() || threadName.contains("main")` pass — the
        // defect returns while both assertions stay green. What must hold is that the
        // decision is nothing but the delegation.
        String body = JavaSource.methodBody(source, "private boolean isStartupPhase()");
        assertTrue(body.contains("StartupPhase.isProvisioning()"),
                "the wrapper no longer asks the declared window: " + body);
        assertFalse(body.contains("Thread") || body.contains("getName"),
                "the decision consults the thread again — `isProvisioning() || "
                        + "Thread.currentThread().getName().contains(\"main\")` passed the "
                        + "previous version of this check: " + body);
    }

    @Test
    @DisplayName("provisioning declares the window around its own run")
    void provisioningDeclaresTheWindow() throws Exception {
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/init/DatabasePreInitializer.java"));
        // The STRUCTURE, not two independent substrings. The first version asserted that
        // "} finally {" and "StartupPhase.end();" each appeared SOMEWHERE in the file —
        // and this file has another, unrelated finally, so removing the try/finally around
        // provisioning left both assertions green while a leaked begin() would hold the
        // grace open for the life of the process.
        String normalized = source.replaceAll("\\s+", " ");
        assertTrue(normalized.contains(
                "StartupPhase.begin(); try { provisionDatabases(event); } finally {"
                        + " StartupPhase.end(); }"),
                "provisioning no longer opens the window inside a try/finally — a begin() "
                        + "without its end() leaves the grace on for the whole process");
    }
}
