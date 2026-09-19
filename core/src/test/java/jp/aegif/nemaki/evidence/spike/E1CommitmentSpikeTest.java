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
package jp.aegif.nemaki.evidence.spike;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The E1 architecture spike: outbox marker (A) vs durable commitment intent (B).
 *
 * <p>The plan (`docs/design/v3.4.0-evidence-and-residuals-plan.md` §8) says to choose between
 * them "by fault injection", against five criteria. This is that measurement. The ADR that
 * records the outcome is `docs/design/adr/0001-e1-content-state-commitment.md`.
 *
 * <h2>What this models, and what it does not</h2>
 *
 * <p>Modelled: two stores that cannot be written in one transaction; a document whose fields are
 * REPLACED by each write (so a field left on a document is lost when the next write does not
 * carry it); an append-only ledger; a crash that can happen between any two writes; recovery
 * that may only read what is in the stores.
 *
 * <p>NOT modelled, and therefore not claimed: CouchDB's revision conflicts and the retry
 * behaviour around them, attachment PUT semantics (a body written separately from the document),
 * multi-replica ordering, view staleness, and anything about performance. A conclusion here is a
 * conclusion about the SHAPE of the two designs, not a measurement of the product.
 *
 * <p>The write order is the product's, read out of {@code ContentServiceImpl} rather than
 * invented: the new-attachment family (createDocument, createDocumentWithNewStream, checkIn,
 * the two copies) writes the attachment FIRST and the document SECOND, and the in-place family
 * (setContentStream on a non-versionable document, appendContentStream) rewrites the SAME
 * attachment row and then updates the document.
 */
class E1CommitmentSpikeTest {

    // ---------------------------------------------------------------- the model

    /** Two stores with no transaction between them. */
    static final class Store {
        final Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        final Map<String, String> attachments = new LinkedHashMap<>();
        final List<Map<String, Object>> ledger = new ArrayList<>();
        final Map<String, Map<String, Object>> intents = new LinkedHashMap<>();

        /** A document write REPLACES the document. A field not carried here is gone. */
        void putDoc(String id, Map<String, Object> fields) {
            Map<String, Object> written = new LinkedHashMap<>(fields);
            int rev = (int) ((Map<String, Object>) docs.getOrDefault(id, Map.of("_rev", 0)))
                    .getOrDefault("_rev", 0);
            written.put("_rev", rev + 1);
            docs.put(id, written);
        }

        Map<String, Object> doc(String id) {
            return docs.get(id);
        }

        void putAttachment(String id, String bytes) {
            attachments.put(id, bytes);
        }

        /** When set, the ledger store does not answer — the C4 case, not a crash. */
        boolean ledgerRefuses = false;

        void appendLedger(Map<String, Object> entry) {
            if (ledgerRefuses) {
                throw new LedgerUnavailable();
            }
            ledger.add(new LinkedHashMap<>(entry));
        }

        void putIntent(String id, Map<String, Object> row) {
            intents.put(id, new LinkedHashMap<>(row));
        }

