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
package jp.aegif.nemaki.odata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.provider.CsdlSingleton;
import org.apache.olingo.commons.api.http.HttpMethod;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataHandler;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.mockito.invocation.Invocation;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.cmis.service.VersioningService;

/**
 * Sends a write or an action through Olingo's own handler — its parser and its dispatcher — to the NemakiWare processors,
 * registered as {@code ODataServlet} registers them (R261, R262, R270, R271, R272 — 3.4.2). The CMIS services are mocks that
 * record what was called with which object id; the body is a stream that records whether it was read.
 *
 * <p>{@code getObject} answers an object whose base type the id names ({@link #baseTypeOf}), unless {@link #onRead} says
 * otherwise for that id.
 */
final class ODataWriteRequests {

    static final String BASE = "http://localhost/core/odata/bedroom";
    static final String NAME = "{\"name\":\"renamed\"}";
    static final String MALFORMED = "{\"name\":[1,2";
    static final String ACL = "{\"addAces\":\"[{\\\"principal\\\":\\\"GROUP_EVERYONE\\\",\\\"permissions\\\":[\\\"cmis:all\\\"]}]\"}";
    static final String MALFORMED_PARAMETERS = "{\"addAces\":[1,2";
    static final String MOVE = "{\"targetFolderId\":\"T\",\"sourceFolderId\":\"S\"}";

    /** The CMIS writes the rows name, and the other writes an action can reach. */
    static final List<String> WRITES = List.of("deleteObject", "updateProperties", "createFolder", "createDocument",
            "applyAcl", "moveObject", "checkOut", "cancelCheckOut", "checkIn", "createDocumentFromSource");

    final CallContext callContext = mock(CallContext.class);
    final ObjectService objects = mock(ObjectService.class);
    final RepositoryService repository = mock(RepositoryService.class);
    final NavigationService navigation = mock(NavigationService.class);
    final DiscoveryService discovery = mock(DiscoveryService.class);
    final VersioningService versioning = mock(VersioningService.class);
    final AclService acls = mock(AclService.class);
    /** What {@code getObject} does for an id in place of answering {@link #object}: throw, or answer something else. */
    final Map<String, Callable<Object>> onRead = new HashMap<>();
    private final boolean singletons;
    private boolean bodyRead;

    /** Requests against the EDM the servlet serves. */
    ODataWriteRequests() {
        this(false);
    }

