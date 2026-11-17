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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.CmisRepositoryContextListener;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * In the Browser Binding a CMIS action is accepted on POST only (R355 - 3.4.2). The {@code cmisaction} parameter was read on
 * every method, and neither this servlet's router nor the handlers it calls look at the method, so a GET or a HEAD that named
 * an action carried the mutation out - and the cross-site check sits inside the POST branch, so such a request was never
 * checked: a browser attaches the reader's own ambient credential to a plain sub-resource load, and the mutation ran as that
 * reader. Now a method that does not read as POST is answered 405 with {@code Allow: POST} whenever the parameters the
 * container decoded name an action - before the router, the selector branches, {@code super.service()} and any service call,
 * so the refused request reaches no service. The scope is the container-decoded names because that is where every reader of
 * the action downstream reads it from; the product's own method says what that rests on.
 *
 * <p>What is locked here is the refusal by method and by the presence of the name, not by the value: an action the router
 * takes, one only the parent servlet dispatches, an unknown one, one named in another case or a percent-encoded spelling, one
 * named twice, one with no value or an empty one, an action the router takes that only reads, and a method or a parameter
 * map that answers null. The claim that carries the security is
 * {@link #everyMethodThatNamesAnActionIsEitherRefusedOrCrossSiteChecked}, which drives each method from a cross-site
 * request and from a same-site one: a method that names an action either leaves by the 405 or is examined by the
 * cross-site check, no mutation reaches the service unless the request is a same-site POST, and every exit is taken.
 *
 * <p>What is NOT measured here, so that the claims above are read with their limits: the methods, the paths, the content
 * types and the credential headers are each a fixed set, so a refusal that let one through which no case drives would be
 * green. The sets are chosen for that - two paths (the object route and the repository URL, which the router serves too),
 * the content type a page may send cross-origin without a preflight, two methods no specification sends here, and the two
 * credentials a browser attaches by itself - and a control on this test names each of those four dimensions. What is left
 * uncovered by construction is any other property of the request: a refusal made conditional on a header, a parameter or
 * a body shape that no case here carries would be green, and only reading the product tells you so.
 *
 * <p>A lowercase {@code post} is on the refused side, because an HTTP method is case-sensitive and the contract is the
 * method POST. It was on the served side first, pinned there as "today's leniency"; one reviewer called that locking the
 * behaviour rather than the rule, and the other read the contract and the product's own comment and called the leniency
 * the defect. Both point the same way, so the product was made strict and this test follows the contract instead of the
 * code it found. The cross-site check further down still reads the method ignoring case. That is the safe direction,
 * but not a second check on the same request: a spelling this arm refuses has already left by the 405 and never
 * reaches it. What it means is that if the order were ever changed, the wider condition is the one below.
 *
 * <p>The servlet is the product's, driven through its {@code service}; what the container provides is stood in for -
 * the service factory, the servlet context and config, and the request and the response. The request is given as the
 * container gives it: the parameter names and values already decoded,
 * each name once with its values in the order sent, a parameter with no value as the empty value. No part of this is measured
 * against a running deployment.
 */
class ACmisActionIsAcceptedOnPostOnlyTest {

    private static final String REPO = "bedroom";

    private static final String ROOT_ID = "R355-ROOT";

    private static final String TARGET = "R355-TARGET";

    private static final String REFUSED = "A CMIS action is accepted on POST only";

    /**
     * The names of the service calls that change state. Each is checked below to be a method the service interface really
     * declares, so that a misspelling here cannot make the claim that none of them was called empty.
     */
    private static final Set<String> MUTATING = new LinkedHashSet<>(List.of("deleteObject", "deleteObjectOrCancelCheckOut",
            "deleteTree", "applyAcl", "updateProperties", "bulkUpdateProperties", "createFolder", "createDocument",
            "createDocumentFromSource", "createItem", "createPolicy", "createRelationship", "createType", "updateType",
            "deleteType", "checkOut", "checkIn", "cancelCheckOut", "setContentStream", "appendContentStream",
            "deleteContentStream", "moveObject", "addObjectToFolder", "removeObjectFromFolder", "applyPolicy",
            "removePolicy"));

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

    /** What the servlet answered: the status, the headers it set, the body written as text and the body written as bytes. */
    private record Answered(int status, Map<String, String> headers, String body, String streamed) {
    }

    /** A parameter as the container decodes it: its name and one value, in the order the query string has it. */
    private record Decoded(String name, String value) {
    }

    private static Decoded p(String name, String value) {
        return new Decoded(name, value);
    }

    private static ObjectData anObject(String id) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, id));
        properties.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, "cmis:folder"));
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, "r355-" + id));
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    /** A service that can answer the reads this test makes, and on which every mutation is recorded and does nothing. */
    private static CmisService aService() {
        CmisService service = mock(CmisService.class);
        RepositoryInfo info = mock(RepositoryInfo.class);
        when(info.getRootFolderId()).thenReturn(ROOT_ID);
        when(service.getRepositoryInfo(eq(REPO), any())).thenReturn(info);
        for (String id : new String[] { ROOT_ID, TARGET }) {
            when(service.getObject(eq(REPO), eq(id), any(), any(), any(), any(), any(), any(), any())).thenReturn(anObject(id));
            when(service.getContentStream(eq(REPO), eq(id), isNull(), any(), any(), any())).thenAnswer(invocation -> new ContentStreamImpl(
                    "r355.txt", null, "text/plain", new java.io.ByteArrayInputStream(("the stream of " + id).getBytes(StandardCharsets.UTF_8))));
        }
        when(service.getObjectByPath(eq(REPO), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(anObject(TARGET));
        when(service.getObjectParents(eq(REPO), anyString(), any(), any(), any(), any(), any(), any())).thenReturn(new ArrayList<>());
        return service;
    }

    /**
     * A request of {@code method} to {@code /core/browser/bedroom<path>} with {@code decoded} as the container holds the
     * parameters, driven through the servlet's {@code service}. {@code crossSite} sends {@code Sec-Fetch-Site: cross-site}.
     */
    private static Answered drive(CmisService service, String method, String path, boolean crossSite, Decoded... decoded) {
        return driveRaw(service, method, path, crossSite, null, decoded);
    }

    /**
     * As {@link #drive}, with the query string as it was sent given separately: a percent-encoded parameter name reaches the
     * servlet decoded, and the raw query string it can also read still carries the encoded spelling. A reader that looks at
     * the raw query string instead of the decoded names is told apart from one that looks at the names only here.
     */
    private static Answered driveRaw(CmisService service, String method, String path, boolean crossSite, String rawQuery,
            Decoded... decoded) {
        return driveFully(service, method, path, crossSite, rawQuery, false, null, decoded);
    }

    /**
     * As {@link #driveRaw}, and with {@code parametersUnreadable} the request answers null when asked for its parameter map
     * — the shape a reader cannot tell from "no action is named". {@code method} may be null, the method that cannot be read.
     * {@code contentType} is the request's content type, which a second reader of the action in the product looks at.
     */
    private static Answered driveFully(CmisService service, String method, String path, boolean crossSite, String rawQuery,
            boolean parametersUnreadable, String contentType, Decoded... decoded) {
        return driveFully(service, method, path, crossSite, rawQuery, parametersUnreadable, contentType, Map.of(), decoded);
    }

    /**
     * As {@link #driveFully(CmisService, String, String, boolean, String, boolean, String, Decoded...)}, with {@code headers}
     * the headers the request carries — the credential a browser attaches by itself is one of them, and it is the condition
     * this whole arm exists for, so a refusal must not be conditional on its absence.
     */
    private static Answered driveFully(CmisService service, String method, String path, boolean crossSite, String rawQuery,
            boolean parametersUnreadable, String contentType, Map<String, String> requestHeaders, Decoded... decoded) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Decoded parameter : decoded) {
            grouped.computeIfAbsent(parameter.name(), name -> new ArrayList<>()).add(parameter.value());
        }
        Map<String, String[]> all = new LinkedHashMap<>();
        grouped.forEach((name, values) -> all.put(name, values.toArray(new String[0])));
        StringBuilder query = new StringBuilder();
        for (Decoded parameter : decoded) {
            query.append(query.length() == 0 ? "" : "&").append(parameter.name()).append('=').append(parameter.value());
        }

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter(anyString())).thenAnswer(invocation -> {
            String[] values = all.get(invocation.getArgument(0, String.class));
            return values == null ? null : values[0];
        });
        when(request.getParameterValues(anyString())).thenAnswer(invocation -> all.get(invocation.getArgument(0, String.class)));
        when(request.getParameterMap())
                .thenAnswer(invocation -> parametersUnreadable ? null : Collections.unmodifiableMap(all));
        when(request.getParameterNames()).thenAnswer(invocation -> Collections.enumeration(all.keySet()));
        when(request.getContentType()).thenReturn(contentType);
        when(request.getMethod()).thenReturn(method);
        when(request.getContextPath()).thenReturn("/core");
        when(request.getServletPath()).thenReturn("/browser");
        when(request.getRequestURI()).thenReturn("/core/browser/" + REPO + path);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost/core/browser/" + REPO + path));
        when(request.getPathInfo()).thenReturn("/" + REPO + path);
        String sent = rawQuery != null ? rawQuery : (query.length() == 0 ? null : query.toString());
        when(request.getQueryString()).thenReturn(sent);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(80);
        when(request.getLocale()).thenReturn(Locale.ENGLISH);
        when(request.getLocales()).thenAnswer(invocation -> Collections.enumeration(List.of(Locale.ENGLISH)));
        Map<String, String> headersSent = new LinkedHashMap<>();
        if (crossSite) {
            headersSent.put("Sec-Fetch-Site", "cross-site");
        }
        headersSent.putAll(requestHeaders);
        when(request.getHeader(anyString()))
                .thenAnswer(invocation -> headersSent.get(invocation.getArgument(0, String.class)));

        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter written = new StringWriter();
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        Map<String, String> headers = new LinkedHashMap<>();
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
        doAnswer(invocation -> {
            headers.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
            return null;
        }).when(response).setHeader(anyString(), anyString());

        try {
            new Driven(service).service(request, response);
        } catch (ServletException | java.io.IOException thrown) {
            throw new java.lang.reflect.UndeclaredThrowableException(thrown, "the servlet threw past its own error writer");
        }
        return new Answered(status[0], headers, written.toString(), streamed.toString(StandardCharsets.UTF_8));
    }

    /** The names of the service calls the request made. */
    private static Set<String> whatWasCalled(CmisService service) {
        Set<String> called = new TreeSet<>();
        mockingDetails(service).getInvocations().forEach(invocation -> called.add(invocation.getMethod().getName()));
        return called;
    }

    /** The request reached no service call at all, so in particular no mutation. */
    private static void nothingReachedTheService(CmisService service, String what) {
        Set<String> called = whatWasCalled(service);
        Set<String> mutations = new TreeSet<>(called);
        mutations.retainAll(MUTATING);
        assertEquals(Set.of(), mutations, what + ": a mutation reached the service");
        assertEquals(Set.of(), called, what + ": the service was called");
    }

    /** The answer is the refusal of this arm: 405, {@code Allow: POST}, and (unless HEAD) a body that names it. */
    private static void refused(Answered answered, String method, String what) {
        assertEquals(HttpServletResponse.SC_METHOD_NOT_ALLOWED, answered.status(), what + ": " + answered.body());
        assertEquals("POST", answered.headers().get("Allow"), what + ": Allow");
        if ("HEAD".equals(method)) {
            assertEquals("", answered.body(), what + ": HEAD wrote a body");
            assertEquals("", answered.streamed(), what + ": HEAD streamed a body");
        } else {
            assertTrue(answered.body().contains(REFUSED), what + ": " + answered.body());
        }
    }

    /** The answer is not the refusal of this arm: neither its 405 with {@code Allow: POST} nor its message. */
    private static void wasNotRefusedByThisArm(Answered answered, String what) {
        // The Allow header is the discriminator: this arm is the only place in the product that sets one, and OpenCMIS's
        // own 405s ("Unsupported method", "Unknown operation") do not. A third assertion on "405 with Allow: POST" was
        // here and was tautological once the first had passed.
        assertFalse(answered.headers().containsKey("Allow"), what + ": Allow was set - " + answered.body());
        assertFalse(answered.body().contains(REFUSED), what + ": " + answered.body());
    }

    /** An action the servlet's own router takes, with the parameters its handler needs to carry the mutation out. */
    private static Decoded[] withWhatTheHandlerNeeds(String name, String action) {
        List<Decoded> all = new ArrayList<>();
        all.add(p(name, action));
        all.add(p("objectId", TARGET));
        all.add(p("folderId", TARGET));
        all.add(p("propertyId[0]", "cmis:name"));
        all.add(p("propertyValue[0]", "r355-new"));
        all.add(p("propertyId[1]", "cmis:objectTypeId"));
        all.add(p("propertyValue[1]", "cmis:folder"));
        all.add(p("aclPropagation", "objectonly"));
        all.add(p("addACEPrincipal[0]", "anyone"));
        all.add(p("addACEPermission[0][0]", "cmis:all"));
        return all.toArray(new Decoded[0]);
    }

    /** The paths a refused request is driven on: the object route, and the repository's own URL, which the router serves too. */
    private static final String[] PATHS = { "/root", "" };

    /** The actions this servlet's own router takes, each of which changes state. */
    private static final String[] ROUTED = { "delete", "deleteObject", "deleteTree", "applyACL", "applyAcl",
            "updateProperties", "update", "createFolder", "createType", "checkOut", "checkIn", "cancelCheckOut",
            "setContent", "setContentStream", "appendContent", "appendContentStream", "deleteContent",
            "deleteContentStream" };

    /** Actions this servlet's router does not take: the parent servlet dispatches them, or nothing does. */
    private static final String[] NOT_ROUTED = { "deleteType", "createDocument", "createDocumentFromSource",
            "createRelationship", "createPolicy", "createItem", "move", "bulkUpdateProperties", "addObjectToFolder",
            "removeObjectFromFolder", "applyPolicy", "removePolicy", "updateType", "query", "nemaki:notAnAction",
            "moveObject", "DELETE", "" };

    @Test
    void theNamesOfTheMutationsAreTheServicesOwn() {
        // A misspelling in MUTATING would make "no mutation reached the service" an empty claim.
        Set<String> declared = new TreeSet<>();
        for (java.lang.reflect.Method method : CmisService.class.getMethods()) {
            declared.add(method.getName());
        }
        Set<String> notDeclared = new TreeSet<>(MUTATING);
        notDeclared.removeAll(declared);
        assertEquals(Set.of(), notDeclared, "named as a mutation but not declared by the service");
        assertTrue(MUTATING.size() >= 20, "the mutations named: " + MUTATING.size());
    }

    @Test
    void anActionTheRouterTakesIsRefusedOnGetAndOnHeadAndReachesNoService() {
        for (String action : ROUTED) {
            for (String method : new String[] { "GET", "HEAD" }) {
                for (String path : PATHS) {
                    CmisService service = aService();
                    String what = method + " " + action + " on '" + path + "'";
                    Answered answered = assertDoesNotThrow(
                            () -> drive(service, method, path, false, withWhatTheHandlerNeeds("cmisaction", action)), what);
                    refused(answered, method, what);
                    nothingReachedTheService(service, what);
                }
            }
        }
    }

    @Test
    void anActionTheRouterTakesThatOnlyReadsIsRefusedToo() {
        // The router takes two actions that do not change state, and the refusal is by method, not by what the action does,
        // so they are refused as well. These two are not actions the specification names at all - they are this servlet's
        // router's own addition - and they are not the only read this arm narrows: an action the router does not take used
        // to fall through to a selector read as well. What makes that acceptable is measured, not assumed: no sender in
        // this repository puts an action on a GET, and the same reads are asked for by a selector.
        for (String action : new String[] { "getTypeChildren", "getTypesChildren" }) {
            for (String method : new String[] { "GET", "HEAD" }) {
                for (String path : PATHS) {
                    CmisService service = aService();
                    String what = method + " " + action + " on '" + path + "'";
                    Answered answered = assertDoesNotThrow(
                            () -> drive(service, method, path, false, p("cmisaction", action), p("typeId", "cmis:document")), what);
                    refused(answered, method, what);
                    nothingReachedTheService(service, what);
                }
            }
        }
    }

    @Test
    void anActionSentWithAContentTypeThatCrossesOriginsIsRefusedToo() {
        // multipart/form-data is a content type a page may send cross-origin without a preflight, and the product has a
        // second reader of the action for it (OpenCMIS's, which does not look at the method). A refusal that exempted the
        // multipart shape would leave exactly the cross-origin case open, so the content types are driven here.
        for (String contentType : new String[] { "multipart/form-data; boundary=r355", "application/x-www-form-urlencoded",
                "text/plain" }) {
            for (String method : new String[] { "GET", "HEAD", "PUT" }) {
                CmisService service = aService();
                String what = method + " as " + contentType;
                Answered answered = assertDoesNotThrow(() -> driveFully(service, method, "/root", false, null, false,
                        contentType, withWhatTheHandlerNeeds("cmisaction", "delete")), what);
                refused(answered, method, what);
                nothingReachedTheService(service, what);
            }
        }
    }

    @Test
    void anActionTheRouterDoesNotTakeIsRefusedToo() {
        for (String action : NOT_ROUTED) {
            for (String method : new String[] { "GET", "HEAD" }) {
                for (String path : PATHS) {
                    CmisService service = aService();
                    String what = method + " " + (action.isEmpty() ? "<empty>" : action) + " on '" + path + "'";
                    Answered answered = assertDoesNotThrow(
                            () -> drive(service, method, path, false, withWhatTheHandlerNeeds("cmisaction", action)), what);
                    refused(answered, method, what);
                    nothingReachedTheService(service, what);
                }
            }
        }
    }

    @Test
    void aMethodOtherThanGetAndHeadThatNamesAnActionIsRefusedToo() {
        // The last two are not methods any specification sends here: a method the refusal does not know is still not POST.
        for (String method : new String[] { "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "PROPFIND", "CONNECT", "R355PROBE" }) {
            for (String action : new String[] { "delete", "applyACL", "deleteType" }) {
                for (String path : PATHS) {
                    CmisService service = aService();
                    String what = method + " " + action + " on '" + path + "'";
                    Answered answered = assertDoesNotThrow(
                            () -> drive(service, method, path, false, withWhatTheHandlerNeeds("cmisaction", action)), what);
                    refused(answered, method, what);
                    nothingReachedTheService(service, what);
                }
            }
        }
    }

    @Test
    void theNameIsReadInAnyCaseAndHoweverOftenItIsGiven() {
        // The name as the container decodes it: in its own case, in another, from a percent-encoded spelling
        // (CMISACTIO%4E, %63misaction), twice, twice in different cases, with no value, with an empty value.
        // The second column is the query string as it was sent, where that is not the decoded names joined: an encoded
        // spelling reaches the servlet decoded, and only a reader of the decoded names sees it.
        Object[][] cases = { { "its own case", null, new Decoded[] { p("cmisaction", "delete"), p("objectId", TARGET) } },
                { "upper case", null, new Decoded[] { p("CMISACTION", "delete"), p("objectId", TARGET) } },
                { "mixed case", null, new Decoded[] { p("cmisAction", "delete"), p("objectId", TARGET) } },
                { "another mixed case", null, new Decoded[] { p("CmisAction", "delete"), p("objectId", TARGET) } },
                { "capitalised", null, new Decoded[] { p("Cmisaction", "delete"), p("objectId", TARGET) } },
                { "from an encoded spelling", "CMISACTIO%4E=delete&objectId=" + TARGET,
                        new Decoded[] { p("CMISACTION", "delete"), p("objectId", TARGET) } },
                { "from an encoded first letter", "%63misaction=delete&objectId=" + TARGET,
                        new Decoded[] { p("cmisaction", "delete"), p("objectId", TARGET) } },
                { "wholly encoded", "%43%4D%49%53%41%43%54%49%4F%4E=delete&objectId=" + TARGET,
                        new Decoded[] { p("CMISACTION", "delete"), p("objectId", TARGET) } },
                { "twice", null, new Decoded[] { p("cmisaction", "delete"), p("cmisaction", "applyACL"), p("objectId", TARGET) } },
                { "twice in two cases", null,
                        new Decoded[] { p("cmisaction", "delete"), p("CMISACTION", "delete"), p("objectId", TARGET) } },
                { "after another parameter", null, new Decoded[] { p("objectId", TARGET), p("cmisaction", "delete") } },
                { "last of several", null,
                        new Decoded[] { p("objectId", TARGET), p("allVersions", "true"), p("CMISACTION", "delete") } },
                { "with no value", "cmisaction&objectId=" + TARGET, new Decoded[] { p("cmisaction", ""), p("objectId", TARGET) } },
                { "with an empty value", null, new Decoded[] { p("CMISACTION", ""), p("objectId", TARGET) } } };
        for (Object[] c : cases) {
            for (String method : new String[] { "GET", "HEAD" }) {
                CmisService service = aService();
                String what = method + " named " + c[0];
                Answered answered = assertDoesNotThrow(
                        () -> driveRaw(service, method, "/root", false, (String) c[1], (Decoded[]) c[2]), what);
                refused(answered, method, what);
                nothingReachedTheService(service, what);
            }
        }
    }

    @Test
    void aMethodOrASetOfParametersThatCannotBeReadIsRefusedToo() {
        // On either read the permissive answer is the one that lets the request through: a method that cannot be read is
        // not POST, so it is not the method the cross-site check examines, and a parameter map that cannot be read may
        // name an action. "Cannot be read" is held here to the one shape a reader cannot tell from an answer - the
        // accessor answering null. An accessor that throws is not turned into a 405; the exception leaves the servlet,
        // and the request reaches no service either way, which is why it is not this test's subject. Neither arm is
        // reachable through a container, which is why only a lock holds them to the refusing answer.
        CmisService byMethod = aService();
        String whatMethod = "a method that cannot be read";
        Answered unreadMethod = assertDoesNotThrow(() -> driveFully(byMethod, null, "/root", false, null, false, null,
                withWhatTheHandlerNeeds("cmisaction", "delete")), whatMethod);
        refused(unreadMethod, null, whatMethod);
        nothingReachedTheService(byMethod, whatMethod);

        for (String method : new String[] { "GET", "HEAD", "PUT" }) {
            CmisService byParameters = aService();
            String what = method + " whose parameter map cannot be read";
            Answered unreadNames = assertDoesNotThrow(() -> driveFully(byParameters, method, "/root", false, null, true, null,
                    withWhatTheHandlerNeeds("cmisaction", "delete")), what);
            refused(unreadNames, method, what);
            nothingReachedTheService(byParameters, what);
        }
    }

    @Test
    void anActionSentWithACredentialTheBrowserAttachesIsRefusedToo() {
        // The condition this arm exists for: a browser attaches the reader's own credential for the realm to a plain
        // sub-resource load from any origin. A refusal that let a request carrying one through would leave exactly the
        // case the arm is for, and every other case here carries no credential at all, so it is driven on its own.
        Object[][] credentials = { { "a Basic realm", Map.of("Authorization", "Basic cjM1NTpub3QtYS1yZWFsLW9uZQ==") },
                { "a session cookie", Map.of("Cookie", "JSESSIONID=r355") },
                { "both", Map.of("Authorization", "Basic cjM1NTpub3QtYS1yZWFsLW9uZQ==", "Cookie", "JSESSIONID=r355") } };
        for (Object[] credential : credentials) {
            for (String method : new String[] { "GET", "HEAD", "PUT" }) {
                for (boolean crossSite : new boolean[] { true, false }) {
                    CmisService service = aService();
                    String what = method + " with " + credential[0] + (crossSite ? " cross-site" : " same-site");
                    @SuppressWarnings("unchecked")
                    Map<String, String> headers = (Map<String, String>) credential[1];
                    Answered answered = assertDoesNotThrow(() -> driveFully(service, method, "/root", crossSite, null,
                            false, null, headers, withWhatTheHandlerNeeds("cmisaction", "delete")), what);
                    refused(answered, method, what);
                    nothingReachedTheService(service, what);
                }
            }
        }
    }

    @Test
    void anActionBesideASelectorIsRefusedAndNotFoldedIntoTheRead() {
        // The read the selector asks for must not be the answer: that would hide the refusal and answer 200.
        for (String selector : new String[] { "object", "children", "content", "versions", "typeChildren", "parents",
                "typeDefinition", "allowableActions" }) {
            for (String method : new String[] { "GET", "HEAD" }) {
                CmisService service = aService();
                String what = method + " " + selector + " beside an action";
                Answered answered = assertDoesNotThrow(() -> drive(service, method, "/root", false,
                        p("cmisselector", selector), p("cmisaction", "delete"), p("objectId", TARGET)), what);
                refused(answered, method, what);
                nothingReachedTheService(service, what);
            }
        }
    }

    @Test
    void anOrdinaryReadThatNamesNoActionIsNotRefusedAndReachesTheService() {
        // What is locked is that this arm does not take an ordinary read away from its route: the read is not answered by
        // the refusal of this arm, and it reaches a service call. What the read then answers is other locks' subject -
        // a selector that does not belong to the route it is on is answered by OpenCMIS, 405 "Unknown operation", which is
        // why "not refused" is read as "not refused by THIS arm" rather than as a status that is not 405. "Reached a
        // service call" is the weaker half: OpenCMIS's own dispatcher closes the service in a finally, so arriving at
        // super.service() is enough to satisfy it. It says the read was not turned away before the routing, not that the
        // read was served; what carries this test is the absence of this arm's own refusal.
        Object[][] reads = { { "/root", "object" }, { "/root", "children" }, { "/root", "content" }, { "/root", "versions" },
                { "/root", "parents" }, { "/root", "allowableActions" }, { "/root", "acl" }, { "", "typeChildren" },
                { "", "typeDefinition" }, { "", "repositoryInfo" } };
        for (Object[] read : reads) {
            for (String method : new String[] { "GET", "HEAD" }) {
                CmisService service = aService();
                String what = method + " " + read[1] + " on '" + read[0] + "'";
                Answered answered = assertDoesNotThrow(() -> drive(service, method, (String) read[0], false,
                        p("cmisselector", (String) read[1]), p("objectId", TARGET)), what);
                wasNotRefusedByThisArm(answered, what);
                assertFalse(whatWasCalled(service).isEmpty(), what + ": the read reached no service");
            }
        }
    }

    @Test
    void anOrdinaryPostMutationStillReachesItsService() {
        CmisService service = aService();
        Answered answered = assertDoesNotThrow(
                () -> drive(service, "POST", "/root", false, p("cmisaction", "delete"), p("objectId", TARGET)));
        assertEquals(HttpServletResponse.SC_OK, answered.status(), answered.body());
        assertFalse(answered.headers().containsKey("Allow"), "Allow was set on a POST");
        verify(service).deleteObject(eq(REPO), eq(TARGET), any(), isNull());
    }

    @Test
    void aCrossSitePostIsStillRefusedByTheCrossSiteCheck() {
        CmisService service = aService();
        Answered answered = assertDoesNotThrow(
                () -> drive(service, "POST", "/root", true, p("cmisaction", "delete"), p("objectId", TARGET)));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, answered.status(), answered.body());
        assertTrue(answered.body().contains("permissionDenied"), answered.body());
        nothingReachedTheService(service, "a cross-site POST");
    }

    @Test
    void everyMethodThatNamesAnActionIsEitherRefusedOrCrossSiteChecked() {
        // The claim that carries the security: a method that names an action leaves either by the 405 of this arm or by
        // the cross-site check, and a mutation reaches the service only on a same-site POST. Each method is driven both
        // ways, because an arm that refused only the cross-site request would leave the plain sub-resource load — which
        // carries no Origin and, from an older browser, no Sec-Fetch-Site either — going through. Every exit is required
        // to be taken, so no arm of the claim is empty.
        String[] methods = { "GET", "HEAD", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT", "R355PROBE", "post",
                "POST" };
        Set<String> readsAsPost = Set.of("POST");
        Set<String> notPost = Set.of("CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "post", "PUT", "R355PROBE",
                "TRACE");
        Set<String> refusedCrossSite = new TreeSet<>();
        Set<String> checkedCrossSite = new TreeSet<>();
        Set<String> refusedSameSite = new TreeSet<>();
        Set<String> servedSameSite = new TreeSet<>();
        for (String method : methods) {
            CmisService crossSite = aService();
            String whatCross = "cross-site " + method;
            Answered cross = assertDoesNotThrow(
                    () -> drive(crossSite, method, "/root", true, withWhatTheHandlerNeeds("cmisaction", "delete")), whatCross);
            nothingReachedTheService(crossSite, whatCross);
            if (cross.status() == HttpServletResponse.SC_METHOD_NOT_ALLOWED) {
                assertEquals("POST", cross.headers().get("Allow"), whatCross + ": Allow");
                refusedCrossSite.add(method);
            } else {
                assertEquals(HttpServletResponse.SC_FORBIDDEN, cross.status(), whatCross + ": " + cross.body());
                assertTrue(cross.body().contains("permissionDenied"), whatCross + ": " + cross.body());
                checkedCrossSite.add(method);
            }

            CmisService sameSite = aService();
            String whatSame = "same-site " + method;
            Answered same = assertDoesNotThrow(
                    () -> drive(sameSite, method, "/root", false, withWhatTheHandlerNeeds("cmisaction", "delete")), whatSame);
            if (same.status() == HttpServletResponse.SC_METHOD_NOT_ALLOWED
                    && "POST".equals(same.headers().get("Allow"))) {
                nothingReachedTheService(sameSite, whatSame);
                refusedSameSite.add(method);
            } else {
                verify(sameSite).deleteObject(eq(REPO), eq(TARGET), any(), isNull());
                servedSameSite.add(method);
            }
        }
        assertEquals(notPost, refusedCrossSite, "refused by the 405 of this arm, cross-site");
        assertEquals(readsAsPost, checkedCrossSite, "left to the cross-site check");
        assertEquals(notPost, refusedSameSite, "refused by the 405 of this arm, same-site");
        assertEquals(readsAsPost, servedSameSite, "served, which only a method that reads as POST may be");
    }
}
