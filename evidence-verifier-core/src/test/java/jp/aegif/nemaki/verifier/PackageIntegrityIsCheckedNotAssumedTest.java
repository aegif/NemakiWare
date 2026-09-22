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
package jp.aegif.nemaki.verifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0, on packages built to break it.
 *
 * <p>The failures that matter here are the quiet ones: an absence reported as a pass, a
 * mismatch reported as "could not check", an addition that travels inside a package which
 * verifies. Each test below is one of those.
 */
class PackageIntegrityIsCheckedNotAssumedTest {

    private static final String ROOT = "sip/";

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

    private static String premis(String digest, String algorithm) {
        return "<premis:premis><premis:object><premis:objectCharacteristics><premis:fixity>"
                + "<premis:messageDigestAlgorithm>" + algorithm
                + "</premis:messageDigestAlgorithm>"
                + "<premis:messageDigest>" + digest + "</premis:messageDigest>"
                + "</premis:fixity></premis:objectCharacteristics></premis:object></premis:premis>";
    }

    private static String mets(String... hrefs) {
        StringBuilder xml = new StringBuilder("<mets:mets><mets:fileSec>");
        for (String href : hrefs) {
            xml.append("<mets:file><mets:FLocat xlink:href=\"").append(href)
                    .append("\"/></mets:file>");
        }
        return xml.append("</mets:fileSec></mets:mets>").toString();
    }

    private static String sha256(String text) {
        return Canonical.hex(Canonical.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> goodPackage(String payload) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt"));
        entries.put(ROOT + "representations/rep1/data/minutes.txt", payload);
        entries.put(ROOT + "metadata/preservation/premis.xml", premis(sha256(payload), "SHA-256"));
        return entries;
    }

    private static Outcome.Check checkNamed(List<Outcome.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a well-formed package passes P0, and every required check actually ran")
    void aGoodPackagePasses(@TempDir Path tmp) throws Exception {
        Path sip = zip(tmp, "good.zip", goodPackage("the minutes"));
        List<Outcome.Check> checks = PackageIntegrity.check(PackageReader.open(sip).entries());

        for (String required : PackageIntegrity.REQUIRED) {
            assertEquals(Outcome.PASSED, checkNamed(checks, required).outcome(),
                    required + " did not pass: " + checks);
        }
        assertEquals(Outcome.Verdict.VERIFIED, Outcome.combine(checks, checks));
    }

    @Test
    @DisplayName("edited payload bytes are a FAILURE, not an absence")
    void editedBytesFail(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = goodPackage("the minutes");
        entries.put(ROOT + "representations/rep1/data/minutes.txt", "the EDITED minutes");
        Path sip = zip(tmp, "edited.zip", entries);

        List<Outcome.Check> checks = PackageIntegrity.check(PackageReader.open(sip).entries());

        assertEquals(Outcome.FAILED, checkNamed(checks, "payload fixity").outcome(), checks + "");
        assertEquals(Outcome.Verdict.FAILED, Outcome.combine(checks, checks),
                "a finding must not be diluted into 'could not tell'");
    }

    @Test
    @DisplayName("a digest algorithm this verifier cannot compute is UNAVAILABLE, not a mismatch")
    void anUnknownAlgorithmIsNotAMismatch(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = goodPackage("the minutes");
        entries.put(ROOT + "metadata/preservation/premis.xml",
                premis("00".repeat(32), "SHA3-512"));
        Path sip = zip(tmp, "sha3.zip", entries);

        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");

        assertEquals(Outcome.UNAVAILABLE, fixity.outcome(),
                "a digest this verifier cannot compute has not been checked; reporting FAILED "
                        + "would announce tampering that was never found");
        assertEquals("UNKNOWN_ALGORITHM", fixity.reasonCode());
    }

    @Test
    @DisplayName("two PREMIS documents are ambiguous, not a free choice of digest")
    void twoPremisDocumentsAreAmbiguous(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = goodPackage("the minutes");
        entries.put(ROOT + "metadata/other/derived/premis.xml", premis("bb".repeat(32), "SHA-256"));
        Path sip = zip(tmp, "two-premis.zip", entries);

        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");

        assertEquals(Outcome.UNAVAILABLE, fixity.outcome());
        assertEquals("AMBIGUOUS_PREMIS", fixity.reasonCode(),
                "choosing by path order would check the bytes against a digest the VERIFIER "
                        + "picked rather than one the package assigned to them");
    }

    @Test
    @DisplayName("two fixities in ONE PREMIS are ambiguous too — reading the first was a free choice")
    void twoFixitiesInOnePremisAreAmbiguous(@TempDir Path tmp) throws Exception {
        // between() took the first of each element, so a PREMIS whose first digest matched and
        // whose second contradicted it passed. The writer produces one; an adversary's package
        // is not the writer's (Codex, fourth review, P1).
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                premis(sha256(payload), "SHA-256").replace("</premis:premis>",
                        "<premis:fixity><premis:messageDigestAlgorithm>SHA-256"
                                + "</premis:messageDigestAlgorithm><premis:messageDigest>"
                                + "ff".repeat(32) + "</premis:messageDigest></premis:fixity>"
                                + "</premis:premis>"));

        Path sip = zip(tmp, "two-fixities.zip", entries);
        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");
        assertEquals(Outcome.UNAVAILABLE, fixity.outcome(),
                "a PREMIS with two message digests was read by taking the first: " + fixity.detail());
        assertEquals("AMBIGUOUS_PREMIS", fixity.reasonCode());
    }

    @Test
    @DisplayName("a digest with no algorithm stated is NOT_PRESENT — SHA-256 was an assumption")
    void aDigestWithNoAlgorithmIsNotPresent(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                "<premis:premis><premis:object><premis:objectCharacteristics><premis:fixity>"
                        + "<premis:messageDigest>" + sha256(payload) + "</premis:messageDigest>"
                        + "</premis:fixity></premis:objectCharacteristics></premis:object>"
                        + "</premis:premis>");

        Path sip = zip(tmp, "no-algorithm.zip", entries);
        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");
        assertEquals(Outcome.NOT_PRESENT, fixity.outcome(),
                "a digest with no stated algorithm was checked as SHA-256, which is a guess "
                        + "that happens to be right for packages this product writes: "
                        + fixity.detail());
    }

