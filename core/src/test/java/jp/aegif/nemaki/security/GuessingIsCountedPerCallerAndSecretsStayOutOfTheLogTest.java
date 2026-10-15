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
package jp.aegif.nemaki.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Password guessing is counted per caller, and the secrets the containers are started with do not reach the
 * log (3.4.1, external review S5, S6 and S8).
 *
 * <p>Three facts that live in different files and only make sense together:
 *
 * <ul>
 * <li>The REST login hands the servlet request to the authentication service, because the brute-force
 *     throttle keys on the client address and reads it from there. Without it the key was
 *     {@code "<repo>:<user>:unknown"} for every caller, so failures from anyone — from anywhere — added up
 *     against one name and locked that account out for everyone.</li>
 * <li>The MCP password login goes through a throttle at all. It went through none, so guessing over MCP was
 *     unlimited while the same guesses over REST or CMIS were locked out.</li>
 * <li>Tomcat's version listener does not log the JVM arguments, because the compose files pass the CouchDB
 *     and LDAP passwords as {@code -D} arguments and the listener's default is to print every one.</li>
 * </ul>
 *
 * <p>These are read from the sources and the shipped configuration rather than driven, because the throttle's
 * own behaviour is covered by {@link LoginThrottle}'s tests and what went wrong here was the wiring: a key
 * that could not tell callers apart, a path that never asked, and a listener left at its default.
 */
class GuessingIsCountedPerCallerAndSecretsStayOutOfTheLogTest {

    private static final Path REST_LOGIN =
            Path.of("src/main/java/jp/aegif/nemaki/rest/AuthTokenResource.java");
    private static final Path CMIS_AUTH =
            Path.of("src/main/java/jp/aegif/nemaki/cmis/factory/auth/impl/AuthenticationServiceImpl.java");
    private static final Path MCP_AUTH =
            Path.of("src/main/java/jp/aegif/nemaki/mcp/McpAuthenticationHandler.java");
    private static final Path SERVER_XML = Path.of("../docker/core/server.xml");
    private static final Path COMPOSE_DIR = Path.of("../docker");

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "this lock looks for " + path + " and it is not there");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the REST login puts the request in the call context, so the throttle can tell callers apart (S5)")
    void theRestLoginGivesTheThrottleSomethingToKeyOn() throws IOException {
        String login = read(REST_LOGIN);

        assertTrue(login.contains("\"httpServletRequest\", request)"),
                "the REST login does not put the servlet request in the call context, so the throttle keys "
                        + "every caller as \"unknown\" and failures against one name add up across the internet");
    }

    @Test
    @DisplayName("the throttle resolves the client address through the shared proxy-aware helper (S5)")
    void theThrottleResolvesTheAddressThroughTheSharedHelper() throws IOException {
        String auth = read(CMIS_AUTH);
        int resolver = auth.indexOf("private String resolveClientIpForThrottle");
        assertTrue(resolver > 0, "resolveClientIpForThrottle is gone — the throttle key no longer has an address");
        String body = auth.substring(resolver, auth.indexOf("\n\t}", resolver));

        assertTrue(body.contains("TrustedProxyResolver.resolveClientIp"),
                "the throttle reads the address without the proxy-aware helper, so behind a reverse proxy "
                        + "every caller is the proxy and there is one key for everyone: " + body);
    }

    @Test
    @DisplayName("the MCP password login is counted by a throttle (S6)")
    void theMcpPasswordLoginIsThrottled() throws IOException {
        String mcp = read(MCP_AUTH);
        int validate = mcp.indexOf("private McpAuthResult validateCredentials");
        assertTrue(validate > 0, "validateCredentials is gone — this lock no longer reads the MCP password login");
        String body = mcp.substring(validate, mcp.indexOf("\n    }", validate));

        assertTrue(body.contains("loginThrottle.isBlocked("),
                "the MCP password login does not ask the throttle, so guessing over MCP is unlimited: " + body);
        assertTrue(body.contains("loginThrottle.recordFailure("),
                "the MCP password login never records a failure, so the throttle it asks can never block");
    }

    @Test
    @DisplayName("Tomcat does not print the JVM arguments, which carry the CouchDB and LDAP passwords (S8)")
    void tomcatDoesNotPrintTheJvmArguments() throws IOException {
        String serverXml = read(SERVER_XML);
        Matcher listener = Pattern
                .compile("<Listener[^>]*VersionLoggerListener[^>]*>")
                .matcher(serverXml);
        assertTrue(listener.find(), "the version listener is not in the shipped server.xml");

        assertTrue(listener.group().contains("logArgs=\"false\""),
                "the version listener logs the JVM arguments, and those carry the passwords the compose "
                        + "files pass with -D: " + listener.group());
    }

    @Test
    @DisplayName("and the two facts stay tied: a compose file that passes a password with -D needs that listener silenced (S8)")
    void aPasswordPassedOnTheCommandLineKeepsTheListenerSilenced() throws IOException {
        List<String> passing = new ArrayList<>();
        try (var files = Files.list(COMPOSE_DIR)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".yml")).toList()) {
                String yaml = Files.readString(file, StandardCharsets.UTF_8);
                if (Pattern.compile("-D[\\w.]*password=", Pattern.CASE_INSENSITIVE).matcher(yaml).find()) {
                    passing.add(file.getFileName().toString());
                }
            }
        }

        // Not "there must be none": moving the secrets off the command line is a separate change. What must
        // hold is that while any compose file does this, the listener that would print them stays silenced.
        if (!passing.isEmpty()) {
            String serverXml = read(SERVER_XML);
            Matcher listener = Pattern.compile("<Listener[^>]*VersionLoggerListener[^>]*>").matcher(serverXml);
            assertTrue(listener.find() && listener.group().contains("logArgs=\"false\""),
                    "these compose files pass a password as a JVM argument " + passing
                            + ", and the version listener is not silenced, so the passwords are printed at startup");
        }
        assertFalse(passing.isEmpty(),
                "no compose file passes a password with -D any more — this lock is measuring nothing and the "
                        + "residual about command-line secrets can be closed");
    }
}
