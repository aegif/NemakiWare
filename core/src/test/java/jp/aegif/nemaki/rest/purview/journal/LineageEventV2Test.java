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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link LineageEventV2} and {@link LineageEventV2Builder}.
 *
 * <p>Two things are being pinned. First, that the record recomputes its own identities instead of
 * believing the caller — an event whose id does not describe its contents must not construct.
 * Second, that {@code build()} is a pure function, because in v2 a retry that re-derives
 * {@code occurredAt} produces the same {@code deliveryId} with a different digest, which the
 * journal can only report as an id collision.
 */
public class LineageEventV2Test {

    private static final String REPO = "bedroom";
    private static final String OCCURRED = "2026-08-01T00:00:00Z";
    private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";

    private static LineageEndpoint doc() {
        return LineageEndpoint.document(REPO, "doc-1", "a.txt");
    }

    private static LineageEndpoint archive() {
        return LineageEndpoint.archive(REPO, "arc-1", "doc-1", 1_700_000_000_000L);
    }

    private static LineageEventV2Builder builder() {
        return new LineageEventV2Builder()
                .eventId(EVENT_ID)
                .occurredAt(OCCURRED)
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview", "atlas")))
                .addInput(doc())
                .addOutput(archive());
    }

    // ------------------------------------------------------------------ builder purity

    /**
     * The §3 v2.3.12 contract. If this fails, every retry path in §6-a is unsound.
     */
    @Test
    public void buildingTwiceFromTheSameInputsProducesEqualEvents() {
        LineageEventV2Builder builder = builder();
        assertEquals(builder.build(), builder.build());
    }

    @Test
    public void twoSeparateBuildersWithTheSameInputsAgreeOnEveryIdentity() {
        LineageEventV2 first = builder().build();
        LineageEventV2 second = builder().build();
        assertEquals(first.processKey(), second.processKey());
        assertEquals(first.deliveryId(), second.deliveryId());
        assertEquals(first.creationPayloadDigest(), second.creationPayloadDigest());
        assertEquals(first, second);
    }

    @Test
    public void buildReadsNoClock() {
        assertThrows(IllegalArgumentException.class,
                () -> builder().occurredAt(null).build());
        assertThrows(IllegalArgumentException.class,
                () -> builder().occurredAt("   ").build());
    }

