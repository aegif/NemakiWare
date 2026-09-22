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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence section's own layout is read — §4.2, §5.2, R71.
 *
 * <p>Every check above P0 finds the document it needs BY NAME and is satisfied when it finds it.
 * That left the section's shape unexamined: a hand-assembled directory with a statement, an
 * entry and a checkpoint in it, and no {@code profile.json}, no {@code bundle-manifest.json} and
 * nothing tying the files together, reached {@code VERIFIED} at {@code ANCHORED_CHECKPOINT_V1}.
 * §4.2 and §5.2 had already said such a package is {@code FAILED}; §9 listed no check that would
 * ever say so, so the specification contradicted itself and the verifier followed §9.
 *
 * <h2>The other half of this lock</h2>
 *
 * <p>A layout check is as easy to get wrong in the refusing direction. The fixtures here are
 * built the way {@code EvidenceBundleWriter} builds a section — manifest last, manifest not
 * listing itself, digests over the exact bytes — and the first test asserts that such a package
 * PASSES. The writer lives in another module that this one may not depend on, so the two halves
 * are joined by the specification rather than by a call: {@code core}'s
 * {@code TheWriterWritesWhatTheLayoutRequiresTest} takes a package the product really built and
 * asserts the same sentences of §4.2 and §5.2 hold of it.
 */
class TheV1LayoutIsCheckedTest {

    private static final String ROOT = "sip/";
    private static final String SECTION = ROOT + "metadata/other/nemaki-evidence/";

    private static String sha256(byte[] bytes) {
        return Canonical.hex(Canonical.sha256(bytes));
    }

    private static Outcome.Check layoutOf(Map<String, byte[]> entries) {
        return PackageIntegrity.check(entries).stream()
                .filter(c -> c.name().equals("v1 layout")).findFirst().orElseThrow();
    }

    /** A package with no evidence section at all: what §4.1 writes. */
    private static Map<String, byte[]> legacyPackage() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", bytes("<mets:mets/>"));
        entries.put(ROOT + "representations/rep1/data/minutes.txt", bytes("the minutes"));
        entries.put(ROOT + "metadata/other/nemaki-evidence.json", bytes("{\"legacy\":true}"));
        return entries;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A section laid out the way the writer lays one out.
     *
     * <p>The manifest is built LAST and over the other files, exactly as
     * {@code EvidenceBundleWriter.writeTo} does, so that a fixture cannot agree with a bug in the
     * check by having been produced from the check's own reading.
     */
    private static Map<String, byte[]> v1Package(Map<String, byte[]> sectionFiles) {
        Map<String, byte[]> section = new LinkedHashMap<>(sectionFiles);
        section.put("bundle-manifest.json", manifestOver(section));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(ROOT + "METS.xml", bytes("<mets:mets/>"));
        entries.put(ROOT + "representations/rep1/data/minutes.txt", bytes("the minutes"));
        for (Map.Entry<String, byte[]> file : section.entrySet()) {
            entries.put(SECTION + file.getKey(), file.getValue());
        }
        return entries;
    }

