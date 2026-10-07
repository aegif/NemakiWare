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

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The caveat travels with every answer, including the ones that refuse.
 *
 * <h2>Why one test over three controllers</h2>
 *
 * <p>Each of these classes states in its own javadoc that every response carries the limits of
 * what a result establishes, and each of them had exits that did not: the shared
 * {@code requireAdmin()} and {@code unavailable()} helpers, which return from inside the prologue
 * where each endpoint sets the key — so the arms that bypassed the promise were the shared ones,
 * in all three classes, for the same reason.
 *
 * <p>Written per-controller it would have been three tests, and the review that found this found
 * it in three separate places one round apart. Iterating the controllers is what makes the next
 * one arrive already covered, and iterating the ENDPOINTS is what stops a lock passing because
 * the one endpoint it names still works.
 *
 * <p>A refusal is where the caveat matters most, not least: a reader who gets a number knows to
 * ask what it means, and a reader who gets "not available" concludes there is nothing to know.
 */
class EveryRefusalCarriesItsLimitsTest {

    /** Endpoints that answer with bytes rather than JSON have nowhere to put a key. */
    private static final List<String> NOT_JSON = List.of();

    @Test
    @DisplayName("EVERY mapped endpoint's non-admin refusal carries its limits")
    void everyRefusalSaysWhatItDoesNotEstablish() throws Exception {
        List<String> silent = new ArrayList<>();
        int checked = 0;
        for (Class<?> type : List.of(AnchorController.class, FixityController.class,
                EarkSipExportController.class)) {
            Object controller = type.getDeclaredConstructor().newInstance();
            setHttpRequest(controller);
            for (Method endpoint : mappedEndpoints(type)) {
                if (NOT_JSON.contains(endpoint.getName())) {
                    continue;
                }
                checked++;
                Object response = endpoint.invoke(controller, argumentsFor(endpoint));
                Object payload = response.getClass().getMethod("getBody").invoke(response);
                if (!(payload instanceof Map<?, ?> body)) {
                    // A refusal that is not a JSON object cannot carry the key; that is a
                    // finding in itself, because this is the admin gate and it should refuse in
                    // the same shape whatever the endpoint returns when it succeeds.
                    silent.add(type.getSimpleName() + "." + endpoint.getName()
                            + " refused with a non-JSON body: " + payload);
                    continue;
                }
                if (!body.containsKey("limits")) {
                    silent.add(type.getSimpleName() + "." + endpoint.getName() + " :: " + body);
                }
            }
        }
        // The fixture check. A rename of the mapping annotations, or a constructor that starts
        // throwing, would empty this loop and the test would pass by looking at nothing.
        assertTrue(checked >= 8,
                "only " + checked + " endpoints were exercised, so this test is no longer "
                        + "looking at the API it exists to cover");
        if (!silent.isEmpty()) {
            fail("a refusal arrives with no statement of what it does and does not establish, "
                    + "so a reader concludes there is nothing to know:\n"
                    + String.join("\n", silent));
        }
    }

    @Test
    @DisplayName("EVERY mapped endpoint's UNAVAILABLE refusal carries its limits too")
    void everyUnavailableSaysWhatItDoesNotEstablish() throws Exception {
        // The test above only ever reaches requireAdmin(): with no CallContext the admin gate
        // returns first, so unavailable() — the OTHER shared helper this file claims to cover —
        // was never called. Deleting its `limits` line left both tests green.
        //
        // Here the caller IS an admin and the services are unwired, which is the shape a node
        // takes when a bean is missing: the 503 arm, reached through every endpoint.
        List<String> silent = new ArrayList<>();
        int checked = 0;
        for (Class<?> type : List.of(AnchorController.class, FixityController.class,
                EarkSipExportController.class)) {
            Object controller = type.getDeclaredConstructor().newInstance();
            setAdminHttpRequest(controller);
            for (Method endpoint : mappedEndpoints(type)) {
                Object response = endpoint.invoke(controller, argumentsFor(endpoint));
                int status = ((org.springframework.http.HttpStatus) response.getClass()
                        .getMethod("getStatusCode").invoke(response)).value();
                if (status != 503) {
                    // Not every endpoint has an unwired arm; only judge the ones that do.
                    continue;
                }
                checked++;
                Object payload = response.getClass().getMethod("getBody").invoke(response);
                if (!(payload instanceof Map<?, ?> body) || !body.containsKey("limits")) {
                    silent.add(type.getSimpleName() + "." + endpoint.getName() + " :: " + payload);
                }
            }
        }
        assertTrue(checked >= 3,
                "only " + checked + " endpoints reached their 503 arm, so this test is not "
                        + "looking at the helper it exists to cover");
        if (!silent.isEmpty()) {
            fail("an 'unavailable' refusal arrives with no statement of what it does and does "
                    + "not establish:\n" + String.join("\n", silent));
        }
    }

    private static void setAdminHttpRequest(Object controller) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        org.apache.chemistry.opencmis.commons.server.CallContext ctx =
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class);
        when(ctx.get(jp.aegif.nemaki.util.constant.CallContextKey.IS_ADMIN)).thenReturn(true);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        for (Method method : controller.getClass().getMethods()) {
            if (method.getName().equals("setHttpRequest") && method.getParameterCount() == 1) {
                method.invoke(controller, request);
                return;
            }
        }
        throw new jp.aegif.nemaki.util.test.HarnessBroken(
                "no setHttpRequest on " + controller.getClass()
                        + ", so nothing was checked");
    }

    private static void setHttpRequest(Object controller) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        // No CallContext attribute at all: the shape a request from a non-admin, or from
        // anything that never went through the filter, arrives in.
        when(request.getAttribute(anyString())).thenReturn(null);
        for (Method method : controller.getClass().getMethods()) {
            if (method.getName().equals("setHttpRequest")
                    && method.getParameterCount() == 1) {
                method.invoke(controller, request);
                return;
            }
        }
        throw new jp.aegif.nemaki.util.test.HarnessBroken("no setHttpRequest on "
                + controller.getClass()
                + ", so the admin gate could not be driven and nothing was checked");
    }

    private static List<Method> mappedEndpoints(Class<?> type) {
        List<Method> endpoints = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            for (Class<? extends java.lang.annotation.Annotation> mapping : List.of(
                    GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class,
                    PatchMapping.class, RequestMapping.class)) {
                if (method.getAnnotation(mapping) != null) {
                    endpoints.add(method);
                    break;
                }
            }
        }
        return endpoints;
    }

    private static Object[] argumentsFor(Method method) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class) {
                args[i] = "bedroom";
            } else if (types[i] == int.class) {
                args[i] = 1;
            } else if (types[i] == long.class) {
                args[i] = 1L;
            } else if (types[i] == boolean.class) {
                args[i] = false;
            }
        }
        return args;
    }
}
