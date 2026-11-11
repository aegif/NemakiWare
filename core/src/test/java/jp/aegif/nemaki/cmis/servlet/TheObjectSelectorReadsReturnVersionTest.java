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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.aspect.impl.ExceptionServiceImpl;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * The browser binding's {@code cmisselector=object} reads {@code returnVersion} (R278 — 3.4.2). An old version's id with
 * {@code returnVersion=latest} (or {@code latestmajor}) answered that old version; AtomPub answered the latest one. With no
 * {@code returnVersion}, an empty one (R295 — 3.4.2) or {@code this} the selector reads {@code getObject} as before; with
 * {@code latest} and {@code latestmajor} it reads the id with {@code getObject} first — so an id that does not exist and an
 * internal row (a version series id among them) stay 404 — and, for a document, answers {@code getObjectOfLatestVersion} with
 * the id it was given and {@code major} false and true, passing the selector's other arguments through. Any other value — one
 * that is not empty — is refused (400). A failure of {@code getObjectOfLatestVersion} is answered as that failure, never as
 * the old version. The properties selector is not changed.
 *
 * <p>The servlet is the product's, driven through its object-specific GET dispatcher ({@code handleObjectSpecificGetOperation}
 * — the routing by {@code cmisselector}, the parameter parsing and the JSON and error writers); only the call context and the
 * service factory, which the container provides, are stood in for.
 */
class TheObjectSelectorReadsReturnVersionTest {

    private static final String REPO = "bedroom";

    private static final String OLD = "R278-V1-0";

    private static final String LATEST = "R278-V1-1";

    private static final String LATEST_MAJOR = "R278-V1-0-MAJOR";

    /** The servlet with the container's two collaborators stood in for. */
    private static final class Driven extends NemakiBrowserBindingServlet {
        private static final long serialVersionUID = 1L;
        final transient CmisService service;
        final transient ServletContext context = mock(ServletContext.class);

        Driven(CmisService service) {
            this.service = service;
        }

        @Override
        protected CallContext createContext(ServletContext servletContext, HttpServletRequest request, HttpServletResponse response,
                TempStoreOutputStreamFactory streamFactory) {
            return mock(CallContext.class);
        }

        @Override
        protected CmisServiceFactory getServiceFactory() {
            CmisServiceFactory factory = mock(CmisServiceFactory.class);
            when(factory.getService(any())).thenReturn(service);
            return factory;
        }

        @Override
        public ServletContext getServletContext() {
            return context;
        }
    }

    /** What the servlet answered: the status and the body. */
    private record Answered(int status, String body) {
        /**
         * The id of the object the answer carries, or null when it carries none — a body of {@code null}, an error, or text
         * that is not JSON. An answer that is not an object is what the servlet said, not this lock failing to look, so
         * the caller's assertion on the id is what fails for it.
         */
        String objectId() {
            Object json;
            try {
                json = new JSONParser().parse(body);
            } catch (Exception notJson) {
                return null;
            }
            if (!(json instanceof Map<?, ?> object) || !(object.get("properties") instanceof Map<?, ?> properties)
                    || !(properties.get(PropertyIds.OBJECT_ID) instanceof Map<?, ?> id)) {
                return null;
            }
            // With no type cache the writer gives each value as a list.
            Object value = id.get("value");
            Object first = value instanceof java.util.List<?> list ? (list.isEmpty() ? null : list.get(0)) : value;
            return first instanceof String string ? string : null;
        }
    }

