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
package jp.aegif.nemaki.rest.purview.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.publish.CloudMetadataSnapshotFormat;

/**
 * The 4b cursor check (v2.3.27), and above all the cases where a laxer predicate would have
 * reported a deployment clean that is not.
 */
public class CloudMetadataCursorInspectionTest {

    /** A URL that must never appear in any output. Shaped like a real sharing link. */
    private static final String TOKEN_URL =
            "https://contoso.sharepoint.com/:x:/g/personal/u/PATHTOKEN?authkey=AUTHKEYabc";

    private static CloudMetadataCursorInspection inspect(String stored) {
        return CloudMetadataCursorInspection.of("bedroom",
                PurviewStateStore.RawEntry.of(stored));
    }

    @Test
    public void aUrlFreeSnapshotIsClean() {
        String stored = String.join("\n",
                CloudMetadataSnapshotFormat.entry("doc-1", "onedrive", "file-1", "2026-01-01"),
                CloudMetadataSnapshotFormat.entry("doc-2", "onedrive", "file-2", "2026-01-02"));
        CloudMetadataCursorInspection verdict = inspect(stored);
        assertTrue(verdict.clean());
        assertEquals(2, verdict.lines());
        assertEquals(0, verdict.malformedLines());
        assertEquals(0, verdict.populatedUrlLines());
    }

    @Test
    public void aPopulatedUrlSlotFails() {
        String stored = "doc-1|onedrive|file-1|" + TOKEN_URL + "|2026-01-01";
        CloudMetadataCursorInspection verdict = inspect(stored);
        assertFalse(verdict.clean());
        assertEquals(1, verdict.populatedUrlLines());
    }

    /**
     * The case the whole check exists for. {@code normalize} passes a line it does not
     * recognize through UNTOUCHED, so {@code stored.equals(normalize(stored))} would have
     * called this clean — with a live URL sitting in it.
     */
    @Test
    public void anUnrecognizedShapeCarryingAUrlIsNotCalledClean() {
        String stored = "doc-1|onedrive|file-1|" + TOKEN_URL; // four fields, not five
        assertEquals(stored, CloudMetadataSnapshotFormat.normalize(stored),
                "normalize leaves it alone — which is exactly why equality is the wrong test");
        CloudMetadataCursorInspection verdict = inspect(stored);
        assertFalse(verdict.clean(), "the strict parse refuses a shape nobody verified");
        assertEquals(1, verdict.malformedLines());
    }

    @Test
    public void sixFieldsIsAlsoMalformed() {
        CloudMetadataCursorInspection verdict =
                inspect("doc-1|onedrive|file-1||2026-01-01|extra");
        assertFalse(verdict.clean());
        assertEquals(1, verdict.malformedLines());
    }

    @Test
    public void blankLinesAreNotCounted() {
        String stored = "\n"
                + CloudMetadataSnapshotFormat.entry("doc-1", "onedrive", "f", "2026-01-01")
                + "\n\n";
        CloudMetadataCursorInspection verdict = inspect(stored);
        assertTrue(verdict.clean());
        assertEquals(1, verdict.lines());
    }

    // ---------------------------------------------------------------- presence

    @Test
    public void absentAndPresentEmptyAreDistinctAndBothClean() {
        CloudMetadataCursorInspection absent = CloudMetadataCursorInspection.of("bedroom",
                PurviewStateStore.RawEntry.absent());
        assertEquals(PurviewStateStore.Presence.ABSENT, absent.presence());
        assertTrue(absent.clean());

        CloudMetadataCursorInspection empty = CloudMetadataCursorInspection.of("bedroom",
                PurviewStateStore.RawEntry.of(""));
        assertEquals(PurviewStateStore.Presence.PRESENT_EMPTY, empty.presence());
        assertTrue(empty.clean());
    }

    /** A cursor we could not read has not been checked. */
    @Test
    public void aReadErrorFailsClosed() {
        CloudMetadataCursorInspection verdict = CloudMetadataCursorInspection.of("bedroom",
                PurviewStateStore.RawEntry.error());
        assertEquals(PurviewStateStore.Presence.ERROR, verdict.presence());
        assertFalse(verdict.clean(), "ERROR is never green — nothing was established");
    }

    // ---------------------------------------------------------------- no leakage

    /**
     * The check must not become the thing that prints a token. Nothing derived from the value
     * may carry any part of it.
     */
    @Test
    public void nothingInTheVerdictContainsAnyPartOfTheStoredValue() {
        String stored = "doc-1|onedrive|file-1|" + TOKEN_URL + "|2026-01-01";
        String rendered = inspect(stored).toString();
        for (String fragment : List.of(TOKEN_URL, "AUTHKEYabc", "PATHTOKEN",
                "sharepoint.com", "authkey")) {
            assertFalse(rendered.contains(fragment),
                    "the verdict leaked '" + fragment + "'");
        }
    }

    // ---------------------------------------------------------------- the union inventory

