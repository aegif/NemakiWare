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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // The namespace IS declared, because a prefix that binds to nothing is not
        // namespace-well-formed XML and the reader parses rather than string-matches. A
        // fixture that omitted it would measure the parse failure, not the check.
        return "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\">"
                + "<premis:object><premis:objectCharacteristics><premis:fixity>"
                + "<premis:messageDigestAlgorithm>" + algorithm
                + "</premis:messageDigestAlgorithm>"
                + "<premis:messageDigest>" + digest + "</premis:messageDigest>"
                + "</premis:fixity></premis:objectCharacteristics></premis:object></premis:premis>";
    }

    private static String mets(String... hrefs) {
        // The namespaces ARE declared, as commons-ip2 declares them. The reader parses rather
        // than string-matches (a prefix is not part of an XML name), so a fixture that left
        // xlink unbound would measure the parse failure instead of the check.
        StringBuilder xml = new StringBuilder(
                "<mets:mets xmlns:mets=\"http://www.loc.gov/METS/\" "
                        + "xmlns:xlink=\"http://www.w3.org/1999/xlink\"><mets:fileSec>");
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
        // ONE algorithm, TWO digests. A second <fixity> with its own algorithm would be caught
        // by the ALGORITHM arm as well, and the lock would then stay green with the digest arm
        // removed — satisfied by a branch it is not about (measured: control NY3 did not fire).
        entries.put(ROOT + "metadata/preservation/premis.xml",
                premis(sha256(payload), "SHA-256").replace("</premis:fixity>",
                        "<premis:messageDigest>" + "ff".repeat(32)
                                + "</premis:messageDigest></premis:fixity>"));

        Path sip = zip(tmp, "two-fixities.zip", entries);
        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");
        // UNAVAILABLE, and this lock has now been written BOTH ways. A seventh-round review
        // read §9 as a rule about counts and it was changed to FAILED; an eighth measured what
        // that does to ordinary CSIP packages, whose PREMIS describes the METS as well — one
        // payload, two digests, refused with "the one-to-one relationship does not hold".
        // §9's sentence is about the LINKAGE, and this verifier does not read it.
        assertEquals(Outcome.UNAVAILABLE, fixity.outcome(),
                "a PREMIS with two message digests was answered as though this verifier could "
                        + "tell which described the payload: " + fixity.detail());
        assertEquals("AMBIGUOUS_PREMIS", fixity.reasonCode());
        assertTrue(fixity.detail().contains("linkage"), fixity.detail());
    }

    /**
     * A second digest under ANOTHER PREFIX is still a second digest.
     *
     * <p>The ambiguity check counted the literal {@code "<premis:messageDigest>"}, and a prefix
     * is not part of an XML name: the same namespace bound to {@code p} gave a document with a
     * matching digest and a contradicting one, counted as one, answered {@code PASSED} (Codex,
     * fifth review, P1).
     */
    @Test
    @DisplayName("two digests under different prefixes are two digests")
    void aSecondDigestUnderAnotherPrefixIsFound(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\" "
                        + "xmlns:p=\"http://www.loc.gov/premis/v3\">"
                        + "<premis:object><premis:objectCharacteristics><premis:fixity>"
                        + "<premis:messageDigestAlgorithm>SHA-256"
                        + "</premis:messageDigestAlgorithm>"
                        + "<premis:messageDigest>" + sha256(payload) + "</premis:messageDigest>"
                        // One algorithm, so only the DIGEST arm can answer: a second one would
                        // let the algorithm arm satisfy this lock instead.
                        + "<p:messageDigest>" + sha256("something else") + "</p:messageDigest>"
                        + "</premis:fixity></premis:objectCharacteristics></premis:object>"
                        + "</premis:premis>");

        Path sip = zip(tmp, "two-prefixes.zip", entries);
        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");

        assertEquals(Outcome.UNAVAILABLE, fixity.outcome(),
                "a PREMIS carrying a matching digest and a contradicting one under a different "
                        + "prefix was read as carrying one: " + fixity.detail());
        assertEquals("AMBIGUOUS_PREMIS", fixity.reasonCode());
        assertTrue(fixity.detail().contains("2 message digests"), fixity.detail());
    }

    /**
     * And the mirror: the same literal inside a COMMENT is not a second digest.
     *
     * <p>Counting text would refuse this package over something that is not markup, and an
     * over-refusal is the same defect as a missed finding on this branch.
     */
    @Test
    @DisplayName("the literal inside a comment is not a second digest")
    void aCommentIsNotASecondDigest(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\">"
                        + "<!-- <premis:messageDigest>deadbeef</premis:messageDigest> -->"
                        + "<premis:object><premis:objectCharacteristics><premis:fixity>"
                        + "<premis:messageDigestAlgorithm>SHA-256"
                        + "</premis:messageDigestAlgorithm>"
                        + "<premis:messageDigest>" + sha256(payload) + "</premis:messageDigest>"
                        + "</premis:fixity></premis:objectCharacteristics></premis:object>"
                        + "</premis:premis>");

        Path sip = zip(tmp, "commented.zip", entries);

        assertEquals(Outcome.PASSED, checkNamed(
                        PackageIntegrity.check(PackageReader.open(sip).entries()),
                        "payload fixity").outcome(),
                "a package was refused because a COMMENT contained the text of a digest element");
    }

    /**
     * A METS is read as XML too, in both directions.
     *
     * <p>The reference scan matched the literal {@code xlink:href="…"}. A METS binding XLink to
     * another prefix produced no references and the closure check silently became
     * {@code NOT_PRESENT}; the same literal inside a COMMENT was counted as a reference and a
     * package was reported as missing a file it never named (subagent, sixth review, P2).
     */
    @Test
    @DisplayName("a METS naming its references under another prefix is still read")
    void aMetsUnderAnotherPrefixIsStillRead(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(goodPackage("the minutes"));
        entries.put(ROOT + "METS.xml", "<m:mets xmlns:m=\"http://www.loc.gov/METS/\" "
                + "xmlns:xl=\"http://www.w3.org/1999/xlink\"><m:fileSec><m:file>"
                + "<m:FLocat xl:href=\"representations/rep1/data/minutes.txt\"/>"
                + "</m:file></m:fileSec></m:mets>");

        Path sip = zip(tmp, "other-prefix.zip", entries);

        assertEquals(Outcome.PASSED, checkNamed(
                        PackageIntegrity.check(PackageReader.open(sip).entries()),
                        "mets closure").outcome(),
                "a METS using its own prefixes was read as naming nothing, so the payload it "
                        + "DOES name went unchecked in both directions");
    }

    @Test
    @DisplayName("a reference inside a comment is not a reference")
    void aCommentedReferenceIsNotAReference(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(goodPackage("the minutes"));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt")
                .replace("</mets:fileSec>",
                        "<!-- <mets:file><mets:FLocat xlink:href=\"gone.txt\"/></mets:file> -->"
                                + "</mets:fileSec>"));

        Path sip = zip(tmp, "commented-mets.zip", entries);

        assertEquals(Outcome.PASSED, checkNamed(
                        PackageIntegrity.check(PackageReader.open(sip).entries()),
                        "mets closure").outcome(),
                "a package was reported as missing a file that only a COMMENT names");
    }

    @Test
    @DisplayName("a METS this verifier cannot read is UNAVAILABLE, not a METS that names nothing")
    void anUnreadableMetsIsNotAMetsThatNamesNothing(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(goodPackage("the minutes"));
        entries.put(ROOT + "METS.xml", "<mets:mets><not-closed>");

        Path sip = zip(tmp, "broken-mets.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.UNAVAILABLE, closure.outcome(), closure.detail());
        assertEquals("METS_NOT_PARSED", closure.reasonCode());
    }

    /**
     * Payload is not a second evidence section, whatever it is called.
     *
     * <p>A package whose CONTENT is a copy of another package's evidence folder was read as
     * carrying two sections: every name duplicated, {@code v1 layout} FAILED, P0 FAILED. The
     * same over-refusal was corrected in {@code oneEvidenceSection} two reviews earlier and
     * reintroduced by the new check (subagent, sixth review, P2).
     */
    @Test
    @DisplayName("a payload copy of an evidence folder is content, not a second section")
    void aPayloadCopyOfASectionIsNotASection(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(goodPackage("the minutes"));
        entries.put(ROOT + "representations/rep1/data/metadata/other/nemaki-evidence/profile.json",
                "{\"profileVersion\":\"1\",\"declaredProfiles\":[]}");
        entries.put(ROOT + "representations/rep1/data/metadata/other/nemaki-evidence/"
                + "bundle-manifest.json", "{\"bundleId\":\"other\",\"files\":[]}");
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "representations/rep1/data/metadata/other/nemaki-evidence/profile.json",
                "representations/rep1/data/metadata/other/nemaki-evidence/bundle-manifest.json"));

        Path sip = zip(tmp, "payload-copy.zip", entries);
        java.util.List<Outcome.Check> checks =
                PackageIntegrity.check(PackageReader.open(sip).entries());

        assertEquals(Outcome.PASSED, checkNamed(checks, "v1 layout").outcome(),
                "a package whose CONTENT contains an evidence folder was refused: "
                        + checkNamed(checks, "v1 layout").detail());
        assertEquals(Outcome.PASSED, checkNamed(checks, "one evidence section").outcome(),
                "the same copy was counted as a second section by the older check too: "
                        + checkNamed(checks, "one evidence section").detail());
    }

    @Test
    @DisplayName("a profile.json that is not JSON is a finding, not a crash")
    void aMalformedProfileIsAFindingNotACrash(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(goodPackage("the minutes"));
        // A broken unicode escape: Json.parse raises NumberFormatException, not
        // NotCanonicalisable, so this used to leave the verifier itself failing (exit 5).
        entries.put(ROOT + "metadata/other/nemaki-evidence/profile.json",
                "{\"profileVersion\":\"" + (char) 92 + "uZZZZ\"}");

        Path sip = zip(tmp, "broken-profile.zip", entries);
        Outcome.Check layout = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> checkNamed(PackageIntegrity.check(PackageReader.open(sip).entries()),
                        "v1 layout"),
                "a package turned the verifier into a crash rather than an answer");

        assertEquals(Outcome.FAILED, layout.outcome(), layout.detail());
    }

    /**
     * The lookups exclude payload too, not only the counting.
     *
     * <p>Stopping {@code one evidence section} from counting a payload copy was right — content
     * is not metadata — but the LOOKUPS kept matching it, and they take the first path in the
     * zip's order. A substituted {@code anchor-target-checkpoint.json} placed earlier was read
     * by every check above P0 while P0 answered PASSED: the correction opened the hole the
     * check existed to close (subagent, seventh review, P1, measured).
     */
    @Test
    @DisplayName("a payload copy is not read by the lookups either")
    void aPayloadCopyIsNotReadByTheLookups() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // FIRST in the map's order, which is what a lookup takes.
        entries.put("sip/representations/rep1/data/metadata/other/nemaki-evidence/"
                + "anchor-target-checkpoint.json",
                "{\"merkleRoot\":\"bb\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("sip/metadata/other/nemaki-evidence/anchor-target-checkpoint.json",
                "{\"merkleRoot\":\"aa\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        byte[] read = Section.fileIn(entries, "anchor-target-checkpoint.json");

        assertEquals("{\"merkleRoot\":\"aa\"}",
                new String(read, java.nio.charset.StandardCharsets.UTF_8),
                "a lookup read the PAYLOAD copy because it came first in the zip. P0 no longer "
                        + "counts that copy as a second section, so nothing else would have "
                        + "reported it");
    }

    /**
     * Equal counts are NOT a finding — this verifier simply cannot pair them.
     *
     * <p>CSIP allows several representations, each with its own fixity, so two digests for two
     * payloads may be a perfectly good package. This verifier does not read the PREMIS
     * object-to-file linkage, so which belongs to which is genuinely unknown. Calling that
     * FAILED would report tampering that was never found — the over-refusal mirror of the
     * defect the two tests above fix.
     */
    @Test
    @DisplayName("two digests for two payloads is 'cannot pair', not a finding")
    void equalCountsAreUnavailableNotFailed(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(goodPackage("the minutes"));
        entries.put(ROOT + "representations/rep2/data/appendix.txt", "the appendix");
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "representations/rep2/data/appendix.txt"));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                premis(sha256("the minutes"), "SHA-256").replace("</premis:object>",
                        "</premis:object><premis:object><premis:objectCharacteristics>"
                                + "<premis:fixity><premis:messageDigestAlgorithm>SHA-256"
                                + "</premis:messageDigestAlgorithm><premis:messageDigest>"
                                + sha256("the appendix") + "</premis:messageDigest>"
                                + "</premis:fixity></premis:objectCharacteristics>"
                                + "</premis:object>"));

        Path sip = zip(tmp, "two-and-two.zip", entries);
        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");

        assertEquals(Outcome.UNAVAILABLE, fixity.outcome(),
                "a package that may well be conformant was reported as breaking the one-to-one "
                        + "relationship. This verifier cannot pair them; that is not a finding "
                        + "about the package: " + fixity.detail());
        assertEquals("AMBIGUOUS_PREMIS", fixity.reasonCode());

        // The shape an ORDINARY CSIP package has: one payload, and a PREMIS that also
        // describes the METS. A count rule called this a one-to-one violation (measured).
        Map<String, String> ordinary = new LinkedHashMap<>(goodPackage("the minutes"));
        ordinary.put(ROOT + "metadata/preservation/premis.xml",
                premis(sha256("the minutes"), "SHA-256").replace("</premis:object>",
                        "</premis:object><premis:object><premis:objectCharacteristics>"
                                + "<premis:fixity><premis:messageDigestAlgorithm>SHA-256"
                                + "</premis:messageDigestAlgorithm><premis:messageDigest>"
                                + sha256("<mets:mets/>") + "</premis:messageDigest>"
                                + "</premis:fixity></premis:objectCharacteristics>"
                                + "</premis:object>"));
        Outcome.Check everyday = checkNamed(PackageIntegrity.check(
                PackageReader.open(zip(tmp, "ordinary.zip", ordinary)).entries()),
                "payload fixity");
        assertEquals(Outcome.UNAVAILABLE, everyday.outcome(),
                "a package whose PREMIS describes the METS as well as the payload — which CSIP "
                        + "and Archivematica both write — was reported as a finding: "
                        + everyday.detail());
    }

    @Test
    @DisplayName("a METS with an internal DOCTYPE is still read")
    void aMetsWithAnInternalDoctypeIsStillRead(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>(goodPackage("the minutes"));
        entries.put(ROOT + "METS.xml", "<?xml version=\"1.0\"?><!DOCTYPE mets:mets [ ]>"
                + mets("representations/rep1/data/minutes.txt"));

        Path sip = zip(tmp, "mets-doctype.zip", entries);

        assertEquals(Outcome.PASSED, checkNamed(
                        PackageIntegrity.check(PackageReader.open(sip).entries()),
                        "mets closure").outcome(),
                "a METS carrying an internal DOCTYPE was refused while a PREMIS carrying one "
                        + "was accepted — the same over-refusal, corrected on one side only");
    }

    /**
     * A nested package's own METS and PREMIS are content, not a second copy.
     *
     * <p>A CSIP AIP that keeps the original SIP as CONTENT carries that SIP's metadata under a
     * representation's data directory. Counting those made the AIP "2 PREMIS documents" and
     * {@code payload fixity} UNAVAILABLE — the nested-package over-refusal surviving in the one
     * lookup that was not moved (subagent, eighth review, P3).
     */
    @Test
    @DisplayName("a nested package's own metadata is content, not a second PREMIS")
    void aNestedPackagesOwnMetadataIsNotCounted(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "representations/rep2/data/inner/metadata/preservation/premis.xml",
                premis(sha256("something the inner package holds"), "SHA-256"));
        entries.put(ROOT + "representations/rep2/data/inner/METS.xml", mets("inner/data/x.txt"));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "representations/rep2/data/inner/metadata/preservation/premis.xml",
                "representations/rep2/data/inner/METS.xml"));

        Path sip = zip(tmp, "nested-metadata.zip", entries);
        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");

        // The claim is about COUNTING PREMIS documents, so that is what is asserted. The
        // package does carry several payloads, which this verifier separately cannot pair —
        // asserting PASSED here would measure that instead.
        assertFalse(fixity.detail().contains("PREMIS documents"),
                "an AIP keeping the original SIP as content was read as carrying two PREMIS "
                        + "documents, so its own fixity went unchecked over metadata that is "
                        + "content: " + fixity.detail());
        assertEquals("AMBIGUOUS_PAYLOAD", fixity.reasonCode(),
                "the check stopped at the PREMIS count rather than reaching the payloads: "
                        + fixity);
    }

    /**
     * An internal DOCTYPE is not a reason to refuse a PREMIS.
     *
     * <p>Refusing DTDs outright kept external entities out and also turned a legitimate
     * third-party document into {@code PREMIS_NOT_PARSED} — the profile does not make a DTD
     * non-conformant (Codex, sixth review, P2). External entity resolution stays off.
     */
    @Test
    @DisplayName("a PREMIS with an internal DOCTYPE is still read")
    void aPremisWithAnInternalDoctypeIsStillRead(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                "<?xml version=\"1.0\"?><!DOCTYPE premis:premis [ ]>"
                        + premis(sha256(payload), "SHA-256"));

        Path sip = zip(tmp, "doctype.zip", entries);

        assertEquals(Outcome.PASSED, checkNamed(
                        PackageIntegrity.check(PackageReader.open(sip).entries()),
                        "payload fixity").outcome(),
                "a PREMIS carrying an internal DOCTYPE was refused, so a legitimate package "
                        + "from another organisation cannot reach P0");
    }

    @Test
    @DisplayName("a digest with no algorithm stated is NOT_PRESENT — SHA-256 was an assumption")
    void aDigestWithNoAlgorithmIsNotPresent(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\">"
                        + "<premis:object><premis:objectCharacteristics><premis:fixity>"
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
