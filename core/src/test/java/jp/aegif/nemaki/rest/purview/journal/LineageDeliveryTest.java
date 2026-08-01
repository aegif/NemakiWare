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

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link LineageDelivery} — the tagged union that says why a journal record exists.
 *
 * <p>What is worth testing here is not the hash (that is {@link LineageIdentityTest}'s and the
 * golden vectors'), but that each variant can only be built with the fields its kind is identified
 * by, and that it delegates to the frozen formula rather than composing its own.
 */
public class LineageDeliveryTest {

    private static final String PROCESS_KEY =
            LineageIdentity.processKey("bedroom", LineageProcessType.ARCHIVE_LOCAL, "op-1",
                    List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                    List.of(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L)),
                    2, 0, 1);

    // ------------------------------------------------------------------ ORIGINAL

    @Test
    public void originalDelegatesToTheFrozenFormula() {
        LineageDelivery delivery = new LineageDelivery.Original(List.of("purview", "atlas"));
        assertEquals(DeliveryKind.ORIGINAL, delivery.kind());
        assertEquals(LineageIdentity.originalDeliveryId(PROCESS_KEY, List.of("purview", "atlas")),
                delivery.deliveryId(PROCESS_KEY));
    }

    /** Target order is the caller's convenience, not part of the identity. */
    @Test
    public void originalIsIndifferentToTheOrderTargetsWereGivenIn() {
        assertEquals(
                new LineageDelivery.Original(List.of("atlas", "purview")).deliveryId(PROCESS_KEY),
                new LineageDelivery.Original(List.of("purview", "atlas")).deliveryId(PROCESS_KEY));
    }

    @Test
    public void originalRejectsANullTargetList() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageDelivery.Original(null));
    }

    /**
     * An ORIGINAL is the only kind whose id depends on the process, so it is the only one where
     * passing a different process key may produce a different id.
     */
    @Test
    public void originalIsScopedToItsProcess() {
        LineageDelivery delivery = new LineageDelivery.Original(List.of("purview"));
        String otherProcess = LineageIdentity.processKey("bedroom",
                LineageProcessType.ARCHIVE_LOCAL, "op-2",
                List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                List.of(LineageEndpoint.archive("bedroom", "arc-1", "doc-1", 1L)),
                2, 0, 1);
        assertNotEquals(delivery.deliveryId(PROCESS_KEY), delivery.deliveryId(otherProcess));
    }

    // ------------------------------------------------------------------ REPLAY

    @Test
    public void replayDelegatesToTheFrozenFormula() {
        LineageDelivery delivery = new LineageDelivery.Replay("delivery-1", "purview", 3L);
        assertEquals(DeliveryKind.REPLAY, delivery.kind());
        assertEquals(LineageIdentity.replayDeliveryId("delivery-1", "purview", 3L),
                delivery.deliveryId(PROCESS_KEY));
    }

    /**
     * A replay is identified by the delivery it replays, which already implies a process. Feeding
     * a different process key must not move it, or a replay reconstructed by a repair tool from
     * partial information would land on a second id.
     */
    @Test
    public void replayIgnoresTheProcessKey() {
        LineageDelivery delivery = new LineageDelivery.Replay("delivery-1", "purview", 3L);
        assertEquals(delivery.deliveryId(PROCESS_KEY), delivery.deliveryId("v2:something-else"));
        assertEquals(delivery.deliveryId(PROCESS_KEY), delivery.deliveryId(null));
    }

    @Test
    public void replayRequiresTheDeliveryItReplays() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageDelivery.Replay(null, "purview", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageDelivery.Replay("  ", "purview", 1L));
    }

    @Test
    public void replayRequiresATarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageDelivery.Replay("delivery-1", null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageDelivery.Replay("delivery-1", "", 1L));
    }

    /** Generation 0 is the original, and there is no negative replay. */
    @Test
    public void replayGenerationStartsAtOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new LineageDelivery.Replay("delivery-1", "purview", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new LineageDelivery.Replay("delivery-1", "purview", -1L));
    }

    @Test
    public void replayGenerationsAreDistinctDeliveries() {
        assertNotEquals(
                new LineageDelivery.Replay("delivery-1", "purview", 1L).deliveryId(PROCESS_KEY),
                new LineageDelivery.Replay("delivery-1", "purview", 2L).deliveryId(PROCESS_KEY));
    }

    /** Per-target compensation only works if two targets are two deliveries. */
    @Test
    public void replayTargetsAreDistinctDeliveries() {
        assertNotEquals(
                new LineageDelivery.Replay("delivery-1", "purview", 1L).deliveryId(PROCESS_KEY),
                new LineageDelivery.Replay("delivery-1", "atlas", 1L).deliveryId(PROCESS_KEY));
    }

    // ------------------------------------------------------------------ REPAIR

    @Test
    public void repairDelegatesToTheFrozenFormula() {
        LineageDelivery delivery = new LineageDelivery.Repair("dl-1", 2L);
        assertEquals(DeliveryKind.REPAIR, delivery.kind());
        assertEquals(LineageIdentity.repairDeliveryId("dl-1", 2L),
                delivery.deliveryId(PROCESS_KEY));
    }

    @Test
    public void repairIgnoresTheProcessKey() {
        LineageDelivery delivery = new LineageDelivery.Repair("dl-1", 2L);
        assertEquals(delivery.deliveryId(PROCESS_KEY), delivery.deliveryId("v2:something-else"));
    }

    @Test
    public void repairRequiresTheDeadLetter() {
        assertThrows(IllegalArgumentException.class, () -> new LineageDelivery.Repair(null, 1L));
        assertThrows(IllegalArgumentException.class, () -> new LineageDelivery.Repair(" ", 1L));
    }

    @Test
    public void repairGenerationStartsAtOne() {
        assertThrows(IllegalArgumentException.class, () -> new LineageDelivery.Repair("dl-1", 0L));
    }

    /**
     * §9 v2.3.12: repairing the same dead letter at the same generation is idempotent, and a new
     * generation is a deliberate second compensation. Both halves need the id to behave this way.
     */
    @Test
    public void repairIsIdempotentPerGenerationAndDistinctAcrossThem() {
        assertEquals(new LineageDelivery.Repair("dl-1", 1L).deliveryId(PROCESS_KEY),
                new LineageDelivery.Repair("dl-1", 1L).deliveryId(PROCESS_KEY));
        assertNotEquals(new LineageDelivery.Repair("dl-1", 1L).deliveryId(PROCESS_KEY),
                new LineageDelivery.Repair("dl-1", 2L).deliveryId(PROCESS_KEY));
    }

    // ------------------------------------------------------------------ the union itself

    /**
     * The three kinds must not collide. They are separate domains in the hash, but that is a
     * property of {@link LineageIdentity} which this type depends on rather than restates.
     */
    @Test
    public void theThreeKindsProduceDifferentIds() {
        String original = new LineageDelivery.Original(List.of("purview")).deliveryId(PROCESS_KEY);
        String replay = new LineageDelivery.Replay(original, "purview", 1L).deliveryId(PROCESS_KEY);
        String repair = new LineageDelivery.Repair(original, 1L).deliveryId(PROCESS_KEY);
        assertNotEquals(original, replay);
        assertNotEquals(original, repair);
        assertNotEquals(replay, repair);
    }

    /**
     * The permits clause is the point of the type: a fourth delivery kind cannot be added from
     * outside, and {@link DeliveryKind} cannot gain a constant without a variant to carry it.
     */
    @Test
    public void everyDeliveryKindHasExactlyOneVariant() {
        assertEquals(DeliveryKind.values().length,
                LineageDelivery.class.getPermittedSubclasses().length,
                "a DeliveryKind constant with no LineageDelivery variant cannot be constructed,"
                        + " and a variant with no constant cannot be tagged");
        assertTrue(LineageDelivery.class.isSealed());
    }
}
