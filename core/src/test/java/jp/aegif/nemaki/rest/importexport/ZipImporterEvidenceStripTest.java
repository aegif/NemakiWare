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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A zip import does not write capture evidence (plan D-6, data-model N2).
 *
 * <p>Archive metadata is attacker-controlled — import needs only create-child permission and the
 * {@code .meta.json} says whatever its author typed (the judgement archive ACEs already get in
 * {@code isAclApplyAllowed}). Before the strip, the import produced the worst available outcome:
 * the evidence VALUES are READONLY and were dropped at create, but the TYPE id flowed into
 * {@code cmis:secondaryObjectTypeIds} — so the product's own restore path manufactured "type
 * attached, every property null". The split these tests pin: archive restore (same repository,
 * raw copy, ledger rows still keyed to the same object id) carries evidence untouched; a zip
 * import mints new ids with no ledger behind the claims, so it must not assert them.
 */
class ZipImporterEvidenceStripTest {

    @AfterEach
    void unhookSpringContext() {
        new SpringContext().setApplicationContext(null);
    }

    @SuppressWarnings("unchecked")
    private static JSONObject metaWithEvidence() {
        JSONObject props = new JSONObject();
        props.put(PropertyIds.OBJECT_ID, "old-1");
        props.put(PropertyIds.OBJECT_TYPE_ID, "cmis:document");
        JSONArray secondary = new JSONArray();
        secondary.add("nemaki:chatContextMetadata");
        secondary.add("nemaki:externalIntegration");
        secondary.add("nemaki:tagged");
        props.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondary);
        props.put("nemaki:chatMessageId", "msg-forged");
        props.put("nemaki:sourceSystem", "slack");
        props.put("nemaki:contentHash", "sha256:forged");
        props.put("nemaki:externalContext", "{}");
        props.put("my:ordinary", "kept");
        JSONObject meta = new JSONObject();
        meta.put("properties", props);
        return meta;
    }

    @Test
    @DisplayName("strip removes the protected types and every evidence property, nothing else")
    void stripRemovesEvidenceAssertions() {
        JSONObject meta = metaWithEvidence();

        List<String> dropped = ZipImporter.stripEvidenceAssertions(meta);

        JSONObject props = (JSONObject) meta.get("properties");
        JSONArray secondary = (JSONArray) props.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
        assertEquals(List.of("nemaki:tagged"), secondary,
                "the protected type ids are exactly what minted the shell");
        assertNull(props.get("nemaki:chatMessageId"));
        assertNull(props.get("nemaki:sourceSystem"));
        assertNull(props.get("nemaki:contentHash"),
                "an imported contentHash would be read back by ingest dedupe and suppress "
                        + "future REAL captures of that source object");
        assertNull(props.get("nemaki:externalContext"));
        assertEquals("kept", props.get("my:ordinary"),
                "the strip must not eat ordinary migration metadata");
        assertTrue(dropped.containsAll(List.of("nemaki:chatContextMetadata",
                "nemaki:externalIntegration", "nemaki:chatMessageId", "nemaki:sourceSystem",
                "nemaki:contentHash", "nemaki:externalContext")),
                "the warning must name everything that was not imported: " + dropped);
    }

    @Test
    @DisplayName("ordinary metadata passes untouched — the control")
    @SuppressWarnings("unchecked")
    void ordinaryMetadataIsUntouched() {
        JSONObject props = new JSONObject();
        JSONArray secondary = new JSONArray();
        secondary.add("nemaki:tagged");
        props.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondary);
        props.put("my:ordinary", "kept");
        JSONObject meta = new JSONObject();
        meta.put("properties", props);

        List<String> dropped = ZipImporter.stripEvidenceAssertions(meta);

        assertTrue(dropped.isEmpty(), "an evidence-free archive must not draw a warning");
        assertEquals(secondary, props.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS));
        assertEquals("kept", props.get("my:ordinary"));
    }

    @Test
    @DisplayName("importCustomFormat: evidence never reaches createDocument; the result says so")
    void importDoesNotForwardEvidenceToCreate(@TempDir Path tempDir) throws Exception {
        File zip = tempDir.resolve("export.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("chat.txt"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("chat.txt" + ImportExportUtils.META_SUFFIX));
            zos.write(metaWithEvidence().toJSONString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        ContentService cs = mock(ContentService.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("ContentService", ContentService.class)).thenReturn(cs);
        new SpringContext().setApplicationContext(ctx);

        Folder target = new Folder();
        target.setId("target");
        when(cs.getFolder("bedroom", "target")).thenReturn(target);
        when(cs.getChildren(eq("bedroom"), anyString())).thenReturn(new ArrayList<>());
        Document created = new Document();
        created.setId("new-1");
        when(cs.createDocument(any(), eq("bedroom"), any(Properties.class), any(), any(), any(),
                any(), any(), any())).thenReturn(created);

        ImportResult result = new ZipImporter().importCustomFormat("bedroom", "target", zip,
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class));

        ArgumentCaptor<Properties> sent = ArgumentCaptor.forClass(Properties.class);
        verify(cs).createDocument(any(), eq("bedroom"), sent.capture(), any(), any(), any(),
                any(), any(), any());

        List<?> secondaryIds = sent.getValue().getProperties()
                .get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS).getValues();
        assertEquals(List.of("nemaki:tagged"), secondaryIds,
                "the evidence type id flowed through to create — this is exactly the list that "
                        + "minted the empty shell");
        assertFalse(sent.getValue().getProperties().containsKey("nemaki:chatMessageId"),
                "an evidence value reached the CMIS create request");
        assertFalse(sent.getValue().getProperties().containsKey("nemaki:contentHash"));
        assertTrue(sent.getValue().getProperties().containsKey("my:ordinary"),
                "ordinary migration metadata must still import");

        assertEquals(1, result.documentsCreated, String.valueOf(result.errors));
        assertTrue(result.warnings.stream().anyMatch(w ->
                        w.contains("Evidence metadata not imported for 'chat.txt'")
                                && w.contains("nemaki:chatContextMetadata")),
                "dropping assertions without saying so would be silent data loss: "
                        + result.warnings);
    }

    @Test
    @DisplayName("an evidence-free import draws no evidence warning — the control")
    void evidenceFreeImportHasNoWarning(@TempDir Path tempDir) throws Exception {
        File zip = tempDir.resolve("plain.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("plain.txt"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        ContentService cs = mock(ContentService.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("ContentService", ContentService.class)).thenReturn(cs);
        new SpringContext().setApplicationContext(ctx);

        Folder target = new Folder();
        target.setId("target");
        when(cs.getFolder("bedroom", "target")).thenReturn(target);
        when(cs.getChildren(eq("bedroom"), anyString())).thenReturn(new ArrayList<>());
        Document created = new Document();
        created.setId("new-1");
        when(cs.createDocument(any(), eq("bedroom"), any(Properties.class), any(), any(), any(),
                any(), any(), any())).thenReturn(created);

        ImportResult result = new ZipImporter().importCustomFormat("bedroom", "target", zip,
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class));

        assertEquals(1, result.documentsCreated, String.valueOf(result.errors));
        assertTrue(result.warnings.stream().noneMatch(w -> w.contains("Evidence metadata")),
                "a warning on every plain import would train operators to ignore it: "
                        + result.warnings);
    }
}