    /**
     * {@code singletons}: the servlet's EDM with two singletons added ({@code TheDocument}, {@code TheFolder}) — the only way
     * a write or an action that does not start with an entity set reaches a processor, since Olingo refuses every such shape
     * of the servlet's own EDM before dispatching it.
     */
    ODataWriteRequests(boolean singletons) {
        this.singletons = singletons;
        when(objects.getObject(any(), eq("bedroom"), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    Callable<Object> read = onRead.get(call.getArgument(2));
                    return read != null ? read.call() : object(call.getArgument(2));
                });
        RepositoryInfo info = new RepositoryInfo();
        info.setRootFolder("ROOT");
        when(repository.getRepositoryInfo("bedroom")).thenReturn(info);
        when(objects.createFolder(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("NEWFOLDER");
        when(objects.createDocument(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("NEWDOC");
    }

    record Answer(String request, int status, String body) {
        /** The message of an OData error body, or null. */
        String message() {
            Matcher m = Pattern.compile("\"message\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
            return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
        }

        /** The objectId of the entity answered, or null. */
        String objectId() {
            Matcher m = Pattern.compile("\"objectId\":\"([^\"]*)\"").matcher(body);
            return m.find() ? m.group(1) : null;
        }
    }

    /** {@code method path} with {@code body} (null for none), answered by Olingo's handler and the processors. */
    Answer send(String method, String path, String body) throws Exception {
        OData odata = OData.newInstance();
        ServiceMetadata metadata = odata.createServiceMetadata(
                singletons ? new WithSingletons() : new CmisEdmProvider(), new ArrayList<>());
        // As ODataServlet registers them.
        CmisFunctionProcessor functionProcessor = new CmisFunctionProcessor(
                repository, objects, navigation, discovery, versioning, "bedroom", callContext);
        functionProcessor.init(odata, metadata);
        CmisEntityCollectionProcessor collectionProcessor = new CmisEntityCollectionProcessor(
                repository, objects, navigation, discovery, "bedroom", callContext);
        collectionProcessor.setFunctionProcessor(functionProcessor);
        CmisEntityProcessor entityProcessor = new CmisEntityProcessor(repository, objects, navigation, "bedroom", callContext);
        entityProcessor.setFunctionProcessor(functionProcessor);
        ODataHandler handler = odata.createRawHandler(metadata);
        handler.register(collectionProcessor);
        handler.register(entityProcessor);
        handler.register(new CmisActionProcessor(
                repository, objects, versioning, navigation, acls, "bedroom", callContext));

        ODataRequest request = new ODataRequest();
        request.setMethod(HttpMethod.valueOf(method));
        int q = path.indexOf('?');
        request.setRawODataPath(q < 0 ? path : path.substring(0, q));
        request.setRawQueryPath(q < 0 ? "" : path.substring(q + 1));
        request.setRawBaseUri(BASE);
        request.setRawRequestUri(BASE + path);
        request.setRawServiceResolutionUri("");
        request.setProtocol("HTTP/1.1");
        request.addHeader("Accept", "application/json");
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (body != null) {
            request.addHeader("Content-Type", "application/json");
        }
        bodyRead = false;
        request.setBody(new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read() {
                bodyRead = true;
                return super.read();
            }

            @Override
            public synchronized int read(byte[] b, int off, int len) {
                bodyRead = true;
                return super.read(b, off, len);
            }

            @Override
            public int read(byte[] b) throws IOException {
                bodyRead = true;
                return super.read(b);
            }

            @Override
            public synchronized byte[] readAllBytes() {
                bodyRead = true;
                return super.readAllBytes();
            }
        });
        ODataResponse response = handler.process(request);
        String answered = "";
        if (response.getContent() != null) {
            try (InputStream in = response.getContent()) {
                answered = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new Answer(method + " " + path, response.getStatusCode(), answered);
    }

    /** Whether the last request's body was read. */
    boolean bodyWasRead() {
        return bodyRead;
    }

    /** The CMIS writes called, as {@code name(repositoryId, objectId)} — for a create, the parent's id. */
    List<String> writes() {
        List<String> writes = new ArrayList<>();
        for (Object service : new Object[] {objects, versioning, acls}) {
            for (Invocation call : mockingDetails(service).getInvocations()) {
                String name = call.getMethod().getName();
                if (!WRITES.contains(name)) {
                    continue;
                }
                int id = name.startsWith("create") && !name.equals("createDocumentFromSource") ? 3 : 2;
                Object target = call.getArgument(id);
                if (target instanceof Holder<?> holder) {
                    target = holder.getValue();
                }
                writes.add(name + "(" + call.getArgument(1) + ", " + target + ")");
                assertEquals(callContext, call.getArgument(0), name + " was not called with the request's call context");
            }
        }
        return writes;
    }

    /** The ids {@code getObject} was called for, each with the request's call context and repository. */
    List<String> reads() {
        List<String> reads = new ArrayList<>();
        for (Invocation call : mockingDetails(objects).getInvocations()) {
            if (call.getMethod().getName().equals("getObject")) {
                assertEquals(callContext, call.getArgument(0), "getObject was not called with the request's call context");
                assertEquals("bedroom", call.getArgument(1), "getObject was not called for the request's repository");
                reads.add(call.getArgument(2));
            }
        }
        return reads;
    }

    /** Every call any service received, by method name. */
    List<String> calls() {
        List<String> calls = new ArrayList<>();
        for (Object service : new Object[] {objects, repository, navigation, discovery, versioning, acls}) {
            for (Invocation call : mockingDetails(service).getInvocations()) {
                calls.add(call.getMethod().getName());
            }
        }
        return calls;
    }

    /**
     * The request was refused with a 400 and {@code message}, without its body being read and without any CMIS service being
     * called — none of the writes the rows name, and nothing else.
     */
    void assertRefused(Answer answer, String message) {
        assertEquals(400, answer.status(), answer.request() + " was answered " + answer.body());
        assertEquals(message, answer.message(), answer.request() + " was refused by another arm");
        assertFalse(bodyWasRead(), answer.request() + " read its body before it was refused");
        List<String> calls = calls();
        for (String write : WRITES) {
            assertFalse(calls.contains(write), answer.request() + " called " + write);
        }
        assertEquals(List.of(), calls, answer.request() + " called a CMIS service before it was refused");
    }

    /**
     * The request was answered 404 because {@code entitySet} does not hold the entity {@code id} names: the entity was read
     * once with the request's call context, and neither the body nor the action's parameters were read, and no other CMIS
     * service was called (R271).
     */
    void assertNotHeld(Answer answer, String entitySet, String id) {
        assertEquals(404, answer.status(), answer.request() + " was answered " + answer.body());
        assertEquals(WriteTarget.NOT_HELD + entitySet + ": " + id, answer.message(), answer.request() + " was answered by another arm");
        assertFalse(bodyWasRead(), answer.request() + " read its body before it was answered 404");
        assertEquals(List.of(), writes(), answer.request() + " wrote to an entity its entity set does not hold");
        assertEquals(List.of("getObject"), calls(), answer.request() + " called a CMIS service besides the read of its entity");
        assertEquals(List.of(id), reads(), answer.request() + " did not read the entity its key names");
    }

    /**
     * The entity could not be read, or its base type could not: the request was answered {@code status} with a message that
     * says so — not the 404 of an entity that is not there — and neither the body was read nor anything written (R271).
     */
    void assertNotWritten(Answer answer, int status) {
        assertEquals(status, answer.status(), answer.request() + " was answered " + answer.body());
        assertNotEquals(404, answer.status(), answer.request() + " answered a failed read as an absent entity");
        assertTrue(answer.message() != null && answer.message().startsWith(WriteTarget.UNREADABLE),
                answer.request() + " was answered by another arm: " + answer.body());
        assertFalse(bodyWasRead(), answer.request() + " read its body although its entity could not be read");
        assertEquals(List.of(), writes(), answer.request() + " wrote although its entity could not be read");
        assertEquals(List.of("getObject"), calls(), answer.request() + " called a CMIS service besides the read of its entity");
    }

    /** The base type of the object an id names: F…, ROOT and NEWFOLDER folders, REL… relationships, POL… policies, ITEM… items. */
    static String baseTypeOf(String id) {
        if (id.startsWith("F") || id.equals("ROOT") || id.equals("NEWFOLDER")) {
            return "cmis:folder";
        }
        if (id.startsWith("REL")) {
            return "cmis:relationship";
        }
        if (id.startsWith("POL")) {
            return "cmis:policy";
        }
        return id.startsWith("ITEM") ? "cmis:item" : "cmis:document";
    }

    static ObjectDataImpl object(String id) {
        String base = baseTypeOf(id);
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl("cmis:objectId", id));
        properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", base));
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", base));
        if (base.equals("cmis:relationship")) {
            // As a relationship is: the Relationship type's sourceId and targetId are not nullable.
            properties.addProperty(new PropertyIdImpl("cmis:sourceId", "D"));
            properties.addProperty(new PropertyIdImpl("cmis:targetId", "D2"));
        }
        properties.addProperty(new PropertyStringImpl("cmis:name", id + ".name"));
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    /** The servlet's EDM and two singletons. */
    static final class WithSingletons extends CmisEdmProvider {
        @Override
        public CsdlSingleton getSingleton(FullQualifiedName entityContainer, String singletonName) {
            if ("TheDocument".equals(singletonName)) {
                return new CsdlSingleton().setName(singletonName).setType(ET_DOCUMENT_FQN);
            }
            if ("TheFolder".equals(singletonName)) {
                return new CsdlSingleton().setName(singletonName).setType(ET_FOLDER_FQN);
            }
            return null;
        }
    }
}
