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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
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

/**
 * A GET with {@code cmisselector=versions} answers only the versions of the object the URL names (R302 — 3.4.2). The
 * {@code versionSeriesId} parameter was handed to {@code getAllVersions}, which reads it before the object id, so a URL that
 * named the document D answered the versions of whatever series the parameter named. A {@code versionSeriesId} is now answered
 * only when it is the named object's own version series, as before; any other — of another document, or for an object that has
 * no version series — is refused with a 400 before any version is read. Without it the named object's versions are answered as
 * before, and so they are for an empty or blank value, which is no series (R308). The three forms of URL that name an object are held to it: the path after root, the object id, and
 * {@code root?objectId=}. {@code VersioningServiceImpl} is not changed.
 *
 * <p>The servlet is the product's, initialized as the container initializes it and driven through its {@code service}; only
 * the service factory the container provides is stood in for.
 */
class TheVersionsSelectorAnswersOnlyTheNamedObjectsVersionsTest {

    private static final String REPO = "bedroom";

    private static final String D = "R302-D";

    private static final String D_SERIES = "R302-VS-D";

    private static final String OTHER_SERIES = "R302-VS-OTHER";

    private static final String FOLDER = "R302-F";

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
                throw new IllegalStateException("the servlet could not be initialized", harness);
            }
        }
    }

    /** What the servlet answered: the status and the body. */
    private record Answered(int status, String body) {
    }

    private static ObjectData anObject(String id, String baseType, String series) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, id));
        properties.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, baseType));
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, baseType));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, "r302-" + id));
        if (series != null) {
            properties.addProperty(new PropertyIdImpl(PropertyIds.VERSION_SERIES_ID, series));
        }
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    /**
     * A service in which {@code /a/b} is the document {@link #D} of the series {@link #D_SERIES}, {@link #FOLDER} is a folder,
     * and any other id is not found. {@code getAllVersions} answers as the product's does: the series the parameter names
     * when there is one — {@link #OTHER_SERIES} is another document's — and the named object's otherwise.
     */
    private static CmisService aService() {
        CmisService service = mock(CmisService.class);
        when(service.getObjectByPath(eq(REPO), eq("/a/b"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(anObject(D, "cmis:document", D_SERIES));
        when(service.getObject(eq(REPO), anyString(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            new ExceptionServiceImpl().objectNotFound(DomainType.OBJECT, null, invocation.getArgument(1, String.class));
            return null;
        });
        when(service.getObject(eq(REPO), eq(D), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(anObject(D, "cmis:document", D_SERIES));
        when(service.getObject(eq(REPO), eq(FOLDER), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(anObject(FOLDER, "cmis:folder", null));
        when(service.getAllVersions(eq(REPO), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            String series = invocation.getArgument(2, String.class);
            if (OTHER_SERIES.equals(series)) {
                return List.of(anObject("R302-OTHER-1.0", "cmis:document", OTHER_SERIES),
                        anObject("R302-OTHER-1.1", "cmis:document", OTHER_SERIES));
            }
            return List.of(anObject("R302-D-1.0", "cmis:document", D_SERIES), anObject(D, "cmis:document", D_SERIES));
        });
        return service;
    }

    /** The three forms of URL that name {@link #D}: the raw path and the pathInfo, and the parameters besides the selector. */
    private static final String[][] FORMS = { { "/root/a/b", "/root/a/b", null }, { "/" + D, "/" + D, null },
            { "/root", "/root", D } };

    private static Answered versions(CmisService service, String[] form, String seriesName, String series) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("cmisselector", "versions");
        if (form[2] != null) {
            parameters.put("objectId", form[2]);
        }
        if (seriesName != null) {
            parameters.put(seriesName, series);
        }
        return get(service, form[0], form[1], parameters);
    }

    private static Answered get(CmisService service, String rawPath, String pathInfo, Map<String, String> decoded) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, String[]> all = new LinkedHashMap<>();
        decoded.forEach((k, v) -> all.put(k, new String[] { v }));
        StringBuilder query = new StringBuilder();
        decoded.forEach((k, v) -> query.append(query.length() == 0 ? "" : "&").append(k).append('=').append(v));
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
        when(request.getQueryString()).thenReturn(query.toString());
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter written = new StringWriter();
        int[] status = { 200 };
        try {
            when(response.getWriter()).thenReturn(new PrintWriter(written));
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        doAnswer(invocation -> {
            status[0] = invocation.getArgument(0, Integer.class);
            return null;
        }).when(response).setStatus(org.mockito.ArgumentMatchers.anyInt());
        try {
            new Driven(service).service(request, response);
        } catch (jakarta.servlet.ServletException | java.io.IOException thrown) {
            throw new AssertionError("the servlet threw past its own error writer", thrown);
        }
        return new Answered(status[0], written.toString());
    }

    private static void noVersionWasRead(CmisService service) {
        verify(service, never()).getAllVersions(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theVersionSeriesOfAnotherDocumentIsRefusedOnEveryFormOfTheUrl() {
        for (String[] form : FORMS) {
            for (String name : new String[] { "versionSeriesId", "VERSIONSERIESID" }) {
                CmisService service = aService();
                Answered answered = assertDoesNotThrow(() -> versions(service, form, name, OTHER_SERIES));
                assertEquals(400, answered.status(), form[0] + " " + name + ": " + answered.body());
                assertTrue(answered.body().contains(NemakiBrowserBindingServlet.VERSION_SERIES_OF_ANOTHER_OBJECT_REFUSED)
                        && !answered.body().contains("R302-OTHER") && !answered.body().contains(OTHER_SERIES),
                        form[0] + " " + name + ": " + answered.body());
                noVersionWasRead(service);
            }
        }
    }

    @Test
    void theNamedObjectsOwnSeriesOrNoneIsAnsweredAsBefore() {
        for (String[] form : FORMS) {
            for (String series : new String[] { D_SERIES, null }) {
                CmisService service = aService();
                Answered answered = assertDoesNotThrow(() -> versions(service, form, series == null ? null : "versionSeriesId", series));
                assertEquals(200, answered.status(), form[0] + " " + series + ": " + answered.body());
                assertTrue(answered.body().contains("R302-D-1.0") && !answered.body().contains("R302-OTHER"),
                        form[0] + " " + series + ": " + answered.body());
                verify(service).getAllVersions(eq(REPO), eq(D), eq(series), any(), any(), any());
            }
        }
    }

    @Test
    void aVersionSeriesForAnObjectThatHasNoneOrIsNotThereIsNotAnsweredWithAnotherSeries() {
        // A folder has no version series; an id that names nothing is not found — neither answers another document's versions.
        CmisService folder = aService();
        Answered refused = assertDoesNotThrow(() -> versions(folder, new String[] { "/" + FOLDER, "/" + FOLDER, null },
                "versionSeriesId", OTHER_SERIES));
        assertEquals(400, refused.status(), refused.body());
        assertTrue(refused.body().contains(NemakiBrowserBindingServlet.VERSION_SERIES_OF_ANOTHER_OBJECT_REFUSED), refused.body());
        noVersionWasRead(folder);
        CmisService missing = aService();
        Answered notFound = assertDoesNotThrow(() -> versions(missing, new String[] { "/R302-MISSING", "/R302-MISSING", null },
                "versionSeriesId", OTHER_SERIES));
        assertEquals(404, notFound.status(), notFound.body());
        assertTrue(!notFound.body().contains("R302-OTHER"), notFound.body());
        noVersionWasRead(missing);
    }

    @Test
    void anEmptyOrBlankVersionSeriesIdIsReadAsNone() {
        // R308: an empty or blank value is no version series: the named object's versions are answered as when there is none —
        // getAllVersions is handed no series — not refused as another object's.
        for (String[] form : FORMS) {
            for (String blank : new String[] { "", " ", "\t" }) {
                CmisService service = aService();
                Answered answered = assertDoesNotThrow(() -> versions(service, form, "versionSeriesId", blank));
                assertEquals(200, answered.status(), form[0] + " [" + blank + "]: " + answered.body());
                assertTrue(answered.body().contains("R302-D-1.0") && !answered.body().contains("R302-OTHER"),
                        form[0] + " [" + blank + "]: " + answered.body());
                verify(service).getAllVersions(eq(REPO), eq(D), org.mockito.ArgumentMatchers.isNull(), any(), any(), any());
            }
        }
    }
}
