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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.api.v1.exception.ApiExceptionMapper;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.resource.ObjectResource;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.service.ObjectServiceInternal;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.dao.impl.couch.ContentDaoServiceImpl;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.model.WebAuthnCredential;
import jp.aegif.nemaki.util.constant.DomainType;

/**
 * A delete answers only CMIS objects (R281, R288 — 3.4.2). {@code ObjectServiceImpl.deleteObject} handed what
 * {@code getContent} read to {@code deleteObjectInternal} as it was: a null content — an id that does not exist — was skipped
 * there with a warning, so the API v1 delete answered 204 for it while the Browser binding's answered 404 (R288); and a CouchDB
 * document that is not a CMIS object was deleted as one — an administrator's API v1 delete of a document's content stream id
 * removed the attachment row and left the document pointing at it (R281). Both are answered as an id that does not exist
 * ({@code CmisObjectNotFoundException}, 404), right after {@code getContent} and before {@code deleteObjectInternal} — to every
 * user, so a user who is not an administrator is not answered 403 for an internal row. A CMIS object is deleted as before. A
 * {@code getContent} that cannot read is thrown as it is.
 *
 * <p>The exception service is the product's ({@link ExceptionServiceImpl}); the internal rows are the ones the product's DAO
 * reads back from the shapes stored in CouchDB. {@code deleteObjectInternal} is stubbed to refuse as it refuses a user who is
 * not an administrator, so a row that reached it would be answered 403, not as an id that does not exist. The API v1 delete is
 * driven through {@code ObjectResource} and answered by the {@code ApiExceptionMapper} Jersey answers it with.
 */
class DeleteAnswersOnlyCmisObjectsTest {

    private static final String REPO = "bedroom";

    private static final String PASSKEY = "nemaki:webauthnCredential";

    /** Everything a delete reads, with the product's exception service. */
    private static final class Fixture {
        final ContentService contentService = mock(ContentService.class);
        final ObjectServiceInternal internal = mock(ObjectServiceInternal.class);
        final SolrUtil solrUtil = mock(SolrUtil.class);
        final CallContext caller = mock(CallContext.class);
        final ObjectServiceImpl objects = new ObjectServiceImpl();

        Fixture() {
            objects.setContentService(contentService);
            objects.setExceptionService(new ExceptionServiceImpl());
            objects.setObjectServiceInternal(internal);
            objects.setSolrUtil(solrUtil);
        }

        /** As a user who is not an administrator: what reaches {@code deleteObjectInternal} is refused there with 403. */
        Fixture refusesWhatReachesTheDelete() {
            doThrow(new CmisPermissionDeniedException("r281: this user may not", BigInteger.valueOf(403))).when(internal)
                    .deleteObjectInternal(any(), any(), anyString(), any(), any());
            return this;
        }

        Fixture stores(Content content) {
            when(contentService.getContent(REPO, content.getId())).thenReturn(content);
            return this;
        }

        void nothingWasDeleted(String who) {
            verify(internal, never()).deleteObjectInternal(any(), any(), anyString(), any(), any());
            verify(internal, never()).deleteObjectInternal(any(), any(), any(Content.class), any(), any());
            verify(solrUtil, never()).invalidateFolderHierarchyCache(any());
        }

        /** The API v1 delete, with this fixture's object service behind it. */
        ObjectResource apiV1() throws Exception {
            ObjectResource resource = new ObjectResource();
            RepositoryService repositories = mock(RepositoryService.class);
            when(repositories.getRepositoryInfo(REPO)).thenReturn(mock(RepositoryInfo.class));
            when(repositories.hasThisRepositoryId(REPO)).thenReturn(true);
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getAttribute("CallContext")).thenReturn(caller);
            set(resource, "objectService", objects);
            set(resource, "repositoryService", repositories);
            set(resource, "httpRequest", request);
            return resource;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** What the product answers for an id that does not exist. */
    private static CmisObjectNotFoundException theAnswerForAnIdThatDoesNotExist(String id) {
        return assertThrows(CmisObjectNotFoundException.class,
                () -> new ExceptionServiceImpl().objectNotFound(DomainType.OBJECT, null, id));
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
        doc.setRev("1-r281");
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
        Content series = readByTheDao("R281-SERIES", fields);
        assertEquals("versionSeries", series.getType(), "the DAO read a version series row with another type");
        return series;
    }

    private static Content anAttachmentRow() {
        Map<String, Object> fields = aStoredNode("attachment");
        fields.put("attachment", true);
        fields.put("name", "r281-quarterly-scan.pdf");
        fields.put("length", 36L);
        fields.put("actualLength", 36L);
        fields.put("mimeType", "application/pdf");
        fields.put("actualMimeType", "application/pdf");
        Content stream = readByTheDao("R281-STREAM", fields);
        assertEquals("attachment", stream.getType(), "the DAO read an attachment row with another type");
        return stream;
    }

