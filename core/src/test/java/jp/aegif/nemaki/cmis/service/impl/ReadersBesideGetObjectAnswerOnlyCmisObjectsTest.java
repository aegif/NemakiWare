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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AllowableActionsImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.ContentDaoServiceImpl;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.WebAuthnCredential;
import jp.aegif.nemaki.util.lock.ThreadLockService;

/**
 * The one-object readers that do not go through {@code getObject} answer only CMIS objects too (R279 — 3.4.2):
 * {@code getAllowableActions}, {@code getRenditions} and {@code getContentStream} ({@code ObjectServiceImpl}), {@code getAcl}
 * ({@code AclServiceImpl}), {@code getObjectParents} ({@code NavigationServiceImpl}), {@code getAppliedPolicies}
 * ({@code PolicyServiceImpl}) and {@code getObjectRelationships} ({@code RelationshipServiceImpl}) each read the content by
 * {@code getContent} themselves, and answered a document's version series id, its content stream id and a passkey's id as an
 * object: on nb33 the browser binding's {@code allowableActions} answered them 200 with {@code canDeleteObject} even to a user
 * who is not an administrator, {@code acl}, {@code parents}, {@code relationships} and {@code policies} answered them to an
 * administrator (and 403 to another user), {@code renditions} 500 and {@code content} 409 or 500 — where an id that does not
 * exist is answered 404, and by {@code renditions} with an empty list.
 *
 * <p>Each reader answers the content that is not a CMIS object ({@code ObjectServiceImpl.isCmisObject} — the same decision
 * getObject makes, R275 and R277) exactly as it answers an id that does not exist, right after {@code getContent}: before the
 * permission check, before anything is compiled, read or computed for it. A document is answered as before, through the
 * permission check and the body of the reader, and so is an item the DAO reads back — a plain one and ones whose objectType only
 * resembles a passkey's.
 *
 * <p>The answer for an id that does not exist is each reader's own, read on a fresh fixture: the exception service is the
 * product's ({@link ExceptionServiceImpl}), and the renditions reader reads through the product's
 * {@link ContentServiceImpl} over a DAO, so that a content that is neither a document nor a folder is answered as the product
 * answers it. The internal rows are the ones the product's DAO reads back from the shapes stored in CouchDB — a version series
 * row, an attachment row and a passkey written by the product's DAO. The permission check is stubbed to refuse, as for a user
 * who is not an administrator, so a refusal that came after it would be answered 403, not as an id that does not exist.
 *
 * <p>{@code getRenditions} answered the renditions of a document to a user getObject refuses: it made no permission check at
 * all, and a rendition's title is the file name of the stream it was made from (R282 — 3.4.2). It checks what getObject checks
 * ({@code CAN_GET_PROPERTIES_OBJECT}) before any rendition is read, and refuses with 403. An item, a relationship and a policy
 * have no renditions: {@code ContentService.getRenditions} answered null for them, and the reader's for-each made that a 500
 * (R283). They are answered an empty list — after the same check, which is not skipped for them. Because that empty list is the
 * same one an id that does not exist is answered, what tells a CMIS object from an internal row here is that it reached the
 * permission check. A read that failed — of the content or of a rendition — is thrown as it is.
 */
class ReadersBesideGetObjectAnswerOnlyCmisObjectsTest {

    private static final String REPO = "bedroom";

    private static final String PASSKEY = "nemaki:webauthnCredential";

    /** objectTypes of items that resemble a passkey's and are not it: each is an item, answered as one. */
    private static final List<String> NEAR_PASSKEY = List.of("nemaki:webauthnCredentials", "nemaki:WebAuthnCredential",
            "nemaki:webauthn", "webauthnCredential");

    /** A reader that does not go through getObject, called the way its binding calls it. */
    private enum Reader {
        ALLOWABLE_ACTIONS {
            @Override
            Object read(Fixture f, String id) {
                return f.objects.getAllowableActions(f.callContext, REPO, id);
            }
        },
        RENDITIONS {
            @Override
            Object read(Fixture f, String id) {
                return f.renditionObjects.getRenditions(f.callContext, REPO, id, "*", null, null, null);
            }
        },
        CONTENT_STREAM {
            @Override
            Object read(Fixture f, String id) {
                return f.objects.getContentStream(f.callContext, REPO, id, null, null, null);
            }
        },
        ACL {
            @Override
            Object read(Fixture f, String id) {
                return f.acls.getAcl(f.callContext, REPO, id, false, null);
            }
        },
        PARENTS {
            @Override
            Object read(Fixture f, String id) {
                return f.navigation.getObjectParents(f.callContext, REPO, id, "*", false, IncludeRelationships.NONE, null,
                        true, null);
            }
        },
        POLICIES {
            @Override
            Object read(Fixture f, String id) {
                return f.policies.getAppliedPolicies(f.callContext, REPO, id, "*", null);
            }
        },
        RELATIONSHIPS {
            @Override
            Object read(Fixture f, String id) {
                return f.relationships.getObjectRelationships(f.callContext, REPO, id, false, RelationshipDirection.SOURCE,
                        null, "*", false, null, null, null);
            }
        };

