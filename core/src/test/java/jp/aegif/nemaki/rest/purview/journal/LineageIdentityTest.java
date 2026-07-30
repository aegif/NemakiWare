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
 * The two identities a lineage record has: which business fact it is, and which delivery
 * obligation it is.
 *
 * <p>These were one key before, and the collisions that caused are what most of this file is
 * about — a replay landing on its original, two targets' replays landing on each other, and a
 * second import into the same folder being discarded as a duplicate of the first.
 */
public class LineageIdentityTest {

    private static final String REPO = "bedroom";
    private static final String OP = "op-1111";

    private static LineageEndpoint doc(String id) {
        return LineageEndpoint.document(REPO, id, "doc-" + id);
    }

    private static String processKey(String operationId, List<LineageEndpoint> inputs,
                                     List<LineageEndpoint> outputs, int chunkIndex,
                                     int chunkCount) {
        return LineageIdentity.processKey(REPO, LineageProcessType.IMPORT_UPLOADED, operationId,
                inputs, outputs, 2, chunkIndex, chunkCount);
    }

    // ------------------------------------------------------------------
    // processKey — which business fact
    // ------------------------------------------------------------------

    /**
     * The reason {@code operationId} is in the key at all. Two imports of the same file into the
     * same folder have identical endpoints; without the operation they are one fact, and the sink
     * skips the second as a duplicate.
     */
    @Test
    public void sameEndpointsUnderDifferentOperationsAreDifferentFacts() {
        assertNotEquals(processKey("op-1", List.of(doc("a")), List.of(doc("b")), 0, 1),
                processKey("op-2", List.of(doc("a")), List.of(doc("b")), 0, 1));
    }

    /** The same export, ticked in a different order in the UI, is the same export. */
    @Test
    public void endpointOrderDoesNotChangeTheFact() {
        assertEquals(processKey(OP, List.of(doc("a"), doc("b")), List.of(), 0, 1),
                processKey(OP, List.of(doc("b"), doc("a")), List.of(), 0, 1));
    }

    /** Inputs and outputs are separate positions; swapping them is a different fact. */
    @Test
    public void inputsAndOutputsAreNotInterchangeable() {
        assertNotEquals(processKey(OP, List.of(doc("a")), List.of(doc("b")), 0, 1),
                processKey(OP, List.of(doc("b")), List.of(doc("a")), 0, 1));
    }

    /** Each chunk of a large export publishes as its own Process, so it needs its own key. */
    @Test
    public void chunksOfOneOperationAreDistinctFacts() {
        assertNotEquals(processKey(OP, List.of(doc("a")), List.of(), 0, 2),
                processKey(OP, List.of(doc("a")), List.of(), 1, 2));
    }

    /** An unchunked event and chunk 0 of a two-chunk event are not the same fact either. */
    @Test
    public void chunkCountIsPartOfTheFact() {
        assertNotEquals(processKey(OP, List.of(doc("a")), List.of(), 0, 1),
                processKey(OP, List.of(doc("a")), List.of(), 0, 2));
    }

    @Test
    public void processTypeIsPartOfTheFact() {
        assertNotEquals(
                LineageIdentity.processKey(REPO, LineageProcessType.IMPORT_UPLOADED, OP,
                        List.of(doc("a")), List.of(), 2, 0, 1),
                LineageIdentity.processKey(REPO, LineageProcessType.IMPORT_FILESYSTEM, OP,
                        List.of(doc("a")), List.of(), 2, 0, 1));
    }

    /** Two repositories doing the same operation on the same object id are two facts. */
    @Test
    public void repositoryIsPartOfTheFact() {
        assertNotEquals(
                LineageIdentity.processKey("bedroom", LineageProcessType.IMPORT_UPLOADED, OP,
                        List.of(LineageEndpoint.document("bedroom", "a", "n")), List.of(),
                        2, 0, 1),
                LineageIdentity.processKey("canopy", LineageProcessType.IMPORT_UPLOADED, OP,
                        List.of(LineageEndpoint.document("canopy", "a", "n")), List.of(),
                        2, 0, 1));
    }

    /**
     * A rename is not a new fact. {@code processKey} is built from qualified names, which use the
     * immutable objectId; the endpoint's attribute snapshot travels with the event and will be
     * covered by {@code creationPayloadDigest} in increment A-2.
     */
    @Test
    public void renamingAnObjectDoesNotChangeTheFact() {
        assertEquals(
                processKey(OP, List.of(LineageEndpoint.document(REPO, "a", "before")),
                        List.of(), 0, 1),
                processKey(OP, List.of(LineageEndpoint.document(REPO, "a", "after")),
                        List.of(), 0, 1));
    }

