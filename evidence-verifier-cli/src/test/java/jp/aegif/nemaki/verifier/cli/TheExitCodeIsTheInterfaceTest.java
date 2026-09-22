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
import java.util.ArrayList;
import java.util.List;
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
                // The namespace IS declared, as PremisWriter declares it: the reader parses
                // rather than string-matches, so an unbound prefix would not be PREMIS at all.
                "<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\">"
                        + "<premis:object><premis:objectCharacteristics><premis:fixity>"
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


    // ------------------------------------------------------------------------------------
    // The JSON is the interface too (R66). The schema at docs/evidence-profile/v1/ is what a
    // receiving party validates the output against with THEIR tools; this module never reads
    // it at run time (a validator would be one more library they have to trust). These locks
    // keep the schema and the implementation pointing at the same shape.
    // ------------------------------------------------------------------------------------

    private static final Path SCHEMA =
            Path.of("../docs/evidence-profile/v1/verifier-result.schema.json");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema() throws Exception {
        assertTrue(Files.exists(SCHEMA), "the result schema is not at " + SCHEMA);
        Object parsed = jp.aegif.nemaki.verifier.Json.parse(
                Files.readString(SCHEMA, StandardCharsets.UTF_8));
        assertTrue(parsed instanceof Map, "the schema is not a JSON object");
        Map<String, Object> root = (Map<String, Object>) parsed;
        // The WHOLE tree is checked for keywords this validator does not implement, before any
        // instance is validated. Checking only the nodes an instance happens to visit left an
        // optional property with a `pattern` unchecked as long as no fixture emitted it — and
        // the day the CLI did, a receiving party's validator would refuse what every lock
        // here had passed (Codex review, P2).
        preflight(root, "$");
        return root;
    }

    @SuppressWarnings("unchecked")
    private static void preflight(Map<String, Object> node, String at) {
        for (Map.Entry<String, Object> keyword : node.entrySet()) {
            String key = keyword.getKey();
            if (!ANNOTATIONS.contains(key) && !IMPLEMENTED.contains(key)) {
                throw new IllegalStateException("the schema uses the keyword '" + key + "' at "
                        + at + ", which this validator does not implement");
            }
            if (key.equals("additionalProperties") && !(keyword.getValue() instanceof Boolean)) {
                // A schema-valued additionalProperties would be read as "allowed" by the
                // boolean check below, which is the one place this validator would report
                // an unread constraint as no constraint (review, P3).
                throw new IllegalStateException("additionalProperties at " + at + " is a "
                        + "schema, which this validator does not implement");
            }
        }
        Object props = node.get("properties");
        if (props instanceof Map) {
            for (Map.Entry<String, Object> p : ((Map<String, Object>) props).entrySet()) {
                preflight((Map<String, Object>) p.getValue(), at + "." + p.getKey());
            }
        }
        for (String sub : List.of("items", "if", "then", "else", "not")) {
            if (node.get(sub) instanceof Map) {
                preflight((Map<String, Object>) node.get(sub), at + "/" + sub);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> node) {
        return (Map<String, Object>) node.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    /** Keywords that carry no constraint. Anything else the validator does not implement is refused. */
    private static final java.util.Set<String> ANNOTATIONS =
            java.util.Set.of("$schema", "$id", "title", "description");

    /** Keywords this validator implements. A schema using any other keyword makes it throw. */
    private static final java.util.Set<String> IMPLEMENTED = java.util.Set.of("type", "enum",
            "const", "minLength", "minItems", "required", "additionalProperties", "properties",
            "items", "if", "then", "else", "not");

    /**
     * A validator for the subset of JSON Schema this schema uses, driven by the schema text.
     *
     * <p>Applicators are INSTANCE-driven, as in JSON Schema: {@code required} and
     * {@code properties} apply whenever the value is an object, {@code items} whenever it is an
     * array, {@code minLength} whenever it is a string — independent of whether the node also
     * says {@code type}. The first version evaluated them only under {@code type: "object"} (and
     * the array/string keywords likewise), so the schema's {@code if}/{@code then}/{@code else}
     * /{@code not} nodes — which carry no {@code type} — were never evaluated at all: {@code if}
     * always matched, {@code then} checked nothing, and {@code not} would have refused every
     * non-UNAVAILABLE check had {@code else} ever been reached. Two defects cancelling into
     * green (both reviews, P1).
     *
     * <p>Unknown keywords are refused, not skipped. A keyword this validator does not implement
     * would otherwise be read as "no constraint" — a could-not-check reported as passed, the
     * defect this branch is named after, in the lock that guards the schema.
     */
    @SuppressWarnings("unchecked")
    private static void validate(Object value, Map<String, Object> node, String at,
            List<String> errors) {
        for (String key : node.keySet()) {
            if (!ANNOTATIONS.contains(key) && !IMPLEMENTED.contains(key)) {
                throw new IllegalStateException("the schema uses the keyword '" + key + "' at "
                        + at + ", which this validator does not implement. Skipping it would "
                        + "report 'conforms' for a constraint nobody checked");
            }
        }
        if (node.containsKey("const") && !java.util.Objects.equals(node.get("const"), value)) {
            errors.add(at + ": expected const " + node.get("const") + ", got " + value);
        }
        if (node.containsKey("enum") && !list(node.get("enum")).contains(value)) {
            errors.add(at + ": " + value + " is not in enum " + node.get("enum"));
        }
        if (node.containsKey("type")) {
            String type = String.valueOf(node.get("type"));
            boolean ok = switch (type) {
                case "object" -> value instanceof Map;
                case "array" -> value instanceof List;
                case "string" -> value instanceof String;
                default -> throw new IllegalStateException("type '" + type + "' at " + at
                        + " is not one this validator implements");
            };
            if (!ok) {
                errors.add(at + ": expected " + type + ", got "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
            }
        }
        if (value instanceof Map) {
            Map<String, Object> object = (Map<String, Object>) value;
            for (Object required : list(node.getOrDefault("required", List.of()))) {
                if (!object.containsKey(String.valueOf(required))) {
                    errors.add(at + ": missing required " + required);
                }
            }
            Map<String, Object> props = (Map<String, Object>) node.getOrDefault("properties", Map.of());
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                if (props.containsKey(entry.getKey())) {
                    validate(entry.getValue(), (Map<String, Object>) props.get(entry.getKey()),
                            at + "." + entry.getKey(), errors);
                } else if (Boolean.FALSE.equals(node.get("additionalProperties"))) {
                    errors.add(at + ": undeclared key " + entry.getKey());
                }
            }
        }
        if (value instanceof List) {
            List<Object> array = list(value);
            if (node.containsKey("minItems")
                    && array.size() < ((Number) node.get("minItems")).intValue()) {
                errors.add(at + ": fewer than minItems " + node.get("minItems"));
            }
            Map<String, Object> items = (Map<String, Object>) node.get("items");
            for (int i = 0; items != null && i < array.size(); i++) {
                validate(array.get(i), items, at + "[" + i + "]", errors);
            }
        }
        if (value instanceof String && node.containsKey("minLength")
                && ((String) value).length() < ((Number) node.get("minLength")).intValue()) {
            errors.add(at + ": shorter than minLength " + node.get("minLength"));
        }
        if (node.containsKey("if")) {
            List<String> ifErrors = new ArrayList<>();
            validate(value, (Map<String, Object>) node.get("if"), at, ifErrors);
            Object branch = ifErrors.isEmpty() ? node.get("then") : node.get("else");
            if (branch != null) {
                validate(value, (Map<String, Object>) branch, at, errors);
            }
        }
        if (node.containsKey("not")) {
            List<String> notErrors = new ArrayList<>();
            validate(value, (Map<String, Object>) node.get("not"), at, notErrors);
            if (notErrors.isEmpty()) {
                errors.add(at + ": matches a 'not' schema " + node.get("not"));
            }
        }
    }

    /** The per-check schema node (checks.items). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkItems() throws Exception {
        return (Map<String, Object>) ((Map<String, Object>)
                properties(schema()).get("checks")).get("items");
    }

    @Test
    @DisplayName("the schema's reasonCode rule actually discriminates, judged by the schema")
    void theReasonCodeRuleDiscriminates() throws Exception {
        // The two objects the if/then/else exists to reject, run through the schema's own
        // per-check node. Expected values come from the schema, not from a hand-written list:
        // empty the schema's `then` and this goes red. The first validator let both through
        // (both reviews, P1).
        Map<String, Object> items = checkItems();
        List<String> missingReason = new ArrayList<>();
        validate(Map.of("name", "x", "outcome", "UNAVAILABLE"), items, "unavailable-no-reason",
                missingReason);
        assertTrue(!missingReason.isEmpty(),
                "an UNAVAILABLE check with no reasonCode passed the schema's per-check node, so "
                        + "the if/then rule is not being evaluated");
        List<String> reasonOnPassed = new ArrayList<>();
        validate(Map.of("name", "x", "outcome", "PASSED", "reasonCode", "UNKNOWN_ALGORITHM"),
                items, "passed-with-reason", reasonOnPassed);
        assertTrue(!reasonOnPassed.isEmpty(),
                "a PASSED check carrying a reasonCode passed the schema's per-check node, so "
                        + "the else/not rule is not being evaluated");
        // And the honest pair passes, or the rule refuses everything.
        List<String> fine = new ArrayList<>();
        validate(Map.of("name", "x", "outcome", "UNAVAILABLE", "reasonCode", "UNKNOWN_ALGORITHM"),
                items, "unavailable-with-reason", fine);
        validate(Map.of("name", "x", "outcome", "PASSED"), items, "passed-plain", fine);
        assertTrue(fine.isEmpty(), "a conforming check was refused: " + fine);
    }

    /** A zip with more entries than the reader admits — the RESOURCE_LIMIT refusal. */
    private static Path tooManyEntries(Path dir) throws Exception {
        Path file = dir.resolve("too-many.zip");
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i <= jp.aegif.nemaki.verifier.PackageReader.MAX_ENTRIES; i++) {
                zip.putNextEntry(new ZipEntry(ROOT + "e" + i));
                zip.closeEntry();
            }
        }
        return file;
    }

    /** Two entries with one name — ZipOutputStream refuses to write that, so the bytes are patched. */
    private static Path duplicateEntries(Path dir) throws Exception {
        Path file = dir.resolve("duplicate.zip");
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
            for (int j = 0; j < from.length && match; j++) {
                match = raw[i + j] == from[j];
            }
            if (match) {
                System.arraycopy(to, 0, raw, i, to.length);
            }
        }
        Files.write(file, raw);
        return file;
    }

    @Test
    @DisplayName("--json output conforms to the published schema for every profile and every refusal")
    void theJsonConformsToThePublishedSchema(@TempDir Path tmp) throws Exception {
        // Every profile the CLI knows (read from the CLI, not copied), each asked to echo the
        // profile it was given; and one package per refusal the reader can raise. What this
        // exercises is the CLI's own reason-code path (refusal.reasonCode()) plus the ledger
        // profiles' LEGACY_PACKAGE_LAYOUT — five codes of the registry's seventeen. The other
        // twelve need anchor material the good package does not carry; their SHAPE is held
        // by the schema-driven validator and the registry, not by this fixture. The first
        // version ran one package against six profiles and called it twelve outputs — the
        // non-zip is refused BEFORE the profile branch, so that was one case six times, and
        // only NOT_A_ZIP and LEGACY_PACKAGE_LAYOUT ever appeared (both reviews, P2).
        Map<String, Object> schema = schema();
        Path good = zip(tmp, "good.zip", goodPackage("the minutes"));
        Path notAZip = tmp.resolve("broken.zip");
        Files.writeString(notAZip, "this is not a zip");
        Map<String, String> traversal = new LinkedHashMap<>(goodPackage("the minutes"));
        traversal.put("../escape.txt", "x");
        Path unsafe = zip(tmp, "unsafe.zip", traversal);
        Map<Path, String> refusals = new LinkedHashMap<>();
        refusals.put(notAZip, "NOT_A_ZIP");
        refusals.put(unsafe, "UNSAFE_PATH");
        refusals.put(duplicateEntries(tmp), "DUPLICATE_ENTRY");
        refusals.put(tooManyEntries(tmp), "RESOURCE_LIMIT");
        // One fixture per refusal the reader can raise — tied to the enum, so a fifth refusal
        // added there is a red run here, not a silently unexercised code (review, P3).
        java.util.Set<String> everyRefusal = new java.util.TreeSet<>();
        for (jp.aegif.nemaki.verifier.PackageReader.Refusal r
                : jp.aegif.nemaki.verifier.PackageReader.Refusal.values()) {
            everyRefusal.add(r.name());
        }
        assertEquals(everyRefusal, new java.util.TreeSet<>(refusals.values()),
                "the reader can raise a refusal this test has no package for");

        List<String> allErrors = new ArrayList<>();
        java.util.SortedSet<String> codesSeen = new java.util.TreeSet<>();
        for (String profile : Verify.KNOWN_PROFILES) {
            Run result = run("verify", good.toString(), "--profile", profile, "--json");
            assertTrue(result.code() != Verify.EXIT_USAGE && result.code() != Verify.EXIT_INTERNAL,
                    profile + " did not produce a result: " + result.err());
            Map<String, Object> body = (Map<String, Object>) jp.aegif.nemaki.verifier.Json.parse(
                    result.out().trim());
            assertEquals(profile, body.get("profile"),
                    "the output does not echo the profile it was asked for");
            validate(body, schema, profile + "/good.zip", allErrors);
            for (Object c : list(body.get("checks"))) {
                Object code = ((Map<String, Object>) c).get("reasonCode");
                if (code != null) {
                    codesSeen.add(String.valueOf(code));
                }
            }
        }
        for (Map.Entry<Path, String> refusal : refusals.entrySet()) {
            Run result = run("verify", refusal.getKey().toString(), "--json");
            assertEquals(Verify.EXIT_INDETERMINATE, result.code(),
                    refusal.getValue() + " did not come back INDETERMINATE: " + result.err());
            Map<String, Object> body = (Map<String, Object>) jp.aegif.nemaki.verifier.Json.parse(
                    result.out().trim());
            validate(body, schema, refusal.getValue(), allErrors);
            java.util.Set<Object> codes = new java.util.HashSet<>();
            for (Object c : list(body.get("checks"))) {
                codes.add(((Map<String, Object>) c).get("reasonCode"));
            }
            assertTrue(codes.contains(refusal.getValue()),
                    "the " + refusal.getKey().getFileName() + " package was meant to exercise "
                            + refusal.getValue() + " and the output carries " + codes
                            + " — the branch this fixture exists for was not reached");
            codesSeen.add(refusal.getValue());
        }
        assertTrue(codesSeen.contains("LEGACY_PACKAGE_LAYOUT"),
                "no profile produced LEGACY_PACKAGE_LAYOUT on the legacy good package, so the "
                        + "ledger profiles' UNAVAILABLE path went unexercised: " + codesSeen);
        assertTrue(allErrors.isEmpty(),
                "output the CLI really prints does not conform to the published schema. A "
                        + "receiving party validating with it would reject these results:\n  "
                        + String.join("\n  ", allErrors));
    }

    @Test
    @DisplayName("the schema is closed, pins the limits, and is versioned in its id")
    void theSchemaIsClosedAndPinsTheLimits() throws Exception {
        Map<String, Object> schema = schema();
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"),
                "the top level admits undeclared keys");
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>)
                properties(schema).get("checks")).get("items");
        assertEquals(Boolean.FALSE, items.get("additionalProperties"),
                "a check admits undeclared keys");
        assertTrue(list(schema.get("required")).contains("limits"),
                "limits is not required. A schema that lets the limits go is a schema that "
                        + "accepts a CLI which dropped them");
        Map<String, Object> limits = (Map<String, Object>) properties(schema).get("limits");
        assertTrue(((Number) limits.get("minLength")).intValue() >= 1,
                "limits may be empty under the schema");
        assertTrue(String.valueOf(schema.get("$id")).contains("/v1/"),
                "the schema's $id does not carry the profile version");
        // Deliberately NOT an enum (owner's decision): the CLI refuses unknown profiles with
        // exit 4 before any output exists, so the schema closing it too would only mean two
        // lists to keep equal.
        Map<String, Object> profile = (Map<String, Object>) properties(schema).get("profile");
        assertTrue(!profile.containsKey("enum"), "profile became an enum");
    }
}
