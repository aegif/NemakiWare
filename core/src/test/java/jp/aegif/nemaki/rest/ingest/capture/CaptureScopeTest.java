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
package jp.aegif.nemaki.rest.ingest.capture;

import jp.aegif.nemaki.rest.ingest.capture.CaptureIntentStore.CaptureCompletion;
import jp.aegif.nemaki.rest.ingest.capture.CaptureScope.CaptureIntentFailedException;
import jp.aegif.nemaki.rest.ingest.capture.CaptureScope.CaptureResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture boundary: an intent exists before the first change, or nothing changes.
 *
 * <p>Ingesting one item writes to two databases — the content to the repository, the evidence to
 * the shared lineage database — and CouchDB has no transaction across databases. Today the
 * content is written first and the evidence second, so a failure between them leaves a document
 * whose origin nothing records, silently. Reversing the order is what makes that state
 * unreachable: whatever happens, a row exists.
 */
class CaptureScopeTest {

    /** A store that records what it was asked to do and answers however the test says. */
    private static final class FakeStore implements CaptureIntentStore {
        final List<CaptureIntent> opened = new ArrayList<>();
        final List<Map<String, Object>> completedWith = new ArrayList<>();
        boolean openSucceeds = true;
        boolean active = true;
        CaptureCompletion completion = CaptureCompletion.COMPLETED;

        @Override
        public boolean openIntent(CaptureIntent intent) {
            opened.add(intent);
            return openSucceeds;
        }

        @Override
        public CaptureCompletion completeIntent(CaptureIntent intent, Map<String, Object> evidence) {
            completedWith.add(evidence);
            return completion;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public Applicability appliesTo(String repositoryId) {
            appliesToCalls.add(repositoryId);
            return applicability;
        }

        Applicability applicability = Applicability.APPLIES;
        final List<String> appliesToCalls = new ArrayList<>();
    }

    private static CaptureIntent intent() {
        return new CaptureIntent(CaptureIntent.documentIdFor("i-1"), "i-1", 1_700_000_000_000L,
                "bedroom", "conn-1", "slack", "message", "1720000000.000200", "req-1",
                "correspondence", "admin", "alice");
    }

    private static CaptureScope scope(FakeStore store) {
        return new CaptureScope(store, intent());
    }

    // ── Ordering: the intent precedes the first change ────────────────────────────────────

    @Test
    @DisplayName("creating a scope writes nothing")
    void creatingAScopeWritesNothing() {
        // Rule 2: the wrapper builds it un-opened, because at that point it cannot know whether
        // execute() will change anything.
        FakeStore store = new FakeStore();

        scope(store);

        assertTrue(store.opened.isEmpty(), "an intent written at construction would create a row "
                + "for every dry run and every dedupe skip");
    }

    @Test
    @DisplayName("a path that changes nothing completes without ever creating a row")
    void noMutationMeansNoRow() {
        // Dry run, skipped empty attachments, dedupe and idempotency skips all end here. A row
        // opened for these could never be completed — there is no object — and the sweeper would
        // report it as unresolved, which is false: the caller knows it changed nothing.
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);

        CaptureResult result = scope.complete(Map.of());

        assertTrue(store.opened.isEmpty());
        assertTrue(store.completedWith.isEmpty());
        assertFalse(result.captured());
        assertNull(result.warning(), "changing nothing is a normal ending, not a problem");
    }

    @Test
    @DisplayName("opening is idempotent, so every mutation site may call it")
    void openingIsIdempotent() {
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);

        scope.ensureIntentOpened();
        scope.ensureIntentOpened();
        scope.ensureIntentOpened();

