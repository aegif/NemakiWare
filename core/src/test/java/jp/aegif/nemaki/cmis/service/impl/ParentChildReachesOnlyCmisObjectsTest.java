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
package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.dao.impl.couch.ContentDaoServiceImpl;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.WebAuthnCredential;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;
import jp.aegif.nemaki.rest.importexport.ZipImporter;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * A parentChild relationship reaches only CMIS objects (R290 — 3.4.2). {@code deleteObject} answers an internal row — a
 * version series, an attachment, a passkey — as an id that does not exist (R281), but the parentChild cascade inside
 * {@code deleteObjectInternal} read each child by {@code getContent} and deleted it without asking: a folder with a parentChild
 * relationship to a document's content stream id took the attachment row with it, and the document was left pointing at a
 * row that was gone — also when the folder's own delete was then refused (422) for not being empty.
 *
 * <p>The cascade skips a child that is not a CMIS object; the folder's delete is not refused for it, and a child that is a CMIS
 * object is deleted with the folder as before. A relationship to an internal row is not saved: CMIS {@code createRelationship}
 * answers an end that is an internal row as an id that does not exist, before anything is checked, and so does the ZIP import,
 * which saves relationships through {@code ContentService} rather than through {@code createRelationship}.
 *
 * <p>The delete runs the product's {@code ObjectServiceImpl}, {@code ObjectServiceInternalImpl} and {@code ExceptionServiceImpl}
 * as an administrator, over a {@code ContentService} that records what it is asked to delete; the internal rows are the ones the
 * product's DAO reads back from the shapes stored in CouchDB. The relationship already stored is given from the start
 * ({@code getParentChildChildIds}), as a store that holds one from before this fix answers it.
 */
class ParentChildReachesOnlyCmisObjectsTest {

    private static final String REPO = "bedroom";

    private static final String ROOT = "R290-ROOT";

    private static final String PASSKEY = "nemaki:webauthnCredential";

    @AfterEach
    void unhookSpringContext() {
        new SpringContext().setApplicationContext(null);
    }

    // ----- internal rows, as the product's DAO reads them back -----

    private static ContentDaoServiceImpl daoOn(CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    private static Content readByTheDao(String id, Map<String, Object> fields) {
        com.ibm.cloud.cloudant.v1.model.Document doc = new com.ibm.cloud.cloudant.v1.model.Document();
        doc.setId(id);
        doc.setRev("1-r290");
        fields.forEach(doc::put);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(id)).thenReturn(doc);
        Content content = daoOn(client).getContent(REPO, id);
        assertNotNull(content, "the DAO read nothing for the stored " + fields);
        return content;
    }

