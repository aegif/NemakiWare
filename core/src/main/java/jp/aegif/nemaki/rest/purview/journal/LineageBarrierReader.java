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

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The single barrier read in the system (A-2 Slice 4a).
 *
 * <p>Four consumers ask this one question — the emitter, the materializer's resolver, reader
 * admission, and the admin status route — and they must all get the SAME answer, because the
 * first two decide where a fact goes and the last two decide whether this node may run at all.
 * Two independent classifiers is how a deployment ends up spooling on one path while admitting
 * on another.
 *
 * <h3>Why absence is not one state</h3>
 *
 * <p>A deployment that never created a barrier is <b>pristine</b>: it behaves exactly as it did
 * before 4a, writing v1. A deployment whose barrier <i>vanished</i> is not pristine, and
 * treating it as such would mean a fence could be undone by deleting one document. The witness
 * separates them: {@code prepare} writes it BEFORE the barrier, so a barrier can never be
 * durable without a witness that already is, and any read that meets a barrier with no witness
 * repairs it (a hand-created document is still a deliberate one).
 *
 * <p><b>Its limit, stated rather than implied:</b> replacing or restoring the whole
 * {@code nemaki_lineage} database removes the witness and the barrier together, while the
 * node-local spool survives — and this class cannot tell that from a fresh deployment.
 * Recreating or revalidating the fence after such a restore is an operator procedure, not
 * something the application can guarantee.
 *
 * <h3>Freshness</h3>
 *
 * <p>The emit path asks on every fact, so the view is memoized for
 * {@code lineage.barrier.view.ttl-ms} (default 1000). The lag is bounded and one-directional
 * in the safe sense: the flip to v2 is an operator action that is verified afterwards, and a
 * rollback to v1 lags by at most the TTL in the direction of "keep writing v2 a moment
 * longer", which the rollback contract already tolerates (it only governs which version NEW
 * facts are materialized at). Without it every emit would add an HTTP round trip.
 */
@Component
public class LineageBarrierReader {

    private static final Logger logger = LoggerFactory.getLogger(LineageBarrierReader.class);

    /** What the barrier says, once, for everyone. */
    public sealed interface BarrierView {

        /** The barrier exists and decoded. */
        record Present(LineageWriteVersionBarrier barrier) implements BarrierView {
        }

        /** Verified absent, with no witness: a deployment that never had a barrier. */
        record Pristine() implements BarrierView {
        }

        /** Unreadable, or absent AFTER a witness: we do not know, so we do not decide. */
        record Indeterminate(String reasonClass) implements BarrierView {
        }
    }

    /** The reason class for the one anomaly that is not an infrastructure failure. */
    public static final String BARRIER_VANISHED = "barrier_vanished";

    /** A barrier exists but its witness could not be made durable — fail closed. */
    public static final String WITNESS_UNCONFIRMED = "witness_unconfirmed";

    @Autowired(required = false)
    private LineageBarrierStore store;

    @Autowired(required = false)
    private LineageConfig lineageConfig;

    private final AtomicBoolean warnedAboutVanished = new AtomicBoolean(false);
    private volatile BarrierView cachedView;
    private volatile long cachedAtMs;
    /** While this is in the future, no memo may be installed or served (see invalidate). */
    private volatile long suppressMemoUntilMs;

    /** Test seam: a fixed clock keeps the memoization deterministic. */
    private java.util.function.LongSupplier clockMs = System::currentTimeMillis;

    public LineageBarrierReader() {
    }

    LineageBarrierReader(LineageBarrierStore store, LineageConfig lineageConfig,
                         java.util.function.LongSupplier clockMs) {
        this.store = store;
        this.lineageConfig = lineageConfig;
        this.clockMs = clockMs;
    }

