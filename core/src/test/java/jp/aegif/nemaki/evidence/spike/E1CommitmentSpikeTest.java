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
 * records the outcome is `docs/design/e1-content-state-commitment-adr.md`.
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

        /**
         * An unrelated writer of the SAME content document: a property update (a rename, an
         * ACL change, a version-series flag) built from a model that knows nothing about
         * content-state bookkeeping. This codebase has many of them.
         */
        void writeUnrelated(Store store, String docId);

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
     *
     * <h2>Written at its strongest, on purpose</h2>
     *
     * <p>The first version of this spike modelled A with a SINGLE marker field and a sweeper
     * that copied what the marker said. Both reviews pointed out that neither is forced by the
     * design: the marker can be a LIST that each write carries forward (the in-place path
     * already reads the document), and the sweeper can re-hash the stored bytes exactly as B's
     * resolver does. A comparison against a weakened opponent decides nothing, so A gets both
     * here. It then meets all five of the plan's criteria — and the decision moves to C6.
     */
    static final class OutboxMarker implements Strategy {

        public String name() {
            return "A (outbox marker on the document)";
        }

        public void writeNewAttachment(Store store, String docId, String attachmentId,
                String bytes, String intentId, Crasher crash) {
            store.putAttachment(attachmentId, bytes);
            crash.step("attachment-written");
            writeDocWithPending(store, docId, attachmentId, intentId, digest(bytes));
            crash.step("doc-written");
            sweep(store, docId);
        }

        public void writeInPlace(Store store, String docId, String bytes, String intentId,
                Crasher crash) {
            String attachmentId = (String) store.doc(docId).get("attachmentId");
            store.putAttachment(attachmentId, bytes);
            crash.step("attachment-written");
            writeDocWithPending(store, docId, attachmentId, intentId, digest(bytes));
            crash.step("doc-written");
            sweep(store, docId);
        }

        /** An unrelated writer: a property update that knows nothing about markers. */
        public void writeUnrelated(Store store, String docId) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("attachmentId", store.doc(docId).get("attachmentId"));
            fields.put("title", "renamed");
            store.putDoc(docId, fields);
        }

        /** Carries forward whatever is still pending — the list variant, not a single slot. */
        @SuppressWarnings("unchecked")
        private void writeDocWithPending(Store store, String docId, String attachmentId,
                String intentId, String digest) {
            Map<String, Object> current = store.doc(docId);
            List<Map<String, Object>> pending = new ArrayList<>();
            if (current != null && current.get("pendingStatements") != null) {
                pending.addAll((List<Map<String, Object>>) current.get("pendingStatements"));
            }
            pending.add(Map.of("intentId", intentId, "attachmentId", attachmentId,
                    "digest", digest));
            Map<String, Object> fields = new LinkedHashMap<>();
            if (current != null) {
                fields.putAll(current);
                fields.remove("_rev");
            }
            fields.put("attachmentId", attachmentId);
            fields.put("pendingStatements", pending);
            store.putDoc(docId, fields);
        }

        @SuppressWarnings("unchecked")
        private void sweep(Store store, String docId) {
            Map<String, Object> current = store.doc(docId);
            if (current == null || current.get("pendingStatements") == null) {
                return;
            }
            List<Map<String, Object>> pending =
                    new ArrayList<>((List<Map<String, Object>>) current.get("pendingStatements"));
            List<Map<String, Object>> remaining = new ArrayList<>();
            for (Map<String, Object> entry : pending) {
                String intentId = (String) entry.get("intentId");
                String attachmentId = (String) entry.get("attachmentId");
                if (alreadyRecorded(store, docId, intentId)) {
                    continue;
                }
                // Verified against the store, exactly as B's resolver does. Writing what the
                // marker SAYS would record a state nobody can still see.
                String stored = store.attachments.get(attachmentId);
                if (stored == null || !digest(stored).equals(entry.get("digest"))) {
                    remaining.add(supersede(entry));
                    continue;
                }
                Map<String, Object> statement = new LinkedHashMap<>();
                statement.put("docId", docId);
                statement.put("intentId", intentId);
                statement.put("attachmentId", attachmentId);
                statement.put("digest", entry.get("digest"));
                try {
                    store.appendLedger(statement);
                } catch (LedgerUnavailable unavailable) {
                    // Criterion 4. The content write has already landed; turning this into a
                    // business failure would fail an operation that succeeded. Everything not
                    // yet swept stays on the document.
                    remaining.add(entry);
                    remaining.addAll(pending.subList(pending.indexOf(entry) + 1, pending.size()));
                    rewritePending(store, docId, remaining);
                    return;
                }
            }
            rewritePending(store, docId, remaining);
        }

        private Map<String, Object> supersede(Map<String, Object> entry) {
            Map<String, Object> superseded = new LinkedHashMap<>(entry);
            superseded.put("state", "SUPERSEDED");
            return superseded;
        }

        private void rewritePending(Store store, String docId,
                List<Map<String, Object>> remaining) {
            Map<String, Object> fields = new LinkedHashMap<>(store.doc(docId));
            fields.remove("_rev");
            if (remaining.isEmpty()) {
                fields.remove("pendingStatements");
            } else {
                fields.put("pendingStatements", remaining);
            }
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
                List<Map<String, Object>> pending =
                        (List<Map<String, Object>>) entry.getValue().get("pendingStatements");
                if (pending != null) {
                    for (Map<String, Object> one : pending) {
                        gaps.add(entry.getKey() + ":" + one.get("intentId")
                                + (one.get("state") == null ? "" : ":" + one.get("state")));
                    }
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

        /** The same unrelated property update. It cannot touch what is in the other store. */
        public void writeUnrelated(Store store, String docId) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("attachmentId", store.doc(docId).get("attachmentId"));
            fields.put("title", "renamed");
            store.putDoc(docId, fields);
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
    @DisplayName("the in-place path: strengthened, BOTH designs keep the gap")
    void inPlaceRewriteNoLongerSeparatesThem() {
        // This test used to be the decisive one, because design A was modelled with a single
        // marker slot that the next in-place write overwrote. With the list variant — which the
        // in-place path can carry forward, since it already reads the document — A keeps the
        // gap here too. Recorded as a NEGATIVE result: the first version of this spike decided
        // the ADR on a difference that was an artefact of how A was written.
        for (Strategy strategy : BOTH) {
            Store store = new Store();
            strategy.writeNewAttachment(store, "doc-1", "att-1", "v1", "i-1", new Crasher("none"));
            try {
                strategy.writeInPlace(store, "doc-1", "v2", "i-2", new Crasher("doc-written"));
            } catch (Crash expected) {
                // v2 landed, no statement for it.
            }
            strategy.writeInPlace(store, "doc-1", "v3", "i-3", new Crasher("none"));
            strategy.recover(store);

            assertTrue(strategy.openGaps(store).stream().anyMatch(gap -> gap.contains("i-2")),
                    strategy.name() + ": the content state that was never recorded left no "
                            + "trace: " + strategy.openGaps(store));
            assertTrue(store.statementsFor("doc-1").stream()
                            .noneMatch(s -> "i-2".equals(s.get("intentId"))),
                    strategy.name() + ": v2's bytes are gone, so no statement about them can be "
                            + "written — one was: " + store.statementsFor("doc-1"));
        }
    }

    @Test
    @DisplayName("C6: an unrelated writer of the same document must not drop the record")
    void anUnrelatedWriterDoesNotDropTheGap() {
        // The property this codebase actually forces, and where the two designs part. The
        // content document is written from many places — renames, ACL changes, version-series
        // flags, the twelve content-write paths themselves — and every service-layer update
        // rebuilds the stored model FROM THE DOMAIN MODEL
        // (ContentDaoServiceImpl.update: `new CouchDocument(document)`), which carries only
        // its declared fields. The stored-JSON carrier (CouchNodeBase's @JsonAnySetter into
        // additionalProperties) does not survive that hop.
        //
        // This is not hypothetical here. CouchContent's own comment records the product losing
        // `contentIncarnation` exactly this way: "the model round-trip used to LOSE
        // contentIncarnation … each ordinary rename silently started a new lifetime". A
        // rename did that. Nothing can drop a row in the other store.
        Store storeA = new Store();
        OutboxMarker a = new OutboxMarker();
        try {
            a.writeNewAttachment(storeA, "doc-1", "att-1", "the minutes", "i-1",
                    new Crasher("doc-written"));
        } catch (Crash expected) {
            // Content landed; the statement did not.
        }
        assertFalse(a.openGaps(storeA).isEmpty(), "the fixture did not leave a gap");
        a.writeUnrelated(storeA, "doc-1");
        a.recover(storeA);

        assertTrue(a.openGaps(storeA).isEmpty(), "design A no longer loses the marker to an "
                + "unrelated write — re-derive the ADR's decision");
        assertTrue(storeA.statementsFor("doc-1").isEmpty(), "design A recorded it after all");
        // So: the bytes are there, no statement is, and nothing says so. A rename did that.

        Store storeB = new Store();
        CommitmentIntent b = new CommitmentIntent();
        try {
            b.writeNewAttachment(storeB, "doc-1", "att-1", "the minutes", "i-1",
                    new Crasher("doc-written"));
        } catch (Crash expected) {
            // Same state.
        }
        b.writeUnrelated(storeB, "doc-1");
        b.recover(storeB);

        assertEquals(1, storeB.statementsFor("doc-1").size(),
                "design B lost the intent to an unrelated write, so the ADR's reason is gone");
        assertTrue(b.openGaps(storeB).isEmpty(), b.openGaps(storeB).toString());
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
