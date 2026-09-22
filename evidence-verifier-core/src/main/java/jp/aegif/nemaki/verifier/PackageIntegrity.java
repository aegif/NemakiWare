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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code PACKAGE_INTEGRITY_V1} — the checks of {@code evidence-profile-v1.md} §9.
 *
 * <p>The profile that needs nothing but SHA-256 and the package itself. It says the container
 * holds what it says it holds; it says <b>nothing</b> about the ledger or any external anchor,
 * and a caller rendering a P0 pass as "this record is authentic" has read it wrong.
 */
public final class PackageIntegrity {

    /** Where a payload lives inside a CSIP package. */
    private static final String PAYLOAD_PREFIX_MARKER = "/representations/";
    private static final String PAYLOAD_DATA_MARKER = "/data/";

    private PackageIntegrity() {
    }

    /** The checks this profile will not pass without — §9. */
    public static final List<String> REQUIRED = List.of(
            "zip safe", "one evidence section", "v1 layout", "payload fixity", "mets closure");

    /**
     * Runs P0 over a package that has already been read.
     *
     * <p>{@code zip safe} is PASSED by the time this is called — {@link PackageReader} refuses
     * the unsafe cases outright — and it is still reported, because a report that lists only
     * what went wrong lets a reader assume the rest was checked. §15: "調べたが必須ではなかった"
     * and "調べていない" are different.
     */
    public static List<Outcome.Check> check(Map<String, byte[]> entries) {
        List<Outcome.Check> checks = new ArrayList<>();
        checks.add(Outcome.Check.passed("zip safe"));
        checks.add(oneEvidenceSection(entries));
        checks.add(v1Layout(entries));
        checks.add(payloadFixity(entries));
        checks.add(metsClosure(entries));
        return checks;
    }

    /** The evidence documents every check above P0 looks up by name (§4.2). */
    private static final List<String> EVIDENCE_FILES = List.of(
            "profile.json", "bundle-manifest.json",
            "record-content-statement.json", "record-content-statement.c14n",
            "ledger-entry.json", "ledger-entry.c14n",
            "inclusion-proof.json",
            "covering-checkpoint.json", "covering-checkpoint.c14n",
            "checkpoint-chain.json",
            "anchor-target-checkpoint.json", "anchor-target-checkpoint.c14n",
            "prior/record-content-statement.json", "prior/record-content-statement.c14n",
            "prior/ledger-entry.json", "prior/ledger-entry.c14n",
            "anchors/rfc3161.der", "anchors/ots.ots", "anchors/ers.der", "anchors/atlas.json");

    /**
     * No evidence document is named twice.
     *
     * <p>Every check above P0 finds its documents by matching the END of an entry path against
     * {@code metadata/other/nemaki-evidence/<name>} and takes the FIRST match, so a package
     * where two entries match one name holds two answers to that question and the zip's order
     * decides which is read — an order the party that built the zip chooses (Codex, second
     * review). A broken section and a made-to-fit one in one package would verify as the
     * made-to-fit one.
     *
     * <p>Counted per NAME rather than per directory, which is what the lookups actually do.
     * Counting directories missed a second section nested inside the first and refused a
     * legitimate package whose PAYLOAD happened to contain a similarly named folder (Codex,
     * third review, P1 and P2).
     *
     * <p>FAILED, not a refusal to read: the package WAS read, and what was found is a package
     * that contradicts itself. Zero evidence documents is not a finding here — a legacy package
     * has none, and P1 reports that under its own name.
     */
    static Outcome.Check oneEvidenceSection(Map<String, byte[]> entries) {
        java.util.SortedMap<String, List<String>> ambiguous = new java.util.TreeMap<>();
        for (String name : EVIDENCE_FILES) {
            String wanted = RecordLedger.DIR + name;
            List<String> matches = new ArrayList<>();
            for (String path : entries.keySet()) {
                // Payload is not a second section, whatever it is called (see
                // sectionRelativeName). Counting per NAME fixed one shape of this and left
                // another: a payload copy of profile.json still matched.
                if (!isPayload(path) && ("/" + path).endsWith(wanted)) {
                    matches.add(path);
                }
            }
            if (matches.size() > 1) {
                ambiguous.put(name, matches);
            }
        }
        if (ambiguous.isEmpty()) {
            return Outcome.Check.passed("one evidence section");
        }
        // A package that CONTAINS another complete package duplicates every relative name by
        // construction. v1Layout answers that UNAVAILABLE (MULTIPLE_PACKAGES) — but this check
        // runs FIRST and composed to FAILED regardless, so the correction never reached a
        // verdict and a legitimate CSIP AIP was still refused. The lock called v1Layout
        // directly and did not go through this sibling branch (Codex, eighth review, P1).
        if (nestedPackages(entries)) {
            return Outcome.Check.unavailable("one evidence section", "MULTIPLE_PACKAGES",
                    "the package carries more than one COMPLETE evidence section, each under "
                            + "its own root, so these names are repeated because one package "
                            + "contains another — not because one section contradicts itself. "
                            + "Which of them this verifier was asked about is not stated: "
                            + ambiguous);
        }
        return Outcome.Check.failed("one evidence section",
                "the package names the same evidence document more than once: " + ambiguous
                        + ". Every check above this one takes the first path that matches, so "
                        + "the zip's order would decide which answer is verified");
    }

    /** The profile version this verifier was written against — §5.1. */
    private static final String PROFILE_VERSION = "1";

    /** The single legacy file of §4.1, which §4.2 forbids beside a v1 section. */
    private static final String LEGACY_FILE = "/metadata/other/nemaki-evidence.json";

