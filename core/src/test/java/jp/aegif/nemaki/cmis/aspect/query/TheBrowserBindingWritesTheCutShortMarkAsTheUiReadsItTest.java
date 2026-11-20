/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.cmis.aspect.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.impl.browser.DiscoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CMIS Browser binding writes the cut-short mark of a query answer where the UI reads it (R344, 3.4.2).
 *
 * <p>The UI's search reads the Browser binding's JSON, so the mark has to arrive there, and the UI has to read the shape the
 * serializer really writes — not a shape guessed from the Java object. This lock drives the Browser binding's own query
 * operation ({@code org.apache.chemistry.opencmis.server.impl.browser.DiscoveryService.Query}, the one the servlet
 * dispatches {@code cmisaction=query} to) over answers marked by the product's own producer, and holds what it writes against
 * the fixture the UI's lock reads ({@code src/main/webapp/ui/src/services/__fixtures__/browserQueryCutShort.json}). The
 * comparison is of the parsed JSON, not of its bytes (the serializer's key order is not a contract). With the system
 * property {@code r344.writeUiFixture=true} the fixture is written from the serializer first.
 *
 * <p>What it shows: the mark is a top-level key {@code truncatedByAclScanLimit} with the STRING value {@code "true"}, beside
 * {@code results}, {@code numItems} and {@code hasMoreItems}; two elements of that name become an array of their values; the
 * name under another element stays nested.
 */
class TheBrowserBindingWritesTheCutShortMarkAsTheUiReadsItTest {

    static final Path FIXTURE = Path.of("src/main/webapp/ui/src/services/__fixtures__/browserQueryCutShort.json");

    /** The cases the UI's lock reads, by name. */
    static Map<String, ObjectListImpl> cases() {
        Map<String, ObjectListImpl> cases = new LinkedHashMap<>(CutShortAnswers.marked());
        cases.put("markedAfterAFalseElement", CutShortAnswers.markedByTheProducer(CutShortAnswers.with(CutShortAnswers.answer(1,
                Boolean.FALSE, 1), new CmisExtensionElementImpl(null, CutShortByAclScanLimit.MARK, null, "false"))));
        cases.put("unmarked", CutShortAnswers.unmarked());
        cases.put("markedFalse", CutShortAnswers.markedFalse());
        cases.put("markNestedUnderAnotherElement", CutShortAnswers.with(CutShortAnswers.answer(1, Boolean.FALSE, 1),
                new CmisExtensionElementImpl(null, "other", null,
                        List.of(new CmisExtensionElementImpl(null, CutShortByAclScanLimit.MARK, null, "true")))));
        return cases;
    }

    /** What the Browser binding's query operation writes for {@code answer}. */
    static String browserJson(ObjectListImpl answer) throws Exception {
        CmisService service = mock(CmisService.class);
        when(service.query(eq("bedroom"), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(answer);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, String[]> parameters = new LinkedHashMap<>();
        parameters.put("cmisaction", new String[] {"query"});
        parameters.put("statement", new String[] {"SELECT * FROM cmis:document"});
        when(request.getParameterMap()).thenReturn(parameters);
        when(request.getMethod()).thenReturn("POST");
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter written = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(written));
        new DiscoveryService.Query().serve(mock(CallContext.class), service, "bedroom", request, response);
        return written.toString();
    }

    @Test
    @DisplayName("the Browser binding writes each case as the fixture the UI reads holds it")
    void theFixtureIsWhatTheBrowserBindingWrites() throws Exception {
        Map<String, Object> written = new LinkedHashMap<>();
        StringBuilder file = new StringBuilder("{\n");
        for (Map.Entry<String, ObjectListImpl> c : cases().entrySet()) {
            String json = browserJson(c.getValue());
            written.put(c.getKey(), new JSONParser().parse(json));
            file.append(file.length() > 2 ? ",\n" : "").append("  \"").append(c.getKey()).append("\": ").append(json);
        }
        file.append("\n}\n");
        if (Boolean.getBoolean("r344.writeUiFixture")) {
            Files.writeString(FIXTURE, file.toString(), StandardCharsets.UTF_8);
        }
        assertTrue(Files.exists(FIXTURE), "the UI's fixture " + FIXTURE + " is not there");
        Object held = new JSONParser().parse(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        assertEquals(written, held, "the UI's fixture is not what the Browser binding writes");
    }

    @Test
    @DisplayName("the mark is a top-level key with the string value true; two elements of its name are an array")
    void theMarkIsATopLevelStringKey() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> marked = (Map<String, Object>) new JSONParser().parse(browserJson(cases().get("markedWithMoreInsideTheWindow")));
        assertEquals("true", marked.get(CutShortByAclScanLimit.MARK), "the mark as written: " + marked);
        assertTrue(marked.containsKey("results") && marked.containsKey("numItems") && marked.containsKey("hasMoreItems"),
                "the answer's own keys beside the mark: " + marked.keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> twice = (Map<String, Object>) new JSONParser().parse(browserJson(cases().get("markedAfterAFalseElement")));
        assertEquals(List.of("false", "true"), twice.get(CutShortByAclScanLimit.MARK), "two elements of the mark's name: " + twice);
    }
}
