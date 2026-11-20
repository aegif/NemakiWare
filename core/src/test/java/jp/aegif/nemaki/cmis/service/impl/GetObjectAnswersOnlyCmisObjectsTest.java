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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.dao.impl.couch.ContentDaoServiceImpl;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.WebAuthnCredential;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * {@code ObjectServiceImpl.getObject} answers only CMIS objects (R275 and R277 — 3.4.2). {@code ContentService.getContent}
 * answers any CouchDB document by its id, and the documents that are not CMIS objects — a version series, an attachment, a
 * change event, a type definition, a user or a group row — were compiled as {@code cmis:item}: on nb33 a document's version
 * series id answered its version series, and its content stream id the attachment with the file name as its name. Only the
 * five object base types are CMIS objects; any other content is answered with the exception an id that does not exist is
 * answered with, before the permission check and before anything is compiled.
 *
 * <p>A passkey (a WebAuthn credential) is stored with type {@code cmis:item}, so its stored type does not tell it from an item
 * (R277): it is told by its objectType {@code nemaki:webauthnCredential}, compared exactly — every other item, a user and a group
 * item, a custom item and an objectType that only resembles it are answered as before. The passkey here is the one the product
 * writes and reads back: built by the product's {@link WebAuthnCredential} constructor (the same type and objectType
 * {@code WebAuthnResource} sets when it registers one), written by the product's DAO, turned into what is sent to CouchDB by the
 * mapper the Cloudant wrapper uses, and read back by the product's {@code ContentDaoServiceImpl.getContent}.
 *
 * <p>A version series id is not another name for its latest version: an id with no content is answered as an id that does not
 * exist, and nothing is read for it as a version series — the latest version is read by {@code getObjectOfLatestVersion}, which
 * is locked here too. A {@code getContent} that cannot read throws, and getObject throws the same — a read that failed is not
 * answered as an id that does not exist.
 *
 * <p>The exception service is the product's ({@link ExceptionServiceImpl}), so the answer for an id that does not exist is the
 * product's too; only its permission check is stubbed, and recorded.
 */
class GetObjectAnswersOnlyCmisObjectsTest {

    private static final String REPO = "bedroom";

    private static final String PASSKEY = "nemaki:webauthnCredential";

    /** The stored {@code type} and {@code objectType} of the documents in a repository that are not CMIS objects. */
    private static final List<String[]> NOT_CMIS_OBJECTS = List.of(new String[] {"versionSeries", "versionSeries"},
            new String[] {"attachment", "attachment"}, new String[] {"change", "cmis:document"},
            new String[] {"typeDefinition", "typeDefinition"}, new String[] {"propertyDefinitionCore", "propertyDefinitionCore"},
            new String[] {"propertyDefinitionDetail", "propertyDefinitionDetail"}, new String[] {"user", "user"},
            new String[] {"group", "group"}, new String[] {"patch", "patch"}, new String[] {"rendition", "rendition"},
            new String[] {"apiKey", "apiKey"}, new String[] {"webhookDeliveryLog", "webhookDeliveryLog"},
            new String[] {"aclEpochAuditCursor", "aclEpochAuditCursor"}, new String[] {"cacheGeneration", "cacheGeneration"},
            new String[] {null, null});

    /** objectTypes of items that resemble a passkey's and are not it: each is an item, answered as before. */
    private static final List<String> NEAR_PASSKEY = List.of("nemaki:webauthnCredentials", "nemaki:WebAuthnCredential",
            "nemaki:webauthn", "webauthnCredential");

    private final ObjectServiceImpl service = new ObjectServiceImpl();
    private final ContentService contentService = mock(ContentService.class);
    private final ExceptionServiceImpl exceptionService = spy(new ExceptionServiceImpl());
    private final CompileService compileService = mock(CompileService.class);
    private final CallContext callContext = mock(CallContext.class);
    private final ThreadLockService locks = mock(ThreadLockService.class);