    private static ObjectData aVersion(String id, String baseType) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, id));
        properties.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, baseType));
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, baseType));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, "r278-" + id));
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    /** A service whose getObject answers the old version, a folder, or the product's not-found, and whose latest is set. */
    private static CmisService aService() {
        CmisService service = mock(CmisService.class);
        when(service.getObject(eq(REPO), eq(OLD), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(aVersion(OLD, "cmis:document"));
        when(service.getObject(eq(REPO), eq("R278-FOLDER"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(aVersion("R278-FOLDER", "cmis:folder"));
        for (String gone : new String[] { "R278-SERIES", "R278-GONE" }) {
            when(service.getObject(eq(REPO), eq(gone), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                new ExceptionServiceImpl().objectNotFound(DomainType.OBJECT, null, gone);
                return null;
            });
        }
        when(service.getObjectOfLatestVersion(eq(REPO), eq(OLD), isNull(), eq(false), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(aVersion(LATEST, "cmis:document"));
        when(service.getObjectOfLatestVersion(eq(REPO), eq(OLD), isNull(), eq(true), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(aVersion(LATEST_MAJOR, "cmis:document"));
        when(service.getProperties(eq(REPO), anyString(), any(), any())).thenReturn(aVersion(OLD, "cmis:document").getProperties());
        return service;
    }

    /** A GET of {@code /bedroom/<objectId>?cmisselector=<selector>&<parameters>}, through the servlet's dispatcher. */
    private static Answered get(CmisService service, String selector, String objectId, Map<String, String> parameters) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, String[]> all = new LinkedHashMap<>();
        all.put("cmisselector", new String[] { selector });
        parameters.forEach((k, v) -> all.put(k, new String[] { v }));
        when(request.getParameter(anyString())).thenAnswer(invocation -> {
            String[] values = all.get(invocation.getArgument(0, String.class));
            return values == null ? null : values[0];
        });
        when(request.getParameterMap()).thenReturn(all);
        when(request.getMethod()).thenReturn("GET");
        when(request.getPathInfo()).thenReturn("/" + REPO + "/" + objectId);
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
            Method dispatch = NemakiBrowserBindingServlet.class.getDeclaredMethod("handleObjectSpecificGetOperation",
                    HttpServletRequest.class, HttpServletResponse.class, String[].class, String.class);
            dispatch.setAccessible(true);
            dispatch.invoke(new Driven(service), request, response, new String[] { REPO, objectId }, selector);
        } catch (InvocationTargetException thrown) {
            // What the dispatcher under test threw is its answer, not this lock failing to look: it is handed on as it is.
            if (thrown.getCause() instanceof Error error) {
                throw error;
            }
            if (thrown.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new java.lang.reflect.UndeclaredThrowableException(thrown.getCause(),
                    "the dispatcher threw past its own error writer");
        } catch (ReflectiveOperationException harness) {
            throw new HarnessBroken("the dispatcher could not be reached", harness);
        }
        return new Answered(status[0], written.toString());
    }

    private static Map<String, String> returnVersion(String value) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (value != null) {
            parameters.put("returnVersion", value);
        }
        return parameters;
    }

    @Test
    void withNoReturnVersionOrThisTheIdItselfIsAnswered() {
        // An empty value is no value (R295), as AtomPub and OpenCMIS read it.
        for (String value : new String[] { null, "", "this" }) {
            CmisService service = aService();
            Answered answered = get(service, "object", OLD, returnVersion(value));
            assertEquals(200, answered.status(), "returnVersion=" + value + ": " + answered.body());
            assertEquals(OLD, answered.objectId(), "returnVersion=" + value + " did not answer the id it was given");
            verify(service, never()).getObjectOfLatestVersion(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                    any(), any());
        }
    }

    @Test
    void latestAnswersTheLatestVersionAndLatestmajorTheLatestMajorVersion() {
        Map<String, String> expected = Map.of("latest", LATEST, "latestmajor", LATEST_MAJOR);
        expected.forEach((value, latest) -> {
            CmisService service = aService();
            Answered answered = get(service, "object", OLD, returnVersion(value));
            assertEquals(200, answered.status(), "returnVersion=" + value + ": " + answered.body());
            assertEquals(latest, answered.objectId(), "returnVersion=" + value + " did not answer the " + value + " version");
            assertFalse(answered.body().contains(OLD + "\""), "returnVersion=" + value + " answered the old version too");
        });
    }

    @Test
    void theSelectorsOtherArgumentsArePassedThrough() {
        for (boolean major : new boolean[] { false, true }) {
            CmisService service = aService();
            Map<String, String> parameters = returnVersion(major ? "latestmajor" : "latest");
            parameters.put("filter", "cmis:name,cmis:objectId");
            parameters.put("includeAllowableActions", "true");
            parameters.put("includeRelationships", "both");
            parameters.put("renditionFilter", "cmis:thumbnail");
            parameters.put("includePolicyIds", "true");
            parameters.put("includeACL", "true");
            Answered answered = get(service, "object", OLD, parameters);
            assertEquals(200, answered.status(), answered.body());
            verify(service).getObjectOfLatestVersion(REPO, OLD, null, major, "cmis:name,cmis:objectId", true,
                    IncludeRelationships.BOTH, "cmis:thumbnail", true, true, null);
        }
    }

    @Test
    void aValueThatIsNoneOfTheThreeIsRefused() {
        // Only an empty value is no value (R295): one that is blank but not empty is unknown, and refused.
        for (String value : new String[] { "newest", "LATEST", "latest major", " ", "\t" }) {
            CmisService service = aService();
            Answered answered = get(service, "object", OLD, returnVersion(value));
            assertEquals(400, answered.status(), "returnVersion=\"" + value + "\" was not refused: " + answered.body());
            assertTrue(answered.body().contains("invalidArgument"), answered.body());
            verify(service, never()).getObject(any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(service, never()).getObjectOfLatestVersion(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                    any(), any());
        }
    }

    @Test
    void aVersionSeriesIdOrAnIdThatDoesNotExistStays404() {
        for (String id : new String[] { "R278-SERIES", "R278-GONE" }) {
            for (String value : new String[] { "latest", "latestmajor", null }) {
                CmisService service = aService();
                Answered answered = get(service, "object", id, returnVersion(value));
                assertEquals(404, answered.status(), id + " with returnVersion=" + value + ": " + answered.body());
                assertTrue(answered.body().contains("objectNotFound"), answered.body());
                verify(service, never()).getObjectOfLatestVersion(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                        any(), any());
            }
        }
    }

    @Test
    void anObjectThatIsNotADocumentIsAnsweredAsItIs() {
        CmisService service = aService();
        Answered answered = get(service, "object", "R278-FOLDER", returnVersion("latest"));
        assertEquals(200, answered.status(), answered.body());
        assertEquals("R278-FOLDER", answered.objectId(), "the folder was not answered as it is: " + answered.body());
        verify(service, never()).getObjectOfLatestVersion(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                any(), any());
    }

    /** An Error a read throws. Not an AssertionError, so a lock that lets it through is not taken for one that fired. */
    private static final class TheReadFailed extends Error {
        private static final long serialVersionUID = 1L;

        TheReadFailed() {
            super("r278: the read failed");
        }
    }

    @Test
    void aLatestVersionReadThatFailedIsNotAnsweredWithTheOldVersion() {
        CmisService service = aService();
        when(service.getObjectOfLatestVersion(eq(REPO), eq(OLD), isNull(), eq(false), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("r278: the latest version could not be read"));
        Answered answered = get(service, "object", OLD, returnVersion("latest"));
        assertEquals(500, answered.status(), "a failed read was answered " + answered.status() + ": " + answered.body());
        assertFalse(answered.body().contains(OLD + "\""), "a failed read was answered with the old version: " + answered.body());

        CmisService erring = aService();
        TheReadFailed failure = new TheReadFailed();
        when(erring.getObjectOfLatestVersion(eq(REPO), eq(OLD), isNull(), eq(true), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(failure);
        try {
            get(erring, "object", OLD, returnVersion("latestmajor"));
            fail("an Error of the latest version read was answered");
        } catch (TheReadFailed thrown) {
            assertSame(failure, thrown);
        }
    }

    @Test
    void thePropertiesSelectorDoesNotReadReturnVersion() {
        CmisService service = aService();
        Answered answered = get(service, "properties", OLD, returnVersion("latest"));
        assertEquals(200, answered.status(), answered.body());
        verify(service).getProperties(eq(REPO), eq(OLD), any(), any());
        verify(service, never()).getObjectOfLatestVersion(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(),
                any(), any());
    }
}
