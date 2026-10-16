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

import jp.aegif.nemaki.util.test.HarnessBroken;
import jp.aegif.nemaki.util.test.JavaSource;
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

    /** What the reader asks for: {@code callContext.get("throttleRequest")}. */
    private static final String THROTTLE_KEY = "throttleRequest";

    private static final String THROTTLE_KEY_LITERAL = "\"throttleRequest\"";

    /**
     * The value of a {@code String} constant named in the production, or null when it cannot be read.
     *
     * <p>Null, not a guess: a key this lock cannot resolve is "could not ask", and the assertion that
     * calls this says so. One case does NOT come back as null — a source file the reader cannot
     * understand throws {@code HarnessBroken}, which fails the test and is deliberately not an
     * {@code AssertionError} so that the control runner does not score it as a firing
     * (round-13 review, P3).
     *
     * <p>Read from CODE, not from the file's text (round-12 review, P2): the first form of this
     * scanned raw sources, so a javadoc example writing the same constant with the right value would
     * have answered for a production that used the wrong one.
     *
     * <p>A qualified name ({@code Keys.REQUEST}) is read ONLY from the type it names: falling back to
     * the door's own file would let a same-named constant there answer for the one the put uses
     * (round-13 review, P2). An unqualified name is read from the door's file, then from the module
     * if there is exactly one declaration.
     */
    private static String constantValue(String name, Path door) {
        if (name == null || name.startsWith("\"")) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        String simple = name.substring(dot + 1);
        String qualifier = dot < 0 ? null : name.substring(0, dot);
        Pattern declaration = Pattern.compile(
                "String\\s+" + Pattern.quote(simple) + "\\s*=\\s*\"([^\"]*)\"");
        List<String> inTheDoor = new ArrayList<>();
        List<String> inTheQualifier = new ArrayList<>();
        List<String> anywhere = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                String code = JavaSource.withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher found = declaration.matcher(code);
                while (found.find()) {
                    anywhere.add(found.group(1));
                    if (file.equals(door)) {
                        inTheDoor.add(found.group(1));
                    }
                    if (qualifier != null
                            && file.getFileName().toString().equals(qualifier + ".java")) {
                        inTheQualifier.add(found.group(1));
                    }
                }
            }
        } catch (IOException unreadable) {
            // Not null: "a file could not be read" is the harness failing, not an answer about the
            // key, and folding the two into one value is the defect this branch is named after
            // (round-14 review, P2).
            throw new HarnessBroken("could not read the sources while resolving the constant "
                    + name, unreadable);
        }
        // The name decides where to look FIRST. A qualified name (Keys.REQUEST) means the type it
        // names, and reading the door's own file before it is a fail-open: a same-named constant in
        // another method of the door would answer for the one the put actually uses
        // (round-13 review, P2). An unqualified name means this file, then the module — taking
        // "exactly one in the module" as the only rule turned this lock red for a constant whose
        // simple name is used elsewhere, and core has 123 String declarations, many of them plain
        // names like KEY (round-12 review, P2).
        if (qualifier != null) {
            // Exactly one literal declaration in the type the name qualifies, or this cannot say
            // which one the door means. Counting the classes in that file was an approximation and
            // the round-15 review found valid Java it refused (a base class and the public class in
            // one file, an interface, an enum); the approximation is gone, and what is left is the
            // honest limit: a declaration this cannot attribute makes the caller fail with a
            // message asking for the lock to be pointed at it.
            return inTheQualifier.size() == 1 ? inTheQualifier.get(0) : null;
        }
        // Null entries only come from a file this cannot read class-wise; they poison the count on
        // purpose, so the answer above is null rather than a guess.
        if (inTheDoor.size() == 1) {
            return inTheDoor.get(0);
        }
        return anywhere.size() == 1 ? anywhere.get(0) : null;
    }

    /**
     * The body of the method in {@code door} that calls {@code login(…)} on the authentication
     * service — the method whose context actually reaches the throttle. A {@code put} anywhere else
     * in the file (a helper nothing calls, a comment) does not wire anything (round-7 review).
     */
    private String methodThatLogsIn(Path door) throws IOException {
        String body = read(door);
        // Any identifier for the context, not the two names in the tree today: renaming a local
        // must not turn this lock red (round-10 review, P3).
        Matcher login = Pattern.compile("\\w+\\.login\\(\\s*\\w+\\s*\\)").matcher(body);
        assertTrue(login.find(), door + " no longer calls login() with a context it built, so this"
                + " lock cannot tell which method the throttle's address has to be put in");
        Matcher signature = Pattern
                // The signature may span lines — AuthTokenResource's login() does, and requiring
                // the brace on the signature's own line made the span start at the PREVIOUS
                // method (register), so a `put` moved there satisfied this lock (round-8
                // review, P2). Allow newlines inside the parameter list.
                // Tabs OR spaces: the api/v1 filter is indented with four spaces, and a tab-only
                // pattern made the span start at the CLASS declaration there (round-9 review, P2).
                .compile("\n[ \\t]*(?:public|private|protected)[^;{]*?\\{", Pattern.DOTALL)
                .matcher(body.substring(0, login.start()));
        int start = 0;
        while (signature.find()) {
            start = signature.start();
        }
        return body.substring(start, login.end());
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
            // In the SAME METHOD that calls login(), with a request as its value — not the string
            // anywhere in the file. One of these doors mentions the key in a comment, and
            // `put("throttleRequest", null)` would read the same to a contains() check (round-6
            // review); a `put` left in an unused helper would read the same to a whole-file regex
            // (round-7 review, P3).
            // The literal, or a constant whose VALUE is that literal. Accepting any constant whose
            // NAME looks throttle-ish was the round-10 answer to a rename, and both reviewers found
            // the hole in round 11: the reader asks for "throttleRequest" by literal, so a constant
            // spelled THROTTLE_REQUEST_KEY = "throttle_request" left this lock green while the
            // resolver found nothing and keyed every caller as "unknown" again — the S5 defect
            // itself. The guard belongs on the side that does the reading
            // ([[loosening-one-side-of-a-pair-opens-the-other]]).
            Matcher put = Pattern
                    .compile("put\\(\\s*(\"?[A-Za-z_][A-Za-z0-9_.]*\"?)\\s*,"
                            + "\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)")
                    .matcher(methodThatLogsIn(door));
            String keyedOn = null;
            String valuePut = null;
            List<String> keysTried = new ArrayList<>();
            while (put.find()) {
                String key = put.group(1);
                String resolved = THROTTLE_KEY_LITERAL.equals(key) ? THROTTLE_KEY
                        : constantValue(key, door);
                keysTried.add(key + " -> " + (resolved == null ? "could not resolve" : resolved));
                if (THROTTLE_KEY.equals(resolved)) {
                    keyedOn = key;
                    valuePut = put.group(2);
                    break;
                }
            }
            // Both cases named, because they are different defects and this lock cannot tell them
            // apart: a key that is something else is the S5 defect itself, while a constant whose
            // declaration this lock cannot find is "could not ask" and needs the lock pointed at it
            // ([[could-not-ask-vs-answered-no]], round-12 review, P2).
            assertTrue(keyedOn != null,
                    door + " puts nothing in the call context under " + THROTTLE_KEY_LITERAL
                            + ". Either the key is something else — and then the throttle keys every"
                            + " caller as \"unknown\" and failures against one name add up across the"
                            + " internet — or a constant's declaration could not be read here and"
                            + " this lock has to be pointed at it. What it found: " + keysTried);
            // What this does NOT prove (round-7 review, P3): that the statement RUNS (it is read from
            // the source, not driven), and that the identifier is the servlet request rather than
            // something else named "request". The behaviour of the throttle itself is measured in
            // LoginThrottle's tests and over MCP in TheMcpLoginToolGoesThroughTheThrottledDoorTest.
            assertTrue(valuePut.toLowerCase(java.util.Locale.ROOT).contains("request"),
                    door + " puts something that is not the servlet request under "
                            + THROTTLE_KEY_LITERAL + ": " + valuePut + " — the resolver reads it as a"
                            + " request and falls back to \"unknown\" when it is not one");
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
        // Recursive, both extensions, and the whole file rather than one line: a compose in a
        // subdirectory, a .yaml, or a multi-line YAML value would otherwise escape the freeze
        // (round-6 review, P3). The whole-file form needs the password to be inside a
        // JAVA_TOOL_OPTIONS value, so the test looks at each such value's own text.
        List<String> viaToolOptions = new ArrayList<>();
        try (var files = Files.walk(COMPOSE_DIR)) {
            for (Path file : files.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".yml") || name.endsWith(".yaml");
            }).toList()) {
                // YAML's own rule — the block is every FOLLOWING line indented deeper than the key —
                // instead of guessing what a continuation line starts with. The guess excluded "-"
                // (meaning list items) and so excluded "-D…password="; allowing only "-D" then
                // stopped at "-Xmx512m" (both measured; round-7 and round-8 reviews).
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    // An ASSIGNMENT of the variable, not a mention: what puts the password in the
                    // JVM's startup echo is the value, and a line that merely names the variable
                    // carries none. In today's tree both spellings give the same set — the two files
                    // that mention it in a comment are the two that assign it, and the mention's own
                    // block is empty, so a bare contains() adds nothing (measured, round-10 review:
                    // the earlier note here claimed a false third compose, which does not happen).
                    // The regex is so that this set does not depend on that coincidence.
                    String code = lines.get(i).contains("#")
                            ? lines.get(i).substring(0, lines.get(i).indexOf('#'))
                            : lines.get(i);
                    if (!Pattern.compile("JAVA_TOOL_OPTIONS\\s*[=:]").matcher(code).find()) {
                        continue;
                    }
                    int indent = lines.get(i).indexOf(lines.get(i).trim());
                    StringBuilder block = new StringBuilder(lines.get(i));
                    for (int j = i + 1; j < lines.size(); j++) {
                        String next = lines.get(j);
                        if (next.isBlank()) {
                            continue;
                        }
                        if (next.indexOf(next.trim()) <= indent) {
                            break;
                        }
                        block.append('\n').append(next);
                    }
                    if (Pattern.compile("-D[\\w.]*password=", Pattern.CASE_INSENSITIVE)
                            .matcher(block.toString()).find()) {
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
        // Recursive and both extensions, like its sibling below: this one kept the non-recursive
        // Files.list + ".yml" form after that was fixed 30 lines down, which is the same reader with
        // the same blind spot (round-7 review, P3).
        List<String> passing = new ArrayList<>();
        try (var files = Files.walk(COMPOSE_DIR)) {
            for (Path file : files.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".yml") || name.endsWith(".yaml");
            }).toList()) {
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
