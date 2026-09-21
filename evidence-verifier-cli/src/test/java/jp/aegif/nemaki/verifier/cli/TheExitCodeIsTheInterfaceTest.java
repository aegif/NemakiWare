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
        return (Map<String, Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> node) {
        return (Map<String, Object>) node.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    /**
     * A validator for the subset of JSON Schema this schema uses, driven by the schema text.
     *
     * <p>Not a hand-written list of what the schema "means". The first version checked
     * required / enum / additionalProperties by hand and nothing else, so an empty
     * {@code name} passed the lock while the schema's {@code minLength: 1} would have rejected
     * it — and it demanded a non-empty {@code checks} the schema did not (both reviews, P2).
     * Everything below is read from the schema node it is validating against.
     */
    @SuppressWarnings("unchecked")
    private static void validate(Object value, Map<String, Object> node, String at,
            List<String> errors) {
        if (node.containsKey("const") && !java.util.Objects.equals(node.get("const"), value)) {
            errors.add(at + ": expected const " + node.get("const") + ", got " + value);
        }
        if (node.containsKey("enum") && !list(node.get("enum")).contains(value)) {
            errors.add(at + ": " + value + " is not in enum " + node.get("enum"));
        }
        String type = (String) node.get("type");
        if ("object".equals(type)) {
            if (!(value instanceof Map)) {
                errors.add(at + ": expected object");
                return;
            }
            Map<String, Object> object = (Map<String, Object>) value;
            for (Object required : list(node.getOrDefault("required", List.of()))) {
                if (!object.containsKey(String.valueOf(required))) {
                    errors.add(at + ": missing required " + required);
                }
            }
            Map<String, Object> props = (Map<String, Object>) node.getOrDefault("properties", Map.of());
            for (Map.Entry<String, Object> e : object.entrySet()) {
                if (props.containsKey(e.getKey())) {
                    validate(e.getValue(), (Map<String, Object>) props.get(e.getKey()),
                            at + "." + e.getKey(), errors);
                } else if (Boolean.FALSE.equals(node.get("additionalProperties"))) {
                    errors.add(at + ": undeclared key " + e.getKey());
                }
            }
        } else if ("array".equals(type)) {
            if (!(value instanceof List)) {
                errors.add(at + ": expected array");
                return;
            }
            List<Object> array = list(value);
            if (node.containsKey("minItems")
                    && array.size() < ((Number) node.get("minItems")).intValue()) {
                errors.add(at + ": fewer than minItems " + node.get("minItems"));
            }
            Map<String, Object> items = (Map<String, Object>) node.get("items");
            for (int i = 0; items != null && i < array.size(); i++) {
                validate(array.get(i), items, at + "[" + i + "]", errors);
            }
        } else if ("string".equals(type)) {
            if (!(value instanceof String)) {
                errors.add(at + ": expected string, got " + value);
                return;
            }
            if (node.containsKey("minLength")
                    && ((String) value).length() < ((Number) node.get("minLength")).intValue()) {
                errors.add(at + ": shorter than minLength " + node.get("minLength"));
            }
        }
        // if / then / else and not — the only applicators this schema uses beyond the above.
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

    @Test
    @DisplayName("--json output conforms to the published schema for EVERY profile and refusal")
    void theJsonConformsToThePublishedSchema(@TempDir Path tmp) throws Exception {
        // Every profile, plus the two refusal paths — because the first version ran one
        // package against one profile, produced no UNAVAILABLE check at all, and so never
        // reached the reasonCode branch: five codes the CLI really prints were missing from
        // the schema and this lock was green (both reviews, P1). A lock that exercises one
        // happy path measures the happy path.
        Map<String, Object> schema = schema();
        Path good = zip(tmp, "good.zip", goodPackage("the minutes"));
        Path notAZip = tmp.resolve("broken.zip");
        Files.writeString(notAZip, "this is not a zip");

        List<String> allErrors = new ArrayList<>();
        int outputs = 0;
        int unavailableSeen = 0;
        for (String profile : List.of("PACKAGE_INTEGRITY_V1", "RECORD_LEDGER_V1",
                "ANCHORED_CHECKPOINT_V1", "TRUSTED_RFC3161_V1", "ANCHORED_OTS_V1",
                "LONG_TERM_ERS_V1")) {
            for (Path sip : List.of(good, notAZip)) {
                Run result = run("verify", sip.toString(), "--profile", profile, "--json");
                assertTrue(result.code() != Verify.EXIT_USAGE && result.code() != Verify.EXIT_INTERNAL,
                        profile + " on " + sip.getFileName() + " did not produce a result: "
                                + result.err());
                Object body = jp.aegif.nemaki.verifier.Json.parse(result.out().trim());
                List<String> errors = new ArrayList<>();
                validate(body, schema, profile + "/" + sip.getFileName(), errors);
                allErrors.addAll(errors);
                outputs++;
                for (Object c : list(((Map<String, Object>) body).get("checks"))) {
                    if ("UNAVAILABLE".equals(((Map<String, Object>) c).get("outcome"))) {
                        unavailableSeen++;
                    }
                }
            }
        }
        assertEquals(12, outputs, "six profiles times two packages were meant to be exercised");
        assertTrue(unavailableSeen > 0,
                "no UNAVAILABLE check was produced across every profile and both packages, so "
                        + "the reasonCode branch of the schema was never exercised — which is "
                        + "exactly how five codes went missing from it");
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
