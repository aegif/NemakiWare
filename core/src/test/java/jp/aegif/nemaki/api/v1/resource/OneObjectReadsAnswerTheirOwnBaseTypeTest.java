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
package jp.aegif.nemaki.api.v1.resource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.api.v1.exception.ApiExceptionMapper;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.response.ObjectResponse;
import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.util.constant.DomainType;

/**
 * The API v1 one-object reads answer only their own base type (R276 — 3.4.2). {@code /documents/{id}}, {@code /folders/{id}}
 * and {@code /items/{id}} handed what {@code getObject} answered to the response as it was: {@code /folders/<a document>} and
 * {@code /items/<a document>} answered the document, {@code /documents/<the root>} the root folder. Judged by the object's
 * {@code cmis:baseTypeId}, so a subtype is its base type's and an {@code objectTypeId} that names another type decides nothing.
 * A document or folder read of another base type is answered as that resource answers an id that does not exist (a 500 with
 * the same body — the owner's decision: the catch and the mapper are not changed); an item read of another base type is
 * answered 404. A base type that cannot be read is a failure, not an object of another type, and a read that failed is
 * answered as it was.
 *
 * <p>The resources are the product's, answered by the {@code ApiExceptionMapper} Jersey answers them with. {@code getObject}
 * answers an id that does not exist with the product's own not-found ({@code ExceptionServiceImpl}).
 */
class OneObjectReadsAnswerTheirOwnBaseTypeTest {

    private static final String REPO = "bedroom";

    private static final String GONE = "R276-GONE";

    /** A resource and how it is read. */
    private enum Resource {
        DOCUMENTS("cmis:document") {
            @Override
            Response read(Fixture f, String id) throws Exception {
                DocumentResource r = new DocumentResource();
                f.wire(r);
                return r.getDocument(REPO, id, null, false);
            }
        },
        FOLDERS("cmis:folder") {
            @Override
            Response read(Fixture f, String id) throws Exception {
                FolderResource r = new FolderResource();
                f.wire(r);
                return r.getFolder(REPO, id, null, false);
            }
        },
        ITEMS("cmis:item") {
            @Override
            Response read(Fixture f, String id) throws Exception {
                ItemResource r = new ItemResource();
                f.wire(r);
                return r.getItem(REPO, id);
            }
        };

        final String baseType;

        Resource(String baseType) {
            this.baseType = baseType;
        }

        abstract Response read(Fixture f, String id) throws Exception;
    }

    private static final List<String> BASE_TYPES = List.of("cmis:document", "cmis:folder", "cmis:item", "cmis:relationship",
            "cmis:policy", "cmis:secondary");

