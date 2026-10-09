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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.v1.exception.ApiExceptionMapper;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Jersey deletes answer a CMIS refusal as the refusal it is (R115).
 *
 * <p>{@code /items/{id}} and {@code /objects/{id}} reach CMIS {@code deleteObject}, which refuses a
 * protected user's item with {@code permissionDenied}, and a second document for a user with
 * {@code constraint}. Both endpoints wrapped every exception into an internal error, so the
 * refusal reached the caller as a 500. Each endpoint is driven as a request reaches it, and what it
 * throws is answered by the {@code ApiExceptionMapper} Jersey answers it with.
 */
class JerseyDeletesAnswerARefusalAsARefusalTest {

    private static final String REPO = "bedroom";
    private static final String PROTECTED = "user 'admin' is never deleted: it is the built-in administrator";
    private static final String UNDECIDED = "which document is the user cannot be decided here, so nothing was deleted";

    private ObjectService objectService;
    private RepositoryService repositoryService;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        objectService = mock(ObjectService.class);
        when(objectService.getObject(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mock(ObjectData.class));
        doThrow(new CmisPermissionDeniedException(PROTECTED))
                .when(objectService).deleteObject(any(), eq(REPO), eq("admin-doc"), any(), any());
        doThrow(new CmisConstraintException(UNDECIDED))
                .when(objectService).deleteObject(any(), eq(REPO), eq("second-doc"), any(), any());

        repositoryService = mock(RepositoryService.class);
        when(repositoryService.getRepositoryInfo(REPO)).thenReturn(mock(RepositoryInfo.class));
        when(repositoryService.hasThisRepositoryId(REPO)).thenReturn(true);

        request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(mock(CallContext.class));
    }

    private void wire(Object resource) throws Exception {
        set(resource, "objectService", objectService);
        set(resource, "repositoryService", repositoryService);
        set(resource, "httpRequest", request);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void answeredAs(int status, String detail, Executable call) {
        Throwable thrown = assertThrows(Throwable.class, call);
        Response response = new ApiExceptionMapper().toResponse(thrown);
        assertEquals(status, response.getStatus(), String.valueOf(response.getEntity()));
        String said = ((ProblemDetail) response.getEntity()).getDetail();
        assertTrue(said != null && said.contains(detail), said);
    }

    @Test
    @DisplayName("/items/{id} answers a protected user's refusal as 403 and an undecided user as 422")
    void theItemsDeleteAnswersARefusalAsOne() throws Exception {
        ItemResource items = new ItemResource();
        wire(items);
        answeredAs(403, PROTECTED, () -> items.deleteItem(REPO, "admin-doc", true));
        answeredAs(422, UNDECIDED, () -> items.deleteItem(REPO, "second-doc", true));
    }

    @Test
    @DisplayName("/objects/{id} answers a protected user's refusal as 403 and an undecided user as 422")
    void theObjectsDeleteAnswersARefusalAsOne() throws Exception {
        ObjectResource objects = new ObjectResource();
        wire(objects);
        answeredAs(403, PROTECTED, () -> objects.deleteObject(REPO, "admin-doc", true));
        answeredAs(422, UNDECIDED, () -> objects.deleteObject(REPO, "second-doc", true));
    }
}