        List<Map<String, Object>> statementsFor(String docId) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> entry : ledger) {
                if (docId.equals(entry.get("docId"))) {
                    out.add(entry);
                }
            }
            return out;
        }
    }

    /** Thrown at an injected crash point. Not an error: the power went out. */
    static final class Crash extends RuntimeException {
        Crash(String at) {
            super("crashed at " + at);
        }
    }

    /** The ledger store did not answer. The content write has already landed. */
    static final class LedgerUnavailable extends RuntimeException {
        LedgerUnavailable() {
            super("the ledger did not answer");
        }
    }

    /** Crashes at one named step, and records every step reached. */
    static final class Crasher {
        private final String crashAt;
        final List<String> reached = new ArrayList<>();

        Crasher(String crashAt) {
            this.crashAt = crashAt;
        }

        void step(String name) {
            reached.add(name);
            if (name.equals(crashAt)) {
                throw new Crash(name);
            }
        }
    }

    static String digest(String bytes) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(bytes.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- the two designs

    interface Strategy {
        String name();

        /** The new-attachment family: attachment, then document, then the statement. */
        void writeNewAttachment(Store store, String docId, String attachmentId, String bytes,
                String intentId, Crasher crash);

        /** The in-place family: the SAME attachment row is rewritten. */
        void writeInPlace(Store store, String docId, String bytes, String intentId,
                Crasher crash);

        /** Reads only the stores. Runs after a restart. */
        void recover(Store store);

        /** What recovery can still see as unfinished. Must survive a restart. */
        List<String> openGaps(Store store);
    }

    /**
     * Design A: a marker on the content document's own revision.
     *
     * <p>Its appeal is that the marker and the bytes-reference land in ONE write, so there is no
     * window in which the content exists without a marker.
     */
    static final class OutboxMarker implements Strategy {
        public String name() {
            return "A (outbox marker on the document)";
        }

        public void writeNewAttachment(Store store, String docId, String attachmentId,
                String bytes, String intentId, Crasher crash) {
            store.putAttachment(attachmentId, bytes);
            crash.step("attachment-written");
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("attachmentId", attachmentId);
            fields.put("pendingStatement", Map.of("intentId", intentId,
                    "attachmentId", attachmentId, "digest", digest(bytes)));
            store.putDoc(docId, fields);
            crash.step("doc-written");
            sweep(store, docId);
        }

        public void writeInPlace(Store store, String docId, String bytes, String intentId,
                Crasher crash) {
            Map<String, Object> current = store.doc(docId);
            String attachmentId = (String) current.get("attachmentId");
            store.putAttachment(attachmentId, bytes);
            crash.step("attachment-written");
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("attachmentId", attachmentId);
            fields.put("pendingStatement", Map.of("intentId", intentId,
                    "attachmentId", attachmentId, "digest", digest(bytes)));
            store.putDoc(docId, fields);
            crash.step("doc-written");
            sweep(store, docId);
        }

        @SuppressWarnings("unchecked")
        private void sweep(Store store, String docId) {
            Map<String, Object> current = store.doc(docId);
            Map<String, Object> pending = (Map<String, Object>) current.get("pendingStatement");
            if (pending == null) {
                return;
            }
            if (alreadyRecorded(store, docId, (String) pending.get("intentId"))) {
                clearMarker(store, docId, current);
                return;
            }
            Map<String, Object> statement = new LinkedHashMap<>();
            statement.put("docId", docId);
            statement.put("intentId", pending.get("intentId"));
            statement.put("attachmentId", pending.get("attachmentId"));
            statement.put("digest", pending.get("digest"));
            try {
                store.appendLedger(statement);
            } catch (LedgerUnavailable unavailable) {
                // Criterion 4. The content write has already landed; turning this into a
                // business failure would fail an operation that succeeded. The marker stays,
                // so the gap is still there for a later sweep.
                return;
            }
            clearMarker(store, docId, store.doc(docId));
        }

        private void clearMarker(Store store, String docId, Map<String, Object> current) {
            Map<String, Object> fields = new LinkedHashMap<>(current);
            fields.remove("pendingStatement");
            store.putDoc(docId, fields);
        }

        private boolean alreadyRecorded(Store store, String docId, String intentId) {
            return store.statementsFor(docId).stream()
                    .anyMatch(s -> intentId.equals(s.get("intentId")));
        }

        public void recover(Store store) {
            for (String docId : new ArrayList<>(store.docs.keySet())) {
                sweep(store, docId);
            }
        }

        @SuppressWarnings("unchecked")
        public List<String> openGaps(Store store) {
            List<String> gaps = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> entry : store.docs.entrySet()) {
                Map<String, Object> pending =
                        (Map<String, Object>) entry.getValue().get("pendingStatement");
                if (pending != null) {
                    gaps.add(entry.getKey() + ":" + pending.get("intentId"));
                }
            }
            return gaps;
        }
    }

    /**
     * Design B: a durable intent row written BEFORE the content, resolved after.
     *
     * <p>Its appeal is that the intent exists even if the content write never happened, so
     * "we do not know whether we wrote" is a state the stores can hold rather than a state that
     * only existed in a JVM that has since died.
     */
    static final class CommitmentIntent implements Strategy {
        public String name() {
            return "B (durable commitment intent)";
        }

        public void writeNewAttachment(Store store, String docId, String attachmentId,
                String bytes, String intentId, Crasher crash) {
            Map<String, Object> intent = new LinkedHashMap<>();
            intent.put("intentId", intentId);
            intent.put("docId", docId);
            intent.put("attachmentId", attachmentId);
            intent.put("digest", digest(bytes));
            intent.put("state", "OPEN");
            store.putIntent(intentId, intent);
            crash.step("intent-written");
            store.putAttachment(attachmentId, bytes);
            crash.step("attachment-written");
            store.putDoc(docId, Map.of("attachmentId", attachmentId));
            crash.step("doc-written");
            resolve(store, intentId);
        }

        public void writeInPlace(Store store, String docId, String bytes, String intentId,
                Crasher crash) {
            String attachmentId = (String) store.doc(docId).get("attachmentId");
            Map<String, Object> intent = new LinkedHashMap<>();
            intent.put("intentId", intentId);
            intent.put("docId", docId);
            intent.put("attachmentId", attachmentId);
            intent.put("digest", digest(bytes));
            intent.put("state", "OPEN");
            store.putIntent(intentId, intent);
            crash.step("intent-written");
            store.putAttachment(attachmentId, bytes);
            crash.step("attachment-written");
            store.putDoc(docId, Map.of("attachmentId", attachmentId));
            crash.step("doc-written");
            resolve(store, intentId);
        }

        /**
         * Closes an intent ONLY against bytes that are still the ones it named.
         *
         * <p>The comparison is on the digest of what is actually stored, not on the intent's own
         * word for it — that is the whole difference between "we recorded what we wrote" and
         * "we recorded what we meant to write".
         */
        private void resolve(Store store, String intentId) {
            Map<String, Object> intent = store.intents.get(intentId);
            if (intent == null || !"OPEN".equals(intent.get("state"))) {
                return;
            }
            String docId = (String) intent.get("docId");
            Map<String, Object> current = store.doc(docId);
            if (current == null) {
                return;
            }
            String attachmentId = (String) intent.get("attachmentId");
            if (!attachmentId.equals(current.get("attachmentId"))) {
                // The document has moved on to different bytes. This intent can never be closed
                // as "recorded"; saying anything else would put a statement in the ledger about
                // a content state the repository no longer has.
                intent.put("state", "SUPERSEDED");
                return;
            }
            String stored = store.attachments.get(attachmentId);
            if (stored == null || !digest(stored).equals(intent.get("digest"))) {
                intent.put("state", "SUPERSEDED");
                return;
            }
            boolean already = store.statementsFor(docId).stream()
                    .anyMatch(s -> intentId.equals(s.get("intentId")));
            if (!already) {
                Map<String, Object> statement = new LinkedHashMap<>();
                statement.put("docId", docId);
                statement.put("intentId", intentId);
                statement.put("attachmentId", attachmentId);
                statement.put("digest", intent.get("digest"));
                try {
                    store.appendLedger(statement);
                } catch (LedgerUnavailable unavailable) {
                    // Criterion 4, same as design A: the intent stays OPEN and the write stands.
                    return;
                }
            }
            intent.put("state", "CLOSED");
        }

        public void recover(Store store) {
            for (String intentId : new ArrayList<>(store.intents.keySet())) {
                resolve(store, intentId);
            }
        }

        public List<String> openGaps(Store store) {
            List<String> gaps = new ArrayList<>();
            for (Map<String, Object> intent : store.intents.values()) {
                if (!"CLOSED".equals(intent.get("state"))) {
                    gaps.add(intent.get("docId") + ":" + intent.get("intentId")
                            + ":" + intent.get("state"));
                }
            }
            return gaps;
        }
    }

    private static final List<Strategy> BOTH = List.of(new OutboxMarker(), new CommitmentIntent());

    // ---------------------------------------------------------------- the five criteria

    @Test
    @DisplayName("C1: no statement names bytes the store does not hold under that reference")
    void statementsNeverPointAtOtherBytes() {
        for (Strategy strategy : BOTH) {
            for (String crashAt : List.of("intent-written", "attachment-written", "doc-written",
                    "none")) {
                Store store = new Store();
                Crasher crash = new Crasher(crashAt);
                try {
                    strategy.writeNewAttachment(store, "doc-1", "att-1", "the minutes",
                            "i-1", crash);
                } catch (Crash expected) {
                    // The power went out. Recovery is what has to be right.
                }
                strategy.recover(store);

                for (Map<String, Object> statement : store.statementsFor("doc-1")) {
                    String stored = store.attachments.get(statement.get("attachmentId"));
                    assertEquals(digest(stored == null ? "" : stored), statement.get("digest"),
                            strategy.name() + " crash@" + crashAt
                                    + ": a statement names a digest the stored bytes do not have");
                }
            }
        }
    }

    @Test
    @DisplayName("C2: a landed write with no statement leaves a gap the stores still show")
    void theGapSurvivesTheJvm() {
        for (Strategy strategy : BOTH) {
            Store store = new Store();
            Crasher crash = new Crasher("doc-written");
            try {
                strategy.writeNewAttachment(store, "doc-1", "att-1", "the minutes", "i-1", crash);
            } catch (Crash expected) {
                // Content landed; the statement did not.
            }
            // No recover() call: this is the moment after the crash, before anything ran.
            assertEquals("att-1", store.doc("doc-1").get("attachmentId"),
                    strategy.name() + ": the fixture did not actually land the content");
            assertTrue(store.statementsFor("doc-1").isEmpty(),
                    strategy.name() + ": the fixture recorded a statement it should not have");
            assertFalse(strategy.openGaps(store).isEmpty(),
                    strategy.name() + ": the content is there, no statement is, and NOTHING in "
                            + "the stores says so — the gap exists only in a JVM that has died");
        }
    }

    @Test
    @DisplayName("C3: a retry after recovery does not record the same state twice")
    void retryDoesNotDoubleRecord() {
        for (Strategy strategy : BOTH) {
            Store store = new Store();
            try {
                strategy.writeNewAttachment(store, "doc-1", "att-1", "the minutes", "i-1",
                        new Crasher("doc-written"));
            } catch (Crash expected) {
                // fall through
            }
            strategy.recover(store);
            // The client retries the same logical operation with the same intent id.
            strategy.writeNewAttachment(store, "doc-1", "att-1", "the minutes", "i-1",
                    new Crasher("none"));
            strategy.recover(store);

            assertEquals(1, store.statementsFor("doc-1").size(),
                    strategy.name() + ": the same content state was recorded twice: "
                            + store.statementsFor("doc-1"));
        }
    }

    @Test
    @DisplayName("C4: a ledger that will not answer does not undo the content write")
    void aLedgerFailureIsNotABusinessFailure() {
        for (Strategy strategy : BOTH) {
            Store store = new Store();
            store.ledgerRefuses = true;

            // No crash: the write runs to the end and the LEDGER is what fails.
            strategy.writeNewAttachment(store, "doc-1", "att-1", "the minutes", "i-1",
                    new Crasher("none"));

            assertEquals("the minutes", store.attachments.get("att-1"),
                    strategy.name() + ": the content write was undone by a recording failure");
            assertEquals("att-1", store.doc("doc-1").get("attachmentId"),
                    strategy.name() + ": the document no longer points at the content");
            assertTrue(store.statementsFor("doc-1").isEmpty(), strategy.name());
            assertFalse(strategy.openGaps(store).isEmpty(),
                    strategy.name() + ": the recording failure left no trace, so the gap is "
                            + "silent — which is the one outcome §8 rules out");

            // And the gap is closable once the ledger answers again.
            store.ledgerRefuses = false;
            strategy.recover(store);
            assertEquals(1, store.statementsFor("doc-1").size(), strategy.name());
            assertTrue(strategy.openGaps(store).isEmpty(), strategy.name());
        }
    }

    @Test
    @DisplayName("C5: an old intent is not closed over the bytes that replaced it")
    void anOldIntentDoesNotCloseOverNewBytes() {
        for (Strategy strategy : BOTH) {
            Store store = new Store();
            try {
                strategy.writeNewAttachment(store, "doc-1", "att-1", "the minutes", "i-1",
                        new Crasher("doc-written"));
            } catch (Crash expected) {
                // fall through
            }
            // The document moves on before anyone recovers: a second version's bytes.
            strategy.writeNewAttachment(store, "doc-1", "att-2", "the minutes, revised", "i-2",
                    new Crasher("none"));
            strategy.recover(store);

            for (Map<String, Object> statement : store.statementsFor("doc-1")) {
                if ("i-1".equals(statement.get("intentId"))) {
                    assertEquals(digest("the minutes"), statement.get("digest"),
                            strategy.name() + ": the first write's statement was closed over the "
                                    + "SECOND write's bytes");
                }
            }
        }
    }

    // ------------------------------------------------- the difference the criteria expose

    @Test
    @DisplayName("the in-place path: A loses the gap, B keeps it")
    void inPlaceRewriteIsWhereTheDesignsDiffer() {
        // setContentStream on a non-versionable document and appendContentStream both rewrite
        // the SAME attachment row and then write the document (ContentServiceImpl:1588-1596,
        // :4528). A marker lives on the document, and a document write replaces the document —
        // so the second in-place change overwrites an unswept marker from the first. The bytes
        // that were never recorded are gone AND so is the only record that they were not.
        Store storeA = new Store();
        OutboxMarker a = new OutboxMarker();
        a.writeNewAttachment(storeA, "doc-1", "att-1", "v1", "i-1", new Crasher("none"));
        try {
            a.writeInPlace(storeA, "doc-1", "v2", "i-2", new Crasher("doc-written"));
        } catch (Crash expected) {
            // v2 landed, no statement for it.
        }
        a.writeInPlace(storeA, "doc-1", "v3", "i-3", new Crasher("none"));
        a.recover(storeA);

        assertTrue(a.openGaps(storeA).isEmpty(),
                "the model no longer matches design A — re-derive the conclusion below");
        assertTrue(storeA.statementsFor("doc-1").stream()
                        .noneMatch(s -> "i-2".equals(s.get("intentId"))),
                "the model no longer matches design A");
        // So: v2 existed, was never recorded, and after the third write NOTHING says so. That is
        // the silent gap §8 forbids, and no amount of recovery finds it.

        Store storeB = new Store();
        CommitmentIntent b = new CommitmentIntent();
        b.writeNewAttachment(storeB, "doc-1", "att-1", "v1", "i-1", new Crasher("none"));
        try {
            b.writeInPlace(storeB, "doc-1", "v2", "i-2", new Crasher("doc-written"));
        } catch (Crash expected) {
            // v2 landed, no statement for it.
        }
        b.writeInPlace(storeB, "doc-1", "v3", "i-3", new Crasher("none"));
        b.recover(storeB);

        assertFalse(b.openGaps(storeB).isEmpty(),
                "design B lost the unrecorded in-place write too — the ADR's reason for choosing "
                        + "it is gone");
        assertTrue(b.openGaps(storeB).stream().anyMatch(gap -> gap.contains("i-2")),
                "the surviving gap is not the one that was actually missed: " + b.openGaps(storeB));
        // B cannot say WHAT v2 was — those bytes are overwritten in both designs. It can say
        // that a content state went unrecorded, which is the difference between a known gap and
        // no gap at all.
    }

    @Test
    @DisplayName("B alone cannot say whether a crashed write ever landed — and says so")
    void anIntentWithNoContentIsNotAClaimThatContentExists() {
        // The cost of writing the intent first: an intent with no content behind it means either
        // "the write never happened" or "the write happened and was rolled back". B must not
        // resolve that into a statement, and must not resolve it into silence either.
        Store store = new Store();
        CommitmentIntent b = new CommitmentIntent();
        try {
            b.writeNewAttachment(store, "doc-1", "att-1", "the minutes", "i-1",
                    new Crasher("intent-written"));
        } catch (Crash expected) {
            // Nothing was written but the intent.
        }
        b.recover(store);

        assertTrue(store.statementsFor("doc-1").isEmpty(),
                "an intent with no content behind it produced a statement");
        assertFalse(b.openGaps(store).isEmpty(),
                "an intent with no content behind it was quietly dropped");
    }
}
