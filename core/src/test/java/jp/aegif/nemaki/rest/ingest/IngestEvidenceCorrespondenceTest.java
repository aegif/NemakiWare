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

import jp.aegif.nemaki.rest.ingest.CaptureEvidenceField.V2Home;
import jp.aegif.nemaki.rest.ingest.IngestLineageEmitter.CapturedContent;
import jp.aegif.nemaki.rest.purview.journal.LineageEndpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two encodings of the same evidence must agree, fact by fact.
 *
 * <p>The ingest snapshot rides the v1 legacy projection, which the v2 envelope has no equivalent
 * for; at the write flip anything living only there disappears. This checks the actual outcome
 * of that: for one set of inputs, every fact present in the v1 map is present in whichever v2
 * home the table names, and every fact the table calls absent really is.
 *
 * <h2>Why "the table is total" is not enough on its own</h2>
 *
 * <p>{@code CaptureEvidenceFieldTest} checks the table's shape. It cannot catch a builder that
 * reads the table for the v1 key and then forgets to write the v2 attribute — the field is still
 * in the table, so a membership check passes. Only running both builders over the same input and
 * comparing what came out separates "absent because the condition was false" from "absent
 * because the code forgot" (external review).
 */
class IngestEvidenceCorrespondenceTest {

    private static IngestLineageEmitter emitter() {
        return new IngestLineageEmitter();
    }

