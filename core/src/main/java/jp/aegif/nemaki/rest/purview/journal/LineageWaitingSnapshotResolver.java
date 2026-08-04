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
import java.util.List;

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
 * <h2>Different attributes are history, not corruption</h2>
 *
 * <p>An earlier version treated any disagreement between candidates as corruption. That is
 * wrong and it is worse than wrong: {@code name}, {@code folderPath} and {@code versionLabel}
 * change legitimately, so any object that had ever been renamed became permanently
 * unreconstructable. Several snapshots of one endpoint are the ordinary case — they are its
 * history.
 *
 * <p>So the candidates are <em>ordered</em> and the latest one is the current evidence.
 * Corruption is reserved for statements that cannot both be true: two snapshots at the same
 * journal position with different content, a snapshot about another subject, a coordinate that
 * cannot order anything.
 *
 * <h2>The order is the journal's, not the delivery id's</h2>
 *
 * <p>See {@link LineageJournalOrder}. Sorting by delivery id produces an order that has nothing
 * to do with time, so "the latest" under it can be the earliest that happened.
 */
public class LineageWaitingSnapshotResolver {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(LineageWaitingSnapshotResolver.class);

    /** What the reverse lookup produced. Exactly one of these is true. */
    public sealed interface Resolution {

        /**
         * The latest snapshot in journal order, and what it says about the source.
         *
         * @param supersededCount how many older snapshots there were; a count, never their
         *        content — an operator needs to know an object has history without reading it
         */
        record Found(LineageWaitingSnapshot snapshot, int supersededCount) implements Resolution { }

        /**
         * No event is waiting on this task.
         *
         * <p>Not corruption and <b>not</b> an incomplete snapshot: the producer may have
         * created the obligation and then failed the CAS that moves the event to
         * {@code WAITING_FOR_CATALOG}. Retryable, or an orphan candidate — never terminal.
         */
        record NoWaitingEvent() implements Resolution { }

        /**
         * Statements that cannot both be true, or material that cannot be ordered.
         *
         * @param reason fixed words; never a value, a name or a digest of one
         */
        record Corrupt(String reason) implements Resolution { }

        /**
         * Nothing was established — a failed query, or a coordinate that cannot order.
         *
         * <p>Separate from {@link Corrupt} because the responses differ: corruption is a fact
         * about the data that will not improve by waiting, and this may.
         */
        record Indeterminate(String reason) implements Resolution { }
    }

    /** Reads the waiting events for a task key. Narrow, so this class can be tested alone. */
    public interface WaitingEventSource {

        /** @return the candidates, in whatever order the store produced; this class orders them */
        List<Candidate> candidatesFor(String taskKey);
    }

    /** One waiting event's view of the endpoint a task names, with its place in the journal. */
    public record Candidate(LineageJournalOrder order, LineageWaitingSnapshot snapshot) { }

    private final WaitingEventSource source;

    public LineageWaitingSnapshotResolver(WaitingEventSource source) {
        this.source = source;
    }

    /**
     * The current snapshot for this obligation, or why there is not one.
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
            return new Resolution.Indeterminate("the waiting-event lookup failed");
        }
        if (candidates == null || candidates.isEmpty()) {
            return new Resolution.NoWaitingEvent();
        }

        List<Candidate> usable = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.snapshot() == null) {
                return new Resolution.Corrupt("a waiting event yielded no usable snapshot");
            }
            if (candidate.order() == null || !candidate.order().usable()) {
                // An UNSEQUENCED event has no place in the stream yet, so it cannot be called
                // earlier or later than anything. That is unknown, not first.
                return new Resolution.Indeterminate(
                        "a waiting event has no usable journal position");
            }
            if (!candidate.snapshot().describesSubject(obligation)) {
                // The task key is a hash of the subject, so a candidate under it describing
                // something else means the event's key set and the obligation disagree.
                return new Resolution.Corrupt(
                        "a waiting event's snapshot does not describe the obligation's subject");
            }
            if (!candidate.order().repositoryId().equals(obligation.repositoryId())) {
                // Sequence numbers come from a per-repository counter; ordering across
                // repositories is meaningless rather than merely wrong.
                return new Resolution.Corrupt(
                        "waiting events span more than one repository");
            }
            usable.add(candidate);
        }

        usable.sort((a, b) -> LineageJournalOrder.withinRepository()
                .compare(a.order(), b.order()));

        for (int i = 1; i < usable.size(); i++) {
            Candidate previous = usable.get(i - 1);
            Candidate current = usable.get(i);
            if (previous.order().samePositionAs(current.order())
                    && !previous.snapshot().evidenceDigest()
                            .equals(current.snapshot().evidenceDigest())) {
                // Same position in the journal, different content. One of these is not what
                // the journal recorded, and choosing either would make it so.
                return new Resolution.Corrupt(
                        "two waiting events at one journal position disagree");
            }
        }

        Candidate latest = usable.get(usable.size() - 1);
        return new Resolution.Found(latest.snapshot(), usable.size() - 1);
    }
}