    /** Generating the id inside build() is the same impurity as reading the clock there. */
    @Test
    public void buildGeneratesNoIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> builder().eventId(null).build());
        assertThrows(IllegalArgumentException.class, () -> builder().eventId("").build());
    }

    @Test
    public void aDeliveryIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> builder().delivery(null).build());
    }

    /** Contrast with the v1 builder, which is where the hazard came from. */
    @Test
    public void theV1BuilderIsTheOneThatReadsTheClock() {
        LineageEventBuilder v1 = new LineageEventBuilder()
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(REPO, "doc-1");
        assertNotEquals(v1.build().occurredAt(), "",
                "v1 fills occurredAt in build(); v2 must not, and this records the difference");
    }

    // ------------------------------------------------------------------ identity is recomputed

    @Test
    public void aProcessKeyThatDoesNotDescribeTheEventIsRejected() {
        LineageEventV2 good = builder().build();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new LineageEventV2(2, LineageIdentity.IDEMPOTENCY_KEY_VERSION, EVENT_ID,
                        "v2:0000000000000000000000000000000000000000000000000000000000000000",
                        good.delivery(), good.deliveryId(), REPO,
                        LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED,
                        good.inputs(), good.outputs(), 0, 1, 0L, null, null, null,
                        good.publishStatusByTarget(), good.creationPayloadDigest()));
        assertTrue(thrown.getMessage().contains("processKey"), thrown.getMessage());
    }

    @Test
    public void aDeliveryIdThatDoesNotDescribeTheDeliveryIsRejected() {
        LineageEventV2 good = builder().build();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new LineageEventV2(2, LineageIdentity.IDEMPOTENCY_KEY_VERSION, EVENT_ID,
                        good.processKey(), good.delivery(), "not-the-delivery-id", REPO,
                        LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED,
                        good.inputs(), good.outputs(), 0, 1, 0L, null, null, null,
                        good.publishStatusByTarget(), good.creationPayloadDigest()));
        assertTrue(thrown.getMessage().contains("deliveryId"), thrown.getMessage());
    }

    /** Swapping the delivery moves the id, so the stored id no longer matches. */
    @Test
    public void changingTheDeliveryWithoutChangingTheIdIsRejected() {
        LineageEventV2 good = builder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new LineageEventV2(2, LineageIdentity.IDEMPOTENCY_KEY_VERSION, EVENT_ID,
                        good.processKey(), new LineageDelivery.Repair("dl-1", 1L),
                        good.deliveryId(), REPO, LineageProcessType.ARCHIVE_LOCAL, "op-1",
                        OCCURRED, good.inputs(), good.outputs(), 0, 1, 0L, null, null, null,
                        good.publishStatusByTarget(), good.creationPayloadDigest()));
    }

    @Test
    public void aDigestThatDoesNotMatchThePayloadIsRejected() {
        LineageEventV2 good = builder().build();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new LineageEventV2(2, LineageIdentity.IDEMPOTENCY_KEY_VERSION, EVENT_ID,
                        good.processKey(), good.delivery(), good.deliveryId(), REPO,
                        LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED,
                        good.inputs(), good.outputs(), 0, 1, 0L, null, null, null,
                        good.publishStatusByTarget(),
                        "0000000000000000000000000000000000000000000000000000000000000000"));
        assertTrue(thrown.getMessage().contains("creationPayloadDigest"), thrown.getMessage());
    }

    // ------------------------------------------------------------------ A-1's checks are invoked

    @Test
    public void crossRepositoryEndpointsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> builder()
                        .addOutput(LineageEndpoint.archive("canopy", "arc-2", "doc-1", 1L))
                        .build());
    }

    @Test
    public void anArtifactEndpointFromAnotherOperationIsRejected() {
        LineageEventV2Builder builder = new LineageEventV2Builder()
                .eventId(EVENT_ID).occurredAt(OCCURRED).repositoryId(REPO)
                .processType(LineageProcessType.IMPORT_UPLOADED)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.importArtifact(REPO, "op-OTHER", "zip-upload", Map.of()))
                .addOutput(LineageEndpoint.folder(REPO, "f1", "Inbox"));
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(thrown.getMessage().contains("op-OTHER"), thrown.getMessage());
    }

    @Test
    public void anEventWithoutAnOperationIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> builder().operationId(null).build());
        assertThrows(IllegalArgumentException.class, () -> builder().operationId("  ").build());
    }

    @Test
    public void endpointsThatDoNotFormAnAcceptedShapeAreRejected() {
        LineageEventV2Builder wrongWayRound = new LineageEventV2Builder()
                .eventId(EVENT_ID).occurredAt(OCCURRED).repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-1")
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(archive())
                .addOutput(doc());
        assertThrows(IllegalArgumentException.class, wrongWayRound::build);
    }

    @Test
    public void duplicateEndpointsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> builder().addInput(doc()).build());
    }

    // ------------------------------------------------------------------ targets

    @Test
    public void anOriginalOwesEveryTargetItNames() {
        LineageEventV2 event = builder().build();
        assertEquals(Map.of("atlas", LineagePublishStatus.PENDING,
                        "purview", LineagePublishStatus.PENDING),
                event.publishStatusByTarget());
    }

    /**
     * The reason targets are derived rather than supplied. A replay built with the full target set
     * would re-publish targets that already succeeded — the failure §8-d's per-target compensation
     * exists to avoid.
     */
    @Test
    public void aReplayOwesOnlyItsOwnTarget() {
        LineageEventV2 original = builder().build();
        LineageEventV2 replay = builder()
                .delivery(new LineageDelivery.Replay(original.deliveryId(), "purview", 1L))
                .build();
        assertEquals(Map.of("purview", LineagePublishStatus.PENDING),
                replay.publishStatusByTarget());
        assertEquals(original.processKey(), replay.processKey(),
                "a compensation keeps the process it compensates for");
        assertNotEquals(original.deliveryId(), replay.deliveryId());
    }

    @Test
    public void aRepairCannotGuessWhichTargetsTheDeadLetterOwed() {
        LineageEventV2Builder builder = builder().delivery(new LineageDelivery.Repair("dl-1", 1L));
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(thrown.getMessage().contains("repairTargets"), thrown.getMessage());

        LineageEventV2 repair = builder.repairTargets(List.of("purview")).build();
        assertEquals(Map.of("purview", LineagePublishStatus.PENDING),
                repair.publishStatusByTarget());
    }

    /** The status map's keys have to be the canonical target set the deliveryId was built from. */
    @Test
    public void targetsAreCanonicalisedTheSameWayTheIdIsBuilt() {
        LineageEventV2 event = builder()
                .delivery(new LineageDelivery.Original(List.of(" purview ", "atlas", "purview")))
                .build();
        assertEquals(Map.of("atlas", LineagePublishStatus.PENDING,
                        "purview", LineagePublishStatus.PENDING),
                event.publishStatusByTarget());
        assertEquals(builder().build().deliveryId(), event.deliveryId());
    }

    // ------------------------------------------------------------------ audit-only fields

    /**
     * §3 v2.3.12: {@code spoolRecordId} is excluded from the digest, because the same fact can be
     * emitted directly or materialised from a spool record under one {@code deliveryId}, and
     * including it would make that pair look like a collision.
     */
    @Test
    public void theSpoolRecordIdIsCarriedButNotDigested() {
        LineageEventV2 direct = builder().build();
        LineageEventV2 materialised = builder().spoolRecordId("a".repeat(64)).build();

        assertEquals(direct.deliveryId(), materialised.deliveryId());
        assertEquals(direct.creationPayloadDigest(), materialised.creationPayloadDigest());
        assertFalse(direct.isMaterialisedFromSpool());
        assertTrue(materialised.isMaterialisedFromSpool());
    }

    @Test
    public void theLegacyEventKeyIsCarriedButNotDigested() {
        assertEquals(builder().build().creationPayloadDigest(),
                builder().legacyEventKey("bedroom:ARCHIVE_LOCAL:1:2").build()
                        .creationPayloadDigest());
    }

    @Test
    public void blankAuditFieldsBecomeAbsent() {
        LineageEventV2 event = builder()
                .spoolRecordId("  ").legacyEventKey("").correlationId(" ").build();
        assertNull(event.spoolRecordId());
        assertNull(event.legacyEventKey());
        assertNull(event.correlationId());
    }

    @Test
    public void theSequenceNumberIsNotPartOfTheDigest() {
        assertEquals(builder().build().creationPayloadDigest(),
                builder().sequenceNumber(4_200L).build().creationPayloadDigest());
    }

    // ------------------------------------------------------------------ envelope guards

    @Test
    public void onlyVersionTwoConstructs() {
        LineageEventV2 good = builder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new LineageEventV2(1, LineageIdentity.IDEMPOTENCY_KEY_VERSION, EVENT_ID,
                        good.processKey(), good.delivery(), good.deliveryId(), REPO,
                        LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED, good.inputs(),
                        good.outputs(), 0, 1, 0L, null, null, null,
                        good.publishStatusByTarget(), good.creationPayloadDigest()));
    }

    @Test
    public void anUnknownIdempotencyKeyVersionIsRejected() {
        LineageEventV2 good = builder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new LineageEventV2(2, LineageIdentity.IDEMPOTENCY_KEY_VERSION + 1, EVENT_ID,
                        good.processKey(), good.delivery(), good.deliveryId(), REPO,
                        LineageProcessType.ARCHIVE_LOCAL, "op-1", OCCURRED, good.inputs(),
                        good.outputs(), 0, 1, 0L, null, null, null,
                        good.publishStatusByTarget(), good.creationPayloadDigest()));
    }

    @Test
    public void aNegativeSequenceNumberIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> builder().sequenceNumber(-1L).build());
    }

    @Test
    public void chunkCoordinatesAreRangeChecked() {
        assertThrows(IllegalArgumentException.class, () -> builder().chunk(2, 2).build());
        assertThrows(IllegalArgumentException.class, () -> builder().chunk(-1, 2).build());
        assertThrows(IllegalArgumentException.class, () -> builder().chunk(0, 0).build());
    }

    @Test
    public void aChunkIsItsOwnProcess() {
        assertNotEquals(builder().chunk(0, 2).build().processKey(),
                builder().chunk(1, 2).build().processKey());
    }

    @Test
    public void collectionsAreDefensivelyCopied() {
        LineageEventV2 event = builder().build();
        assertThrows(UnsupportedOperationException.class, () -> event.inputs().add(doc()));
        assertThrows(UnsupportedOperationException.class,
                () -> event.publishStatusByTarget().put("x", LineagePublishStatus.PENDING));
    }
}
