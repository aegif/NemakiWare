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

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntent;
import jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore.CaptureCompletion;
import jp.aegif.nemaki.rest.ingest.capture.CaptureState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The CouchDB side of the capture boundary: strict IO, CAS, and views that cannot cross.
 *
 * <p>Everything here turns on one property the ordinary write path does not have — that a
 * failure is reported rather than swallowed. {@code CloudantClientWrapper.create} and
 * {@code .update} return {@code null} for a conflict, a 500, an authentication failure and an
 * oversized document alike; a boundary built on that claims evidence it never had.
 */
class CouchCaptureIntentStoreTest {

    /** A support that behaves as the test tells it to, and records what it was asked. */
    private static final class FakeSupport implements LineageStoreSupport {
        final Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        boolean createSucceeds = true;
        RuntimeException createThrows;
        RuntimeException readThrows;
        boolean casSucceeds = true;
        /** Applied once, after the first failed CAS, to model a concurrent writer. */
        Map<String, Object> afterLostCas;
        boolean clientAvailable = true;
        int casAttempts;

        @Override public void ensureDatabase() { }

        @Override public CloudantClientWrapper client() {
            return clientAvailable ? mock(CloudantClientWrapper.class) : null;
        }

        @Override public String designDoc() { return "lineage"; }

        @Override public Map<String, Object> readRawStrict(String documentId) {
            if (readThrows != null) {
                throw readThrows;
            }
            Map<String, Object> d = docs.get(documentId);
            return d == null ? null : new LinkedHashMap<>(d);
        }

        @Override public boolean updateStrictCas(Map<String, Object> raw) {
            casAttempts++;
            if (!casSucceeds) {
                if (afterLostCas != null) {
                    docs.put((String) raw.get("_id"), new LinkedHashMap<>(afterLostCas));
                }
                return false;
            }
            docs.put((String) raw.get("_id"), new LinkedHashMap<>(raw));
            return true;
        }

        @Override public boolean createIfAbsentStrict(String documentId,
                Map<String, Object> properties) {
            if (createThrows != null) {
                throw createThrows;
            }
            if (!createSucceeds || docs.containsKey(documentId)) {
                return false;
            }
            Map<String, Object> stored = new LinkedHashMap<>(properties);
            stored.put("_id", documentId);
            stored.put("_rev", "1-a");
            docs.put(documentId, stored);
            return true;
        }

        /** Rows the view will answer with, in order. */
        java.util.List<Map<String, Object>> viewRows = new java.util.ArrayList<>();
        final java.util.List<String> deleted = new java.util.ArrayList<>();
        Map<String, Object> lastViewParams;

        RuntimeException viewThrows;
        String lastViewName;

        @Override public java.util.List<Map<String, Object>> queryRawView(String designDocName,
                String viewName, Map<String, Object> params) {
            lastViewParams = params;
            lastViewName = viewName;
            if (viewThrows != null) {
                throw viewThrows;
            }
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (Map<String, Object> r : viewRows) {
                out.add(new LinkedHashMap<>(r));
            }
            return out;
        }

        @Override public boolean deleteRaw(Map<String, Object> raw) {
            deleted.add((String) raw.get("_id"));
            docs.remove(raw.get("_id"));
            return true;
        }

        @Override public Map<String, Object> readV2RawStrict(String recordId) { return null; }

        @Override public LineageJournalRowV2 decodeV2Strict(Map<String, Object> raw) {
            return null;
        }

        @Override public LineageMetrics metrics() { return null; }
    }

    private static CaptureIntent intent() {
        return new CaptureIntent(CaptureIntent.documentIdFor("i-1"), "i-1", 1_700_000_000_000L,
                "bedroom", "conn-1", "slack", "message", "msg-1", "req-1", "correspondence",
                "admin", "alice");
    }

    // ── Opening ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a confirmed create is the only thing reported as success")
    void openIntentConfirms() {
        FakeSupport support = new FakeSupport();

        assertTrue(new CouchCaptureIntentStore(support).openIntent(intent()));
        assertEquals(CaptureState.CAPTURE_INTENT.name(),
                support.docs.get("lineage_capture:i-1").get("captureState"));
    }

