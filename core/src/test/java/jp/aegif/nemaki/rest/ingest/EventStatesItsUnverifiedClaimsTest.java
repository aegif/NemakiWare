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
package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.rest.ingest.CaptureEvidenceField.Assurance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lineage event must say which of its own facts nothing verified (P1-1(d) R2/R4).
 *
 * <h2>The defect this closes</h2>
 *
 * <p>{@code buildV1Snapshot} carries ten of the eleven chat evidence facts, read from
 * {@code request.getMetadata()} — the same map the wrapper reads when it writes them to the
 * object. The ingest never contacts the chat service to confirm any of them, and the wrapper's
 * write can fail and be downgraded to a warning, so the event can assert a channel id for a
 * document that has no chat aspect at all. Nothing in the event said which of its facts were
 * mere claims (external review, P1-1(d) D1).
 *
 * <p>The design first blamed this on ordering and proposed moving the emit. That was wrong on
 * the facts — the values are present at emit time — and the fix is to state the strength of the
 * claim instead, which needs no reordering.
 */
class EventStatesItsUnverifiedClaimsTest {

    @Test
    @DisplayName("the enum's order IS the documented strength order — weakest first")
    void theDeclarationOrderIsTheStrengthOrder() {
        // The class javadoc mandates ASSERTED < CONFIGURED < APPLIED < OBSERVED and invites a
        // "weakest wins" computation. The first declaration inverted APPLIED and OBSERVED, so
        // the first Collections.min over the enum would have called OBSERVED the weakest
        // (external review). Ordinals must agree with the lattice, and this holds them to it.
        assertTrue(Assurance.ASSERTED.ordinal() < Assurance.CONFIGURED.ordinal());
        assertTrue(Assurance.CONFIGURED.ordinal() < Assurance.APPLIED.ordinal());
        assertTrue(Assurance.APPLIED.ordinal() < Assurance.OBSERVED.ordinal(),
                "APPLIED must rank below OBSERVED: 'we wrote it and the call returned' is a "
                        + "weaker justification than 'we read it back'");
    }

