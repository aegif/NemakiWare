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

import jp.aegif.nemaki.util.test.JavaSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A receiver that answers and then goes quiet must not hold a thread for ever.
 *
 * <h2>The contract that moved when the means did</h2>
 *
 * <p>{@code HttpRequest.timeout()} is satisfied the moment a response line arrives. While this
 * class used {@code BodyHandlers.ofString()} that was the whole read, so the timeout covered it.
 * Switching to {@code ofInputStream()} — done for a different reason, to bound the SIZE of what a
 * receiver may send — moved the body read outside every timer the client keeps, and the comment
 * saying the timeout was "short enough to fail rather than hang" stayed.
 *
 * <p>Measured on Temurin 21 before the fix: a 2-second request timeout returned in 23 ms, and a
 * server that sent one byte and stopped left {@code read()} blocked with no exception until the
 * process was killed. {@code MAX_BYTES} does not help: it bounds volume, and a byte a second
 * takes 68 years to reach 2 GiB.
 */
class StalledReceiverBudgetTest {

    @Test
    @DisplayName("a read that stalls is abandoned, and says it was abandoned")
    void aStalledReadIsAbandoned() throws Exception {
        // The fake has to behave like the real stream in the one way that matters: a read
        // blocked inside HttpClient's response body throws when the body is closed. The first
        // version of this fake just slept, so closing it did nothing, the sleep ran out, the
        // read returned -1 — and the test spent ten minutes proving nothing before failing.
        // A watchdog that closes is only a protection if closing is what unblocks the reader.
        CountDownLatch blocked = new CountDownLatch(1);
        InputStream stalling = new InputStream() {
            private final Object lock = new Object();
            private boolean closed;
            private boolean sentOne;

            @Override
            public int read() throws IOException {
                if (!sentOne) {
                    sentOne = true;
                    return 'x';
                }
                synchronized (lock) {
                    blocked.countDown();
                    while (!closed) {
                        try {
                            lock.wait(Duration.ofMinutes(5).toMillis());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted");
                        }
                    }
                    throw new IOException("stream closed under the reader");
                }
            }

            @Override
            public void close() {
                synchronized (lock) {
                    closed = true;
                    lock.notifyAll();
                }
            }
        };

        long started = System.nanoTime();
        try (SubmittedDigestRecovery.BodyBudget budget =
                new SubmittedDigestRecovery.BodyBudget(stalling, Duration.ofMillis(200))) {
            assertThrows(IOException.class, () -> {
                byte[] buffer = new byte[8192];
                while (stalling.read(buffer) != -1) {
                    // drain, exactly as hashOf and bodyOf do
                }
            }, "the stalled read was never interrupted, so the calling thread is parked");
            assertTrue(blocked.await(2, TimeUnit.SECONDS), "the stream never blocked, so this "
                    + "test is not exercising the case it exists for");
            assertTrue(budget.fired(),
                    "the read failed but the budget says it did not fire, so the failure would "
                            + "be reported as the network dropping rather than the receiver "
                            + "stalling");
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 30_000,
                "the read took " + elapsedMs + "ms, so it was not the budget that ended it");
    }

    @Test
    @DisplayName("a read that finishes inside its budget is untouched — the control")
    void aPromptReadIsNotDisturbed() throws Exception {
        InputStream prompt = new java.io.ByteArrayInputStream("a manifest line\n".getBytes());
        try (SubmittedDigestRecovery.BodyBudget budget =
                new SubmittedDigestRecovery.BodyBudget(prompt, Duration.ofSeconds(30))) {
            assertTrue(prompt.readAllBytes().length > 0);
            assertFalse(budget.fired(),
                    "an ordinary read was reported as a stall, which would turn every recovery "
                            + "into 'the receiver stopped answering'");
        }
    }

    @Test
    @DisplayName("BOTH streaming reads are under a budget, not just the one that was fixed")
    void everyStreamingReadIsBounded() throws Exception {
        // hashOf downloads a package and bodyOf downloads a manifest. They were written months
        // apart and each has been corrected once for something the other already had — the
        // unconsumed-body leak reached hashOf first and bodyOf later. Naming both here so a
        // third read cannot arrive unbounded.
        String source = JavaSource.withoutComments(JavaSource.read(
                "src/main/java/jp/aegif/nemaki/custody/connector/SubmittedDigestRecovery.java"));
        int opened = source.split("BodyHandlers\\.ofInputStream", -1).length - 1;
        int bounded = source.split("new BodyBudget\\(", -1).length - 1;
        assertTrue(opened >= 2, "fixture check: only " + opened + " streaming read(s) were found");
        assertTrue(bounded >= opened,
                opened + " streamed response(s) are opened but only " + bounded + " are under a "
                        + "body budget, so at least one can be stalled indefinitely");
    }
}
