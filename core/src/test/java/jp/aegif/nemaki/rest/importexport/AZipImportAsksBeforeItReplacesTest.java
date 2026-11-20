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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.service.ObjectServiceInternal;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * The ZIP import replaces a document of the same name only when the CMIS delete of every version of it would be made, and
 * asks before it creates or deletes anything (R326, 3.4.2).
 *
 * <p>The import's replace created the imported document under a temporary name and deleted the one of the same name —
 * every version of it — by {@code ContentService.deleteDocument}, asking nothing: a non-administrator who may only create
 * in the folder replaced documents it could neither delete nor read, and the working copy another user held of one was
 * left without its version series (measured on nb33).
 *
 * <p>What is locked, on the product's {@code ZipImporter.importCustomFormat}: before it creates or deletes anything for an
 * entry whose name a document in the folder has, it asks the CMIS delete's own service
 * ({@code ObjectServiceInternal.refuseADeleteOfEveryVersion}) about that document, as the importer; a refusal — a
 * permission it does not grant, another user's check-out — creates nothing, deletes nothing and names the entry in the
 * errors; so does a question that cannot be asked (no service, a failure); a delete that may be made replaces the
 * document as before; an entry no document has the name of is not asked about; after a refusal the next entry is
 * imported. The question, the temporary document and the delete are made under the document's write lock — the one a
 * check-out takes (R334) — and the document is read again under it: one that changed (not the latest version any more,
 * renamed, gone) is not replaced, and with no lock service nothing is. The content service, the object service, the
 * exception service and the lock service are stand-ins, handed over as the product looks them up (the Spring context);
 * the question's own lock is {@code ADeleteOfEveryVersionIsAskedAsTheCmisDeleteAsksItTest}, and where a document may be
 * created is {@code AZipImportCreatesOnlyWhereTheImporterMayCreateTest}.
 */
class AZipImportAsksBeforeItReplacesTest {

    private static final String REPO = "bedroom";

    private final ContentService cs = mock(ContentService.class);
    private final ObjectServiceInternal deletes = mock(ObjectServiceInternal.class);
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final ExceptionService exceptions = mock(ExceptionService.class);
    private final ThreadLockService locks = mock(ThreadLockService.class);
    private final java.util.concurrent.locks.ReentrantLock existingsLock = new java.util.concurrent.locks.ReentrantLock();
    private final CallContext importer = mock(CallContext.class);
    private final Document existing = new Document();
    private final Document created = new Document();

    AZipImportAsksBeforeItReplacesTest() {
        when(context.getBean("ContentService", ContentService.class)).thenReturn(cs);
        when(context.getBean("objectServiceInternal", ObjectServiceInternal.class)).thenReturn(deletes);
        when(context.getBean("ExceptionService", ExceptionService.class)).thenReturn(exceptions);
        when(context.getBean("ThreadLockService", ThreadLockService.class)).thenReturn(locks);
        when(locks.getWriteLock(eq(REPO), anyString())).thenAnswer(call -> "the-existing".equals(call.getArgument(1))
                ? existingsLock : new java.util.concurrent.locks.ReentrantLock());
        new SpringContext().setApplicationContext(context);
        Folder target = new Folder();
        target.setId("target");
        when(cs.getFolder(REPO, "target")).thenReturn(target);
        existing.setId("the-existing");
        existing.setName("X.txt");
        existing.setType("cmis:document");
        existing.setObjectType("cmis:document");
        existing.setLatestVersion(true);
        when(cs.getDocument(REPO, "the-existing")).thenReturn(existing);
        created.setId("the-imported");
        created.setName(".importing-X.txt");
        when(cs.createDocument(any(), eq(REPO), any(Properties.class), any(), any(), any(), any(), any(), any())).thenReturn(created);
        when(importer.getUsername()).thenReturn("importer");
    }

    @AfterEach
    void unhookSpringContext() {
        new SpringContext().setApplicationContext(null);
    }