    /**
     * The current view, memoized for the configured TTL.
     *
     * <p><b>What the memo does and does not widen.</b> Reading a flag and then acting on it is
     * inherently racy — an emit that read {@code Present(1)} a microsecond before activation
     * appends v1 a microsecond after it, memo or no memo. The memo widens that window to the
     * TTL, so activation closes it explicitly: {@link #invalidate()} both drops the memo and
     * SUPPRESSES installation for one TTL, which a bare {@code cachedView = null} would not do
     * (a read already in flight would install its stale answer straight afterwards).
     *
     * <p>The fence itself does not depend on this. {@code minReaderSchemaVersion} is what stops
     * an incompatible READER, and a boundary fact written as v1 is ordinary, complete lineage
     * — not a loss.
     */
    public BarrierView view() {
        long now = clockMs.getAsLong();
        BarrierView cached = cachedView;
        if (cached != null && now >= suppressMemoUntilMs
                && now - cachedAtMs < ttlMs() && now >= cachedAtMs) {
            return cached;
        }
        BarrierView fresh = readUncached();
        install(fresh, now);
        return fresh;
    }

    /** Reads through the memo — for the admin routes, which must never show a stale answer. */
    public BarrierView viewUncached() {
        // The stamp is taken BEFORE the read, exactly as view() does: a read that started
        // before an invalidation must not qualify to install just because it finished after.
        long startedAt = clockMs.getAsLong();
        BarrierView fresh = readUncached();
        install(fresh, startedAt);
        return fresh;
    }

    private synchronized void install(BarrierView view, long atMs) {
        if (atMs < suppressMemoUntilMs) {
            return; // a read that started before an invalidation must not become the memo
        }
        cachedView = view;
        cachedAtMs = atMs;
    }

    /**
     * Drops the memo AND suppresses installation for one TTL, so a read already in flight
     * cannot reinstate the answer that was just superseded. Called after every barrier write.
     */
    public synchronized void invalidate() {
        cachedView = null;
        suppressMemoUntilMs = clockMs.getAsLong() + ttlMs();
    }

    private long ttlMs() {
        return lineageConfig == null ? 1000L : lineageConfig.getBarrierViewTtlMs();
    }

    private BarrierView readUncached() {
        if (store == null) {
            // No seam wired: this is the pre-4a construction, not a deployment state.
            return new BarrierView.Pristine();
        }
        try {
            java.util.Map<String, Object> raw = store.readBarrierRaw();
            if (raw == null) {
                boolean witnessed = store.readWitness() != null;
                if (witnessed) {
                    if (warnedAboutVanished.compareAndSet(false, true)) {
                        logger.error("The write-version barrier is gone but this node has seen"
                                + " one before — refusing to treat that as a pristine"
                                + " deployment. Facts spool until it is restored.");
                    }
                    return new BarrierView.Indeterminate(BARRIER_VANISHED);
                }
                return new BarrierView.Pristine();
            }
            LineageWriteVersionBarrier barrier = LineageBarrierCodec.decode(raw);
            // Repair: a hand-created barrier is still deliberate, and the witness must exist
            // before the next read can be fooled by its deletion. This write cannot happen on
            // a barrier-less deployment — we only get here having READ a barrier.
            //
            // It is FAIL-CLOSED. A Present answer here means "there is a barrier AND its
            // deletion will be detectable"; returning Present with no durable witness would
            // let the very next delete look pristine, which is the hole the witness exists
            // to close.
            boolean witnessed;
            try {
                witnessed = store.writeWitnessIfAbsent(clockMs.getAsLong());
            } catch (RuntimeException e) {
                logger.error("Could not record the barrier witness: {}", e.getMessage());
                witnessed = false;
            }
            if (!witnessed) {
                return new BarrierView.Indeterminate(WITNESS_UNCONFIRMED);
            }
            return new BarrierView.Present(barrier);
        } catch (RuntimeException e) {
            logger.warn("Barrier read failed ({}) — facts spool until it is readable",
                    e.getMessage());
            return new BarrierView.Indeterminate(e.getClass().getSimpleName());
        }
    }
}
