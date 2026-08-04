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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds the endpoint snapshot an obligation is about, from the events waiting on it.
 *
 * <h2>Why the obligation does not carry the snapshot</h2>
 *
 * <p>The v2 event already holds the full payload durably, and it is the record of what was
 * actually observed. Copying the snapshot onto the obligation would create a second copy that
 * can disagree with the first, and the obligation is the thing that gets retried, reclaimed and
 * re-created — precisely the document you would least like to be a source of truth about
 * content.
 *
 * <h2>Corruption is not incompleteness</h2>
 *
 * <p>A snapshot that does not describe the obligation's subject, an event that cannot be
 * decoded, a query that failed — none of these are "the snapshot lacks a field". They are
 * states where nothing was established, and the historical builder must not conclude anything
 * terminal from them. {@link Resolution} keeps them apart by construction.
 *
 * <h2>Several events, one task</h2>
 *
 * <p>Many events can wait for one entity. Candidates are examined in a stable order (by delivery
 * id) rather than "whichever row the view returned first", so the same inputs always produce the
 * same snapshot. If two candidates describe the same subject with <em>different</em> evidence
 * digests, that is a disagreement about content and is reported as corruption rather than
 * settled by picking one.
 */
public class LineageWaitingSnapshotResolver {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(LineageWaitingSnapshotResolver.class);

    /** What the reverse lookup produced. Exactly one of these is true. */
    public sealed interface Resolution {

        /** A single, subject-matching, self-consistent snapshot. */
        record Found(LineageWaitingSnapshot snapshot) implements Resolution { }

        /** No event is waiting on this task. Not corruption — the wait may have ended. */
        record NoWaitingEvent() implements Resolution { }

        /**
         * Something is wrong with the material: a mismatched subject, an undecodable event,
         * candidates that disagree, or a query that failed.
         *
         * @param reason fixed words; never a value, a name or a digest of one
         */
        record Corrupt(String reason) implements Resolution { }
    }

    /** Reads the waiting events for a task key. Narrow, so this class can be tested alone. */
    public interface WaitingEventSource {

        /**
         * @return the endpoints, in whatever order the store produced; this class sorts them
         */
        List<Candidate> candidatesFor(String taskKey);
    }

    /**
     * One waiting event's view of the endpoint a task names.
     *
     * @param orderingKey a stable, total ordering across candidates — the delivery id
     */
    public record Candidate(String orderingKey, LineageWaitingSnapshot snapshot) { }

    private final WaitingEventSource source;

    public LineageWaitingSnapshotResolver(WaitingEventSource source) {
        this.source = source;
    }

    /**
     * The snapshot for this obligation, or why there is not one.
     *
     * <p>Every candidate must describe the obligation's subject exactly. A near miss is not a
     * weaker match — an entity rebuilt from another repository's snapshot would be wrong in a
     * way nothing downstream could detect.
     */
    public Resolution resolve(LineageCatalogObligation obligation) {
        if (obligation == null) {
            return new Resolution.Corrupt("no obligation was supplied");
        }
        List<Candidate> candidates;
        try {
            candidates = source.candidatesFor(obligation.taskKey());
        } catch (RuntimeException e) {
            // A query that failed established nothing. Class name only: a store message can
            // echo a document, and a v2 document carries endpoint attributes.
            logger.warn("Waiting-event lookup failed: {}", e.getClass().getSimpleName());
            return new Resolution.Corrupt("the waiting-event lookup failed");
        }
        if (candidates == null || candidates.isEmpty()) {
            return new Resolution.NoWaitingEvent();
        }

        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(c -> c == null || c.orderingKey() == null ? ""
                : c.orderingKey()));

        LineageWaitingSnapshot chosen = null;
        for (Candidate candidate : ordered) {
            if (candidate == null || candidate.snapshot() == null) {
                return new Resolution.Corrupt("a waiting event yielded no usable snapshot");
            }
            LineageWaitingSnapshot snapshot = candidate.snapshot();
            if (!snapshot.describesSubject(obligation)) {
                // The task key is a hash of the subject, so a candidate under it that describes
                // something else means the key set on the event and the obligation disagree.
                return new Resolution.Corrupt(
                        "a waiting event's snapshot does not describe the obligation's subject");
            }
            if (chosen == null) {
                chosen = snapshot;
            } else if (!chosen.evidenceDigest().equals(snapshot.evidenceDigest())) {
                // Same subject, different content. Choosing either would silently make one of
                // the two events' record of the object the one that survives.
                return new Resolution.Corrupt(
                        "waiting events disagree about the endpoint's snapshot");
            }
        }
        return new Resolution.Found(chosen);
    }
}