    @SuppressWarnings("unchecked")
    private static Content aStoredPasskey() {
        WebAuthnCredential credential = new WebAuthnCredential(new NodeBase());
        credential.setUserId("alice");
        credential.setCredentialId("R281-CREDENTIAL");
        credential.setPublicKeyCose("pQECAyYgASFYIA");
        credential.setSignCount(3L);
        credential.setAaguid("00000000000000000000000000000000");
        credential.setDisplayName("alice's r281 key");
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
        Content passkey = readByTheDao("R281-PASSKEY", fields);
        assertEquals(PASSKEY, passkey.getObjectType(), "the DAO no longer reads a passkey's objectType");
        return passkey;
    }

    private static Map<String, Content> theInternalRows() {
        Map<String, Content> rows = new LinkedHashMap<>();
        rows.put("a version series id", aVersionSeriesRow());
        rows.put("a content stream id", anAttachmentRow());
        rows.put("a passkey's id", aStoredPasskey());
        return rows;
    }

    /** What an internal row says that an answer must not repeat: its type, its file name, its passkey's names. */
    private static final List<String> WHAT_A_ROW_SAYS = List.of("versionSeries", "attachment", "r281-quarterly-scan.pdf",
            "application/pdf", PASSKEY, "R281-CREDENTIAL", "alice's r281 key", "ALICE-ACCOUNT", "webauthn");

    private static Content aCmisObject(String id, String baseType, String objectType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", baseType);
        fields.put("objectType", objectType);
        fields.put("name", "r281-" + id);
        Content content = readByTheDao(id, fields);
        assertEquals(baseType, content.getType(), baseType + " was read back with another type");
        return content;
    }

