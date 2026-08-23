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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filesystem import route gets the same evidence strip as the zip route (review F-9).
 *
 * <p>Same {@code .meta.json} format, same {@code addTypedProperty} application — and before this,
 * no strip: the route is admin-only and path-allowlisted, but the rule ("an import writes no
 * capture ledger rows, so it must not write capture assertions") does not depend on who imports.
 */
class FilesystemImporterEvidenceStripTest {

    @AfterEach
    void unhookSpringContext() {
        new SpringContext().setApplicationContext(null);
    }

    @Test
    @DisplayName("evidence in a filesystem meta.json does not reach createDocument")
    @SuppressWarnings("unchecked")
    void filesystemImportIsStripped(@TempDir Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("chat.txt"), "hello", StandardCharsets.UTF_8);
        JSONObject props = new JSONObject();
        JSONArray secondary = new JSONArray();
        secondary.add("nemaki:chatContextMetadata");
        secondary.add("nemaki:tagged");
        props.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondary);
        props.put("nemaki:chatMessageId", "msg-forged");
        props.put("my:ordinary", "kept");
        JSONObject meta = new JSONObject();
        meta.put("properties", props);
        Files.writeString(sourceDir.resolve("chat.txt" + ImportExportUtils.META_SUFFIX),
                meta.toJSONString(), StandardCharsets.UTF_8);

        ContentService cs = mock(ContentService.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("ContentService", ContentService.class)).thenReturn(cs);
        new SpringContext().setApplicationContext(ctx);

        Folder target = new Folder();
        target.setId("target");
        when(cs.getFolder(eq("bedroom"), anyString())).thenReturn(target);
        when(cs.getChildren(eq("bedroom"), anyString())).thenReturn(new ArrayList<>());
        Document created = new Document();
        created.setId("new-1");
        when(cs.createDocument(any(), eq("bedroom"), any(Properties.class), any(), any(), any(),
                any(), any(), any())).thenReturn(created);

        ImportResult result = new FilesystemImporter().importFromFilesystemDirectory(
                "bedroom", "target", sourceDir,
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class));

        ArgumentCaptor<Properties> sent = ArgumentCaptor.forClass(Properties.class);
        verify(cs).createDocument(any(), eq("bedroom"), sent.capture(), any(), any(), any(),
                any(), any(), any());
        List<?> secondaryIds = sent.getValue().getProperties()
                .get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS).getValues();
        assertEquals(List.of("nemaki:tagged"), secondaryIds,
                "the protected type id reached create through the filesystem route");
        assertFalse(sent.getValue().getProperties().containsKey("nemaki:chatMessageId"));
        assertTrue(sent.getValue().getProperties().containsKey("my:ordinary"),
                "ordinary migration metadata must still import");
        assertEquals(1, result.documentsCreated, String.valueOf(result.errors));
        assertTrue(result.warnings.stream().anyMatch(w ->
                        w.contains("Evidence metadata not imported for 'chat.txt'")),
                "dropping assertions without saying so would be silent data loss: "
                        + result.warnings);
    }
}
