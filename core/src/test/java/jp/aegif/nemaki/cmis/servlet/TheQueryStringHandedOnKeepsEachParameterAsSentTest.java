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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
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
 * The query string the Browser servlet hands on to OpenCMIS — which answers {@code parents}, the upper-case {@code OBJECT} and
 * {@code CONTENT} and any selector the servlet does not — keeps each parameter as it was sent (R304 — 3.4.2). R301 built it
 * from the parameters as the container decoded them, and the container holds {@code x} and {@code x=} alike as the empty
 * value, so a parameter sent with no value was handed on explicitly empty: {@code dateTimeFormat} was refused 400,
 * {@code callback} answered a JSONP with no function name, {@code streamId} asked for the stream whose id is empty. OpenCMIS
 * reads {@code x} as no value and {@code x=} as the empty one, and each is now handed on as it was sent, its duplicates in the
 * order they were sent: {@code x=} is still the empty value. The names and the values are the container's, each encoded, so
 * an encoded {@code &} or {@code =} still does not make a new parameter (R301); the selector the servlet read and the object it
 * resolved are written once each, and no {@code objectId} or {@code cmisselector} of the query string — in another case,
 * encoded, repeated, with no value — is read in their place. A query string that does not match its parameters is answered 400.
 *
 * <p>The servlet is the product's, driven through its {@code service} — the routing, the path read, OpenCMIS's own dispatcher
 * and its reading of the query string; only the service factory, which the container provides, is stood in for. The request
 * is given as the container gives it: the request URI and the query string as they were sent, and the parameters with their
 * names and values decoded, each value of a name in the order it was sent, a parameter with no value as the empty value.
 */
class TheQueryStringHandedOnKeepsEachParameterAsSentTest {

    private static final String REPO = "bedroom";

    private static final String ROOT_ID = "R304-ROOT";

    private static final String AT_PATH = "R304-AT-PATH";

