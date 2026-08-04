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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.journal.LineageCatalogEntityProbe.Presence;

/**
 * One target's answer never becomes another's.
 *
 * <p>The obligation's task key names a target. If the verdict behind it could come from a
 * different catalog, the key would be a label rather than an identity — and a projection to
 * Purview would proceed because Atlas happened to hold the entity.
 */
public class LineageCatalogProbeRegistryTest {

    private static final String QN = "nemaki://bedroom/folders/f-1/dataset";
    private static final EndpointKind KIND = EndpointKind.CMIS_FOLDER;

    /** Records what it was asked, so routing can be asserted and not merely assumed. */
    private static final class RecordingProbe implements LineageCatalogEntityProbe {
        final List<String> asked = new ArrayList<>();
        final Presence answer;
        final RuntimeException failure;

        RecordingProbe(Presence answer) {
            this(answer, null);
        }

        RecordingProbe(Presence answer, RuntimeException failure) {
            this.answer = answer;
            this.failure = failure;
        }

        @Override
        public Presence presenceOf(String target, String repositoryId, EndpointKind kind,
                String catalogQualifiedName) {
            asked.add(target + "/" + repositoryId + "/" + kind);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    /** The case the separation exists for. */
    @Test
    @DisplayName("the same name is PRESENT in one catalog and ABSENT in another")
    public void targetsAnswerIndependently() {
        RecordingProbe atlas = new RecordingProbe(Presence.PRESENT);
        RecordingProbe purview = new RecordingProbe(Presence.ABSENT);
        LineageCatalogProbeRegistry registry =
                new LineageCatalogProbeRegistry(Map.of("atlas", atlas, "purview", purview));

        assertEquals(Presence.PRESENT, registry.presenceOf("atlas", "bedroom", KIND, QN));
        assertEquals(Presence.ABSENT, registry.presenceOf("purview", "bedroom", KIND, QN));

        assertEquals(List.of("atlas/bedroom/CMIS_FOLDER"), atlas.asked);
        assertEquals(List.of("purview/bedroom/CMIS_FOLDER"), purview.asked);
    }

    @Test
    @DisplayName("one target failing does not change the other's answer")
    public void oneTargetFailingIsContained() {
        LineageCatalogProbeRegistry registry = new LineageCatalogProbeRegistry(Map.of(
                "atlas", new RecordingProbe(null, new IllegalStateException("connection refused")),
                "purview", new RecordingProbe(Presence.PRESENT)));

        assertEquals(Presence.UNKNOWN, registry.presenceOf("atlas", "bedroom", KIND, QN));
        assertEquals(Presence.PRESENT, registry.presenceOf("purview", "bedroom", KIND, QN));
    }

    /** Fail-closed, and specifically not "ask whoever is available". */
    @Test
    @DisplayName("an unknown target is UNKNOWN, never answered by another probe")
    public void unknownTargetIsFailClosed() {
        RecordingProbe atlas = new RecordingProbe(Presence.PRESENT);
        LineageCatalogProbeRegistry registry =
                new LineageCatalogProbeRegistry(Map.of("atlas", atlas));

        assertEquals(Presence.UNKNOWN, registry.presenceOf("purview", "bedroom", KIND, QN));
        assertEquals(Presence.UNKNOWN, registry.presenceOf(null, "bedroom", KIND, QN));
        assertTrue(atlas.asked.isEmpty(), "another target's probe was consulted");
    }

    @Test
    @DisplayName("an empty registry answers UNKNOWN rather than throwing")
    public void emptyRegistryIsUnknown() {
        LineageCatalogProbeRegistry registry = new LineageCatalogProbeRegistry(null);

        assertEquals(Presence.UNKNOWN, registry.presenceOf("atlas", "bedroom", KIND, QN));
        assertEquals(Set.of(), registry.knownTargets());
        assertFalse(registry.canProbe("atlas"));
    }

    /** A probe that answers null has answered nothing, which is UNKNOWN and not a crash. */
    @Test
    @DisplayName("a null answer is UNKNOWN")
    public void nullAnswerIsUnknown() {
        LineageCatalogProbeRegistry registry = new LineageCatalogProbeRegistry(
                Map.of("atlas", new RecordingProbe(null)));

        assertEquals(Presence.UNKNOWN, registry.presenceOf("atlas", "bedroom", KIND, QN));
    }

    /**
     * The readiness wiring check reads these, so they must reflect what is actually routable
     * rather than what was configured.
     */
    @Test
    @DisplayName("the wiring check reports exactly the routable targets")
    public void wiringCheckIsStructural() {
        LineageCatalogProbeRegistry registry = new LineageCatalogProbeRegistry(Map.of(
                "atlas", new RecordingProbe(Presence.PRESENT),
                "purview", new RecordingProbe(Presence.ABSENT)));

        assertEquals(Set.of("atlas", "purview"), registry.knownTargets());
        assertTrue(registry.canProbe("atlas"));
        assertFalse(registry.canProbe("unconfigured"));
        assertFalse(registry.canProbe(null));
    }

    /**
     * A probe's exception message can echo a catalog response body, and an external asset's
     * qualified name contains its stable key. Neither may reach a log through this class.
     */
    @Test
    @DisplayName("a probe failure is swallowed into UNKNOWN, not rethrown with its body")
    public void failureCarriesNothingOut() {
        String secretish = "nemaki://bedroom/external/"
                + "s3%3A%2F%2Fbucket%2Fkey%3FX-Amz-Signature%3Ddeadbeef";
        LineageCatalogProbeRegistry registry = new LineageCatalogProbeRegistry(Map.of(
                "atlas", new RecordingProbe(null,
                        new IllegalStateException("catalog said: " + secretish))));

        // No exception escapes, so no message carrying the name can be logged by a caller.
        assertEquals(Presence.UNKNOWN,
                registry.presenceOf("atlas", "bedroom", EndpointKind.EXTERNAL_ASSET, secretish));
    }
}
