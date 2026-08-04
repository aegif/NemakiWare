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

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The obligation's identity, its invariants, and what it refuses to be.
 *
 * <p>These are the properties the whole machine rests on: a deterministic key, so a restart and
 * a replay converge instead of multiplying; and a terminal state that cannot be reached for a
 * reason that would have gone away on its own.
 */
public class LineageCatalogObligationTest {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";
    private static final String QN = "nemaki://bedroom/folders/f-1/dataset";

    private static LineageCatalogObligation pending() {
        return new LineageCatalogObligation(null,
                LineageCatalogObligation.taskKey(TARGET, REPO, EndpointKind.CMIS_FOLDER, QN),
                TARGET, REPO, EndpointKind.CMIS_FOLDER, QN,
                LineageCatalogObligation.State.PENDING, null, null, 0L, 0L, 0, 1000L,
                LineageCatalogObligation.Outcome.NONE, null, null);
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        /** A restart, a replay and a duplicate delivery must converge on one document. */
        @Test
        @DisplayName("is the same for the same subject, every time")
        void deterministic() {
            assertEquals(
                    LineageCatalogObligation.taskKey(TARGET, REPO, EndpointKind.CMIS_FOLDER, QN),
                    LineageCatalogObligation.taskKey(TARGET, REPO, EndpointKind.CMIS_FOLDER, QN));
        }

        /** No clock is an input, so two obligations made a day apart are still one. */
        @Test
        @DisplayName("does not depend on when it was created")
        void timeIndependent() {
            LineageCatalogObligation early = pending();
            LineageCatalogObligation late = new LineageCatalogObligation(null, early.taskKey(),
                    TARGET, REPO, EndpointKind.CMIS_FOLDER, QN,
                    LineageCatalogObligation.State.PENDING, null, null, 0L, 0L, 7,
                    9_999_999_999L, LineageCatalogObligation.Outcome.NONE, null, null);

            assertEquals(early.taskKey(), late.taskKey());
            assertEquals(early.documentId(), late.documentId());
        }

        @Test
        @DisplayName("separates every part of the subject")
        void everyPartMatters() {
            String base = LineageCatalogObligation.taskKey(
                    TARGET, REPO, EndpointKind.CMIS_FOLDER, QN);

            assertNotEquals(base, LineageCatalogObligation.taskKey(
                    "atlas", REPO, EndpointKind.CMIS_FOLDER, QN));
            assertNotEquals(base, LineageCatalogObligation.taskKey(
                    TARGET, "canopy", EndpointKind.CMIS_FOLDER, QN));
            assertNotEquals(base, LineageCatalogObligation.taskKey(
                    TARGET, REPO, EndpointKind.CMIS_DOCUMENT, QN));
            assertNotEquals(base, LineageCatalogObligation.taskKey(
                    TARGET, REPO, EndpointKind.CMIS_FOLDER, QN + "x"));
        }

        /**
         * The typed encoding is what stops {@code ("ab","c")} and {@code ("a","bc")} from
         * colliding — an obligation key built by concatenation could be forged by an object
         * name containing the separator.
         */
        @Test
        @DisplayName("cannot be forged by moving a boundary between parts")
        void noConcatenationAmbiguity() {
            assertNotEquals(
                    LineageCatalogObligation.taskKey(
                            "purview", "bedroom", EndpointKind.CMIS_FOLDER, QN),
                    LineageCatalogObligation.taskKey(
                            "purviewbed", "room", EndpointKind.CMIS_FOLDER, QN));
        }

        /** Domain-tagged, so it cannot equal any other identity hash over the same parts. */
        @Test
        @DisplayName("is domain-separated from every other identity hash")
        void domainSeparated() {
            assertNotEquals(
                    LineageCanonicalHash.hash(TARGET, REPO, "CMIS_FOLDER", QN),
                    LineageCatalogObligation.taskKey(TARGET, REPO, EndpointKind.CMIS_FOLDER, QN));
        }

        @Test
        @DisplayName("the document id is the key under a fixed prefix")
        void documentId() {
            LineageCatalogObligation obligation = pending();
            assertEquals("lineage_catalog_obligation:" + obligation.taskKey(),
                    obligation.documentId());
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        /**
         * §2 binds both terminal states to a reason. An UNRESOLVED with no reason terminates an
         * event and leaves nobody able to review why.
         */
        @Test
        @DisplayName("a terminal state without a reason is refused")
        void terminalNeedsAReason() {
            assertThrows(IllegalArgumentException.class, () -> new LineageCatalogObligation(
                    null, "k", TARGET, REPO, EndpointKind.CMIS_FOLDER, QN,
                    LineageCatalogObligation.State.RESOLVED, null, null, 0L, 0L, 0, 1L,
                    LineageCatalogObligation.Outcome.SOURCE_EXISTS, null, null));
            assertThrows(IllegalArgumentException.class, () -> new LineageCatalogObligation(
                    null, "k", TARGET, REPO, EndpointKind.CMIS_FOLDER, QN,
                    LineageCatalogObligation.State.UNRESOLVED, null, null, 0L, 0L, 0, 1L,
                    LineageCatalogObligation.Outcome.NONE, "why", null));
        }

        /**
         * The one that turns a five-minute outage into a permanently unprojectable event.
         * Refused in the record itself, so no path can construct it.
         */
        @Test
        @DisplayName("a retryable failure cannot be recorded as terminal")
        void sourceErrorIsNeverUnresolved() {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new LineageCatalogObligation(null, "k", TARGET, REPO,
                            EndpointKind.CMIS_FOLDER, QN,
                            LineageCatalogObligation.State.UNRESOLVED, null, null, 0L, 0L, 0, 1L,
                            LineageCatalogObligation.Outcome.SOURCE_ERROR, "catalog down", null));
            assertTrue(refusal.getMessage().contains("retryable"));
        }

        @Test
        @DisplayName("a claim without an owner or a token is refused")
        void claimNeedsOwnerAndToken() {
            assertThrows(IllegalArgumentException.class, () -> new LineageCatalogObligation(
                    null, "k", TARGET, REPO, EndpointKind.CMIS_FOLDER, QN,
                    LineageCatalogObligation.State.CLAIMED, "node-1", null, 1L, 0L, 0, 1L,
                    LineageCatalogObligation.Outcome.NONE, null, null));
            assertThrows(IllegalArgumentException.class, () -> new LineageCatalogObligation(
                    null, "k", TARGET, REPO, EndpointKind.CMIS_FOLDER, QN,
                    LineageCatalogObligation.State.CLAIMED, null, "tok", 1L, 0L, 0, 1L,
                    LineageCatalogObligation.Outcome.NONE, null, null));
        }

        @Test
        @DisplayName("a lease is expired at its instant, not after it")
        void leaseBoundary() {
            LineageCatalogObligation claimed = new LineageCatalogObligation(null, "k", TARGET,
                    REPO, EndpointKind.CMIS_FOLDER, QN,
                    LineageCatalogObligation.State.CLAIMED, "node-1", "tok", 1000L, 0L, 0, 1L,
                    LineageCatalogObligation.Outcome.NONE, null, null);

            assertFalse(claimed.leaseExpired(999L));
            assertTrue(claimed.leaseExpired(1000L));
            assertTrue(claimed.leaseExpired(1001L));
            assertFalse(pending().leaseExpired(Long.MAX_VALUE), "PENDING holds no lease");
        }

        @Test
        @DisplayName("only RESOLVED and UNRESOLVED are terminal")
        void terminalStates() {
            assertFalse(pending().terminal());
            assertTrue(resolved().terminal());
        }
    }

