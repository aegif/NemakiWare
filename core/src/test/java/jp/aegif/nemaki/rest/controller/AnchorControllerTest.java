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

import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every door on the anchor API is admin-only.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>It did not, for a while: {@code AnchorController} shipped with four endpoints, four
 * {@code requireAdmin()} calls and zero tests. A review pointed out that deleting the three
 * lines in {@code status()} would let any authenticated caller read the Merkle root, the
 * ledger's head sequence and the receipt list, and nothing in the suite would notice.
 *
 * <p>The sibling {@code AuthenticityReportControllerTest} had already written down the reason
 * this matters — "it would be entirely possible to gate one and leave the other open" — while
 * this controller had four ungated-by-omission doors.
 *
 * <p>Rather than one test per endpoint, the first test walks EVERY mapped method by reflection.
 * A fifth endpoint added later is covered the day it is written, which a hand-listed set is not.
 */
class AnchorControllerTest {

    private static AnchorController controllerFor(boolean admin) {
        AnchorController controller = new AnchorController();
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);
        return controller;
    }

    private static List<Method> mappedEndpoints() {
        List<Method> endpoints = new ArrayList<>();
        for (Method method : AnchorController.class.getDeclaredMethods()) {
            // EVERY mapping annotation. Limiting this to Get/Post is what made the claim
            // "a fifth endpoint is covered the day it is written" untrue — this project uses
            // PATCH, and a @PutMapping/@DeleteMapping/@PatchMapping door would have been
            // walked past in silence (review).
            for (Class<? extends java.lang.annotation.Annotation> mapping : List.of(
                    GetMapping.class, PostMapping.class,
                    org.springframework.web.bind.annotation.PutMapping.class,
                    org.springframework.web.bind.annotation.DeleteMapping.class,
                    org.springframework.web.bind.annotation.PatchMapping.class,
                    org.springframework.web.bind.annotation.RequestMapping.class)) {
                if (method.getAnnotation(mapping) != null) {
                    endpoints.add(method);
                    break;
                }
            }
        }
        return endpoints;
    }

    /** Plausible arguments for each endpoint, by parameter type. */
    private static Object[] argumentsFor(Method method) {
        Object[] args = new Object[method.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = method.getParameterTypes()[i];
            if (type == String.class) {
                args[i] = "bedroom";
            } else if (type == int.class) {
                args[i] = 10;
            } else if (type == boolean.class) {
                args[i] = false;
            }
        }
        return args;
    }

    @Test
    @DisplayName("EVERY mapped endpoint refuses a non-admin")
    void everyEndpointIsAdminOnly() throws Exception {
        AnchorController controller = controllerFor(false);
        List<Method> endpoints = mappedEndpoints();

        // A count guard, so an endpoint that stops being mapped (and therefore stops being
        // checked) is visible rather than silently reducing the coverage of this test.
        assertEquals(4, endpoints.size(),
                "the anchor API has " + endpoints.size() + " mapped endpoints, not 4; if one "
                        + "was added, confirm it is gated and update this number");

        for (Method endpoint : endpoints) {
            Object response = endpoint.invoke(controller, argumentsFor(endpoint));
            HttpStatus status = (HttpStatus) response.getClass()
                    .getMethod("getStatusCode").invoke(response);
            assertEquals(HttpStatus.FORBIDDEN, status,
                    endpoint.getName() + " served a non-admin. This API exposes the Merkle "
                            + "root, the ledger's head sequence and every anchor receipt, and "
                            + "checkpoint-and-anchor SENDS data to an external service.");
        }
    }

    @Test
    @DisplayName("an admin is not refused — the control")
    void anAdminGetsThrough() throws Exception {
        // Without this, gating everything shut unconditionally would pass the test above and
        // the API would be unusable.
        AnchorController controller = controllerFor(true);

        Method status = AnchorController.class.getDeclaredMethod("status", String.class);
        Object response = status.invoke(controller, "bedroom");
        HttpStatus code = (HttpStatus) response.getClass()
                .getMethod("getStatusCode").invoke(response);

        // The ledger store is unwired here, so SERVICE_UNAVAILABLE is the right answer — what
        // matters is that it is NOT the refusal.
        assertTrue(code != HttpStatus.FORBIDDEN,
                "an admin was refused; the gate no longer distinguishes who is asking");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, code,
                "an unwired ledger answered " + code + "; absence of the store must not read "
                        + "as an empty but successful result");
    }
}