    GetObjectAnswersOnlyCmisObjectsTest() {
        when(locks.getReadLock(any(), any())).thenAnswer(invocation -> new ReentrantLock());
        doNothing().when(exceptionService).permissionDenied(any(), any(), any(), any());
        when(compileService.compileObjectData(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new Compiled(((Content) invocation.getArgument(2)).getId()));
        service.setContentService(contentService);
        service.setExceptionService(exceptionService);
        service.setCompileService(compileService);
        service.setThreadLockService(locks);
    }

    /** What the stubbed compile answers: the id of the content it was given. */
    private static final class Compiled extends ObjectDataImpl {
        private static final long serialVersionUID = 1L;
        private final String compiledFrom;

        Compiled(String compiledFrom) {
            this.compiledFrom = compiledFrom;
        }
    }

    /** An error a read can fail with that is not a VirtualMachineError. */
    private static final class ReadBroke extends Error {
        private static final long serialVersionUID = 1L;

        ReadBroke(String message) {
            super(message);
        }
    }

    private static String compiledFrom(ObjectData answered, String what) {
        assertTrue(answered instanceof Compiled, what + " was answered by something other than the compile: " + answered);
        return ((Compiled) answered).compiledFrom;
    }

    private ObjectData get(String id) {
        return service.getObject(callContext, REPO, id, "*", false, IncludeRelationships.NONE, null, false, false, null);
    }

    private static Content stored(String id, String type, String objectType) {
        Content content = new Content();
        content.setId(id);
        content.setType(type);
        content.setObjectType(objectType);
        content.setName("r275.txt");
        return content;
    }

    private static <T extends Content> T withId(T content, String id) {
        content.setId(id);
        return content;
    }

    /** The answer for an id that is nothing, read on an instance of its own (nothing it reads is counted against another). */
    private static CmisObjectNotFoundException absent() {
        GetObjectAnswersOnlyCmisObjectsTest nothing = new GetObjectAnswersOnlyCmisObjectsTest();
        return assertThrows(CmisObjectNotFoundException.class, () -> nothing.get("GONE"), "an id that is nothing was answered");
    }

    /** As if a version series and its latest version had this id: what reading the id as a version series id would answer. */
    private void aVersionSeriesHasTheId(String id) {
        VersionSeries series = new VersionSeries();
        series.setId(id);
        when(contentService.getVersionSeries(REPO, id)).thenReturn(series);
        when(contentService.getDocumentOfLatestVersion(REPO, id)).thenReturn(withId(new Document(), "LATEST"));
    }

    private void refusedAsAbsent(String id, String what) {
        CmisObjectNotFoundException absent = absent();
        CmisObjectNotFoundException refused = assertThrows(CmisObjectNotFoundException.class, () -> get(id),
                what + " was answered as an object");
        assertEquals(absent.getMessage().replace("GONE", id), refused.getMessage(),
                what + " is not answered as an id that does not exist");
        assertEquals(absent.getCode(), refused.getCode(), what);
        verify(exceptionService, never()).permissionDenied(any(), any(), any(), any());
        verify(compileService, never()).compileObjectData(any(), any(), any(), any(), any(), any(), any(), any());
        verify(contentService, never()).getVersionSeries(anyString(), anyString());
        verify(contentService, never()).getVersionSeries(anyString(), any(Document.class));
        verify(contentService, never()).getDocumentOfLatestVersion(any(), any());
    }

    private void answeredAsItself(Content object, String what) {
        when(contentService.getContent(REPO, object.getId())).thenReturn(object);
        ObjectData answered = assertDoesNotThrow(() -> get(object.getId()), what + " was refused");
        assertEquals(object.getId(), compiledFrom(answered, what), what + " was not answered as itself");
        verify(exceptionService).permissionDenied(eq(callContext), eq(REPO), eq(PermissionMapping.CAN_GET_PROPERTIES_OBJECT),
                eq(object));
        verify(compileService).compileObjectData(eq(callContext), eq(REPO), eq(object), any(), any(), any(), any(), any());
        verify(contentService, never()).getVersionSeries(anyString(), anyString());
        verify(contentService, never()).getVersionSeries(anyString(), any(Document.class));
    }

    /** A DAO whose CouchDB is the given client. */
    private static ContentDaoServiceImpl daoOn(CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        ContentDaoServiceImpl dao = new ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    /** A stored CouchDB document as the product's DAO reads it back ({@code ContentDaoServiceImpl.getContent}). */
    private static Content readByTheDao(String id, Map<String, Object> fields) {
        com.ibm.cloud.cloudant.v1.model.Document doc = new com.ibm.cloud.cloudant.v1.model.Document();
        doc.setId(id);
        doc.setRev("1-r277");
        fields.forEach(doc::put);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(id)).thenReturn(doc);
        Content content = daoOn(client).getContent(REPO, id);
        assertTrue(content != null, "the DAO read nothing for the stored " + fields);
        return content;
    }

    /** An item as it is stored: type cmis:item and the given objectType. */
    private static Map<String, Object> anItemStoredAs(String objectType, String name) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "cmis:item");
        fields.put("objectType", objectType);
        fields.put("name", name);
        return fields;
    }

    /**
     * A passkey as the product stores it: the product's constructor sets its type and objectType, the fields are the ones
     * {@code WebAuthnResource} sets when it registers one, the product's DAO writes it, and the Cloudant wrapper's mapper turns
     * what was written into the document sent to CouchDB.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> aStoredPasskey() {
        WebAuthnCredential credential = new WebAuthnCredential(new NodeBase());
        credential.setUserId("alice");
        credential.setCredentialId("AbCdEf0123");
        credential.setPublicKeyCose("pQECAyYgASFYIA");
        credential.setSignCount(3L);
        credential.setAaguid("00000000000000000000000000000000");
        credential.setDisplayName("alice's key");
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
        return fields;
    }

    @Test
    @DisplayName("getObject: a document that is not a CMIS object is answered as an id that does not exist — not checked, not compiled, not read as a version series")
    void aDocumentThatIsNotACmisObjectIsAnsweredAsAnIdThatDoesNotExist() {
        for (String[] stored : NOT_CMIS_OBJECTS) {
            GetObjectAnswersOnlyCmisObjectsTest fresh = new GetObjectAnswersOnlyCmisObjectsTest();
            String id = "R275-" + stored[0];
            when(fresh.contentService.getContent(REPO, id)).thenReturn(stored(id, stored[0], stored[1]));
            fresh.aVersionSeriesHasTheId(id);
            fresh.refusedAsAbsent(id, "a stored " + stored[0]);
        }
    }

    @Test
    @DisplayName("getObject: a passkey — stored as cmis:item with objectType nemaki:webauthnCredential, read back by the DAO — is answered as an id that does not exist, not checked, not compiled")
    void aStoredPasskeyIsAnsweredAsAnIdThatDoesNotExist() {
        Map<String, Object> stored = aStoredPasskey();
        assertEquals("cmis:item", stored.get("type"), "a passkey is no longer stored as cmis:item: " + stored);
        assertEquals(PASSKEY, stored.get("objectType"), "a passkey is no longer stored as " + PASSKEY + ": " + stored);
        Content passkey = readByTheDao("PASSKEY", stored);
        // what a check of the stored type alone lets through: the DAO reads it back as an item
        assertTrue(passkey instanceof Item, "the DAO no longer reads a passkey as an item: " + passkey.getClass());
        assertEquals(Boolean.TRUE, passkey.isItem(), "the DAO no longer reads a passkey with type cmis:item");
        assertEquals(PASSKEY, passkey.getObjectType(), "the DAO no longer reads a passkey's objectType");

        when(contentService.getContent(REPO, "PASSKEY")).thenReturn(passkey);
        aVersionSeriesHasTheId("PASSKEY");
        refusedAsAbsent("PASSKEY", "a stored passkey");
    }

    @Test
    @DisplayName("getObject: each of the five object base types is checked and answered as before — subtypes too")
    void eachObjectBaseTypeIsAnsweredAsBefore() {
        Document subtype = withId(new Document(), "SUBTYPED");
        subtype.setObjectType("nemaki:document");
        for (Content object : List.of(withId(new Document(), "D"), withId(new Folder(), "F"), withId(new Relationship(), "REL"),
                withId(new Policy(), "POL"), withId(new Item(), "ITEM"), subtype)) {
            new GetObjectAnswersOnlyCmisObjectsTest().answeredAsItself(object, object.getClass().getSimpleName() + " " + object.getId());
        }
    }

    @Test
    @DisplayName("getObject: an item read back by the DAO is checked and answered as before — cmis:item, a user item, a group item, a custom item and objectTypes that only resemble a passkey's")
    void anItemReadByTheDaoIsAnsweredAsBefore() {
        Map<String, Object> storedUser = anItemStoredAs("nemaki:user", "alice");
        storedUser.put("userId", "alice");
        Content user = readByTheDao("USER-ITEM", storedUser);
        assertTrue(user instanceof UserItem, "the DAO no longer reads a nemaki:user item as a user item: " + user.getClass());
        Map<String, Object> storedGroup = anItemStoredAs("nemaki:group", "staff");
        storedGroup.put("groupId", "staff");
        Content group = readByTheDao("GROUP-ITEM", storedGroup);
        assertTrue(group instanceof GroupItem, "the DAO no longer reads a nemaki:group item as a group item: " + group.getClass());
        Map<String, Content> items = new LinkedHashMap<>();
        items.put("cmis:item", readByTheDao("PLAIN-ITEM", anItemStoredAs("cmis:item", "plain")));
        items.put("nemaki:user", user);
        items.put("nemaki:group", group);
        items.put("custom:invoice", readByTheDao("CUSTOM-ITEM", anItemStoredAs("custom:invoice", "invoice")));
        for (String near : NEAR_PASSKEY) {
            items.put(near, readByTheDao("NEAR-" + near, anItemStoredAs(near, "near")));
        }
        items.forEach((objectType, item) -> {
            assertEquals(Boolean.TRUE, item.isItem(), objectType + " was not read back as an item");
            assertEquals(objectType, item.getObjectType(), objectType + " was read back with another objectType");
            new GetObjectAnswersOnlyCmisObjectsTest().answeredAsItself(item, "an item " + objectType);
        });
    }

    @Test
    @DisplayName("getObject: an id with no content is answered as an id that does not exist even when a version series has that id — it is not another name for its latest version")
    void anIdWithNoContentIsAnsweredAsAnIdThatDoesNotExistEvenWhenAVersionSeriesHasIt() {
        when(contentService.getContent(REPO, "SERIES")).thenReturn(null);
        aVersionSeriesHasTheId("SERIES");
        refusedAsAbsent("SERIES", "an id with no content that a version series has");
    }

    @Test
    @DisplayName("getObject: an id that is nothing is answered 404 「The specified object is not found」, as before, and nothing is read for it as a version series")
    void anIdThatIsNothingIsAnsweredAsBefore() {
        CmisObjectNotFoundException absent = assertThrows(CmisObjectNotFoundException.class, () -> get("GONE"),
                "an id that is nothing was answered");
        assertEquals("[objectId:GONE]The specified object is not found", absent.getMessage());
        assertEquals(404, absent.getCode().intValue());
        verify(exceptionService, never()).permissionDenied(any(), any(), any(), any());
        verify(compileService, never()).compileObjectData(any(), any(), any(), any(), any(), any(), any(), any());
        verify(contentService, never()).getVersionSeries(anyString(), anyString());
        verify(contentService, never()).getDocumentOfLatestVersion(any(), any());
    }

    @Test
    @DisplayName("getObject: a getContent that fails with a RuntimeException fails getObject with the same exception — not answered as an id that does not exist")
    void aReadThatFailedWithARuntimeExceptionIsThrownAsItIs() {
        IllegalStateException broke = new IllegalStateException("r275: the content could not be read");
        when(contentService.getContent(REPO, "BROKEN")).thenThrow(broke);
        Throwable thrown = assertThrows(Throwable.class, () -> get("BROKEN"), "a read that failed was answered");
        assertSame(broke, thrown, "a read that failed was answered as " + thrown);
        verify(exceptionService, never()).permissionDenied(any(), any(), any(), any());
        verify(compileService, never()).compileObjectData(any(), any(), any(), any(), any(), any(), any(), any());
        verify(contentService, never()).getVersionSeries(anyString(), anyString());
    }

    @Test
    @DisplayName("getObject: a getContent that fails with an Error — a VirtualMachineError too — fails getObject with the same error")
    void aReadThatFailedWithAnErrorIsThrownAsItIs() {
        for (Error broke : List.of(new ReadBroke("r275: the content could not be read"), new InternalError("r275: the JVM broke"))) {
            GetObjectAnswersOnlyCmisObjectsTest fresh = new GetObjectAnswersOnlyCmisObjectsTest();
            when(fresh.contentService.getContent(REPO, "BROKEN")).thenThrow(broke);
            Throwable thrown = assertThrows(Throwable.class, () -> fresh.get("BROKEN"), "a read that failed was answered");
            assertSame(broke, thrown, "a read that failed with " + broke.getClass().getSimpleName() + " was answered as " + thrown);
            verify(fresh.exceptionService, never()).permissionDenied(any(), any(), any(), any());
            verify(fresh.compileService, never()).compileObjectData(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("getObjectOfLatestVersion answers a version series' latest version, by its version series id and by an older version's id — the path that reads it")
    void theLatestVersionIsReadByGetObjectOfLatestVersion() {
        VersioningServiceImpl versioning = new VersioningServiceImpl();
        versioning.setContentService(contentService);
        versioning.setExceptionService(exceptionService);
        versioning.setCompileService(compileService);
        versioning.setThreadLockService(locks);
        Document older = withId(new Document(), "V1");
        older.setVersionSeriesId("SERIES");
        when(contentService.getDocument(REPO, "V1")).thenReturn(older);
        Document latest = withId(new Document(), "LATEST");
        when(contentService.getDocumentOfLatestVersion(REPO, "SERIES")).thenReturn(latest);

        ObjectData bySeries = assertDoesNotThrow(() -> versioning.getObjectOfLatestVersion(callContext, REPO, null, "SERIES", false,
                "*", false, IncludeRelationships.NONE, null, false, false, null), "the latest version by its series id was refused");
        assertEquals("LATEST", compiledFrom(bySeries, "by the series id"), "the series id did not answer the latest version");
        ObjectData byOlder = assertDoesNotThrow(() -> versioning.getObjectOfLatestVersion(callContext, REPO, "V1", null, false, "*",
                false, IncludeRelationships.NONE, null, false, false, null), "the latest version by an older version's id was refused");
        assertEquals("LATEST", compiledFrom(byOlder, "by an older version's id"), "an older version's id did not answer the latest version");
        verify(exceptionService, times(2)).permissionDenied(eq(callContext), eq(REPO),
                eq(PermissionMapping.CAN_GET_PROPERTIES_OBJECT), eq(latest));
    }
}
