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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a 5xx from {@code /core/api/v1/**} tells the client, and what it does not.
 *
 * <p>The handler used to copy {@code ex.getMessage()} into the {@code error} field of every 500
 * under {@code rest.controller}. That is the HANDLER path. CodeQL java/error-message-exposure
 * #1410 itself points at a different path: AuthenticityReportController.reportHtml (line 111)
 * rendering the assembler's UNAVAILABLE reasons, which carried the store exception's message —
 * fixed in AuthenticityReportAssembler and locked by AuthenticityReportDoesNotLeakExceptionsTest.
 * Both paths follow the same rule: an unexpected exception's message is whatever the failing
 * layer put there (a JDBC URL, a file path, a class name), so the client gets a fixed text and
 * an incident id, and the message goes to the log under that id (owner decision, 2026-09-28).
 *
 * <p>Both directions, because over-suppressing is the other defect this branch is about: a 400
 * for an IllegalArgumentException is the product answering "your request is wrong, here is
 * why", and THAT message must keep reaching the client.
 */
class GlobalExceptionHandlerTest {

    private static final String INTERNAL = "jdbc://db.internal:5984/nemaki_conf rev 3-abc";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a 500 for an unexpected exception does not carry the exception's message")
    void anUnexpectedExceptionsMessageStaysOutOfThe500() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleGeneralException(new Exception(INTERNAL), null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("error", body.get("status"), "the client must still be told this failed");
        assertFalse(String.valueOf(body).contains(INTERNAL),
                "the exception's own text reached the client: " + body);
        assertTrue(String.valueOf(body.get("incidentId")).length() >= 8,
                "without an incident id the operator cannot find the logged message: " + body);
    }

    @Test
    @DisplayName("a 500 for a runtime exception does not carry the exception's message either")
    void aRuntimeExceptionsMessageStaysOutOfThe500() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleRuntimeException(new IllegalStateException(INTERNAL), null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("error", body.get("status"));
        assertFalse(String.valueOf(body).contains(INTERNAL),
                "the exception's own text reached the client: " + body);
        assertTrue(String.valueOf(body.get("incidentId")).length() >= 8,
                "without an incident id the operator cannot find the logged message: " + body);
    }

    @Test
    @DisplayName("a 400 for a bad argument still says what was wrong with the request")
    void aBadArgumentsMessageStillReachesTheClient() {
        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("repositoryId must not be blank"), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("repositoryId must not be blank", body.get("error"),
                "the validation message is the product's own answer and must keep reaching the "
                        + "client — suppressing it would be the over-refusal side of #1410");
    }
}