    /** A repository dropped from configuration keeps its cursor — and must still be checked. */
    @Test
    public void theInventoryIsTheUnionOfConfiguredAndPersisted() {
        Map<String, Object> stored = Map.of(
                "purview.cursor.state.gone.cloud-metadata-snapshot.cursor",
                "doc-1|onedrive|file-1|" + TOKEN_URL + "|2026-01-01");
        PurviewCursorStateServiceImpl service =
                new PurviewCursorStateServiceImpl(new FakeStore(stored));

        List<CloudMetadataCursorInspection> verdicts =
                service.inspectCloudMetadataCursors(List.of("bedroom"));
        List<String> ids = verdicts.stream()
                .map(CloudMetadataCursorInspection::repositoryId).sorted().toList();
        assertEquals(List.of("bedroom", "gone"), ids,
                "checking only the configured set would step over the residue");
        assertFalse(verdicts.stream()
                        .filter(v -> v.repositoryId().equals("gone")).findFirst()
                        .orElseThrow().clean());
    }

    /**
     * The migration leaves the legacy document in place when the dedicated one already
     * exists, so a clean dedicated cursor can sit on top of a legacy one that still carries a
     * raw URL. Checking only the value a reader would get would call that deployment clean.
     */
    @Test
    public void aDirtyLegacyCursorIsNotMaskedByACleanDedicatedOne() {
        CloudMetadataCursorInspection verdict = CloudMetadataCursorInspection.ofAll("bedroom",
                List.of(
                        PurviewStateStore.RawEntry.of(CloudMetadataSnapshotFormat
                                .entry("doc-1", "onedrive", "f", "2026-01-01")),
                        PurviewStateStore.RawEntry.of(
                                "doc-1|onedrive|file-1|" + TOKEN_URL + "|2026-01-01")));
        assertFalse(verdict.clean(), "the legacy residue must not hide behind the new store");
        assertEquals(1, verdict.populatedUrlLines());
    }

    @Test
    public void oneStoreFailingToReadFailsTheWholeRepository() {
        CloudMetadataCursorInspection verdict = CloudMetadataCursorInspection.ofAll("bedroom",
                List.of(
                        PurviewStateStore.RawEntry.of(CloudMetadataSnapshotFormat
                                .entry("doc-1", "onedrive", "f", "2026-01-01")),
                        PurviewStateStore.RawEntry.error()));
        assertFalse(verdict.clean());
        assertEquals(PurviewStateStore.Presence.ERROR, verdict.presence());
    }

    @Test
    public void anEmptyStoreListIsAnError() {
        CloudMetadataCursorInspection verdict =
                CloudMetadataCursorInspection.ofAll("bedroom", List.of());
        assertFalse(verdict.clean());
        assertEquals(PurviewStateStore.Presence.ERROR, verdict.presence());
    }

    /** A store that has not opted into strict enumeration cannot be treated as complete. */
    @Test
    public void aStoreWithoutStrictEnumerationFailsClosed() {
        PurviewCursorStateServiceImpl service = new PurviewCursorStateServiceImpl(
                new PurviewStateStore() {
                    @Override
                    public String getString(String key) {
                        return "";
                    }

                    @Override
                    public int getInt(String key) {
                        return 0;
                    }

                    @Override
                    public Map<String, Object> getAll() {
                        return Map.of();
                    }

                    @Override
                    public void putAll(Map<String, Object> values) {
                    }

                    @Override
                    public void removeAll(java.util.Collection<String> keys) {
                    }
                });
        List<CloudMetadataCursorInspection> verdicts =
                service.inspectCloudMetadataCursors(List.of("bedroom"));
        assertTrue(verdicts.stream().anyMatch(v -> !v.clean()),
                "the default getAllStrict throws, and that must surface as a failure");
        // and the default getRaw is ERROR, so bedroom itself is not green either
        assertTrue(verdicts.stream()
                .filter(v -> v.repositoryId().equals("bedroom")).findFirst().orElseThrow()
                .presence() == PurviewStateStore.Presence.ERROR);
    }

    @Test
    public void anUnreadableInventoryFailsClosed() {
        PurviewCursorStateServiceImpl service =
                new PurviewCursorStateServiceImpl(new FakeStore(Map.of()) {
                    @Override
                    public Map<String, Object> getAllStrict() {
                        throw new IllegalStateException("state DB unreachable");
                    }
                });
        List<CloudMetadataCursorInspection> verdicts =
                service.inspectCloudMetadataCursors(List.of("bedroom"));
        assertTrue(verdicts.stream().anyMatch(v -> !v.clean()
                        && v.presence() == PurviewStateStore.Presence.ERROR),
                "an inventory that could not be enumerated must not look complete");
    }

    /** A minimal store with real four-state presence. */
    static class FakeStore implements PurviewStateStore {
        private final Map<String, Object> values;

        FakeStore(Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public String getString(String key) {
            Object value = values.get(key);
            return value == null ? "" : value.toString();
        }

        @Override
        public RawEntry getRaw(String key) {
            return values.containsKey(key)
                    ? RawEntry.of(String.valueOf(values.get(key))) : RawEntry.absent();
        }

        @Override
        public java.util.List<RawEntry> getRawEverywhere(String key) {
            return java.util.List.of(getRaw(key));
        }

        @Override
        public int getInt(String key) {
            return 0;
        }

        @Override
        public Map<String, Object> getAll() {
            return values;
        }

        @Override
        public Map<String, Object> getAllStrict() {
            return getAll();
        }

        @Override
        public void putAll(Map<String, Object> toPut) {
        }

        @Override
        public void removeAll(java.util.Collection<String> keys) {
        }
    }
}
