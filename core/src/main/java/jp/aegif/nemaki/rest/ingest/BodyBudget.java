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
package jp.aegif.nemaki.rest.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Closes a stalled body read after a budget, so a remote party cannot park a thread.
 *
 * <p>{@code HttpRequest.timeout()} covers the HEADERS only. It is satisfied the moment a
 * response line arrives, and with {@code BodyHandlers.ofInputStream()} the body is read
 * afterwards, by the caller, outside any timer the client keeps. Measured on Temurin 21: with a
 * 2-second request timeout the send returned in 23 ms, and a receiver that sent one byte and
 * then stopped left {@code read()} blocked with no exception until the JVM was killed.
 *
 * <p>Closing is the lever, not a clock check: a receiver that stalls blocks INSIDE
 * {@code read()}, so a loop testing a deadline at the top of each iteration never reaches the
 * test. Closing the response stream makes the blocked read throw.
 *
 * <p>A daemon thread per read. Not free, but the reads under it happen once per custody
 * transfer or once per anchoring, not once per request, and the alternative is a thread parked
 * for ever.
 *
 * <p>Written for the custody receiver ({@code SubmittedDigestRecovery}) and moved here when the
 * RFC 3161 CRL fetch (R65) needed the same bound: the second review of that batch found the
 * fetch had a byte cap and no time cap, and this class already existed two packages away
 * (2026-09-22). One implementation; a second copy would have been the duplicate this tree
 * refuses.
 */
public final class BodyBudget implements AutoCloseable {

    private final Thread watchdog;
    private final AtomicBoolean fired = new AtomicBoolean(false);

    /**
     * Starts the watchdog. When {@code budget} elapses before {@link #close()} is called, the
     * stream is closed from the watchdog thread and {@link #fired()} turns true.
     */
    public BodyBudget(InputStream body, Duration budget) {
        this.watchdog = new Thread(() -> {
            try {
                Thread.sleep(budget.toMillis());
            } catch (InterruptedException e) {
                return;
            }
            fired.set(true);
            closeQuietly(body);
        }, "body-budget");
        this.watchdog.setDaemon(true);
        this.watchdog.start();
    }

    /**
     * Whether the budget ran out.
     *
     * <p>Asked AFTER the read fails, because what the reader sees is an ordinary
     * {@code IOException} from a stream someone else closed — indistinguishable, at the catch
     * site, from the remote party dropping the connection. Reporting the wrong one sends an
     * operator to the network for a problem that is a stalled receiver, or the reverse.
     */
    public boolean fired() {
        return fired.get();
    }

    @Override
    public void close() {
        watchdog.interrupt();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the read that was blocked is the one that reports.
        }
    }
}
