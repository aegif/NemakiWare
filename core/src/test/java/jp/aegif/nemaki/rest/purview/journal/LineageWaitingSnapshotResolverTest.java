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

    private static Candidate at(long sequence, String deliveryId, LineageWaitingSnapshot snap) {
        return new Candidate(new LineageJournalOrder(REPO, sequence, deliveryId), snap);
    }

    private static LineageWaitingSnapshotResolver over(List<Candidate> candidates) {
        return new LineageWaitingSnapshotResolver(taskKey -> candidates);
    }

    private static LineageWaitingSnapshot resolved(List<Candidate> candidates) {
        return assertInstanceOf(Resolution.Found.class, over(candidates).resolve(obligation()))
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
            Resolution.Found found = assertInstanceOf(Resolution.Found.class, over(List.of(
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
                    new Candidate(null, snapshot("a", LineageSourceDisposition.SOURCE_PURGED))))
                    .resolve(obligation()));
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
            Candidate elsewhere = new Candidate(new LineageJournalOrder("canopy", 20L, "d-2"),
                    snapshot("a", LineageSourceDisposition.SOURCE_PURGED));

            assertInstanceOf(Resolution.Corrupt.class, over(List.of(
                    at(10L, "d-1", snapshot("a", LineageSourceDisposition.SOURCE_PURGED)),
                    elsewhere)).resolve(obligation()));
        }

        @Test
        @DisplayName("a candidate with no snapshot is corruption")
        void nullSnapshot() {
            assertInstanceOf(Resolution.Corrupt.class, over(java.util.Arrays.asList(
                    new Candidate(new LineageJournalOrder(REPO, 10L, "d-1"), null)))
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
