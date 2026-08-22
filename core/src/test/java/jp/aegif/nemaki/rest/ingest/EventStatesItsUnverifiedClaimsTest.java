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
        assertEquals(10, chat.size(),
                "the chat block is ten facts on the event — capturedAt is the eleventh property "
                        + "and it is stamped after the emit, so no event carries it: " + chat);
        for (CaptureEvidenceField field : chat) {
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
        assertEquals(Assurance.OBSERVED, CaptureEvidenceField.CONTENT_STORED.assurance());
        assertEquals(Assurance.OBSERVED, CaptureEvidenceField.CONTENT_HASH_SUBJECT.assurance());
        assertEquals(Assurance.APPLIED, CaptureEvidenceField.TARGET_FOLDER_ID.assurance());
        // The connector's own configuration is a claim too, but by a different party at a
        // different time, so it is kept apart rather than folded into ASSERTED.
        assertEquals(Assurance.CONFIGURED, CaptureEvidenceField.SOURCE_SYSTEM.assurance());
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
    @DisplayName("the order is the table's, so two events are diffable")
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
    }

    @Test
    @DisplayName("an event with no unverified claims carries no list")
    void nothingAssertedMeansNoKey() {
        // An empty string would read as "we checked, and the answer is nothing", which is true,
        // but it would also put the key on every content-only event for no benefit.
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("contentHash", "a".repeat(64));
        assertEquals("", IngestLineageEmitter.assertedKeysIn(snapshot));
    }
}