    /**
     * The evidence section is laid out the way §4.2 defines it — or there is no section.
     *
     * <p>§4.2 and §5.2 already say what a v1 package carries and what makes one {@code FAILED}:
     * a manifest that covers every file, no unlisted addition beside them, and never the legacy
     * single file next to a v1 section. Nothing evaluated any of it. Every check above P0 looks
     * up the documents it needs BY NAME and is content when it finds them, so a hand-assembled
     * section with no {@code profile.json} and no {@code bundle-manifest.json} reached
     * {@code VERIFIED} at P2 — the profile's own layout was the one part of the package nobody
     * read (both reviews, fourth round, R71).
     *
     * <h2>What this does not do</h2>
     *
     * <p>It does not refuse a package that has no v1 section. A legacy package (§4.1) is what
     * this product wrote before the section existed and is still a valid P0 package; P1 reports
     * its own absence under {@code LEGACY_PACKAGE_LAYOUT}. Turning "no section" into a P0
     * failure would refuse every package this product shipped, which is the over-refusal this
     * branch treats as the same defect as a missed finding.
     *
     * <p>It does not judge a section that declares a version this verifier does not implement.
     * A {@code profileVersion} of {@code "2"} is answered {@code UNAVAILABLE} rather than
     * {@code FAILED}: the rules below are v1's, and applying them to a v2 package would report
     * a violation of a contract that package was never written to (§5.1).
     */
    static Outcome.Check v1Layout(Map<String, byte[]> entries) {
        Map<String, byte[]> section = sectionFiles(entries);
        boolean legacy = entries.keySet().stream()
                .anyMatch(path -> ("/" + path).endsWith(LEGACY_FILE));
        if (section.isEmpty()) {
            return Outcome.Check.passed("v1 layout", legacy
                    ? "the package carries the legacy single evidence file (§4.1) and no v1 "
                            + "section, so §4.2's layout has nothing to constrain here. What "
                            + "that costs is reported by the profiles above this one"
                    : "the package carries no " + RecordLedger.DIR.substring(1) + " section, so "
                            + "§4.2's layout has nothing to constrain here");
        }

        byte[] profileJson = section.get("profile.json");
        if (profileJson == null) {
            return Outcome.Check.failed("v1 layout",
                    "the package carries a " + RecordLedger.DIR.substring(1) + " section with "
                            + section.size() + " file(s) and no profile.json. §4.2 requires it, "
                            + "and without it nothing states which contract the documents beside "
                            + "it were written to — the checks above would read them as v1 "
                            + "because that is the only thing they know how to read");
        }
        Map<String, Object> profile = parseObject(profileJson);
        if (profile == null) {
            return Outcome.Check.failed("v1 layout",
                    "profile.json is not a JSON object, so the section declares no profile "
                            + "version at all");
        }
        Object version = profile.get("profileVersion");
        if (version == null) {
            // §5: a MISSING required field is NOT_PRESENT — the document was read and does not
            // say. That is different from saying something this verifier disagrees with.
            return Outcome.Check.absent("v1 layout",
                    "profile.json states no profileVersion, so which contract this section was "
                            + "written to is unstated and the layout below cannot be applied to it");
        }
        if (!(version instanceof String stated)) {
            return Outcome.Check.failed("v1 layout",
                    "profile.json types profileVersion as " + version.getClass().getSimpleName()
                            + " and §5.1 types it STRING");
        }
        if (!PROFILE_VERSION.equals(stated)) {
            return Outcome.Check.unavailable("v1 layout", "UNSUPPORTED_PROFILE_VERSION",
                    "the section declares profileVersion " + stated + " and this verifier "
                            + "implements v" + PROFILE_VERSION + " only. Its layout rules are "
                            + "v1's, so applying them here would report a violation of a "
                            + "contract this package was never written to");
        }

        List<String> failures = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (legacy) {
            failures.add("the package carries BOTH the legacy metadata/other/nemaki-evidence.json "
                    + "and a v1 section. §4.2 makes that FAILED: a verifier would have to choose "
                    + "which one is the evidence, and whichever it chose would be its own choice "
                    + "rather than the package's statement");
        }
        // NESTED first. A package that contains another complete package duplicates every
        // relative name by construction, so asking about duplicates before asking about roots
        // reports a CSIP AIP carrying its original SIP as a broken package. The answer is
        // UNAVAILABLE either way — INDETERMINATE, never a pass — so nothing escapes a finding
        // by nesting (subagent, seventh review, P3).
        java.util.SortedSet<String> roots = sectionRoots(entries);
        if (roots.size() > 1) {
            // COMPLETE means both files §4.2 makes mandatory, not just a profile: a root
            // carrying one loose profile.json beside another root's documents is a SPLIT
            // wearing a profile, and treating it as a package would let a split escape into
            // "cannot tell" (measured — the duplicate lock went UNAVAILABLE).
            long complete = roots.stream().filter(root ->
                    hasFile(entries, root, "profile.json")
                            && hasFile(entries, root, "bundle-manifest.json")).count();
            if (nestedPackages(entries)) {
                return Outcome.Check.unavailable("v1 layout", "MULTIPLE_PACKAGES",
                        "the package carries " + roots.size() + " complete evidence sections, "
                                + "each under its own root (" + roots + "). Nothing states "
                                + "which of them this verifier was asked about");
            }
            // A SPLIT: the profile and manifest under one root, the documents under another.
            // No root holds a whole section, so neither the duplicate check nor the manifest
            // closure catches it, and every check above this takes the first path that matches
            // (Codex, sixth review, P1).
            failures.add("the package carries evidence section files under " + roots.size()
                    + " different roots (" + roots + ") and " + complete + " of them declare a "
                    + "profile. A section split across roots is verified as one, because every "
                    + "check above this takes the first path that matches");
        }
        java.util.SortedSet<String> duplicated = duplicatesIn(entries);
        if (!duplicated.isEmpty()) {
            failures.add("the section names the same file more than once: " + duplicated
                    + ". The zip's order would decide which bytes every check above this one "
                    + "reads, and that order is chosen by whoever built the zip");
        }
        Object declared = profile.get("declaredProfiles");
        if (declared == null) {
            gaps.add("profile.json lists no declaredProfiles, which §5.1 requires");
        } else if (!(declared instanceof List)) {
            failures.add("profile.json types declaredProfiles as "
                    + declared.getClass().getSimpleName() + " and §5.1 types it LIST of STRING");
        }
        manifestAgreesWithSection(section, failures, gaps);

        if (!failures.isEmpty()) {
            return Outcome.Check.failed("v1 layout", String.join("; ", failures));
        }
        if (!gaps.isEmpty()) {
            return Outcome.Check.absent("v1 layout", String.join("; ", gaps));
        }
        return Outcome.Check.passed("v1 layout");
    }

    /**
     * The manifest names every file in the section, with the digest each one actually has.
     *
     * <p>Both directions, for the reason {@code metsClosure} gives: a manifest naming a file the
     * package does not carry is an incomplete section, and a file in the section that no manifest
     * names is an addition riding inside a package that would otherwise verify (§5.2).
     *
     * <p>{@code bundle-manifest.json} is the one file exempt from the second direction. It is
     * written last and does not list itself — a manifest that named its own digest would have to
     * be hashed before it was finished — so requiring it to appear would refuse every package
     * this product writes.
     */
    private static void manifestAgreesWithSection(Map<String, byte[]> section,
            List<String> failures, List<String> gaps) {
        byte[] manifestJson = section.get("bundle-manifest.json");
        if (manifestJson == null) {
            failures.add("the section carries no bundle-manifest.json, which §4.2 requires. "
                    + "Nothing in the package then states which files belong to the bundle, so "
                    + "a file added to it or taken out of it leaves no trace");
            return;
        }
        Map<String, Object> manifest = parseObject(manifestJson);
        if (manifest == null) {
            failures.add("bundle-manifest.json is not a JSON object");
            return;
        }
        Object files = manifest.get("files");
        if (files == null) {
            gaps.add("bundle-manifest.json lists no files, which §5.2 requires");
            return;
        }
        if (!(files instanceof List<?> listed)) {
            failures.add("bundle-manifest.json types files as " + files.getClass().getSimpleName()
                    + " and §5.2 types it LIST of MAP");
            return;
        }

        java.util.SortedSet<String> named = new java.util.TreeSet<>();
        for (Object item : listed) {
            if (!(item instanceof Map<?, ?> file)) {
                failures.add("bundle-manifest.json lists a "
                        + (item == null ? "null" : item.getClass().getSimpleName())
                        + " among its files and §5.2 types each entry as MAP");
                continue;
            }
            Object path = file.get("path");
            if (path == null) {
                gaps.add("a file entry in bundle-manifest.json omits path, so which file it "
                        + "commits to cannot be read");
                continue;
            }
            if (!(path instanceof String relative)) {
                failures.add("a file entry in bundle-manifest.json types path as "
                        + path.getClass().getSimpleName() + " and §5.2 types it STRING");
                continue;
            }
            // Recorded as NAMED before anything else is judged. An entry whose digest is missing
            // or mistyped still names its file, and dropping it here would report that file as
            // an unlisted addition — a second, wrong finding produced by the first (measured by
            // aManifestEntryWithoutADigestIsAGapNotAPass, which this failed on first writing).
            named.add(relative);
            byte[] bytes = section.get(relative);
            if (bytes == null) {
                failures.add("bundle-manifest.json names " + relative + ", which the section does "
                        + "not carry");
                continue;
            }
            Object sha256 = file.get("sha256");
            if (sha256 == null) {
                gaps.add("the manifest entry for " + relative + " omits sha256, so the manifest "
                        + "names that file without committing to its bytes");
                continue;
            }
            if (!(sha256 instanceof String recorded)) {
                failures.add("the manifest entry for " + relative + " types sha256 as "
                        + sha256.getClass().getSimpleName() + " and §5.2 types it STRING");
                continue;
            }
            String actual = Canonical.hex(Canonical.sha256(bytes));
            if (!actual.equalsIgnoreCase(recorded)) {
                failures.add(relative + " hashes to " + actual + " and the manifest records "
                        + recorded);
            }
        }

        java.util.SortedSet<String> unlisted = new java.util.TreeSet<>();
        for (String relative : section.keySet()) {
            if (!"bundle-manifest.json".equals(relative) && !named.contains(relative)) {
                unlisted.add(relative);
            }
        }
        if (!unlisted.isEmpty()) {
            failures.add("the section carries file(s) no manifest entry names: " + unlisted
                    + ". §5.2 makes an unreferenced addition FAILED — it is content that travels "
                    + "inside the bundle without the bundle committing to it");
        }
    }