    /** Two endpoints resolving to one entity would misstate the Process arity. */
    @Test
    public void duplicateEndpointsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> processKey(OP, List.of(doc("a"), doc("a")), List.of(), 0, 1));
    }

    @Test
    public void processKeyIsPrefixedWithTheIdentityVersion() {
        String key = processKey(OP, List.of(doc("a")), List.of(), 0, 1);
        assertTrue(key.startsWith("v2:"), key);
        assertEquals(2, LineageIdentity.IDEMPOTENCY_KEY_VERSION);
        assertEquals(3 + 64, key.length(), "'v2:' plus a SHA-256 in hex");
    }

    // ------------------------------------------------------------------
    // deliveryId — which delivery obligation
    // ------------------------------------------------------------------

    /**
     * An ORIGINAL has no single target — it carries {@code publishStatusByTarget} — so it keys off
     * the whole target set, in canonical order.
     */
    @Test
    public void originalDeliveryIdKeysOffTheWholeTargetSet() {
        String fact = processKey(OP, List.of(doc("a")), List.of(doc("b")), 0, 1);
        assertEquals(LineageIdentity.originalDeliveryId(fact, List.of("atlas", "purview")),
                LineageIdentity.originalDeliveryId(fact, List.of("purview", "atlas")),
                "the order targets are configured in is not part of the obligation");
        assertNotEquals(LineageIdentity.originalDeliveryId(fact, List.of("atlas", "purview")),
                LineageIdentity.originalDeliveryId(fact, List.of("atlas")),
                "delivering to two targets is a different obligation from delivering to one");
    }

    /** Same targets, different fact: different record. */
    @Test
    public void originalDeliveryIdDependsOnTheFact() {
        assertNotEquals(
                LineageIdentity.originalDeliveryId(
                        processKey("op-1", List.of(doc("a")), List.of(), 0, 1), List.of("atlas")),
                LineageIdentity.originalDeliveryId(
                        processKey("op-2", List.of(doc("a")), List.of(), 0, 1), List.of("atlas")));
    }

    /**
     * The collision that lost a compensation event: a replay must not land on its original, and
     * two targets replaying the same fact must not land on each other.
     */
    @Test
    public void replayDeliveryIdsCollideWithNothing() {
        String fact = processKey(OP, List.of(doc("a")), List.of(), 0, 1);
        String original = LineageIdentity.originalDeliveryId(fact, List.of("atlas", "purview"));

        String atlasGen1 = LineageIdentity.replayDeliveryId(original, "atlas", 1);
        String purviewGen1 = LineageIdentity.replayDeliveryId(original, "purview", 1);
        String atlasGen2 = LineageIdentity.replayDeliveryId(original, "atlas", 2);

        assertNotEquals(original, atlasGen1);
        assertNotEquals(atlasGen1, purviewGen1, "per-target replays are separate obligations");
        assertNotEquals(atlasGen1, atlasGen2, "a second replay is a new obligation");
    }

    /**
     * Deterministic on purpose: a crash between writing the compensation event and acking the
     * request must not leave a second one behind. The retry recomputes the same id and
     * create-if-absent collapses it.
     */
    @Test
    public void replayDeliveryIdIsReproducibleAfterACrash() {
        String original = LineageIdentity.originalDeliveryId(
                processKey(OP, List.of(doc("a")), List.of(), 0, 1), List.of("atlas"));
        assertEquals(LineageIdentity.replayDeliveryId(original, "atlas", 1),
                LineageIdentity.replayDeliveryId(original, "atlas", 1));
    }

    @Test
    public void repairDeliveryIdIsPerDeadLetterAndGeneration() {
        assertNotEquals(LineageIdentity.repairDeliveryId("lineage_dl:x", 1),
                LineageIdentity.repairDeliveryId("lineage_dl:y", 1));
        assertNotEquals(LineageIdentity.repairDeliveryId("lineage_dl:x", 1),
                LineageIdentity.repairDeliveryId("lineage_dl:x", 2));
        assertEquals(LineageIdentity.repairDeliveryId("lineage_dl:x", 1),
                LineageIdentity.repairDeliveryId("lineage_dl:x", 1));
    }

    /**
     * A blank target would silently produce a well-formed id for an obligation with no target,
     * which nothing downstream could route.
     */
    @Test
    public void replayRejectsANullOrBlankTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageIdentity.replayDeliveryId("d", null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> LineageIdentity.replayDeliveryId("d", " ", 1));
    }

    @Test
    public void repairRejectsANullOrBlankDeadLetterId() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageIdentity.repairDeliveryId(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> LineageIdentity.repairDeliveryId(" ", 1));
    }

    /** The three kinds share one hash function; the tag is what keeps their spaces apart. */
    @Test
    public void theThreeDeliveryKindsOccupyDifferentSpaces() {
        String fact = processKey(OP, List.of(doc("a")), List.of(), 0, 1);
        String original = LineageIdentity.originalDeliveryId(fact, List.of("atlas"));
        assertNotEquals(original, LineageIdentity.replayDeliveryId(original, "atlas", 1));
        assertNotEquals(original, LineageIdentity.repairDeliveryId(original, 1));
        assertNotEquals(LineageIdentity.replayDeliveryId(original, "atlas", 1),
                LineageIdentity.repairDeliveryId(original, 1));
    }

    // ------------------------------------------------------------------
    // Golden vectors
    // ------------------------------------------------------------------

    /**
     * Fixed expected values, not values recomputed from the code under test. A refactor that
     * reorders the arguments, drops one, or changes a tag leaves every relational test above green
     * and turns this one red — which is the point, because the ids already in CouchDB were written
     * by the old argument list.
     */
    @Test
    public void goldenVectors() {
        String processKey = LineageIdentity.processKey(REPO, LineageProcessType.IMPORT_UPLOADED,
                "op-fixed",
                List.of(LineageEndpoint.document(REPO, "in-1", "n")),
                List.of(LineageEndpoint.document(REPO, "out-1", "n")),
                2, 0, 1);
        assertEquals("v2:30565d46184fd03ca367573e4b34b78962995a7094258f901f0c4022afd773b3",
                processKey);

        String original = LineageIdentity.originalDeliveryId(processKey, List.of("atlas"));
        assertEquals("3db33959793d7be66f9ad5329d4a28f77f3332aefdd33f44a4d7669d3507ec48", original);

        assertEquals("b096d6174a5b83b2ec23fd7685a83fab95843b54e4c2c69668832497e388ba62",
                LineageIdentity.replayDeliveryId(original, "atlas", 1));
        assertEquals("1510a3f32e8cdda507d877cf86e87a2958f5551c811fffd7e316823813c9deb6",
                LineageIdentity.repairDeliveryId("lineage_dl:fixed", 1));
    }
}