        abstract Object read(Fixture f, String id);
    }

    /** Everything one reading reads, and what it is counted against. */
    private static final class Fixture {
        final ContentService contentService = mock(ContentService.class);
        final ContentDaoService dao = mock(ContentDaoService.class);
        final ContentServiceImpl readingContentService = spy(new ContentServiceImpl());
        final ExceptionServiceImpl exceptionService = spy(new ExceptionServiceImpl());
        final CompileService compileService = mock(CompileService.class);
        final CallContext callContext = mock(CallContext.class);
        final ThreadLockService locks = mock(ThreadLockService.class);
        final ObjectServiceImpl objects = new ObjectServiceImpl();
        final ObjectServiceImpl renditionObjects = new ObjectServiceImpl();
        final AclServiceImpl acls = new AclServiceImpl();
        final NavigationServiceImpl navigation = new NavigationServiceImpl();
        final PolicyServiceImpl policies = new PolicyServiceImpl();
        final RelationshipServiceImpl relationships = new RelationshipServiceImpl();

        Fixture() {
            when(locks.getReadLock(any(), any())).thenAnswer(invocation -> new ReentrantLock());
            when(locks.orderedLocks(any(), any(), anyBoolean())).thenAnswer(invocation -> List.<Lock>of(new ReentrantLock()));
            when(locks.readLocks(any(), any())).thenAnswer(invocation -> List.<Lock>of());
            readingContentService.setContentDaoService(dao);
            objects.setContentService(contentService);
            objects.setExceptionService(exceptionService);
            objects.setCompileService(compileService);
            objects.setThreadLockService(locks);
            renditionObjects.setContentService(readingContentService);
            renditionObjects.setExceptionService(exceptionService);
            renditionObjects.setCompileService(compileService);
            renditionObjects.setThreadLockService(locks);
            acls.setContentService(contentService);
            acls.setExceptionService(exceptionService);
            acls.setCompileService(compileService);
            acls.setThreadLockService(locks);
            navigation.setContentService(contentService);
            navigation.setExceptionService(exceptionService);
            navigation.setCompileService(compileService);
            navigation.setThreadLockService(locks);
            policies.setContentService(contentService);
            policies.setExceptionService(exceptionService);
            policies.setCompileService(compileService);
            policies.setThreadLockService(locks);
            relationships.setContentService(contentService);
            relationships.setExceptionService(exceptionService);
            relationships.setCompileService(compileService);
            relationships.setThreadLockService(locks);
        }

        /** As if {@code getContent} read this content for its id — the content service's and the DAO's alike. */
        Fixture stores(Content content) {
            when(contentService.getContent(REPO, content.getId())).thenReturn(content);
            when(dao.getContent(REPO, content.getId())).thenReturn(content);
            return this;
        }

        /** As a user who is not an administrator and may not: the permission check refuses with 403. */
        Fixture refusesPermission() {
            doThrow(new CmisPermissionDeniedException("r279: this user may not", java.math.BigInteger.valueOf(403))).when(exceptionService)
                    .permissionDenied(any(), any(), any(), any());
            return this;
        }

        Fixture grantsPermission() {
            doNothing().when(exceptionService).permissionDenied(any(), any(), any(), any());
            return this;
        }