    /** Sorted, and never naming itself — {@code EvidenceBundleWriter.manifestDocument}. */
    private static byte[] manifestOver(Map<String, byte[]> section) {
        StringBuilder json = new StringBuilder(
                "{\"bundleId\":\"b-1\",\"createdAt\":\"2026-09-22T00:00:00Z\",\"files\":[");
        boolean first = true;
        for (Map.Entry<String, byte[]> file : new TreeMap<>(section).entrySet()) {
            if (file.getKey().equals("bundle-manifest.json")) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"path\":\"").append(file.getKey()).append("\",\"sha256\":\"")
                    .append(sha256(file.getValue())).append("\"}");
        }
        return bytes(json.append("],\"anchors\":[]}").toString());
    }

    private static Map<String, byte[]> wholeSection() {
        Map<String, byte[]> section = new LinkedHashMap<>();
        section.put("profile.json", bytes("{\"profileVersion\":\"1\","
                + "\"declaredProfiles\":[\"RECORD_LEDGER_V1\"],"
                + "\"createdAt\":\"2026-09-22T00:00:00Z\"}"));
        section.put("record-content-statement.json", bytes("{\"objectId\":\"doc-1\"}"));
        section.put("record-content-statement.c14n", bytes("canonical statement"));
        section.put("ledger-entry.json", bytes("{\"sequence\":1}"));
        section.put("ledger-entry.c14n", bytes("canonical entry"));
        section.put("covering-checkpoint.json", bytes("{\"toSequence\":10}"));
        section.put("covering-checkpoint.c14n", bytes("canonical checkpoint"));
        section.put("anchors/rfc3161.der", bytes("not really DER"));
        // The four files §4.2 defines for a prior. Neither end had a fixture carrying any
        // (subagent, sixth review, P3), so the layout check's treatment of prior/ — a
        // subdirectory inside the section — was never measured.
        section.put("prior/record-content-statement.json", bytes("{\"objectId\":\"doc-1\"}"));
        section.put("prior/record-content-statement.c14n", bytes("canonical prior statement"));
        section.put("prior/ledger-entry.json", bytes("{\"sequence\":0}"));
        section.put("prior/ledger-entry.c14n", bytes("canonical prior entry"));
        return section;
    }

    @Test
    @DisplayName("a section built the way the writer builds one passes")
    void aWrittenSectionPasses() {
        Outcome.Check layout = layoutOf(v1Package(wholeSection()));

        assertEquals(Outcome.PASSED, layout.outcome(),
                "a package laid out the way EvidenceBundleWriter lays one out was refused by the "
                        + "check meant to read that layout. Refusing what the product writes is "
                        + "the same defect as passing what it does not: " + layout.detail());
    }

    @Test
    @DisplayName("the manifest not listing itself is not an unlisted file")
    void theManifestDoesNotHaveToNameItself() {
        Map<String, byte[]> entries = v1Package(wholeSection());
        String manifest = new String(entries.get(SECTION + "bundle-manifest.json"),
                StandardCharsets.UTF_8);

        assertFalse(manifest.contains("bundle-manifest.json"),
                "this fixture stopped reproducing the writer: the writer builds the manifest last "
                        + "and does not list it, because a manifest naming its own digest would "
                        + "have to be hashed before it was finished. A fixture that lists it "
                        + "would stop measuring the exemption below");
        assertEquals(Outcome.PASSED, layoutOf(entries).outcome(),
                "the one file the manifest cannot name was treated as an unreferenced addition");
    }

    @Test
    @DisplayName("a legacy package has nothing to lay out, and is not refused for it")
    void aLegacyPackageIsNotRefused() {
        Outcome.Check layout = layoutOf(legacyPackage());

        assertEquals(Outcome.PASSED, layout.outcome(),
                "every package this product wrote before the v1 section existed was refused at "
                        + "P0. Over-refusal is not the safe direction: it makes the verifier "
                        + "useless on the packages that are actually in the field");
        assertTrue(layout.detail() != null && layout.detail().contains("legacy"),
                "the pass does not say WHY there was nothing to check, so a reader takes "
                        + "'v1 layout: PASSED' as 'this package's v1 section is well formed'");
    }

    @Test
    @DisplayName("a section with no profile.json is a finding, not a pass")
    void aSectionWithoutAProfileFails() {
        Map<String, byte[]> section = wholeSection();
        section.remove("profile.json");

        Outcome.Check layout = layoutOf(v1Package(section));

        assertEquals(Outcome.FAILED, layout.outcome(),
                "a hand-assembled section with no profile.json passed P0, and P1 and P2 read the "
                        + "documents beside it as v1 because that is the only thing they know "
                        + "how to read: " + layout.detail());
    }

    @Test
    @DisplayName("a section with no bundle-manifest.json is a finding")
    void aSectionWithoutAManifestFails() {
        Map<String, byte[]> section = wholeSection();
        Map<String, byte[]> entries = v1Package(section);
        entries.remove(SECTION + "bundle-manifest.json");

        assertEquals(Outcome.FAILED, layoutOf(entries).outcome(),
                "without a manifest nothing in the package states which files belong to the "
                        + "bundle, so a file added to it or taken out of it leaves no trace");
    }

    @Test
    @DisplayName("an unstated profileVersion is NOT_PRESENT, a version we do not implement is UNAVAILABLE")
    void theVersionSeparatesUnstatedFromUnsupported() {
        Map<String, byte[]> unstated = wholeSection();
        unstated.put("profile.json", bytes("{\"declaredProfiles\":[\"RECORD_LEDGER_V1\"]}"));
        assertEquals(Outcome.NOT_PRESENT, layoutOf(v1Package(unstated)).outcome(),
                "a profile.json that does not say which contract it was written to was answered "
                        + "as though it had said something");

        Map<String, byte[]> future = wholeSection();
        future.put("profile.json", bytes("{\"profileVersion\":\"2\","
                + "\"declaredProfiles\":[\"RECORD_LEDGER_V1\"]}"));
        Outcome.Check layout = layoutOf(v1Package(future));
        assertEquals(Outcome.UNAVAILABLE, layout.outcome(),
                "a package written to a later contract was reported as violating this one. The "
                        + "rules applied here are v1's, and a v2 package was never written to "
                        + "them: " + layout.detail());
        assertEquals("UNSUPPORTED_PROFILE_VERSION", layout.reasonCode());

        Map<String, byte[]> mistyped = wholeSection();
        mistyped.put("profile.json", bytes("{\"profileVersion\":1,"
                + "\"declaredProfiles\":[\"RECORD_LEDGER_V1\"]}"));
        assertEquals(Outcome.FAILED, layoutOf(v1Package(mistyped)).outcome(),
                "§5 separates a MISSING required field (NOT_PRESENT) from a wrongly typed one "
                        + "(FAILED), and a number where a STRING belongs is the second");
    }

    @Test
    @DisplayName("both layouts in one package is FAILED — the verifier would have to choose")
    void bothLayoutsInOnePackageFails() {
        Map<String, byte[]> entries = v1Package(wholeSection());
        entries.put(ROOT + "metadata/other/nemaki-evidence.json", bytes("{\"legacy\":true}"));

        Outcome.Check layout = layoutOf(entries);

        assertEquals(Outcome.FAILED, layout.outcome(),
                "a package carrying the legacy evidence file AND a v1 section holds two answers "
                        + "to the same question, and whichever the verifier read would be its "
                        + "own choice rather than the package's statement (§4.2)");
        assertTrue(layout.detail().contains("BOTH"), layout.detail());
    }

    @Test
    @DisplayName("an edited file the manifest covers is a FAILURE, not an absence")
    void aDigestMismatchFails() {
        Map<String, byte[]> entries = v1Package(wholeSection());
        entries.put(SECTION + "ledger-entry.c14n", bytes("canonical entry, EDITED"));

        Outcome.Check layout = layoutOf(entries);

        assertEquals(Outcome.FAILED, layout.outcome(), layout.detail());
        assertTrue(layout.detail().contains("ledger-entry.c14n"),
                "the finding does not name the file that moved: " + layout.detail());
    }

    @Test
    @DisplayName("a file the manifest does not name is an addition riding inside the bundle")
    void anUnlistedFileFails() {
        Map<String, byte[]> entries = v1Package(wholeSection());
        entries.put(SECTION + "anchors/atlas.json", bytes("{\"added\":\"later\"}"));

        Outcome.Check layout = layoutOf(entries);

        assertEquals(Outcome.FAILED, layout.outcome(),
                "a file dropped into the section after the manifest was written travelled inside "
                        + "a package that verified");
        assertTrue(layout.detail().contains("anchors/atlas.json"), layout.detail());
    }

    @Test
    @DisplayName("a manifest naming a file the package does not carry is a FAILURE")
    void aNamedFileThatIsNotThereFails() {
        Map<String, byte[]> entries = v1Package(wholeSection());
        entries.remove(SECTION + "covering-checkpoint.c14n");

        Outcome.Check layout = layoutOf(entries);

        assertEquals(Outcome.FAILED, layout.outcome(),
                "a file taken out of a section whose manifest still names it was not reported. "
                        + "Checking only one direction leaves the removal invisible");
        assertTrue(layout.detail().contains("covering-checkpoint.c14n"), layout.detail());
    }

    @Test
    @DisplayName("a manifest entry with no sha256 is NOT_PRESENT — it was read and says nothing")
    void aManifestEntryWithoutADigestIsAGapNotAPass() {
        Map<String, byte[]> section = wholeSection();
        Map<String, byte[]> entries = v1Package(section);
        String manifest = new String(entries.get(SECTION + "bundle-manifest.json"),
                StandardCharsets.UTF_8);
        String stripped = manifest.replaceFirst(
                "\\{\"path\":\"ledger-entry\\.json\",\"sha256\":\"[0-9a-f]{64}\"\\}",
                "{\"path\":\"ledger-entry.json\"}");
        assertFalse(stripped.equals(manifest),
                "this fixture edited nothing, so what follows would measure the unedited package");
        entries.put(SECTION + "bundle-manifest.json", bytes(stripped));

        assertEquals(Outcome.NOT_PRESENT, layoutOf(entries).outcome(),
                "a manifest entry that commits to nothing was treated as one that did");
    }

    @Test
    @DisplayName("a finding beside a gap is reported as the finding")
    void aFindingOutranksAGap() {
        Map<String, byte[]> entries = v1Package(wholeSection());
        String manifest = new String(entries.get(SECTION + "bundle-manifest.json"),
                StandardCharsets.UTF_8);
        entries.put(SECTION + "bundle-manifest.json", bytes(manifest.replaceFirst(
                "\\{\"path\":\"ledger-entry\\.json\",\"sha256\":\"[0-9a-f]{64}\"\\}",
                "{\"path\":\"ledger-entry.json\"}")));
        entries.put(SECTION + "ledger-entry.c14n", bytes("canonical entry, EDITED"));

        assertEquals(Outcome.FAILED, layoutOf(entries).outcome(),
                "an edited file was diluted into 'could not tell' because another manifest entry "
                        + "happened to be incomplete. §15 puts a finding above a gap");
    }

    @Test
    @DisplayName("the same section file twice is a FAILURE, whatever its name")
    void aDuplicatedSectionFileFails() {
        Map<String, byte[]> entries = v1Package(wholeSection());
        entries.put("other-root/metadata/other/nemaki-evidence/profile.json",
                bytes("{\"profileVersion\":\"1\",\"declaredProfiles\":[]}"));

        assertEquals(Outcome.FAILED, layoutOf(entries).outcome(),
                "two entries answering to one name leave the zip's order deciding which bytes "
                        + "every check above P0 reads");
    }

    /**
     * The check is REQUIRED, and a P0 run that skipped it would compose to VERIFIED.
     *
     * <p>Named here rather than left to the profile list: a check that runs and is reported but
     * does not move the verdict is exactly the shape R71 describes — §4.2 said {@code FAILED} and
     * nothing produced it.
     */
    @Test
    @DisplayName("v1 layout is one of P0's required checks, and a failing one composes to FAILED")
    void theCheckIsRequired() {
        assertTrue(PackageIntegrity.REQUIRED.contains("v1 layout"),
                "v1 layout is reported but not required, so a package that fails it still "
                        + "composes to VERIFIED and the profile's own layout goes unenforced");

        Map<String, byte[]> section = wholeSection();
        section.remove("profile.json");
        List<Outcome.Check> checks = PackageIntegrity.check(v1Package(section));
        List<Outcome.Check> required = new ArrayList<>();
        for (String name : PackageIntegrity.REQUIRED) {
            required.add(checks.stream().filter(c -> c.name().equals(name)).findFirst()
                    .orElse(Outcome.Check.absent(name, "this check did not run")));
        }
        assertEquals(Outcome.Verdict.FAILED, Outcome.combine(checks, required),
                "P0 over a section with no profile.json did not compose to FAILED: " + checks);
    }
}