    @Test
    @DisplayName("every fact declares how strongly it is known")
    void everyFactDeclaresItsAssurance() {
        // A field added without one cannot compile — the constructor requires it — so this
        // guards the weaker failure: a null slipped through some future overload.
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            assertTrue(field.assurance() != null,
                    field.v1Key() + " does not say how strongly its value is known");
        }
    }

    @Test
    @DisplayName("the whole chat block is ASSERTED — nothing verifies any of it")
    void theChatBlockIsAsserted() {
        // The load-bearing claim of D1. If someone re-classifies one of these as OBSERVED or
        // APPLIED without adding a check, this is what fails.
        List<CaptureEvidenceField> chat = Arrays.stream(CaptureEvidenceField.values())
                .filter(f -> f.v1Key().startsWith("chat."))
                .toList();
        assertEquals(11, chat.size(),
                "the chat block gained its eleventh at P1-1(e): the beforeEmit hook stamps "
                        + "capturedAt BEFORE emission, so the event now carries it too: " + chat);
        for (CaptureEvidenceField field : chat) {
            if (field == CaptureEvidenceField.CHAT_CAPTURED_AT) {
                // The one chat fact that is OURS: the server clock at the moment of capture,
                // observed by the stamping code — not read out of the caller's metadata.
                assertEquals(Assurance.OBSERVED, field.assurance(),
                        "the custody stamp is this deployment's own observation");
                continue;
            }
            assertEquals(Assurance.ASSERTED, field.assurance(),
                    field.v1Key() + " is recorded as verified, but the ingest reads it straight "
                            + "out of the request metadata and checks nothing");
        }
    }

    @Test
    @DisplayName("what we computed ourselves is not lumped in with what we were told")
    void ourOwnObservationsAreNotAsserted() {
        // The counterweight. Marking everything ASSERTED would pass the test above and make the
        // list useless, so pin the other side too.
        assertEquals(Assurance.OBSERVED, CaptureEvidenceField.CONTENT_HASH.assurance());
        assertEquals(Assurance.OBSERVED, CaptureEvidenceField.CONTENT_HASH_SUBJECT.assurance());
        assertEquals(Assurance.APPLIED, CaptureEvidenceField.REIMPORT_FILLED.assurance());
        // The connector's own configuration is a claim too, but by a different party at a
        // different time, so it is kept apart rather than folded into ASSERTED.
        assertEquals(Assurance.CONFIGURED, CaptureEvidenceField.SOURCE_SYSTEM.assurance());
        // And the two the review corrected: contentStored is INFERRED from a successful write on
        // the ordinary path rather than read back, and targetFolderId can come straight from the
        // request. Both take the weakest justification any of their paths uses.
        assertEquals(Assurance.APPLIED, CaptureEvidenceField.CONTENT_STORED.assurance());
        assertEquals(Assurance.ASSERTED, CaptureEvidenceField.TARGET_FOLDER_ID.assurance());
    }

    @Test
    @DisplayName("the list names only facts this event actually carries")
    void theListIsDerivedFromTheEventNotTheTable() {
        // Listing every ASSERTED field in the table would claim facts the caller never supplied.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceSystem", "acme");
        snapshot.put("chat.channelId", "C123");
        snapshot.put("contentHash", "a".repeat(64));

        String asserted = IngestLineageEmitter.assertedKeysIn(snapshot);

        assertTrue(asserted.contains("chat.channelId"));
        assertFalse(asserted.contains("chat.participants"),
                "the list named a fact this event does not carry: " + asserted);
        assertFalse(asserted.contains("contentHash"),
                "a digest this pass computed was listed as an unverified claim: " + asserted);
        assertFalse(asserted.contains("sourceSystem"),
                "the connector configuration was listed as a caller's claim: " + asserted);
    }

    @Test
    @DisplayName("the order does not depend on the build, so two events are diffable")
    void theOrderIsStable() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("chat.channelId", "C1");
        a.put("chat.workspaceId", "W1");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("chat.workspaceId", "W1");
        b.put("chat.channelId", "C1");

        assertEquals(IngestLineageEmitter.assertedKeysIn(a),
                IngestLineageEmitter.assertedKeysIn(b),
                "the same facts produced different strings depending on insertion order, so "
                        + "diffing two events shows changes that did not happen");
        assertEquals("chat.channelId,chat.workspaceId", IngestLineageEmitter.assertedKeysIn(a),
                "the order follows the enum's declaration, so reordering the table would rewrite "
                        + "the string on every future event");
    }

    @Test
    @DisplayName("a snapshot with no unverified claims yields an empty list")
    void nothingAssertedYieldsEmpty() {
        // A helper-level contract, and said so deliberately: a REAL ingest event always names at
        // least sourceObjectId, so production never takes this branch today. It is pinned
        // because the emitter's "omit the key when empty" guard depends on it, and because an
        // absent key must not later come to mean "this build had no assurance data".
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("contentHash", "a".repeat(64));
        assertEquals("", IngestLineageEmitter.assertedKeysIn(snapshot));
    }

    @Test
    @DisplayName("a blank value is not named as a claim")
    void blankIsNotAClaim() {
        // The rest of the snapshot machinery treats blank as absent; naming a blank key here
        // would report a claim the caller never made (external review).
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("chat.channelId", "  ");
        snapshot.put("chat.workspaceId", "W1");
        assertEquals("chat.workspaceId", IngestLineageEmitter.assertedKeysIn(snapshot));
    }

    @Test
    @DisplayName("a REAL snapshot carries the list, and names the facts the caller supplied")
    void aRealSnapshotCarriesIt() {
        // The tests above exercise the helper. This one drives buildV1Snapshot, because a helper
        // that is correct and never called is the failure mode this repository has hit before
        // (external review).
        Map<String, String> snapshot = realSnapshot();

        String asserted = snapshot.get("assuranceAsserted");
        assertTrue(asserted != null, "a real event carried no assurance list at all: " + snapshot);
        assertTrue(asserted.contains("chat.channelId"), asserted);
        assertTrue(asserted.contains("sourceObjectId"), asserted);
        assertFalse(asserted.contains("contentHash"), asserted);
        assertFalse(asserted.contains("sourceSystem"), asserted);
    }

    @Test
    @DisplayName("a REAL snapshot's digest says it is of the input")
    void aRealSnapshotNamesTheDigestSubject() {
        Map<String, String> snapshot = realSnapshot();

        assertEquals("a".repeat(64), snapshot.get("contentHash"));
        assertEquals("input", snapshot.get("contentHashSubject"),
                "the digest went out without saying what it is of: " + snapshot);
    }

    /** A snapshot from the real builder, with a request that supplies chat facts and a digest. */
    private static Map<String, String> realSnapshot() {
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setSourceSystem("acme");
        connector.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);

        ExternalIngestRequest request = new ExternalIngestRequest();
        request.setRepositoryId("bedroom");
        request.setConnectorId("c1");
        request.setSourceObjectId("1720000000.000200");
        request.setSourceObjectType("message");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelId", "C123");
        metadata.put("participants", "otsuka,ishii");
        request.setMetadata(metadata);

        return new IngestLineageEmitter().buildV1Snapshot(connector, request, "folder-1",
                IngestLineageEmitter.CapturedContent.hashed("a".repeat(64)), "admin", null);
    }
}
