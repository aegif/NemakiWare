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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final Path REST_FILTER =
            Path.of("src/main/java/jp/aegif/nemaki/rest/AuthenticationFilter.java");
    private static final Path API_FILTER =
            Path.of("src/main/java/jp/aegif/nemaki/api/v1/filter/ApiAuthenticationFilter.java");
    private static final Path MAIN = Path.of("src/main/java");
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
    @DisplayName("every door that reaches the Basic-auth throttle puts the request in the call context (S5)")
    void everyDoorGivesTheThrottleSomethingToKeyOn() throws IOException {
        // Three of the four callers of AuthenticationService.login build the context themselves. The
        // first round of the S5 fix covered the authtoken door only, and the two filters — which
        // between them carry /rest/**, /api/** and /odata/** — kept keying every caller as "unknown",
        // so 15 failures against a name from anywhere locked it out for everyone (round-5 review).
        // The fourth caller is the CMIS binding, whose context map holds strings only; it supplies
        // "remoteAddress" instead and is measured by the resolver lock below.
        for (Path door : List.of(REST_LOGIN, REST_FILTER, API_FILTER)) {
            assertTrue(read(door).contains("\"throttleRequest\""),
                    door + " does not put the servlet request in the call context, so the throttle keys"
                            + " every caller as \"unknown\" and failures against one name add up across"
                            + " the internet");
        }
    }

    @Test
    @DisplayName("and they use the dedicated key, which does not also switch proxy-header SSO on (S5)")
    void theKeyIsTheDedicatedOneAndNotTheSsoFallback() throws IOException {
        // loginWithExternalAuth falls back to "httpServletRequest" for its trusted-proxy check, so a
        // door that put the request THERE would also turn proxy-header authentication on for itself —
        // a widening with nothing to do with counting failures. Nothing may write that key.
        List<String> writers = new ArrayList<>();
        try (var files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                if (Pattern.compile("put\\(\\s*\"httpServletRequest\"").matcher(body).find()) {
                    writers.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(writers.isEmpty(),
                "these files put the servlet request under \"httpServletRequest\", which the SSO"
                        + " proxy-header check reads as well: " + writers);
    }

    @Test
    @DisplayName("the throttle resolves the client address through the shared proxy-aware helper (S5)")
    void theThrottleResolvesTheAddressThroughTheSharedHelper() throws IOException {
        String auth = read(CMIS_AUTH);
        int resolver = auth.indexOf("private String resolveClientIpForThrottle");
        assertTrue(resolver > 0, "resolveClientIpForThrottle is gone — the throttle key no longer has an address");
        String body = auth.substring(resolver, auth.indexOf("\n\t}", resolver));

        assertTrue(auth.contains("TrustedProxyResolver.resolveClientIp"),
                "the throttle reads the address without the proxy-aware helper, so behind a reverse proxy "
                        + "every caller is the proxy and there is one key for everyone");

        // Order matters: the request (proxy-aware) before the CMIS binding's raw peer address, which
        // is also the SSO proxy-header check's input and must stay the peer there.
        assertTrue(body.indexOf("throttleRequest") >= 0 && body.indexOf("remoteAddress") >= 0
                        && body.indexOf("throttleRequest") < body.indexOf("remoteAddress"),
                "the raw peer address is read before the proxy-aware request, so a door that supplies"
                        + " both would silently lose the resolution: " + body);
    }

    @Test
    @DisplayName("one construction of the key, so a door that counts cannot count against a different key (S6)")
    void theKeyIsBuiltInOnePlace() throws IOException {
        String auth = read(CMIS_AUTH);
        assertTrue(auth.contains("private String throttleKey(String repositoryId, String username, String address)"),
                "the single key construction is gone; each door building its own key is how the API v1"
                        + " door ended up not sharing the counter at all");
        Matcher uses = Pattern.compile("throttleKey\\(repositoryId, username,").matcher(auth);
        int count = 0;
        while (uses.find()) {
            count++;
        }
        assertEquals(4, count,
                "the doors that build a throttle key changed in number; each one must go through"
                        + " throttleKey(repositoryId, username, address) — Basic auth and the three"
                        + " methods the API v1 login calls");
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
        // EVERY server.xml in the image tree, not only the one the Dockerfiles copy today: the other
        // one was left at the default, so a build-context change would have restored the leak with
        // this lock green (round-5 review).
        List<Path> serverXmls = new ArrayList<>();
        try (var files = Files.walk(COMPOSE_DIR)) {
            serverXmls.addAll(files.filter(f -> f.getFileName().toString().equals("server.xml")).toList());
        }
        assertFalse(serverXmls.isEmpty(), "no server.xml under " + COMPOSE_DIR + " — this lock measures nothing");

        for (Path serverXml : serverXmls) {
            Matcher listener = Pattern
                    .compile("<Listener[^>]*VersionLoggerListener[^>]*>")
                    .matcher(read(serverXml));
            assertTrue(listener.find(), "the version listener is not in " + serverXml);
            assertTrue(listener.group().contains("logArgs=\"false\""),
                    serverXml + "'s version listener logs the JVM arguments, and those carry the"
                            + " passwords the compose files pass with -D: " + listener.group());
        }
    }

    @Test
    @DisplayName("and the listener cannot help the composes that pass a password in JAVA_TOOL_OPTIONS — that set is frozen (S8)")
    void theComposesThatUseJavaToolOptionsAreTheKnownTwo() throws IOException {
        // The JVM launcher prints "Picked up JAVA_TOOL_OPTIONS: <the whole value>" to its own stderr
        // before any Tomcat listener runs, so logArgs="false" does not cover it. Two evaluation
        // overlays pass an Atlas/Purview password that way; that is recorded as R180's remaining
        // shape, and this lock freezes the set so a THIRD one cannot join silently (round-5 review).
        List<String> viaToolOptions = new ArrayList<>();
        try (var files = Files.list(COMPOSE_DIR)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".yml")).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.contains("JAVA_TOOL_OPTIONS")
                            && Pattern.compile("-D[\\w.]*password=", Pattern.CASE_INSENSITIVE)
                                    .matcher(line).find()) {
                        viaToolOptions.add(file.getFileName().toString());
                        break;
                    }
                }
            }
        }
        java.util.Collections.sort(viaToolOptions);
        assertEquals(List.of("docker-compose-4b-dryrun.yml", "docker-compose-atlas.yml"), viaToolOptions,
                "the set of compose files whose password the JVM echoes at startup changed. Adding one"
                        + " puts a credential in the container log where no Tomcat setting can stop it;"
                        + " removing both closes R180's remaining shape and this list goes empty.");
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
