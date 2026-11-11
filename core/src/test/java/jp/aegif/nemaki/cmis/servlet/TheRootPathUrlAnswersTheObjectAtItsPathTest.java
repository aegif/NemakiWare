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
package jp.aegif.nemaki.cmis.servlet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AllowableActionsImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.CmisRepositoryContextListener;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * A GET of {@code /browser/{repo}/root/<path>} with a selector names the object at {@code <path>} (R297 — 3.4.2). The servlet
 * read only the first two segments, dropped the path and answered the root folder — for a path that names nothing too. The
 * path is now read with {@code getObjectByPath} as OpenCMIS reads it for the same URL with no selector (each segment decoded
 * once, empty ones skipped), and {@code object}, {@code properties} and {@code children} — and the other selectors this route
 * answers — are answered for the object at the path. A path that names nothing is answered 404, and a read that failed as a
 * failure, never with the root folder. The path names the object, so the {@code objectId} parameter is not read. With nothing
 * after root the root folder is answered as before, and the {@code /browser/{repo}/{objectId}} form is not changed.
 *
 * <p>The route and the path are read from the same segments — the request URI as it was sent, as OpenCMIS reads it — not from
 * {@code pathInfo}, which the container normalizes (R298): {@code root/.}, {@code root/<name>/..}, {@code root/;x=1} and
 * {@code root/../<id>} name nothing and are answered 404, not as the root or the object the id names, and no other URL the
 * container normalized is folded into the root or an id. The {@code objectId} parameter is hidden by its decoded name (R299),
 * and the query string handed on to OpenCMIS — which answers {@code parents}, the upper-case {@code OBJECT} and any selector
 * this servlet does not — is built from the decoded parameters only, each name and value encoded (R301): OpenCMIS decodes the
 * query string again, and read {@code OBJECTI%44} as the object, and a selector {@code object%26objectId%3D<id>} as the
 * selector {@code object} with a first objectId {@code <id>}. That selector is the one value {@code object&objectId=<id>},
 * no selector OpenCMIS knows, and is answered 405.
 *
 * <p>The servlet is the product's, initialized as the container initializes it and driven through its {@code service} — the
 * routing of the URL, the parameter parsing, the JSON and error writers, and OpenCMIS's own dispatcher for the selectors it
 * answers; only the service factory, which the container provides, is stood in for. The request is given as the container
 * gives it: {@code getPathInfo} decoded and normalized, the request URI and the query string as they were sent, and the
 * parameters with their names and values decoded.
 */
class TheRootPathUrlAnswersTheObjectAtItsPathTest {

    private static final String REPO = "bedroom";

    private static final String ROOT_ID = "R297-ROOT";

    private static final String AT_PATH = "R297-AT-PATH";

    private static final String NAMED_BY_PARAMETER = "R297-BY-PARAMETER";

    /** The product's servlet, initialized as the container initializes it, with the service factory the container provides. */
    private static final class Driven extends NemakiBrowserBindingServlet {
        private static final long serialVersionUID = 1L;

        Driven(CmisService service) {
            CmisServiceFactory factory = mock(CmisServiceFactory.class);
            when(factory.getService(any())).thenReturn(service);
            ServletContext context = mock(ServletContext.class);
            when(context.getAttribute(CmisRepositoryContextListener.SERVICES_FACTORY)).thenReturn(factory);
            ServletConfig config = mock(ServletConfig.class);
            when(config.getServletContext()).thenReturn(context);
            when(config.getInitParameterNames()).thenReturn(Collections.emptyEnumeration());
            try {
                init(config);
            } catch (ServletException harness) {
                // Said as breakage: every request of this lock is sent inside assertDoesNotThrow, which would report any
                // other type thrown here as the lock's own assertion.
                throw new HarnessBroken("the servlet could not be initialized", harness);
            }
        }
    }

    /** What the servlet answered: the status and the body. */
    private record Answered(int status, String body) {
    }

