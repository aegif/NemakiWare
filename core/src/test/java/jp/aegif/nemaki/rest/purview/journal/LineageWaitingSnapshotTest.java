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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The snapshot cannot be constructed in a state the historical builder would trust wrongly.
 *
 * <p>Every invariant is enforced in the canonical constructor rather than promised by a factory
 * comment, so no path — a hand-written {@code new}, a deserializer, a future refactor — can get
 * round it.
 */
public class LineageWaitingSnapshotTest {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";
    private static final String QN = "nemaki://bedroom/objects/doc-1";
    private static final EndpointKind KIND = EndpointKind.CMIS_DOCUMENT;

    private static LineageWaitingSnapshot valid() {
        return LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of("name", "a.txt"),
                LineageSourceDisposition.SOURCE_PURGED, 2);
    }

    @Nested
    @DisplayName("the evidence digest")
    class Digest {

        /**
         * Frozen. If this changes, every stored evidence digest stops matching, so it may only
         * change together with the {@code _V2} domain tag being bumped again.
         */
        @Test
        @DisplayName("golden vector")
        void goldenVector() {
            assertEquals(
                    LineageCanonicalHash.hash("LINEAGE_WAITING_SNAPSHOT_V2", TARGET, REPO,
                            "CMIS_DOCUMENT", QN, 2L, "SOURCE_PURGED",
                            new java.util.TreeMap<>(Map.of("name", "a.txt"))),
                    valid().evidenceDigest());
        }

        /** The hole the V1 formula left open. */
        @Test
        @DisplayName("covers the source disposition")
        void coversDisposition() {
            assertNotEquals(valid().evidenceDigest(),
                    LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of("name", "a.txt"),
                            LineageSourceDisposition.SOURCE_EXISTS, 2).evidenceDigest());
        }

        @Test
        @DisplayName("covers every other part of the subject")
        void coversSubject() {
            assertNotEquals(valid().evidenceDigest(),
                    LineageWaitingSnapshot.of("atlas", REPO, KIND, QN, Map.of("name", "a.txt"),
                            LineageSourceDisposition.SOURCE_PURGED, 2).evidenceDigest());
            assertNotEquals(valid().evidenceDigest(),
                    LineageWaitingSnapshot.of(TARGET, "canopy", KIND, QN, Map.of("name", "a.txt"),
                            LineageSourceDisposition.SOURCE_PURGED, 2).evidenceDigest());
            assertNotEquals(valid().evidenceDigest(),
                    LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of("name", "a.txt"),
                            LineageSourceDisposition.SOURCE_PURGED, 1).evidenceDigest());
        }

        @Test
        @DisplayName("does not depend on attribute order")
        void orderIndependent() {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", "a.txt");
            one.put("versionLabel", "1.0");
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("versionLabel", "1.0");
            other.put("name", "a.txt");

            assertEquals(
                    LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, one,
                            LineageSourceDisposition.SOURCE_PURGED, 2).evidenceDigest(),
                    LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, other,
                            LineageSourceDisposition.SOURCE_PURGED, 2).evidenceDigest());
        }

        /** A digest that does not describe its snapshot is refused, not recomputed. */
        @Test
        @DisplayName("a forged digest is refused")
        void forgedDigestIsRefused() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new LineageWaitingSnapshot(TARGET, REPO, KIND, QN,
                            Map.of("name", "a.txt"), LineageSourceDisposition.SOURCE_PURGED, 2,
                            "0".repeat(64)));
            assertTrue(refusal.getMessage().contains("does not describe"));
        }

        @Test
        @DisplayName("a malformed digest is refused")
        void malformedDigestIsRefused() {
            for (String bad : List.of("", "abc", "0".repeat(63), "0".repeat(65),
                    "G".repeat(64), "A".repeat(64))) {
                assertThrows(IllegalArgumentException.class,
                        () -> new LineageWaitingSnapshot(TARGET, REPO, KIND, QN,
                                Map.of("name", "a.txt"),
                                LineageSourceDisposition.SOURCE_PURGED, 2, bad));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new LineageWaitingSnapshot(TARGET, REPO, KIND, QN,
                            Map.of("name", "a.txt"),
                            LineageSourceDisposition.SOURCE_PURGED, 2, null));
        }

        /** The factory and the constructor must reach the same verdict. */
        @Test
        @DisplayName("the constructor accepts exactly what the factory produces")
        void constructorAndFactoryAgree() {
            LineageWaitingSnapshot made = valid();
            LineageWaitingSnapshot rebuilt = new LineageWaitingSnapshot(made.target(),
                    made.repositoryId(), made.endpointKind(), made.catalogQualifiedName(),
                    made.attributes(), made.sourceDisposition(), made.snapshotSchemaVersion(),
                    made.evidenceDigest());

            assertEquals(made, rebuilt);
        }
    }

    @Nested
    @DisplayName("attributes")
    class Attributes {

        /**
         * {@code Map.copyOf} is shallow, so a nested list would stay mutable through the
         * caller's reference and the snapshot could change under a digest describing the old
         * contents. Rejected rather than deep-copied — §2 forbids them in endpoints anyway.
         */
        @Test
        @DisplayName("a nested list or map is refused, not deep-copied")
        void nonScalarsAreRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                            Map.of("name", List.of("a", "b")),
                            LineageSourceDisposition.SOURCE_PURGED, 2));
            assertThrows(IllegalArgumentException.class,
                    () -> LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                            Map.of("name", Map.of("nested", "x")),
                            LineageSourceDisposition.SOURCE_PURGED, 2));
            assertThrows(IllegalArgumentException.class,
                    () -> LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                            Map.of("name", new String[] {"a"}),
                            LineageSourceDisposition.SOURCE_PURGED, 2));
            assertThrows(IllegalArgumentException.class,
                    () -> LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                            Map.of("name", new Object()),
                            LineageSourceDisposition.SOURCE_PURGED, 2));
        }

        @Test
        @DisplayName("permitted scalars pass")
        void scalarsPass() {
            LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                    Map.of("name", "a.txt", "contentLength", 42L),
                    LineageSourceDisposition.SOURCE_PURGED, 2);

            assertEquals(42L, snapshot.attributes().get("contentLength"));
        }

        /** An attribute the catalog would drop must not be in the digest either. */
        @Test
        @DisplayName("an attribute outside the kind's allowlist is refused")
        void allowlistIsEnforced() {
            assertThrows(IllegalArgumentException.class,
                    () -> LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                            Map.of("somethingInvented", "x"),
                            LineageSourceDisposition.SOURCE_PURGED, 2));
            // …and one that IS on the kind's list passes.
            assertTrue(KIND.isAllowedAttribute("versionLabel"));
        }

        /** A snapshot is published material too, so the same gate applies. */
        @Test
        @DisplayName("a value the secret boundary refuses cannot be held waiting")
        void secretBoundaryApplies() {
            assertThrows(RuntimeException.class,
                    () -> LineageWaitingSnapshot.of(TARGET, REPO, EndpointKind.EXTERNAL_ASSET,
                            QN, Map.of("externalPath", "https://host/x?token=abc"),
                            LineageSourceDisposition.SOURCE_PURGED, 2));
        }

        @Test
        @DisplayName("attributes cannot be modified after construction")
        void immutable() {
            Map<String, Object> mutable = new LinkedHashMap<>();
            mutable.put("name", "a.txt");
            LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                    mutable, LineageSourceDisposition.SOURCE_PURGED, 2);

            mutable.put("name", "changed");
            assertEquals("a.txt", snapshot.attributes().get("name"));
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.attributes().put("versionLabel", "x"));
        }

        @Test
        @DisplayName("mandatory attributes are checked structurally, blank counting as absent")
        void mandatoryAttributes() {
            LineageWaitingSnapshot complete = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                    Map.of("name", "a.txt", "versionLabel", "1.0"),
                    LineageSourceDisposition.SOURCE_PURGED, 2);
            LineageWaitingSnapshot blank = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                    Map.of("name", "   "), LineageSourceDisposition.SOURCE_PURGED, 2);

            assertTrue(complete.hasAll(List.of("name", "versionLabel")));
            assertFalse(complete.hasAll(List.of("name", "contentHash")));
            assertFalse(blank.hasAll(List.of("name")));
            assertTrue(complete.hasAll(null));
        }
    }

    @Nested
    @DisplayName("schema version")
    class SchemaVersion {

        /** Outside the range is not "missing a field" — the contents cannot be read at all. */
        @Test
        @DisplayName("an unsupported version is refused")
        void unsupportedVersionIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of(),
                            LineageSourceDisposition.SOURCE_PURGED, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of(),
                            LineageSourceDisposition.SOURCE_PURGED, 99));
        }

        @Test
        @DisplayName("supported versions pass")
        void supportedVersionsPass() {
            assertEquals(1, LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of(),
                    LineageSourceDisposition.SOURCE_PURGED, 1).snapshotSchemaVersion());
            assertEquals(2, valid().snapshotSchemaVersion());
        }
    }

    @Nested
    @DisplayName("the historical gate")
    class HistoricalGate {

        private LineageCatalogObligation obligation() {
            return new LineageCatalogObligation(null,
                    LineageCatalogObligation.taskKey(TARGET, REPO, KIND, QN), TARGET, REPO, KIND,
                    QN, LineageCatalogObligation.State.CLAIMED, "node-1", "tok", 9999L, 0L, 0, 1L,
                    LineageCatalogObligation.Outcome.NONE, null, null);
        }

        private LineageSourceDispositionResolver.SourceEvidence purgedSource() {
            return new LineageSourceDispositionResolver.SourceEvidence(
                    LineageSourceDisposition.SOURCE_PURGED, "inc-1", "rev-1", 1000L, null);
        }

        /** The whole point of the type: the publisher cannot be handed a live source. */
        @Test
        @DisplayName("only a purged snapshot converts")
        void onlyPurgedSnapshotConverts() {
            assertTrue(HistoricalEntitySnapshot.from(valid(), obligation(), TARGET,
                    purgedSource()).isPresent());

            for (LineageSourceDisposition live : List.of(LineageSourceDisposition.SOURCE_EXISTS,
                    LineageSourceDisposition.SOURCE_UNKNOWN)) {
                assertTrue(HistoricalEntitySnapshot.from(
                        LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of("name", "a.txt"),
                                live, 2), obligation(), TARGET, purgedSource()).isEmpty(),
                        live + " must not be publishable as a historical entity");
            }
        }

        /**
         * The replay hole: a snapshot saying PURGED may be a re-delivery of an observation
         * from before a restore. Only the repository can say whether the object is gone now.
         */
        @Test
        @DisplayName("a purged snapshot alone does not convert — the source must agree")
        void snapshotAloneIsNotEnough() {
            assertTrue(HistoricalEntitySnapshot.from(valid(), obligation(), TARGET, null)
                    .isEmpty());

            for (LineageSourceDisposition notPurged : List.of(
                    LineageSourceDisposition.SOURCE_EXISTS,
                    LineageSourceDisposition.SOURCE_UNKNOWN)) {
                assertTrue(HistoricalEntitySnapshot.from(valid(), obligation(), TARGET,
                        new LineageSourceDispositionResolver.SourceEvidence(notPurged, null,
                                null, 1000L, null)).isEmpty(),
                        "an authoritative " + notPurged + " must veto a PURGED snapshot");
            }
        }

        @Test
        @DisplayName("a subject or target mismatch does not convert")
        void mismatchDoesNotConvert() {
            assertTrue(HistoricalEntitySnapshot.from(valid(), obligation(), "atlas",
                    purgedSource()).isEmpty(),
                    "a historical entity must not be written to a catalog the task does not name");
            assertTrue(HistoricalEntitySnapshot.from(valid(), obligation(), null, purgedSource())
                    .isEmpty());
            assertTrue(HistoricalEntitySnapshot.from(null, obligation(), TARGET, purgedSource())
                    .isEmpty());
            assertTrue(HistoricalEntitySnapshot.from(valid(), null, TARGET, purgedSource())
                    .isEmpty());
        }

        /** Even the constructor refuses, so the check cannot be bypassed by not using from(). */
        @Test
        @DisplayName("the constructor refuses a live source and a missing verdict")
        void constructorRefuses() {
            assertThrows(IllegalArgumentException.class,
                    () -> new HistoricalEntitySnapshot(
                            LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN, Map.of(),
                                    LineageSourceDisposition.SOURCE_EXISTS, 2),
                            "task", purgedSource()));
            assertThrows(IllegalArgumentException.class,
                    () -> new HistoricalEntitySnapshot(valid(), "task", null));
        }

        /**
         * A purge verdict with nothing to point at cannot be re-checked before publishing, and
         * re-checking is what closes the restore-during-publish window.
         */
        @Test
        @DisplayName("a PURGED verdict without incarnation or revision is refused")
        void purgedVerdictNeedsSomethingToRecheck() {
            assertThrows(IllegalArgumentException.class,
                    () -> new LineageSourceDispositionResolver.SourceEvidence(
                            LineageSourceDisposition.SOURCE_PURGED, null, null, 1000L, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new LineageSourceDispositionResolver.SourceEvidence(
                            LineageSourceDisposition.SOURCE_PURGED, "inc-1", "  ", 1000L, null));
        }

        /** TOCTOU: a restore between the check and the write must stop the publish. */
        @Test
        @DisplayName("a changed incarnation withdraws the authorisation")
        void changedIncarnationWithdrawsAuthorisation() {
            HistoricalEntitySnapshot authorised = HistoricalEntitySnapshot
                    .from(valid(), obligation(), TARGET, purgedSource()).orElseThrow();

            assertTrue(authorised.stillAuthorised(purgedSource()));
            assertFalse(authorised.stillAuthorised(
                    new LineageSourceDispositionResolver.SourceEvidence(
                            LineageSourceDisposition.SOURCE_PURGED, "inc-2", "rev-1", 2000L,
                            null)),
                    "a restore makes a new incarnation; evidence from the old one authorises"
                            + " nothing about it");
            assertFalse(authorised.stillAuthorised(
                    new LineageSourceDispositionResolver.SourceEvidence(
                            LineageSourceDisposition.SOURCE_EXISTS, "inc-1", "rev-1", 2000L,
                            null)));
            assertFalse(authorised.stillAuthorised(
                    LineageSourceDispositionResolver.SourceEvidence.unknown(2000L)));
            assertFalse(authorised.stillAuthorised(null));
        }

        /** Evidence is read back on admin routes and put in logs. */
        @Test
        @DisplayName("source evidence carries no revision or incarnation in its description")
        void evidenceDescriptionLeaksNothing() {
            String description = purgedSource().toString();
            assertFalse(description.contains("inc-1"));
            assertFalse(description.contains("rev-1"));
            assertTrue(description.contains("SOURCE_PURGED"));
        }
    }

    /** The snapshot is logged and put in reports; its content is the object's own. */
    @Test
    @DisplayName("the description carries no qualified name and no attribute value")
    public void descriptionLeaksNothing() {
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                Map.of("name", "quarterly-results-confidential.xlsx"),
                LineageSourceDisposition.SOURCE_PURGED, 2);

        String description = snapshot.toString();
        assertFalse(description.contains(QN));
        assertFalse(description.contains("quarterly-results-confidential"));
        assertTrue(description.contains("<redacted:"));
    }
}
