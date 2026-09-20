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
package jp.aegif.nemaki.verifier.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exit code is what a receiving organisation's script branches on.
 *
 * <p>Which makes one substitution the whole point of this class: <b>3 is not 0</b>. "Could not
 * tell" arriving as success at the last possible moment would undo every refusal underneath it.
 */
class TheExitCodeIsTheInterfaceTest {

    private static final String ROOT = "sip/";

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Verify.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static Path zip(Path dir, String name, Map<String, String> entries) throws Exception {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, String> goodPackage(String payload) {
        // Computed here with the JDK rather than through the verifier's own helpers: a
        // fixture built by the code under test would agree with it by construction.
        String digest = sha256Hex(payload);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", "<mets:mets><mets:fileSec><mets:file><mets:FLocat "
                + "xlink:href=\"representations/rep1/data/minutes.txt\"/></mets:file>"
                + "</mets:fileSec></mets:mets>");
        entries.put(ROOT + "representations/rep1/data/minutes.txt", payload);
        entries.put(ROOT + "metadata/preservation/premis.xml",
                "<premis:premis><premis:object><premis:objectCharacteristics><premis:fixity>"
                        + "<premis:messageDigestAlgorithm>SHA-256</premis:messageDigestAlgorithm>"
                        + "<premis:messageDigest>" + digest + "</premis:messageDigest>"
                        + "</premis:fixity></premis:objectCharacteristics></premis:object>"
                        + "</premis:premis>");
        return entries;
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a package that passes P0 exits 0")
    void aGoodPackageExitsZero(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));
        Run result = run("verify", sip.toString());

        assertEquals(Verify.EXIT_VERIFIED, result.code(), result.out() + result.err());
        assertTrue(result.out().contains("verdict: VERIFIED"), result.out());
        assertTrue(result.out().contains("does NOT establish"),
                "the limits print on SUCCESS too. A reader who sees VERIFIED and nothing else "
                        + "supplies their own idea of what it means");
    }

    @Test
    @DisplayName("an edited package exits 2, and it is not 3")
    void anEditedPackageExitsTwo(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = goodPackage("the minutes");
        entries.put(ROOT + "representations/rep1/data/minutes.txt", "the EDITED minutes");
        Path sip = zip(tmp, "edited.zip", entries);

        Run result = run("verify", sip.toString());

        assertEquals(Verify.EXIT_FAILED, result.code(), result.out());
        assertTrue(result.out().contains("verdict: FAILED"), result.out());
    }

    @Test
    @DisplayName("a package that cannot be evaluated exits 3, which is NOT success")
    void anIndeterminatePackageExitsThree(@TempDir Path tmp) throws Exception {
        // A P0-only package asked for the ledger profile: its P1 section is simply not there.
        Path sip = zip(tmp, "legacy.zip", goodPackage("the minutes"));

        Run result = run("verify", sip.toString(), "--profile", "RECORD_LEDGER_V1");

        assertEquals(Verify.EXIT_INDETERMINATE, result.code(), result.out());
        assertTrue(result.out().contains("LEGACY_PACKAGE_LAYOUT"), result.out());
        assertTrue(result.code() != Verify.EXIT_VERIFIED,
                "'could not tell' arriving as success at the last possible moment would undo "
                        + "every refusal underneath it");
    }

    @Test
    @DisplayName("an unreadable package is indeterminate, not a finding about its contents")
    void anUnreadablePackageIsIndeterminate(@TempDir Path tmp) throws Exception {
        Path notAZip = Files.writeString(tmp.resolve("broken.zip"), "this is not a zip");

        Run result = run("verify", notAZip.toString());

        assertEquals(Verify.EXIT_INDETERMINATE, result.code(), result.out());
        assertTrue(result.out().contains("NOT_A_ZIP"), result.out());
    }

    @Test
    @DisplayName("a profile this version cannot evaluate is a usage error, never a weaker pass")
    void anUnknownProfileIsAUsageError(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));

        // Every profile evidence-profile-v1.md defines is now evaluable, so the refusal is
        // measured with a name that is not one of them. This assertion has already had to move
        // twice as profiles landed — each time, leaving it pointed at a name that had become
        // known would have stopped measuring the refusal without going red.
        Run result = run("verify", sip.toString(), "--profile", "SOMETHING_FROM_A_LATER_VERSION");

        assertEquals(Verify.EXIT_USAGE, result.code(),
                "a caller asking for a profile this version cannot evaluate must not be told "
                        + "the package passed a different one");
        assertTrue(result.err().contains("not a profile this version evaluates"), result.err());
    }

    @Test
    @DisplayName("--allow-network is refused rather than silently ignored")
    void allowNetworkIsRefused(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));

        Run result = run("verify", sip.toString(), "--allow-network");

        assertEquals(Verify.EXIT_USAGE, result.code(),
                "a caller passing it expects something to happen; ignoring it would let them "
                        + "believe a network check ran");
    }

    @Test
    @DisplayName("no package named is a usage error, and nothing is checked")
    void noPackageIsAUsageError() {
        Run result = run("verify");
        assertEquals(Verify.EXIT_USAGE, result.code());
        assertTrue(result.err().contains("usage:"), result.err());
    }

    @Test
    @DisplayName("--json prints one object a script can read")
    void jsonOutputIsParseable(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));

        Run result = run("verify", sip.toString(), "--json");

        assertEquals(Verify.EXIT_VERIFIED, result.code());
        Object parsed = jp.aegif.nemaki.verifier.Json.parse(result.out().trim());
        assertTrue(parsed instanceof Map, result.out());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) parsed;
        assertEquals("VERIFIED", body.get("verdict"));
        assertTrue(String.valueOf(body.get("limits")).contains("does NOT establish"),
                "the limits are in the machine-readable output too, not only the human one");
    }

    @Test
    @DisplayName("every exit code the documentation names is distinct")
    void theExitCodesAreDistinct() {
        // 1 is deliberately unused, so a shell treating "nonzero" as failure is right either
        // way and it stays clear that every code here was chosen.
        assertEquals(5, java.util.Set.of(Verify.EXIT_VERIFIED, Verify.EXIT_FAILED,
                Verify.EXIT_INDETERMINATE, Verify.EXIT_USAGE, Verify.EXIT_INTERNAL).size());
        assertEquals(0, Verify.EXIT_VERIFIED, "0 is VERIFIED and nothing else is");
    }
}
