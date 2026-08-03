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

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The one spool bundle for this node (A-2 Slice 4a).
 *
 * <p>Before 4a the spool was built in three places — the projection loop's private cache, a
 * throwaway probe inside the readiness gate, and (as of 4a) the emitter. Three instances of a
 * thing whose whole job is "one durable directory, one quarantine slot, one record-size bound"
 * is a defect waiting for a config change: the emitter could keep writing to the old volume
 * while the scanner read the new one.
 *
 * <p>The bundle is memoized on the NORMALIZED directory, and a directory change rebuilds it
 * atomically — scanner included, so the scanner's JVM-lifetime rotation cursor survives every
 * poll that does not change the directory.
 *
 * <h3>The pinned view</h3>
 *
 * <p>The materializer holds a resolver that answers from a {@link BarrierPin} rather than from
 * a live barrier read. Each scan entry point — the projection loop and the admin route — pins
 * the {@link LineageBarrierReader.BarrierView} it already evaluated for its admission
 * decision, runs the scan, and clears the pin in a {@code finally}. That matters because the
 * bundle is SHARED while scans are not: the admin route runs on a request thread, so a
 * bundle-wide mutable pin could leak between a manual and an automatic scan. The pin is a
 * {@link ThreadLocal}, so each scan sees its own; a scan with no pin resolves to unavailable
 * rather than falling back to a live read.
 */
@Component
public class LineageSpoolMachinery {

    /** Spool, scanner and materializer for one directory, rebuilt together or not at all. */
    public record Bundle(LineageFactSpool spool, LineageSpoolScanner scanner,
                         LineageSpoolMaterializer materializer, String directory) {
    }

    /**
     * The per-scan barrier view. Installed and removed by the scan's caller; absent means
     * "no version can be resolved", never "go and read one".
     */
    public static final class BarrierPin {

        private static final ThreadLocal<LineageBarrierReader.BarrierView> PINNED =
                new ThreadLocal<>();

        private BarrierPin() {
        }

        /** Runs {@code body} with {@code view} pinned, always removing it afterwards. */
        public static <T> T with(LineageBarrierReader.BarrierView view,
                java.util.function.Supplier<T> body) {
            LineageBarrierReader.BarrierView previous = PINNED.get();
            PINNED.set(view);
            try {
                return body.get();
            } finally {
                if (previous == null) {
                    PINNED.remove();
                } else {
                    PINNED.set(previous);
                }
            }
        }

        static LineageBarrierReader.BarrierView current() {
            return PINNED.get();
        }
    }

    /**
     * The materializer's version seam, answering from the pinned view.
     *
     * <p>{@code Pristine} resolves to v1 at barrier generation 0: a fact spooled while the
     * barrier was unreadable must CONVERGE once the absence is proven, and "no barrier" is a
     * legitimate, complete answer — leaving it unresolved forever would strand every fact
     * spooled during a transient outage.
     */
    static final class PinnedWriteVersionResolver implements WriteVersionResolver {

        @Override
        public Optional<ResolvedWrite> resolve(LineageSpoolPayloadV1 payload) {
            LineageBarrierReader.BarrierView view = BarrierPin.current();
            if (view instanceof LineageBarrierReader.BarrierView.Present present) {
                return Optional.of(new ResolvedWrite(
                        present.barrier().writeSchemaVersion(),
                        present.barrier().generation()));
            }
            if (view instanceof LineageBarrierReader.BarrierView.Pristine) {
                return Optional.of(new ResolvedWrite(1, 0L));
            }
            // Indeterminate, or no pin at all: no NEW decision is made. An already-frozen
            // decision still converges — that is §6-a's crash-convergence rule, and this seam
            // is not consulted for it.
            return Optional.empty();
        }
    }

    @Autowired(required = false)
    private LineageConfig lineageConfig;

    @Autowired(required = false)
    private LineageMetrics lineageMetrics;

    @Autowired(required = false)
    private LineageJournalStore journalStore;

    private volatile Bundle bundle;

    public LineageSpoolMachinery() {
    }

    LineageSpoolMachinery(LineageConfig lineageConfig, LineageMetrics lineageMetrics,
                          LineageJournalStore journalStore) {
        this.lineageConfig = lineageConfig;
        this.lineageMetrics = lineageMetrics;
        this.journalStore = journalStore;
    }

    /** The bundle for the CURRENT directory, or empty when none is configured. */
    public synchronized Optional<Bundle> bundle() {
        String dir = lineageConfig == null ? "" : lineageConfig.getSpoolDir();
        if (dir == null || dir.isBlank()) {
            return Optional.empty(); // tested BEFORE any Path construction
        }
        String normalized = Path.of(dir).normalize().toString();
        Bundle current = bundle;
        if (current != null && current.directory().equals(normalized)) {
            return Optional.of(current);
        }
        LineageFactSpool spool = new LineageFactSpool(Path.of(normalized), lineageMetrics);
        LineageSpoolScanner scanner = new LineageSpoolScanner(spool, lineageMetrics);
        LineageSpoolMaterializer materializer = null;
        if (journalStore instanceof LineageMaterializationStore materializations
                && journalStore instanceof LineageV2TransitionStore transitions) {
            materializer = new LineageSpoolMaterializer(materializations, journalStore,
                    transitions, new PinnedWriteVersionResolver(), spool, lineageMetrics,
                    () -> UUID.randomUUID().toString(), System::currentTimeMillis,
                    new LineageChunkPlanner.ChunkLimits(lineageConfig.getEndpointMaxPerEvent(),
                            lineageConfig.getEventMaxPayloadBytes()),
                    lineageConfig.getEventMaxDocumentBytes());
        }
        Bundle rebuilt = new Bundle(spool, scanner, materializer, normalized);
        bundle = rebuilt;
        return Optional.of(rebuilt);
    }

    /** The spool for the current directory, or empty when none is configured. */
    public Optional<LineageFactSpool> spool() {
        return bundle().map(Bundle::spool);
    }

    /**
     * The readiness probe — the same instance the writer and the scanner use, so a green probe
     * is a statement about the volume they will actually use.
     */
    public boolean probeReadiness() {
        return spool().map(LineageFactSpool::probeReadiness).orElse(false);
    }
}