    /**
     * The section's files, keyed by their path RELATIVE to the section directory.
     *
     * <p>Directory entries are skipped: a zip may or may not carry them, and a section whose
     * verdict depended on that would differ between two archivers writing the same files.
     *
     * <p>The FIRST entry wins when a name appears twice, because that is what every lookup above
     * P0 does ({@code RecordLedger.fileIn} takes the first path that matches). Keeping the last
     * would make this check hash bytes no other check ever reads. The duplication itself is a
     * finding, reported by {@link #duplicatesIn} and by {@code one evidence section}.
     */
    private static Map<String, byte[]> sectionFiles(Map<String, byte[]> entries) {
        Map<String, byte[]> section = new java.util.TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String relative = sectionRelativeName(entry.getKey());
            if (relative != null) {
                section.putIfAbsent(relative, entry.getValue());
            }
        }
        return section;
    }

    /**
     * Section-relative names carried by more than one entry.
     *
     * <p>{@code one evidence section} answers this for the documents it knows by name; here it is
     * answered for whatever the section actually holds, so a duplicate of a file that list does
     * not mention is still a package holding two answers to one question.
     */
    private static java.util.SortedSet<String> duplicatesIn(Map<String, byte[]> entries) {
        java.util.SortedSet<String> seen = new java.util.TreeSet<>();
        java.util.SortedSet<String> twice = new java.util.TreeSet<>();
        for (String path : entries.keySet()) {
            String relative = sectionRelativeName(path);
            if (relative != null && !seen.add(relative)) {
                twice.add(relative);
            }
        }
        return twice;
    }

    /** Whether {@code root}'s own section carries {@code name}. */
    private static boolean hasFile(Map<String, byte[]> entries, String root, String name) {
        String wanted = root + RecordLedger.DIR + name;
        return entries.keySet().stream().anyMatch(path -> ("/" + path).equals(wanted));
    }

    /**
     * Whether every root carrying section files carries a COMPLETE section.
     *
     * <p>Complete means both files §4.2 makes mandatory. A root holding one loose
     * {@code profile.json} beside another root's documents is a SPLIT wearing a profile, and
     * treating it as a package would let a split escape into "cannot tell".
     */
    static boolean nestedPackages(Map<String, byte[]> entries) {
        java.util.SortedSet<String> roots = sectionRoots(entries);
        return roots.size() > 1 && roots.stream().allMatch(root ->
                hasFile(entries, root, "profile.json")
                        && hasFile(entries, root, "bundle-manifest.json"));
    }

    /** The distinct archival roots that carry evidence-section files. */
    private static java.util.SortedSet<String> sectionRoots(Map<String, byte[]> entries) {
        java.util.SortedSet<String> roots = new java.util.TreeSet<>();
        for (String path : entries.keySet()) {
            if (sectionRelativeName(path) == null) {
                continue;
            }
            roots.add(("/" + path).substring(0, ("/" + path).indexOf(RecordLedger.DIR)));
        }
        return roots;
    }

    /**
     * {@code null} when the entry is not a file inside the section.
     *
     * <p>A PAYLOAD file is never one, whatever it is called. A package whose content happens to
     * be a copy of another package's evidence folder carries entries under a representation's
     * own data directory, and reading those as a second section
     * made every name a duplicate and the whole check FAILED — the same over-refusal
     * {@code oneEvidenceSection} was corrected for two reviews earlier (subagent, sixth review,
     * P2). The evidence section lives under {@code metadata/}; content does not.
     */
    private static String sectionRelativeName(String path) {
        if (path.endsWith("/") || isPayload(path)) {
            return null;
        }
        int at = ("/" + path).indexOf(RecordLedger.DIR);
        if (at < 0) {
            return null;
        }
        String relative = ("/" + path).substring(at + RecordLedger.DIR.length());
        return relative.isEmpty() ? null : relative;
    }

    /** Content, not metadata: a file under a representation's own data directory. */
    private static boolean isPayload(String path) {
        String slashed = "/" + path;
        return slashed.contains(PAYLOAD_PREFIX_MARKER) && slashed.contains(PAYLOAD_DATA_MARKER);
    }

    /**
     * The document as a map, or {@code null} when it is not one.
     *
     * <p>Every RuntimeException, not only {@code NotCanonicalisable}: {@code Json.parse} raises
     * {@code NumberFormatException} for a broken {@code \\uXXXX} escape, and a package could
     * therefore turn a check into exit 5 — the verifier failing rather than answering (subagent,
     * sixth review, P3).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(byte[] json) {
        try {
            Object value = Json.parse(new String(json, StandardCharsets.UTF_8));
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Payload bytes against the digest PREMIS records for them. */
    static Outcome.Check payloadFixity(Map<String, byte[]> entries) {
        List<String> premisPaths = packageLevel(pathsEndingWith(entries, "premis.xml"));
        if (premisPaths.isEmpty()) {
            return Outcome.Check.absent("payload fixity", "the package carries no PREMIS");
        }
        if (premisPaths.size() > 1) {
            // Ambiguity is UNAVAILABLE, not NOT_PRESENT: the package HAS fixity metadata, and
            // choosing between two documents by path order would check the bytes against a
            // digest the verifier picked.
            return Outcome.Check.unavailable("payload fixity", "AMBIGUOUS_PREMIS",
                    "the package carries " + premisPaths.size() + " PREMIS documents ("
                            + premisPaths + ") and this verifier cannot tell which describes "
                            + "the payload");
        }
        // ONE fixity, and the algorithm stated. Reading the first of each let a PREMIS carry a
        // matching digest followed by a contradicting one, and an omitted algorithm be treated
        // as SHA-256 — a package the writer never produces, which is not a reason to accept it
        // from someone else (Codex, fourth review, P1).
        //
        // Read as XML rather than as text: the count was over the literal
        // "<premis:messageDigest>", and a PREFIX IS NOT PART OF AN XML NAME. A document binding
        // the PREMIS namespace to another prefix carried two digests and was counted as one
        // (Codex, fifth review, P1).
        Premis.Fixity fixity = Premis.read(entries.get(premisPaths.get(0)));
        if (!fixity.parsed()) {
            // UNAVAILABLE, not FAILED. Some of what this refuses is the package's fault (not
            // XML) and some is this verifier's policy (no DTDs, because a verifier must not
            // fetch what a package names). Reporting either as a finding would announce a
            // defect in a document nobody read.
            return Outcome.Check.unavailable("payload fixity", "PREMIS_NOT_PARSED",
                    "the package presents " + premisPaths.get(0) + " as PREMIS and this "
                            + "verifier could not read it as XML: " + fixity.unreadable());
        }
        if (fixity.contradiction() != null) {
            // §9 on its own terms: ONE premis:object recording two DIFFERENT digests under ONE
            // algorithm is PREMIS contradicting itself, and seeing that needs no object-to-file
            // linkage. Two digests under two algorithms is not that — premis:fixity is
            // repeatable exactly so one file can carry an MD5 and a SHA-256 (subagent, tenth
            // review, P1). The twin in core's SipVerifier.payloadDigestCheck answers the same.
            return Outcome.Check.failed("payload fixity",
                    "one premis:object records two different " + fixity.contradiction()
                            + " digests for the file it describes, so the PREMIS contradicts "
                            + "itself about that file");
        }
        if (fixity.digests().size() > 1) {
            // UNAVAILABLE, and NOT a count comparison.
            //
            // A review read §9's "PREMIS が 1 つの payload に 2 つ fixity を持つ … は FAILED"
            // as a rule about NUMBERS and this check was changed to compare the digest count
            // with the payload count. Another review then measured what that does to ordinary
            // packages: CSIP and Archivematica write one premis:object per FILE — the METS and
            // the submission documentation included — so a perfectly good package with one
            // payload routinely records two or three digests, and the comparison called it
            // "the one-to-one relationship does not hold". exit 2, "checked and wrong", for a
            // package with nothing wrong with it (subagent, eighth review, P1, measured).
            //
            // §9's sentence is about the LINKAGE — which digest describes which file — and
            // this verifier does not read the PREMIS object-to-file linkage at all. Not
            // reading it is exactly why it cannot say the relationship is broken.
            return Outcome.Check.unavailable("payload fixity", "AMBIGUOUS_PREMIS",
                    "the PREMIS records " + fixity.digests().size() + " message digests and "
                            + "this verifier does not read the object-to-file linkage that "
                            + "says which of them describes the payload");
        }
        String recorded = fixity.digests().isEmpty() ? null : fixity.digests().get(0);
        if (recorded == null || recorded.isBlank()) {
            return Outcome.Check.absent("payload fixity",
                    "PREMIS records no message digest, so there is nothing to check the bytes "
                            + "against. That is a gap in what was captured, not a failure here");
        }
        if (fixity.algorithms().size() > 1) {
            return Outcome.Check.unavailable("payload fixity", "AMBIGUOUS_PREMIS",
                    "the PREMIS records " + fixity.algorithms().size() + " digest algorithms "
                            + "for one digest, so which function produced it is not stated");
        }
        String algorithm = fixity.algorithms().isEmpty() ? null : fixity.algorithms().get(0);
        if (algorithm == null || algorithm.isBlank()) {
            return Outcome.Check.absent("payload fixity",
                    "PREMIS records a digest and no algorithm, so which function produced it "
                            + "is not stated and nothing here can recompute it");
        }
        if (!"SHA-256".equalsIgnoreCase(algorithm.trim())) {
            // UNSUPPORTED, not FAILED. A digest this verifier cannot compute is one it has not
            // checked; calling it a mismatch would report tampering that was never found.
            return Outcome.Check.unavailable("payload fixity", "UNKNOWN_ALGORITHM",
                    "PREMIS records a " + algorithm.trim() + " digest and this verifier computes "
                            + "SHA-256 only");
        }

        Map<String, byte[]> payloads = payloadsIn(entries);
        if (payloads.isEmpty()) {
            return Outcome.Check.absent("payload fixity",
                    "the package carries no payload under representations/*/data/");
        }
        if (payloads.size() > 1) {
            // Same reasoning in the other direction: without the linkage this verifier cannot
            // say WHICH payload the one recorded digest describes, so it cannot say the
            // relationship is broken either.
            return Outcome.Check.unavailable("payload fixity", "AMBIGUOUS_PAYLOAD",
                    "the package carries " + payloads.size() + " payloads and PREMIS records "
                            + "one digest, and this verifier does not read the object-to-file "
                            + "linkage that says which payload it describes");
        }
        Map.Entry<String, byte[]> payload = payloads.entrySet().iterator().next();
        String actual = Canonical.hex(Canonical.sha256(payload.getValue()));
        if (!actual.equalsIgnoreCase(recorded.trim())) {
            return Outcome.Check.failed("payload fixity",
                    payload.getKey() + " hashes to " + actual + " and PREMIS records "
                            + recorded.trim());
        }
        return Outcome.Check.passed("payload fixity");
    }

    /**
     * Every file the METS names is in the package, and every payload is named by the METS.
     *
     * <p>Both directions. One of them alone leaves a hole: a METS naming a file that is not
     * there is an incomplete package, and a file that is there and unnamed is content nobody
     * committed to — which is how an addition travels inside a package that verifies.
     */
    static Outcome.Check metsClosure(Map<String, byte[]> entries) {
        // EVERY non-payload METS, not just the package-level one. packageLevel() exists to stop
        // a representation's own PREMIS being counted as a second PACKAGE PREMIS; applied to
        // METS it DROPPED representations/<id>/METS.xml — and in CSIP that is the METS that
        // names the payload. So `named` never contained the payload, the reverse direction
        // accused the package of carrying "payload the METS does not name", and every package
        // this product writes answered FAILED, exit 2, from its own verifier (subagent, tenth
        // review, measured end to end with the real exporter).
        List<String> metsPaths = pathsEndingWith(entries, "METS.xml");
        if (metsPaths.isEmpty()) {
            return Outcome.Check.absent("mets closure", "the package carries no METS");
        }
        // NOT one IP root for the zip. A zip may carry two packages side by side — the shape
        // v1Layout answers MULTIPLE_PACKAGES for — and one root then belonged to one of them,
        // so every absolute reference in the OTHER was refused (subagent, thirteenth review,
        // P2). The root is asked PER METS, in packageRootOf.
        // Each href stays PAIRED with the METS that wrote it. Collecting them into one list and
        // then trying each against every METS directory let a reference written by one METS be
        // resolved by another one's neighbourhood: a package missing the file its root METS
        // names answered PASSED because an unrelated METS beside an unrelated copy happened to
        // sit one directory over (Codex, tenth review, P1).
        Map<String, List<String>> namedBy = new LinkedHashMap<>();
        List<String> named = new ArrayList<>();
        int external = 0;
        for (String metsPath : metsPaths) {
            String mets = new String(entries.get(metsPath), StandardCharsets.UTF_8);
            List<String> hrefs = hrefsIn(mets);
            if (hrefs == null) {
                // The package HAS a METS and this verifier could not read it. Saying the METS
                // names nothing would turn that into a fact about the package.
                return Outcome.Check.unavailable("mets closure", "METS_NOT_PARSED",
                        "the package presents " + metsPath + " as a METS and this verifier "
                                + "could not read it as XML, so what it names is unknown"
                                + notEvaluated(external));
            }
            namedBy.put(metsPath, hrefs);
            named.addAll(hrefs);
            external += LAST_EXTERNAL.get();
        }
        if (named.isEmpty() && payloadsIn(entries).isEmpty()) {
            return Outcome.Check.absent("mets closure",
                    external == 0
                            ? "the METS names no files, so there is nothing to close over."
                            : "the METS names no file INSIDE the package."
                                    + notEvaluated(external));
        }
        // NOT an early return when the package HAS payload. Leaving here the moment no local
        // reference was collected skipped the reverse direction, so a payload whose ONLY name
        // was a non-URL locator answered "the METS names no files" — NOT_PRESENT — instead of
        // "the package carries payload the METS does not name" (Codex, fifteenth review, P1).

        // BOTH directions read one resolution. The reverse one used to ask whether some entry's
        // path ENDED with some href — a different question from the one the forward direction
        // asked, so the two halves of this check could disagree about the same reference.
        List<String> missing = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        java.util.Set<String> claimed = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, List<String>> wrote : namedBy.entrySet()) {
            for (String href : wrote.getValue()) {
                String entry = resolve(entries, wrote.getKey(),
                        packageRootOf(wrote.getKey(), metsPaths), metsPaths, href);
                if (entry != null) {
                    claimed.add(entry);
                } else if (spellingsOf(withoutFragmentOrQuery(href)).isEmpty()) {
                    // REFUSED, not missing: decoding it would invent a separator or a dot
                    // segment. Counting it as "a file the package does not carry" said
                    // something FALSE about a package that may well carry a file of that name
                    // — §9 draws the distinction and the answer did not (subagent, sixteenth
                    // review, P3).
                    refused.add(wrote.getKey() + " -> " + href);
                } else {
                    missing.add(wrote.getKey() + " -> " + href);
                }
            }
        }
        if (!missing.isEmpty()) {
            return Outcome.Check.failed("mets closure",
                    "the METS names " + missing.size() + " file(s) the package does not carry: "
                            + missing + wouldNotFollow(refused) + notEvaluated(external));
        }
        if (!refused.isEmpty()) {
            return Outcome.Check.unavailable("mets closure", "METS_NOT_PARSED",
                    "this verifier will not follow " + refused.size() + " reference(s) the METS "
                            + "makes, because decoding them would invent a path separator or a "
                            + "dot segment: " + refused + ". Whether the package carries what "
                            + "they name has NOT been established" + notEvaluated(external));
        }

        List<String> unnamed = new ArrayList<>();
        for (String path : payloadsIn(entries).keySet()) {
            if (!claimed.contains(path)) {
                unnamed.add(path);
            }
        }
        if (!unnamed.isEmpty()) {
            if (external > 0) {
                // NOT a finding. Some locators were not followed, so "no reference names this
                // payload" is not something this check established — one of the ones it
                // declined may name it. Reporting FAILED said "checked, and content nobody
                // committed to is inside" while the same sentence admitted it had not looked
                // (subagent, sixteenth review, P2). The early return this replaced hid the
                // case; answering exit 2 for it was the other half of the same mistake.
                return Outcome.Check.unavailable("mets closure", "AMBIGUOUS_PAYLOAD",
                        "no reference this verifier follows names " + unnamed + ", and "
                                + external + " locator(s) name something OUTSIDE the package "
                                + "and were NOT evaluated — so whether the payload is named "
                                + "has NOT been established");
            }
            return Outcome.Check.failed("mets closure",
                    "the package carries payload the METS does not name: " + unnamed
                            + ". Content nobody committed to travels inside a package that "
                            + "would otherwise verify");
        }
        return external == 0 ? Outcome.Check.passed("mets closure")
                : Outcome.Check.passed("mets closure",
                        "every file the METS names is present and every payload is named."
                                + notEvaluated(external));
    }

    /**
     * What to add to any answer when locators were declined.
     *
     * <p>It used to be on the PASSED arm only, and the arm where it matters most was the one
     * that said "the METS names no files" about a METS that named four (subagent, fifteenth
     * review, P2). "Did not ask" must not read as "asked, and there was nothing".
     */
    private static String notEvaluated(int external) {
        return external == 0 ? "" : " " + external + " locator(s) name something OUTSIDE the "
                + "package (a URN, a DOI, a handle, a file: URI on another host, an http URL) "
                + "and were NOT evaluated here.";
    }

    /** What to add when some references were refused rather than looked for. */
    private static String wouldNotFollow(List<String> refused) {
        return refused.isEmpty() ? "" : " " + refused.size() + " further reference(s) were not "
                + "followed at all (decoding them would invent a separator): " + refused + ".";
    }

    /**
     * The package entry this METS's href names, or null.
     *
     * <p>A METS {@code xlink:href} is a URI reference. Resolving one here means:
     *
     * <ol>
     *   <li>drop the fragment and the query — they identify something INSIDE the file, not
     *       another file;</li>
     *   <li>take the local path out of a {@code file:} URI — {@code file://./x},
     *       {@code file:///x} and {@code file://localhost/x} are this machine, any other
     *       authority is another one and is not a reference into this package;</li>
     *   <li>try the reference PERCENT-DECODED, and decoded the way
     *       {@code URLEncoder} writes it (a {@code +} for a space). commons-ip2 encodes the
     *       href with {@code URLEncoder} and writes the zip entry name RAW, so every payload
     *       whose name carries a space or a non-ASCII character was named one way and stored
     *       another — <b>and this verifier refused every such package this product writes</b>,
     *       which for a Japanese repository is the ordinary case, not an edge one (subagent,
     *       eleventh review, P1, measured against the real commons-ip2 jar);</li>
     *   <li>resolve against a BASE: an absolute reference ({@code /…}) against the PACKAGE
     *       ROOT and nothing else; a relative one against the METS's own directory, then the
     *       package root, then the zip root. The package root is the SHALLOWEST METS above the
     *       one that wrote the reference, asked PER METS — one root for the whole zip gave one
     *       package the other's root when a zip carried two (subagent, thirteenth review,
     *       P2);</li>
     *   <li>remove dot segments (RFC 3986 §5.2.4) and REFUSE an excess {@code ..} rather than
     *       discarding it. §5.2.4 discards it, which with a zip as the base silently turns
     *       "points outside this package" into "names a file inside it". A reference that ends
     *       up inside ANOTHER package — one with its own METS above it, sideways from this one
     *       — is refused by {@link #belongsToTheSamePackage}; there is no separate check
     *       against the IP root, and the javadoc used to promise one (subagent, eleventh and
     *       twelfth reviews, P2).</li>
     * </ol>
     *
     * <p><b>Not a suffix match.</b> "any entry whose path ends with this href" resolved one
     * METS's reference in ANOTHER METS's neighbourhood, so a package missing the file its root
     * METS names answered PASSED because an unrelated copy sat one directory over (Codex, tenth
     * review, P1). It also let a copy INSIDE the payload stand in for a metadata file the
     * package did not carry — both are the same looseness, and one rule closes both.
     */
    private static String resolve(Map<String, byte[]> entries, String metsPath, String ipRoot,
            List<String> metsPaths, String href) {
        String reference = localPathOfFileUri(withoutFragmentOrQuery(href.replace('\\', '/')));
        if (reference == null) {
            // A file: URI on another host. collectHrefs does not collect one, so this is only
            // a belt: nothing outside this package is resolved against it.
            return null;
        }
        int slash = metsPath.lastIndexOf('/');
        String directory = slash < 0 ? "" : metsPath.substring(0, slash + 1);
        for (String spelling : spellingsOf(reference)) {
            // An ABSOLUTE reference has ONE base: the IP root. This arm was written, then
            // removed on the strength of a control that did not fire — and the control did not
            // fire because its fixture had no COLLISION: nothing sat at the METS-relative
            // position, so directory-first and IP-root-only gave the same answer. With a file
            // at both, directory-first claims the wrong one (Codex, twelfth review, P1). The
            // arm is back, and the fixture beside it now has the collision.
            List<String> bases = spelling.startsWith("/") ? List.of(ipRoot)
                    : List.of(directory, ipRoot, "");
            String relative = spelling.startsWith("/") ? spelling.substring(1) : spelling;
            for (String base : bases) {
                String candidate = withoutDotSegments(base + relative);
                if (candidate != null && entries.containsKey(candidate)
                        && belongsToTheSamePackage(candidate, metsPath, metsPaths)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Is this candidate inside the package whose METS named it — or inside a DIFFERENT one?
     *
     * <p>A zip may carry two packages side by side. A reference that climbs out of its own and
     * lands in the other reported the first as closed over a file belonging to the second
     * (subagent, eleventh review, P2). The owner of a path is the DEEPEST METS above it — the
     * shallowest owned everything as soon as one METS sat at the top of the zip, which switched
     * this off entirely (subagent, twelfth review, P2). A reference is refused when that owner
     * is neither an ancestor of the writing METS nor below it.
     */
    private static boolean belongsToTheSamePackage(String candidate, String metsPath,
            List<String> metsPaths) {
        // The DEEPEST METS above the candidate owns it. Taking the shallowest meant a root
        // METS at the zip's top — whose directory is "" and prefixes everything — was the owner
        // of every path, and the check was then satisfied by every METS: a zip with one METS at
        // the top switched this off entirely (subagent, twelfth review, P2).
        String owner = null;
        for (String other : metsPaths) {
            int at = other.lastIndexOf('/');
            String directory = at < 0 ? "" : other.substring(0, at + 1);
            if (candidate.startsWith(directory)
                    && (owner == null || directory.length() > owner.length())) {
                owner = directory;
            }
        }
        if (owner == null || metsPath.startsWith(owner)) {
            return true;
        }
        // DOWNWARD is fine: a root METS pointing into a representation's own tree is the
        // ordinary CSIP mptr. What this refuses is SIDEWAYS — a reference that leaves its own
        // package and lands in another one.
        int at = metsPath.lastIndexOf('/');
        return owner.startsWith(at < 0 ? "" : metsPath.substring(0, at + 1));
    }

    /**
     * What this reference can mean, most-meant first.
     *
     * <p>A URI reference MEANS its percent-decoded form, so that is what is tried — and when the
     * reference carries a {@code %} escape, the literal spelling is NOT tried at all. Trying it
     * first let a package that lacks {@code data/a b.txt} but carries a file literally NAMED
     * {@code data/a%20b.txt} satisfy the reference with a DIFFERENT file, and a {@code %2e%2e}
     * reach a literal entry before the traversal refusal could see it (Codex, twelfth review,
     * P1). A file whose name really contains a percent sign has to be referenced as
     * {@code %25}, which is what RFC 3986 says.
     *
     * <p>The {@code +}-for-space spelling comes second: commons-ip2 encodes with
     * {@code URLEncoder}, so its packages need it, but a file genuinely named {@code a+b.txt}
     * and referenced without escapes is read literally by the first spelling.
     */
    private static List<String> spellingsOf(String reference) {
        List<String> spellings = new ArrayList<>();
        if (wouldInventSeparator(reference)) {
            // Refused outright: no decoded spelling, and no literal one either.
            return spellings;
        }
        String decoded = decodedPerSegment(reference, false);
        if (decoded != null) {
            spellings.add(decoded);
        }
        String formDecoded = decodedPerSegment(reference, true);
        if (formDecoded != null && !spellings.contains(formDecoded)) {
            spellings.add(formDecoded);
        }
        if (spellings.isEmpty()) {
            // The escaping is not well formed, so there is nothing to decode it INTO. Read it
            // as written rather than answering "names nothing".
            spellings.add(reference);
        }
        return spellings;
    }

    /**
     * The reference with each SEGMENT decoded, or null when decoding would change its shape.
     *
     * <p>Decoding the whole string let {@code %2F} become a separator and {@code ..%2F..%2F}
     * become a climb, AFTER the literal spelling had been checked and BEFORE dot segments were
     * removed — so an encoded traversal walked out of the package while the locks, whose
     * fixtures spell {@code ../} plainly, stayed green (subagent, twelfth review, P1).
     * commons-ip2 does not encode {@code /} (its safe set includes it), so nothing this product
     * writes needs that, and a reference that only means what it means after inventing a
     * separator is not one this verifier will follow.
     */
    private static String decodedPerSegment(String reference, boolean plusIsSpace) {
        String[] segments = reference.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            String decoded = percentDecoded(segments[i], plusIsSpace);
            if (decoded == null) {
                return null;
            }
            out.append(i == 0 ? "" : "/").append(decoded);
        }
        return out.toString();
    }

    /**
     * Would decoding this reference INVENT a separator or a dot segment?
     *
     * <p>Asked apart from decoding, because the two failures need different answers. A
     * MALFORMED escape means "this verifier could not read the reference", and the literal
     * spelling is then the last thing left to try. A decode that produces {@code /} or
     * {@code ..} means the reference is trying to reach further than it says — and falling back
     * to the literal after refusing it reopened the hole the refusal exists for: a package
     * carrying a file literally NAMED {@code data/%2e%2e/secret.txt} satisfied a reference to
     * {@code data/../secret.txt} (Codex, fifteenth review, P1).
     */
    private static boolean wouldInventSeparator(String reference) {
        for (String segment : reference.split("/", -1)) {
            for (boolean plusIsSpace : new boolean[] { false, true }) {
                String decoded = percentDecoded(segment, plusIsSpace);
                if (decoded != null && !segment.equals(decoded)
                        && (decoded.indexOf('/') >= 0 || decoded.equals("..")
                                || decoded.equals("."))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code %XX} decoded as UTF-8, or null when the escaping is not well formed.
     *
     * <p>Literal characters stay CHARACTERS. Writing every character through
     * {@code String.valueOf(c).getBytes(UTF_8)} split a surrogate pair into two lone surrogates
     * and turned each into {@code ?}, so a payload named 𠮟 or 📄 — outside the BMP, and
     * ordinary in Japanese personal and place names — was decoded into something else and
     * reported as a file the package does not carry (subagent, thirteenth review, P1). Only the
     * bytes of a {@code %XX} run are decoded.
     *
     * <p>And that run must be VALID UTF-8. {@code new String(bytes, UTF_8)} replaces what it
     * cannot decode with U+FFFD silently, so {@code data/%FF.txt} claimed a file literally named
     * {@code data/�.txt} — a different file satisfying the reference (Codex, thirteenth
     * review, P1).
     */
    private static String percentDecoded(String reference, boolean plusIsSpace) {
        StringBuilder out = new StringBuilder();
        java.io.ByteArrayOutputStream escaped = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c == '%') {
                if (i + 2 >= reference.length()) {
                    return null;
                }
                int high = Character.digit(reference.charAt(i + 1), 16);
                int low = Character.digit(reference.charAt(i + 2), 16);
                // BOTH digits. Multiplying the first by 16 and adding a -1 from the second is
                // positive whenever the first is 1 or more, so "%3Z" decoded to '/' and a
                // broken escape became a path separator (subagent, twelfth review, P1).
                if (high < 0 || low < 0) {
                    return null;
                }
                escaped.write(high * 16 + low);
                i += 2;
                continue;
            }
            String run = flushEscaped(escaped);
            if (run == null) {
                return null;
            }
            out.append(run);
            out.append(plusIsSpace && c == '+' ? ' ' : c);
        }
        String tail = flushEscaped(escaped);
        return tail == null ? null : out.append(tail).toString();
    }

    /** The collected {@code %XX} bytes as UTF-8, or null when they are not valid UTF-8. */
    private static String flushEscaped(java.io.ByteArrayOutputStream escaped) {
        if (escaped.size() == 0) {
            return "";
        }
        byte[] bytes = escaped.toByteArray();
        escaped.reset();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            return null;
        }
    }

    /**
     * The local path a {@code file:} URI names, or null when it names another host's file.
     *
     * <p>Three spellings are local, and they are the ones commons-ip2 writes and accepts:
     * {@code file://./x}, {@code file:///x} and {@code file://localhost/x}. Stripping the
     * authority whatever it was turned {@code file://archive.example.org/…} — a file on ANOTHER
     * MACHINE — into a claim that the package carries it (both reviewers, thirteenth review,
     * P1/P2).
     *
     * <p>The leading slash is KEPT. Dropping it made {@code file:///representations/…} a
     * RELATIVE reference, which then resolved beside the METS that wrote it instead of at the
     * package root — the same "directory-first claims a different file" defect that the
     * absolute-reference arm exists to prevent, arriving by another spelling (subagent,
     * thirteenth review, P1).
     */
    private static String localPathOfFileUri(String href) {
        if (!href.regionMatches(true, 0, "file:", 0, 5)) {
            return href;
        }
        String rest = href.substring("file:".length());
        if (!rest.startsWith("//")) {
            return rest;
        }
        int slash = rest.indexOf('/', 2);
        String authority = slash < 0 ? rest.substring(2) : rest.substring(2, slash);
        if (!authority.isEmpty() && !authority.equals(".") && !authority.equalsIgnoreCase("localhost")) {
            return null;
        }
        String path = slash < 0 ? "" : rest.substring(slash);
        return authority.equals(".") && path.startsWith("/") ? path.substring(1) : path;
    }

    private static String withoutFragmentOrQuery(String href) {
        int cut = href.length();
        for (char mark : new char[] { '#', '?' }) {
            int at = href.indexOf(mark);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return href.substring(0, cut);
    }

    /**
     * A path with {@code .} and {@code ..} segments removed, or null when it climbs out.
     *
     * <p>A METS href is a relative URI reference, and resolving one means removing dot segments.
     * Concatenating the strings instead looked for a literal
     * {@code sip/metadata/../representations/…} that no zip contains, so a third party's
     * perfectly ordinary {@code ../} reference was reported as a file the package does not
     * carry (Codex, tenth review, P2).
     *
     * <p><b>An excess {@code ..} is a refusal, not a discard.</b> §5.2.4 discards it, which is
     * right for a base with an authority; with a zip as the base it turns "points outside this
     * package" into "names a file inside it" (subagent, eleventh review, P2). The caller also
     * checks the result against the IP root, so a reference into a SIBLING package is refused
     * even when it does not climb above the zip.
     */
    private static String withoutDotSegments(String path) {
        java.util.Deque<String> out = new java.util.ArrayDeque<>();
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (out.isEmpty()) {
                    return null;
                }
                out.pollLast();
                continue;
            }
            out.addLast(segment);
        }
        return String.join("/", out);
    }

    /**
     * Is this locator a path inside the package at all?
     *
     * <p>A URI with a SCHEME names something outside it — {@code http:}, but equally the
     * {@code urn:}, {@code doi:}, {@code hdl:} and {@code ftp:} forms a METS {@code LOCTYPE}
     * exists to declare. Skipping only http and https reported a legal {@code mets:mdRef
     * LOCTYPE="URN"} as "a file the package does not carry" (subagent, eleventh review, P2).
     * {@code file:} is the exception: commons-ip2 writes and accepts it for a local path.
     */
    private static boolean isPackageLocal(String href) {
        // A WINDOWS DRIVE is one letter followed by a separator. Requiring two characters for
        // a scheme was the first attempt and refused "x:catalog-entry", which is a syntactically
        // valid URI (Codex, thirteenth review, P2); reading every "C:" as a scheme made the
        // reference vanish silently (subagent, twelfth review, P3). The drive is recognised by
        // its SHAPE instead.
        if (href.matches("^[A-Za-z][:|][/\\\\].*")) {
            return true;
        }
        // "//host/path" is a NETWORK-PATH reference (RFC 3986 §4.2): it has an authority
        // and names something on that host, so it is not a path in this package. Reading
        // it as one made an absolute package path out of it (Codex, fourteenth review, P2).
        if (namesAnotherHost(href)) {
            return false;
        }
        java.util.regex.Matcher scheme =
                java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):").matcher(href);
        if (!scheme.find()) {
            return true;
        }
        // file: is local only when its authority is this machine — an authority naming another
        // host is a file somewhere else, not one this package carries.
        return scheme.group(1).equalsIgnoreCase("file") && localPathOfFileUri(href) != null;
    }


    /**
     * The local references a METS makes — read as XML.
     *
     * <p>This matched the literal {@code xlink:href="…"}, which is wrong in both directions for
     * the reason {@code Premis} gives: a PREFIX IS NOT PART OF AN XML NAME, so a METS binding
     * the XLink namespace to another prefix produced no references at all and the closure check
     * silently became {@code NOT_PRESENT}; and the same literal inside a COMMENT was counted as
     * a reference, so a package was reported as missing a file it never named (subagent, sixth
     * review, P2).
     *
     * @return {@code null} when the document could not be read as XML — which is not the same
     *         as a METS that names nothing
     */

    static List<String> hrefsIn(String xml) {
        // Cleared at the ENTRY, so a caller that gets null back — or a second caller — never
        // reads the count the previous METS left behind (subagent, fifteenth review, P3).
        LAST_EXTERNAL.set(0);
        org.w3c.dom.Document document;
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // The same configuration Premis.read uses, for the same reason: external entity
            // resolution stays off, an internal DOCTYPE is allowed. Refusing DTDs here while
            // allowing them there meant a legitimate third-party METS could not reach P0 —
            // one half of an over-refusal corrected (Codex, seventh review, P2).
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            // INTERNAL entities are expanded. External resolution is off above, and secure
            // processing REFUSES a document whose expansion runs away — measured on a
            // billion-laughs document: rejected as JAXP00010001 in about 74 ms, answered as
            // "could not read", never as "read and empty". Leaving expansion off while allowing
            // a DOCTYPE made a digest written as an internal entity read as "records no message
            // digest" — "read and absent" for something that was not read (subagent, seventh
            // and eighth reviews, P3).
            factory.setExpandEntityReferences(true);
            document = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(
                    new java.io.StringReader(xml)));
        } catch (Exception notXml) {
            return null;
        }
        List<String> hrefs = new ArrayList<>();
        collectHrefs(document.getDocumentElement(), hrefs);
        LAST_EXTERNAL.set(externalLocatorsIn(document.getDocumentElement()));
        return hrefs;
    }

    /** The XLink namespace, which is where a METS puts {@code href}. */
    private static final String XLINK = "http://www.w3.org/1999/xlink";

    /**
     * How many locators the METS {@link #hrefsIn} last read named OUTSIDE the package.
     *
     * <p>A thread-local rather than a second return value, so the one caller that wants the
     * count gets it without changing what every other caller of {@code hrefsIn} receives.
     */
    private static final ThreadLocal<Integer> LAST_EXTERNAL = ThreadLocal.withInitial(() -> 0);

    private static void collectHrefs(org.w3c.dom.Element element, List<String> hrefs) {
        collectHrefs(element, "", hrefs);
    }

    /**
     * How many locators this METS makes that are NOT paths in the package.
     *
     * <p>They are skipped, and skipping them SILENTLY made {@code mets closure} answer PASSED
     * over a METS whose references it had not looked at — "checked and complete" for a document
     * where most of the work was declined (subagent, fourteenth review, P3). The count goes in
     * the detail.
     */
    private static int externalLocatorsIn(org.w3c.dom.Element element) {
        List<String> local = new ArrayList<>();
        collectHrefs(element, "", local);
        List<String> all = new ArrayList<>();
        collectEveryHref(element, all);
        return all.size() - local.size();
    }

    private static void collectEveryHref(org.w3c.dom.Element element, List<String> hrefs) {
        if (element == null) {
            return;
        }
        String locator = element.getAttributeNS(XLINK, "href");
        if (locator != null && !locator.isEmpty()) {
            hrefs.add(locator);
        }
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof org.w3c.dom.Element child) {
                collectEveryHref(child, hrefs);
            }
        }
    }

    /**
     * @param base the {@code xml:base} in force here, accumulated down the tree
     */
    private static void collectHrefs(org.w3c.dom.Element element, String base,
            List<String> hrefs) {
        if (element == null) {
            return;
        }
        // BACKSLASHES ARE NORMALISED HERE, once, before any rule looks at a reference. The UNC
        // rule was added to isPackageLocal only, and the three siblings that decide the base
        // (hasAuthority, merge's authority arm, the absolute-href guard) still knew the forward
        // spelling alone — so an xml:base of "\\host\share\" was DROPPED and the package's own
        // payload satisfied a reference to another machine (subagent, sixteenth review, P1).
        // One spelling in, and every rule below agrees. A Windows drive still reads as one:
        // "C:\x" becomes "C:/x", which is the shape isPackageLocal recognises.
        // xml:base, which a METS may use to say where its references start from. Ignoring it
        // sent every reference under it to the wrong directory, so a standard third-party METS
        // was reported as naming files the package does not carry (Codex, twelfth review, P2).
        String declared = element.getAttributeNS(javax.xml.XMLConstants.XML_NS_URI, "base");
        declared = declared == null ? null : declared.replace('\\', '/');
        if (declared != null && !declared.isEmpty()) {
            // MERGED per RFC 3986 §5.2.2: a base's last segment is REPLACED, not kept. Adding a
            // "/" to a relative base and nothing to an absolute one produced
            // "/representations/rep1/datap.bin" for a base ending in a segment (subagent,
            // thirteenth review, P2).
            // The href rule, applied to the base stack as well: an ABSOLUTE declared base under
            // an outer base that HAS AN AUTHORITY still belongs to that authority. Dropping the
            // outer base for every absolute one left this arm producing a package path from an
            // external URL (subagent, fifteenth review, P2).
            base = merge(hasScheme(declared)
                    || (declared.startsWith("/") && !hasAuthority(base)) ? "" : base, declared);
        }
        // The XLink namespace, never the prefix. A fallback on the literal "xlink:href" was
        // added on the reasoning that a METS without a namespace declaration still says href —
        // which is false: such a document does not parse namespace-aware at all, so the
        // fallback never fires for it. What it DID fire on is a document that binds the prefix
        // `xlink` to something else, where it produced references the METS never made and a
        // phantom "names a file the package does not carry" (subagent, seventh review, P3 —
        // the very misreading this method was rewritten to remove).
        String href = element.getAttributeNS(XLINK, "href");
        href = href == null ? null : href.replace('\\', '/');
        // A METS can point outside the package. Those are not files it is closing over and
        // reporting them as missing would turn a legitimate external reference into a failure.
        if (href != null && !href.isEmpty() && isLocalLocType(element)) {
            // An ABSOLUTE href under a base that HAS AN AUTHORITY still belongs to that
            // authority — "/x" under "https://example.invalid/archive/" is
            // "https://example.invalid/x", not a path in this package. Skipping the merge for
            // every absolute href left that arm judging the bare "/x" (subagent, fourteenth
            // review, P2).
            String merged = hasScheme(href) || base.isEmpty()
                    || (href.startsWith("/") && !hasAuthority(base))
                    ? href : merge(base, href);
            // Locality is judged on the MERGED reference. Judging the bare href and merging
            // afterwards made an xml:base of "https://example.org/" produce a package path to
            // look for, and the package was told it does not carry a URL (both reviewers,
            // thirteenth review, P2).
            if (isPackageLocal(merged)) {
                hrefs.add(merged);
            }
        }
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof org.w3c.dom.Element child) {
                collectHrefs(child, base, hrefs);
            }
        }
    }

    /**
     * The root of the package {@code metsPath} belongs to: the SHALLOWEST METS above it.
     *
     * <p><b>What does the work is the scoping</b>, not the comparison: only directories that
     * PREFIX this METS's path are considered, and among prefixes of one string, shortest and
     * shallowest are always the same. The earlier version compared every METS in the zip, and
     * then {@code a/b/} (two segments, four characters) beat {@code verylongpackage/} (one
     * segment, sixteen) and became the root of a package it is not in (both reviewers,
     * thirteenth review, P2). Depth is kept because it says what is meant; the correction was
     * the {@code startsWith} (subagent, fourteenth review, P2 — the javadoc and §9 named the
     * comparison instead, and a control aimed at it changed nothing).
     */
    private static String packageRootOf(String metsPath, List<String> metsPaths) {
        String root = directoryOf(metsPath);
        for (String other : metsPaths) {
            String directory = directoryOf(other);
            if (metsPath.startsWith(directory) && depthOf(directory) < depthOf(root)) {
                root = directory;
            }
        }
        return root;
    }

    private static String directoryOf(String path) {
        int at = path.lastIndexOf('/');
        return at < 0 ? "" : path.substring(0, at + 1);
    }

    private static int depthOf(String directory) {
        return (int) directory.chars().filter(c -> c == '/').count();
    }

    /** {@code base} with its last segment replaced by {@code reference} — RFC 3986 §5.2.2. */
    private static String merge(String base, String reference) {
        if (reference.startsWith("//")) {
            // The reference carries its OWN authority (§5.2.2, the arm before the path one), so
            // the base contributes only its scheme. Treating it as a plain "/" reference kept
            // the base's authority and produced "file://localhost//remote.example/…" (Codex,
            // fifteenth review, P1).
            int scheme = base.indexOf(':');
            return (scheme < 0 ? "" : base.substring(0, scheme + 1)) + reference;
        }
        if (reference.startsWith("/") && hasAuthority(base)) {
            // An absolute reference under a base with an authority keeps the authority and
            // replaces the WHOLE path.
            int authorityEnd = base.indexOf('/', base.indexOf("//") + 2);
            return (authorityEnd < 0 ? base : base.substring(0, authorityEnd)) + reference;
        }
        if (hasAuthority(base) && base.indexOf('/', base.indexOf("//") + 2) < 0) {
            // §5.2.3: a base with an authority and NO path merges to "/" + reference. Taking
            // the last "/" of "file://localhost" gave "file://" + reference, which then read
            // the first segment as a host (Codex, fourteenth review, P2).
            return base + "/" + reference;
        }
        int slash = base.lastIndexOf('/');
        return (slash < 0 ? "" : base.substring(0, slash + 1)) + reference;
    }

    /**
     * Does this reference carry an AUTHORITY — {@code //host/path} or {@code \\host\share}?
     *
     * <p>A network-path reference (RFC 3986 §4.2) names something on that host, so it is not a
     * path in this package; reading it as one made an absolute package path out of it (Codex,
     * fourteenth review, P2).
     *
     * <p>ONE spelling. A {@code \\host\share} arm was added here for the UNC form and then
     * REMOVED: backslashes are normalised where references are read, so this never sees one,
     * and the control aimed at the arm did not fire (measured, sixteenth review). Normalising
     * early is what makes every rule agree; a second spelling in one of them was what let the
     * others disagree.
     */
    private static boolean namesAnotherHost(String href) {
        return href.startsWith("//");
    }

    /** Does this base carry an authority — {@code scheme://host…} or {@code //host…}? */
    private static boolean hasAuthority(String base) {
        return base.startsWith("//") || base.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*");
    }

    private static boolean hasScheme(String value) {
        return value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") && !value.matches("^[A-Za-z][:|][/\\\\].*");
    }

    /**
     * Does this element's {@code LOCTYPE} say the locator is a path inside the package?
     *
     * <p>METS declares the KIND of a locator in {@code LOCTYPE}: {@code URL} is a URL,
     * {@code URN}/{@code HANDLE}/{@code DOI}/{@code PURL} are not, and {@code OTHER} with an
     * {@code OTHERLOCTYPE} is whatever the producer says. Only the scheme was read, so an
     * external locator written as a RELATIVE {@code anyURI} — which {@code LOCTYPE="OTHER"}
     * allows and the spec's own examples use — had no scheme, was taken for a package path, and
     * a legitimate third-party METS was reported as missing a file (Codex, twelfth review, P2).
     * Absent {@code LOCTYPE} means the ordinary case: a local path.
     */
    private static boolean isLocalLocType(org.w3c.dom.Element element) {
        String locType = element.getAttribute("LOCTYPE");
        if (locType == null || locType.isEmpty()) {
            return true;
        }
        // NORMALISED, like OTHERLOCTYPE beside it. Comparing the raw attribute meant a METS
        // that wrapped the line — XML normalises the newline to a space, giving "URL " — was
        // read as declaring something external, and its payload became content nobody
        // committed to (subagent, fifteenth review, P2).
        String kind = normalised(locType);
        if (kind.equals("URL")) {
            return true;
        }
        // WITHDRAWN (subagent, sixteenth review, P2/P3): the fifteenth review asked for a
        // path-word in LOCTYPE to mean what it means in OTHERLOCTYPE, on the ground that the
        // same word should not answer two ways. It should, and the reason is the spec: METS
        // ENUMERATES LOCTYPE (ARK, URN, URL, PURL, HANDLE, DOI, OTHER) and leaves OTHERLOCTYPE
        // free text. "FILE" in LOCTYPE is not a path declaration, it is a value the enumeration
        // does not have — and reading it as a path turned a relative external identifier into a
        // file the package does not carry. §9's first clause said "not URL, not counted" all
        // along; accepting the path-words contradicted it in the same line.
        if (kind.equals("OTHER")) {  // NOPMD — the one exception §9 names
            // OTHER means "whatever OTHERLOCTYPE says", and the javadoc said so while the code
            // read nothing: every OTHER locator was dropped, so a METS naming its own payload
            // that way was accused of carrying content nobody committed to (subagent,
            // thirteenth review, P1). The producer has DECLARED this is not one of the standard
            // kinds, so the default is external; it is a path only when OTHERLOCTYPE says so.
            String other = element.getAttribute("OTHERLOCTYPE");
            return other != null && A_PATH.contains(normalised(other));
        }
        // Anything else the producer DECLARED is not a URL. Listing the kinds that are not a
        // path instead let an unlisted one — "URI", which producers write — be read as a
        // package path, and a legitimate external identifier became a missing file (subagent,
        // fourteenth review, P2). §9 says "not URL, not counted"; this now says the same.
        return false;
    }

    /** Upper case with separators removed, so {@code relative_path} and {@code RELATIVE PATH}
     * are the same word. A closed list that only matched one spelling reported a payload named
     * with another as content nobody committed to (subagent, fourteenth review, P2). */
    private static String normalised(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
    }

    /** The {@code OTHERLOCTYPE} values that DO name a path, so the reference is closed over. */
    private static final java.util.Set<String> A_PATH =
            java.util.Set.of("SYSTEM", "FILE", "PATH", "RELATIVE", "RELATIVEPATH", "LOCAL");

    private static Map<String, byte[]> payloadsIn(Map<String, byte[]> entries) {
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = "/" + entry.getKey();
            if (path.contains(PAYLOAD_PREFIX_MARKER) && path.contains(PAYLOAD_DATA_MARKER)
                    && !entry.getKey().endsWith("/")) {
                payloads.put(entry.getKey(), entry.getValue());
            }
        }
        return payloads;
    }

    /**
     * The PACKAGE's own copies, not a representation's.
     *
     * <p>CSIP gives every representation its own {@code metadata/} and its own METS, at
     * {@code representations/<id>/…}. Those describe that representation, not the package, and
     * counting them made an ordinary AIP "2 PREMIS documents" — the same over-refusal the
     * payload exclusion removed, one directory up (subagent, ninth review, P2).
     *
     * <p>When there is no package-level copy the list is returned unchanged, so a package that
     * only has representation-level metadata is still read rather than treated as having none.
     */
    private static List<String> packageLevel(List<String> paths) {
        List<String> top = new ArrayList<>();
        for (String path : paths) {
            if (!("/" + path).contains("/representations/")) {
                top.add(path);
            }
        }
        return top.isEmpty() ? paths : top;
    }

    /**
     * Metadata files whose path ends with {@code suffix} — PAYLOAD excluded.
     *
     * <p>A CSIP AIP that keeps the original SIP as CONTENT carries that SIP's METS and PREMIS
     * under a representation's own data directory. Counting them made the AIP "2 PREMIS documents"
     * and {@code payload fixity} UNAVAILABLE: the nested-package over-refusal, surviving in the
     * one lookup that was not moved (subagent, eighth review, P3).
     */
    private static List<String> pathsEndingWith(Map<String, byte[]> entries, String suffix) {
        List<String> paths = new ArrayList<>();
        for (String key : entries.keySet()) {
            if (!isPayload(key) && key.endsWith(suffix)) {
                paths.add(key);
            }
        }
        return paths;
    }

}