    private static ConnectorDefinition connector() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("c1");
        c.setSourceSystem("acme-chat");
        c.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);
        return c;
    }

    /** A chat message import with every optional fact supplied, so nothing is absent by input. */
    private static ExternalIngestRequest fullRequest() {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setRepositoryId("bedroom");
        req.setProfileId("p1");
        req.setConnectorId("c1");
        req.setSourceObjectId("1720000000.000200");
        req.setSourceObjectType("message");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("workspaceId", "W1");
        meta.put("channelId", "C1");
        meta.put("channelName", "incident-2026");
        meta.put("threadId", "T1");
        meta.put("messageId", "M1");
        meta.put("participants", "alice,bob");
        meta.put("selectionReason", "named in the audit request");
        meta.put("evidenceScope", "thread");
        meta.put("captureWindowStart", "2026-08-01T00:00:00Z");
        meta.put("captureWindowEnd", "2026-08-02T00:00:00Z");
        req.setMetadata(meta);
        return req;
    }

    /** Where a fact ended up in the v2 encoding, for one set of inputs. */
    private record V2Encoding(Map<String, Object> inputAttributes,
                              Map<String, Object> outputAttributes,
                              String stableKey) {

        boolean carries(CaptureEvidenceField field, String expectedValue) {
            V2Home home = field.v2Home();
            return switch (home.placement()) {
                case INPUT_ATTRIBUTE -> expectedValue.equals(
                        String.valueOf(inputAttributes.get(home.attributeName())));
                case OUTPUT_ATTRIBUTE -> expectedValue.equals(
                        String.valueOf(outputAttributes.get(home.attributeName())));
                // The fact is inside the endpoint's name rather than beside it.
                case IDENTITY -> stableKey.contains(expectedValue);
                case NONE -> false;
            };
        }
    }

    private static V2Encoding encode(ConnectorDefinition connector, ExternalIngestRequest request,
                                     CapturedContent content) {
        String sourceUri = IngestLineageEmitter.buildCanonicalSourceUri(connector, request);
        LineageEndpoint input = emitter().buildInputEndpoint("bedroom", sourceUri, connector,
                request);
        LineageEndpoint output = emitter().buildOutputEndpoint("bedroom", "obj-1", "message.txt",
                content);
        return new V2Encoding(input.attributes(), output.attributes(),
                String.valueOf(input.attributes().get("externalStableKey")));
    }

    // ── The correspondence itself ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("every fact in the v1 snapshot is carried by v2, or the table says why not")
    void everyV1FactIsAccountedFor() {
        ConnectorDefinition connector = connector();
        ExternalIngestRequest request = fullRequest();
        CapturedContent content = CapturedContent.hashed("a".repeat(64));

        Map<String, String> v1 = emitter().buildV1Snapshot(connector, request, "folder-1",
                content, "admin", "alice");
        V2Encoding v2 = encode(connector, request, content);

        List<String> lost = new ArrayList<>();
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            String v1Value = v1.get(field.v1Key());
            if (v1Value == null || v1Value.isBlank()) {
                continue;   // absent by input, not by omission
            }
            if (!field.v2Home().carriedByV2()) {
                continue;   // declared as not carried; the table's reason says why
            }
            if (!v2.carries(field, v1Value)) {
                lost.add(field + " (v1='" + v1Value + "', v2 home=" + field.v2Home().placement()
                        + " " + field.v2Home().attributeName() + ")");
            }
        }

        assertTrue(lost.isEmpty(),
                "these facts are in the v1 snapshot and the table says v2 carries them, but the "
                        + "v2 builders did not produce them — at the write flip they would be "
                        + "lost: " + lost);
    }

    @Test
    @DisplayName("the v1 snapshot produces exactly the table's keys — nothing extra, nothing missing")
    void theV1SnapshotIsExactlyTheTable() {
        // Quantifying over the TABLE cannot see a key the builder puts directly: the loop never
        // visits it. Quantifying over the OUTPUT can. Without this, adding a fact to
        // buildV1Snapshot with a literal key would leave it outside the correspondence entirely
        // — which is the failure mode the table exists to close (external review).
        // Two inputs, unioned: contentHash and contentHashUnavailable are mutually exclusive by
        // construction — you get the digest or the reason it is missing, never both — so no
        // single input can produce every key.
        java.util.Set<String> produced = new java.util.TreeSet<>();
        produced.addAll(emitter().buildV1Snapshot(connector(), fullRequest(), "folder-1",
                CapturedContent.hashed("a".repeat(64)), "admin", "alice").keySet());
        produced.addAll(emitter().buildV1Snapshot(connector(), fullRequest(), "folder-1",
                CapturedContent.unknown("content state undetermined"), "admin", "alice").keySet());
        // A third input: the re-import facts describe what a PASS decided, so no request can
        // supply them. Unioned rather than excluded, so they stay inside the correspondence —
        // an excluded key is one nothing checks (P1-1(d) D6).
        produced.addAll(emitter().buildV1Snapshot(connector(), fullRequest(), "folder-1",
                CapturedContent.none(), "admin", "alice",
                java.util.Map.of(CaptureEvidenceField.REIMPORT_OUTCOME, "stored nothing",
                        CaptureEvidenceField.REIMPORT_FILLED, "nemaki:chatChannelName",
                        CaptureEvidenceField.REIMPORT_REFUSED, "nemaki:chatChannelId")).keySet());

        java.util.Set<String> tableKeys = new java.util.TreeSet<>();
        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            tableKeys.add(field.v1Key());
        }

        java.util.Set<String> unknown = new java.util.TreeSet<>(produced);
        unknown.removeAll(tableKeys);
        assertTrue(unknown.isEmpty(),
                "the snapshot produced keys the evidence table does not know about, so nothing "
                        + "checks whether v2 carries them: " + unknown);

        java.util.Set<String> missing = new java.util.TreeSet<>(tableKeys);
        missing.removeAll(produced);
        assertTrue(missing.isEmpty(),
                "the table declares facts the snapshot did not produce for an input that supplies "
                        + "all of them, so the table is describing something the code does not do: "
                        + missing);
    }

    @Test
    @DisplayName("a fact the table calls absent is really absent from v2")
    void declaredAbsencesAreAbsent() {
        // The other direction. Without it, quietly adding an attribute would leave the table
        // saying "not carried" while the code carried it — and the design, the roadmap and the
        // reason text would all be describing something that is no longer true.
        ConnectorDefinition connector = connector();
        ExternalIngestRequest request = fullRequest();
        CapturedContent content = CapturedContent.hashed("a".repeat(64));
        V2Encoding v2 = encode(connector, request, content);

        for (CaptureEvidenceField field : CaptureEvidenceField.values()) {
            if (field.v2Home().carriedByV2()) {
                continue;
            }
            assertFalse(v2.inputAttributes().containsKey(field.v1Key())
                            || v2.outputAttributes().containsKey(field.v1Key()),
                    field + " is declared as not carried by v2, but an attribute of that name "
                            + "was produced");
        }
    }

    @Test
    @DisplayName("the personal-name facts never reach an endpoint attribute")
    void personalNamesStayOutOfTheCatalogue() {
        // An endpoint attribute is declared on the delivery type and therefore stored in the
        // catalogue, which has no retention rule of its own — unlike the journal, which purges.
        ConnectorDefinition connector = connector();
        ExternalIngestRequest request = fullRequest();
        V2Encoding v2 = encode(connector, request, CapturedContent.none());

        String allAttributes = v2.inputAttributes() + " " + v2.outputAttributes();
        assertFalse(allAttributes.contains("alice,bob"),
                "chat participants reached an endpoint attribute: " + allAttributes);
        assertFalse(allAttributes.contains("incident-2026"),
                "the channel name reached an endpoint attribute; in a direct message it is the "
                        + "other person's name: " + allAttributes);
    }

    @Test
    @DisplayName("this ingest's judgement never reaches an endpoint attribute")
    void judgementStaysOutOfTheEndpoint() {
        // The endpoint's qualified name repeats on re-ingest and the delivery target upserts, so
        // a second ingest would silently overwrite the first one's judgement.
        ConnectorDefinition connector = connector();
        V2Encoding v2 = encode(connector, fullRequest(), CapturedContent.none());

        String allAttributes = v2.inputAttributes() + " " + v2.outputAttributes();
        assertFalse(allAttributes.contains("named in the audit request"), allAttributes);
        assertFalse(allAttributes.contains("2026-08-01T00:00:00Z"), allAttributes);
    }

    // ── The three-state ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an undetermined content state reaches v2 as 'unknown', not as an absent hash")
    void undeterminedContentIsCarriedAsSuch() {
        // Inferring the state from whether a digest is present cannot express this third value,
        // and reading an absent digest as "nothing stored" is wrong: a check-in with no stream
        // carries the previous version's content forward.
        V2Encoding v2 = encode(connector(), fullRequest(),
                CapturedContent.unknown("the object references content from an earlier import"));

        assertEquals("unknown", v2.outputAttributes().get("contentStored"));
        assertFalse(v2.outputAttributes().containsKey("contentHash"));
        assertEquals("the object references content from an earlier import",
                v2.outputAttributes().get("contentStateReason"));
    }

    @Test
    @DisplayName("stored content carries the digest and its algorithm")
    void storedContentCarriesTheDigest() {
        V2Encoding v2 = encode(connector(), fullRequest(),
                CapturedContent.hashed("b".repeat(64)));

        assertEquals("true", v2.outputAttributes().get("contentStored"));
        assertEquals("b".repeat(64), v2.outputAttributes().get("contentHash"));
        assertEquals("SHA-256", v2.outputAttributes().get("contentHashAlgorithm"));
    }

    @Test
    @DisplayName("no content is 'false', which is not the same as undetermined")
    void noContentIsFalse() {
        V2Encoding v2 = encode(connector(), fullRequest(), CapturedContent.none());

        assertEquals("false", v2.outputAttributes().get("contentStored"));
        assertFalse(v2.outputAttributes().containsKey("contentHash"));
    }

    // ── Identity is not repeated ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the channel is in the stable key and is not repeated; the others are attributes")
    void identityIsNotRepeatedAndNonIdentityIsCarried() {
        // Corrected after a correspondence failure. Three chat ids LOOK as though the source URI
        // carries them; only the channel actually does. The key's tenant segment comes from
        // connector.getTenantId(), not from the request's workspaceId, and it is omitted
        // entirely when the connector has no tenant — so the key here is
        // acme-chat://channels/C1/messages/... with no workspace in it at all (external review).
        V2Encoding v2 = encode(connector(), fullRequest(), CapturedContent.none());

        assertTrue(v2.stableKey().contains("C1"), v2.stableKey());
        assertFalse(v2.stableKey().contains("W1"),
                "the workspace is NOT in the key — this is the fact the table now records: "
                        + v2.stableKey());
        assertFalse(v2.inputAttributes().containsKey("chatChannelId"),
                "the channel IS in the key, so repeating it would give two places to disagree: "
                        + v2.inputAttributes());
        assertEquals("W1", v2.inputAttributes().get("chatWorkspaceId"));
        assertEquals("M1", v2.inputAttributes().get("chatMessageId"));
        assertEquals("T1", v2.inputAttributes().get("chatThreadId"));
    }

    @Test
    @DisplayName("an attachment's parent message survives, which is what links it to the message")
    void attachmentKeepsItsParentMessage() {
        // The case that decided the classification. An attachment's URI is
        // channels/{channelId}/files/{fileId} — the id in it is the FILE's — so metadata.messageId
        // is the only thing tying the attachment back to the message it came from.
        ExternalIngestRequest attachment = fullRequest();
        attachment.setSourceObjectId("F-9");
        attachment.setSourceObjectType("attachment");

        V2Encoding v2 = encode(connector(), attachment, CapturedContent.none());

        assertTrue(v2.stableKey().contains("F-9"), v2.stableKey());
        assertFalse(v2.stableKey().contains("M1"),
                "the parent message is not in an attachment's key: " + v2.stableKey());
        assertEquals("M1", v2.inputAttributes().get("chatMessageId"),
                "so it has to travel as an attribute, or the link is lost at the write flip");
    }
}
