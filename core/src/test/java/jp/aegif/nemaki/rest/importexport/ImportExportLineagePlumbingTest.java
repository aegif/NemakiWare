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
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.importexport.ImportExportUtils.CreatedObject;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ExportedObject;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ExportedObjectCollector;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;

/**
 * The moved-content recording that producer P-3 feeds into {@code LineageFact} — insertion
 * order and objectId dedupe are what the typed endpoints inherit, so both are pinned here.
 */
public class ImportExportLineagePlumbingTest {

    @Test
    public void createdObjectsKeepCreationOrderAndDedupeById() {
        ImportResult result = new ImportResult();
        result.recordCreated("f1", "Reports", true, "root");
        result.recordCreated("d1", "a.txt", false, "f1");
        result.recordCreated("d1", "renamed-later.txt", false, "f1"); // duplicate id: first wins
        result.recordCreated(null, "no-id", false, "f1");             // ignored
        result.recordCreated("d2", "b.txt", false, "root");

        assertEquals(List.of(
                        new CreatedObject("f1", "Reports", true, "root"),
                        new CreatedObject("d1", "a.txt", false, "f1"),
                        new CreatedObject("d2", "b.txt", false, "root")),
                result.createdObjects);
    }

    @Test
    public void exportedObjectCollectorKeepsOrderAndDedupesById() {
        ExportedObjectCollector collector = new ExportedObjectCollector();
        collector.record("d1", "a.txt", false);
        collector.record("f1", "Sub", true);
        collector.record("d1", "a.txt", false); // a doc reachable twice records once
        collector.record(null, "no-id", false);

        assertEquals(List.of(
                        new ExportedObject("d1", "a.txt", false),
                        new ExportedObject("f1", "Sub", true)),
                collector.asList());
    }

    @Test
    public void exportResultRecordingMirrorsTheCollector() {
        ImportExportUtils.ExportResult result = new ImportExportUtils.ExportResult();
        result.recordExported("d1", "a.txt", false);
        result.recordExported("d1", "a.txt", false);
        result.recordExported("f1", "Sub", true);

        assertEquals(2, result.exportedObjects.size());
        assertEquals("d1", result.exportedObjects.get(0).objectId());
        assertTrue(result.exportedObjects.get(1).folder());
    }

    /** The collector's list is a snapshot, not a live view — producers hand it to a lambda. */
    @Test
    public void collectorSnapshotsAreImmutableAndStable() {
        ExportedObjectCollector collector = new ExportedObjectCollector();
        collector.record("d1", "a.txt", false);
        List<ExportedObject> first = collector.asList();
        collector.record("d2", "b.txt", false);

        assertEquals(1, first.size(), "an earlier snapshot must not grow");
        assertEquals(2, collector.asList().size());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> collector.asList().add(new ExportedObject("x", "x", false)));
    }
}
