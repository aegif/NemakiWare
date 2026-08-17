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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * May this node's lineage READER run at all? (§6-a's startup fence, A-2 Slice 4a.)
 *
 * <p>{@code minReaderSchemaVersion} only ever increases, and once it reaches 2 a node that
 * reads only v1 must not participate: its cursor would advance past v2 rows it cannot decode,
 * and the events would be silently lost. §6-a therefore requires such a node to fail closed.
 *
 * <p><b>It is a gate, not an exception.</b> Throwing from a bean's {@code @PostConstruct}
 * would fail the whole application context, taking CMIS down because a lineage flag moved.
 * This is evaluated per tick instead, so it also self-heals: a CouchDB outage at startup
 * leaves the reader UNDETERMINED rather than permanently disabled, and the next tick admits it.
 *
 * <p>The verdict is derived from the SAME {@link LineageBarrierReader.BarrierView} the emitter
 * uses. Two classifiers reading the same document independently is exactly how a deployment
 * ends up spooling on one path while admitting on another.
 */
@Component
public class LineageReaderAdmission {

    private static final Logger logger = LoggerFactory.getLogger(LineageReaderAdmission.class);

    /** ADMITTED: run. REFUSED: this build must not read. UNDETERMINED: we cannot tell. */
    public enum Decision { ADMITTED, REFUSED, UNDETERMINED }

    /** The decision plus its evidence — never a bare boolean. */
    public record Admission(Decision decision, List<String> violations,
                            LineageBarrierReader.BarrierView view) {
        public Admission {
            violations = List.copyOf(violations);
        }

        public boolean admitted() {
            return decision == Decision.ADMITTED;
        }
    }

    @Autowired(required = false)
    private LineageBarrierReader barrierReader;

    @Autowired(required = false)
    private LineageConfig lineageConfig;

    private final AtomicReference<Decision> lastLogged = new AtomicReference<>();

    public LineageReaderAdmission() {
    }

    LineageReaderAdmission(LineageBarrierReader barrierReader, LineageConfig lineageConfig) {
        this.barrierReader = barrierReader;
        this.lineageConfig = lineageConfig;
    }

    /** Evaluates admission from a fresh (memoized) barrier view. */
    public Admission evaluate() {
        if (barrierReader == null) {
            // Pre-4a construction: no barrier machinery at all, so no fence to enforce.
            return log(new Admission(Decision.ADMITTED, List.of(),
                    new LineageBarrierReader.BarrierView.Pristine()));
        }
        return evaluate(barrierReader.view());
    }

    /** Evaluates admission from an already-obtained view, so one tick uses one answer. */
    public Admission evaluate(LineageBarrierReader.BarrierView view) {
        if (view instanceof LineageBarrierReader.BarrierView.Indeterminate indeterminate) {
            return log(new Admission(Decision.UNDETERMINED,
                    List.of("the barrier is unreadable (" + indeterminate.reasonClass()
                            + "), so this node cannot prove it may read"), view));
        }
        // The version this node MUST be able to read to participate: whatever the barrier's
        // floor is, or 1 where there is no barrier at all.
        int required = view instanceof LineageBarrierReader.BarrierView.Present present
                ? present.barrier().minReaderSchemaVersion() : 1;
        Set<Integer> readable = lineageConfig == null ? Set.of(1, 2)
                : lineageConfig.getReadSchemaVersions();
        if (!readable.contains(required)) {
            // This also catches a node that declares it reads NOTHING — an unparseable
            // lineage.read.schema.versions resolves to the empty set, and a reader that
            // cannot decode what it is about to claim must not claim it.
            return log(new Admission(Decision.REFUSED,
                    List.of("this node reads " + (readable.isEmpty() ? "nothing" : readable)
                            + " but participating here requires reading v" + required
                            + " — a reader that cannot decode a row would still advance its"
                            + " cursor past it"), view));
        }
        return log(new Admission(Decision.ADMITTED, List.of(), view));
    }

    private Admission log(Admission admission) {
        Decision previous = lastLogged.getAndSet(admission.decision());
        if (previous != admission.decision()) {
            switch (admission.decision()) {
                case REFUSED -> logger.error("Lineage reader REFUSED: {}",
                        admission.violations());
                case UNDETERMINED -> logger.warn("Lineage reader admission UNDETERMINED: {}",
                        admission.violations());
                case ADMITTED -> {
                    if (previous != null) {
                        logger.info("Lineage reader admitted");
                    }
                }
            }
        }
        return admission;
    }
}