    @Test
    @DisplayName("a file the METS does not name is a failure, not an extra")
    void anUnnamedPayloadFails(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = goodPackage("the minutes");
        entries.put(ROOT + "representations/rep1/data/slipped-in.txt", "not in the METS");
        Path sip = zip(tmp, "extra.zip", entries);

        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.FAILED, closure.outcome(),
                "content nobody committed to must not travel inside a package that verifies");
        assertTrue(closure.detail().contains("slipped-in.txt"), closure.detail());
    }

    @Test
    @DisplayName("a file the METS names and the package lacks is a failure too")
    void aMissingNamedFileFails(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = goodPackage("the minutes");
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "representations/rep1/data/annex.pdf"));
        Path sip = zip(tmp, "missing.zip", entries);

        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.FAILED, closure.outcome(),
                "closure runs BOTH ways: a METS naming a file that is not there is an "
                        + "incomplete package");
        assertTrue(closure.detail().contains("annex.pdf"), closure.detail());
    }

    @Test
    @DisplayName("a traversal entry is refused before any check reads it")
    void aTraversalEntryIsRefused(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = goodPackage("the minutes");
        entries.put("../premis.xml", premis("cc".repeat(32), "SHA-256"));
        Path sip = zip(tmp, "traversal.zip", entries);

        PackageReader.Unreadable refusal =
                assertThrows(PackageReader.Unreadable.class, () -> PackageReader.open(sip));
        assertEquals(PackageReader.Refusal.UNSAFE_PATH, refusal.refusal(),
                "nothing here writes to disk, but the NAME decides which entry each check "
                        + "reads: ../premis.xml would be taken for the package's PREMIS");
    }

    @Test
    @DisplayName("a backslash traversal is refused too")
    void aBackslashTraversalIsRefused() {
        assertTrue(!PackageReader.safe("..\\premis.xml"),
                "a check that only looked for ../ would let this through on the platform where "
                        + "it matters");
        assertTrue(!PackageReader.safe("/etc/passwd"));
        assertTrue(!PackageReader.safe("C:/windows/system32"));
        assertTrue(PackageReader.safe("sip/metadata/preservation/premis.xml"));
    }

    @Test
    @DisplayName("a duplicate entry name is refused, not resolved by taking one")
    void aDuplicateEntryIsRefused(@TempDir Path tmp) throws Exception {
        // ZipOutputStream refuses to WRITE a duplicate name, so the archive is built with two
        // distinct names of equal length and the bytes are then patched so both read the same.
        // That is how a hostile package would arrive — nothing stops an archive on disk from
        // holding one path twice — and a verifier that only ever saw JDK-written zips would
        // never meet the case.
        Path file = tmp.resolve("duplicate.zip");
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("a/premis.xml"));
            zip.write("first".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("b/premis.xml"));
            zip.write("second".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] raw = Files.readAllBytes(file);
        byte[] from = "b/premis.xml".getBytes(StandardCharsets.UTF_8);
        byte[] to = "a/premis.xml".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + from.length <= raw.length; i++) {
            boolean match = true;
            for (int j = 0; j < from.length; j++) {
                if (raw[i + j] != from[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(to, 0, raw, i, to.length);
            }
        }
        Files.write(file, raw);

        PackageReader.Unreadable refusal =
                assertThrows(PackageReader.Unreadable.class, () -> PackageReader.open(file));
        assertEquals(PackageReader.Refusal.DUPLICATE_ENTRY, refusal.refusal(),
                "whichever entry is kept, the package has two answers for one path and the "
                        + "verifier has chosen one of them");
    }

    @Test
    @DisplayName("a package with no payload is NOT_PRESENT, never a pass")
    void noPayloadIsNotAPass(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", mets("metadata/preservation/premis.xml"));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                premis(sha256("the minutes"), "SHA-256"));
        Path sip = zip(tmp, "nopayload.zip", entries);

        List<Outcome.Check> checks = PackageIntegrity.check(PackageReader.open(sip).entries());

        assertEquals(Outcome.NOT_PRESENT, checkNamed(checks, "payload fixity").outcome());
        assertEquals(Outcome.Verdict.INDETERMINATE, Outcome.combine(checks, checks),
                "absence must not be promoted to assurance");
    }
}
