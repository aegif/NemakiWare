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
package jp.aegif.nemaki.rest.controller;

import jp.aegif.nemaki.evidence.AuthenticityReport;
import jp.aegif.nemaki.evidence.AuthenticityReportAssembler;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The evidence report is admin-only, in both representations (P1-4 AC 8).
 *
 * <h2>Why the HTML door is tested separately</h2>
 *
 * <p>Two endpoints render the SAME report, and it would be entirely possible to gate the JSON
 * one and leave the HTML one open — the kind of asymmetry a single test on "the endpoint"
 * never catches. The identity section carries capture evidence and the custody section names
 * connectors and schedules, so an ungated HTML door hands an inventory of how this deployment
 * ingests to any authenticated caller.
 */
class AuthenticityReportControllerTest {

    private static AuthenticityReportController controllerFor(boolean admin,
            AuthenticityReportAssembler assembler) {
        AuthenticityReportController controller = new AuthenticityReportController();
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);
        controller.setAssembler(assembler);
        return controller;
    }

    private static AuthenticityReportAssembler assemblerReturningSomething() {
        AuthenticityReportAssembler assembler = mock(AuthenticityReportAssembler.class);
        when(assembler.assemble(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new AuthenticityReport("bedroom", "doc-1", "t",
                        List.of(new AuthenticityReport.Section("identity",
                                AuthenticityReport.Verdict.REPORTED,
                                Map.of("nemaki:sourceSystem", "slack"),
                                "recorded faithfully is not true"))));
        return assembler;
    }

    @Test
    @DisplayName("AC8: a non-admin gets 403 from the JSON endpoint")
    void jsonIsAdminOnly() {
        AuthenticityReportAssembler assembler = assemblerReturningSomething();
        ResponseEntity<Map<String, Object>> response =
                controllerFor(false, assembler).report("bedroom", "doc-1", false);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        // Refused BEFORE the work, not after: assembling and then discarding would still have
        // read the evidence and written the "requested WITH internal-only" audit line.
        verify(assembler, never()).assemble(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("AC8: a non-admin gets 403 from the HTML endpoint too")
    void htmlIsAdminOnly() {
        AuthenticityReportAssembler assembler = assemblerReturningSomething();
        ResponseEntity<String> response =
                controllerFor(false, assembler).reportHtml("bedroom", "doc-1", false);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "the HTML endpoint renders the same evidence and is not gated");
        assertFalse(String.valueOf(response.getBody()).contains("slack"),
                "the refusal still leaked section content");
        verify(assembler, never()).assemble(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("AC8 control: an admin gets the report from both doors")
    void anAdminIsServed() {
        // Without this, gating everything shut would pass both tests above.
        ResponseEntity<Map<String, Object>> json =
                controllerFor(true, assemblerReturningSomething()).report("bedroom", "doc-1",
                        false);
        assertEquals(HttpStatus.OK, json.getStatusCode());
        assertNotNull(json.getBody());
        // Content, not identity with the constant: comparing REPORT_LIMITS to itself passes
        // even when the paragraph has been emptied.
        String limits = String.valueOf(json.getBody().get("whatThisDoesNotEstablish"));
        // Reworded with the identity section: it no longer claims "recorded faithfully",
        // because this path reads the stored aspects without re-checking the capture hash.
        assertTrue(limits.contains("would not be truth in any case")
                        && limits.contains("not that they were never altered"),
                "the report served to an admin lost the clauses that keep a digest from "
                        + "reading as proof: " + limits);

        ResponseEntity<String> html =
                controllerFor(true, assemblerReturningSomething()).reportHtml("bedroom", "doc-1",
                        false);
        assertEquals(HttpStatus.OK, html.getStatusCode());
        assertTrue(String.valueOf(html.getBody()).contains("does not establish"),
                "the HTML served to an admin has no limits paragraph");
    }

    @Test
    @DisplayName("a caller who omits the flag gets no personal data — on EVERY endpoint")
    void theFlagDefaultsToWithholding() {
        // Read from the annotation, not from a direct call. A direct call passes the flag
        // explicitly and so never exercises Spring's default at all: asserting the value came
        // back would only be asserting what the test itself passed in.
        int checked = 0;
        for (Method method : AuthenticityReportController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                RequestParam annotation = parameter.getAnnotation(RequestParam.class);
                if (annotation == null || parameter.getType() != boolean.class) {
                    continue;
                }
                assertEquals("false", annotation.defaultValue(),
                        "an endpoint (" + method.getName() + ") defaults a boolean request "
                                + "parameter to '" + annotation.defaultValue() + "'; the only "
                                + "one here widens what the report discloses, and a caller who "
                                + "omits it did not ask for personal data");
                checked++;
            }
        }
        assertEquals(2, checked,
                "expected the flag on both the JSON and HTML endpoints but found " + checked
                        + "; if an endpoint was added or renamed this test stopped covering it");
    }
}