    @Nested
    @DisplayName("durable capped backoff")
    class Backoff {

        /** Deterministic: the caller supplies the clock, so a test can state the answer. */
        @Test
        @DisplayName("grows with attempts and stops at the cap")
        void exponentialAndCapped() {
            assertEquals(1000L, LineageCatalogObligation.backoffUntil(1000L, 0, 100L, 800L),
                    "no failures yet means no wait");
            assertEquals(1100L, LineageCatalogObligation.backoffUntil(1000L, 1, 100L, 800L));
            assertEquals(1200L, LineageCatalogObligation.backoffUntil(1000L, 2, 100L, 800L));
            assertEquals(1400L, LineageCatalogObligation.backoffUntil(1000L, 3, 100L, 800L));
            assertEquals(1800L, LineageCatalogObligation.backoffUntil(1000L, 4, 100L, 800L));
            assertEquals(1800L, LineageCatalogObligation.backoffUntil(1000L, 5, 100L, 800L));
            assertEquals(1800L, LineageCatalogObligation.backoffUntil(1000L, 99, 100L, 800L),
                    "the cap holds however many times it failed");
        }

        /**
         * A clock that jumped backwards, or an addition that wrapped, must not produce a time
         * in the past — that would defeat the backoff exactly when the system is least well.
         */
        @Test
        @DisplayName("overflow becomes never, not now")
        void overflowIsFailClosed() {
            assertEquals(Long.MAX_VALUE, LineageCatalogObligation.backoffUntil(
                    Long.MAX_VALUE - 10L, 5, 1000L, 1_000_000L));
            assertTrue(LineageCatalogObligation.backoffUntil(Long.MAX_VALUE - 10L, 1, 1000L,
                    1_000_000L) >= Long.MAX_VALUE - 10L);
        }

