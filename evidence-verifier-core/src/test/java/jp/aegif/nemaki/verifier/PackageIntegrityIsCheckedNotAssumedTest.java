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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** A METS whose {@code mets:mets} element carries {@code xml:base}. */
    private static String metsWithBase(String base, String... hrefs) {
        return mets(hrefs).replace("<mets:mets ", "<mets:mets xml:base=\"" + base + "\" ");
    }

    /** A METS whose single reference declares a non-URL {@code LOCTYPE}. */
    private static String metsWithLocType(String locType, String href, String... local) {
        return metsWithOtherLocType(locType, href, "external-id", local);
    }

    /** The same, with {@code OTHERLOCTYPE} chosen — it is what decides for {@code OTHER}. */
    private static String metsWithOtherLocType(String locType, String href, String otherLocType,
            String... local) {
        return mets(local).replace("</mets:fileSec>",
                "</mets:fileSec><mets:mdRef LOCTYPE=\"" + locType + "\" "
                        + "OTHERLOCTYPE=\"" + otherLocType + "\" xlink:href=\"" + href + "\"/>");
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
        // FAILED, and this lock has been written three ways. A seventh-round review read §9 as
        // a rule about COUNTS and it became FAILED for any two digests; an eighth measured what
        // that does to ordinary CSIP packages, whose PREMIS describes the METS as well, and it
        // went to UNAVAILABLE for all of them; a ninth pointed out that §9's own sentence — one
        // premis:object describing one file TWICE — needs no object-to-file linkage to see.
        // This fixture is that sentence: both digests are inside one object.
        assertEquals(Outcome.FAILED, fixity.outcome(),
                "a PREMIS whose single object records two contradicting digests for one file "
                        + "was not reported as contradicting itself: " + fixity.detail());
        assertTrue(fixity.detail().contains("contradicts itself"), fixity.detail());
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

        assertEquals(Outcome.FAILED, fixity.outcome(),
                "a PREMIS carrying a matching digest and a contradicting one under a different "
                        + "prefix, both inside ONE object, was read as carrying one: "
                        + fixity.detail());
        assertTrue(fixity.detail().contains("contradicts itself"), fixity.detail());
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
        // ONE messageDigestAlgorithm between the two objects. With two, the "two algorithms
        // for one digest" arm answers UNAVAILABLE as well and a sabotage of the digest-count
        // arm leaves this green — the product-side twin was given this treatment and this side
        // was not (subagent, tenth review, P3).
        ordinary.put(ROOT + "metadata/preservation/premis.xml",
                premis(sha256("the minutes"), "SHA-256").replace("</premis:object>",
                        "</premis:object><premis:object><premis:objectCharacteristics>"
                                + "<premis:fixity><premis:messageDigest>"
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

    /**
     * The METS is SEARCHED for with payload excluded; its references are RESOLVED the same way.
     *
     * <p>They were not. {@code metsClosure} found the METS through {@code pathsEndingWith} (no
     * payload) and then resolved every href with a bare {@code endsWith} over every entry
     * (payload included), so a METS naming {@code metadata/preservation/premis.xml} that the
     * package carried ONLY as a copy inside a payload answered "closure complete" — while
     * {@code payload fixity}, looking for the same name, answered "the package carries no
     * PREMIS". Two checks of one profile reading one name in opposite directions (subagent,
     * ninth review, P3).
     *
     * <p>Both halves are asserted here, so the lock is about the DISAGREEMENT and not about
     * either answer on its own.
     */
    @Test
    @DisplayName("a METS reference is not satisfied by a copy inside the payload")
    void aPayloadCopyDoesNotCloseTheMets(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        String inPayload = "representations/rep1/data/inner/metadata/preservation/premis.xml";
        // The package's OWN PREMIS is gone; only a copy inside the payload is left. The METS
        // names that copy as well, so the "payload the METS does not name" arm cannot answer
        // this lock instead.
        entries.remove(ROOT + "metadata/preservation/premis.xml");
        entries.put(ROOT + inPayload, premis(sha256(payload), "SHA-256"));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt", inPayload,
                "metadata/preservation/premis.xml"));

        Path sip = zip(tmp, "payload-copy-closes-mets.zip", entries);
        List<Outcome.Check> checks = PackageIntegrity.check(PackageReader.open(sip).entries());

        Outcome.Check closure = checkNamed(checks, "mets closure");
        assertEquals(Outcome.FAILED, closure.outcome(),
                "the METS names a file the package does not carry, and a copy of it INSIDE the "
                        + "payload was accepted as that file: " + closure.detail());
        assertTrue(closure.detail().contains("metadata/preservation/premis.xml"),
                closure.detail());

        Outcome.Check fixity = checkNamed(checks, "payload fixity");
        assertTrue(fixity.detail().contains("carries no PREMIS"),
                "the other half of the disagreement changed shape, so this lock no longer "
                        + "measures the two checks reading one name the same way: "
                        + fixity.detail());
    }

    /**
     * TWO algorithms for one file is what {@code premis:fixity} is repeatable FOR.
     *
     * <p>The arm above counted {@code messageDigest} elements inside one {@code premis:object},
     * so a conformant PREMIS recording an MD5 and a SHA-256 of the SAME payload — both correct —
     * read as "the PREMIS contradicts itself", exit 2. Meanwhile the check thirty lines further
     * down calls two algorithms an AMBIGUITY: one document, two answers from one profile
     * (subagent, tenth review, P1, measured).
     *
     * <p>So this is the control that keeps the contradiction arm narrow, and
     * {@code aSecondDigestUnderAnotherPrefixIsFound} — two digests under ONE algorithm — is the
     * lock on it. Only the pair discriminates.
     */
    @Test
    @DisplayName("two algorithms for one file is not the PREMIS contradicting itself")
    void twoAlgorithmsForOneFileIsNotAContradiction(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "metadata/preservation/premis.xml",
                "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\">"
                        + "<premis:object><premis:objectCharacteristics>"
                        + "<premis:fixity><premis:messageDigestAlgorithm>MD5"
                        + "</premis:messageDigestAlgorithm><premis:messageDigest>"
                        + "5d41402abc4b2a76b9719d911017c592</premis:messageDigest>"
                        + "</premis:fixity>"
                        + "<premis:fixity><premis:messageDigestAlgorithm>SHA-256"
                        + "</premis:messageDigestAlgorithm><premis:messageDigest>"
                        + sha256(payload) + "</premis:messageDigest>"
                        + "</premis:fixity>"
                        + "</premis:objectCharacteristics></premis:object></premis:premis>");

        Path sip = zip(tmp, "two-algorithms.zip", entries);
        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");

        assertNotEquals(Outcome.FAILED, fixity.outcome(),
                "a PREMIS recording the same payload under MD5 and under SHA-256 — which "
                        + "premis:fixity is repeatable for — was reported as contradicting "
                        + "itself: " + fixity.detail());
    }

    /**
     * The METS that names the payload is the REPRESENTATION's, and it has to be read.
     *
     * <p>CSIP puts a root METS that points at each representation's METS, and the payload
     * reference lives in the latter. {@code packageLevel} — written to stop a representation's
     * own PREMIS being counted as a second PACKAGE PREMIS — was applied to METS as well, so the
     * representation METS was dropped, nothing named the payload, and the reverse direction
     * accused the package of carrying content nobody committed to. <b>Every package this
     * product writes answered FAILED at its own verifier</b> (subagent, tenth review, measured
     * end to end with the real exporter).
     */
    @Test
    @DisplayName("the representation's own METS is read, so CSIP's payload reference counts")
    void aRepresentationsMetsIsReadToo(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        // The CSIP shape: the root METS points at the representation's METS, and only the
        // representation's METS names the payload.
        entries.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        entries.put(ROOT + "representations/rep1/METS.xml", mets("data/minutes.txt"));

        Path sip = zip(tmp, "csip-two-mets.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "the payload is named by the representation's own METS, and this verifier "
                        + "refused to read that METS — so it reported the package as carrying "
                        + "content nobody committed to: " + closure.detail());
    }

    /**
     * An href written from ANOTHER base still resolves — even when it names payload.
     *
     * <p>Resolving relative to the METS's own directory is right, and the ROOT METS may also
     * write the path from the zip root — for it the two differ only by the wrapping folder's
     * name. The loose suffix match that used to serve this is GONE (it resolved one METS's
     * reference in another's neighbourhood, Codex, tenth review, P1); what stands in for it is
     * a named base, not "any entry ending with this".
     */
    @Test
    @DisplayName("an href written from the zip root still resolves, payload included")
    void anHrefFromAnotherBaseStillResolves(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets(ROOT + "representations/rep1/data/minutes.txt"));

        Path sip = zip(tmp, "root-based-href.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a METS naming its own payload from the zip root was told the package does not "
                        + "carry a file the package is carrying: " + closure.detail());
    }

    /**
     * An href is resolved against the METS THAT WROTE IT, not against any METS in the package.
     *
     * <p>Collecting every reference into one list and then trying each against every METS
     * directory let a second METS's neighbourhood satisfy the first one's reference: the root
     * METS names a file that is NOT there, an unrelated METS sits beside an unrelated copy of
     * the same relative name, and the closure answered PASSED (Codex, tenth review, P1).
     */
    @Test
    @DisplayName("another METS's neighbourhood does not satisfy this METS's reference")
    void oneMetssReferenceIsNotResolvedByAnothersDirectory(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        // The root METS names its own metadata/preservation/premis.xml — and the package does
        // NOT carry one at the root. A second METS one directory over does, beside its own copy.
        entries.remove(ROOT + "metadata/preservation/premis.xml");
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "metadata/preservation/premis.xml"));
        entries.put(ROOT + "other/METS.xml", mets("metadata/preservation/premis.xml"));
        entries.put(ROOT + "other/metadata/preservation/premis.xml",
                premis(sha256(payload), "SHA-256"));

        Path sip = zip(tmp, "two-neighbourhoods.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.FAILED, closure.outcome(),
                "the root METS names a file the package does not carry, and a DIFFERENT METS's "
                        + "neighbour was accepted as it: " + closure.detail());
        assertTrue(closure.detail().contains(ROOT + "METS.xml"),
                "the finding does not say WHICH METS named the missing file, so an operator "
                        + "cannot tell the two references apart: " + closure.detail());
    }

    /**
     * {@code ../} in an href is resolved, not searched for literally.
     *
     * <p>A METS href is a relative URI reference and RFC 3986 §5.2.4 removes dot segments.
     * Concatenating the strings looked for a literal {@code sip/metadata/../representations/…}
     * that no zip contains, so a third party's ordinary upward reference was reported as a file
     * the package does not carry (Codex, tenth review, P2).
     */
    @Test
    @DisplayName("an href with ../ resolves, rather than being searched for literally")
    void anUpwardHrefIsResolved(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.remove(ROOT + "METS.xml");
        entries.put(ROOT + "metadata/METS.xml",
                mets("../representations/rep1/data/minutes.txt"));

        Path sip = zip(tmp, "upward-href.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a METS naming its payload one directory up was told the package does not carry "
                        + "a file the package is carrying: " + closure.detail());
    }

    /**
     * An href the producer PERCENT-ENCODED names the file the zip stores RAW.
     *
     * <p>commons-ip2 encodes the href with {@code URLEncoder} and writes the zip entry name
     * unencoded, so every payload whose name carries a space or a non-ASCII character was named
     * one way and stored another — and this verifier refused <b>every such package this product
     * writes</b>, which for a Japanese repository is the ordinary case (subagent, eleventh
     * review, P1, measured against the real library). Both spellings are tried: RFC 3986's
     * {@code %XX} and {@code URLEncoder}'s {@code +} for a space.
     */
    @Test
    @DisplayName("an encoded href names the file the zip stores under its raw name")
    void anEncodedHrefResolves(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        entries.put(ROOT + "representations/rep1/METS.xml",
                mets("data/%E5%A5%91%E7%B4%84%E6%9B%B8.txt", "data/My+Report.pdf"));
        entries.put(ROOT + "representations/rep1/data/契約書.txt", payload);
        entries.put(ROOT + "representations/rep1/data/My Report.pdf", "a report");
        entries.put(ROOT + "metadata/preservation/premis.xml", premis(sha256(payload), "SHA-256"));

        Path sip = zip(tmp, "encoded-href.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a METS naming its payload in the encoded form the producer writes was told the "
                        + "package does not carry files it is carrying: " + closure.detail());
    }

    /**
     * A locator that is not a path inside the package is not a MISSING file.
     *
     * <p>METS has {@code LOCTYPE} precisely to say that a locator is a URN, a DOI or a handle.
     * Skipping only {@code http}/{@code https} reported a legal {@code mets:mdRef LOCTYPE="URN"}
     * as a file the package does not carry (subagent, eleventh review, P2). The {@code file:}
     * forms are the exception — commons-ip2 writes and reads them for a local path.
     */
    @Test
    @DisplayName("a urn: locator is not a file the package is missing; file: is a local path")
    void aNonLocalLocatorIsNotAMissingFile(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "urn:uuid:1b671a64-40d5-491e-99b0-da01ff1f3341",
                "doi:10.1000/182",
                "file://./metadata/preservation/premis.xml"));

        Path sip = zip(tmp, "urn.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a locator METS provides a LOCTYPE for — or the file: form commons-ip2 itself "
                        + "accepts — was reported as a file the package does not carry: "
                        + closure.detail());
    }

    /**
     * A reference that leaves its own package is not closure.
     *
     * <p>RFC 3986 §5.2.4 DISCARDS an excess {@code ..}, which is right for a base with an
     * authority. With a zip as the base it turns "points outside this package" into "names a
     * file inside it": a METS naming {@code ../aip-2/secret.txt} reported its own package as
     * closed over a file belonging to another one, and one climbing above the zip root landed
     * on whatever sat at the top (subagent, eleventh review, P2).
     */
    @Test
    @DisplayName("a reference into a sibling package, or out of the zip, does not close")
    void aReferenceOutOfThePackageIsRefused(@TempDir Path tmp) throws Exception {
        Map<String, String> sibling = new LinkedHashMap<>();
        sibling.put("aip-1/METS.xml", mets("../aip-2/secret.txt"));
        sibling.put("aip-2/METS.xml", mets("secret.txt"));
        sibling.put("aip-2/secret.txt", "another package's file");
        Outcome.Check across = checkNamed(PackageIntegrity.check(
                PackageReader.open(zip(tmp, "siblings.zip", sibling)).entries()), "mets closure");
        assertEquals(Outcome.FAILED, across.outcome(),
                "a package was reported as closed over a file belonging to a DIFFERENT package "
                        + "in the same zip: " + across.detail());

        Map<String, String> climbing = new LinkedHashMap<>();
        climbing.put("aip-1/METS.xml", mets("../../../../x.txt"));
        climbing.put("x.txt", "at the top of the zip");
        Outcome.Check out = checkNamed(PackageIntegrity.check(
                PackageReader.open(zip(tmp, "climbing.zip", climbing)).entries()), "mets closure");
        assertEquals(Outcome.FAILED, out.outcome(),
                "a reference that climbs above the zip root was silently clamped onto a file "
                        + "inside it: " + out.detail());
    }

    /**
     * A fragment identifies something INSIDE a file, and an absolute reference has a base.
     *
     * <p>Both are parts of URI-reference resolution the string concatenation did not do (Codex,
     * eleventh review, P1): {@code data/minutes.txt#page=2} was looked for as a file name with
     * a {@code #} in it, and {@code /metadata/preservation/premis.xml} had its leading slash
     * stripped and was then resolved relative to the METS rather than to the package root.
     */
    @Test
    @DisplayName("a fragment is not part of the file name, and /… is resolved from the IP root")
    void aFragmentAndAnAbsoluteReferenceAreResolved(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        entries.put(ROOT + "representations/rep1/METS.xml",
                mets("data/minutes.txt#page=2", "/metadata/preservation/premis.xml"));

        Path sip = zip(tmp, "fragment.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a fragment was read as part of the file name, or an absolute reference was "
                        + "resolved against the METS instead of the package root: "
                        + closure.detail());
    }

    /**
     * A {@code %} escape MEANS the decoded name — the literal spelling is not a second chance.
     *
     * <p>Trying the reference as written FIRST let a package that lacks {@code data/a b.txt}
     * but carries a file literally NAMED {@code data/a%20b.txt} satisfy the reference with a
     * DIFFERENT file, and closure then reported the package as complete (Codex, twelfth review,
     * P1). A file whose name really contains a percent sign has to be referenced as
     * {@code %25}, which is what RFC 3986 says.
     */
    @Test
    @DisplayName("a %-escaped href is not satisfied by a file literally named that way")
    void anEscapedHrefIsNotSatisfiedByItsLiteralSpelling(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        entries.put(ROOT + "representations/rep1/METS.xml", mets("data/minutes.txt",
                "data/a%20b.txt"));
        // The file the reference MEANS is absent; a file spelled the way the reference is
        // written is present.
        entries.put(ROOT + "representations/rep1/data/a%20b.txt", "a different file");

        Path sip = zip(tmp, "literal-escape.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.FAILED, closure.outcome(),
                "a reference to 'a b.txt' was satisfied by a file named 'a%20b.txt', so closure "
                        + "was reported over a file the METS does not name: " + closure.detail());
    }

    /**
     * An ABSOLUTE reference has one base, and the METS's own directory is not it.
     *
     * <p>The fixture has the COLLISION the first one lacked: a file at the METS-relative
     * position AND the right one at the package root. Without the absolute arm the first is
     * claimed and closure passes over the wrong file — and because nothing was missing, the
     * earlier control could not fire (Codex, twelfth review, P1). That non-firing was read as
     * "the arm changes nothing" and the arm was removed; it was the EXAMPLE that discriminated
     * nothing.
     */
    @Test
    @DisplayName("an absolute reference resolves at the package root, not beside the METS")
    void anAbsoluteReferenceIgnoresTheMetssOwnDirectory(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        entries.put(ROOT + "representations/rep1/METS.xml",
                mets("data/minutes.txt", "/metadata/other/catalogue.xml"));
        entries.put(ROOT + "metadata/other/catalogue.xml", "the one the reference means");
        // The collision: the same relative name also exists beside the METS that names it.
        entries.put(ROOT + "representations/rep1/metadata/other/catalogue.xml", "a DIFFERENT one");

        Path sip = zip(tmp, "absolute-collision.zip", entries);
        List<Outcome.Check> checks = PackageIntegrity.check(PackageReader.open(sip).entries());
        Outcome.Check closure = checkNamed(checks, "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(), closure.detail());
        // Which one was claimed is what this is about, and the reverse direction is where that
        // shows: the file beside the METS is NOT named by anything, and the package root's is.
        Map<String, String> movedIntoPayload = new LinkedHashMap<>(entries);
        movedIntoPayload.remove(ROOT + "metadata/other/catalogue.xml");
        Outcome.Check without = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "absolute-missing.zip", movedIntoPayload)).entries()), "mets closure");
        assertEquals(Outcome.FAILED, without.outcome(),
                "with the file the absolute reference means REMOVED, the one beside the METS "
                        + "was accepted in its place: " + without.detail());
    }

    /**
     * A relative locator a METS declares as EXTERNAL is not a file the package is missing.
     *
     * <p>{@code LOCTYPE} is how METS says what kind of locator this is. Reading only the URI
     * scheme meant an external identifier written as a relative {@code anyURI} — which
     * {@code LOCTYPE="OTHER"} allows — was taken for a package path (Codex, twelfth review, P2).
     */
    @Test
    @DisplayName("a relative locator declared OTHER is external, not a missing file")
    void aRelativeExternalLocatorIsNotAMissingFile(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", metsWithLocType("OTHER", "catalog-entry-1",
                "representations/rep1/data/minutes.txt"));

        Path sip = zip(tmp, "loctype.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "an external identifier the METS declares as such was reported as a file the "
                        + "package does not carry: " + closure.detail());
    }

    /**
     * {@code xml:base} says where a METS's references start from.
     *
     * <p>Ignoring it sent every reference under it to the wrong directory, so a standard METS
     * was reported as naming files the package does not carry (Codex, twelfth review, P2).
     */
    @Test
    @DisplayName("xml:base is where the references start from")
    void anXmlBaseIsHonoured(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", metsWithBase("representations/rep1/", "data/minutes.txt"));

        Path sip = zip(tmp, "xml-base.zip", entries);
        Outcome.Check closure = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a METS declaring where its references start from was told the package does not "
                        + "carry a file it is carrying: " + closure.detail());
    }

    /**
     * Decoding must not INVENT a separator, and a broken escape must not decode at all.
     *
     * <p>Two ways the decoding added at the eleventh review walked out of the package, both
     * measured (subagent, twelfth review, P1):
     *
     * <ul>
     *   <li>{@code ..%2F..%2Floose.txt} — the escapes became separators AFTER the literal
     *       spelling had been checked and BEFORE dot segments were removed. <b>The same climb
     *       spelled {@code ../../} is NOT refused</b> when no other METS owns what it lands on;
     *       what this measures is that the ENCODED spelling does not reach further than the
     *       plain one (subagent, thirteenth review, P2 — the earlier wording here claimed the
     *       plain climb was refused, and it is not);</li>
     *   <li>{@code metadata%3Zpremis.xml} — {@code %3Z} is not an escape, and
     *       {@code digit('3')*16 + digit('Z')} is {@code 48 + -1 = 47}, which is {@code '/'}.
     *       A malformed reference decoded into a path separator.</li>
     * </ul>
     */
    @Test
    @DisplayName("an encoded separator, and a broken escape, do not walk out of the package")
    void anEncodedSeparatorDoesNotWalkOutOfThePackage(@TempDir Path tmp) throws Exception {
        Map<String, String> climbing = new LinkedHashMap<>();
        climbing.put("aip-1/deep/METS.xml", mets("..%2F..%2Floose.txt"));
        climbing.put("loose.txt", "outside the package");
        Outcome.Check encoded = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "encoded-climb.zip", climbing)).entries()), "mets closure");
        assertNotEquals(Outcome.PASSED, encoded.outcome(),
                "a climb spelled with %2F invented separators and resolved: " + encoded.detail());
        assertTrue(encoded.detail().contains("will not follow"), encoded.detail());

        // %4Z is not an escape either, and digit('4')*16 + digit('Z') is 64 + -1 = 63, which is
        // '?'. Deliberately NOT a separator: the per-segment guard answers those, so a fixture
        // built on %3Z would measure that guard instead of the digit check.
        String payload = "the minutes";
        Map<String, String> broken = new LinkedHashMap<>(goodPackage(payload));
        broken.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "metadata/preservation%4Zpremis.xml"));
        broken.put(ROOT + "metadata/preservation?premis.xml", "what the mis-decode lands on");
        Outcome.Check malformed = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "broken-escape.zip", broken)).entries()), "mets closure");
        assertEquals(Outcome.FAILED, malformed.outcome(),
                "a reference whose escapes are not escapes was decoded into a path that happens "
                        + "to exist: " + malformed.detail());
    }

    /**
     * A METS at the TOP of the zip does not own every path in it.
     *
     * <p>The owner of a candidate was the SHALLOWEST METS above it, and a root METS's directory
     * is {@code ""}, which prefixes everything — so one METS at the top made every reference
     * "inside its own package" and switched the check off (subagent, twelfth review, P2).
     */
    @Test
    @DisplayName("a METS at the top of the zip does not own every path in it")
    void aMetsAtTheTopDoesNotOwnEveryPath(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("METS.xml", mets("aip-1/METS.xml", "aip-2/METS.xml"));
        entries.put("aip-1/METS.xml", mets("../aip-2/secret.txt"));
        entries.put("aip-2/METS.xml", mets("secret.txt"));
        entries.put("aip-2/secret.txt", "another package's file");

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "top-mets.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.FAILED, closure.outcome(),
                "with one METS at the top of the zip, a reference from aip-1 into aip-2 was "
                        + "accepted as staying inside its own package: " + closure.detail());
    }

    /**
     * A file that is not part of the package does not change the answer about the package.
     *
     * <p>The IP root was the common directory of every zip entry, so a single stray file beside
     * the package — the {@code __MACOSX} folder macOS writes, a checksum sidecar — collapsed it
     * to {@code ""} and the package began failing (subagent, twelfth review, P2, measured on
     * this exact shape).
     */
    @Test
    @DisplayName("a stray file beside the package does not change the answer about it")
    void aStrayFileBesideThePackageDoesNotChangeTheAnswer(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml",
                "submissionDocumentation/METS.xml"));
        entries.put(ROOT + "representations/rep1/METS.xml", mets("data/minutes.txt"));
        // A reference that needs the IP ROOT to resolve, so a collapsed one is VISIBLE here.
        // Without it this fixture answered the same either way — the stray file changed the IP
        // root and nothing in the package depended on it.
        entries.put(ROOT + "submissionDocumentation/METS.xml",
                mets("metadata/preservation/premis.xml"));
        Outcome.Check clean = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "clean.zip", entries)).entries()), "mets closure");
        assertEquals(Outcome.PASSED, clean.outcome(), clean.detail());

        Map<String, String> withStray = new LinkedHashMap<>(entries);
        withStray.put("__MACOSX/._sip", "resource fork");
        withStray.put("sip.zip.sha256", "aa".repeat(32));
        Outcome.Check strayed = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "strayed.zip", withStray)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, strayed.outcome(),
                "a file that is not part of the package changed what this verifier says about "
                        + "the package: " + strayed.detail());
    }

    /**
     * Any METS may write the wrapping folder's name into the path — not only the root one.
     *
     * <p>Restricting the zip-root base to the root METS refused a representation METS that
     * wrote {@code sip/representations/rep1/data/…}, a form the previous rule accepted
     * (subagent, twelfth review, P2).
     */
    @Test
    @DisplayName("a reference written from the zip root resolves from any METS")
    void aReferenceFromTheZipRootResolvesFromAnyMets(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        entries.put(ROOT + "representations/rep1/METS.xml",
                mets(ROOT + "representations/rep1/data/minutes.txt"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "rep-from-root.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a representation METS naming its own payload from the zip root was told the "
                        + "package does not carry it: " + closure.detail());
    }

    /**
     * {@code file:} with an authority is still a local path, and {@code C:} is not a scheme.
     *
     * <p>Only {@code file://./} was stripped, so {@code file:///x} and {@code file://localhost/x}
     * — both legal, both written by real tools — became files the package does not carry. And a
     * Windows drive letter was read as a URI scheme, which made the reference vanish SILENTLY:
     * the METS "names no files" (subagent, twelfth review, P3).
     */
    @Test
    @DisplayName("file:// with an authority is local, and a drive letter is not a scheme")
    void aFileUrlIsLocalAndADriveLetterIsNotAScheme(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        // The PAYLOAD is named through the file: URI and nothing else names it, so a spelling
        // that is skipped instead of resolved shows up as "payload the METS does not name" —
        // skipping and resolving both answered PASSED while the reference was to metadata.
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets(
                "file:///representations/rep1/data/minutes.txt",
                "file://localhost/metadata/preservation/premis.xml"));
        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "file-url.zip", entries)).entries()), "mets closure");
        assertEquals(Outcome.PASSED, closure.outcome(),
                "a file:// URL with an empty or localhost authority was not read as the local "
                        + "path it names: " + closure.detail());

        Map<String, String> drive = new LinkedHashMap<>(goodPackage(payload));
        drive.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "C:/metadata/nowhere.xml"));
        Outcome.Check windows = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "drive.zip", drive)).entries()), "mets closure");
        assertEquals(Outcome.FAILED, windows.outcome(),
                "a Windows drive letter was read as a URI scheme, so a reference the METS makes "
                        + "was dropped without a word: " + windows.detail());
    }

    /**
     * A sub-METS may write paths from the PACKAGE root, not only from its own directory.
     *
     * <p>An ordinary CSIP shape: {@code submissionDocumentation/METS.xml} naming
     * {@code metadata/preservation/premis.xml} means the package's own metadata, not a copy
     * beside itself. The IP root is a base for exactly this, and it has to be the ROOT METS's
     * directory — deriving it from every zip entry let one stray file collapse it (subagent,
     * twelfth review, P2).
     */
    @Test
    @DisplayName("a sub-METS may write paths from the package root")
    void aSubMetsMayWritePackageRootRelativePaths(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "submissionDocumentation/METS.xml"));
        entries.put(ROOT + "submissionDocumentation/METS.xml",
                mets("metadata/preservation/premis.xml"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "sub-mets.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a sub-METS naming the package's own metadata was told the package does not "
                        + "carry it: " + closure.detail());
    }

    /**
     * A name outside the BMP, and a {@code %XX} run that is not valid UTF-8.
     *
     * <p>Two faults in one decoder, found from opposite directions (both reviewers, thirteenth
     * review, P1):
     *
     * <ul>
     *   <li>every character was written out through {@code String.valueOf(c).getBytes(UTF_8)},
     *       which splits a surrogate pair into two lone surrogates and turns each into
     *       {@code ?}. A payload named 𠮟 — outside the BMP, ordinary in Japanese names — was
     *       decoded into something else and reported as missing, while a BMP name beside it
     *       passed;</li>
     *   <li>{@code new String(bytes, UTF_8)} replaces what it cannot decode with U+FFFD in
     *       silence, so {@code %FF} claimed a file literally named {@code \uFFFD.txt} — a
     *       DIFFERENT file satisfying the reference.</li>
     * </ul>
     */
    @Test
    @DisplayName("a name outside the BMP resolves; an invalid %XX run does not")
    void aNameOutsideTheBmpResolvesAndAnInvalidEscapeDoesNot(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> outside = new LinkedHashMap<>(goodPackage(payload));
        outside.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        outside.put(ROOT + "representations/rep1/METS.xml",
                mets("data/minutes.txt", "data/\uD842\uDF9F.txt"));
        outside.put(ROOT + "representations/rep1/data/\uD842\uDF9F.txt", "outside the BMP");
        Outcome.Check bmp = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "outside-bmp.zip", outside)).entries()), "mets closure");
        assertEquals(Outcome.PASSED, bmp.outcome(),
                "a payload whose name is outside the BMP was told the package does not carry "
                        + "it, while a BMP name beside it resolved: " + bmp.detail());

        Map<String, String> invalid = new LinkedHashMap<>(goodPackage(payload));
        invalid.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        invalid.put(ROOT + "representations/rep1/METS.xml",
                mets("data/minutes.txt", "data/%FF.txt"));
        // The file the SILENT replacement would land on.
        invalid.put(ROOT + "representations/rep1/data/\uFFFD.txt", "a different file");
        Outcome.Check broken = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "invalid-utf8.zip", invalid)).entries()), "mets closure");
        assertEquals(Outcome.FAILED, broken.outcome(),
                "a %XX run that is not valid UTF-8 was decoded into U+FFFD and claimed a "
                        + "different file: " + broken.detail());
    }

    /**
     * {@code file:} on ANOTHER host is not this package, and {@code file:///} is absolute.
     *
     * <p>The authority was stripped whatever it was, so {@code file://archive.example.org/x}
     * claimed a local entry — "points somewhere else" answered as "here it is" (both
     * reviewers, thirteenth review, P1/P2). And dropping the leading slash made
     * {@code file:///x} RELATIVE, so it resolved beside the METS that wrote it: the same
     * "directory-first claims a different file" defect the absolute arm exists to prevent,
     * arriving by another spelling. The fixture has the COLLISION that shows it.
     */
    @Test
    @DisplayName("file: on another host is not local, and file:/// is absolute")
    void aForeignFileUriIsNotLocalAndAnEmptyAuthorityIsAbsolute(@TempDir Path tmp)
            throws Exception {
        String payload = "the minutes";
        // The foreign URI is the ONLY thing naming the payload. Stripping the authority makes
        // it claim the local entry and closure passes; reading it as a file somewhere else
        // leaves the payload unnamed, which is the honest answer — and the two differ, which a
        // fixture pointing at metadata did not (measured: the control stayed green).
        Map<String, String> foreign = new LinkedHashMap<>(goodPackage(payload));
        foreign.put(ROOT + "METS.xml", mets(
                "file://archive.example.org/representations/rep1/data/minutes.txt",
                "metadata/preservation/premis.xml"));
        Outcome.Check elsewhere = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "foreign-file-uri.zip", foreign)).entries()), "mets closure");
        // NOT PASSED: stripping the authority would let the foreign URI claim the local entry
        // and close over it. NOT FAILED either — a locator that was declined may be the one
        // that names the payload, so "the METS does not name it" is not established.
        assertEquals(Outcome.UNAVAILABLE, elsewhere.outcome(),
                "a file: URI on ANOTHER MACHINE claimed an entry in this package, so closure "
                        + "was reported over a file the METS does not name locally: "
                        + elsewhere.detail());
        assertEquals("AMBIGUOUS_PAYLOAD", elsewhere.reasonCode(), elsewhere.detail());

        Map<String, String> collision = new LinkedHashMap<>(goodPackage(payload));
        collision.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml"));
        collision.put(ROOT + "representations/rep1/METS.xml", mets("data/minutes.txt",
                "file:///metadata/other/catalogue.xml"));
        collision.put(ROOT + "metadata/other/catalogue.xml", "the one it means");
        collision.put(ROOT + "representations/rep1/metadata/other/catalogue.xml", "a DIFFERENT one");
        Map<String, String> without = new LinkedHashMap<>(collision);
        without.remove(ROOT + "metadata/other/catalogue.xml");
        Outcome.Check missing = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "file-uri-collision.zip", without)).entries()), "mets closure");
        assertEquals(Outcome.FAILED, missing.outcome(),
                "with the file the absolute file: URI means REMOVED, the one beside the METS "
                        + "was accepted in its place: " + missing.detail());
    }

    /**
     * {@code LOCTYPE="OTHER"} says what {@code OTHERLOCTYPE} says.
     *
     * <p>Every {@code OTHER} locator was dropped without reading {@code OTHERLOCTYPE} — which
     * the javadoc claimed to read — so a METS naming its own payload that way was told it
     * carries content nobody committed to (subagent, thirteenth review, P1).
     */
    @Test
    @DisplayName("OTHERLOCTYPE decides whether an OTHER locator is a path")
    void anOtherLocTypeIsReadBeforeDroppingTheReference(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml",
                metsWithOtherLocType("OTHER", "representations/rep1/data/minutes.txt", "SYSTEM"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "otherloctype.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a payload named by a locator the METS declares as a SYSTEM path was dropped, "
                        + "so the package was accused of carrying content nobody named: "
                        + closure.detail());
    }

    /**
     * {@code xml:base} is merged per RFC 3986 §5.2.2, and locality is judged on the RESULT.
     *
     * <p>A base ending in a segment had the reference concatenated onto it
     * ({@code /representations/rep1/data} + {@code p.bin}), and an {@code xml:base} with a
     * SCHEME produced a URL that was then looked for as a package path (both reviewers,
     * thirteenth review, P2).
     */
    @Test
    @DisplayName("xml:base replaces its last segment, and an external base is external")
    void anXmlBaseIsMergedAndJudgedOnTheResult(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        // §5.2.2 REPLACES the base's last segment, so "…/rep1/catalogue.xml" as a base makes
        // "premis.xml" mean "…/rep1/premis.xml". Concatenation would look for
        // "…/rep1/catalogue.xmlpremis.xml", and adding a slash would look for
        // "…/rep1/catalogue.xml/premis.xml" — neither is in the package, so the fixture tells
        // the three apart.
        Map<String, String> segment = new LinkedHashMap<>();
        segment.put(ROOT + "METS.xml",
                metsWithBase("representations/rep1/catalogue.xml", "premis.xml",
                        "data/minutes.txt"));
        segment.put(ROOT + "representations/rep1/data/minutes.txt", payload);
        segment.put(ROOT + "representations/rep1/premis.xml", premis(sha256(payload), "SHA-256"));
        Outcome.Check merged = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "base-segment.zip", segment)).entries()), "mets closure");
        assertEquals(Outcome.PASSED, merged.outcome(),
                "a base ending in a segment was concatenated rather than merged (RFC 3986 "
                        + "§5.2.2 replaces the last segment): " + merged.detail());

        Map<String, String> external = new LinkedHashMap<>(goodPackage(payload));
        external.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt"));
        external.put(ROOT + "metadata/other/catalogue-mets.xml", "not read");
        external.put(ROOT + "representations/rep1/METS.xml",
                metsWithBase("https://example.invalid/archive/", "catalog.xml"));
        Outcome.Check url = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "base-external.zip", external)).entries()), "mets closure");
        assertEquals(Outcome.PASSED, url.outcome(),
                "a reference under an xml:base with a scheme was looked for as a path inside "
                        + "the package: " + url.detail());
    }

    /**
     * Two packages in one zip: each absolute reference resolves at ITS OWN package's root.
     *
     * <p>One root for the whole zip belonged to one of them, so every absolute reference in the
     * other was refused — the shape {@code v1Layout} answers {@code MULTIPLE_PACKAGES} for, so
     * it is expected, not exotic. And "shallowest" was implemented as the SHORTEST directory
     * string, which made {@code a/b/} the root over {@code verylongpackage/} (both reviewers,
     * thirteenth review, P2).
     */
    @Test
    @DisplayName("each package's absolute references resolve at its own root")
    void twoPackagesEachResolveAtTheirOwnRoot(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String root : List.of("a/b/", "verylongpackage/")) {
            entries.put(root + "METS.xml", mets("representations/rep1/data/minutes.txt",
                    "/metadata/preservation/premis.xml"));
            entries.put(root + "representations/rep1/data/minutes.txt", "the minutes");
            entries.put(root + "metadata/preservation/premis.xml",
                    premis(sha256("the minutes"), "SHA-256"));
        }

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "two-packages.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a zip carrying two packages gave one of them the other's root, so its "
                        + "absolute references were refused: " + closure.detail());
    }

    /**
     * A raw {@code %} in a file name still resolves — the literal spelling is the LAST resort.
     *
     * <p>Trying it FIRST was a fail-open (Codex, twelfth review). Removing it altogether
     * refused a payload named {@code 50%off.txt} that the package carries, and the guard meant
     * to keep it was {@code reference.indexOf('%') < 0} — unsatisfiable, because every path
     * that fails to decode needs a {@code %} to get there. The fallback was dead code and the
     * refusal was real (subagent, fourteenth review, P2, measured).
     *
     * <p>Last resort does not reopen the fail-open: {@code data/a%20b.txt} DECODES, so a
     * package carrying a file literally spelled that way never reaches the fallback — which
     * {@code anEscapedHrefIsNotSatisfiedByItsLiteralSpelling} holds shut.
     */
    @Test
    @DisplayName("a raw % in a file name resolves, as the last resort")
    void aRawPercentInAFileNameStillResolves(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/50%off.txt"));
        entries.put(ROOT + "representations/rep1/data/50%off.txt", payload);
        entries.put(ROOT + "metadata/preservation/premis.xml", premis(sha256(payload), "SHA-256"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "raw-percent.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a payload whose name carries a raw % was reported as a file the package does "
                        + "not carry, while it sits in the zip: " + closure.detail());
    }

    /**
     * An authority means somewhere else — in the reference, in the base, and after merging.
     *
     * <p>Three shapes, all measured (both reviewers, fourteenth review, P2):
     *
     * <ul>
     *   <li>{@code //archive.example.org/x} is a NETWORK-PATH reference (RFC 3986 §4.2) and was
     *       read as an absolute path inside the package;</li>
     *   <li>an ABSOLUTE href under a base with an authority skipped the merge, so
     *       {@code /catalogue/x} under {@code https://example.invalid/archive/} was looked for
     *       in the package;</li>
     *   <li>a base that is authority-only ({@code file://localhost}) merged by its last slash,
     *       giving {@code file://} + the reference — which then read the first segment as a
     *       host and dropped the only reference to the payload.</li>
     * </ul>
     */
    @Test
    @DisplayName("an authority means somewhere else — bare, under a base, and authority-only")
    void anAuthorityMeansSomewhereElse(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> network = new LinkedHashMap<>(goodPackage(payload));
        network.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "//archive.example.org/catalogue/entry-7.xml"));
        assertEquals(Outcome.PASSED, checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "network-path.zip", network)).entries()), "mets closure").outcome(),
                "a //host/path reference was read as a path inside the package");

        Map<String, String> underBase = new LinkedHashMap<>(goodPackage(payload));
        underBase.put(ROOT + "METS.xml", mets("representations/rep1/METS.xml",
                "representations/rep1/data/minutes.txt"));
        underBase.put(ROOT + "representations/rep1/METS.xml",
                metsWithBase("https://example.invalid/archive/", "/catalogue/entry-7.xml"));
        Outcome.Check under = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "absolute-under-base.zip", underBase)).entries()), "mets closure");
        assertEquals(Outcome.PASSED, under.outcome(),
                "an absolute href under a base with an authority was looked for in the package: "
                        + under.detail());

        Map<String, String> authorityOnly = new LinkedHashMap<>(goodPackage(payload));
        authorityOnly.put(ROOT + "METS.xml",
                metsWithBase("file://localhost", "representations/rep1/data/minutes.txt",
                        "metadata/preservation/premis.xml"));
        Outcome.Check only = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "authority-only-base.zip", authorityOnly)).entries()), "mets closure");
        assertEquals(Outcome.PASSED, only.outcome(),
                "an authority-only base merged by its last slash, so the first segment of the "
                        + "reference was read as a host: " + only.detail());
    }

    /**
     * {@code LOCTYPE} that is not {@code URL} is not a path — and {@code OTHERLOCTYPE} is
     * matched on its WORD, not its punctuation.
     *
     * <p>Listing the kinds that are NOT paths let an unlisted one — {@code URI}, which
     * producers write — be read as a package path, so a legitimate external identifier became
     * a missing file. And the {@code OTHERLOCTYPE} whitelist matched one spelling, so
     * {@code relativePath} and {@code RELATIVE PATH} dropped a payload's only name (subagent,
     * fourteenth review, P2). §9 now says what the code does.
     */
    @Test
    @DisplayName("a non-URL LOCTYPE is external; OTHERLOCTYPE matches on the word")
    void aNonUrlLocTypeIsExternalAndOtherLocTypeMatchesOnTheWord(@TempDir Path tmp)
            throws Exception {
        String payload = "the minutes";
        Map<String, String> uri = new LinkedHashMap<>(goodPackage(payload));
        uri.put(ROOT + "METS.xml", metsWithOtherLocType("URI", "catalogue/entry-7", "",
                "representations/rep1/data/minutes.txt"));
        assertEquals(Outcome.PASSED, checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "loctype-uri.zip", uri)).entries()), "mets closure").outcome(),
                "a LOCTYPE this verifier has no entry for was read as a package path");

        for (String spelling : List.of("relativePath", "RELATIVE PATH", "relative_path")) {
            Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
            entries.put(ROOT + "METS.xml", metsWithOtherLocType("OTHER",
                    "representations/rep1/data/minutes.txt", spelling));
            Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                    zip(tmp, "otherloctype-" + spelling.hashCode() + ".zip", entries)).entries()),
                    "mets closure");
            assertEquals(Outcome.PASSED, closure.outcome(),
                    "OTHERLOCTYPE=" + spelling + " dropped the payload's only name, so the "
                            + "package was accused of carrying content nobody committed to: "
                            + closure.detail());
        }
    }

    /**
     * A locator this check did NOT evaluate is counted in the answer.
     *
     * <p>They were skipped in silence, so {@code mets closure} answered PASSED over a METS
     * whose references it had mostly declined to look at — "checked and complete" for work that
     * was not done (subagent, fourteenth review, P3).
     */
    @Test
    @DisplayName("locators that were not evaluated are counted in the answer")
    void skippedLocatorsAreCountedInTheAnswer(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "urn:uuid:1b671a64-40d5-491e-99b0-da01ff1f3341",
                "https://example.invalid/catalogue"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "skipped-counted.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(), closure.detail());
        assertTrue(closure.detail() != null && closure.detail().contains("2 locator(s)"),
                "the answer does not say that two locators were not evaluated: "
                        + closure.detail());
    }

    /**
     * The literal spelling is the last resort for a MALFORMED reference — not for a refused one.
     *
     * <p>Reviving the fallback (fourteenth review) made it fire after the SAFETY refusal too,
     * so a package carrying a file literally NAMED {@code data/%2e%2e/secret.txt} satisfied a
     * reference the verifier had just declined to follow — the twelfth review's fail-open, back
     * in a narrower shape (Codex, fifteenth review, P1). "Could not read it" and "read it and
     * will not follow it" are different answers.
     */
    @Test
    @DisplayName("a refused encoded traversal does not fall back to its literal spelling")
    void aRefusedTraversalDoesNotFallBackToItsLiteralSpelling(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "data/%2e%2e/secret.txt"));
        // A file whose NAME is the reference as written. The decoded form is refused; the
        // literal one must not be tried after that.
        entries.put(ROOT + "data/%2e%2e/secret.txt", "a file named like the escape");

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "refused-literal.zip", entries)).entries()), "mets closure");

        // UNAVAILABLE, not FAILED: the package may well carry a file of that name, and saying
        // "does not carry" about it is a false statement (subagent, sixteenth review, P3).
        // What this locks is that the literal spelling did NOT satisfy the reference.
        assertEquals(Outcome.UNAVAILABLE, closure.outcome(),
                "a reference this verifier refused to follow was satisfied by a file named the "
                        + "way it is written: " + closure.detail());
        assertTrue(closure.detail().contains("will not follow"), closure.detail());
    }

    /**
     * A payload whose ONLY name is a non-local locator is UNNAMED, not "the METS names nothing".
     *
     * <p>Returning the moment no local reference was collected skipped the reverse direction,
     * so the package answered {@code NOT_PRESENT} — "there is nothing to close over" — while
     * carrying content no local reference names (Codex, fifteenth review, P1).
     */
    @Test
    @DisplayName("a payload named only by an external locator is NOT ESTABLISHED, either way")
    void aPayloadNamedOnlyByAnExternalLocatorIsNotEstablished(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", metsWithOtherLocType("URI",
                "representations/rep1/data/minutes.txt", ""));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "only-external.zip", entries)).entries()), "mets closure");

        // NOT "the METS names no files" (which skipped the reverse direction), and NOT FAILED
        // (which asserts a finding the check did not establish — one of the locators it
        // declined may name this payload). Both were wrong, in opposite directions, one review
        // apart.
        assertEquals(Outcome.UNAVAILABLE, closure.outcome(),
                "a package whose payload is named only by a locator this verifier does not "
                        + "follow got a settled answer: " + closure.detail());
        assertEquals("AMBIGUOUS_PAYLOAD", closure.reasonCode(), closure.detail());
        assertTrue(closure.detail().contains("NOT been established"), closure.detail());
        // The COUNT on this arm too. The lock written for "every arm states it" measured the
        // absent and missing-reference arms only, so deleting it from THIS one left both it and
        // the control green (Codex, sixteenth review, P3).
        assertTrue(closure.detail().contains("1 locator(s)"), closure.detail());
    }

    /**
     * A reference with its OWN authority replaces the base's — RFC 3986 §5.2.2.
     *
     * <p>Treating {@code //remote.example/x} under {@code file://localhost/archive/} as a plain
     * absolute reference kept the base's authority and produced
     * {@code file://localhost//remote.example/x}, which was then read as a path in this package
     * (Codex, fifteenth review, P1).
     */
    @Test
    @DisplayName("a reference with its own authority replaces the base's")
    void aReferenceWithItsOwnAuthorityReplacesTheBases(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", metsWithBase("file://localhost/archive/",
                "//remote.example/catalogue", "/representations/rep1/data/minutes.txt"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "own-authority.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a reference carrying its own authority kept the base's and was looked for "
                        + "inside the package: " + closure.detail());
    }

    /**
     * {@code LOCTYPE} is read the same way {@code OTHERLOCTYPE} is, and a path-word means a path.
     *
     * <p>Two inconsistencies, both measured (subagent, fifteenth review, P2): the raw attribute
     * was compared, so a METS that wrapped the line — XML normalises the newline to a space,
     * giving {@code "URL "} — declared something external and its payload became content nobody
     * committed to; and the SAME WORD answered opposite ways depending on which attribute
     * carried it ({@code OTHERLOCTYPE="FILE"} local, {@code LOCTYPE="FILE"} external).
     */
    @Test
    @DisplayName("LOCTYPE is normalised; anything but URL is external")
    void aLocTypeIsNormalisedAndANonUrlIsExternal(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        for (String locType : List.of("URL ", " URL", "URL\n")) {
            Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
            entries.put(ROOT + "METS.xml", metsWithOtherLocType(locType,
                    "representations/rep1/data/minutes.txt", ""));
            Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                    zip(tmp, "loctype-" + locType.hashCode() + ".zip", entries)).entries()),
                    "mets closure");
            assertEquals(Outcome.PASSED, closure.outcome(),
                    "LOCTYPE=\"" + locType + "\" dropped the payload's only name: "
                            + closure.detail());
        }

        // WITHDRAWN, and the withdrawal is the assertion. The fifteenth review asked for a
        // path-word in LOCTYPE to mean what it means in OTHERLOCTYPE; the sixteenth measured
        // what that does — a relative external identifier written as LOCTYPE="LOCAL" became a
        // file the package does not carry. METS ENUMERATES LOCTYPE and leaves OTHERLOCTYPE free
        // text, so the two attributes are not the same question, and §9's first clause said so
        // all along.
        for (String locType : List.of("FILE", "SYSTEM", "LOCAL")) {
            Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
            entries.put(ROOT + "METS.xml", metsWithOtherLocType(locType,
                    "representations/rep1/data/minutes.txt", ""));
            Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                    zip(tmp, "nonurl-" + locType + ".zip", entries)).entries()), "mets closure");
            assertNotEquals(Outcome.PASSED, closure.outcome(),
                    "LOCTYPE=\"" + locType + "\" was followed as a path, and METS does not "
                            + "have that value: " + closure.detail());
        }
    }

    /**
     * An absolute {@code xml:base} under an outer base with an authority keeps that authority.
     *
     * <p>The href side got this rule and the base stack did not, so a nested
     * {@code xml:base="/catalogue/"} under {@code https://example.invalid/archive/} produced a
     * package path — and either claimed an unrelated local file or reported an external
     * reference as missing (subagent, fifteenth review, P2).
     */
    @Test
    @DisplayName("an absolute xml:base under an authority keeps it")
    void anAbsoluteXmlBaseUnderAnAuthorityKeepsIt(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "representations/rep1/METS.xml"));
        entries.put(ROOT + "representations/rep1/METS.xml",
                mets("data/minutes.txt").replace("<mets:fileSec>",
                        "<mets:fileSec xml:base=\"https://example.invalid/archive/\">"
                                + "<mets:fileGrp xml:base=\"/catalogue/\">"
                                + "<mets:file><mets:FLocat xlink:href=\"entry-7.xml\"/>"
                                + "</mets:file></mets:fileGrp>"));
        // NO local sip/catalogue/entry-7.xml. With the base dropped, the reference becomes the
        // package path "/catalogue/entry-7.xml" and is reported as missing; with the base kept
        // it is an external URL and is not this check's business. The two answers differ only
        // when the package does NOT carry that path — with the file present, both arms answer
        // PASSED and the example measures nothing (measured).

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "nested-absolute-base.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a reference under an external xml:base was looked for inside the package: "
                        + closure.detail());
    }

    /**
     * The locators that were NOT evaluated are stated on every answer, not only on PASSED.
     *
     * <p>The arm where it matters most was the silent one: a METS naming four external
     * identifiers and nothing else answered "the METS names no files, so there is nothing to
     * close over" — "did not ask" reported as "asked, and there was nothing" (subagent,
     * fifteenth review, P2).
     */
    @Test
    @DisplayName("locators that were not evaluated are stated on the silent arms too")
    void notEvaluatedIsStatedOnEveryArm(@TempDir Path tmp) throws Exception {
        Map<String, String> onlyExternal = new LinkedHashMap<>();
        onlyExternal.put(ROOT + "METS.xml", mets("urn:uuid:1b671a64-40d5-491e-99b0-da01ff1f3341",
                "doi:10.1000/182", "https://example.invalid/catalogue", "hdl:20.500.12345/abc"));
        Outcome.Check silent = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "only-external-locators.zip", onlyExternal)).entries()), "mets closure");
        assertTrue(silent.detail() != null && silent.detail().contains("4 locator(s)"),
                "a METS naming four external identifiers answered 'names no files' without "
                        + "saying that four were declined: " + silent.detail());

        String payload = "the minutes";
        Map<String, String> missing = new LinkedHashMap<>(goodPackage(payload));
        missing.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "metadata/nowhere.xml", "urn:uuid:1b671a64-40d5-491e-99b0-da01ff1f3341"));
        Outcome.Check failed = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "missing-and-external.zip", missing)).entries()), "mets closure");
        assertEquals(Outcome.FAILED, failed.outcome(), failed.detail());
        assertTrue(failed.detail().contains("1 locator(s)"),
                "a finding was reported without saying that a locator was declined: "
                        + failed.detail());
    }

    /**
     * {@code \\host\share} names another machine too.
     *
     * <p>{@code isPackageLocal} runs BEFORE {@code resolve} turns {@code \\} into {@code /},
     * and recognising only the forward spelling left a UNC path read as a path inside the
     * package (subagent, fifteenth review, P3).
     */
    @Test
    @DisplayName("a UNC path names another machine, not this package")
    void aUncPathIsNotInsideThePackage(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "\\\\archive.example.org\\catalogue\\entry-7.xml"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "unc.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a UNC path naming another machine was reported as a file the package does not "
                        + "carry: " + closure.detail());
    }

    /**
     * A UNC {@code xml:base} is an authority too — the rule reached only one of four places.
     *
     * <p>{@code namesAnotherHost} was added for the UNC spelling and wired into
     * {@code isPackageLocal} ALONE. The three siblings that decide the base — the
     * absolute-href guard, the base stack, {@code hasAuthority} — still knew the forward
     * spelling, so an {@code xml:base} of {@code \\host\share\} was DROPPED (it has no
     * forward slash for {@code merge} to find) and the package's own payload satisfied a
     * reference to another machine. The two legal spellings of the same base answered
     * opposite ways (subagent, sixteenth review, P1). Backslashes are now normalised ONCE,
     * where references are read, so every rule below sees one spelling.
     */
    @Test
    @DisplayName("a UNC xml:base is an authority too, like its forward-slash spelling")
    void aUncXmlBaseIsAnAuthorityToo(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", metsWithBase("\\\\archive.example.org\\catalogue\\",
                "representations/rep1/data/minutes.txt"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "unc-base.zip", entries)).entries()), "mets closure");

        assertNotEquals(Outcome.PASSED, closure.outcome(),
                "a reference under a base naming ANOTHER MACHINE was satisfied by this "
                        + "package's own payload: " + closure.detail());
    }

    /**
     * An empty-path reference names the BASE DOCUMENT, not a sibling.
     *
     * <p>{@code ?download} and {@code #page=2} have no path of their own, and RFC 3986 §5.2.2
     * makes them the base itself. Merging them as paths produced the base's DIRECTORY once the
     * query was dropped, so the METS was told it names a folder (Codex, sixteenth review, P1).
     */
    @Test
    @DisplayName("an empty-path reference names the base document")
    void anEmptyPathReferenceNamesTheBase(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml",
                metsWithBase("representations/rep1/data/minutes.txt", "?download"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "empty-path-reference.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.PASSED, closure.outcome(),
                "a reference with no path of its own was resolved as a sibling of the base "
                        + "rather than as the base: " + closure.detail());

        // With NO xml:base at all it names the METS ITSELF (the document base, §5.1.3), and
        // with an xml:base that is itself path-less the outer base stands. Requiring an
        // explicit file-path base left both resolving to the package root (Codex, seventeenth
        // review, P2).
        Map<String, String> noBase = new LinkedHashMap<>(goodPackage(payload));
        noBase.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt", "?download"));
        assertEquals(Outcome.PASSED, checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "no-base-empty-path.zip", noBase)).entries()), "mets closure").outcome(),
                "a path-less reference in a METS with no xml:base did not name the METS itself");

        Map<String, String> nested = new LinkedHashMap<>(goodPackage(payload));
        nested.put(ROOT + "METS.xml", metsWithBase("representations/rep1/data/minutes.txt",
                "?download").replace("<mets:fileSec", "<mets:fileSec xml:base=\"?download\""));
        assertEquals(Outcome.PASSED, checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "path-less-base.zip", nested)).entries()), "mets closure").outcome(),
                "an xml:base with no path of its own was merged as a path");
    }

    /**
     * A refused reference is refused the SAME WAY wherever the question is asked.
     *
     * <p>{@code resolve} stripped {@code file:} before deciding, and the refused/missing
     * classification asked on the raw href — so {@code file:%2e%2e/secret.txt} was refused by
     * one and counted as a missing file by the other, and the answer was a FINDING about a
     * package that may carry a file of that name (Codex, seventeenth review, P2).
     */
    @Test
    @DisplayName("a refused reference is refused the same way however it is spelled")
    void aRefusedReferenceIsRefusedTheSameWayHoweverSpelled(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "METS.xml", mets("representations/rep1/data/minutes.txt",
                "file:%2e%2e/secret.txt"));

        Outcome.Check closure = checkNamed(PackageIntegrity.check(PackageReader.open(
                zip(tmp, "file-prefixed-refusal.zip", entries)).entries()), "mets closure");

        assertEquals(Outcome.UNAVAILABLE, closure.outcome(),
                "a reference this verifier refused to follow was reported as a file the package "
                        + "does not carry, because the two questions read it differently: "
                        + closure.detail());
        assertTrue(closure.detail().contains("will not follow"), closure.detail());
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
     * A representation's OWN metadata is not a second package PREMIS.
     *
     * <p>CSIP gives every representation its own {@code metadata/}, at
     * {@code representations/<id>/metadata/…}. That is not payload, so the payload exclusion
     * does not reach it, and counting it made an ordinary AIP "2 PREMIS documents" —
     * {@code payload fixity} UNAVAILABLE for a package with nothing wrong with it (subagent,
     * ninth review, P2, measured).
     */
    @Test
    @DisplayName("a representation's own metadata is not the package's")
    void aRepresentationsOwnMetadataIsNotThePackages(@TempDir Path tmp) throws Exception {
        String payload = "the minutes";
        Map<String, String> entries = new LinkedHashMap<>(goodPackage(payload));
        entries.put(ROOT + "representations/rep1/metadata/preservation/premis.xml",
                premis(sha256(payload), "SHA-256"));

        Path sip = zip(tmp, "representation-metadata.zip", entries);
        Outcome.Check fixity = checkNamed(
                PackageIntegrity.check(PackageReader.open(sip).entries()), "payload fixity");

        assertEquals(Outcome.PASSED, fixity.outcome(),
                "a representation's own PREMIS was counted as a second package PREMIS, so an "
                        + "ordinary CSIP AIP cannot reach P0: " + fixity.detail());
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
