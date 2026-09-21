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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 */
class TheGuardComesBeforeTheToggleTest {

    private static final Path TARGET = Path.of(
            "src/main/java/jp/aegif/nemaki/rest/purview/anchor/Rfc3161AnchorTarget.java");
    private static final Path WIRING = Path.of(
            "src/main/java/jp/aegif/nemaki/rest/purview/anchor/AnchorWiringConfig.java");

    /** Source with comments removed and string literals kept intact. */
    private static String code(Path file) throws IOException {
        assertTrue(Files.exists(file), "this lock reads " + file + ", which is not there");
        return Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\")|(//[^\\n]*)|(/\\*.*?\\*/)", Pattern.DOTALL)
                .matcher(Files.readString(file, StandardCharsets.UTF_8))
                .replaceAll(m -> m.group(1) != null ? Matcher.quoteReplacement(m.group(1)) : "");
    }

    /** The body of collectRevocationMaterial, by brace matching from its declaration. */
    private static String collectBody() throws IOException {
        String text = code(TARGET);
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

    @Test
    @DisplayName("the toggle is wired only while the fetch rides the send-time-pinned path")
    void theToggleIsNeverWiredAheadOfTheGuard() throws IOException {
        boolean toggleWired = code(WIRING).contains("setCollectRevocationAtIssuance(");
        String body = collectBody();
        boolean fetchPinned = body.contains("AdapterHttpClient.sendWithRetry(");
        boolean fetchBare = body.contains("HttpClient.newBuilder(") || body.contains("client.send(");

        assertFalse(fetchBare,
                "collectRevocationMaterial opens its own HttpClient or calls send() directly. "
                        + "That path resolves the TSA-supplied host once, or not at all, and is "
                        + "the SSRF the guard exists to close");
        if (toggleWired) {
            assertTrue(fetchPinned,
                    "AnchorWiringConfig wires the revocation toggle while the fetch is not on "
                            + "AdapterHttpClient.sendWithRetry. Toggle before guard is the one "
                            + "order R65 forbids: the moment an operator sets the key, this node "
                            + "connects to whatever the TSA certificate names, unchecked");
        }
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

    @Test
    @DisplayName("the fetch is bounded, and the bound is the one the runbook states")
    void theFetchIsBounded() {
        assertEquals(8L * 1024 * 1024, Rfc3161AnchorTarget.MAX_CRL_BYTES,
                "the CRL cap moved. The runbook states 8 MiB; a cap the runbook does not state "
                        + "is a limit an operator meets in production");
    }
}