        @Test
        @DisplayName("a PENDING obligation is not claimable before its time")
        void notClaimableDuringBackoff() {
            LineageCatalogObligation backingOff = new LineageCatalogObligation(null, "k", TARGET,
                    REPO, EndpointKind.CMIS_FOLDER, QN, LineageCatalogObligation.State.PENDING,
                    null, null, 0L, 5000L, 3, 1L, LineageCatalogObligation.Outcome.NONE,
                    "retrying", null);

            assertFalse(backingOff.claimableAt(4999L));
            assertTrue(backingOff.claimableAt(5000L));
        }

        /**
         * The backoff says when to try again; an expired lease says nobody is trying. The
         * second wins, or a worker that died would hold the obligation for the backoff too.
         */
        @Test
        @DisplayName("an expired lease is reclaimable regardless of backoff")
        void expiredLeaseBeatsBackoff() {
            LineageCatalogObligation stalled = new LineageCatalogObligation(null, "k", TARGET,
                    REPO, EndpointKind.CMIS_FOLDER, QN, LineageCatalogObligation.State.CLAIMED,
                    "node-1", "tok", 100L, Long.MAX_VALUE, 3, 1L,
                    LineageCatalogObligation.Outcome.NONE, null, null);

            assertTrue(stalled.claimableAt(200L));
        }

        @Test
        @DisplayName("a terminal obligation is never claimable")
        void terminalIsNeverClaimable() {
            assertFalse(resolved().claimableAt(Long.MAX_VALUE));
        }

        /** Durable: the schedule is on the document, so a restart does not reset it. */
        @Test
        @DisplayName("the schedule survives a codec round trip")
        void backoffIsDurable() {
            LineageCatalogObligation backingOff = new LineageCatalogObligation(null, "k", TARGET,
                    REPO, EndpointKind.CMIS_FOLDER, QN, LineageCatalogObligation.State.PENDING,
                    null, null, 0L, 987_654L, 4, 1L, LineageCatalogObligation.Outcome.NONE,
                    "retrying", null);

            LineageCatalogObligation decoded = CouchLineageCatalogObligationStore.fromRaw(
                    CouchLineageCatalogObligationStore.toRaw(backingOff));

            assertEquals(987_654L, decoded.notBeforeMs());
            assertEquals(4, decoded.attempts());
            assertFalse(decoded.claimableAt(987_653L));
        }
    }