    private static Map<String, Object> aStoredNode(String type) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", type);
        fields.put("creator", "admin");
        fields.put("created", 1787177957568L);
        fields.put("modifier", "admin");
        fields.put("modified", 1787177957865L);
        fields.put("document", false);
        fields.put("folder", false);
        fields.put("relationship", false);
        fields.put("policy", false);
        fields.put("content", false);
        fields.put("attachment", false);
        return fields;
    }

    private static Content aVersionSeriesRow() {
        Map<String, Object> fields = aStoredNode("versionSeries");
        fields.put("versionSeriesCheckedOut", false);
        Content series = readByTheDao("R290-SERIES", fields);
        assertEquals("versionSeries", series.getType(), "the DAO read a version series row with another type");
        return series;
    }

    private static Content anAttachmentRow() {
        Map<String, Object> fields = aStoredNode("attachment");
        fields.put("attachment", true);
        fields.put("name", "r290-scan.pdf");
        fields.put("length", 36L);
        fields.put("actualLength", 36L);
        fields.put("mimeType", "application/pdf");
        fields.put("actualMimeType", "application/pdf");
        Content stream = readByTheDao("R290-STREAM", fields);
        assertEquals("attachment", stream.getType(), "the DAO read an attachment row with another type");
        return stream;
    }

    @SuppressWarnings("unchecked")
    private static Content aStoredPasskey() {
        WebAuthnCredential credential = new WebAuthnCredential(new NodeBase());
        credential.setUserId("alice");
        credential.setCredentialId("R290-CREDENTIAL");
        credential.setPublicKeyCose("pQECAyYgASFYIA");
        credential.setSignCount(3L);
        credential.setAaguid("00000000000000000000000000000000");
        credential.setDisplayName("alice's r290 key");
        credential.setDiscoverable(true);
        credential.setCreatedAt(new GregorianCalendar());
        credential.setAccountId("ALICE-ACCOUNT");
        credential.setCredentialGeneration("1");
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        daoOn(client).createWebAuthnCredential(REPO, credential);
        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(client).create(written.capture());
        Map<String, Object> fields = ObjectMapperFactory.createCouchdbObjectMapper().convertValue(written.getValue(), Map.class);
        fields.remove("_id");
        fields.remove("_rev");
        Content passkey = readByTheDao("R290-PASSKEY", fields);
        assertEquals(PASSKEY, passkey.getObjectType(), "the DAO no longer reads a passkey's objectType");
        return passkey;
    }

    private static Map<String, Content> theInternalRows() {
        Map<String, Content> rows = new LinkedHashMap<>();
        rows.put("a version series", aVersionSeriesRow());
        rows.put("an attachment", anAttachmentRow());
        rows.put("a passkey", aStoredPasskey());
        return rows;
    }

    private static Content aCmisObject(String id, String baseType, String objectType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", baseType);
        fields.put("objectType", objectType);
        fields.put("name", "r290-" + id);
        Content content = readByTheDao(id, fields);
        assertEquals(baseType, content.getType(), baseType + " was read back with another type");
        return content;
    }

    /** A user as it is stored — an item with objectType nemaki:user — read back by the DAO. */
    private static Content aStoredUser() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "cmis:item");
        fields.put("objectType", "nemaki:user");
        fields.put("name", "r290-user");
        fields.put("userId", "r290-user");
        fields.put("passwordHash", "$2a$10$r290r290r290r290r290r.r290r290r290r290r290r290r290r29");
        fields.put("admin", false);
        Content user = readByTheDao("R290-USER", fields);
        assertEquals("nemaki:user", user.getObjectType(), "a user was read back with another objectType");
        return user;
    }

    /** The CMIS objects other than a document and a folder that a relationship may join, each read back by the DAO. */
    private static Map<String, Content> theOtherCmisObjects() {
        Map<String, Content> objects = new LinkedHashMap<>();
        objects.put("an item", aCmisObject("R290-ITEM", "cmis:item", "cmis:item"));
        objects.put("a user's item", aStoredUser());
        objects.put("a custom item", aCmisObject("R290-CUSTOM", "cmis:item", "r290:invoice"));
        objects.put("an item whose objectType resembles a passkey's", aCmisObject("R290-NEAR", "cmis:item",
                "nemaki:webauthnCredentials"));
        objects.put("a relationship", aCmisObject("R290-RELOBJ", "cmis:relationship", "cmis:relationship"));
        objects.put("a policy", aCmisObject("R290-POL", "cmis:policy", "cmis:policy"));
        objects.put("another document", aCmisObject("R290-DOC2", "cmis:document", "cmis:document"));
        return objects;
    }

    private static Folder aFolder(String id) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setType("cmis:folder");
        folder.setObjectType("cmis:folder");
        folder.setName(id);
        folder.setParentId(ROOT);
        return folder;
    }

    private static Document aDocument(String id) {
        Document document = new Document();
        document.setId(id);
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName(id + ".txt");
        document.setLatestVersion(true);
        return document;
    }

    // ----- the delete, with the product's cascade -----

    /** The product's delete as an administrator, over a content service that records what it is asked to delete. */
    private static final class Deleting {
        final ContentService contentService = mock(ContentService.class);
        final CallContext admin = mock(CallContext.class);
        final ObjectServiceImpl objects = new ObjectServiceImpl();
        final ObjectServiceInternalImpl internal = new ObjectServiceInternalImpl();

        Deleting() {
            RepositoryInfo info = new RepositoryInfo();
            info.setId(REPO);
            info.setRootFolder(ROOT);
            RepositoryInfoMap infoMap = new RepositoryInfoMap();
            infoMap.add(info);
            ExceptionServiceImpl exceptions = new ExceptionServiceImpl();
            exceptions.setContentService(contentService);
            exceptions.setRepositoryInfoMap(infoMap);
            ThreadLockService locks = mock(ThreadLockService.class);
            when(locks.getWriteLock(anyString(), anyString())).thenAnswer(invocation -> new ReentrantLock());
            when(locks.getReadLock(anyString(), anyString())).thenAnswer(invocation -> new ReentrantLock());
            NemakiCachePool cache = mock(NemakiCachePool.class, Answers.RETURNS_DEEP_STUBS);
            internal.setContentService(contentService);
            internal.setExceptionService(exceptions);
            internal.setThreadLockService(locks);
            internal.setNemakiCachePool(cache);
            objects.setContentService(contentService);
            objects.setExceptionService(exceptions);
            objects.setObjectServiceInternal(internal);
            objects.setThreadLockService(locks);
            objects.setNemakiCachePool(cache);
            objects.setSolrUtil(mock(SolrUtil.class));
            when(admin.getUsername()).thenReturn("admin");
            when(contentService.getUserItemById(REPO, "admin"))
                    .thenReturn(new UserItem("admin-item", "nemaki:user", "admin", "admin", null, true, null));
            when(contentService.getChildren(eq(REPO), anyString())).thenReturn(new ArrayList<>());
            when(contentService.getParentChildChildIds(eq(REPO), anyString())).thenReturn(new ArrayList<>());
        }

        Deleting stores(Content content) {
            when(contentService.getContent(REPO, content.getId())).thenReturn(content);
            return this;
        }

        /** A parentChild relationship from {@code parent} to each of {@code children}, stored from before. */
        Deleting parentChild(String parent, List<String> children) {
            when(contentService.getParentChildChildIds(REPO, parent)).thenReturn(new ArrayList<>(children));
            return this;
        }

        /** {@code folder} has a filed child, so deleting it alone is refused. */
        Deleting filedChildIn(String folder) {
            List<Content> filed = new ArrayList<>();
            filed.add(aDocument("R290-FILED"));
            when(contentService.getChildren(REPO, folder)).thenReturn(filed);
            return this;
        }

        void wasNotDeleted(String id, String who) {
            verify(contentService, never().description(who + ": the row was deleted")).delete(any(), any(), eq(id), any());
            verify(contentService, never().description(who + ": the row was deleted as a document")).deleteDocument(any(), any(),
                    eq(id), any(), any());
            verify(contentService, never().description(who + ": the row was deleted as a user")).deleteUserItem(any(), any());
        }
    }

    @Test
    void aFoldersDeleteLeavesItsInternalParentChildChildren() {
        theInternalRows().forEach((what, row) -> {
            Deleting d = new Deleting().stores(aFolder("R290-FOLDER")).stores(row)
                    .parentChild("R290-FOLDER", List.of(row.getId()));
            assertDoesNotThrow(() -> d.objects.deleteObject(d.admin, REPO, "R290-FOLDER", true, null),
                    "the folder's delete was refused for its parentChild child, " + what);
            verify(d.contentService, description("the folder itself was not deleted")).delete(any(), eq(REPO), eq("R290-FOLDER"),
                    any());
            d.wasNotDeleted(row.getId(), what + ", the parentChild child of a folder that was deleted");
        });
    }

    @Test
    void aFoldersRefusedDeleteLeavesItsInternalParentChildChildren() {
        theInternalRows().forEach((what, row) -> {
            Deleting d = new Deleting().stores(aFolder("R290-FOLDER")).stores(row)
                    .parentChild("R290-FOLDER", List.of(row.getId())).filedChildIn("R290-FOLDER");
            Throwable thrown = assertThrows(Throwable.class,
                    () -> d.objects.deleteObject(d.admin, REPO, "R290-FOLDER", true, null), "a folder with a child was deleted");
            assertTrue(thrown instanceof CmisConstraintException, "the folder's delete was not refused for its child: " + thrown);
            verify(d.contentService, never()).delete(any(), eq(REPO), eq("R290-FOLDER"), any());
            d.wasNotDeleted(row.getId(), what + ", the parentChild child of a folder whose delete was refused");
        });
    }

    @Test
    void aCmisChildIsDeletedWithItsFolderAsBefore() {
        Deleting d = new Deleting().stores(aFolder("R290-FOLDER")).stores(aDocument("R290-CHILD"))
                .stores(aFolder("R290-SUBFOLDER")).stores(anAttachmentRow())
                .parentChild("R290-FOLDER", List.of("R290-STREAM", "R290-CHILD", "R290-SUBFOLDER"));
        assertDoesNotThrow(() -> d.objects.deleteObject(d.admin, REPO, "R290-FOLDER", true, null));
        verify(d.contentService, times(1).description("a document child was not deleted with its folder"))
                .deleteDocument(any(), eq(REPO), eq("R290-CHILD"), any(), eq(true));
        verify(d.contentService, times(1).description("a folder child was not deleted with its folder"))
                .delete(any(), eq(REPO), eq("R290-SUBFOLDER"), eq(true));
        verify(d.contentService, times(1)).delete(any(), eq(REPO), eq("R290-FOLDER"), any());
        d.wasNotDeleted("R290-STREAM", "the attachment beside two CMIS children");
    }

    @Test
    void aDirectDeleteOfAnInternalRowOrAnIdThatDoesNotExistIs404AndLeavesTheRow() {
        Map<String, Content> cases = new LinkedHashMap<>(theInternalRows());
        cases.put("an id that does not exist", null);
        cases.forEach((what, row) -> {
            Deleting d = new Deleting();
            String id = row == null ? "R290-GONE" : row.getId();
            if (row != null) {
                d.stores(row);
            }
            Throwable thrown = assertThrows(Throwable.class, () -> d.objects.deleteObject(d.admin, REPO, id, true, null),
                    what + " was deleted");
            assertTrue(thrown instanceof CmisObjectNotFoundException, what + " was answered as " + thrown);
            assertEquals(BigInteger.valueOf(404), ((CmisObjectNotFoundException) thrown).getCode(), what);
            d.wasNotDeleted(id, "the direct delete of " + what);
        });
    }

    // ----- CMIS createRelationship -----

    /** The product's createRelationship as an administrator; the checks a relationship's ends pass are let through. */
    private static final class Relating {
        final ContentService contentService = mock(ContentService.class);
        final ExceptionServiceImpl exceptions = spy(new ExceptionServiceImpl());
        final CallContext admin = mock(CallContext.class);
        final ObjectServiceImpl objects = new ObjectServiceImpl();

        Relating() {
            TypeManager types = mock(TypeManager.class);
            when(types.getTypeDefinition(eq(REPO), anyString())).thenReturn(mock(RelationshipTypeDefinition.class));
            doNothing().when(exceptions).constraintAllowedSourceTypes(any(), any());
            doNothing().when(exceptions).constraintAllowedTargetTypes(any(), any());
            doNothing().when(exceptions).permissionDenied(any(), any(), any(), any());
            doNothing().when(exceptions).constraintBaseTypeId(any(), any(), any());
            doNothing().when(exceptions).constraintPropertyValue(any(), any(), any(), any());
            doNothing().when(exceptions).constraintCotrollablePolicies(any(), any(), any());
            doNothing().when(exceptions).constraintCotrollableAcl(any(), any(), any(), any());
            doNothing().when(exceptions).constraintPermissionDefined(any(), any(), any());
            doNothing().when(exceptions).nameConstraintViolation(any(), (jp.aegif.nemaki.model.Folder) any(), any(Properties.class));
            objects.setContentService(contentService);
            objects.setExceptionService(exceptions);
            objects.setTypeManager(types);
            objects.setNemakiCachePool(mock(NemakiCachePool.class, Answers.RETURNS_DEEP_STUBS));
            Relationship saved = new Relationship();
            saved.setId("R290-REL");
            when(contentService.createRelationship(any(), eq(REPO), any(), any(), any(), any(), any())).thenReturn(saved);
        }

        Relating stores(Content content) {
            when(contentService.getContent(REPO, content.getId())).thenReturn(content);
            return this;
        }

        String create(String sourceId, String targetId) {
            PropertiesImpl properties = new PropertiesImpl();
            properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "nemaki:parentChildRelationship"));
            properties.addProperty(new PropertyIdImpl(PropertyIds.SOURCE_ID, sourceId));
            properties.addProperty(new PropertyIdImpl(PropertyIds.TARGET_ID, targetId));
            return objects.createRelationship(admin, REPO, properties, null, null, null, null);
        }
    }

    private static String theAnswerForAnIdThatDoesNotExist(String id) {
        return assertThrows(CmisObjectNotFoundException.class,
                () -> new ExceptionServiceImpl().objectNotFound(DomainType.OBJECT, null, id)).getMessage();
    }

    @Test
    void aRelationshipToOrFromAnInternalRowOrAnIdThatDoesNotExistIsNotSaved() {
        Map<String, Content> cases = new LinkedHashMap<>(theInternalRows());
        cases.put("an id that does not exist", null);
        cases.forEach((what, row) -> {
            String id = row == null ? "R290-GONE" : row.getId();
            for (boolean asTheTarget : new boolean[] { true, false }) {
                Relating r = new Relating().stores(aFolder("R290-FOLDER"));
                if (row != null) {
                    r.stores(row);
                }
                String who = what + (asTheTarget ? " as the target" : " as the source");
                Throwable thrown = assertThrows(Throwable.class,
                        () -> r.create(asTheTarget ? "R290-FOLDER" : id, asTheTarget ? id : "R290-FOLDER"), who + " was saved");
                assertTrue(thrown instanceof CmisObjectNotFoundException, who + " was answered as " + thrown);
                assertEquals(theAnswerForAnIdThatDoesNotExist(id), thrown.getMessage(),
                        who + " is not answered as an id that does not exist");
                verify(r.contentService, never().description(who + ": the relationship was saved")).createRelationship(any(), any(),
                        any(), any(), any(), any(), any());
                if (row != null) {
                    verify(r.exceptions, never().description(who + ": the row was checked as an end")).permissionDenied(any(),
                            any(), any(), eq(row));
                }
            }
        });
    }

    @Test
    void aRelationshipBetweenADocumentAndAFolderIsSavedAsBefore() {
        Relating r = new Relating().stores(aFolder("R290-FOLDER")).stores(aDocument("R290-DOC"));
        assertEquals("R290-REL", assertDoesNotThrow(() -> r.create("R290-FOLDER", "R290-DOC"),
                "a relationship from a folder to a document was refused"), "the relationship was not answered");
        Relating reverse = new Relating().stores(aFolder("R290-FOLDER")).stores(aDocument("R290-DOC"));
        assertEquals("R290-REL", assertDoesNotThrow(() -> reverse.create("R290-DOC", "R290-FOLDER"),
                "a relationship from a document to a folder was refused"), "the reverse relationship was not answered");
        verify(r.contentService, times(1)).createRelationship(any(), eq(REPO), any(), any(), any(), any(), any());
    }

    @Test
    void aRelationshipBetweenAnyTwoCmisObjectsIsSavedAsBefore() {
        theOtherCmisObjects().forEach((what, object) -> {
            for (boolean asTheTarget : new boolean[] { true, false }) {
                Relating r = new Relating().stores(aDocument("R290-DOC")).stores(object);
                String who = "a relationship with " + what + (asTheTarget ? " as the target" : " as the source");
                assertEquals("R290-REL", assertDoesNotThrow(() -> asTheTarget ? r.create("R290-DOC", object.getId())
                        : r.create(object.getId(), "R290-DOC"), who + " was refused"), who + " was not answered");
                verify(r.contentService, times(1).description(who + " was not saved")).createRelationship(any(), eq(REPO), any(),
                        any(), any(), any(), any());
            }
        });
    }

    /** A read that failed. Neither an assertion nor an absence. */
    private static final class TheReadFailed extends Error {
        private static final long serialVersionUID = 1L;

        TheReadFailed() {
            super("r290: the read failed");
        }
    }

    @Test
    void aReadOfAnEndThatFailedIsThrownAsItIs() {
        for (Throwable failure : List.of(new IllegalStateException("r290: CouchDB did not answer"), new TheReadFailed())) {
            Relating r = new Relating().stores(aFolder("R290-FOLDER"));
            when(r.contentService.getContent(REPO, "R290-UNREAD")).thenThrow(failure);
            Throwable thrown = assertThrows(Throwable.class, () -> r.create("R290-FOLDER", "R290-UNREAD"));
            assertSame(failure, thrown, "a read that failed was answered as " + thrown);
            verify(r.contentService, never()).createRelationship(any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ----- the ZIP import, which saves relationships through ContentService -----

    @SuppressWarnings("unchecked")
    private static File aZipWithRelationships(Path dir, List<String[]> ends) throws Exception {
        File zip = dir.resolve("r290.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            int n = 0;
            for (String[] end : ends) {
                JSONObject rel = new JSONObject();
                rel.put("objectType", "nemaki:parentChildRelationship");
                rel.put("sourceId", end[0]);
                rel.put("targetId", end[1]);
                zos.putNextEntry(new ZipEntry(ImportExportUtils.RELATIONSHIPS_DIR + "r" + (n++) + ImportExportUtils.RELATIONSHIP_SUFFIX));
                zos.write(rel.toJSONString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    private static ContentService aContentServiceTheImportReaches() {
        ContentService cs = mock(ContentService.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean("ContentService", ContentService.class)).thenReturn(cs);
        new SpringContext().setApplicationContext(ctx);
        when(cs.getFolder(REPO, "R290-TARGET")).thenReturn(aFolder("R290-TARGET"));
        when(cs.getChildren(eq(REPO), anyString())).thenReturn(new ArrayList<>());
        Relationship saved = new Relationship();
        saved.setId("R290-IMPORTED");
        when(cs.createRelationship(any(), eq(REPO), any(Properties.class), any(), any(), any(), any())).thenReturn(saved);
        return cs;
    }

    @Test
    void theZipImportDoesNotSaveARelationshipToAnInternalRowOrAnIdThatDoesNotExist(@TempDir Path dir) throws Exception {
        Map<String, Content> cases = new LinkedHashMap<>(theInternalRows());
        cases.put("an id that does not exist", null);
        for (Map.Entry<String, Content> c : cases.entrySet()) {
            String what = c.getKey();
            String id = c.getValue() == null ? "R290-GONE" : c.getValue().getId();
            for (boolean asTheTarget : new boolean[] { true, false }) {
                ContentService cs = aContentServiceTheImportReaches();
                when(cs.getContent(REPO, "R290-FOLDER")).thenReturn(aFolder("R290-FOLDER"));
                if (c.getValue() != null) {
                    when(cs.getContent(REPO, id)).thenReturn(c.getValue());
                }
                String who = "the ZIP import of a relationship with " + what + (asTheTarget ? " as the target" : " as the source");
                File zip = aZipWithRelationships(dir, List.<String[]>of(asTheTarget ? new String[] { "R290-FOLDER", id }
                        : new String[] { id, "R290-FOLDER" }));
                ImportResult result = new ZipImporter().importCustomFormat(REPO, "R290-TARGET", zip, mock(CallContext.class));
                verify(cs, never().description(who + ": the relationship was saved")).createRelationship(any(), any(),
                        any(Properties.class), any(), any(), any(), any());
                assertEquals(0, result.relationshipsCreated, who + ": " + result.warnings);
                String expected = theAnswerForAnIdThatDoesNotExist(id);
                assertTrue(result.warnings.stream().anyMatch(w -> w.contains(expected)),
                        who + ": not refused as an id that does not exist: " + result.warnings);
            }
        }
    }

    @Test
    void theZipImportSavesARelationshipBetweenCmisObjectsAsBefore(@TempDir Path dir) throws Exception {
        ContentService cs = aContentServiceTheImportReaches();
        when(cs.getContent(REPO, "R290-FOLDER")).thenReturn(aFolder("R290-FOLDER"));
        when(cs.getContent(REPO, "R290-DOC")).thenReturn(aDocument("R290-DOC"));
        ImportResult result = new ZipImporter().importCustomFormat(REPO, "R290-TARGET",
                aZipWithRelationships(dir, List.<String[]>of(new String[] { "R290-FOLDER", "R290-DOC" })), mock(CallContext.class));
        assertEquals(1, result.relationshipsCreated, "a relationship between a folder and a document was not imported: "
                + result.warnings);
        verify(cs, times(1)).createRelationship(any(), eq(REPO),
                argThat((Properties p) -> "R290-DOC".equals(p.getProperties().get(PropertyIds.TARGET_ID).getFirstValue())), any(),
                any(), any(), any());
    }

    @Test
    void theZipImportSavesARelationshipBetweenAnyTwoCmisObjectsAsBefore(@TempDir Path dir) throws Exception {
        for (Map.Entry<String, Content> c : theOtherCmisObjects().entrySet()) {
            for (boolean asTheTarget : new boolean[] { true, false }) {
                ContentService cs = aContentServiceTheImportReaches();
                when(cs.getContent(REPO, "R290-DOC")).thenReturn(aDocument("R290-DOC"));
                when(cs.getContent(REPO, c.getValue().getId())).thenReturn(c.getValue());
                String id = c.getValue().getId();
                String who = "the ZIP import of a relationship with " + c.getKey() + (asTheTarget ? " as the target" : " as the source");
                ImportResult result = new ZipImporter().importCustomFormat(REPO, "R290-TARGET",
                        aZipWithRelationships(dir, List.<String[]>of(asTheTarget ? new String[] { "R290-DOC", id }
                                : new String[] { id, "R290-DOC" })), mock(CallContext.class));
                assertEquals(1, result.relationshipsCreated, who + " was not imported: " + result.warnings);
                verify(cs, times(1)).createRelationship(any(), eq(REPO), any(Properties.class), any(), any(), any(), any());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theZipImportChecksTheEndsItMapped(@TempDir Path dir) throws Exception {
        // An end the archive also carries is imported under a new id, and the relationship joins the new id: the check reads
        // that one, not the archive's.
        ContentService cs = aContentServiceTheImportReaches();
        Document created = aDocument("R290-NEW-DOC");
        when(cs.createDocument(any(), eq(REPO), any(Properties.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);
        when(cs.getContent(REPO, "R290-NEW-DOC")).thenReturn(created);
        when(cs.getContent(REPO, "R290-FOLDER")).thenReturn(aFolder("R290-FOLDER"));
        File zip = dir.resolve("r290-mapped.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("r290-doc.txt"));
            zos.write("r290".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            JSONObject props = new JSONObject();
            props.put(PropertyIds.OBJECT_ID, "R290-OLD-DOC");
            JSONObject meta = new JSONObject();
            meta.put("properties", props);
            zos.putNextEntry(new ZipEntry("r290-doc.txt" + ImportExportUtils.META_SUFFIX));
            zos.write(meta.toJSONString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            JSONObject rel = new JSONObject();
            rel.put("objectType", "nemaki:parentChildRelationship");
            rel.put("sourceId", "R290-OLD-DOC");
            rel.put("targetId", "R290-FOLDER");
            zos.putNextEntry(new ZipEntry(ImportExportUtils.RELATIONSHIPS_DIR + "mapped" + ImportExportUtils.RELATIONSHIP_SUFFIX));
            zos.write(rel.toJSONString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        ImportResult result = new ZipImporter().importCustomFormat(REPO, "R290-TARGET", zip, mock(CallContext.class));
        assertEquals(1, result.documentsCreated, "the archive's document was not imported, so this lock measures nothing: "
                + result.errors + " " + result.warnings);
        assertEquals(1, result.relationshipsCreated, "a relationship from a document the archive carries was not imported: "
                + result.warnings);
        verify(cs, times(1)).createRelationship(any(), eq(REPO),
                argThat((Properties p) -> "R290-NEW-DOC".equals(p.getProperties().get(PropertyIds.SOURCE_ID).getFirstValue())),
                any(), any(), any(), any());
    }

    private static org.mockito.verification.VerificationMode description(String what) {
        return times(1).description(what);
    }
}
