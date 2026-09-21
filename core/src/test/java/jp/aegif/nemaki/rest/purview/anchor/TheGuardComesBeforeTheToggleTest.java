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
package jp.aegif.nemaki.rest.purview.anchor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard comes before the toggle (R65), held as a lock rather than as a sequence someone
 * remembers.
 *
 * <p>The CRL fetch takes a URL out of the TSA's certificate — input from a party this node did
 * not choose — and for a while it did so with a bare {@code HttpClient} while every other
 * outbound call in the product went through the send-time-pinned path. Nothing was live only
 * because the toggle had no caller. Wiring the toggle first would have shipped that fetch;
 * so the two are held together: the toggle may be wired only while the fetch is on the pinned
 * path, and a fetch on the pinned path with no toggle is the earlier state, also allowed.
 * Toggle without guard is the one combination refused.
 *
 * <p>These read SOURCE, which is a structural claim: the body of one method names the pinned
 * entry point and names no bare one. The behavioural half — that a loopback distribution
 * point is refused with the escape off, that a stalled body and a 503 come back as
 * UNAVAILABLE in bounded time — is in {@code Rfc3161AnchorTargetTest}. Neither alone is the
 * lock: the source reading cannot tell a dead pinned call from a live one, and the behaviour
 * cannot tell a one-shot check from the pin (both refuse loopback). Together they can.
 */
class TheGuardComesBeforeTheToggleTest {

    private static final Path TARGET = Path.of(
            "src/main/java/jp/aegif/nemaki/rest/purview/anchor/Rfc3161AnchorTarget.java");
    private static final Path WIRING = Path.of(
            "src/main/java/jp/aegif/nemaki/rest/purview/anchor/AnchorWiringConfig.java");
    private static final Path RUNBOOK = Path.of("../docs/operations/v3.4.0-upgrade-runbook.md");

    /** The entry points that pin at send time. Any of them is the guard; nothing else is. */
    private static final List<String> PINNED = List.of(
            "AdapterHttpClient.sendPinned(",
            "AdapterHttpClient.sendWithRetry(",
            "AdapterHttpClient.sendWithRedirectValidation(");

    /**
     * Ways of sending that do not pin. The first version listed two; the reviews listed the
     * rest (`newHttpClient()`, `sendAsync`, and the {@code HttpURLConnection} that the same
     * class's {@code post()} uses — the most natural "make it consistent" edit).
     */
    private static final List<String> BARE = List.of(
            "HttpClient.newHttpClient(",
            "HttpClient.newBuilder(",
            ".send(",
            ".sendAsync(",
            "HttpURLConnection",
            "openConnection(",
            "openStream(",
            ".execute(");

    private static final Pattern COMMENTS_AND_LITERALS = Pattern.compile(
            "(\"(?:\\\\.|[^\"\\\\])*\")|('(?:\\\\.|[^'\\\\])')|(//[^\\n]*)|(/\\*.*?\\*/)",
            Pattern.DOTALL);

    /** Source with comments removed and string literals kept intact. */
    private static String code(Path file) throws IOException {
        assertTrue(Files.exists(file), "this lock reads " + file + ", which is not there");
        return COMMENTS_AND_LITERALS.matcher(Files.readString(file, StandardCharsets.UTF_8))
                .replaceAll(m -> m.group(1) != null ? Matcher.quoteReplacement(m.group(1))
                        : m.group(2) != null ? Matcher.quoteReplacement(m.group(2)) : "");
    }

    /**
     * Source with comments removed AND literals blanked, for brace matching. A {@code "}"}
     * inside a message, or a {@code '"'} char literal, would otherwise end or extend the body
     * (Codex P2, subagent P3-5).
     */
    private static String structure(Path file) throws IOException {
        assertTrue(Files.exists(file), "this lock reads " + file + ", which is not there");
        return COMMENTS_AND_LITERALS.matcher(Files.readString(file, StandardCharsets.UTF_8))
                .replaceAll(m -> m.group(1) != null ? "\"\"" : m.group(2) != null ? "' '" : "");
    }

