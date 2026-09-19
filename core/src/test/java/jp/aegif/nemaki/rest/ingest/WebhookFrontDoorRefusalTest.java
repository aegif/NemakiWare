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

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unauthenticated webhook front door.
 *
 * <p>{@code verifyGraphClientState} answered TRUE for an EMPTY notification array: the loop
 * never ran and nothing was compared. The call site's own comment says the rate limiter sits
 * "AFTER signature verification to prevent unauthenticated exhaustion" — for Graph connectors
 * that was false, and a hundred posts of <code>{"value":[]}</code> locked out a minute of
 * genuine change notifications. A review found the pair.
 */
class WebhookFrontDoorRefusalTest {

    private boolean verify(String rawBody) throws Exception {
        IngestWebhookController controller = new IngestWebhookController();
        Method m = IngestWebhookController.class.getDeclaredMethod(
                "verifyGraphClientState", String.class, String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(controller, "the-secret", rawBody);
    }

    @Test
    @DisplayName("an empty notification array does not verify")
    void anEmptyNotificationArrayDoesNotVerify() throws Exception {
        assertFalse(verify("{\"value\":[]}"),
                "an empty array proves nothing, so accepting it lets an unauthenticated caller "
                        + "reach this connector's rate limiter");
    }

    @Test
    @DisplayName("a matching clientState still verifies")
    void aMatchingClientStateStillVerifies() throws Exception {
        // The other direction: refusing everything would stop genuine Graph deliveries.
        assertTrue(verify("{\"value\":[{\"clientState\":\"the-secret\"}]}"),
                "a genuine notification carrying the secret was refused");
    }

    @Test
    @DisplayName("a mismatched clientState does not verify")
    void aMismatchedClientStateDoesNotVerify() throws Exception {
        assertFalse(verify("{\"value\":[{\"clientState\":\"wrong\"}]}"),
                "a notification carrying the wrong secret was accepted");
    }
}