    @Nested
    @DisplayName("subject comparison")
    class SubjectComparison {

        /** What create-if-absent uses to tell "already done" from "something else is here". */
        @Test
        @DisplayName("the same subject in a different state is still the same subject")
        void stateDoesNotChangeTheSubject() {
            assertTrue(pending().sameSubjectAs(resolved()));
        }

        @Test
        @DisplayName("a different subject under the same key is not adopted")
        void differentSubjectIsNotTheSame() {
            LineageCatalogObligation other = new LineageCatalogObligation(null,
                    pending().taskKey(), TARGET, REPO, EndpointKind.CMIS_DOCUMENT, QN,
                    LineageCatalogObligation.State.PENDING, null, null, 0L, 0L, 0, 1L,
                    LineageCatalogObligation.Outcome.NONE, null, null);

            assertFalse(pending().sameSubjectAs(other));
            assertFalse(pending().sameSubjectAs(null));
        }
    }

    /**
     * An obligation is logged and put in dead letters, so its description must not carry the
     * catalog name — an external asset's name contains its stable key.
     */
    @Test
    @DisplayName("the description names no qualified name and no token")
    public void descriptionLeaksNothing() {
        LineageCatalogObligation claimed = new LineageCatalogObligation(null, pending().taskKey(),
                TARGET, REPO, EndpointKind.CMIS_FOLDER, QN,
                LineageCatalogObligation.State.CLAIMED, "node-1", "secret-token-value", 1L, 0L, 0, 1L,
                LineageCatalogObligation.Outcome.NONE, null, null);

        String description = claimed.toString();
        assertFalse(description.contains(QN));
        assertFalse(description.contains("secret-token-value"));
        assertTrue(description.contains("<redacted:"));
        assertTrue(description.contains("CLAIMED"));
    }

    @Test
    @DisplayName("the codec round-trips every field it is asked to keep")
    public void codecRoundTrip() {
        LineageCatalogObligation original = resolved();
        Map<String, Object> raw = CouchLineageCatalogObligationStore.toRaw(original);
        LineageCatalogObligation decoded = CouchLineageCatalogObligationStore.fromRaw(raw);

        assertEquals(original.taskKey(), decoded.taskKey());
        assertEquals(original.endpointKind(), decoded.endpointKind());
        assertEquals(original.state(), decoded.state());
        assertEquals(original.outcome(), decoded.outcome());
        assertEquals(original.reason(), decoded.reason());
        assertEquals(original.evidence(), decoded.evidence());
        assertEquals("lineage_catalog_obligation", raw.get("type"));
    }

    /** A shape that cannot mean anything is refused rather than partly understood. */
    @Test
    @DisplayName("a document that is not an obligation is refused")
    public void decodingRefusesAForeignDocument() {
        Map<String, Object> raw = CouchLineageCatalogObligationStore.toRaw(pending());
        raw.put("type", "lineage_event_v2");

        assertThrows(LineageCatalogObligationStore.ObligationStorageException.class,
                () -> CouchLineageCatalogObligationStore.fromRaw(raw));
    }

    @Test
    @DisplayName("a document missing a required field is refused, not defaulted")
    public void decodingRefusesAnIncompleteDocument() {
        Map<String, Object> raw = CouchLineageCatalogObligationStore.toRaw(pending());
        raw.remove("catalogQualifiedName");

        assertThrows(LineageCatalogObligationStore.ObligationStorageException.class,
                () -> CouchLineageCatalogObligationStore.fromRaw(raw));
    }

    private static LineageCatalogObligation resolved() {
        return new LineageCatalogObligation(null, pending().taskKey(), TARGET, REPO,
                EndpointKind.CMIS_FOLDER, QN, LineageCatalogObligation.State.RESOLVED,
                null, null, 0L, 0L, 2, 1000L, LineageCatalogObligation.Outcome.SOURCE_EXISTS,
                "the catalog holds it", "guid-digest");
    }
}
