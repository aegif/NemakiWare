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
package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two protections answer 503 here. Each is measured on its own (R38, plan A-5).
 *
 * <h2>Why the old control retired</h2>
 *
 * <p>The webhook GET handshake catches "the connector index could not be read" itself AND the
 * class carries an {@code @ExceptionHandler} for the same type. Break either one and the answer
 * is still 503 — so a single-anchor sabotage left every lock green, and the control was retired
 * as unfirable rather than fixed (R38). "Retired because it could not fire" is not the same as
 * "the protection is doubled"; nobody had shown the second one does anything.
 *
 * <h2>What tells them apart</h2>
 *
 * <p>The two answers are not identical. The arm answers with the receiver's own wording; the
 * class handler answers {@code {"error":"temporarily unavailable"}}, deliberately saying nothing
 * about the row because this class has an unauthenticated front door. So the BODY says which
 * protection replied — and the endpoints only the handler can serve say whether it replies at
 * all. One lock each.
 */
class TheDoubleRefusalIsActuallyDoubleTest {

    private static IngestWebhookController controllerWhoseIndexIsDown() throws Exception {
        IngestWebhookController controller = new IngestWebhookController();
        ConnectorDefinitionService connectors = mock(ConnectorDefinitionService.class);
        when(connectors.resolveOrRefuse(anyString())).thenThrow(
                new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                        "the connector index is rebuilding"));
        Field field = IngestWebhookController.class.getDeclaredField("connectorDefinitionService");
        field.setAccessible(true);
        field.set(controller, connectors);
        return controller;
    }

    @Test
    @DisplayName("the handshake answers with ITS OWN refusal, not the class handler's")
    void theArmAnswersTheHandshake() throws Exception {
        // Protection one. If this arm goes, the class handler still answers 503 — and the only
        // way to see the difference is the body. Asserting the status alone is what made the
        // old control unfirable.
        IngestWebhookController controller = controllerWhoseIndexIsDown();
        // assertDoesNotThrow, because the arm's job is to ANSWER. Letting the refusal out
        // leaves the reply to Spring's dispatcher and the class handler — which works, and is
        // a different protection with a different body. A bare call would fail here with an
        // exception rather than on this test's assertion, and the runner counts that as
        // breaking the harness rather than as the lock firing (measured).
        ResponseEntity<?> answer = (ResponseEntity<?>) org.junit.jupiter.api.Assertions
                .assertDoesNotThrow(() -> controller.verifyWebhook("c-1", "challenge-value"),
                        "the handshake let the refusal out instead of answering it");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, answer.getStatusCode());
        Object body = answer.getBody();
        assertTrue(body instanceof Map, String.valueOf(body));
        assertEquals("Connector could not be read; retry shortly", ((Map<?, ?>) body).get("error"),
                "the handshake's own refusal is gone and the class handler answered for it — "
                        + "same status, different sentence, and nothing measured the difference "
                        + "(R38)");
    }

    @Test
    @DisplayName("the class handler answers the endpoints that have no arm of their own")
    void theHandlerAnswersTheRest() throws Exception {
        // Protection two, measured where it is the ONLY one: the handler is what stands between
        // a typed "could not read" and a Spring 500 on every verb in this class that does not
        // catch it itself. Called directly because that is how Spring reaches it.
        ResponseEntity<?> answer = (ResponseEntity<?>) new IngestWebhookController()
                .definitionRowsCouldNotBeRead(
                        new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                                "the connector index is rebuilding"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, answer.getStatusCode(),
                "a typed 'could not be read' reaches Spring as a 500 — our bug, for a condition "
                        + "whose whole point is that a retry fixes it");
        assertEquals("temporarily unavailable", ((Map<?, ?>) answer.getBody()).get("error"),
                "the class handler echoed the reason. This class has an unauthenticated front "
                        + "door whose refusals must not say whether a row exists at the id");
    }

    @Test
    @DisplayName("the handler covers every typed refusal the definitions raise")
    void theHandlerCoversTheTypes() throws Exception {
        // The handler is a LIST of types, and a type added to the services later is not added
        // here by anything. Measured by asking the annotation, not by reading it.
        org.springframework.web.bind.annotation.ExceptionHandler handler =
                IngestWebhookController.class
                        .getMethod("definitionRowsCouldNotBeRead", RuntimeException.class)
                        .getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class);

        java.util.List<Class<? extends Throwable>> covered = java.util.List.of(handler.value());

        assertTrue(covered.contains(
                        ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException.class),
                "the connector refusal is not on the handler: " + covered);
        assertTrue(covered.contains(
                        ImportProfileDefinitionServiceImpl.ProfileIndexNotReadyException.class),
                "the profile refusal is not on the handler: " + covered);
        assertTrue(covered.contains(jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                        .SettingUnreadableException.class),
                "the settings refusal is not on the handler: " + covered);
    }
}