        /** Nothing was checked, compiled, read or computed for the content after {@code getContent}. */
        void nothingWasDoneFor(String what) {
            verify(exceptionService, never()).permissionDenied(any(), any(), any(), any());
            verify(compileService, never()).compileAllowableActions(any(), any(), any());
            verify(compileService, never()).compileAllowableActions(any(), any(), any(), any());
            verify(compileService, never()).compileObjectData(any(), any(), any(), any(), any(), any(), any(), any());
            verify(compileService, never()).compileObjectDataList(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), anyBoolean(), any());
            verify(compileService, never()).compileAcl(any(), any(), any());
            verify(contentService, never()).calculateAcl(any(), any());
            verify(contentService, never()).getAttachment(any(), any());
            verify(contentService, never()).getRendition(any(), any());
            verify(contentService, never()).getAppliedPolicies(any(), any(), any());
            verify(contentService, never()).getRelationsipsOfObject(any(), any(), any());
            verify(contentService, never()).isRoot(any(), any());
            verify(dao, never()).getRendition(any(), any());
        }
    }

    /** What a reader answered: what it returned, or what it threw. */
    private record Answer(Object returned, Throwable thrown) {
        static Answer of(Reader reader, Fixture f, String id) {
            try {
                return new Answer(reader.read(f, id), null);
            } catch (Throwable t) {
                return new Answer(null, t);
            }
        }

        @Override
        public String toString() {
            return thrown != null ? "thrown " + thrown : "returned " + returned;
        }
    }

    /** What a reader answers for an id that is nothing, read on a fixture of its own. */
    private static Answer absent(Reader reader) {
        return Answer.of(reader, new Fixture().refusesPermission(), "GONE");
    }

    /** The reader answered {@code id} exactly as it answers an id that does not exist, and did nothing for it. */
    private static void answeredAsAbsent(Reader reader, Content stored, String what) {
        Answer absent = absent(reader);
        Fixture f = new Fixture().refusesPermission().stores(stored);
        Answer answered = Answer.of(reader, f, stored.getId());
        String who = reader + ": " + what;
        if (absent.thrown() != null) {
            assertTrue(absent.thrown() instanceof CmisBaseException, reader + ": an id that is nothing is no longer answered as"
                    + " one that does not exist: " + absent);
            assertTrue(answered.thrown() != null, who + " was answered as an object: " + answered);
            assertEquals(absent.thrown().getClass(), answered.thrown().getClass(), who + " is not answered as an id that does"
                    + " not exist: " + answered + " — an id that does not exist is " + absent);
            assertEquals(absent.thrown().getMessage().replace("GONE", stored.getId()), answered.thrown().getMessage(),
                    who + " is not answered as an id that does not exist");
            assertEquals(((CmisBaseException) absent.thrown()).getCode(), ((CmisBaseException) answered.thrown()).getCode(),
                    who);
        } else {
            assertTrue(answered.thrown() == null, who + " is not answered as an id that does not exist: " + answered
                    + " — an id that does not exist is " + absent);
            assertNotNull(absent.returned(), reader + ": an id that is nothing was answered with nothing");
            assertEquals(absent.returned(), answered.returned(), who + " is not answered as an id that does not exist");
        }
        f.nothingWasDoneFor(who);
        if (reader == Reader.RENDITIONS) {
            verify(f.readingContentService, never().description(who + ": its renditions were read"))
                    .getRenditions(any(), any());
        }
    }

    /** The reader did not answer {@code item} as an id that does not exist: it went on past the check. */
    private static void notAnsweredAsAbsent(Reader reader, Content item, String what) {
        Answer absent = absent(reader);
        Fixture f = new Fixture().grantsPermission().stores(item);
        String who = reader + ": " + what;
        if (reader == Reader.RENDITIONS) {
            // The renditions of an object that has none are the same empty list an id that does not exist is answered (R283):
            // what tells the two apart is that the object went on to the permission check, and was not refused there.
            Object renditions = assertDoesNotThrow(() -> reader.read(f, item.getId()),
                    who + " was not answered with a list of renditions");
            assertNotNull(renditions, who + " was answered with nothing");
            verify(f.exceptionService, description(who + " was answered as an id that does not exist: it never reached the"
                    + " permission check")).permissionDenied(f.callContext, REPO, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, item);
            return;
        }
        Answer answered = Answer.of(reader, f, item.getId());
        if (absent.thrown() != null) {
            boolean sameAsAbsent = answered.thrown() != null && absent.thrown().getClass() == answered.thrown().getClass()
                    && absent.thrown().getMessage().replace("GONE", item.getId()).equals(answered.thrown().getMessage());
            assertTrue(!sameAsAbsent, who + " was answered as an id that does not exist: " + answered);
        } else {
            assertTrue(answered.thrown() != null || !absent.returned().equals(answered.returned()),
                    who + " was answered as an id that does not exist: " + answered);
        }
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
        doc.setRev("1-r279");
        fields.forEach(doc::put);
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(id)).thenReturn(doc);
        Content content = daoOn(client).getContent(REPO, id);
        assertNotNull(content, "the DAO read nothing for the stored " + fields);
        return content;
    }

    /** The flags every stored node carries, as a version series and an attachment row on nb33 carry them. */
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

    /** A document's version series row, as nb33 stores one. */
    private static Content aVersionSeriesRow() {
        Map<String, Object> fields = aStoredNode("versionSeries");
        fields.put("versionSeriesCheckedOut", false);
        return readByTheDao("R279-SERIES", fields);
    }

    /** A document's content stream row, as nb33 stores one. */
    private static Content anAttachmentRow() {
        Map<String, Object> fields = aStoredNode("attachment");
        fields.put("attachment", true);
        fields.put("name", null);
        fields.put("length", 36L);
        fields.put("actualLength", 36L);
        fields.put("mimeType", "application/octet-stream");
        fields.put("actualMimeType", "application/octet-stream");
        return readByTheDao("R279-STREAM", fields);
    }

    /**
     * A passkey as the product stores it: the product's constructor sets its type and objectType, the product's DAO writes it,
     * the Cloudant wrapper's mapper turns what was written into the document sent to CouchDB, and the product's DAO reads it
     * back — an item with objectType {@code nemaki:webauthnCredential}.
     */
    @SuppressWarnings("unchecked")
    private static Content aStoredPasskey() {
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
        assertEquals("cmis:item", fields.get("type"), "a passkey is no longer stored as cmis:item: " + fields);
        assertEquals(PASSKEY, fields.get("objectType"), "a passkey is no longer stored as " + PASSKEY + ": " + fields);
        Content passkey = readByTheDao("R279-PASSKEY", fields);
        // what a check of the stored type alone lets through: the DAO reads it back as an item
        assertTrue(passkey instanceof Item, "the DAO no longer reads a passkey as an item: " + passkey.getClass());
        assertEquals(PASSKEY, passkey.getObjectType(), "the DAO no longer reads a passkey's objectType");
        return passkey;
    }

    /** An item as it is stored: type cmis:item and the given objectType, read back by the DAO. */
    private static Content anItemStoredAs(String id, String objectType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "cmis:item");
        fields.put("objectType", objectType);
        fields.put("name", "r279-item");
        Content item = readByTheDao(id, fields);
        assertEquals(Boolean.TRUE, item.isItem(), objectType + " was not read back as an item");
        assertEquals(objectType, item.getObjectType(), objectType + " was read back with another objectType");
        return item;
    }

    /** A user as it is stored — an item with objectType nemaki:user — read back by the DAO. */
    private static Content aStoredUser() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "cmis:item");
        fields.put("objectType", "nemaki:user");
        fields.put("name", "r279-user");
        fields.put("userId", "r279-user");
        fields.put("passwordHash", "$2a$10$r279r279r279r279r279r.r279r279r279r279r279r279r279r27");
        fields.put("admin", false);
        Content user = readByTheDao("R279-USER", fields);
        assertEquals(Boolean.TRUE, user.isItem(), "a user was not read back as an item");
        assertEquals("nemaki:user", user.getObjectType(), "a user was read back with another objectType");
        return user;
    }

    /** A group as it is stored — an item with objectType nemaki:group — read back by the DAO. */
    private static Content aStoredGroup() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "cmis:item");
        fields.put("objectType", "nemaki:group");
        fields.put("name", "r279-group");
        fields.put("groupId", "r279-group");
        Content group = readByTheDao("R279-GROUP", fields);
        assertEquals(Boolean.TRUE, group.isItem(), "a group was not read back as an item");
        assertEquals("nemaki:group", group.getObjectType(), "a group was read back with another objectType");
        return group;
    }

    /** A folder, a relationship or a policy as it is stored, read back by the DAO. */
    private static Content aStored(String id, String baseType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", baseType);
        fields.put("objectType", baseType);
        fields.put("name", "r279-" + baseType.substring("cmis:".length()));
        Content content = readByTheDao(id, fields);
        assertEquals(baseType, content.getType(), baseType + " was read back with another type");
        return content;
    }

    /** The CMIS objects that have no renditions: an item of each kind, a relationship and a policy, read back by the DAO. */
    private static Map<String, Content> theObjectsWithNoRenditions() {
        Map<String, Content> objects = new LinkedHashMap<>();
        objects.put("an item", anItemStoredAs("R283-ITEM", "cmis:item"));
        objects.put("a user", aStoredUser());
        objects.put("a group", aStoredGroup());
        objects.put("a custom item", anItemStoredAs("R283-CUSTOM", "r279:invoice"));
        objects.put("an item whose objectType resembles a passkey's", anItemStoredAs("R283-NEAR", NEAR_PASSKEY.get(0)));
        objects.put("a relationship", aStored("R283-REL", "cmis:relationship"));
        objects.put("a policy", aStored("R283-POL", "cmis:policy"));
        return objects;
    }

    /**
     * A rendition as the DAO reads one back. Its title is what the product writes there for a rendition made from a stream:
     * the stream's file name ({@code AttachmentDaoDelegate}: {@code setTitle(contentStream.getFileName())}).
     */
    private static Rendition aRendition(String id, String title) {
        Rendition rendition = new Rendition();
        rendition.setId(id);
        rendition.setTitle(title);
        rendition.setMimetype("application/pdf");
        rendition.setKind("cmis:preview");
        rendition.setLength(4L);
        return rendition;
    }

    private static Document aDocumentWithRenditions(String id, String... renditionIds) {
        Document document = new Document();
        document.setId(id);
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName(id + ".pdf");
        document.setRenditionIds(List.of(renditionIds));
        return document;
    }

    private static Folder aFolderWithRenditions(String id, String... renditionIds) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setType("cmis:folder");
        folder.setObjectType("cmis:folder");
        folder.setName(id);
        folder.setRenditionIds(List.of(renditionIds));
        return folder;
    }

    /** An Error a read throws: not an assertion, so that a lock that lets it through is not taken for a lock that fired. */
    private static final class TheReadFailed extends Error {
        TheReadFailed(String message) {
            super(message);
        }
    }

    /** What a read that failed throws: a RuntimeException and an Error, each its own instance. */
    private static List<Throwable> failedReads() {
        return List.of(new IllegalStateException("r279: CouchDB did not answer"), new TheReadFailed("r279: the read failed"));
    }

    @Test
    @DisplayName("Each reader beside getObject answers a document's version series id as an id that does not exist — not checked, not compiled, nothing read for it")
    void aVersionSeriesIdIsAnsweredAsAnIdThatDoesNotExist() {
        Content series = aVersionSeriesRow();
        assertEquals("versionSeries", series.getType(), "the DAO read a version series row with another type");
        for (Reader reader : Reader.values()) {
            answeredAsAbsent(reader, series, "a version series id");
        }
    }

    @Test
    @DisplayName("Each reader beside getObject answers a document's content stream id as an id that does not exist — not checked, not compiled, nothing read for it")
    void aContentStreamIdIsAnsweredAsAnIdThatDoesNotExist() {
        Content stream = anAttachmentRow();
        assertEquals("attachment", stream.getType(), "the DAO read an attachment row with another type");
        for (Reader reader : Reader.values()) {
            answeredAsAbsent(reader, stream, "a content stream id");
        }
    }

    @Test
    @DisplayName("Each reader beside getObject answers a passkey — stored as cmis:item with objectType nemaki:webauthnCredential — as an id that does not exist")
    void aPasskeyIsAnsweredAsAnIdThatDoesNotExist() {
        Content passkey = aStoredPasskey();
        for (Reader reader : Reader.values()) {
            answeredAsAbsent(reader, passkey, "a passkey");
        }
    }

    @Test
    @DisplayName("Each reader beside getObject answers an item the DAO reads back — a plain one and ones whose objectType only resembles a passkey's — not as an id that does not exist")
    void anItemIsNotAnsweredAsAnIdThatDoesNotExist() {
        Map<String, Content> items = new LinkedHashMap<>();
        items.put("cmis:item", anItemStoredAs("R279-ITEM", "cmis:item"));
        for (String near : NEAR_PASSKEY) {
            items.put(near, anItemStoredAs("R279-NEAR-" + near, near));
        }
        items.put("nemaki:user", aStoredUser());
        items.put("nemaki:group", aStoredGroup());
        items.put("r279:invoice (a custom item)", anItemStoredAs("R279-CUSTOM", "r279:invoice"));
        for (Reader reader : Reader.values()) {
            items.forEach((objectType, item) -> notAnsweredAsAbsent(reader, item, "an item " + objectType));
        }
    }

    @Test
    @DisplayName("Each reader beside getObject answers a folder, a relationship and a policy the DAO reads back not as an id that does not exist")
    void theOtherBaseTypesAreNotAnsweredAsAnIdThatDoesNotExist() {
        Map<String, Content> objects = new LinkedHashMap<>();
        objects.put("a folder", aStored("R279-FOLDER", "cmis:folder"));
        objects.put("a relationship", aStored("R279-REL", "cmis:relationship"));
        objects.put("a policy", aStored("R279-POL", "cmis:policy"));
        for (Reader reader : Reader.values()) {
            objects.forEach((what, object) -> notAnsweredAsAbsent(reader, object, what));
        }
    }

    @Test
    @DisplayName("Each reader beside getObject answers a document as before — through its permission check and its body")
    void aDocumentIsAnsweredAsBefore() {
        for (Reader reader : Reader.values()) {
            Fixture f = new Fixture().grantsPermission();
            Document document = new Document();
            document.setId("R279-DOC");
            document.setType("cmis:document");
            document.setObjectType("cmis:document");
            document.setName("r279.txt");
            document.setParentId("R279-PARENT");
            document.setAttachmentNodeId("R279-ATT");
            document.setRenditionIds(List.of("R279-REND"));
            f.stores(document);
            Folder parent = new Folder();
            parent.setId("R279-PARENT");
            when(f.contentService.getParent(REPO, "R279-DOC")).thenReturn(parent);
            doNothing().when(f.exceptionService).constraintContentStreamDownload(any(), any());
            doNothing().when(f.exceptionService).objectNotFoundParentFolder(any(), any(), any());
            doNothing().when(f.exceptionService).invalidArgumentRootFolder(any(), any());

            AllowableActionsImpl actions = new AllowableActionsImpl();
            actions.setAllowableActions(java.util.Set.of(Action.CAN_GET_PROPERTIES));
            when(f.compileService.compileAllowableActions(f.callContext, REPO, document)).thenReturn(actions);
            Rendition rendition = new Rendition();
            rendition.setId("R279-REND");
            rendition.setMimetype("image/png");
            when(f.dao.getRendition(REPO, "R279-REND")).thenReturn(rendition);
            AttachmentNode attachment = new AttachmentNode();
            attachment.setId("R279-ATT");
            attachment.setName("r279.txt");
            attachment.setMimeType("text/plain");
            attachment.setLength(4);
            attachment.setInputStream(new ByteArrayInputStream("r279".getBytes(StandardCharsets.UTF_8)));
            when(f.contentService.getAttachment(REPO, "R279-ATT")).thenReturn(attachment);
            AccessControlListImpl acl = new AccessControlListImpl();
            when(f.compileService.compileAcl(any(), any(), any())).thenReturn(acl);
            ObjectDataImpl compiledParent = new ObjectDataImpl();
            when(f.compileService.compileObjectData(eq(f.callContext), eq(REPO), eq(parent), any(), any(), any(), any(), any()))
                    .thenReturn(compiledParent);
            Policy policy = new Policy();
            policy.setId("R279-POL");
            when(f.contentService.getAppliedPolicies(REPO, "R279-DOC", null)).thenReturn(List.of(policy));
            ObjectDataImpl compiledPolicy = new ObjectDataImpl();
            when(f.compileService.compileObjectData(eq(f.callContext), eq(REPO), eq(policy), any(), any(), any(), any(), any()))
                    .thenReturn(compiledPolicy);
            Relationship relationship = new Relationship();
            relationship.setId("R279-REL");
            when(f.contentService.getRelationsipsOfObject(REPO, "R279-DOC", RelationshipDirection.SOURCE))
                    .thenReturn(List.of(relationship));
            ObjectListImpl compiledRelationships = new ObjectListImpl();
            when(f.compileService.compileObjectDataList(eq(f.callContext), eq(REPO), eq(List.of(relationship)), any(), any(),
                    any(), any(), any(), any(), any(), anyBoolean(), any())).thenReturn(compiledRelationships);

            Object answered = assertDoesNotThrow(() -> reader.read(f, "R279-DOC"), reader + ": a document was refused");
            switch (reader) {
                case ALLOWABLE_ACTIONS -> assertSame(actions, (AllowableActions) answered, reader + ": not the compiled actions");
                case RENDITIONS -> {
                    @SuppressWarnings("unchecked")
                    List<RenditionData> renditions = (List<RenditionData>) answered;
                    assertEquals(1, renditions.size(), reader + ": not the document's renditions: " + renditions);
                    assertEquals("R279-REND", renditions.get(0).getStreamId(), reader + ": not the document's rendition");
                }
                case CONTENT_STREAM -> {
                    assertEquals("r279.txt", ((ContentStream) answered).getFileName(), reader + ": not the document's stream");
                    verify(f.exceptionService).permissionDenied(f.callContext, REPO,
                            PermissionMapping.CAN_GET_PROPERTIES_OBJECT, document);
                }
                case ACL -> {
                    assertSame(acl, answered, reader + ": not the compiled acl");
                    verify(f.exceptionService).permissionDenied(f.callContext, REPO, PermissionMapping.CAN_GET_ACL_OBJECT,
                            document);
                    verify(f.contentService).calculateAcl(REPO, document);
                }
                case PARENTS -> {
                    @SuppressWarnings("unchecked")
                    List<ObjectParentData> parents = (List<ObjectParentData>) answered;
                    assertEquals(1, parents.size(), reader + ": not the document's parent: " + parents);
                    assertSame(compiledParent, parents.get(0).getObject(), reader + ": not the compiled parent");
                    verify(f.exceptionService).permissionDenied(f.callContext, REPO, PermissionMapping.CAN_GET_PARENTS_FOLDER,
                            document);
                }
                case POLICIES -> {
                    @SuppressWarnings("unchecked")
                    List<ObjectData> applied = (List<ObjectData>) answered;
                    assertEquals(List.of(compiledPolicy), applied, reader + ": not the document's policies");
                    verify(f.exceptionService).permissionDenied(f.callContext, REPO,
                            PermissionMapping.CAN_GET_APPLIED_POLICIES_OBJECT, document);
                }
                case RELATIONSHIPS -> {
                    assertSame(compiledRelationships, (ObjectList) answered, reader + ": not the compiled relationships");
                    verify(f.exceptionService).permissionDenied(f.callContext, REPO,
                            PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, document);
                }
                default -> fail("a reader with no expectation: " + reader);
            }
        }
    }

    @Test
    @DisplayName("Each reader beside getObject answers an id that is nothing as before: 404 「The specified object is not found」, and renditions an empty list")
    void anIdThatIsNothingIsAnsweredAsBefore() {
        for (Reader reader : Reader.values()) {
            Fixture f = new Fixture().refusesPermission();
            Answer absent = Answer.of(reader, f, "GONE");
            if (reader == Reader.RENDITIONS) {
                assertTrue(absent.thrown() == null, reader + ": an id that is nothing was not answered with a list: " + absent);
                assertEquals(List.of(), absent.returned(), reader + ": an id that is nothing was not answered with an empty list");
            } else {
                assertTrue(absent.thrown() instanceof CmisBaseException, reader + ": an id that is nothing was answered " + absent);
                assertEquals("[objectId:GONE]The specified object is not found", absent.thrown().getMessage(), reader.toString());
                assertEquals(404, ((CmisBaseException) absent.thrown()).getCode().intValue(), reader.toString());
            }
            f.nothingWasDoneFor(reader + ": an id that is nothing");
        }
    }

    @Test
    @DisplayName("getRenditions refuses a document and a folder the user may not read with 403, before their renditions are read (R282)")
    void anUnreadableDocumentOrFolderIsRefusedItsRenditionsBeforeTheyAreRead() {
        for (Content stored : List.of(aDocumentWithRenditions("R282-DOC", "R282-DOC-REND"),
                aFolderWithRenditions("R282-FOLDER", "R282-FOLDER-REND"))) {
            Fixture f = new Fixture().refusesPermission().stores(stored);
            when(f.dao.getRendition(any(), any())).thenReturn(aRendition("R282-REND", "r282.pdf"));
            Answer answered = Answer.of(Reader.RENDITIONS, f, stored.getId());
            String who = "getRenditions of " + stored.getType() + " the user may not read";
            assertTrue(answered.thrown() instanceof CmisPermissionDeniedException, who + " was answered " + answered);
            assertEquals(403, ((CmisBaseException) answered.thrown()).getCode().intValue(), who);
            verify(f.exceptionService, description(who + ": not refused by the check getObject makes")).permissionDenied(
                    f.callContext, REPO, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, stored);
            verify(f.readingContentService, never().description(who + ": its renditions were read before it was refused"))
                    .getRenditions(any(), any());
            verify(f.dao, never().description(who + ": a rendition was read before it was refused")).getRendition(any(), any());
        }
    }

    @Test
    @DisplayName("A rendition whose title is the file name it was made from is not answered to a user who may not read the document, and its title is not read (R282)")
    void aRenditionTitledWithTheFileNameIsNotAnsweredToAUserWhoMayNotRead() {
        String fileName = "給与明細_山田太郎_2026-10.pdf";
        Rendition rendition = spy(aRendition("R282-NAMED-REND", fileName));
        Document document = aDocumentWithRenditions("R282-NAMED", "R282-NAMED-REND");
        Fixture f = new Fixture().refusesPermission().stores(document);
        when(f.dao.getRendition(REPO, "R282-NAMED-REND")).thenReturn(rendition);
        Answer answered = Answer.of(Reader.RENDITIONS, f, document.getId());
        assertTrue(answered.returned() == null && answered.thrown() instanceof CmisPermissionDeniedException,
                "a rendition titled " + fileName + " of a document the user may not read was answered: " + answered);
        assertTrue(!String.valueOf(answered.thrown().getMessage()).contains(fileName),
                "the refusal carries the file name: " + answered);
        verify(rendition, never().description("the title of a rendition of a document the user may not read was read"))
                .getTitle();
    }

    @Test
    @DisplayName("getRenditions answers a document and a folder the user may read their renditions, as before, after the check getObject makes (R282)")
    void aReadableDocumentOrFolderIsAnsweredItsRenditionsAsBefore() {
        for (Content stored : List.of(aDocumentWithRenditions("R282-OK-DOC", "R282-OK-DOC-REND"),
                aFolderWithRenditions("R282-OK-FOLDER", "R282-OK-FOLDER-REND"))) {
            Fixture f = new Fixture().grantsPermission().stores(stored);
            String renditionId = stored.getId() + "-REND";
            when(f.dao.getRendition(REPO, renditionId)).thenReturn(aRendition(renditionId, stored.getId() + ".pdf"));
            String who = "getRenditions of " + stored.getType() + " the user may read";
            Object answered = assertDoesNotThrow(() -> Reader.RENDITIONS.read(f, stored.getId()), who + " was refused");
            @SuppressWarnings("unchecked")
            List<RenditionData> renditions = (List<RenditionData>) answered;
            assertEquals(1, renditions.size(), who + ": not its renditions: " + renditions);
            assertEquals(renditionId, renditions.get(0).getStreamId(), who + ": not its rendition");
            assertEquals(stored.getId() + ".pdf", renditions.get(0).getTitle(), who + ": not its rendition's title");
            assertEquals("application/pdf", renditions.get(0).getMimeType(), who + ": not its rendition's mime type");
            verify(f.exceptionService, description(who + ": not checked as getObject checks it")).permissionDenied(f.callContext,
                    REPO, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, stored);
        }
    }

    @Test
    @DisplayName("getRenditions answers an item of each kind, a relationship and a policy the user may read an empty list — not a 500 — after the check getObject makes (R283)")
    void aReadableObjectWithNoRenditionsIsAnsweredAnEmptyList() {
        theObjectsWithNoRenditions().forEach((what, stored) -> {
            Fixture f = new Fixture().grantsPermission().stores(stored);
            String who = "getRenditions of " + what + " the user may read";
            Object answered = assertDoesNotThrow(() -> Reader.RENDITIONS.read(f, stored.getId()),
                    who + " was not answered with a list");
            assertEquals(List.of(), answered, who + " was not answered with an empty list");
            verify(f.exceptionService, description(who + ": not checked as getObject checks it")).permissionDenied(f.callContext,
                    REPO, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, stored);
        });
    }

    @Test
    @DisplayName("getRenditions refuses an item of each kind, a relationship and a policy the user may not read with 403 — the check is not skipped for an object with no renditions (R283)")
    void anUnreadableObjectWithNoRenditionsIsRefused() {
        theObjectsWithNoRenditions().forEach((what, stored) -> {
            Fixture f = new Fixture().refusesPermission().stores(stored);
            Answer answered = Answer.of(Reader.RENDITIONS, f, stored.getId());
            String who = "getRenditions of " + what + " the user may not read";
            assertTrue(answered.thrown() instanceof CmisPermissionDeniedException, who + " was answered " + answered);
            assertEquals(403, ((CmisBaseException) answered.thrown()).getCode().intValue(), who);
        });
    }

    @Test
    @DisplayName("Each reader beside getObject throws a getContent that failed — a RuntimeException or an Error — as it is, not as an id that does not exist")
    void aReadThatFailedIsThrownAsItIs() {
        for (Reader reader : Reader.values()) {
            for (Throwable failed : failedReads()) {
                Fixture f = new Fixture().refusesPermission();
                when(f.contentService.getContent(REPO, "R279-UNREADABLE")).thenThrow(failed);
                when(f.dao.getContent(REPO, "R279-UNREADABLE")).thenThrow(failed);
                Answer answered = Answer.of(reader, f, "R279-UNREADABLE");
                assertSame(failed, answered.thrown(), reader + ": a read that failed (" + failed + ") was answered " + answered);
            }
        }
    }

    @Test
    @DisplayName("getRenditions throws a rendition read that failed — a RuntimeException or an Error — as it is, not as an empty list (R282)")
    void aRenditionReadThatFailedIsThrownAsItIs() {
        for (Content stored : List.of(aDocumentWithRenditions("R282-BAD-DOC", "R282-BAD-REND"),
                aFolderWithRenditions("R282-BAD-FOLDER", "R282-BAD-REND"))) {
            for (Throwable failed : failedReads()) {
                Fixture f = new Fixture().grantsPermission().stores(stored);
                when(f.dao.getRendition(REPO, "R282-BAD-REND")).thenThrow(failed);
                Answer answered = Answer.of(Reader.RENDITIONS, f, stored.getId());
                assertSame(failed, answered.thrown(), "getRenditions of " + stored.getType() + ": a rendition read that failed ("
                        + failed + ") was answered " + answered);
            }
        }
    }
}
