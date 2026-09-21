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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -Dnemaki.ingest.allowLocalhost=true} allows loopback. Nothing else.
 *
 * <p>Until the second review of R65 the property returned from
 * {@link AdapterHttpClient#pinRequestToValidatedAddress} and from
 * {@link AdapterHttpClient#validateExternalUrl} before any address was looked at — the whole
 * guard off, for every destination, under a name that said "localhost". A production JVM
 * started with it (it is an ordinary {@code -D}) could then be sent to RFC 1918 space or the
 * metadata endpoint by anything that chooses a URL, which for the CRL fetch is the TSA's
 * certificate (Codex P1, subagent P3-4, 2026-09-22). This lock holds the property to its name.
 */
class TheTestEscapeIsLoopbackOnlyTest {

    private static final String PROPERTY = "nemaki.ingest.allowLocalhost";

    private String before;

    @BeforeEach
    void rememberTheProperty() {
        before = System.getProperty(PROPERTY);
    }

    @AfterEach
    void putThePropertyBack() {
        if (before == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, before);
        }
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    @Test
    @DisplayName("with the escape on, private, link-local and metadata addresses are still refused — by the check and by the pin")
    void privateAddressesStayRefused() {
        System.setProperty(PROPERTY, "true");
        for (String url : new String[] {
                "http://10.0.0.1/x", "http://192.168.1.1/x", "http://172.16.0.1/x",
                "http://169.254.169.254/latest/meta-data/" }) {
            assertThrows(SecurityException.class, () -> AdapterHttpClient.validateExternalUrl(url),
                    url + " passed validateExternalUrl with the localhost escape on. The escape "
                            + "is for a stub on this machine; a JVM carrying it must not become "
                            + "a JVM that reaches the private network");
            assertThrows(SecurityException.class,
                    () -> AdapterHttpClient.pinRequestToValidatedAddress(get(url)),
                    url + " was pinned for sending with the localhost escape on. The send-time "
                            + "pin is the guard every outbound call relies on, and the property "
                            + "used to skip it whole");
        }
    }

    @Test
    @DisplayName("with the escape on, loopback is accepted AND still goes through the pin")
    void loopbackIsAcceptedThroughThePin() throws Exception {
        System.setProperty(PROPERTY, "true");
        assertDoesNotThrow(() -> AdapterHttpClient.validateExternalUrl("http://127.0.0.1:9/x"));

        // A loopback NAME, so the rewrite has something to do: the pinned request carries the
        // resolved literal in its URI and the original name in Host — the branch production
        // takes for every http:// destination, which no test used to send through because
        // the escape skipped it (subagent review, P2-4).
        HttpRequest pinned = AdapterHttpClient.pinRequestToValidatedAddress(get("http://localhost:9/x"));
        String host = pinned.uri().getHost();
        assertTrue(InetAddress.getByName(host).isLoopbackAddress(),
                "the pinned URI's host is " + host + ", which is not a loopback literal — the "
                        + "escape let the request through WITHOUT the pin, which is the old "
                        + "behaviour this lock exists to refuse");
        assertTrue(host.matches("[\\[\\]0-9a-fA-F.:]+"),
                "the pinned URI's host is " + host + ", a name rather than a literal; the pin "
                        + "rewrites to the address it validated so the connection cannot go "
                        + "to a rebound one");
        assertEquals("localhost:9", pinned.headers().firstValue("Host").orElse(null),
                "the pinned request does not carry the original name in Host, so a name-based "
                        + "virtual host would answer for the wrong site");
    }

    @Test
    @DisplayName("with the escape off, loopback is refused — the control")
    void loopbackIsRefusedWithTheEscapeOff() {
        System.clearProperty(PROPERTY);
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.validateExternalUrl("http://127.0.0.1:9/x"),
                "loopback passed with the escape off, so the other two tests are not measuring "
                        + "the escape");
        assertThrows(SecurityException.class,
                () -> AdapterHttpClient.pinRequestToValidatedAddress(get("http://127.0.0.1:9/x")));
    }
}