    @Test
    @DisplayName("a create that was not confirmed is reported as failure, not silence")
    void unconfirmedCreateIsFailure() {
        FakeSupport support = new FakeSupport();
        support.createSucceeds = false;

        assertFalse(new CouchCaptureIntentStore(support).openIntent(intent()));
    }

    @Test
    @DisplayName("an id that already exists is refused, not ridden on")
    void existingIdIsRefused() {
        // The id is unique per attempt, so a conflict means two attempts produced the same one.
        // Returning true would let a second ingest complete the first one's row.
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());

        assertFalse(store.openIntent(intent()));
    }

    @Test
    @DisplayName("a create that throws is a failure, not a crash out of the ingest")
    void throwingCreateIsFailure() {
        // The caller turns false into the fail-closed exception; letting an SDK exception escape
        // from here would bypass that and lose the explanation.
        FakeSupport support = new FakeSupport();
        support.createThrows = new RuntimeException("connection reset");

        assertFalse(new CouchCaptureIntentStore(support).openIntent(intent()));
    }

    // ── Completing ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("completing sets the state and stamps when it happened")
    void completeSetsStateAndStamp() {
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());

        assertEquals(CaptureCompletion.COMPLETED,
                store.completeIntent(intent(), Map.of("objectId", "obj-1")));

        Map<String, Object> row = support.docs.get("lineage_capture:i-1");
        assertEquals(CaptureState.CAPTURED.name(), row.get("captureState"));
        assertEquals("obj-1", row.get("objectId"));
        assertNotNull(row.get("capturedAtMs"), "retention keys on this; without it the row is "
                + "unreachable by any purge");
    }

    @Test
    @DisplayName("the row keeps its own type and never becomes a journal event")
    void rowKeepsItsType() {
        // A direct v1 write would bypass the write-schema barrier, whose javadoc says a missing
        // spool is not a licence to write v1 — in a schema-2 deployment that erases the v2
        // representation of ingest events.
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());

        store.completeIntent(intent(), Map.of());

        Map<String, Object> row = support.docs.get("lineage_capture:i-1");
        assertEquals("lineage_capture_intent", row.get("type"));
        assertFalse(row.containsKey("sequenceNumber"));
        assertFalse(row.containsKey("publishStatusByTarget"),
                "the v1 projection view selects on this field; carrying it would push the row "
                        + "into the delivery pipeline");
    }

    @Test
    @DisplayName("evidence cannot overwrite the row's identity or its state")
    void evidenceCannotForgeState() {
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());

        Map<String, Object> hostile = new HashMap<>();
        hostile.put("captureState", "UNRESOLVED");
        hostile.put("type", "lineage_event");
        hostile.put("intentId", "someone-else");
        hostile.put("_id", "lineage:evil");
        hostile.put("objectId", "obj-1");
        store.completeIntent(intent(), hostile);

        Map<String, Object> row = support.docs.get("lineage_capture:i-1");
        assertEquals(CaptureState.CAPTURED.name(), row.get("captureState"));
        assertEquals("lineage_capture_intent", row.get("type"));
        assertEquals("i-1", row.get("intentId"));
        assertEquals("obj-1", row.get("objectId"), "ordinary evidence still gets through");
    }

    @Test
    @DisplayName("an unresolved row can still be completed by a late ingest")
    void unresolvedCanBeCompleted() {
        // The sweeper's verdict is not final: an ingest that finished late really did finish.
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());
        support.docs.get("lineage_capture:i-1")
                .put("captureState", CaptureState.UNRESOLVED.name());

        assertEquals(CaptureCompletion.COMPLETED, store.completeIntent(intent(), Map.of()));
    }

    @Test
    @DisplayName("a missing row is reported as unreadable, with no cause decided here")
    void missingRowIsUnreadable() {
        FakeSupport support = new FakeSupport();

        assertEquals(CaptureCompletion.ROW_UNREADABLE,
                new CouchCaptureIntentStore(support).completeIntent(intent(), Map.of()));
    }

    @Test
    @DisplayName("a read that throws is unreadable rather than an escaping exception")
    void throwingReadIsUnreadable() {
        FakeSupport support = new FakeSupport();
        support.readThrows = new RuntimeException("socket timeout");

        assertEquals(CaptureCompletion.ROW_UNREADABLE,
                new CouchCaptureIntentStore(support).completeIntent(intent(), Map.of()));
    }

    @Test
    @DisplayName("completing an already-completed row is not an error")
    void completingTwiceIsFine() {
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());
        store.completeIntent(intent(), Map.of());
        int attemptsAfterFirst = support.casAttempts;

        assertEquals(CaptureCompletion.COMPLETED, store.completeIntent(intent(), Map.of()));
        assertEquals(attemptsAfterFirst, support.casAttempts,
                "a terminal row must not be written again");
    }

    // ── Losing the CAS ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("losing the CAS to a concurrent completion reports COMPLETED, not failure")
    void lostCasToACompletion() {
        // Two workers finishing one capture is ordinary. Reporting that as a failure would put a
        // warning in front of an operator for something that went right.
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());
        support.casSucceeds = false;
        Map<String, Object> byOther = new LinkedHashMap<>(support.docs.get("lineage_capture:i-1"));
        byOther.put("captureState", CaptureState.CAPTURED.name());
        support.afterLostCas = byOther;

        assertEquals(CaptureCompletion.COMPLETED, store.completeIntent(intent(), Map.of()));
    }

    @Test
    @DisplayName("losing the CAS and finding the row gone is unreadable, not completed")
    void lostCasThenGone() {
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());
        support.casSucceeds = false;
        support.docs.clear();

        assertEquals(CaptureCompletion.ROW_UNREADABLE, store.completeIntent(intent(), Map.of()));
    }

    @Test
    @DisplayName("losing the CAS to something else is reported as not completable")
    void lostCasToSomethingElse() {
        FakeSupport support = new FakeSupport();
        CouchCaptureIntentStore store = new CouchCaptureIntentStore(support);
        store.openIntent(intent());
        support.casSucceeds = false;

        assertEquals(CaptureCompletion.NOT_COMPLETABLE, store.completeIntent(intent(), Map.of()));
    }

    // ── Activity ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("activity does not depend on any repository's lineage mode")
    void activityIsModeIndependent() {
        // Tying this to whether any repository is journaled would stop the boundary the moment
        // the last one is switched off — freezing every intent still open at that instant.
        FakeSupport support = new FakeSupport();

        assertTrue(new CouchCaptureIntentStore(support).isActive());

        support.clientAvailable = false;
        assertFalse(new CouchCaptureIntentStore(support).isActive());
    }

    @Test
    @DisplayName("a view that cannot be read is not reported as an empty listing")
    void unreadableViewIsNotEmpty() {
        // The listing is the boundary's entire visible surface. An empty answer here reads as
        // "every ingest completed", which is the opposite of what a read failure means.
        FakeSupport support = new FakeSupport();
        support.viewThrows = new RuntimeException("view group rebuilding");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> new CouchCaptureIntentStore(support).listUnresolved(null, 10, 0));
    }

    @Test
    @DisplayName("a view that cannot be read stops the sweep rather than sweeping nothing")
    void unreadableViewStopsTheSweep() {
        FakeSupport support = new FakeSupport();
        support.viewThrows = new RuntimeException("view group rebuilding");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> new CouchCaptureIntentStore(support).sweepExpiredIntents(9_999L, 100));
    }

    @Test
    @DisplayName("a view that cannot be read stops retention rather than deleting nothing quietly")
    void unreadableViewStopsRetention() {
        // Quietly deleting nothing is the benign direction here, but reporting zero deletions as
        // a successful pass would hide an index that never comes back.
        FakeSupport support = new FakeSupport();
        support.viewThrows = new RuntimeException("view group rebuilding");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> new CouchCaptureIntentStore(support).purgeCapturedOlderThan(9_999L, 100));
    }

    // ── Per-repository mode ──────────────────────────────────────────────────────────────

    private static LineageConfig configWith(String repositoryId, String mode) throws Exception {
        LineageConfig config = new LineageConfig();
        java.lang.reflect.Field f = LineageConfig.class.getDeclaredField("mode");
        f.setAccessible(true);
        f.set(config, mode);
        return config;
    }

    @Test
    @DisplayName("the boundary applies to a journaled repository and not to a disabled one")
    void appliesToFollowsTheRepositoryMode() throws Exception {
        // Reading the global setting would get this wrong the moment two repositories differ.
        assertTrue(new CouchCaptureIntentStore(new FakeSupport(),
                configWith("bedroom", "journaled")).appliesTo("bedroom"));
        assertFalse(new CouchCaptureIntentStore(new FakeSupport(),
                configWith("bedroom", "disabled")).appliesTo("bedroom"));
    }

    @Test
    @DisplayName("direct mode is not the boundary either")
    void directModeDoesNotApply() throws Exception {
        assertFalse(new CouchCaptureIntentStore(new FakeSupport(),
                configWith("bedroom", "direct")).appliesTo("bedroom"));
    }

    @Test
    @DisplayName("with no configuration the boundary does not apply")
    void noConfigurationMeansNotApplicable() {
        // The conservative direction: leave every repository behaving exactly as it does today
        // rather than fail an ingest closed on a boundary nobody configured.
        assertFalse(new CouchCaptureIntentStore(new FakeSupport()).appliesTo("bedroom"));
    }

    // ── Views ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the views live in their own design document")
    void viewsAreInTheirOwnDesignDocument() {
        // Adding them to _design/lineage would rebuild that whole view group, during which its
        // views answer from an incomplete index rather than failing.
        assertEquals("lineage_capture", CouchCaptureIntentStore.DESIGN_DOC);
    }

    @Test
    @DisplayName("every view is guarded by the intent type, in both directions")
    void viewsCannotCross() {
        Map<String, String> views = CouchCaptureIntentStore.views();

        assertEquals(5, views.size());
        for (Map.Entry<String, String> v : views.entrySet()) {
            assertTrue(v.getValue().contains("doc.type === 'lineage_capture_intent'"),
                    v.getKey() + " must not be able to see a journal row: " + v.getValue());
            assertTrue(v.getValue().contains("captureState"),
                    v.getKey() + " must select on state: " + v.getValue());
        }
    }

    @Test
    @DisplayName("an open intent is invisible to the retention views")
    void openIntentIsNotReachableByRetention() {
        // An intent must be swept to UNRESOLVED before anything may delete it. A retention view
        // that could see CAPTURE_INTENT would delete the evidence that something crashed
        // mid-ingest — the one row that records it.
        Map<String, String> views = CouchCaptureIntentStore.views();

        String unresolvedRetention =
                views.get(CouchCaptureIntentStore.VIEW_UNRESOLVED_BY_UNRESOLVED_AT);
        assertTrue(unresolvedRetention.contains("'UNRESOLVED'"), unresolvedRetention);
        assertFalse(unresolvedRetention.contains("'CAPTURE_INTENT'"), unresolvedRetention);

        String capturedRetention = views.get(CouchCaptureIntentStore.VIEW_CAPTURED_BY_CAPTURED_AT);
        assertTrue(capturedRetention.contains("'CAPTURED'"), capturedRetention);
        assertFalse(capturedRetention.contains("'CAPTURE_INTENT'"), capturedRetention);
    }

    @Test
    @DisplayName("each retention view keys on its own state's timestamp")
    void retentionViewsKeyOnTheRightClock() {
        // Keying the unresolved view on intentOpenedAtMs would make a long-running ingest
        // eligible for deletion the instant it was swept.
        Map<String, String> views = CouchCaptureIntentStore.views();

        assertTrue(views.get(CouchCaptureIntentStore.VIEW_UNRESOLVED_BY_UNRESOLVED_AT)
                .contains("emit(doc.unresolvedAtMs"));
        assertTrue(views.get(CouchCaptureIntentStore.VIEW_CAPTURED_BY_CAPTURED_AT)
                .contains("emit(doc.capturedAtMs"));
    }

    // ── Sweeping ─────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> row(String id, CaptureState state, String repositoryId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("_id", id);
        r.put("_rev", "1-a");
        r.put("type", "lineage_capture_intent");
        r.put("captureState", state.name());
        r.put("repositoryId", repositoryId);
        r.put("intentOpenedAtMs", 1_700_000_000_000L);
        return r;
    }

    @Test
    @DisplayName("an expired intent becomes unresolved and is stamped with when")
    void sweepMovesExpiredIntents() {
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.CAPTURE_INTENT, "bedroom"));

        assertEquals(1, new CouchCaptureIntentStore(support).sweepExpiredIntents(9_999L, 100));

        Map<String, Object> swept = support.docs.get("lineage_capture:i-1");
        assertEquals(CaptureState.UNRESOLVED.name(), swept.get("captureState"));
        assertNotNull(swept.get("unresolvedAtMs"),
                "retention keys on this; without it the row can never be deleted");
    }

    @Test
    @DisplayName("the sweeper re-checks the state instead of trusting the view")
    void sweepRechecksState() {
        // A view group is allowed to answer from a stale index, and the ingest may have completed
        // between the view answering and this write. Trusting the view would move a CAPTURED row
        // back to UNRESOLVED — a transition the state machine does not have.
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.CAPTURED, "bedroom"));

        assertEquals(0, new CouchCaptureIntentStore(support).sweepExpiredIntents(9_999L, 100));
        assertTrue(support.docs.isEmpty());
    }

    @Test
    @DisplayName("the sweeper does not look for the document")
    void sweepDoesNotHunt() {
        // Whether an object that exists came from this attempt cannot be decided without a stamp
        // the ingest does not write yet, so searching would be guessing and recording the guess.
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.CAPTURE_INTENT, "bedroom"));

        new CouchCaptureIntentStore(support).sweepExpiredIntents(9_999L, 100);

        assertFalse(support.docs.get("lineage_capture:i-1").containsKey("objectId"));
    }

    @Test
    @DisplayName("a lost CAS during sweeping is not counted and not an error")
    void sweepLostCasIsOrdinary() {
        // Another replica swept it, or the ingest completed it. Both are correct outcomes.
        FakeSupport support = new FakeSupport();
        support.casSucceeds = false;
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.CAPTURE_INTENT, "bedroom"));

        assertEquals(0, new CouchCaptureIntentStore(support).sweepExpiredIntents(9_999L, 100));
    }

    @Test
    @DisplayName("sweeping is bounded, because every replica walks the same batch")
    void sweepIsBounded() {
        // Leader election is off by default and, when off, grants leadership to every replica.
        FakeSupport support = new FakeSupport();
        new CouchCaptureIntentStore(support).sweepExpiredIntents(9_999L, 25);

        assertEquals(25, support.lastViewParams.get("limit"));
    }

    // ── Retention ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unresolved rows past the cutoff are deleted")
    void purgeUnresolved() {
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.UNRESOLVED, "bedroom"));

        assertEquals(1, new CouchCaptureIntentStore(support)
                .purgeUnresolvedOlderThan(9_999L, 100));
        assertEquals(List.of("lineage_capture:i-1"), support.deleted);
    }

    @Test
    @DisplayName("captured rows past the cutoff are deleted")
    void purgeCaptured() {
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.CAPTURED, "bedroom"));

        assertEquals(1, new CouchCaptureIntentStore(support).purgeCapturedOlderThan(9_999L, 100));
        assertEquals(List.of("lineage_capture:i-1"), support.deleted);
    }

    @Test
    @DisplayName("purge re-checks state, so a stale view cannot delete the wrong row")
    void purgeRechecksState() {
        // The one that matters: an open intent deleted here would destroy the only evidence that
        // something crashed mid-ingest.
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.CAPTURE_INTENT, "bedroom"));

        assertEquals(0, new CouchCaptureIntentStore(support)
                .purgeUnresolvedOlderThan(9_999L, 100));
        assertEquals(0, new CouchCaptureIntentStore(support).purgeCapturedOlderThan(9_999L, 100));
        assertTrue(support.deleted.isEmpty());
    }

    @Test
    @DisplayName("each purge only touches its own state")
    void purgesDoNotOverlap() {
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.CAPTURED, "bedroom"));

        assertEquals(0, new CouchCaptureIntentStore(support)
                .purgeUnresolvedOlderThan(9_999L, 100),
                "a captured row must not be deleted by the unresolved retention setting");
    }

    // ── Listing ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the listing returns whole rows, newest first")
    void listingIsSelfDescribing() {
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.UNRESOLVED, "bedroom"));

        UnresolvedPageAssert page = UnresolvedPageAssert.of(
                new CouchCaptureIntentStore(support).listUnresolved(null, 10, 0));

        assertEquals(1, page.size());
        assertEquals("bedroom", page.first().get("repositoryId"));
        assertEquals(Boolean.TRUE, support.lastViewParams.get("descending"));
    }

    @Test
    @DisplayName("the repository filter is a view key, not a filter applied after paging")
    void listingFiltersInsideCouchDb() {
        // Filtering in Java after the page was cut is wrong, not merely slow: limit and skip are
        // applied by CouchDB first, so a busy repository's rows push another repository's out of
        // the page and the caller is told there is nothing there (external review).
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.UNRESOLVED, "canopy"));

        new CouchCaptureIntentStore(support).listUnresolved("canopy", 10, 0);

        assertEquals(CouchCaptureIntentStore.VIEW_UNRESOLVED_BY_REPO, support.lastViewName,
                "a repository-scoped listing must use the composite-key view");
        assertEquals(List.of("canopy", Long.MAX_VALUE), support.lastViewParams.get("startkey"));
        assertEquals(List.of("canopy", 0), support.lastViewParams.get("endkey"));
    }

    @Test
    @DisplayName("an unscoped listing uses the plain view and asks for no key range")
    void unscopedListingUsesThePlainView() {
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.UNRESOLVED, "bedroom"));

        assertEquals(1, UnresolvedPageAssert.of(new CouchCaptureIntentStore(support)
                .listUnresolved(null, 10, 0)).size());
        assertEquals(CouchCaptureIntentStore.VIEW_UNRESOLVED, support.lastViewName);
        org.junit.jupiter.api.Assertions.assertNull(support.lastViewParams.get("startkey"));
    }

    @Test
    @DisplayName("a blank repository is treated as no filter, not as an empty repository id")
    void blankRepositoryIsNoFilter() {
        FakeSupport support = new FakeSupport();
        support.viewRows.add(row("lineage_capture:i-1", CaptureState.UNRESOLVED, "bedroom"));

        assertEquals(1, UnresolvedPageAssert.of(new CouchCaptureIntentStore(support)
                .listUnresolved("  ", 10, 0)).size());
        assertEquals(CouchCaptureIntentStore.VIEW_UNRESOLVED, support.lastViewName);
    }

    @Test
    @DisplayName("hasMore is answered by fetching one extra row, not by counting the view")
    void listingReportsHasMore() {
        FakeSupport support = new FakeSupport();
        for (int i = 0; i < 3; i++) {
            support.viewRows.add(row("lineage_capture:i-" + i, CaptureState.UNRESOLVED, "bedroom"));
        }

        var page = new CouchCaptureIntentStore(support).listUnresolved(null, 2, 0);

        assertEquals(2, page.entries().size());
        assertTrue(page.hasMore());
        assertEquals(3, support.lastViewParams.get("limit"), "limit + 1, to answer hasMore");
    }

    @Test
    @DisplayName("the page size is bounded")
    void listingBoundsThePageSize() {
        FakeSupport support = new FakeSupport();

        assertEquals(500, new CouchCaptureIntentStore(support)
                .listUnresolved(null, 100_000, 0).limit());
        assertEquals(50, new CouchCaptureIntentStore(support).listUnresolved(null, 0, 0).limit());
    }

    /** Tiny helper so the listing assertions read as assertions rather than as plumbing. */
    private record UnresolvedPageAssert(java.util.List<Map<String, Object>> entries) {
        static UnresolvedPageAssert of(
                jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore.UnresolvedPage p) {
            return new UnresolvedPageAssert(p.entries());
        }
        int size() { return entries.size(); }
        Map<String, Object> first() { return entries.get(0); }
    }
}
