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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ScriptableObject;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Every CouchDB view of the lineage design document, executed — not pattern-matched — against a
 * synthetic document of each version.
 *
 * <h2>Why the JavaScript actually runs</h2>
 *
 * <p>v2 rows carry {@code type=lineage_event_v2} so old binaries' views cannot see them; the
 * price is that every view a new binary queries must cover both types, and a view that does not
 * makes v2 <em>silently</em> invisible (§6-a v2.3.14). A test that merely greps the view source
 * for both type literals would pass on a view whose emit is still guarded by a v1-only field —
 * {@code by_event_key}'s shape exactly. The property only binds if the map function executes
 * against a real v2 document and emits.
 */
public class LineageJournalViewCoverageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> DEAD_LETTER_VIEWS =
            Set.of("dead_letter_by_time", "dead_letter_by_replayed");

    /**
     * Views over a document type that is not an event at all (§2's catalog obligations).
     *
     * <p>The coverage property below is about event rows: every view an event should be
     * visible in must emit for one. A view indexing a different type emits nothing for an
     * event by design, and asserting otherwise would demand it index events it has no business
     * with. Listed rather than pattern-matched, so a new one has to be classified deliberately.
     */
    private static final Set<String> OBLIGATION_VIEWS = Set.of("obligationsByState");

    /**
     * v1-ONLY views (D-rest-2 schema split): every selector that feeds v1 machinery — claim,
     * drains, reaper, ordered walk, purge. Isolation by selection is the only isolation that
     * also protects OLD binaries, which query these names with no v2 guards downstream.
     */
    private static final Set<String> V1_ONLY_VIEWS = Set.of(
            "by_event_key", "by_target_status", "by_target_status_time",
            "non_terminal_by_target_repo", "projecting_by_claimed_at",
            "by_repository_and_sequence", "by_occurred_at");

    /** v2-only §8-b/§8-d views (D-rest-2/3); old binaries never query these names. */
    private static final Set<String> V2_PROJECTION_VIEWS = Set.of(
            "v2_by_repository_and_sequence", "v2_by_occurred_at",
            "v2_non_terminal_by_target_repo", "v2_claims_by_expiry", "v2_verifying_by_since",
            "v2_replay_requests_unacked", "v2_sequenced_repositories");

    /**
     * v2-only and state-specific by definition (§8-a v2, D-rest-1): the fenced sequencer's
     * scans and its rewind watermark. Excluded from the generic both-versions loops; their
     * per-state behaviour is pinned in a dedicated test below.
     */
    private static final Set<String> V2_SEQUENCER_VIEWS = Set.of(
            "v2_sequencer_backlog", "v2_sequencer_in_flight", "sequence_watermark");

    // ------------------------------------------------------------------ fixtures

    private static Map<String, Object> v1Document() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .addOutput("nemaki://bedroom/archives/doc-1")
                .targets(List.of("atlas"))
                .build();
        Map<String, Object> doc = new CouchLineageEvent(event).toMap();
        doc.put("sequenceNumber", 7L);
        // PROJECTING so the claim, oldest-first, non-terminal and stale-reaping views all have
        // something to emit; PENDING would leave projecting_by_claimed_at legitimately silent.
        doc.put("publishStatusByTarget", Map.of("atlas", "PROJECTING"));
        return doc;
    }

    private static Map<String, Object> v2Document() {
        LineageEventV2 event = new LineageEventV2Builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .occurredAt("2026-08-01T00:00:00Z")
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("atlas")))
                .addInput(LineageEndpoint.document("bedroom", "doc-1", "a.txt"))
                .addOutput(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L))
                .sequenceNumber(7L)
                .build();
        Map<String, Object> doc = new LinkedHashMap<>(CouchLineageEventV2.toMap(event));
        doc.put("state", "SEQUENCED");
        doc.put("publishStatusByTarget", Map.of("atlas", "PROJECTING"));
        doc.put("v2ClaimByTarget", Map.of("atlas", Map.of(
                "token", "tok-1", "claimedAtMs", 1000L, "leaseExpiresAtMs", 2000L,
                "retryCount", 0L)));
        return doc;
    }

    private static Map<String, Object> deadLetterDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "lineage_dl:evt-1");
        doc.put("type", "lineage_dead_letter");
        doc.put("recordedAt", "2026-08-01T00:00:00Z");
        doc.put("replayed", false);
        return doc;
    }

    /** Runs one deployed map function against one document and returns what it emitted. */
    private static List<?> emits(String viewName, Map<String, Object> doc) {
        String mapFunction = CouchLineageJournalStore.VIEWS.get(viewName).map();
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            ScriptableObject scope = cx.initStandardObjects();
            String script;
            try {
                script = "var emits = [];\n"
                        + "function emit(k, v) { emits.push([k, v]); }\n"
                        + "var doc = (" + JSON.writeValueAsString(doc) + ");\n"
                        + "(" + mapFunction + ")(doc);\n"
                        + "JSON.stringify(emits);";
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new AssertionError(e);
            }
            Object result = cx.evaluateString(scope, script, viewName, 1, null);
            try {
                return JSON.readValue(Context.toString(result), List.class);
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                throw new AssertionError(e);
            }
        } finally {
            Context.exit();
        }
    }

    private static Set<String> eventViews() {
        Set<String> names = new java.util.LinkedHashSet<>(CouchLineageJournalStore.VIEWS.keySet());
        names.removeAll(DEAD_LETTER_VIEWS);
        names.removeAll(OBLIGATION_VIEWS);
        return names;
    }

    // ------------------------------------------------------------------ the coverage property

    /**
     * The D-rest-2 schema split, executed as a property: a SEQUENCED v2 row with a live claim
     * emits in every v2 projection view and every remaining dual listing view, and in NO
     * v1-only view. §6-a's original both-versions obligation is superseded for the v1
     * machinery views — for those, v2 INVISIBILITY is the contract (old binaries query them
     * with token-less machinery downstream).
     */
    @Test
    public void everyEventViewEmitsForASyntheticV2Document() {
        Map<String, Object> doc = v2Document();
        for (String view : eventViews()) {
            if (V2_SEQUENCER_VIEWS.contains(view)) {
                continue;
            }
            if ("v2_verifying_by_since".equals(view)
                    || "v2_replay_requests_unacked".equals(view)) {
                continue; // state-specific — each pinned in its dedicated test
            }
            if (V1_ONLY_VIEWS.contains(view)) {
                assertTrue(emits(view, doc).isEmpty(),
                        view + " is v1-only; a v2 row emitting here reopens the old-binary"
                                + " accident the schema split closed");
            } else {
                assertFalse(emits(view, doc).isEmpty(),
                        view + " emitted nothing for a v2 document — v2 rows would be"
                                + " invisible to whatever queries this view");
            }
        }
    }

    /**
     * The obligation index emits for an obligation and for nothing else.
     *
     * <p>Its own coverage, since it is excluded from the event property above: a view that
     * indexed events as well would put unrelated rows in front of the scanner and the
     * reclaimer.
     */
    @Test
    public void theObligationViewEmitsOnlyForObligations() {
        Map<String, Object> obligation = new java.util.LinkedHashMap<>();
        obligation.put("_id", "lineage_catalog_obligation:abc");
        obligation.put("type", "lineage_catalog_obligation");
        obligation.put("state", "PENDING");

        assertFalse(emits("obligationsByState", obligation).isEmpty(),
                "an obligation must be visible to the scanner and the reclaimer");
        assertTrue(emits("obligationsByState", v1Document()).isEmpty());
        assertTrue(emits("obligationsByState", v2Document()).isEmpty());

        // A document with no state cannot be scheduled and must not be emitted as if it could.
        Map<String, Object> stateless = new java.util.LinkedHashMap<>(obligation);
        stateless.remove("state");
        assertTrue(emits("obligationsByState", stateless).isEmpty());
    }

    /** v1 rows emit everywhere except the v2-only families. */
    @Test
    public void everyEventViewStillEmitsForASyntheticV1Document() {
        Map<String, Object> doc = v1Document();
        for (String view : eventViews()) {
            if (V2_SEQUENCER_VIEWS.contains(view)) {
                continue;
            }
            if (V2_PROJECTION_VIEWS.contains(view)) {
                assertTrue(emits(view, doc).isEmpty(),
                        view + " is v2-only; a v1 row emitting here would hand v1 rows to"
                                + " token-fenced machinery they never signed up for");
            } else {
                assertFalse(emits(view, doc).isEmpty(),
                        view + " emitted nothing for a v1 document");
            }
        }
    }

    /**
     * {@code by_event_key} serves v1 append idempotency, keyed by a field v2 rows do not have.
     * Substituting {@code processKey} would silently change what "same event" means for its one
     * caller, so the view stays v1-only until a v2 key semantics is defined deliberately.
     */
    @Test
    public void byEventKeyIsDeliberatelyV1Only() {
        assertFalse(emits("by_event_key", v1Document()).isEmpty());
        assertTrue(emits("by_event_key", v2Document()).isEmpty(),
                "a v2 row has no eventKey; if this starts emitting, someone substituted another"
                        + " field without deciding its semantics");
    }

    @Test
    public void eventViewsIgnoreDeadLetterDocuments() {
        Map<String, Object> doc = deadLetterDocument();
        for (String view : eventViews()) {
            assertTrue(emits(view, doc).isEmpty(), view);
        }
    }

    @Test
    public void deadLetterViewsIgnoreEventDocumentsOfBothVersions() {
        for (String view : DEAD_LETTER_VIEWS) {
            assertTrue(emits(view, v1Document()).isEmpty(), view + " on v1");
            assertTrue(emits(view, v2Document()).isEmpty(), view + " on v2");
        }
    }

    // ------------------------------------------------------------------ shape pins

    /** A new view must join this test, or its v1/v2 coverage is nobody's problem. */
    @Test
    public void theViewSetIsExactlyTheKnownTwentyFour() {
        assertEquals(Set.of(
                        // §2's obligation index (v2.3.37). Not an event view — see
                        // OBLIGATION_VIEWS for why it is excluded from the coverage property.
                        "obligationsByState",
                        "by_event_key", "by_repository_and_time", "by_target_status",
                        "by_process_type", "by_occurred_at", "by_repository_and_process_type",
                        "dead_letter_by_time", "dead_letter_by_replayed", "by_target_status_time",
                        "by_repository_and_sequence", "non_terminal_by_target_repo",
                        "projecting_by_claimed_at", "by_process_type_time",
                        "by_repo_process_type_time", "v2_sequencer_backlog",
                        "v2_sequencer_in_flight", "sequence_watermark",
                        "v2_by_repository_and_sequence", "v2_by_occurred_at",
                        "v2_non_terminal_by_target_repo", "v2_claims_by_expiry",
                        "v2_verifying_by_since", "v2_replay_requests_unacked",
                        "v2_sequenced_repositories"),
                CouchLineageJournalStore.VIEWS.keySet(),
                "a view was added or renamed; give it v1/v2 coverage here before anything"
                        + " queries it");
    }

    /**
     * The sequencer views are state machines' eyes: each sees exactly its state, and none
     * sees v1 — a v1 row in a sequencer scan would be claimed by machinery v1 rows never
     * signed up for.
     */
    @Test
    public void theSequencerViewsSeeExactlyTheirStates() {
        Map<String, Object> unsequenced = v2Document();
        unsequenced.put("state", "UNSEQUENCED");
        Map<String, Object> sequencing = v2Document();
        sequencing.put("state", "SEQUENCING");
        Map<String, Object> sequenced = v2Document();
        sequenced.put("state", "SEQUENCED");
        sequenced.put("sequenceNumber", 7L);

        assertFalse(emits("v2_sequencer_backlog", unsequenced).isEmpty());
        assertTrue(emits("v2_sequencer_backlog", sequencing).isEmpty());
        assertTrue(emits("v2_sequencer_backlog", sequenced).isEmpty());
        assertTrue(emits("v2_sequencer_backlog", v1Document()).isEmpty());

        assertFalse(emits("v2_sequencer_in_flight", sequencing).isEmpty());
        assertTrue(emits("v2_sequencer_in_flight", unsequenced).isEmpty());
        assertTrue(emits("v2_sequencer_in_flight", v1Document()).isEmpty());

        assertFalse(emits("sequence_watermark", sequenced).isEmpty());
        assertTrue(emits("sequence_watermark", unsequenced).isEmpty(),
                "an unfinalized v2 row has no sequence to bound the counter with");
        assertFalse(emits("sequence_watermark", v1Document()).isEmpty(),
                "v1 sequences DO bound the shared counter (v2.3.18 ③) — a rewound counter"
                        + " must fail the check even before any v2 row exists");

        Map<String, Object> cursor = new java.util.HashMap<>();
        cursor.put("type", "projection_cursor");
        cursor.put("repositoryId", "bedroom");
        cursor.put("target", "purview");
        cursor.put("lastProcessedSequence", 42L);
        assertFalse(emits("sequence_watermark", cursor).isEmpty(),
                "target cursors bound the counter too");
    }

    /**
     * The router MERGES the two ordered views by sequence, so their keys must share one shape:
     * [repositoryId, sequenceNumber].
     */
    @Test
    public void theTwoOrderedViewsEmitTheSameKeyShape() {
        List<?> v1rows = emits("by_repository_and_sequence", v1Document());
        assertEquals(1, v1rows.size());
        assertEquals(List.of("bedroom", 7), ((List<?>) v1rows.get(0)).get(0));

        List<?> v2rows = emits("v2_by_repository_and_sequence", v2Document());
        assertEquals(1, v2rows.size());
        assertEquals(List.of("bedroom", 7), ((List<?>) v2rows.get(0)).get(0),
                "the composite key the merge relies on");
    }

    /**
     * The write-flip precondition (parallel review, 2026-08-01): appendV2 stores v2 rows with
     * state=UNSEQUENCED and every target PENDING. The ordered view already hid them
     * (sequenceNumber 0), but the claim, oldest-first and repo-discovery views fed the UNORDERED
     * path — which would have published a row before the fenced sequencer assigned its sequence.
     * Event-first means the event exists first, not that it is deliverable first.
     */
    @Test
    public void anUnsequencedV2RowIsInvisibleToEveryClaimFeedingView() {
        Map<String, Object> doc = v2Document();
        doc.put("state", "UNSEQUENCED");
        for (String view : List.of("by_target_status", "by_target_status_time",
                "non_terminal_by_target_repo")) {
            assertTrue(emits(view, doc).isEmpty(),
                    view + " must not expose an unsequenced row to the projector");
        }
    }

    /**
     * The allowlist's reason for being (parallel review follow-up): SEQUENCING — the fenced
     * sequencer's mid-assignment state (§8-a) — was still visible under a denylist of
     * UNSEQUENCED alone, and so would be any state a future slice invents. Deliverable is
     * explicit: no state field (v1), or SEQUENCED.
     */
    @Test
    public void aSequencingV2RowIsInvisibleToEveryClaimFeedingView() {
        Map<String, Object> doc = v2Document();
        doc.put("state", "SEQUENCING");
        for (String view : List.of("by_target_status", "by_target_status_time",
                "non_terminal_by_target_repo")) {
            assertTrue(emits(view, doc).isEmpty(),
                    view + " must not expose a mid-assignment row to the projector");
        }
    }

    /** Fail-closed for the unknown: a state nobody defined yet is not deliverable. */
    @Test
    public void anUnknownFutureStateIsInvisibleToTheClaimViews() {
        Map<String, Object> doc = v2Document();
        doc.put("state", "SOME_FUTURE_STATE");
        for (String view : List.of("by_target_status", "by_target_status_time",
                "non_terminal_by_target_repo")) {
            assertTrue(emits(view, doc).isEmpty(), view);
        }
    }

    /**
     * Once the sequencer finalizes the row, the V2 discovery view serves it — and the legacy
     * claim views still do NOT (the schema split is unconditional, not state-gated).
     */
    @Test
    public void aSequencedV2RowIsServedByTheV2ViewsOnly() {
        Map<String, Object> doc = v2Document();
        assertFalse(emits("v2_non_terminal_by_target_repo", doc).isEmpty());
        assertFalse(emits("v2_by_repository_and_sequence", doc).isEmpty());
        for (String view : List.of("by_target_status", "by_target_status_time",
                "non_terminal_by_target_repo", "projecting_by_claimed_at",
                "by_repository_and_sequence", "by_occurred_at")) {
            assertTrue(emits(view, doc).isEmpty(),
                    view + " must never serve a v2 row, whatever its state");
        }
    }

    /** The v2 discovery view is SEQUENCED-only: mid-sequencing rows are not deliverable. */
    @Test
    public void theV2DiscoveryViewHidesUnfinalizedRows() {
        for (String state : List.of("UNSEQUENCED", "SEQUENCING")) {
            Map<String, Object> doc = v2Document();
            doc.put("state", state);
            assertTrue(emits("v2_non_terminal_by_target_repo", doc).isEmpty(), state);
            assertTrue(emits("v2_by_repository_and_sequence", doc).isEmpty(), state);
        }
    }

    /**
     * The token-fenced reaper's scan: a live claim (PROJECTING/VERIFYING with a numeric lease
     * expiry) emits [target, leaseExpiresAtMs]; terminal rows and claims without an expiry do
     * not. The legacy reaper view sees no v2 row at all — a leased v2 claim must be physically
     * invisible to the token-less v1 reaper.
     */
    @Test
    public void theV2ClaimExpiryViewSeesExactlyLiveLeases() {
        Map<String, Object> live = v2Document();
        List<?> rows = emits("v2_claims_by_expiry", live);
        assertEquals(1, rows.size());
        assertEquals(List.of("atlas", 2000), ((List<?>) rows.get(0)).get(0));

        Map<String, Object> published = v2Document();
        published.put("publishStatusByTarget", Map.of("atlas", "PUBLISHED"));
        assertTrue(emits("v2_claims_by_expiry", published).isEmpty());

        Map<String, Object> noExpiry = v2Document();
        noExpiry.put("v2ClaimByTarget", Map.of("atlas", Map.of(
                "token", "tok-1", "claimedAtMs", 1000L, "retryCount", 0L)));
        assertTrue(emits("v2_claims_by_expiry", noExpiry).isEmpty(),
                "no numeric expiry, nothing to reap by");

        assertTrue(emits("projecting_by_claimed_at", live).isEmpty(),
                "the v1 reaper must be blind to v2 claims");
    }

    /** The §8-d recovery scan sees exactly unacked requests ([updatedAtMs, target]). */
    @Test
    public void theReplayScanViewSeesExactlyUnackedRequests() {
        for (String state : List.of("REQUESTED", "CREATED")) {
            Map<String, Object> doc = v2Document();
            doc.put("v2ReplayRequestsByTarget", Map.of("atlas", Map.of(
                    "state", state, "generation", 1L, "requestId", "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
                    "requestedAtMs", 1000L, "updatedAtMs", 1500L)));
            List<?> rows = emits("v2_replay_requests_unacked", doc);
            assertEquals(1, rows.size(), state);
            assertEquals(List.of(1500, "atlas"), ((List<?>) rows.get(0)).get(0));
        }
        for (String state : List.of("ACKED", "FAILED")) {
            Map<String, Object> doc = v2Document();
            doc.put("v2ReplayRequestsByTarget", Map.of("atlas", Map.of(
                    "state", state, "generation", 1L, "requestId", "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
                    "requestedAtMs", 1000L, "updatedAtMs", 1500L)));
            assertEquals(0, emits("v2_replay_requests_unacked", doc).size(),
                    state + " owes no recovery");
        }
        assertEquals(0, emits("v2_replay_requests_unacked", v1Document()).size());
    }

    /** v2.3.22 C2: terminal-only rows are still discoverable, per target. */
    @Test
    public void theSequencedRepositoryViewSeesTerminalRowsToo() {
        Map<String, Object> terminal = v2Document();
        terminal.put("publishStatusByTarget", Map.of("atlas", "UNRESOLVED"));
        List<?> rows = emits("v2_sequenced_repositories", terminal);
        assertEquals(1, rows.size(), "a terminal row must still be discoverable");
        assertEquals(List.of("atlas", "bedroom"), ((List<?>) rows.get(0)).get(0),
                "target-qualified: a repo never becomes visible to a target it does not owe");

        Map<String, Object> unsequenced = v2Document();
        unsequenced.put("state", "UNSEQUENCED");
        assertEquals(0, emits("v2_sequenced_repositories", unsequenced).size());
        assertEquals(0, emits("v2_sequenced_repositories", v1Document()).size());
    }

    /** D-rest-4: a materialization decision document emits in NO view (reached by _id only). */
    @Test
    public void aMaterializationDecisionEmitsNowhere() {
        Map<String, Object> doc = new java.util.HashMap<>();
        doc.put("_id", "lineage_materialization:" + "a".repeat(64));
        doc.put("type", "lineage_materialization");
        doc.put("spoolRecordId", "a".repeat(64));
        doc.put("occurredAt", "2026-08-01T00:00:00Z");
        doc.put("repositoryId", "bedroom");
        doc.put("sequenceNumber", 7L);
        doc.put("publishStatusByTarget", Map.of("atlas", "PENDING"));
        for (String view : CouchLineageJournalStore.VIEWS.keySet()) {
            assertTrue(emits(view, doc).isEmpty(), view);
        }
    }

    /** The verifying metrics view sees VERIFYING rows with a numeric since, and only those. */
    @Test
    public void theVerifyingMetricsViewSeesExactlyVerifyingRows() {
        Map<String, Object> verifying = v2Document();
        verifying.put("publishStatusByTarget", Map.of("atlas", "VERIFYING"));
        verifying.put("v2ClaimByTarget", Map.of("atlas", Map.of(
                "token", "tok-1", "claimedAtMs", 1000L, "leaseExpiresAtMs", 2000L,
                "verifyingSinceMs", 1500L, "retryCount", 0L)));
        List<?> rows = emits("v2_verifying_by_since", verifying);
        assertEquals(1, rows.size());
        assertEquals(List.of("atlas", 1500), ((List<?>) rows.get(0)).get(0));

        assertTrue(emits("v2_verifying_by_since", v2Document()).isEmpty(),
                "PROJECTING is not VERIFYING");
    }

    /**
     * Not a coverage rule, a semantics pin: an unsequenced row (sequenceNumber 0) is invisible to
     * the ordered view, for v2 exactly as for v1. The fenced sequencer is what makes it visible.
     */
    @Test
    public void anUnsequencedRowIsInvisibleToTheOrderedViewInBothVersions() {
        Map<String, Object> v1 = v1Document();
        v1.put("sequenceNumber", 0L);
        Map<String, Object> v2 = v2Document();
        v2.put("sequenceNumber", 0L);
        assertTrue(emits("by_repository_and_sequence", v1).isEmpty());
        assertTrue(emits("by_repository_and_sequence", v2).isEmpty());
    }
}
