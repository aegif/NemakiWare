/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.rest.importexport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * The ZIP import creates type definitions for an administrator only, asked as the CMIS createType asks it (R335, 3.4.2).
 *
 * <p>The custom format's type-definition entries were made into repository-wide types for whoever imported: a
 * non-administrator who may create in one folder made a type with one ZIP, while the Browser binding's createType answered
 * that user 403 (measured on nb33).
 *
 * <p>What is locked, on the product's {@code ZipImporter.importCustomFormat}: when the ZIP holds a type entry, the
 * exception service's {@code perimissionAdmin} — createType's own question — is asked, as the importer, before the type
 * service is touched at all (no entry read into a type, no existing type looked up — whose answer would tell which types
 * there are — nothing created); a refusal, or a question that cannot be asked (no exception service, a failure), makes no
 * type and names each type entry in the errors, and the other entries go on; an administrator's import makes the types as
 * before; a ZIP with no type entry does not ask. The services are stand-ins handed over as the product looks them up.
 */
class AZipImportCreatesTypesForAnAdministratorOnlyTest {

    private static final String REPO = "bedroom";
    private static final String NEW_TYPE = "{\"id\":\"t:new\",\"localName\":\"new\",\"baseId\":\"cmis:document\",\"parentId\":\"cmis:document\"}";

    private final ContentService cs = mock(ContentService.class);
    private final ExceptionService exceptions = mock(ExceptionService.class);
    private final TypeService types = mock(TypeService.class);
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final CallContext importer = mock(CallContext.class);
    private final List<CallContext> askedAbout = new ArrayList<>();

    AZipImportCreatesTypesForAnAdministratorOnlyTest() {
        when(context.getBean("ContentService", ContentService.class)).thenReturn(cs);
        when(context.getBean("ExceptionService", ExceptionService.class)).thenReturn(exceptions);
        when(context.getBean("TypeService", TypeService.class)).thenReturn(types);
        new SpringContext().setApplicationContext(context);
        Folder target = new Folder();
        target.setId("target");
        target.setName("target");
        when(cs.getFolder(REPO, "target")).thenReturn(target);
        when(cs.getChildren(eq(REPO), anyString())).thenReturn(List.of());
        Document created = new Document();
        created.setId("the-imported");
        when(cs.createDocument(any(), eq(REPO), any(Properties.class), any(), any(), any(), any(), any(), any())).thenReturn(created);
        when(types.createTypeDefinition(eq(REPO), any(NemakiTypeDefinition.class))).thenAnswer(call -> call.getArgument(1));
        when(types.getTypeDefinition(REPO, "cmis:document")).thenReturn(new NemakiTypeDefinition());
        when(importer.getUsername()).thenReturn("importer");
    }

    @AfterEach
    void unhookSpringContext() {
        new SpringContext().setApplicationContext(null);
    }

    private void anAdministratorIs(boolean admin) {
        doAnswer(call -> {
            askedAbout.add(call.getArgument(0));
            if (!admin) {
                throw new CmisPermissionDeniedException("This operation is permitted only for administrator");
            }
            return null;
        }).when(exceptions).perimissionAdmin(any(CallContext.class), eq(REPO));
    }

    private ImportResult importZip(Path dir, boolean withTypes) throws Exception {
        File zip = dir.resolve("import.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            if (withTypes) {
                zos.putNextEntry(new ZipEntry(".nemaki-types/a-new.type.json"));
                zos.write(NEW_TYPE.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry(".nemaki-types/b-existing.type.json"));
                zos.write("{\"id\":\"cmis:document\",\"baseId\":\"cmis:document\"}".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("doc.txt"));
            zos.write("a document".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return assertDoesNotThrow(() -> new ZipImporter().importCustomFormat(REPO, "target", zip, importer), "importing the ZIP");
    }

    private void assertNoTypeTouchedAndEachTypeEntryAnError(ImportResult result, String why) {
        verifyNoInteractions(types);
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not imported") && e.contains("a-new.type.json")),
                "the new type's entry in the errors when " + why + ": " + result.errors);
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not imported") && e.contains("b-existing.type.json")),
                "the existing type's entry in the errors when " + why + ": " + result.errors);
    }

    @Test
    @DisplayName("a non-administrator's import makes no type and looks up none; the type entries are errors and the document is imported")
    void aNonAdministratorMakesNoType(@TempDir Path dir) throws Exception {
        anAdministratorIs(false);
        ImportResult result = importZip(dir, true);
        assertNoTypeTouchedAndEachTypeEntryAnError(result, "the importer is not an administrator");
        assertEquals(2, result.errors.stream().filter(e -> e.contains("administrator only")).count(),
                "the refusal says why — not that it could not be asked: " + result.errors);
        assertEquals(1, result.documentsCreated, "the document imported beside the refused types: " + result.errors);
    }

    @Test
    @DisplayName("a question that cannot be asked makes no type either — no exception service, a failure")
    void aQuestionThatCannotBeAskedMakesNoType(@TempDir Path dir) throws Exception {
        doAnswer(call -> {
            throw new IllegalStateException("the user could not be read");
        }).when(exceptions).perimissionAdmin(any(CallContext.class), eq(REPO));
        assertNoTypeTouchedAndEachTypeEntryAnError(importZip(dir, true), "whether the importer is an administrator cannot be read");
        when(context.getBean("ExceptionService", ExceptionService.class))
                .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("ExceptionService"));
        assertNoTypeTouchedAndEachTypeEntryAnError(importZip(dir, true), "there is no exception service");
    }

    @Test
    @DisplayName("the question is createType's own, asked as the importer, once")
    void theQuestionIsAskedAsTheImporter(@TempDir Path dir) throws Exception {
        anAdministratorIs(false);
        importZip(dir, true);
        assertEquals(1, askedAbout.size(), "how many times it was asked");
        assertSame(importer, askedAbout.get(0), "whom it was asked about");
    }

    @Test
    @DisplayName("an administrator's import makes the types as before")
    void anAdministratorMakesTheTypes(@TempDir Path dir) throws Exception {
        anAdministratorIs(true);
        ImportResult result = importZip(dir, true);
        org.mockito.ArgumentCaptor<NemakiTypeDefinition> made = org.mockito.ArgumentCaptor.forClass(NemakiTypeDefinition.class);
        verify(types).createTypeDefinition(eq(REPO), made.capture());
        assertEquals("t:new", made.getValue().getTypeId(), "the type made — the new one; the existing one is skipped");
        assertTrue(result.errors.stream().noneMatch(e -> e.startsWith("Not imported")), "the errors of an administrator's import: " + result.errors);
    }

    @Test
    @DisplayName("a ZIP with no type entry does not ask")
    void aZipWithNoTypeEntryDoesNotAsk(@TempDir Path dir) throws Exception {
        anAdministratorIs(false);
        ImportResult result = importZip(dir, false);
        verify(exceptions, never()).perimissionAdmin(any(CallContext.class), anyString());
        assertEquals(1, result.documentsCreated, "the document imported: " + result.errors);
    }
}
