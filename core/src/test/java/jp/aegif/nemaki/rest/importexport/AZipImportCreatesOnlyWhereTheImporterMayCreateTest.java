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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.service.ObjectServiceInternal;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * A ZIP import creates a document or a folder only where the CMIS create would, asked of the folder it is created in
 * (R333, 3.4.2).
 *
 * <p>The import asked whether the importer may create in the target folder, and nothing more. A path in the ZIP that
 * named a folder already there put the document in it: a non-administrator who may neither read nor create in a subfolder
 * of the target created a document in it with one import, while the Browser binding's create there answered 403
 * (measured on nb33).
 *
 * <p>What is locked, on the product's {@code ZipImporter.importCustomFormat} and {@code importAcpFormat}: before a document
 * is created the CMIS create's question ({@code CAN_CREATE_DOCUMENT_FOLDER}) is asked, as the importer, of the folder it is
 * created in — a subfolder that was there already, one this import resolved for an earlier entry, the target — for every
 * entry, the replace's temporary document too, always about the importer's own context; before a folder is created,
 * {@code CAN_CREATE_FOLDER_FOLDER} of its parent;
 * a refusal creates nothing for the entry (nor what is under a refused ACP folder) and names it in the errors; a question
 * that cannot be asked — no exception service, a folder that cannot be read — creates nothing either. Whether the importer
 * may create is a stand-in that refuses what it is told to and records what it was asked; the content service and the
 * other services are stand-ins handed over as the product looks them up (the Spring context).
 */
class AZipImportCreatesOnlyWhereTheImporterMayCreateTest {

    private static final String REPO = "bedroom";

    private final ContentService cs = mock(ContentService.class);
    private final ExceptionService exceptions = mock(ExceptionService.class);
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final CallContext importer = mock(CallContext.class);
    /** "key folder-id" the stand-in refuses, and every one it was asked. */
    private final Set<String> refused = new HashSet<>();
    private final List<String> asked = new ArrayList<>();
    /** Whom each question was asked about — the importer's own context, not one the import made up. */
    private final List<CallContext> askedAs = new ArrayList<>();
    private final Folder target = folder("target", "target");
    private final Folder secret = folder("secret", "SECRET");
    private final Folder made = folder("made", "MADE");
    private final Document created = new Document();
    private final Document existing = new Document();

    AZipImportCreatesOnlyWhereTheImporterMayCreateTest() {
        when(context.getBean("ContentService", ContentService.class)).thenReturn(cs);
        when(context.getBean("ExceptionService", ExceptionService.class)).thenReturn(exceptions);
        when(context.getBean("objectServiceInternal", ObjectServiceInternal.class)).thenReturn(mock(ObjectServiceInternal.class));
        ThreadLockService locks = mock(ThreadLockService.class);
        when(locks.getWriteLock(eq(REPO), anyString())).thenAnswer(call -> new java.util.concurrent.locks.ReentrantLock());
        when(context.getBean("ThreadLockService", ThreadLockService.class)).thenReturn(locks);
        new SpringContext().setApplicationContext(context);
        doAnswer(call -> {
            String key = call.getArgument(2);
            Content where = call.getArgument(3);
            String question = key + " " + where.getId();
            asked.add(question);
            askedAs.add(call.getArgument(0));
            if (refused.contains(question)) {
                throw new CmisPermissionDeniedException("Permission Denied!");
            }
            return null;
        }).when(exceptions).permissionDenied(any(CallContext.class), eq(REPO), anyString(), any(Content.class));
        for (Folder f : new Folder[] {target, secret, made}) {
            when(cs.getFolder(REPO, f.getId())).thenReturn(f);
        }
        when(cs.getChildren(eq(REPO), anyString())).thenReturn(List.of());
        created.setId("the-imported");
        when(cs.createDocument(any(), eq(REPO), any(Properties.class), any(), any(), any(), any(), any(), any())).thenReturn(created);
        when(cs.createFolder(any(), eq(REPO), any(Properties.class), any(), any(), any(), any(), any())).thenReturn(made);
        existing.setId("the-existing");
        existing.setName("X.txt");
        existing.setLatestVersion(true);
        when(cs.getDocument(REPO, "the-existing")).thenReturn(existing);
        when(importer.getUsername()).thenReturn("importer");
    }

    @AfterEach
    void unhookSpringContext() {
        new SpringContext().setApplicationContext(null);
    }

