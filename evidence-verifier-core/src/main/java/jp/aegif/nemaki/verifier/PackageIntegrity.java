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
        List<String> premisPaths = pathsEndingWith(entries, "premis.xml");
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
        if (fixity.digests().size() > 1) {
            return Outcome.Check.unavailable("payload fixity", "AMBIGUOUS_PREMIS",
                    "the PREMIS records " + fixity.digests().size() + " message digests and "
                            + "this verifier cannot tell which describes the payload");
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
            return Outcome.Check.unavailable("payload fixity", "AMBIGUOUS_PAYLOAD",
                    "the package carries " + payloads.size() + " payloads and one recorded "
                            + "digest, so the one-to-one PREMIS requires does not hold");
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
        List<String> metsPaths = pathsEndingWith(entries, "METS.xml");
        if (metsPaths.isEmpty()) {
            return Outcome.Check.absent("mets closure", "the package carries no METS");
        }
        List<String> named = new ArrayList<>();
        for (String metsPath : metsPaths) {
            String mets = new String(entries.get(metsPath), StandardCharsets.UTF_8);
            List<String> hrefs = hrefsIn(mets);
            if (hrefs == null) {
                // The package HAS a METS and this verifier could not read it. Saying the METS
                // names nothing would turn that into a fact about the package.
                return Outcome.Check.unavailable("mets closure", "METS_NOT_PARSED",
                        "the package presents " + metsPath + " as a METS and this verifier "
                                + "could not read it as XML, so what it names is unknown");
            }
            named.addAll(hrefs);
        }
        if (named.isEmpty()) {
            return Outcome.Check.absent("mets closure",
                    "the METS names no files, so there is nothing to close over");
        }

        List<String> missing = new ArrayList<>();
        for (String href : named) {
            if (!resolves(entries, href)) {
                missing.add(href);
            }
        }
        if (!missing.isEmpty()) {
            return Outcome.Check.failed("mets closure",
                    "the METS names " + missing.size() + " file(s) the package does not carry: "
                            + missing);
        }

        List<String> unnamed = new ArrayList<>();
        for (String path : payloadsIn(entries).keySet()) {
            boolean claimed = named.stream().anyMatch(href -> path.endsWith(trimLeading(href)));
            if (!claimed) {
                unnamed.add(path);
            }
        }
        if (!unnamed.isEmpty()) {
            return Outcome.Check.failed("mets closure",
                    "the package carries payload the METS does not name: " + unnamed
                            + ". Content nobody committed to travels inside a package that "
                            + "would otherwise verify");
        }
        return Outcome.Check.passed("mets closure");
    }

    private static boolean resolves(Map<String, byte[]> entries, String href) {
        String wanted = trimLeading(href);
        return entries.keySet().stream().anyMatch(path -> path.endsWith(wanted));
    }

    private static String trimLeading(String href) {
        String value = href.replace('\\', '/');
        while (value.startsWith("./") || value.startsWith("/")) {
            value = value.startsWith("./") ? value.substring(2) : value.substring(1);
        }
        return value;
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
        org.w3c.dom.Document document;
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            document = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(
                    new java.io.StringReader(xml)));
        } catch (Exception notXml) {
            return null;
        }
        List<String> hrefs = new ArrayList<>();
        collectHrefs(document.getDocumentElement(), hrefs);
        return hrefs;
    }

    /** The XLink namespace, which is where a METS puts {@code href}. */
    private static final String XLINK = "http://www.w3.org/1999/xlink";

    private static void collectHrefs(org.w3c.dom.Element element, List<String> hrefs) {
        if (element == null) {
            return;
        }
        String href = element.getAttributeNS(XLINK, "href");
        if (href == null || href.isEmpty()) {
            // A METS written without a namespace declaration still says href.
            href = element.getAttribute("xlink:href");
        }
        // A METS can point outside the package. Those are not files it is closing over and
        // reporting them as missing would turn a legitimate external reference into a failure.
        if (href != null && !href.isEmpty()
                && !href.startsWith("http://") && !href.startsWith("https://")) {
            hrefs.add(href);
        }
        org.w3c.dom.NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof org.w3c.dom.Element child) {
                collectHrefs(child, hrefs);
            }
        }
    }

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

    private static List<String> pathsEndingWith(Map<String, byte[]> entries, String suffix) {
        List<String> paths = new ArrayList<>();
        for (String key : entries.keySet()) {
            if (key.endsWith(suffix)) {
                paths.add(key);
            }
        }
        return paths;
    }

}
