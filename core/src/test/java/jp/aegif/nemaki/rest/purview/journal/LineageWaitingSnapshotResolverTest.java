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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.journal.LineageWaitingSnapshotResolver.Candidate;
import jp.aegif.nemaki.rest.purview.journal.LineageWaitingSnapshotResolver.Resolution;

/**
 * History is not corruption, and a delivery id is not a clock.
 *
 * <p>Both were wrong in the first version. Treating any attribute difference as corruption made
 * every renamed object permanently unreconstructable; sorting by delivery id produced an order
 * unrelated to when anything happened, so "the latest" could be the earliest.
 */
public class LineageWaitingSnapshotResolverTest {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";
    private static final String QN = "nemaki://bedroom/objects/doc-1";
    private static final EndpointKind KIND = EndpointKind.CMIS_DOCUMENT;

    private static LineageCatalogObligation obligation() {
        return new LineageCatalogObligation(null,
                LineageCatalogObligation.taskKey(TARGET, REPO, KIND, QN), TARGET, REPO, KIND, QN,
                LineageCatalogObligation.State.CLAIMED, "node-1", "tok", 9999L, 0L, 0, 1L,
                LineageCatalogObligation.Outcome.NONE, null, null);
    }

    private static LineageWaitingSnapshot snapshot(String name,
            LineageSourceDisposition disposition) {
        return LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of("name", name),
                disposition, 2);
    }

    /** An original observation: its delivery is its observation. */
    private static Candidate at(long sequence, String deliveryId, LineageWaitingSnapshot snap) {
        return new Candidate(new LineageJournalOrder(REPO, sequence, deliveryId),
                new LineageObservationProvenance(
                        LineageObservationProvenance.LineageDeliveryKind.ORIGINAL,
                        deliveryId, deliveryId, sequence, sequence, "2026-01-01T00:00:00Z",
                        snap.evidenceDigest()),
                snap);
    }

    /** A replay: a NEW delivery sequence carrying an OLD observation. */
    private static Candidate replayOf(long deliverySequence, String deliveryId,
            long originSequence, String originDeliveryId, LineageWaitingSnapshot snap) {
        return new Candidate(new LineageJournalOrder(REPO, deliverySequence, deliveryId),
                new LineageObservationProvenance(
                        LineageObservationProvenance.LineageDeliveryKind.REPLAY,
                        deliveryId, originDeliveryId, deliverySequence, originSequence,
                        "2026-01-01T00:00:00Z", snap.evidenceDigest()),
                snap);
    }

    private static LineageWaitingSnapshotResolver over(List<Candidate> candidates) {
        return new LineageWaitingSnapshotResolver(taskKey -> candidates);
    }

    private static LineageWaitingSnapshot resolved(List<Candidate> candidates) {
        return assertInstanceOf(Resolution.LatestWaitingSnapshot.class, over(candidates).resolve(obligation()))
                .snapshot();
    }

    @Nested
    @DisplayName("ordinary history")
    class History {

        /** The case that broke: a rename made the object permanently unreconstructable. */
        @Test
        @DisplayName("a rename is history, and the later name wins")
        void renameIsNotCorruption() {
            assertEquals("new.txt", resolved(List.of(
                    at(10L, "d-1", snapshot("old.txt", LineageSourceDisposition.SOURCE_PURGED)),
                    at(20L, "d-2", snapshot("new.txt", LineageSourceDisposition.SOURCE_PURGED))))
                    .attributes().get("name"));
        }

        @Test
        @DisplayName("a move is history too")
        void moveIsNotCorruption() {
            LineageWaitingSnapshot before = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                    Map.of("folderPath", "/a"), LineageSourceDisposition.SOURCE_PURGED, 2);
            LineageWaitingSnapshot after = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                    Map.of("folderPath", "/b"), LineageSourceDisposition.SOURCE_PURGED, 2);

            assertEquals("/b", resolved(List.of(at(10L, "d-1", before), at(20L, "d-2", after)))
                    .attributes().get("folderPath"));
        }

        @Test
        @DisplayName("a version change is history")
        void versionChangeIsNotCorruption() {
            LineageWaitingSnapshot v1 = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                    Map.of("versionLabel", "1.0"), LineageSourceDisposition.SOURCE_PURGED, 2);
            LineageWaitingSnapshot v2 = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                    Map.of("versionLabel", "2.0"), LineageSourceDisposition.SOURCE_PURGED, 2);

            assertEquals("2.0", resolved(List.of(at(10L, "d-1", v1), at(20L, "d-2", v2)))
                    .attributes().get("versionLabel"));
        }

        /** An operator needs to know an object has history without being shown it. */
        @Test
        @DisplayName("the count of superseded snapshots is reported, never their content")
        void supersededCountIsReported() {
            Resolution.LatestWaitingSnapshot found = assertInstanceOf(Resolution.LatestWaitingSnapshot.class, over(List.of(
                    at(10L, "d-1", snapshot("a", LineageSourceDisposition.SOURCE_PURGED)),
                    at(20L, "d-2", snapshot("b", LineageSourceDisposition.SOURCE_PURGED)),
                    at(30L, "d-3", snapshot("c", LineageSourceDisposition.SOURCE_PURGED))))
                    .resolve(obligation()));

            assertEquals(2, found.supersededCount());
        }
    }

    @Nested
    @DisplayName("disposition transitions")
    class Dispositions {

        @Test
        @DisplayName("EXISTS then PURGED: the later PURGED is current")
        void existsThenPurged() {
            assertEquals(LineageSourceDisposition.SOURCE_PURGED, resolved(List.of(
                    at(10L, "d-1", snapshot("a", LineageSourceDisposition.SOURCE_EXISTS)),
                    at(20L, "d-2", snapshot("a", LineageSourceDisposition.SOURCE_PURGED))))
                    .sourceDisposition());
        }

        /** A restore. The latest says the source is back, so no tombstone may be built. */
        @Test
        @DisplayName("PURGED then EXISTS: the later EXISTS is current")
        void purgedThenExists() {
            assertEquals(LineageSourceDisposition.SOURCE_EXISTS, resolved(List.of(
                    at(10L, "d-1", snapshot("a", LineageSourceDisposition.SOURCE_PURGED)),
                    at(20L, "d-2", snapshot("a", LineageSourceDisposition.SOURCE_EXISTS))))
                    .sourceDisposition());
        }

        @Test
        @DisplayName("UNKNOWN then PURGED: the later PURGED is current")
        void unknownThenPurged() {
            assertEquals(LineageSourceDisposition.SOURCE_PURGED, resolved(List.of(
                    at(10L, "d-1", snapshot("a", LineageSourceDisposition.SOURCE_UNKNOWN)),
                    at(20L, "d-2", snapshot("a", LineageSourceDisposition.SOURCE_PURGED))))
                    .sourceDisposition());
        }

        /**
         * The hole the incomplete digest left: identical attributes, opposite dispositions, one
         * digest. A resolver comparing digests called that agreement — and a tombstone could be
         * built for a live object.
         */
        @Test
        @DisplayName("two snapshots differing only in disposition have different digests")
        void dispositionIsInTheDigest() {
            assertFalse(snapshot("a", LineageSourceDisposition.SOURCE_PURGED).evidenceDigest()
                    .equals(snapshot("a", LineageSourceDisposition.SOURCE_EXISTS)
                            .evidenceDigest()));
        }
    }

    @Nested
    @DisplayName("order")
    class Order {

        @Test
        @DisplayName("the view's return order does not change the answer")
        void viewOrderIsIrrelevant() {
            Candidate earlier = at(10L, "d-1",
                    snapshot("old.txt", LineageSourceDisposition.SOURCE_PURGED));
            Candidate later = at(20L, "d-2",
                    snapshot("new.txt", LineageSourceDisposition.SOURCE_PURGED));

            assertEquals("new.txt", resolved(List.of(earlier, later)).attributes().get("name"));
            assertEquals("new.txt", resolved(List.of(later, earlier)).attributes().get("name"));
        }

        /** The defect: a delivery id is a stable identifier, not a clock. */
        @Test
        @DisplayName("journal order wins when the delivery id sorts the other way")
        void deliveryIdIsNotTime() {
            // Sequence says "aaa" is later; lexicographic delivery id says "zzz" is.
            assertEquals("later.txt", resolved(List.of(
                    at(20L, "aaa", snapshot("later.txt", LineageSourceDisposition.SOURCE_PURGED)),
                    at(10L, "zzz", snapshot("earlier.txt",
                            LineageSourceDisposition.SOURCE_PURGED))))
                    .attributes().get("name"));
        }

        @Test
        @DisplayName("the delivery id breaks a tie deterministically")
        void deliveryIdBreaksTies() {
            LineageWaitingSnapshot same = snapshot("a", LineageSourceDisposition.SOURCE_PURGED);
            assertEquals(same.evidenceDigest(),
                    resolved(List.of(at(10L, "d-2", same), at(10L, "d-1", same)))
                            .evidenceDigest());
        }

        /** An UNSEQUENCED event has no place in the stream, so it cannot be ordered at all. */
        @Test
        @DisplayName("an unusable journal position is INDETERMINATE, not first")
        void unusablePositionIsIndeterminate() {
            assertInstanceOf(Resolution.Indeterminate.class, over(List.of(
                    at(0L, "d-1", snapshot("a", LineageSourceDisposition.SOURCE_PURGED))))
                    .resolve(obligation()));

            assertInstanceOf(Resolution.Indeterminate.class, over(List.of(
                    new Candidate(null,
                            new LineageObservationProvenance(
                                    LineageObservationProvenance.LineageDeliveryKind.ORIGINAL,
                                    "d-1", "d-1", 10L, 10L, "t", null),
                            snapshot("a", LineageSourceDisposition.SOURCE_PURGED))))
                    .resolve(obligation()));
        }
    }

    @Nested
    @DisplayName("replay and repair are not new observations")
    class Provenance {

        /**
         * The failure this whole slice exists for: a replay of an old PURGED observation
         * arrives after a restore, takes a higher delivery sequence, and would otherwise be
         * read as the latest evidence — authorising a tombstone over a live object.
         */
        @Test
        @DisplayName("a replayed PURGED does not outrank a later restore")
        void replayedPurgedDoesNotOutrankRestore() {
            LineageWaitingSnapshot purged =
                    snapshot("a", LineageSourceDisposition.SOURCE_PURGED);
            LineageWaitingSnapshot restored =
                    snapshot("a", LineageSourceDisposition.SOURCE_EXISTS);

            LineageWaitingSnapshot latest = resolved(List.of(
                    at(10L, "d-1", purged),
                    at(20L, "d-2", restored),
                    replayOf(30L, "d-3", 10L, "d-1", purged)));

            assertEquals(LineageSourceDisposition.SOURCE_EXISTS, latest.sourceDisposition(),
                    "a replay of the old purge must not become the latest observation");
        }

        @Test
        @DisplayName("a replayed EXISTS does not outrank a later purge")
        void replayedExistsDoesNotOutrankPurge() {
            LineageWaitingSnapshot exists =
                    snapshot("a", LineageSourceDisposition.SOURCE_EXISTS);
            LineageWaitingSnapshot purged =
                    snapshot("a", LineageSourceDisposition.SOURCE_PURGED);

            assertEquals(LineageSourceDisposition.SOURCE_PURGED, resolved(List.of(
                    at(10L, "d-1", exists),
                    at(20L, "d-2", purged),
                    replayOf(30L, "d-3", 10L, "d-1", exists))).sourceDisposition());
        }

        /** Five replays of one original are one observation delivered five times. */
        @Test
        @DisplayName("several replays of one original are deduped to one observation")
        void replaysAreDeduped() {
            LineageWaitingSnapshot purged =
                    snapshot("a", LineageSourceDisposition.SOURCE_PURGED);
            LineageWaitingSnapshot restored =
                    snapshot("a", LineageSourceDisposition.SOURCE_EXISTS);

            Resolution.LatestWaitingSnapshot found = assertInstanceOf(
                    Resolution.LatestWaitingSnapshot.class, over(List.of(
                            at(10L, "d-1", purged),
                            at(20L, "d-2", restored),
                            replayOf(30L, "d-3", 10L, "d-1", purged),
                            replayOf(40L, "d-4", 10L, "d-1", purged)))
                            .resolve(obligation()));

            assertEquals(LineageSourceDisposition.SOURCE_EXISTS,
                    found.snapshot().sourceDisposition());
            assertEquals(1, found.supersededCount(),
                    "two replays of one original must not count as two observations");
        }

        @Test
        @DisplayName("a replay whose origin cannot be traced is INDETERMINATE")
        void untraceableOriginIsIndeterminate() {
            LineageWaitingSnapshot purged =
                    snapshot("a", LineageSourceDisposition.SOURCE_PURGED);

            assertInstanceOf(Resolution.Indeterminate.class, over(List.of(
                    new Candidate(new LineageJournalOrder(REPO, 30L, "d-3"),
                            new LineageObservationProvenance(
                                    LineageObservationProvenance.LineageDeliveryKind.REPLAY,
                                    "d-3", null, 30L, 0L, "t", purged.evidenceDigest()),
                            purged))).resolve(obligation()));
        }

        /** A replay must carry what it claims to re-deliver. */
        @Test
        @DisplayName("an altered replay payload is corruption")
        void alteredReplayIsCorrupt() {
            LineageWaitingSnapshot original =
                    snapshot("original.txt", LineageSourceDisposition.SOURCE_PURGED);
            LineageWaitingSnapshot altered =
                    snapshot("tampered.txt", LineageSourceDisposition.SOURCE_PURGED);

            assertInstanceOf(Resolution.Corrupt.class, over(List.of(
                    new Candidate(new LineageJournalOrder(REPO, 30L, "d-3"),
                            new LineageObservationProvenance(
                                    LineageObservationProvenance.LineageDeliveryKind.REPLAY,
                                    "d-3", "d-1", 30L, 10L, "t", original.evidenceDigest()),
                            altered))).resolve(obligation()));
        }

        /** Two deliveries of one observation with different content cannot both be right. */
        @Test
        @DisplayName("two replays of one original that disagree are corruption")
        void disagreeingReplaysAreCorrupt() {
            LineageWaitingSnapshot one = snapshot("a", LineageSourceDisposition.SOURCE_PURGED);
            LineageWaitingSnapshot other = snapshot("b", LineageSourceDisposition.SOURCE_PURGED);

            assertInstanceOf(Resolution.Corrupt.class, over(List.of(
                    new Candidate(new LineageJournalOrder(REPO, 30L, "d-3"),
                            new LineageObservationProvenance(
                                    LineageObservationProvenance.LineageDeliveryKind.REPLAY,
                                    "d-3", "d-1", 30L, 10L, "t", one.evidenceDigest()),
                            one),
                    new Candidate(new LineageJournalOrder(REPO, 40L, "d-4"),
                            new LineageObservationProvenance(
                                    LineageObservationProvenance.LineageDeliveryKind.REPLAY,
                                    "d-4", "d-1", 40L, 10L, "t", other.evidenceDigest()),
                            other))).resolve(obligation()));
        }

        @Test
        @DisplayName("an original claiming to re-deliver something else is unusable")
        void originalMustBeItsOwnOrigin() {
            LineageWaitingSnapshot purged =
                    snapshot("a", LineageSourceDisposition.SOURCE_PURGED);

            assertInstanceOf(Resolution.Indeterminate.class, over(List.of(
                    new Candidate(new LineageJournalOrder(REPO, 10L, "d-1"),
                            new LineageObservationProvenance(
                                    LineageObservationProvenance.LineageDeliveryKind.ORIGINAL,
                                    "d-1", "d-other", 10L, 5L, "t", purged.evidenceDigest()),
                            purged))).resolve(obligation()));
        }

        @Test
        @DisplayName("the view's return order still does not change the answer")
        void orderIndependentWithReplays() {
            LineageWaitingSnapshot purged =
                    snapshot("a", LineageSourceDisposition.SOURCE_PURGED);
            LineageWaitingSnapshot restored =
                    snapshot("a", LineageSourceDisposition.SOURCE_EXISTS);
            List<Candidate> forward = List.of(at(10L, "d-1", purged), at(20L, "d-2", restored),
                    replayOf(30L, "d-3", 10L, "d-1", purged));
            List<Candidate> reversed = new java.util.ArrayList<>(forward);
            java.util.Collections.reverse(reversed);

            assertEquals(resolved(forward).evidenceDigest(),
                    resolved(reversed).evidenceDigest());
        }
    }

    @Nested
    @DisplayName("corruption")
    class Corruption {

        /** Same position, different content: one of these is not what the journal recorded. */
        @Test
        @DisplayName("two snapshots at one journal position disagree")
        void samePositionDifferentPayload() {
            assertInstanceOf(Resolution.Corrupt.class, over(List.of(
                    at(10L, "d-1", snapshot("a", LineageSourceDisposition.SOURCE_PURGED)),
                    at(10L, "d-1", snapshot("b", LineageSourceDisposition.SOURCE_PURGED))))
                    .resolve(obligation()));
        }

        @Test
        @DisplayName("a subject mismatch in any part is corruption")
        void subjectMismatch() {
            assertInstanceOf(Resolution.Corrupt.class, over(List.of(at(10L, "d-1",
                    LineageWaitingSnapshot.of("atlas", REPO, KIND, QN, Map.of(),
                            LineageSourceDisposition.SOURCE_PURGED, 2)))).resolve(obligation()));

            assertInstanceOf(Resolution.Corrupt.class, over(List.of(at(10L, "d-1",
                    LineageWaitingSnapshot.of(TARGET, REPO, EndpointKind.CMIS_FOLDER, QN,
                            Map.of(), LineageSourceDisposition.SOURCE_PURGED, 2))))
                    .resolve(obligation()));

            assertInstanceOf(Resolution.Corrupt.class, over(List.of(at(10L, "d-1",
                    LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN + "-other", Map.of(),
                            LineageSourceDisposition.SOURCE_PURGED, 2)))).resolve(obligation()));
        }

        /** Sequence numbers come from a per-repository counter; mixing them is meaningless. */
        @Test
        @DisplayName("candidates spanning repositories are corruption, not an ordering problem")
        void crossRepositoryIsCorrupt() {
            LineageWaitingSnapshot other =
                    snapshot("a", LineageSourceDisposition.SOURCE_PURGED);
            Candidate elsewhere = new Candidate(new LineageJournalOrder("canopy", 20L, "d-2"),
                    new LineageObservationProvenance(
                            LineageObservationProvenance.LineageDeliveryKind.ORIGINAL,
                            "d-2", "d-2", 20L, 20L, "t", other.evidenceDigest()),
                    other);

            assertInstanceOf(Resolution.Corrupt.class, over(List.of(
                    at(10L, "d-1", snapshot("a", LineageSourceDisposition.SOURCE_PURGED)),
                    elsewhere)).resolve(obligation()));
        }

        @Test
        @DisplayName("a candidate with no snapshot is corruption")
        void nullSnapshot() {
            assertInstanceOf(Resolution.Corrupt.class, over(java.util.Arrays.asList(
                    new Candidate(new LineageJournalOrder(REPO, 10L, "d-1"),
                            new LineageObservationProvenance(
                                    LineageObservationProvenance.LineageDeliveryKind.ORIGINAL,
                                    "d-1", "d-1", 10L, 10L, "t", null),
                            null)))
                    .resolve(obligation()));
        }
    }

    @Nested
    @DisplayName("nothing to resolve")
    class Absent {

        /**
         * The producer may have created the obligation and then failed the CAS that moves the
         * event to WAITING. Retryable, and specifically not an incomplete snapshot.
         */
        @Test
        @DisplayName("no waiting event is its own answer, neither corrupt nor incomplete")
        void noWaitingEvent() {
            assertInstanceOf(Resolution.NoWaitingEvent.class,
                    over(List.of()).resolve(obligation()));
        }

        @Test
        @DisplayName("a failed lookup is INDETERMINATE, never an empty result")
        void queryFailure() {
            assertInstanceOf(Resolution.Indeterminate.class,
                    new LineageWaitingSnapshotResolver(taskKey -> {
                        throw new IllegalStateException("view unavailable");
                    }).resolve(obligation()));
        }
    }
}