        assertEquals(1, store.opened.size(),
                "a mutation site must not need to know whether it is the first");
    }

    // ── Fail-closed: an unwritable intent stops the ingest ────────────────────────────────

    @Test
    @DisplayName("an intent that cannot be written stops the ingest before it changes anything")
    void unwritableIntentIsFailClosed() {
        FakeStore store = new FakeStore();
        store.openSucceeds = false;
        CaptureScope scope = scope(store);

        assertThrows(CaptureIntentFailedException.class, scope::ensureIntentOpened);
        assertFalse(scope.isOpened());
    }

    @Test
    @DisplayName("a store that reports failure by RETURN VALUE is believed")
    void failureIsReadFromTheReturnValue() {
        // The point of the interface. CloudantClientWrapper.create and .update catch everything
        // and return null, and append does not check that — so a 500, an auth failure and an
        // oversized document all arrive without an exception. Treating "nothing was thrown" as
        // success is how fail-closed silently becomes fail-open.
        FakeStore store = new FakeStore();
        store.openSucceeds = false;   // no exception thrown anywhere

        assertThrows(CaptureIntentFailedException.class, () -> scope(store).ensureIntentOpened());
    }

    // ── What may be completed ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every tracked change succeeding completes the capture")
    void allSucceededCompletes() {
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);
        scope.ensureIntentOpened();
        scope.record("createDocument", MutationOutcome.SUCCEEDED);
        scope.record("applyAcl", MutationOutcome.SUCCEEDED);

        CaptureResult result = scope.complete(Map.of("objectId", "obj-1"));

        assertTrue(result.captured());
        assertNull(result.warning());
        assertEquals(1, store.completedWith.size());
        assertEquals("obj-1", store.completedWith.get(0).get("objectId"));
    }

    @Test
    @DisplayName("a failed change stops the capture and tells the caller which one")
    void failedMutationBlocksCapture() {
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);
        scope.ensureIntentOpened();
        scope.record("createDocument", MutationOutcome.SUCCEEDED);
        scope.record("applyAcl", MutationOutcome.FAILED, "stale revision");

        CaptureResult result = scope.complete(Map.of());

        assertFalse(result.captured());
        assertTrue(store.completedWith.isEmpty(),
                "the row stays CAPTURE_INTENT so the sweeper can report it as unresolved");
        assertNotNull(result.warning());
        assertTrue(result.warning().contains("applyAcl"), result.warning());
        assertFalse(result.warning().contains("createDocument"),
                "naming the changes that DID succeed would bury the one that did not: "
                        + result.warning());
    }

    @Test
    @DisplayName("an indeterminate change stops the capture just as a failure does")
    void indeterminateMutationBlocksCapture() {
        // The whole reason the outcome has three values. The ingest path's writes go through
        // wrappers that return null for every kind of failure, so "we could not tell" is the
        // common case, not the exotic one. Collapsing it into success is how a capture claims
        // evidence it does not have.
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);
        scope.ensureIntentOpened();
        scope.record("updateProperties", MutationOutcome.INDETERMINATE, "write returned null");

        CaptureResult result = scope.complete(Map.of());

        assertFalse(result.captured());
        assertTrue(store.completedWith.isEmpty());
        assertTrue(result.warning().contains("INDETERMINATE"), result.warning());
    }

    @Test
    @DisplayName("the evidence records each tracked change and its outcome")
    void evidenceCarriesTheMutations() {
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);
        scope.ensureIntentOpened();
        scope.record("createDocument", MutationOutcome.SUCCEEDED);

        scope.complete(Map.of("objectId", "obj-1"));

        Object mutations = store.completedWith.get(0).get("mutations");
        assertTrue(mutations.toString().contains("createDocument"), mutations.toString());
        assertTrue(mutations.toString().contains("SUCCEEDED"), mutations.toString());
    }

    // ── The row was not there when completing ────────────────────────────────────────────

    @Test
    @DisplayName("an unreadable row is reported as an observation, with no cause named")
    void unreadableRowNamesNoCause() {
        // The layers below collapse a missing document, a transient failure and a write that
        // never landed into the same answer, so naming one of them would be a guess presented
        // as a finding.
        FakeStore store = new FakeStore();
        store.completion = CaptureCompletion.ROW_UNREADABLE;
        CaptureScope scope = scope(store);
        scope.ensureIntentOpened();
        scope.record("createDocument", MutationOutcome.SUCCEEDED);

        CaptureResult result = scope.complete(Map.of());

        assertFalse(result.captured());
        String w = result.warning();
        assertTrue(w.contains("could not be read"), w);
        assertTrue(w.contains("retention") && w.contains("transient") && w.contains("never"),
                "all three possibilities must be offered, none asserted: " + w);
    }

    @Test
    @DisplayName("a row that cannot take the transition is reported without being forced")
    void notCompletableIsReported() {
        FakeStore store = new FakeStore();
        store.completion = CaptureCompletion.NOT_COMPLETABLE;
        CaptureScope scope = scope(store);
        scope.ensureIntentOpened();
        scope.record("createDocument", MutationOutcome.SUCCEEDED);

        CaptureResult result = scope.complete(Map.of());

        assertFalse(result.captured());
        assertNotNull(result.warning());
    }

    // ── Inert when the collaborator is absent ────────────────────────────────────────────

    @Test
    @DisplayName("with no store wired, nothing happens and nothing fails")
    void inactiveScopeIsInert() {
        // This is the condition the fail-closed behaviour hangs on — the collaborator being
        // present — not a test-only flag. It has the same shape as the existing
        // ingestLineageEmitter != null guard.
        CaptureScope scope = CaptureScope.inactive();

        scope.ensureIntentOpened();   // must not throw
        scope.record("createDocument", MutationOutcome.FAILED);

        assertFalse(scope.isActive());
        assertFalse(scope.complete(Map.of()).captured());
        assertNull(scope.complete(Map.of()).warning());
    }

    @Test
    @DisplayName("an unreachable store on an applicable repository is a refusal, not silence")
    void unreachableStoreIsARefusal() {
        // An inert scope here meant a provisioning failure silently disabled the whole boundary:
        // nothing opened, nothing recorded, nothing warned, and the ingest reported success
        // (external review).
        FakeStore store = new FakeStore();
        store.active = false;

        CaptureScope scope = scope(store);
        assertThrows(CaptureIntentFailedException.class, scope::ensureIntentOpened);
        assertTrue(store.opened.isEmpty());
        assertTrue(scope.wasOpenRefused());
    }

    @Test
    @DisplayName("the mode is asked before the store is ever touched")
    void modeIsAskedBeforeAvailability() {
        // isActive() provisions the lineage database. Asking it first created that database, and
        // deployed its design documents, on deployments where every repository is disabled.
        FakeStore store = new FakeStore();
        store.applicability = CaptureIntentStore.Applicability.NOT_APPLICABLE;
        store.active = false;

        scope(store).ensureIntentOpened();   // must not throw and must not ask about the store

        assertEquals(1, store.appliesToCalls.size());
        assertTrue(store.opened.isEmpty());
    }

    @Test
    @DisplayName("an ACTIVE store does fail closed — the control for the two tests above")
    void activeStoreStillFailsClosed() {
        // Without this, making isActive() always false would pass every test in this class while
        // disabling the entire feature.
        FakeStore store = new FakeStore();
        store.active = true;
        store.openSucceeds = false;

        assertThrows(CaptureIntentFailedException.class, () -> scope(store).ensureIntentOpened());
    }

    @Test
    @DisplayName("a refused open is latched, so a swallowed exception still shows")
    void refusalIsLatched() {
        // The ingest path is full of catch (Exception) blocks that turn anything into a warning
        // string. Guards were added at each one, but a missed guard must not be able to turn
        // fail-closed into a note in the margin — so the owner reads this when building the
        // result rather than relying on the throw having survived.
        FakeStore store = new FakeStore();
        store.openSucceeds = false;
        CaptureScope scope = scope(store);

        try {
            scope.ensureIntentOpened();
        } catch (CaptureIntentFailedException swallowed) {
            // exactly what a stray catch (Exception) would do
        }

        assertTrue(scope.wasOpenRefused());
    }

    @Test
    @DisplayName("a scope that was never refused does not claim it was — the control")
    void noRefusalWhenOpenSucceeds() {
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);

        scope.ensureIntentOpened();

        assertFalse(scope.wasOpenRefused());
    }

    @Test
    @DisplayName("a repository the boundary skips is not a refusal")
    void notApplicableIsNotARefusal() {
        // Otherwise every ingest into a disabled repository would be reported as failed.
        FakeStore store = new FakeStore();
        store.applicability = CaptureIntentStore.Applicability.NOT_APPLICABLE;
        CaptureScope scope = scope(store);

        scope.ensureIntentOpened();

        assertFalse(scope.wasOpenRefused());
    }

    // ── Per-repository mode ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a repository the boundary does not apply to is left exactly as it is today")
    void notApplicableRepositoryIsUntouched() {
        // Lineage mode is per repository. Refusing an ingest into a repository whose mode is
        // disabled or direct would stop deployments that never asked for the boundary.
        FakeStore store = new FakeStore();
        store.applicability = CaptureIntentStore.Applicability.NOT_APPLICABLE;
        store.openSucceeds = false;   // would fail closed if the gate were not consulted
        CaptureScope scope = scope(store);

        scope.ensureIntentOpened();   // must not throw
        scope.record("createDocument", MutationOutcome.SUCCEEDED);

        assertTrue(store.opened.isEmpty());
        assertFalse(scope.isOpened());
        assertFalse(scope.complete(Map.of()).captured());
        assertNull(scope.complete(Map.of()).warning(), "not applicable is not a problem");
    }

    @Test
    @DisplayName("the mode is asked once, not per mutation")
    void modeIsLatched() {
        // Asking per mutation would mean a mid-operation configuration change could half-apply,
        // and would put a configuration read on every write.
        FakeStore store = new FakeStore();
        store.applicability = CaptureIntentStore.Applicability.NOT_APPLICABLE;
        CaptureScope scope = scope(store);

        scope.ensureIntentOpened();
        scope.ensureIntentOpened();
        scope.ensureIntentOpened();

        assertEquals(1, store.appliesToCalls.size());
        assertEquals("bedroom", store.appliesToCalls.get(0),
                "the repository being ingested into, not a global setting");
    }

    @Test
    @DisplayName("an undetermined repository is not refused, but is reported")
    void undeterminedIsReportedNotRefused() {
        // Two opposite mistakes are possible here and both have been made. Refusing fails every
        // ingest in every deployment that never enabled lineage, the moment nemaki_conf blinks
        // (guarantee 3). Staying silent is how a journaled repository gets ingested into with no
        // evidence at all. The answer is: change nothing, say so (external review).
        FakeStore store = new FakeStore();
        store.applicability = CaptureIntentStore.Applicability.UNDETERMINED;
        store.openSucceeds = false;   // would fail closed if this were treated as APPLIES
        CaptureScope scope = scope(store);

        scope.ensureIntentOpened();   // must not throw
        scope.record("createDocument", MutationOutcome.SUCCEEDED);

        assertTrue(store.opened.isEmpty(), "nothing may be written when we cannot tell");
        assertFalse(scope.wasOpenRefused(), "and it is not a refusal");
        CaptureResult result = scope.complete(Map.of());
        assertFalse(result.captured());
        assertNotNull(result.warning(), "but it must not be silent");
        assertTrue(result.warning().contains("could not be determined"), result.warning());
        assertTrue(result.warning().contains("does NOT establish"), result.warning());
    }

    @Test
    @DisplayName("a determinate not-applicable stays silent — the control")
    void determinateNotApplicableIsSilent() {
        // Without this, reporting a warning for every skipped repository would put a line in
        // front of an operator on every ingest of a deployment that runs without lineage.
        FakeStore store = new FakeStore();
        store.applicability = CaptureIntentStore.Applicability.NOT_APPLICABLE;
        CaptureScope scope = scope(store);

        scope.ensureIntentOpened();

        assertNull(scope.complete(Map.of()).warning());
    }

    @Test
    @DisplayName("an applicable repository still fails closed — the control")
    void applicableRepositoryStillFailsClosed() {
        // Without this, hard-coding appliesTo to false would pass the two tests above while
        // disabling the whole feature.
        FakeStore store = new FakeStore();
        store.applicability = CaptureIntentStore.Applicability.APPLIES;
        store.openSucceeds = false;

        assertThrows(CaptureIntentFailedException.class, () -> scope(store).ensureIntentOpened());
    }

    @Test
    @DisplayName("once open, completion does not re-check the mode")
    void completionDoesNotRecheckTheMode() {
        // Switching a repository to disabled half way through an ingest must not strand an
        // intent that is already open.
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);
        scope.ensureIntentOpened();
        scope.record("createDocument", MutationOutcome.SUCCEEDED);
        store.applicability = CaptureIntentStore.Applicability.NOT_APPLICABLE;                 // the operator switches it off mid-ingest

        assertTrue(scope.complete(Map.of()).captured());
        assertEquals(1, store.completedWith.size());
    }

    // ── Completing twice ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("completing twice does not write twice")
    void completingTwiceIsIdempotent() {
        FakeStore store = new FakeStore();
        CaptureScope scope = scope(store);
        scope.ensureIntentOpened();
        scope.record("createDocument", MutationOutcome.SUCCEEDED);

        scope.complete(Map.of());
        CaptureResult second = scope.complete(Map.of());

        assertEquals(1, store.completedWith.size());
        assertTrue(second.captured());
    }

    // ── The stored shape ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the intent id is out of reach of the journal's id-addressed paths")
    void intentIdIsNotAJournalId() {
        // findByRecordId and getRetryCount build "lineage:" + recordId with no type check at all,
        // and updatePublishStatus and discardEvent exclude only v2. Under that prefix the admin
        // console's retry and discard buttons could write to an intent row — transitions the
        // state machine does not have.
        String id = CaptureIntent.documentIdFor("i-1");

        assertFalse(id.startsWith("lineage:"), id);
        assertEquals("lineage_capture:i-1", id);
    }

    @Test
    @DisplayName("the stored row carries what an operator needs to know what was attempted")
    void storedRowIsSelfDescribing() {
        // The unresolved listing shows this row and nothing else. An intent id and a timestamp
        // would not tell an operator what happened.
        Map<String, Object> doc = intent().toDocument();

        assertEquals("lineage_capture_intent", doc.get("type"));
        assertEquals("CAPTURE_INTENT", doc.get("captureState"));
        assertEquals("bedroom", doc.get("repositoryId"));
        assertEquals("slack", doc.get("sourceSystem"));
        assertEquals("1720000000.000200", doc.get("sourceObjectId"));
        assertEquals("message", doc.get("sourceObjectType"));
        assertEquals("req-1", doc.get("requestId"));
        assertEquals("correspondence", doc.get("processType"));
        assertEquals("admin", doc.get("executedBy"));
        assertEquals("alice", doc.get("onBehalfOf"));
        assertEquals(1_700_000_000_000L, doc.get("intentOpenedAtMs"));
        assertFalse(doc.containsKey("publishStatusByTarget"),
                "the existing v1 projection view selects on this field; carrying it would put an "
                        + "incomplete intent into the delivery pipeline");
    }
}