    private static final String NAMED_BY_PARAMETER = "R304-BY-PARAMETER";

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
                throw new HarnessBroken("the servlet could not be initialized", harness);
            }
        }
    }

    /** What the servlet answered: the status, the body written as text, and the body written as bytes. */
    private record Answered(int status, String body, String streamed) {
    }

    private static ObjectData anObject(String id) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, id));
        properties.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, "cmis:document"));
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document"));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, "r304-" + id));
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    /**
     * A service in which {@code /a/b} is {@link #AT_PATH} and any other path names nothing; each object read answers its id,
     * the parents of any object are none, and the content of {@link #AT_PATH} is its default stream when no stream id is
     * asked for, while the stream whose id is empty is refused as the product's {@code getContentStream} refuses it (409).
     */
    private static CmisService aService() {
        CmisService service = mock(CmisService.class);
        RepositoryInfo info = mock(RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn(ROOT_ID);
        when(service.getRepositoryInfo(eq(REPO), any())).thenReturn(info);
        when(service.getObjectByPath(eq(REPO), anyString(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            new ExceptionServiceImpl().objectNotFoundByPath(DomainType.OBJECT, null, invocation.getArgument(1, String.class));
            return null;
        });
        when(service.getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any())).thenReturn(anObject(AT_PATH));
        for (String id : new String[] { ROOT_ID, AT_PATH, NAMED_BY_PARAMETER }) {
            when(service.getObject(eq(REPO), eq(id), any(), any(), any(), any(), any(), any(), any())).thenReturn(anObject(id));
        }
        when(service.getObjectParents(eq(REPO), anyString(), any(), any(), any(), any(), any(), any())).thenReturn(new ArrayList<>());
        when(service.getContentStream(eq(REPO), eq(AT_PATH), isNull(), any(), any(), any())).thenAnswer(invocation -> new ContentStreamImpl(
                "r304.txt", null, "text/plain", new java.io.ByteArrayInputStream("the default stream".getBytes(StandardCharsets.UTF_8))));
        when(service.getContentStream(eq(REPO), eq(AT_PATH), eq(""), any(), any(), any()))
                .thenThrow(new CmisConstraintException("r304: no stream has the id \"\""));
        return service;
    }

    /** A parameter as the container decodes it: its name and one value, in the order the query string has it. */
    private record Decoded(String name, String value) {
    }

    private static Decoded p(String name, String value) {
        return new Decoded(name, value);
    }

    /**
     * A GET of {@code /core/browser/bedroom<rawPath>?<rawQuery>} through the servlet's {@code service}: {@code pathInfo} is the
     * path as the container decodes it, and {@code decoded} the parameters as the container holds them — each name once, with
     * its values in the order sent.
     */
    private static Answered get(CmisService service, String rawPath, String pathInfo, String rawQuery, Decoded... decoded) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Decoded parameter : decoded) {
            grouped.computeIfAbsent(parameter.name(), name -> new ArrayList<>()).add(parameter.value());
        }
        Map<String, String[]> all = new LinkedHashMap<>();
        grouped.forEach((name, values) -> all.put(name, values.toArray(new String[0])));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter(anyString())).thenAnswer(invocation -> {
            String[] values = all.get(invocation.getArgument(0, String.class));
            return values == null ? null : values[0];
        });
        when(request.getParameterValues(anyString())).thenAnswer(invocation -> all.get(invocation.getArgument(0, String.class)));
        when(request.getParameterMap()).thenReturn(Collections.unmodifiableMap(all));
        when(request.getParameterNames()).thenAnswer(invocation -> Collections.enumeration(all.keySet()));
        when(request.getMethod()).thenReturn("GET");
        when(request.getContextPath()).thenReturn("/core");
        when(request.getServletPath()).thenReturn("/browser");
        when(request.getRequestURI()).thenReturn("/core/browser/" + REPO + rawPath);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost/core/browser/" + REPO + rawPath));
        when(request.getPathInfo()).thenReturn("/" + REPO + pathInfo);
        when(request.getQueryString()).thenReturn(rawQuery);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter written = new StringWriter();
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        int[] status = { 200 };
        try {
            when(response.getWriter()).thenReturn(new PrintWriter(written));
            when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override
                public void write(int b) {
                    streamed.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }
            });
        } catch (java.io.IOException impossible) {
            throw new HarnessBroken(impossible);
        }
        doAnswer(invocation -> {
            status[0] = invocation.getArgument(0, Integer.class);
            return null;
        }).when(response).setStatus(org.mockito.ArgumentMatchers.anyInt());
        try {
            new Driven(service).service(request, response);
        } catch (ServletException | java.io.IOException thrown) {
            throw new java.lang.reflect.UndeclaredThrowableException(thrown, "the servlet threw past its own error writer");
        }
        return new Answered(status[0], written.toString(), streamed.toString(StandardCharsets.UTF_8));
    }

    /** The parents of {@link #AT_PATH} were read with {@code filter}, and nothing was read for the object a parameter named. */
    private static void parentsOfTheTargetWereRead(CmisService service, String filter) {
        verify(service).getObjectParents(eq(REPO), eq(AT_PATH), filter == null ? isNull() : eq(filter), any(), any(), any(), any(), any());
        verify(service, never()).getObjectParents(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any());
        verify(service, never()).getObject(eq(REPO), eq(NAMED_BY_PARAMETER), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * The two routes that hand {@code parents} on to OpenCMIS for {@link #AT_PATH}: the path after root, and root with an
     * objectId. ({@code /browser/{repo}/{objectId}} hands it on too, but OpenCMIS reads the route from the request URI, where
     * the id is not root, and answers 405 to any selector it is handed there — before this change and after it.)
     */
    private static Answered parentsOn(String route, CmisService service, String rest, Decoded... restDecoded) {
        List<Decoded> decoded = new ArrayList<>();
        String query;
        String rawPath;
        if ("path".equals(route)) {
            rawPath = "/root/a/b";
            query = "cmisselector=parents";
            decoded.add(p("cmisselector", "parents"));
        } else {
            rawPath = "/root";
            query = "cmisselector=parents&objectId=" + AT_PATH;
            decoded.add(p("cmisselector", "parents"));
            decoded.add(p("objectId", AT_PATH));
        }
        decoded.addAll(List.of(restDecoded));
        return get(service, rawPath, rawPath, query + (rest.isEmpty() ? "" : "&" + rest), decoded.toArray(new Decoded[0]));
    }

    private static final String[] ROUTES = { "path", "root" };

    @Test
    void aDateTimeFormatWithNoValueIsTheDefaultFormatOnEveryRoute() {
        for (String route : ROUTES) {
            CmisService service = aService();
            Answered answered = assertDoesNotThrow(() -> parentsOn(route, service, "dateTimeFormat", p("dateTimeFormat", "")));
            assertEquals(200, answered.status(), route + ": " + answered.body());
            parentsOfTheTargetWereRead(service, null);
        }
        CmisService object = aService();
        Answered answered = assertDoesNotThrow(() -> get(object, "/root/a/b", "/root/a/b", "cmisselector=OBJECT&dateTimeFormat",
                p("cmisselector", "OBJECT"), p("dateTimeFormat", "")));
        assertEquals(200, answered.status(), "OBJECT: " + answered.body());
        assertTrue(answered.body().contains(AT_PATH), answered.body());
        verify(object).getObject(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anExplicitlyEmptyDateTimeFormatIsStillRefused() {
        for (String route : ROUTES) {
            CmisService service = aService();
            Answered answered = assertDoesNotThrow(() -> parentsOn(route, service, "dateTimeFormat=", p("dateTimeFormat", "")));
            assertEquals(400, answered.status(), route + ": " + answered.body());
            assertTrue(answered.body().contains("dateTimeFormat"), route + ": " + answered.body());
            verify(service, never()).getObjectParents(any(), any(), any(), any(), any(), any(), any(), any());
        }
        CmisService object = aService();
        Answered answered = assertDoesNotThrow(() -> get(object, "/root/a/b", "/root/a/b", "cmisselector=OBJECT&dateTimeFormat=",
                p("cmisselector", "OBJECT"), p("dateTimeFormat", "")));
        assertEquals(400, answered.status(), "OBJECT: " + answered.body());
        assertTrue(answered.body().contains("dateTimeFormat"), answered.body());
    }

    @Test
    void aCallbackWithNoValueIsNotAnEmptyCallback() {
        CmisService bare = aService();
        Answered noValue = assertDoesNotThrow(() -> parentsOn("path", bare, "callback", p("callback", "")));
        assertEquals(200, noValue.status(), noValue.body());
        assertTrue(noValue.body().startsWith("["), "a callback with no value was answered as a JSONP: " + noValue.body());
        CmisService empty = aService();
        Answered emptyValue = assertDoesNotThrow(() -> parentsOn("path", empty, "callback=", p("callback", "")));
        assertEquals(200, emptyValue.status(), emptyValue.body());
        assertTrue(emptyValue.body().startsWith("(") && emptyValue.body().endsWith(");"),
                "an empty callback was not answered as the JSONP OpenCMIS writes for it: " + emptyValue.body());
        assertNotEquals(noValue.body(), emptyValue.body());
    }

    @Test
    void aStreamIdWithNoValueIsTheDefaultStream() {
        CmisService bare = aService();
        Answered noValue = assertDoesNotThrow(() -> get(bare, "/root/a/b", "/root/a/b", "cmisselector=CONTENT&streamId",
                p("cmisselector", "CONTENT"), p("streamId", "")));
        assertEquals(200, noValue.status(), noValue.body());
        assertEquals("the default stream", noValue.streamed());
        verify(bare).getContentStream(eq(REPO), eq(AT_PATH), isNull(), any(), any(), any());
        verify(bare, never()).getContentStream(eq(REPO), eq(AT_PATH), eq(""), any(), any(), any());

        CmisService empty = aService();
        Answered emptyValue = assertDoesNotThrow(() -> get(empty, "/root/a/b", "/root/a/b", "cmisselector=CONTENT&streamId=",
                p("cmisselector", "CONTENT"), p("streamId", "")));
        assertEquals(409, emptyValue.status(), emptyValue.body());
        verify(empty).getContentStream(eq(REPO), eq(AT_PATH), eq(""), any(), any(), any());
    }

    @Test
    void aNameWithNoValueAndTheSameNameEmptyAreKeptInTheirOrder() {
        CmisService bareFirst = aService();
        Answered answered = assertDoesNotThrow(() -> parentsOn("path", bareFirst, "dateTimeFormat&dateTimeFormat=",
                p("dateTimeFormat", ""), p("dateTimeFormat", "")));
        assertEquals(200, answered.status(), "dateTimeFormat&dateTimeFormat=: " + answered.body());
        CmisService emptyFirst = aService();
        answered = assertDoesNotThrow(() -> parentsOn("path", emptyFirst, "dateTimeFormat=&dateTimeFormat",
                p("dateTimeFormat", ""), p("dateTimeFormat", "")));
        assertEquals(400, answered.status(), "dateTimeFormat=&dateTimeFormat: " + answered.body());

        CmisService filterBareFirst = aService();
        answered = assertDoesNotThrow(() -> parentsOn("path", filterBareFirst, "filter&filter=cmis:name",
                p("filter", ""), p("filter", "cmis:name")));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(filterBareFirst, null);
        CmisService filterValueFirst = aService();
        answered = assertDoesNotThrow(() -> parentsOn("path", filterValueFirst, "filter=cmis:name&filter",
                p("filter", "cmis:name"), p("filter", "")));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(filterValueFirst, "cmis:name");
        CmisService filterEmptyFirst = aService();
        answered = assertDoesNotThrow(() -> parentsOn("path", filterEmptyFirst, "filter=&filter",
                p("filter", ""), p("filter", "")));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(filterEmptyFirst, "");
    }

    @Test
    void anEncodedNameKeepsTheShapeItWasSentIn() {
        CmisService bare = aService();
        Answered answered = assertDoesNotThrow(() -> parentsOn("path", bare, "dateTime%46ormat", p("dateTimeFormat", "")));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(bare, null);
        CmisService empty = aService();
        answered = assertDoesNotThrow(() -> parentsOn("path", empty, "dateTime%46ormat=", p("dateTimeFormat", "")));
        assertEquals(400, answered.status(), answered.body());
        CmisService plus = aService();
        answered = assertDoesNotThrow(() -> parentsOn("path", plus, "fil%74er=cmis%3Aname,cmis:object+Id",
                p("filter", "cmis:name,cmis:object Id")));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(plus, "cmis:name,cmis:object Id");
    }

    @Test
    void aPlusInANameIsTheSpaceTheContainerReadsItAs() {
        // The container reads a + in a name as a space: a+b is the parameter "a b", sent once with a value and once without.
        // Read as a +, the name matches no parameter the container holds and an ordinary request is refused.
        for (String route : ROUTES) {
            CmisService service = aService();
            Answered answered = assertDoesNotThrow(() -> parentsOn(route, service, "a+b=1&filter=cmis:name&a+b",
                    p("a b", "1"), p("filter", "cmis:name"), p("a b", "")));
            assertEquals(200, answered.status(), route + ": " + answered.body());
            parentsOfTheTargetWereRead(service, "cmis:name");
        }
    }

    @Test
    void anEncodedAmpersandOrEqualsDoesNotMakeANewParameter() {
        CmisService value = aService();
        Answered answered = assertDoesNotThrow(() -> parentsOn("path", value, "filter=cmis%3Aname%26objectId%3D" + NAMED_BY_PARAMETER,
                p("filter", "cmis:name&objectId=" + NAMED_BY_PARAMETER)));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(value, "cmis:name&objectId=" + NAMED_BY_PARAMETER);
        CmisService bareName = aService();
        answered = assertDoesNotThrow(() -> parentsOn("path", bareName, "x%26objectId%3D" + NAMED_BY_PARAMETER,
                p("x&objectId=" + NAMED_BY_PARAMETER, "")));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(bareName, null);
        CmisService bareNameWithEquals = aService();
        answered = assertDoesNotThrow(() -> parentsOn("path", bareNameWithEquals, "dateTimeFormat%3D",
                p("dateTimeFormat=", "")));
        assertEquals(200, answered.status(), "a name dateTimeFormat= with no value was read as an empty dateTimeFormat: "
                + answered.body());
    }

    @Test
    void noObjectIdOfTheQueryStringIsReadInPlaceOfTheTarget() {
        Object[][] cases = { { "OBJECTID=" + NAMED_BY_PARAMETER, new Decoded[] { p("OBJECTID", NAMED_BY_PARAMETER) } },
                { "ObjectId=" + NAMED_BY_PARAMETER, new Decoded[] { p("ObjectId", NAMED_BY_PARAMETER) } },
                { "OBJECTI%44=" + NAMED_BY_PARAMETER, new Decoded[] { p("OBJECTID", NAMED_BY_PARAMETER) } },
                { "OBJECTID", new Decoded[] { p("OBJECTID", "") } },
                { "OBJECTID=&OBJECTID=" + NAMED_BY_PARAMETER, new Decoded[] { p("OBJECTID", ""), p("OBJECTID", NAMED_BY_PARAMETER) } } };
        for (String route : ROUTES) {
            for (Object[] c : cases) {
                CmisService service = aService();
                Answered answered = assertDoesNotThrow(() -> parentsOn(route, service, (String) c[0], (Decoded[]) c[1]));
                assertEquals(200, answered.status(), route + " " + c[0] + ": " + answered.body());
                parentsOfTheTargetWereRead(service, null);
            }
        }
        // the path names the target; the objectId parameter there is not read, in its own case either
        for (String route : new String[] { "path" }) {
            for (String rest : new String[] { "objectId=" + NAMED_BY_PARAMETER, "objectId",
                    "objectId=" + NAMED_BY_PARAMETER + "&objectId=" + NAMED_BY_PARAMETER }) {
                CmisService service = aService();
                Decoded[] decoded = rest.equals("objectId") ? new Decoded[] { p("objectId", "") }
                        : rest.contains("&") ? new Decoded[] { p("objectId", NAMED_BY_PARAMETER), p("objectId", NAMED_BY_PARAMETER) }
                        : new Decoded[] { p("objectId", NAMED_BY_PARAMETER) };
                Answered answered = assertDoesNotThrow(() -> parentsOn(route, service, rest, decoded));
                assertEquals(200, answered.status(), route + " " + rest + ": " + answered.body());
                parentsOfTheTargetWereRead(service, null);
            }
        }
    }

    @Test
    void noSelectorOfTheQueryStringIsReadInPlaceOfTheOneTheServletRead() {
        // cmisSelector and Cmisselector are ahead of cmisselector in the order OpenCMIS's parameters are kept in, so a query
        // string that carried either was read with it in place of the selector the servlet read.
        Object[][] cases = { { "cmisSelector=object", p("cmisSelector", "object") }, { "Cmisselector=object", p("Cmisselector", "object") },
                { "cmisSelecto%72=object", p("cmisSelector", "object") }, { "cmisSelector", p("cmisSelector", "") },
                { "CMISSELECTOR=object", p("CMISSELECTOR", "object") } };
        for (String route : ROUTES) {
            for (Object[] c : cases) {
                CmisService service = aService();
                Answered answered = assertDoesNotThrow(() -> parentsOn(route, service, (String) c[0], (Decoded) c[1]));
                assertEquals(200, answered.status(), route + " " + c[0] + ": " + answered.body());
                parentsOfTheTargetWereRead(service, null);
                verify(service, never()).getObject(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any(), any());
                verify(service, never()).getContentStream(any(), any(), any(), any(), any(), any());
            }
        }
    }

    @Test
    void aQueryStringThatDoesNotMatchItsParametersIsRefused() {
        // Each case names the reason it is refused for: the reasons are separate checks, and one that let its case through
        // would otherwise be covered by another that refuses the same case later.
        String notHeld = "a parameter the container does not hold as often";
        String notSent = "a parameter the query string does not have as often";
        Object[][] cases = {
                { "cmisselector=parents&filter=cmis:name", notHeld, new Decoded[] { p("cmisselector", "parents") } },
                { "cmisselector=parents&filter=a&filter=b", notHeld, new Decoded[] { p("cmisselector", "parents"), p("filter", "a") } },
                { "cmisselector=parents", notSent, new Decoded[] { p("cmisselector", "parents"), p("filter", "cmis:name") } },
                { "cmisselector=parents&filter=a", notSent,
                        new Decoded[] { p("cmisselector", "parents"), p("filter", "a"), p("filter", "b") } },
                { "cmisselector=parents&=cmis:name", "a parameter has no name", new Decoded[] { p("cmisselector", "parents") } },
                { "cmisselector=parents&fil%zzter=a", "a parameter name does not decode", new Decoded[] { p("cmisselector", "parents") } },
                { "cmisselector=parents&filter%4=a", "a parameter name does not decode", new Decoded[] { p("cmisselector", "parents") } },
                { "cmisselector=parents&filter", "a parameter with no value the container holds a value for",
                        new Decoded[] { p("cmisselector", "parents"), p("filter", "cmis:name") } } };
        for (Object[] c : cases) {
            CmisService service = aService();
            Answered answered = assertDoesNotThrow(() -> get(service, "/root/a/b", "/root/a/b", (String) c[0], (Decoded[]) c[2]));
            assertEquals(400, answered.status(), c[0] + ": " + answered.body());
            assertTrue(answered.body().contains("does not match its parameters (" + c[1] + ")"), c[0] + ": " + answered.body());
            verify(service, never()).getObjectParents(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void anObjectIdThatCarriesAnEncodedAmpersandIsHandedOnAsOneValue() {
        // root with an objectId: the objectId the servlet resolved is written encoded, as the others are (R301).
        CmisService service = aService();
        String id = AT_PATH + "&filter=cmis:name";
        Answered answered = assertDoesNotThrow(() -> get(service, "/root", "/root",
                "cmisselector=parents&objectId=" + AT_PATH + "%26filter%3Dcmis%3Aname", p("cmisselector", "parents"), p("objectId", id)));
        assertEquals(200, answered.status(), answered.body());
        verify(service).getObjectParents(eq(REPO), eq(id), isNull(), any(), any(), any(), any(), any());
        verify(service, never()).getObjectParents(eq(REPO), eq(AT_PATH), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anOrdinaryOrEncodedValueIsReadAsBefore() {
        CmisService plain = aService();
        Answered answered = assertDoesNotThrow(() -> parentsOn("path", plain, "filter=cmis:name", p("filter", "cmis:name")));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(plain, "cmis:name");
        CmisService encoded = aService();
        answered = assertDoesNotThrow(() -> parentsOn("root", encoded, "filter=cmis%3Aname%2Ccmis%3AobjectId",
                p("filter", "cmis:name,cmis:objectId")));
        assertEquals(200, answered.status(), answered.body());
        parentsOfTheTargetWereRead(encoded, "cmis:name,cmis:objectId");
        CmisService empties = aService();
        answered = assertDoesNotThrow(() -> parentsOn("root", empties, "&filter=cmis:name&", p("filter", "cmis:name")));
        assertEquals(200, answered.status(), "empty parts of the query string, which the container skips: " + answered.body());
        parentsOfTheTargetWereRead(empties, "cmis:name");
    }

    @Test
    void withNoSelectorThePathIsHandedOnAsItWasSent() {
        // No selector: OpenCMIS answers the URL from the query string as it was sent — the content of the document at the path.
        CmisService bare = aService();
        Answered answered = assertDoesNotThrow(() -> get(bare, "/root/a/b", "/root/a/b", "streamId", p("streamId", "")));
        assertEquals(200, answered.status(), answered.body());
        assertEquals("the default stream", answered.streamed());
        verify(bare).getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any());
        verify(bare).getContentStream(eq(REPO), eq(AT_PATH), isNull(), any(), any(), any());
        CmisService empty = aService();
        answered = assertDoesNotThrow(() -> get(empty, "/root/a/b", "/root/a/b", "streamId=", p("streamId", "")));
        assertEquals(409, answered.status(), answered.body());
        assertFalse(answered.body().contains("does not match its parameters"), answered.body());
        verify(empty).getContentStream(eq(REPO), eq(AT_PATH), eq(""), any(), any(), any());
    }
}