    private File zipWith(Path dir, String... names) throws Exception {
        File zip = dir.resolve("import.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String name : names) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(("imported " + name).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    private ImportResult importInto(Path dir, boolean aDocumentHasTheName) throws Exception {
        when(cs.getChildren(eq(REPO), anyString())).thenReturn(aDocumentHasTheName ? List.of(existing) : List.of());
        File zip = zipWith(dir, "X.txt");
        return assertDoesNotThrow(() -> new ZipImporter().importCustomFormat(REPO, "target", zip, importer), "importing X.txt");
    }

    private void assertNothingCreatedOrDeleted(ImportResult result, String why) {
        verify(cs, never()).createDocument(any(), anyString(), any(Properties.class), any(), any(), any(), any(), any(), any());
        verify(cs, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
        assertEquals(0, result.documentsCreated, "the documents created when " + why);
        assertTrue(result.errors.stream().anyMatch(e -> e.contains("X.txt")), "the errors name the entry when " + why + ": " + result.errors);
    }

    @Test
    @DisplayName("a document the importer may not delete is not replaced: nothing is created or deleted, and the entry is an error")
    void aDocumentTheImporterMayNotDeleteIsNotReplaced(@TempDir Path dir) throws Exception {
        doThrow(new CmisPermissionDeniedException("permission denied")).when(deletes).refuseADeleteOfEveryVersion(any(), anyString(), any());
        ImportResult result = importInto(dir, true);
        assertNothingCreatedOrDeleted(result, "the importer may not delete X.txt");
        assertTrue(!existingsLock.isLocked(), "the existing document's write lock is held after a refusal");
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not replaced")), "the error says it was not replaced: " + result.errors);
    }

    @Test
    @DisplayName("a document another user has checked out is not replaced either")
    void aDocumentAnotherUserHoldsIsNotReplaced(@TempDir Path dir) throws Exception {
        doThrow(new CmisVersioningException("checked out by another user", java.math.BigInteger.valueOf(409)))
                .when(deletes).refuseADeleteOfEveryVersion(any(), anyString(), any());
        ImportResult result = importInto(dir, true);
        assertNothingCreatedOrDeleted(result, "another user has checked out X.txt");
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not replaced")), "the refusal is a refusal: " + result.errors);
        assertTrue(result.errors.stream().noneMatch(e -> e.contains("[objectId=")), "the refusal names the document's id: " + result.errors);
    }

    @Test
    @DisplayName("a question that cannot be answered replaces nothing")
    void aQuestionThatCannotBeAnsweredReplacesNothing(@TempDir Path dir) throws Exception {
        doThrow(new IllegalStateException("the versions could not be read")).when(deletes).refuseADeleteOfEveryVersion(any(), anyString(), any());
        ImportResult result = importInto(dir, true);
        assertNothingCreatedOrDeleted(result, "whether X.txt may be deleted cannot be asked");
    }

    @Test
    @DisplayName("with no object service to ask, nothing is replaced")
    void withNoObjectServiceNothingIsReplaced(@TempDir Path dir) throws Exception {
        when(context.getBean("objectServiceInternal", ObjectServiceInternal.class))
                .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("objectServiceInternal"));
        ImportResult result = importInto(dir, true);
        assertNothingCreatedOrDeleted(result, "there is no object service");
    }

    @Test
    @DisplayName("the existing document is asked about, as the importer, once")
    void theExistingDocumentIsAskedAboutAsTheImporter(@TempDir Path dir) throws Exception {
        importInto(dir, true);
        verify(deletes).refuseADeleteOfEveryVersion(importer, REPO, existing);
    }

    @Test
    @DisplayName("a document the importer may delete is replaced as before")
    void aDocumentTheImporterMayDeleteIsReplacedAsBefore(@TempDir Path dir) throws Exception {
        ImportResult result = importInto(dir, true);
        verify(cs).deleteDocument(importer, REPO, "the-existing", Boolean.TRUE, Boolean.FALSE);
        assertEquals(1, result.documentsCreated, "the documents created: " + result.errors);
        assertEquals(List.of(), result.errors, "the errors of a replace that may be made");
    }

    @Test
    @DisplayName("after a refusal the import goes on: the next entry, which no document has the name of, is created")
    void aRefusalGoesOnToTheNextEntry(@TempDir Path dir) throws Exception {
        doThrow(new CmisPermissionDeniedException("permission denied")).when(deletes).refuseADeleteOfEveryVersion(any(), anyString(), any());
        when(cs.getChildren(eq(REPO), anyString())).thenReturn(List.of(existing));
        File zip = zipWith(dir, "X.txt", "Y.txt");
        ImportResult result = assertDoesNotThrow(() -> new ZipImporter().importCustomFormat(REPO, "target", zip, importer),
                "importing X.txt and Y.txt");
        assertEquals(1, result.documentsCreated, "the documents created — Y.txt: " + result.errors);
        assertTrue(result.errors.stream().anyMatch(e -> e.startsWith("Not replaced") && e.contains("X.txt")),
                "the refusal of X.txt is in the errors: " + result.errors);
        verify(cs, never()).deleteDocument(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the question, the temporary document and the delete are made under the existing document's write lock, which is let go after")
    void theReplaceIsMadeUnderTheDocumentsWriteLock(@TempDir Path dir) throws Exception {
        List<String> held = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> {
            held.add("asked " + existingsLock.isHeldByCurrentThread());
            return null;
        }).when(deletes).refuseADeleteOfEveryVersion(any(), anyString(), any());
        when(cs.getDocument(REPO, "the-existing")).thenAnswer(call -> {
            held.add("read " + existingsLock.isHeldByCurrentThread());
            return existing;
        });
        when(cs.createDocument(any(), eq(REPO), any(Properties.class), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            held.add("created " + existingsLock.isHeldByCurrentThread());
            return created;
        });
        org.mockito.Mockito.doAnswer(call -> {
            held.add("deleted " + existingsLock.isHeldByCurrentThread());
            return null;
        }).when(cs).deleteDocument(any(), eq(REPO), eq("the-existing"), any(), any());
        ImportResult result = importInto(dir, true);
        assertEquals(List.of("read true", "asked true", "created true", "deleted true"), held,
                "what was done, and whether the existing document's write lock was held: " + result.errors);
        assertTrue(!existingsLock.isLocked(), "the write lock is held after the import");
    }

    @Test
    @DisplayName("a document that changed before its lock was taken — not the latest version, renamed, gone — is not replaced")
    void aDocumentThatChangedIsNotReplaced(@TempDir Path dir) throws Exception {
        Document older = new Document();
        older.setId("the-existing");
        older.setName("X.txt");
        older.setLatestVersion(false);
        Document renamed = new Document();
        renamed.setId("the-existing");
        renamed.setName("Y.txt");
        renamed.setLatestVersion(true);
        for (Document now : new Document[] {older, renamed, null}) {
            org.mockito.Mockito.clearInvocations(cs, deletes);
            when(cs.getDocument(REPO, "the-existing")).thenReturn(now);
            ImportResult result = importInto(dir, true);
            assertNothingCreatedOrDeleted(result, "X.txt changed into " + (now == null ? "nothing" : now.getName()));
            verify(deletes, never()).refuseADeleteOfEveryVersion(any(), anyString(), any());
        }
    }

    @Test
    @DisplayName("with no lock service, nothing is replaced")
    void withNoLockServiceNothingIsReplaced(@TempDir Path dir) throws Exception {
        when(context.getBean("ThreadLockService", ThreadLockService.class))
                .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("ThreadLockService"));
        ImportResult result = importInto(dir, true);
        assertNothingCreatedOrDeleted(result, "there is no lock service");
    }

    @Test
    @DisplayName("an entry no document has the name of is not asked about, and is created")
    void aNewNameIsNotAskedAbout(@TempDir Path dir) throws Exception {
        ImportResult result = importInto(dir, false);
        verify(deletes, never()).refuseADeleteOfEveryVersion(any(), anyString(), any());
        assertEquals(1, result.documentsCreated, "the documents created: " + result.errors);
    }
}