    private static ObjectData anObject(String id, String baseType) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, id));
        properties.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, baseType));
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, baseType));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, "r297-" + id));
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    private static ObjectInFolderListImpl noChildren() {
        ObjectInFolderListImpl children = new ObjectInFolderListImpl();
        children.setObjects(new java.util.ArrayList<>());
        children.setHasMoreItems(false);
        children.setNumItems(java.math.BigInteger.ZERO);
        return children;
    }

    /**
     * A service in which {@code /a/b} (and the other paths the tests name) is {@link #AT_PATH}, and any other path is the
     * product's not-found by path; each read of an id answers that id.
     */
    private static CmisService aService() {
        CmisService service = mock(CmisService.class);
        RepositoryInfo info = mock(RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn(ROOT_ID);
        when(service.getRepositoryInfo(eq(REPO), any())).thenReturn(info);
        when(service.getObjectByPath(eq(REPO), anyString(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(1, String.class);
            new ExceptionServiceImpl().objectNotFoundByPath(DomainType.OBJECT, null, path);
            return null;
        });
        for (String path : new String[] { "/a/b", "/a b/c", "/x%41" }) {
            when(service.getObjectByPath(eq(REPO), eq(path), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(anObject(AT_PATH, "cmis:folder"));
        }
        when(service.getObject(eq(REPO), anyString(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            new ExceptionServiceImpl().objectNotFound(DomainType.OBJECT, null, invocation.getArgument(1, String.class));
            return null;
        });
        when(service.getObjectParents(eq(REPO), anyString(), any(), any(), any(), any(), any(), any())).thenReturn(new java.util.ArrayList<>());
        for (String id : new String[] { ROOT_ID, AT_PATH, NAMED_BY_PARAMETER }) {
            when(service.getObject(eq(REPO), eq(id), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(anObject(id, "cmis:folder"));
            when(service.getProperties(eq(REPO), eq(id), any(), any())).thenReturn(anObject(id, "cmis:folder").getProperties());
            when(service.getChildren(eq(REPO), eq(id), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(noChildren());
            when(service.getAllowableActions(eq(REPO), eq(id), any())).thenReturn(new AllowableActionsImpl());
        }
        return service;
    }

    /**
     * A GET of {@code /core/browser/bedroom<rawPath>?<parameters>} through the servlet's {@code service}. {@code rawPath} is the
     * path as it is sent (percent-encoded); the container gives {@code getPathInfo} decoded and normalized, as {@code pathInfo}.
     * The parameters are sent as they are named.
     */
    private static Answered get(CmisService service, String rawPath, String pathInfo, Map<String, String> parameters) {
        StringBuilder query = new StringBuilder();
        parameters.forEach((k, v) -> query.append(query.length() == 0 ? "" : "&").append(k).append('=').append(v));
        return get(service, rawPath, pathInfo, query.toString(), parameters);
    }

    /**
     * As {@link #get(CmisService, String, String, Map)}, with the query string as it is sent ({@code rawQuery}) and the
     * parameters as the container decodes them ({@code decoded}).
     */
    private static Answered get(CmisService service, String rawPath, String pathInfo, String rawQuery, Map<String, String> decoded) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, String[]> all = new LinkedHashMap<>();
        decoded.forEach((k, v) -> all.put(k, new String[] { v }));
        String query = rawQuery;
        when(request.getParameter(anyString())).thenAnswer(invocation -> {
            String[] values = all.get(invocation.getArgument(0, String.class));
            return values == null ? null : values[0];
        });
        when(request.getParameterValues(anyString())).thenAnswer(invocation -> all.get(invocation.getArgument(0, String.class)));
        when(request.getParameterMap()).thenReturn(all);
        when(request.getParameterNames()).thenAnswer(invocation -> Collections.enumeration(all.keySet()));
        when(request.getMethod()).thenReturn("GET");
        when(request.getContextPath()).thenReturn("/core");
        when(request.getServletPath()).thenReturn("/browser");
        when(request.getRequestURI()).thenReturn("/core/browser/" + REPO + rawPath);
        when(request.getPathInfo()).thenReturn("/" + REPO + pathInfo);
        when(request.getQueryString()).thenReturn(query.isEmpty() ? null : query);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter written = new StringWriter();
        int[] status = { 200 };
        try {
            when(response.getWriter()).thenReturn(new PrintWriter(written));
        } catch (java.io.IOException impossible) {
            throw new HarnessBroken(impossible);
        }
        doAnswer(invocation -> {
            status[0] = invocation.getArgument(0, Integer.class);
            return null;
        }).when(response).setStatus(org.mockito.ArgumentMatchers.anyInt());
        try {
            new Driven(service).service(request, response);
        } catch (jakarta.servlet.ServletException | java.io.IOException thrown) {
            // What the servlet under test threw is its answer, not this lock failing to look: it is handed on as it is,
            // so a lock that says the request is answered fails on that and not as harness breakage.
            throw new java.lang.reflect.UndeclaredThrowableException(thrown, "the servlet threw past its own error writer");
        }
        return new Answered(status[0], written.toString());
    }

    private static Map<String, String> selector(String selector) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("cmisselector", selector);
        return parameters;
    }

    private static Map<String, String> parameters(String... namesAndValues) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            parameters.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return parameters;
    }

    private static void neverTheNamedObject(CmisService service) {
        verify(service, never()).getObject(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).getChildren(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any(), any(), any(),
                any());
        verify(service, never()).getObjectParents(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any());
    }

    private static void neverRoot(CmisService service) {
        verify(service, never()).getRepositoryInfo(any(), any());
        verify(service, never()).getObject(eq(REPO), eq(ROOT_ID), any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).getProperties(eq(REPO), eq(ROOT_ID), any(), any());
        verify(service, never()).getChildren(eq(REPO), eq(ROOT_ID), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void objectPropertiesAndChildrenAreAnsweredForTheObjectAtThePath() {
        for (String selector : new String[] { "object", "properties", "children", "allowableActions" }) {
            CmisService service = aService();
            Answered answered = assertDoesNotThrow(() -> get(service, "/root/a/b", "/root/a/b", selector(selector)));
            assertEquals(200, answered.status(), selector + ": " + answered.body());
            verify(service).getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any());
            switch (selector) {
            case "object" -> verify(service).getObject(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any());
            case "properties" -> verify(service).getProperties(eq(REPO), eq(AT_PATH), any(), any());
            case "children" -> verify(service).getChildren(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any(), any(),
                    any());
            default -> verify(service).getAllowableActions(eq(REPO), eq(AT_PATH), any());
            }
            neverRoot(service);
            assertFalse(answered.body().contains(ROOT_ID), selector + " answered the root folder: " + answered.body());
        }
    }

    @Test
    void aPathThatNamesNothingIsAnswered404AndNeverTheRoot() {
        for (String selector : new String[] { "object", "properties", "children" }) {
            for (String path : new String[] { "/root/no-such-object", "/root/a/b/c" }) {
                CmisService service = aService();
                Answered answered = get(service, path, path, selector(selector));
                assertEquals(404, answered.status(), selector + " " + path + ": " + answered.body());
                assertTrue(answered.body().contains("objectNotFound"), answered.body());
                neverRoot(service);
                verify(service, never()).getObject(any(), any(), any(), any(), any(), any(), any(), any(), any());
                verify(service, never()).getChildren(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            }
        }
    }

    @Test
    void theSegmentsAreDecodedOnceAndEmptyOnesSkipped() {
        // "%2541" decoded once is "%41"; decoded twice it would be "A". The container gives getPathInfo decoded already.
        String[][] cases = { { "/root/a%20b/c", "/root/a b/c", "/a b/c" }, { "/root/x%2541", "/root/x%41", "/x%41" },
                { "/root//a//b", "/root//a//b", "/a/b" } };
        for (String[] c : cases) {
            CmisService service = aService();
            Answered answered = get(service, c[0], c[1], selector("object"));
            assertEquals(200, answered.status(), c[0] + ": " + answered.body());
            verify(service).getObjectByPath(eq(REPO), eq(c[2]), any(), any(), any(), any(), any(), any(), any());
            verify(service).getObject(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any());
            neverRoot(service);
        }
    }

    @Test
    void thePathNotTheObjectIdParameterNamesTheObject() {
        for (String name : new String[] { "objectId", "OBJECTID" }) {
            for (String selector : new String[] { "object", "children" }) {
                CmisService service = aService();
                Map<String, String> parameters = selector(selector);
                parameters.put(name, NAMED_BY_PARAMETER);
                Answered answered = get(service, "/root/a/b", "/root/a/b", parameters);
                assertEquals(200, answered.status(), answered.body());
                if ("object".equals(selector)) {
                    verify(service).getObject(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any());
                } else {
                    verify(service).getChildren(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any(), any(), any());
                }
                verify(service, never()).getObject(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any(), any());
                verify(service, never()).getChildren(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any(), any(),
                        any(), any());
            }
        }
    }

    @Test
    void withNothingAfterRootTheRootFolderIsAnsweredAsBefore() {
        for (String path : new String[] { "/root", "/root/" }) {
            CmisService service = aService();
            Answered answered = assertDoesNotThrow(() -> get(service, path, path, selector("object")));
            assertEquals(200, answered.status(), path + ": " + answered.body());
            verify(service).getObject(eq(REPO), eq(ROOT_ID), any(), any(), any(), any(), any(), any(), any());
            verify(service, never()).getObjectByPath(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
        // and the objectId parameter names the object, as before
        CmisService service = aService();
        Map<String, String> parameters = selector("object");
        parameters.put("objectId", NAMED_BY_PARAMETER);
        Answered answered = assertDoesNotThrow(() -> get(service, "/root", "/root", parameters));
        assertEquals(200, answered.status(), answered.body());
        verify(service).getObject(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).getObjectByPath(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theObjectIdFormIsNotChanged() {
        CmisService service = aService();
        Answered answered = assertDoesNotThrow(() -> get(service, "/" + NAMED_BY_PARAMETER + "/a", "/" + NAMED_BY_PARAMETER + "/a",
                selector("object")));
        assertEquals(200, answered.status(), answered.body());
        verify(service).getObject(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).getObjectByPath(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** An Error a read throws. Not an AssertionError, so a lock that lets it through is not taken for one that fired. */
    private static final class TheReadFailed extends Error {
        private static final long serialVersionUID = 1L;

        TheReadFailed() {
            super("r297: the read failed");
        }
    }

    @Test
    void aPathReadThatFailedIsAnsweredAsAFailureNeverAsTheRoot() {
        CmisService failing = aService();
        when(failing.getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("r297: the path could not be read"));
        Answered answered = get(failing, "/root/a/b", "/root/a/b", selector("object"));
        assertEquals(500, answered.status(), answered.body());
        neverRoot(failing);

        CmisService empty = aService();
        when(empty.getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any())).thenReturn(null);
        answered = get(empty, "/root/a/b", "/root/a/b", selector("object"));
        assertEquals(500, answered.status(), "a path read that answered nothing was answered " + answered.status() + ": "
                + answered.body());
        neverRoot(empty);

        CmisService refused = aService();
        when(refused.getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            throw new org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException("Permission Denied!");
        });
        answered = get(refused, "/root/a/b", "/root/a/b", selector("object"));
        assertEquals(403, answered.status(), answered.body());
        neverRoot(refused);

        CmisService erring = aService();
        TheReadFailed failure = new TheReadFailed();
        when(erring.getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any())).thenThrow(failure);
        try {
            get(erring, "/root/a/b", "/root/a/b", selector("object"));
            fail("an Error of the path read was answered");
        } catch (TheReadFailed thrown) {
            assertSame(failure, thrown);
        }
    }

    @Test
    void aDotSegmentOrAParameterAfterRootIsReadAsTheNameItIs() {
        // The container drops "." and ".." segments and ";" parameters from getPathInfo; the request URI keeps them (R298).
        String[][] cases = { { "/root/.", "/root/", "/." }, { "/root/zz-no-such/..", "/root", "/zz-no-such/.." },
                { "/root/;x=1", "/root/", "/;x=1" }, { "/root/../" + NAMED_BY_PARAMETER, "/" + NAMED_BY_PARAMETER, "/../" + NAMED_BY_PARAMETER },
                { "/root/a/b/..", "/root/a", "/a/b/.." } };
        for (String[] c : cases) {
            for (String selector : new String[] { "object", "children" }) {
                CmisService service = aService();
                Answered answered = get(service, c[0], c[1], selector(selector));
                assertEquals(404, answered.status(), selector + " " + c[0] + " was answered: " + answered.body());
                assertTrue(answered.body().contains("objectNotFound"), answered.body());
                verify(service).getObjectByPath(eq(REPO), eq(c[2]), any(), any(), any(), any(), any(), any(), any());
                neverRoot(service);
                neverTheNamedObject(service);
            }
        }
    }

    @Test
    void aUrlTheContainerNormalizedIsFoldedNeitherIntoTheRootNorIntoAnId() {
        String[][] cases = { { "/x/../root", "/root" }, { "/root;x=1/a/b", "/root/a/b" }, { "/./" + NAMED_BY_PARAMETER, "/" + NAMED_BY_PARAMETER },
                { "/" + NAMED_BY_PARAMETER + ";x=1", "/" + NAMED_BY_PARAMETER } };
        for (String[] c : cases) {
            CmisService service = aService();
            Answered answered = get(service, c[0], c[1], selector("object"));
            assertEquals(404, answered.status(), c[0] + " was answered: " + answered.body());
            assertTrue(answered.body().contains("objectNotFound"), answered.body());
            neverRoot(service);
            neverTheNamedObject(service);
            verify(service, never()).getObjectByPath(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void emptySegmentsAreSkippedAsTheContainerMergesThem() {
        CmisService byId = aService();
        Answered answered = assertDoesNotThrow(() -> get(byId, "//" + NAMED_BY_PARAMETER, "/" + NAMED_BY_PARAMETER, selector("object")));
        assertEquals(200, answered.status(), answered.body());
        verify(byId).getObject(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any(), any());
        CmisService byPath = aService();
        answered = assertDoesNotThrow(() -> get(byPath, "//root//a//b", "/root/a/b", selector("object")));
        assertEquals(200, answered.status(), answered.body());
        verify(byPath).getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any());
        verify(byPath).getObject(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any());
        neverRoot(byPath);
    }

    @Test
    void parentsDoesNotReadAnEncodedNameOfTheObjectIdParameter() {
        // The container decodes a parameter's name — OBJECTI%44 and %4FBJECTID are OBJECTID; OpenCMIS, which answers parents,
        // decodes the query string handed on to it again (R299).
        for (String name : new String[] { "OBJECTI%44", "%4FBJECTID" }) {
            CmisService parents = aService();
            Answered answered = assertDoesNotThrow(() -> get(parents, "/root/a/b", "/root/a/b",
                    "cmisselector=parents&" + name + "=" + NAMED_BY_PARAMETER, parameters("cmisselector", "parents", "OBJECTID", NAMED_BY_PARAMETER)));
            assertEquals(200, answered.status(), "parents with " + name + ": " + answered.body());
            verify(parents).getObjectParents(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any());
            neverTheNamedObject(parents);
        }
        for (Map<String, String> sent : java.util.List.of(parameters("cmisselector", "parents"),
                parameters("cmisselector", "parents", "objectId", NAMED_BY_PARAMETER))) {
            CmisService parents = aService();
            Answered answered = assertDoesNotThrow(() -> get(parents, "/root/a/b", "/root/a/b", sent));
            assertEquals(200, answered.status(), sent + ": " + answered.body());
            verify(parents).getObjectParents(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any());
            neverTheNamedObject(parents);
        }
    }

    @Test
    void theUpperCaseObjectSelectorDoesNotReadAnEncodedNameOfTheObjectIdParameter() {
        // cmisselector=OBJECT is not this servlet's own selector; OpenCMIS answers it, from the query string handed on (R299).
        for (String name : new String[] { "OBJECTI%44", "%4FBJECTID" }) {
            CmisService object = aService();
            Answered answered = assertDoesNotThrow(() -> get(object, "/root/a/b", "/root/a/b",
                    "cmisselector=OBJECT&" + name + "=" + NAMED_BY_PARAMETER, parameters("cmisselector", "OBJECT", "OBJECTID", NAMED_BY_PARAMETER)));
            assertEquals(200, answered.status(), "OBJECT with " + name + ": " + answered.body());
            assertTrue(answered.body().contains(AT_PATH) && !answered.body().contains(NAMED_BY_PARAMETER), answered.body());
            verify(object).getObject(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any());
            neverTheNamedObject(object);
            neverRoot(object);
        }
    }


    @Test
    void aSelectorThatCarriesAnEncodedObjectIdIsHandedOnAsOneValue() {
        // R301: the query string handed on to OpenCMIS is built from the parameters, each value encoded. The selector
        // object%26objectId%3D<id> is the one value object&objectId=<id> once decoded — no selector OpenCMIS knows, so it is
        // answered 405 — never the selector object with a first objectId <id> ahead of the object at the path.
        for (String selector : new String[] { "object", "parents", "children" }) {
            CmisService service = aService();
            Answered answered = assertDoesNotThrow(() -> get(service, "/root/a/b", "/root/a/b",
                    "cmisselector=" + selector + "%26objectId%3D" + NAMED_BY_PARAMETER,
                    parameters("cmisselector", selector + "&objectId=" + NAMED_BY_PARAMETER)));
            assertEquals(405, answered.status(), selector + ": " + answered.body());
            assertTrue(answered.body().contains("Unknown operation") && !answered.body().contains(NAMED_BY_PARAMETER),
                    selector + ": " + answered.body());
            verify(service).getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any());
            neverTheNamedObject(service);
            neverRoot(service);
        }
    }

    @Test
    void aParameterNameThatCarriesAnEncodedObjectIdIsHandedOnAsOneName() {
        // R301: each name is encoded too. OBJECTID%3D<id>%26x is the one name OBJECTID=<id>&x once decoded — not a parameter
        // OBJECTID, which OpenCMIS, reading names case-insensitively, would find ahead of the objectId of the object at the path.
        CmisService parents = aService();
        Answered answered = assertDoesNotThrow(() -> get(parents, "/root/a/b", "/root/a/b",
                "cmisselector=parents&OBJECTID%3D" + NAMED_BY_PARAMETER + "%26x=1",
                parameters("cmisselector", "parents", "OBJECTID=" + NAMED_BY_PARAMETER + "&x", "1")));
        assertEquals(200, answered.status(), answered.body());
        verify(parents).getObjectParents(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any());
        neverTheNamedObject(parents);
        neverRoot(parents);
    }
}
