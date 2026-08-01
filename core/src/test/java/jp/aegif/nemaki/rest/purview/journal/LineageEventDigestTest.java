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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link LineageEventDigest} — the digest that answers "same record?" after the id said
 * "same process".
 */
public class LineageEventDigestTest {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";

    private static LineageEndpoint doc(String id, String name) {
        return LineageEndpoint.document(REPO, id, name);
    }

    private static String digestOf(List<LineageEndpoint> inputs, List<LineageEndpoint> outputs,
                                   String operationId, String occurredAt) {
        String processKey = LineageIdentity.processKey(REPO, LineageProcessType.ARCHIVE_LOCAL,
                operationId, inputs, outputs, 2, 0, 1);
        String deliveryId = LineageIdentity.originalDeliveryId(processKey, List.of("purview"));
        return LineageEventDigest.creationPayloadDigest(processKey, deliveryId, 2, REPO,
                LineageProcessType.ARCHIVE_LOCAL, operationId, occurredAt, inputs, outputs, 0, 1);
    }

    private static List<LineageEndpoint> archiveOut() {
        return List.of(LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1_700_000_000_000L));
    }

    // ------------------------------------------------------------------ the central property

    /**
     * The reason a second hash exists. A renamed document is the same document — same process — but
     * a different record, and only the digest may notice.
     */
    @Test
    public void anAttributeChangeMovesTheDigestButNotTheProcessKey() {
        List<LineageEndpoint> before = List.of(doc("doc-1", "before.txt"));
        List<LineageEndpoint> after = List.of(doc("doc-1", "after.txt"));

        assertEquals(
                LineageIdentity.processKey(REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1",
                        before, archiveOut(), 2, 0, 1),
                LineageIdentity.processKey(REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1",
                        after, archiveOut(), 2, 0, 1),
                "renaming a document must not create a second Process");
        assertNotEquals(
                digestOf(before, archiveOut(), "op-1", OCCURRED),
                digestOf(after, archiveOut(), "op-1", OCCURRED),
                "a changed attribute under an unchanged id is the collision the digest is for");
    }

    /**
     * §3 v2.3.12: {@code occurredAt} is in the digest and not in {@code processKey}, so re-deriving
     * it is detected at the journal rather than silently producing a second record. This is the
     * runtime half of the "allocate occurredAt once" contract.
     */
    @Test
    public void reDerivingOccurredAtMovesTheDigestUnderTheSameDeliveryId() {
        List<LineageEndpoint> inputs = List.of(doc("doc-1", "a.txt"));
        String processKey = LineageIdentity.processKey(REPO, LineageProcessType.ARCHIVE_LOCAL,
                "op-1", inputs, archiveOut(), 2, 0, 1);

        assertEquals(processKey, LineageIdentity.processKey(REPO, LineageProcessType.ARCHIVE_LOCAL,
                        "op-1", inputs, archiveOut(), 2, 0, 1),
                "occurredAt is not part of the process identity");
        assertNotEquals(
                digestOf(inputs, archiveOut(), "op-1", "2026-08-01T00:00:00Z"),
                digestOf(inputs, archiveOut(), "op-1", "2026-08-01T00:00:01Z"),
                "one second later must be a different record, or the contract is unenforceable");
    }

    @Test
    public void theSameInputsProduceTheSameDigest() {
        assertEquals(digestOf(List.of(doc("doc-1", "a.txt")), archiveOut(), "op-1", OCCURRED),
                digestOf(List.of(doc("doc-1", "a.txt")), archiveOut(), "op-1", OCCURRED));
    }

    @Test
    public void theDigestIsSixtyFourLowercaseHexCharacters() {
        String digest = digestOf(List.of(doc("doc-1", "a.txt")), archiveOut(), "op-1", OCCURRED);
        assertEquals(64, digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"), digest);
    }

    @Test
    public void everyDigestedFieldActuallyMovesTheDigest() {
        String base = digestOf(List.of(doc("doc-1", "a.txt")), archiveOut(), "op-1", OCCURRED);
        assertNotEquals(base,
                digestOf(List.of(doc("doc-2", "a.txt")), archiveOut(), "op-1", OCCURRED),
                "a different input endpoint");
        assertNotEquals(base, digestOf(List.of(doc("doc-1", "a.txt")),
                        List.of(LineageEndpoint.archive(REPO, "arc-2", "doc-1", 1_700_000_000_000L)),
                        "op-1", OCCURRED),
                "a different output endpoint");
        assertNotEquals(base,
                digestOf(List.of(doc("doc-1", "a.txt")), archiveOut(), "op-2", OCCURRED),
                "a different operation");
    }

    /** Chunk coordinates are in the digest as well as the process key. */
    @Test
    public void chunkCoordinatesMoveTheDigest() {
        List<LineageEndpoint> inputs = List.of(doc("doc-1", "a.txt"));
        String pk = LineageIdentity.processKey(REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1",
                inputs, archiveOut(), 2, 0, 2);
        String did = LineageIdentity.originalDeliveryId(pk, List.of("purview"));
        assertNotEquals(
                LineageEventDigest.creationPayloadDigest(pk, did, 2, REPO,
                        LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED, inputs, archiveOut(),
                        0, 2),
                LineageEventDigest.creationPayloadDigest(pk, did, 2, REPO,
                        LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED, inputs, archiveOut(),
                        1, 2));
    }

    // ------------------------------------------------------------------ endpointRecords

    @Test
    public void endpointRecordsCarryEveryFieldOfTheEndpoint() {
        LineageEndpoint endpoint = doc("doc-1", "a.txt");
        Map<String, Object> record = LineageEventDigest.endpointRecords(List.of(endpoint)).get(0);
        assertEquals("CMIS_DOCUMENT", record.get("kind"));
        assertEquals(REPO, record.get("repositoryId"));
        assertEquals(endpoint.catalogQualifiedName(), record.get("catalogQualifiedName"));
        assertEquals("doc-1", record.get("objectId"));
        assertEquals(null, record.get("operationId"));
        assertEquals(Map.of("name", "a.txt"), record.get("attributes"));
        assertEquals(6, record.size(), "a field added to LineageEndpoint has to be decided about"
                + " here: in the digest, or deliberately out of it");
    }

    /** Order comes from A-1's canonicalisation, not from the caller and not from a second sort. */
    @Test
    public void endpointRecordsFollowTheCanonicalOrder() {
        LineageEndpoint a = doc("doc-a", "a.txt");
        LineageEndpoint b = doc("doc-b", "b.txt");
        List<String> canonical = LineageCanonicalHash.canonicalQualifiedNames(List.of(b, a));

        List<Map<String, Object>> records = LineageEventDigest.endpointRecords(List.of(b, a));
        assertEquals(canonical.get(0), records.get(0).get("catalogQualifiedName"));
        assertEquals(canonical.get(1), records.get(1).get("catalogQualifiedName"));
    }

    @Test
    public void theOrderEndpointsWereGivenInDoesNotMoveTheDigest() {
        List<LineageEndpoint> forwards = List.of(doc("doc-a", "a.txt"), doc("doc-b", "b.txt"));
        List<LineageEndpoint> backwards = List.of(doc("doc-b", "b.txt"), doc("doc-a", "a.txt"));
        assertEquals(LineageEventDigest.endpointRecords(forwards),
                LineageEventDigest.endpointRecords(backwards));
    }

    /** Delegated to A-1; asserted here so that a future local sort could not quietly allow it. */
    @Test
    public void duplicateEndpointsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageEventDigest.endpointRecords(
                        List.of(doc("doc-1", "a.txt"), doc("doc-1", "a.txt"))));
    }

    /**
     * A null list is a caller bug and an empty one is a fact. A-1 already draws that line; the
     * digest inherits it rather than softening it, because "no inputs" and "the mapping produced
     * nothing" must not hash the same.
     */
    @Test
    public void anEmptyEndpointListIsEmptyRecordsAndANullOneIsAnError() {
        assertEquals(List.of(), LineageEventDigest.endpointRecords(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LineageEventDigest.endpointRecords(null));
    }

    /**
     * The identity fields are null for the kinds not identified by them, and the canonical hash
     * gives null its own tag — so an absent objectId is a different record from an empty one.
     */
    @Test
    public void anAbsentIdentityFieldIsRecordedAsNullNotAsEmpty() {
        Map<String, Object> artifact = LineageEventDigest.endpointRecords(
                List.of(LineageEndpoint.importArtifact(REPO, "op-1", "zip-upload", Map.of())))
                .get(0);
        assertEquals(null, artifact.get("objectId"));
        assertEquals("op-1", artifact.get("operationId"));

        Map<String, Object> withNull = new LinkedHashMap<>(artifact);
        Map<String, Object> withEmpty = new LinkedHashMap<>(artifact);
        withEmpty.put("objectId", "");
        assertNotEquals(LineageCanonicalHash.hash(withNull), LineageCanonicalHash.hash(withEmpty));
    }

    /** A COUNT attribute is a number, and a number is not its own decimal spelling. */
    @Test
    public void aCountAttributeStaysANumber() {
        Map<String, Object> record = LineageEventDigest.endpointRecords(
                List.of(LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1_700_000_000_000L)))
                .get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) record.get("attributes");
        assertEquals(1_700_000_000_000L, attributes.get("archivedAt"));
        assertNotEquals("1700000000000", attributes.get("archivedAt"));
    }

    // ------------------------------------------------------------------ domain separation

    /**
     * The digest must not be reachable by feeding the same values to the plain hash: a caller who
     * could reproduce it without this method could forge a match after changing the payload.
     */
    @Test
    public void theDigestIsDomainSeparated() {
        List<LineageEndpoint> inputs = List.of(doc("doc-1", "a.txt"));
        String processKey = LineageIdentity.processKey(REPO, LineageProcessType.ARCHIVE_LOCAL,
                "op-1", inputs, archiveOut(), 2, 0, 1);
        String deliveryId = LineageIdentity.originalDeliveryId(processKey, List.of("purview"));

        String digest = LineageEventDigest.creationPayloadDigest(processKey, deliveryId, 2, REPO,
                LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED, inputs, archiveOut(), 0, 1);
        String undomained = LineageCanonicalHash.hash(processKey, deliveryId, 2L, REPO,
                "ARCHIVE_LOCAL", "op-1", OCCURRED,
                LineageEventDigest.endpointRecords(inputs),
                LineageEventDigest.endpointRecords(archiveOut()), 0L, 1L);
        assertNotEquals(undomained, digest);
    }

    @Test
    public void aNullProcessTypeIsEncodedRatherThanThrowing() {
        // The record rejects it; the digest itself must still be total, because it is also called
        // from the spool path where the value comes off disk.
        assertEquals(64, LineageEventDigest.creationPayloadDigest("pk", "did", 2, REPO, null,
                "op-1", OCCURRED, List.of(), List.of(), 0, 1).length());
    }
}