    /** The body of collectRevocationMaterial, by brace matching from its declaration. */
    private static String collectBody() throws IOException {
        String text = structure(TARGET);
        // The DECLARATION. indexOf of the bare name found the call site in anchor() first and
        // brace-matched an unrelated block, which had neither a pinned nor a bare send — so
        // the lock reported "toggle wired ahead of the guard" on a tree where the guard was
        // there (measured on first run).
        int start = text.indexOf("RevocationMaterial collectRevocationMaterial(");
        assertTrue(start >= 0, "Rfc3161AnchorTarget no longer declares collectRevocationMaterial");
        int open = text.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                depth++;
            } else if (text.charAt(i) == '}' && --depth == 0) {
                return text.substring(open, i + 1);
            }
        }
        throw new AssertionError("collectRevocationMaterial's body does not close");
    }

    private static String o52() throws IOException {
        assertTrue(Files.exists(RUNBOOK), "the runbook is not at " + RUNBOOK);
        String text = Files.readString(RUNBOOK, StandardCharsets.UTF_8);
        int start = text.indexOf("### O5-2.");
        assertTrue(start >= 0, "the runbook no longer has §O5-2 on revocation collection");
        int end = text.indexOf("### O5-3.", start);
        assertTrue(end > start, "§O5-2 does not end where this lock looks");
        return text.substring(start, end);
    }

    @Test
    @DisplayName("the toggle is wired only while the fetch rides the send-time-pinned path")
    void theToggleIsNeverWiredAheadOfTheGuard() throws IOException {
        boolean toggleWired = code(WIRING).contains("setCollectRevocationAtIssuance(");
        String body = collectBody();
        boolean fetchPinned = PINNED.stream().anyMatch(body::contains);
        List<String> bare = BARE.stream().filter(body::contains).toList();

        assertTrue(bare.isEmpty(),
                "collectRevocationMaterial sends through " + bare + ". That path resolves the "
                        + "TSA-supplied host once, or not at all, and is the SSRF the guard "
                        + "exists to close. A one-shot validateExternalUrl before it is not "
                        + "the pin either: it leaves the resolve-then-connect gap");
        if (toggleWired) {
            assertTrue(fetchPinned,
                    "AnchorWiringConfig wires the revocation toggle while the fetch is not on "
                            + "any of " + PINNED + ". Toggle before guard is the one order R65 "
                            + "forbids: the moment an operator sets the key, this node connects "
                            + "to whatever the TSA certificate names, unchecked. A fetch moved "
                            + "out of this method into a helper trips this arm too — the lock "
                            + "reads this body and would not see the helper");
        }
    }

    /**
     * The HTTP pin sets {@code Host}, which the JDK allows only under
     * {@code jdk.httpclient.allowRestrictedHeaders=host}, and only if the flag is on the JVM
     * command line (the JDK computes its restricted set once, at the first HttpClient). The
     * runbook says Docker deployments carry it; this reads the files the runbook is about.
     * A compose that overrides {@code JAVA_OPTS} drops the Dockerfile's copy, so it has to
     * carry its own (subagent, third review: four of them do, and nothing read them).
     */
    @Test
    @DisplayName("every shipped configuration that starts core carries the JVM flag the pin needs")
    void theJvmFlagIsInEveryShippedConfiguration() throws IOException {
        String flag = "allowRestrictedHeaders=host";
        for (Path dockerfile : List.of(Path.of("../docker/core/Dockerfile"),
                Path.of("../docker/core/Dockerfile.simple"))) {
            assertTrue(Files.exists(dockerfile), dockerfile + " is not there");
            assertTrue(Files.readString(dockerfile, StandardCharsets.UTF_8).contains(flag),
                    dockerfile + " no longer sets " + flag + ". Every http:// pin on that image "
                            + "then fails with 'restricted header name: Host'");
        }
        List<Path> composes;
        try (var files = Files.list(Path.of("../docker"))) {
            composes = files.filter(f -> f.getFileName().toString().startsWith("docker-compose")
                    && f.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
        assertTrue(composes.size() >= 4, "fixture: only " + composes.size() + " compose files found");
        int checked = 0;
        for (Path compose : composes) {
            String text = Files.readString(compose, StandardCharsets.UTF_8);
            boolean startsCore = Pattern.compile("(?m)^\\s+core:\\s*$").matcher(text).find();
            boolean overridesJavaOpts = text.contains("JAVA_OPTS=");
            if (!startsCore || !overridesJavaOpts) {
                continue; // inherits the Dockerfile's ENV, which the loop above holds
            }
            checked++;
            assertTrue(text.contains(flag),
                    compose + " overrides JAVA_OPTS (dropping the Dockerfile's flag) and does "
                            + "not set " + flag + " itself. The runbook says Docker deployments "
                            + "carry it; this one would answer UNAVAILABLE on every http:// "
                            + "distribution point");
        }
        assertTrue(checked >= 1, "fixture: no compose overrides JAVA_OPTS for core, so this "
                + "lock measured nothing");
    }

    @Test
    @DisplayName("the key defaults to off, in the wiring itself")
    void theKeyDefaultsToOff() throws IOException {
        // Read from the @Value, not from a test double: the default an operator gets is the
        // one written in the placeholder.
        assertTrue(code(WIRING).contains("anchor.rfc3161.revocation.collect-at-issuance:false"),
                "the revocation toggle's @Value no longer defaults to false. Collection reaches "
                        + "an outside endpoint and is the operator's decision to take, not "
                        + "one taken for them by a default");
    }

    /**
     * Bounded in bytes AND in time, and both bounds are the ones the runbook states.
     *
     * <p>The first version compared the constant with a literal and nothing else: the read
     * could have gone back to {@code readAllBytes()} with every lock green (the 8 MiB + 1
     * fixture is refused AFTER being read whole), and the runbook's "8 MiB" was unlocked
     * (subagent review, P2-2 / P3-3). Now the body has to name the bounded read and the
     * watchdog, and the runbook's numbers are read from the runbook.
     */
    @Test
    @DisplayName("the fetch is bounded in bytes and time, and the bounds are the ones the runbook states")
    void theFetchIsBounded() throws IOException {
        assertEquals(8L * 1024 * 1024, Rfc3161AnchorTarget.MAX_CRL_BYTES,
                "the CRL cap moved. A cap the runbook does not state is a limit an operator "
                        + "meets in production");
        assertEquals(20, Rfc3161AnchorTarget.CRL_BODY_BUDGET.getSeconds(),
                "the body budget moved. Same reason");

        String body = collectBody();
        assertTrue(body.contains("readNBytes((int) MAX_CRL_BYTES + 1)"),
                "collectRevocationMaterial no longer reads at most MAX_CRL_BYTES + 1 bytes. "
                        + "The cap check after the read refuses an oversized CRL, but only a "
                        + "bounded read keeps a 2 GB one out of the heap first");
        assertTrue(Pattern.compile("new (jp\\.aegif\\.nemaki\\.rest\\.ingest\\.)?BodyBudget\\(")
                        .matcher(body).find(),
                "collectRevocationMaterial reads the body with no BodyBudget on it. The request "
                        + "timeout ends at the headers; a distribution point that sends one "
                        + "byte and stops then parks the anchoring, and the admin request "
                        + "waiting on it, until the JVM is killed");

        // The BULLETS that state each bound, not the bare number: "8 MiB" also appears in
        // the UNAVAILABLE row, so a bullet changed to 16 MiB would leave a bare needle true.
        String runbook = o52();
        String cap = "**上限 " + (Rfc3161AnchorTarget.MAX_CRL_BYTES / (1024 * 1024)) + " MiB。**";
        String budget = "**本文の上限 " + Rfc3161AnchorTarget.CRL_BODY_BUDGET.getSeconds() + " 秒。**";
        assertTrue(runbook.contains(cap),
                "§O5-2 no longer states the cap as 「" + cap + "」, which is what the code "
                        + "enforces. An operator reads the runbook's number, not the constant");
        assertTrue(runbook.contains(budget),
                "§O5-2 no longer states the body budget as 「" + budget + "」, which is what "
                        + "the code enforces");
    }
}