    /** An object as getObject compiles one: its id, base type, object type and name. */
    private static ObjectData anObject(String id, String baseType, String objectType) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, id));
        if (baseType != null) {
            properties.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, baseType));
        }
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, objectType));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, "r276-" + id));
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    /** The product's resources over a getObject that answers what it is given. */
    private static final class Fixture {
        final ObjectService objectService = mock(ObjectService.class);
        final RepositoryService repositoryService = mock(RepositoryService.class);
        final HttpServletRequest request = mock(HttpServletRequest.class);
        final UriInfo uriInfo = mock(UriInfo.class);

        Fixture() {
            when(repositoryService.getRepositoryInfo(REPO)).thenReturn(mock(RepositoryInfo.class));
            when(repositoryService.hasThisRepositoryId(REPO)).thenReturn(true);
            when(request.getAttribute("CallContext")).thenReturn(mock(CallContext.class));
            when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:8080/core/api/v1/"));
            when(objectService.getObject(any(), eq(REPO), eq(GONE), any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        new ExceptionServiceImpl().objectNotFound(DomainType.OBJECT, null, GONE);
                        return null;
                    });
        }

        Fixture answers(ObjectData object) {
            String id = (String) object.getProperties().getProperties().get(PropertyIds.OBJECT_ID).getFirstValue();
            when(objectService.getObject(any(), eq(REPO), eq(id), any(), any(), any(), any(), any(), any(), any())).thenReturn(object);
            return this;
        }

        void wire(Object resource) throws Exception {
            set(resource, "objectService", objectService);
            set(resource, "repositoryService", repositoryService);
            set(resource, "httpRequest", request);
            set(resource, "uriInfo", uriInfo);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** What the resource answered for {@code id}: the response, or the mapper's answer for what it threw. */
    private static Response answer(Resource resource, Fixture f, String id) {
        try {
            return resource.read(f, id);
        } catch (RuntimeException thrown) {
            return new ApiExceptionMapper().toResponse(thrown);
        } catch (Exception checked) {
            // What the resource under test threw is its answer, not this lock failing to look.
            throw new java.lang.reflect.UndeclaredThrowableException(checked, "the resource threw a checked exception");
        }
    }

    private static ProblemDetail problem(Response response, String who) {
        assertTrue(response.getEntity() instanceof ProblemDetail, who + " was answered " + response.getStatus() + " with "
                + response.getEntity());
        return (ProblemDetail) response.getEntity();
    }

    @Test
    void anObjectOfTheResourcesOwnBaseTypeIsAnsweredAsBefore() {
        for (Resource resource : Resource.values()) {
            for (String objectType : List.of(resource.baseType, "r276:custom")) {
                Fixture f = new Fixture().answers(anObject("R276-OWN", resource.baseType, objectType));
                String who = resource + " of its own base type (objectType " + objectType + ")";
                Response response = assertDoesNotThrow(() -> resource.read(f, "R276-OWN"), who + " was refused");
                assertEquals(200, response.getStatus(), who + " was not answered");
                assertTrue(response.getEntity() instanceof ObjectResponse, who + " was answered with " + response.getEntity());
                assertEquals("R276-OWN", ((ObjectResponse) response.getEntity()).getObjectId(), who);
                assertEquals(resource.baseType, ((ObjectResponse) response.getEntity()).getBaseTypeId(), who);
            }
        }
    }

    @Test
    void aDocumentOrFolderReadOfAnotherBaseTypeIsAnsweredAsAnIdThatDoesNotExist() {
        for (Resource resource : List.of(Resource.DOCUMENTS, Resource.FOLDERS)) {
            ProblemDetail gone = problem(answer(resource, new Fixture(), GONE), resource + " of an id that does not exist");
            Response goneResponse = answer(resource, new Fixture(), GONE);
            for (String baseType : BASE_TYPES) {
                if (baseType.equals(resource.baseType)) {
                    continue;
                }
                // The objectTypeId names the resource's type: the base type decides.
                for (String objectType : List.of(baseType, resource.baseType)) {
                    Fixture f = new Fixture().answers(anObject("R276-OTHER", baseType, objectType));
                    String who = resource + " of " + baseType + " (objectType " + objectType + ")";
                    Response response = answer(resource, f, "R276-OTHER");
                    assertNotEquals(200, response.getStatus(), who + " was answered as the object");
                    ProblemDetail said = problem(response, who);
                    assertEquals(goneResponse.getStatus(), response.getStatus(), who + " is not answered as an id that does not exist");
                    assertEquals(gone.getType(), said.getType(), who);
                    assertEquals(gone.getTitle(), said.getTitle(), who);
                    assertEquals(gone.getDetail().replace(GONE, "R276-OTHER"), said.getDetail(),
                            who + " is not answered as an id that does not exist");
                }
            }
        }
    }

    @Test
    void anItemReadOfAnotherBaseTypeIsAnswered404() {
        for (String baseType : BASE_TYPES) {
            if (baseType.equals("cmis:item")) {
                continue;
            }
            for (String objectType : List.of(baseType, "cmis:item")) {
                Fixture f = new Fixture().answers(anObject("R276-OTHER", baseType, objectType));
                String who = "items of " + baseType + " (objectType " + objectType + ")";
                Response response = answer(Resource.ITEMS, f, "R276-OTHER");
                assertEquals(404, response.getStatus(), who + " was not answered 404: " + response.getEntity());
                ProblemDetail said = problem(response, who);
                assertTrue(said.getType().endsWith("/object-not-found"), who + ": " + said.getType());
                assertTrue(said.getDetail().contains("R276-OTHER"), who + ": " + said.getDetail());
            }
        }
    }

    @Test
    void aBaseTypeThatCannotBeReadIsAFailure() {
        for (Resource resource : Resource.values()) {
            ProblemDetail gone = problem(answer(resource, new Fixture(), GONE), resource + " of an id that does not exist");
            for (String baseType : new String[] { null, "", "r276:not-a-base-type" }) {
                Fixture f = new Fixture().answers(anObject("R276-UNREAD", baseType, resource.baseType));
                String who = resource + " of an object whose base type is " + baseType;
                Response response = answer(resource, f, "R276-UNREAD");
                assertEquals(500, response.getStatus(), who + " was not answered as a failure: " + response.getEntity());
                ProblemDetail said = problem(response, who);
                assertNotEquals(gone.getDetail().replace(GONE, "R276-UNREAD"), said.getDetail(),
                        who + " was answered as an id that does not exist");
                assertTrue(said.getDetail().contains("base type"), who + ": " + said.getDetail());
            }
        }
    }

    /** An Error a read throws. Not an AssertionError, so a lock that lets it through is not taken for one that fired. */
    private static final class TheReadFailed extends Error {
        private static final long serialVersionUID = 1L;

        TheReadFailed() {
            super("r276: the read failed");
        }
    }

    @Test
    void aReadThatFailedIsAnsweredAsBefore() {
        for (Resource resource : Resource.values()) {
            TheReadFailed failure = new TheReadFailed();
            Fixture f = new Fixture();
            when(f.objectService.getObject(any(), eq(REPO), eq("R276-ERROR"), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(failure);
            Throwable thrown = assertThrows(Throwable.class, () -> resource.read(f, "R276-ERROR"));
            assertSame(failure, thrown, resource + ": an Error was answered as " + thrown);

            Fixture g = new Fixture();
            when(g.objectService.getObject(any(), eq(REPO), eq("R276-RUNTIME"), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("r276: CouchDB did not answer"));
            Response response = answer(resource, g, "R276-RUNTIME");
            assertEquals(500, response.getStatus(), resource + ": a read that failed was answered " + response.getStatus());
            assertTrue(problem(response, resource.toString()).getDetail().contains("CouchDB did not answer"), resource.toString());
        }
    }
}