    private static Folder folder(String id, String name) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setName(name);
        folder.setType("cmis:folder");
        folder.setObjectType("cmis:folder");
        return folder;
    }

    private static String document(String folderId) {
        return PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER + " " + folderId;
    }

    private static String folderIn(String folderId) {
        return PermissionMapping.CAN_CREATE_FOLDER_FOLDER + " " + folderId;
    }

    private ImportResult importZip(Path dir, String... names) throws Exception {
        File zip = dir.resolve("import.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String name : names) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(("imported " + name).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return assertDoesNotThrow(() -> new ZipImporter().importCustomFormat(REPO, "target", zip, importer), "importing " + List.of(names));
    }

    private ImportResult importAcp(Path dir, String xml) throws Exception {
        File zip = dir.resolve("import.acp").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("package.xml"));
            zos.write(xml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return assertDoesNotThrow(() -> new ZipImporter().importAcpFormat(REPO, "target", zip, importer), "importing the ACP");
    }

    private void assertNoDocumentCreated() {
        verify(cs, never()).createDocument(any(), anyString(), any(Properties.class), any(), any(), any(), any(), any(), any());
    }

    private void assertNoFolderCreated() {
        verify(cs, never()).createFolder(any(), anyString(), any(Properties.class), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a document in a subfolder that was there already is asked of that subfolder, and a refusal creates nothing")
    void aDocumentIsAskedOfTheFolderItIsCreatedIn(@TempDir Path dir) throws Exception {
        when(cs.getChildren(REPO, "target")).thenReturn(List.of(secret));
        refused.add(document("secret"));
        ImportResult result = importZip(dir, "SECRET/x.txt");
        assertNoDocumentCreated();
        assertTrue(asked.contains(document("secret")), "what was asked: " + asked);
        assertEquals(0, result.documentsCreated, "the documents created");
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not created") && e.contains("SECRET/x.txt")), "the errors: " + result.errors);
    }

    @Test
    @DisplayName("a folder the path names that is not there is asked of its parent, and a refusal creates neither it nor what is in it")
    void aFolderIsAskedOfItsParent(@TempDir Path dir) throws Exception {
        refused.add(folderIn("target"));
        ImportResult result = importZip(dir, "NEW/x.txt");
        assertNoFolderCreated();
        assertNoDocumentCreated();
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not created") && e.contains("NEW/x.txt")), "the errors: " + result.errors);
    }

    @Test
    @DisplayName("every document is asked, in a folder an earlier entry resolved too")
    void everyDocumentIsAsked(@TempDir Path dir) throws Exception {
        when(cs.getChildren(REPO, "target")).thenReturn(List.of(secret));
        importZip(dir, "SECRET/a.txt", "SECRET/b.txt", "c.txt");
        assertEquals(2, asked.stream().filter(document("secret")::equals).count(), "the questions about SECRET: " + asked);
        assertTrue(asked.contains(document("target")), "the question about the target: " + asked);
        refused.add(document("secret"));
        asked.clear();
        org.mockito.Mockito.clearInvocations(cs);
        ImportResult result = importZip(dir, "SECRET/a.txt", "SECRET/b.txt");
        assertNoDocumentCreated();
        assertEquals(2, result.errors.size(), "the errors: " + result.errors);
    }

    @Test
    @DisplayName("the replace's temporary document is asked too: a refusal creates and deletes nothing")
    void theReplacesTemporaryDocumentIsAsked(@TempDir Path dir) throws Exception {
        when(cs.getChildren(REPO, "target")).thenReturn(List.of(existing));
        refused.add(document("target"));
        ImportResult result = importZip(dir, "X.txt");
        assertNoDocumentCreated();
        verify(cs, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not created") && e.contains("X.txt")), "the errors: " + result.errors);
    }

    @Test
    @DisplayName("a question that cannot be asked creates nothing — no exception service, a folder that cannot be read")
    void aQuestionThatCannotBeAskedCreatesNothing(@TempDir Path dir) throws Exception {
        when(cs.getChildren(REPO, "target")).thenReturn(List.of(secret));
        when(cs.getFolder(REPO, "secret")).thenReturn(null);
        ImportResult unread = importZip(dir, "SECRET/x.txt");
        assertNoDocumentCreated();
        assertTrue(unread.errors.stream().anyMatch(e -> e.contains("SECRET/x.txt")), "the errors: " + unread.errors);
        when(context.getBean("ExceptionService", ExceptionService.class))
                .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("ExceptionService"));
        ImportResult unasked = importZip(dir, "y.txt", "NEW/z.txt");
        assertNoDocumentCreated();
        assertNoFolderCreated();
        assertEquals(2, unasked.errors.size(), "the errors: " + unasked.errors);
    }

    @Test
    @DisplayName("every question is asked about the importer — its own context, the one the import was handed")
    void everyQuestionIsAskedAsTheImporter(@TempDir Path dir) throws Exception {
        when(cs.getChildren(REPO, "target")).thenReturn(List.of(secret, existing));
        importZip(dir, "SECRET/a.txt", "NEW/b.txt", "X.txt");
        importAcp(dir, "<view><folder childName=\"F\"><content childName=\"d.txt\"/></folder></view>");
        assertTrue(askedAs.size() >= 6, "the questions asked: " + asked);
        assertTrue(askedAs.stream().allMatch(c -> c == importer), "a question was asked about another caller than the importer: " + askedAs);
    }

    @Test
    @DisplayName("ACP: a folder is asked of its parent, and a refusal creates neither it nor what is in it")
    void anAcpFolderIsAskedOfItsParent(@TempDir Path dir) throws Exception {
        refused.add(folderIn("target"));
        ImportResult result = importAcp(dir, "<view><folder childName=\"F\"><content childName=\"d.txt\"/></folder></view>");
        assertNoFolderCreated();
        assertNoDocumentCreated();
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not created") && e.contains("F")), "the errors: " + result.errors);
    }

    @Test
    @DisplayName("ACP: a document is asked of the folder it is created in — one this import made — and a refusal creates nothing")
    void anAcpDocumentIsAskedOfTheFolderItIsCreatedIn(@TempDir Path dir) throws Exception {
        refused.add(document("made"));
        ImportResult result = importAcp(dir, "<view><folder childName=\"F\"><content childName=\"d.txt\"/></folder><content childName=\"e.txt\"/></view>");
        verify(cs).createFolder(any(), eq(REPO), any(Properties.class), eq(target), any(), any(), any(), any());
        assertTrue(asked.contains(document("made")), "what was asked: " + asked);
        assertEquals(1, result.documentsCreated, "the documents created — e.txt in the target: " + result.errors);
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not created") && e.contains("d.txt")), "the errors: " + result.errors);
    }
}