    /** A user as it is stored — an item with objectType nemaki:user — read back by the DAO. */
    private static Content aStoredUser() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "cmis:item");
        fields.put("objectType", "nemaki:user");
        fields.put("name", "r281-user");
        fields.put("userId", "r281-user");
        fields.put("passwordHash", "$2a$10$r281r281r281r281r281r.r281r281r281r281r281r281r281r28");
        fields.put("admin", false);
        Content user = readByTheDao("R281-USER", fields);
        assertEquals("nemaki:user", user.getObjectType(), "a user was read back with another objectType");
        return user;
    }

    private static Map<String, Content> theCmisObjects() {
        Map<String, Content> objects = new LinkedHashMap<>();
        objects.put("a document", aCmisObject("R281-DOC", "cmis:document", "cmis:document"));
        objects.put("a folder", aCmisObject("R281-FOLDER", "cmis:folder", "cmis:folder"));
        objects.put("an item", aCmisObject("R281-ITEM", "cmis:item", "cmis:item"));
        objects.put("a user's item", aStoredUser());
        objects.put("an item whose objectType resembles a passkey's", aCmisObject("R281-NEAR", "cmis:item",
                "nemaki:webauthnCredentials"));
        objects.put("a relationship", aCmisObject("R281-REL", "cmis:relationship", "cmis:relationship"));
        objects.put("a policy", aCmisObject("R281-POL", "cmis:policy", "cmis:policy"));
        return objects;
    }

    // ----- the CMIS delete -----

    @Test
    void anIdThatDoesNotExistIsAnswered404AndNothingIsDeleted() {
        Fixture f = new Fixture().refusesWhatReachesTheDelete();
        Throwable thrown = assertThrows(Throwable.class,
                () -> f.objects.deleteObject(f.caller, REPO, "R288-GONE", true, null), "an id that does not exist was deleted");
        assertTrue(thrown instanceof CmisObjectNotFoundException, "an id that does not exist was answered as " + thrown);
        CmisObjectNotFoundException expected = theAnswerForAnIdThatDoesNotExist("R288-GONE");
        assertEquals(expected.getMessage(), thrown.getMessage(), "not the answer for an id that does not exist");
        assertEquals(BigInteger.valueOf(404), ((CmisObjectNotFoundException) thrown).getCode());
        f.nothingWasDeleted("an id that does not exist");
    }

    @Test
    void anInternalRowIsAnsweredAsAnIdThatDoesNotExistAndNothingIsDeleted() {
        theInternalRows().forEach((what, row) -> {
            Fixture f = new Fixture().refusesWhatReachesTheDelete().stores(row);
            Throwable thrown = assertThrows(Throwable.class,
                    () -> f.objects.deleteObject(f.caller, REPO, row.getId(), true, null), what + " was deleted");
            assertTrue(thrown instanceof CmisObjectNotFoundException, what + " was answered as " + thrown
                    + " — a user who is not an administrator is refused 403 only by what reaches the delete");
            assertEquals(theAnswerForAnIdThatDoesNotExist(row.getId()).getMessage(), thrown.getMessage(),
                    what + " is not answered as an id that does not exist");
            assertEquals(BigInteger.valueOf(404), ((CmisObjectNotFoundException) thrown).getCode(), what);
            f.nothingWasDeleted(what);
        });
    }

    @Test
    void aCmisObjectIsDeletedAsBefore() {
        theCmisObjects().forEach((what, object) -> {
            Fixture f = new Fixture().stores(object);
            assertDoesNotThrow(() -> f.objects.deleteObject(f.caller, REPO, object.getId(), true, null),
                    what + " was not deleted");
            verify(f.internal, times(1)).deleteObjectInternal(f.caller, REPO, object.getId(), true, false);
            boolean folder = Boolean.TRUE.equals(object.isFolder());
            verify(f.solrUtil, times(folder ? 1 : 0)).invalidateFolderHierarchyCache(REPO);
        });
    }

    /** What a read that failed throws. Neither an assertion nor an absence. */
    private static final class TheReadFailed extends Error {
        private static final long serialVersionUID = 1L;

        TheReadFailed(String what) {
            super(what);
        }
    }

    @Test
    void aReadThatFailedIsThrownAsItIs() {
        for (Throwable failure : List.of(new IllegalStateException("r288: CouchDB did not answer"),
                new TheReadFailed("r288: the read failed"))) {
            Fixture f = new Fixture().refusesWhatReachesTheDelete();
            when(f.contentService.getContent(REPO, "R288-UNREAD")).thenThrow(failure);
            Throwable thrown = assertThrows(Throwable.class,
                    () -> f.objects.deleteObject(f.caller, REPO, "R288-UNREAD", true, null), "a failed read was deleted");
            assertSame(failure, thrown, "a read that failed was answered as " + thrown);
            f.nothingWasDeleted("a read that failed");
        }
    }

    @Test
    void anIdThatIsNotGivenIsAnInvalidArgument() {
        for (String id : new String[] { null, "" }) {
            Fixture f = new Fixture().refusesWhatReachesTheDelete();
            Throwable thrown = assertThrows(Throwable.class, () -> f.objects.deleteObject(f.caller, REPO, id, true, null));
            assertTrue(thrown instanceof CmisInvalidArgumentException, "an objectId of " + id + " was answered as " + thrown);
            verify(f.contentService, never()).getContent(any(), any());
            f.nothingWasDeleted("an id that is not given");
        }
    }

    // ----- the API v1 delete -----

    /** The API v1 delete threw {@code thrown}; what Jersey answers for it. */
    private static ProblemDetail answered(Throwable thrown, int status, String who) {
        Response response = new ApiExceptionMapper().toResponse(thrown);
        assertEquals(status, response.getStatus(), who + ": " + response.getEntity());
        return (ProblemDetail) response.getEntity();
    }

    @Test
    void theApiV1DeleteAnswers404ForAnIdThatDoesNotExistAndForAnInternalRow() throws Exception {
        Map<String, Content> cases = new LinkedHashMap<>(theInternalRows());
        cases.put("an id that does not exist", null);
        for (Map.Entry<String, Content> c : cases.entrySet()) {
            String what = c.getKey();
            Fixture f = new Fixture().refusesWhatReachesTheDelete();
            String id = c.getValue() == null ? "R288-GONE" : c.getValue().getId();
            if (c.getValue() != null) {
                f.stores(c.getValue());
            }
            ObjectResource resource = f.apiV1();
            Throwable thrown = assertThrows(Throwable.class, () -> resource.deleteObject(REPO, id, true),
                    "the API v1 delete of " + what + " answered 204");
            assertTrue(thrown instanceof CmisObjectNotFoundException,
                    "the API v1 delete of " + what + " did not pass the not-found answer through as itself: " + thrown);
            ProblemDetail problem = answered(thrown, 404, "the API v1 delete of " + what);
            String said = problem.getType() + " " + problem.getTitle() + " " + problem.getDetail();
            for (String word : WHAT_A_ROW_SAYS) {
                assertFalse(said.contains(word), "the API v1 delete of " + what + " says \"" + word + "\": " + said);
            }
            f.nothingWasDeleted("the API v1 delete of " + what);
        }
    }

    @Test
    void theApiV1DeleteOfADocumentAnswers204AndDeletesIt() throws Exception {
        Content document = theCmisObjects().get("a document");
        Fixture f = new Fixture().stores(document);
        ObjectResource resource = f.apiV1();
        Response response = assertDoesNotThrow(() -> resource.deleteObject(REPO, document.getId(), true),
                "the API v1 delete of a document was refused");
        assertEquals(204, response.getStatus(), "the API v1 delete of a document did not answer 204");
        verify(f.internal, times(1)).deleteObjectInternal(f.caller, REPO, document.getId(), true, false);
    }

    @Test
    void theApiV1DeleteDoesNotAnswerAFailureAsAnAbsence() throws Exception {
        Fixture f = new Fixture();
        when(f.contentService.getContent(REPO, "R288-FAILED")).thenThrow(
                new IllegalStateException("the design document was not found on this node"));
        ObjectResource resource = f.apiV1();
        Throwable thrown = assertThrows(Throwable.class, () -> resource.deleteObject(REPO, "R288-FAILED", true));
        answered(thrown, 500, "a read that failed with words that say \"not found\"");
        f.nothingWasDeleted("a read that failed");
        verify(f.internal, never()).deleteObjectInternal(any(), any(), anyString(), anyBoolean(), anyBoolean());
    }
}
