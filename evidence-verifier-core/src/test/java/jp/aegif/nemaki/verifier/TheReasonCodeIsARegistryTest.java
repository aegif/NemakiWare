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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reason code reaches a reader only through {@link Outcome.Check}'s constructor, and the
 * constructor accepts only the registry (R66).
 *
 * <p>These are the guards that make the published schema's {@code reasonCode} enum complete.
 * The enum used to be derived by grepping the sources and missed five codes; now the set is a
 * declared registry, the schema is held equal to it by {@link #theSchemaEnumIsTheRegistry}
 * HERE (in the registry's own module — a CLI-side lock reads the installed core jar and never
 * sees a change to this file), and the guards below hold the registry to the code — a code
 * nothing can construct is a code nothing can emit.
 */
class TheReasonCodeIsARegistryTest {

    @Test
    @DisplayName("an unregistered reason code cannot be constructed")
    void anUnregisteredCodeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> Outcome.Check.unavailable("some check", "NOT_A_REGISTERED_CODE", "why"),
                "a reason code outside REASON_CODES was accepted. It would reach the JSON, "
                        + "and the published schema — kept equal to the registry — would "
                        + "reject the result");
    }

    @Test
    @DisplayName("a reason code on anything but UNAVAILABLE is refused")
    void aReasonOnAPassedCheckIsRefused() {
        // The schema says reasonCode is present exactly when UNAVAILABLE. Saying so in a
        // description while the constructor accepted PASSED + reason was the document being
        // stronger than the code (both reviews, P2).
        assertThrows(IllegalArgumentException.class,
                () -> new Outcome.Check("some check", Outcome.PASSED, "UNKNOWN_ALGORITHM", null));
        assertThrows(IllegalArgumentException.class,
                () -> new Outcome.Check("some check", Outcome.FAILED, "UNKNOWN_ALGORITHM", "d"));
    }

    @Test
    @DisplayName("every refusal the package reader can raise is a registered code")
    void everyRefusalIsRegistered() {
        // These four are the codes the first derivation missed: they travel as
        // refusal.reasonCode(), a method call, which no grep for a string literal sees.
        for (PackageReader.Refusal refusal : PackageReader.Refusal.values()) {
            assertTrue(Outcome.Check.REASON_CODES.contains(refusal.name()),
                    refusal.name() + " is a refusal the reader raises and is not in the "
                            + "registry, so the CLI's 'package readable' check could not be "
                            + "constructed for it");
        }
    }

    @Test
    @DisplayName("the published schema's reasonCode enum IS the registry, in both directions")
    void theSchemaEnumIsTheRegistry() throws java.io.IOException {
        // Lives HERE, in the module that owns the registry, and not in the CLI: the CLI's tests
        // run against the INSTALLED core jar, so a sabotage of this file never reached a lock
        // over there — control LU3 stayed green while the registry gained a code the schema
        // lacked (measured). A lock has to sit where the thing it reads can be changed under it.
        java.nio.file.Path schema =
                java.nio.file.Path.of("../docs/evidence-profile/v1/verifier-result.schema.json");
        assertTrue(java.nio.file.Files.exists(schema), "the result schema is not at " + schema);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> root = (java.util.Map<String, Object>) Json.parse(
                java.nio.file.Files.readString(schema, java.nio.charset.StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> items = (java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) ((java.util.Map<String, Object>)
                        root.get("properties")).get("checks")).get("items");
        @SuppressWarnings("unchecked")
        java.util.List<Object> listed = (java.util.List<Object>) ((java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) items.get("properties")).get("reasonCode")).get("enum");
        java.util.SortedSet<String> inSchema = new java.util.TreeSet<>();
        for (Object code : listed) {
            inSchema.add(String.valueOf(code));
        }
        java.util.SortedSet<String> registered = new java.util.TreeSet<>(Outcome.Check.REASON_CODES);
        org.junit.jupiter.api.Assertions.assertEquals(registered, inSchema,
                "the schema's reasonCode enum and Outcome.Check.REASON_CODES differ. A code the "
                        + "registry has and the schema lacks is a result a receiving party's "
                        + "validator rejects; one the schema has and nothing emits is a branch "
                        + "they write for nothing. Registry: " + registered + " / schema: "
                        + inSchema);
    }

    @Test
    @DisplayName("every reason code written as a literal in the sources is registered")
    void everyLiteralCodeIsRegistered() throws java.io.IOException {
        // One direction, and a SUBSET on purpose. The constructor guard is a runtime check and
        // only fires on a branch a test actually reaches; a typo in a branch nothing exercises
        // would ship, and the first package to reach it in the field would turn "could not
        // check, here is why" (exit 3) into "the program failed" (exit 5). This scan catches
        // the literal form at build time regardless of which branches run. It cannot see a code
        // passed through a variable — for that the guard and everyRefusalIsRegistered remain —
        // and that is the safe side: a literal it cannot read is not a false alarm, it is a
        // literal that does not exist (review, P2).
        java.util.SortedSet<String> literals = new java.util.TreeSet<>();
        java.util.regex.Pattern call = java.util.regex.Pattern.compile(
                "unavailable\\(\\s*[^,]+,\\s*\"([A-Z_]+)\"", java.util.regex.Pattern.DOTALL);
        for (java.nio.file.Path root : java.util.List.of(java.nio.file.Path.of("src/main/java"),
                java.nio.file.Path.of("../evidence-verifier-cli/src/main/java"))) {
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    // Comments stripped first: the registry's own javadoc shows the call with
                    // "CODE" as a placeholder, and the scan read it as a literal (measured).
                    String text = java.nio.file.Files.readString(file,
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .replaceAll("(?m)//.*$", "")
                            .replaceAll("(?s)/\\*.*?\\*/", "");
                    java.util.regex.Matcher m = call.matcher(text);
                    while (m.find()) {
                        literals.add(m.group(1));
                    }
                }
            }
        }
        assertTrue(literals.size() >= 10, "the literal scan found only " + literals
                + "; the pattern no longer matches how unavailable(...) is written and this "
                + "would pass by finding nothing");
        java.util.SortedSet<String> unregistered = new java.util.TreeSet<>(literals);
        unregistered.removeAll(Outcome.Check.REASON_CODES);
        assertTrue(unregistered.isEmpty(),
                "reason codes written in the sources are not in the registry: " + unregistered
                        + ". Whichever branch first reaches one in the field exits 5 instead of "
                        + "answering 3 with a reason");
    }

    @Test
    @DisplayName("the documents that state the registry's size state the measured size")
    void theDocumentsStateTheMeasuredSize() throws java.io.IOException {
        // "17 値" was hand-written in three documents by the same batch whose design document
        // says hand-copied tables always go stale (review, P3). Now it is read.
        int size = Outcome.Check.REASON_CODES.size();
        for (String doc : java.util.List.of("../docs/design/verifier-result-schema.md",
                "../docs/design/fail-closed-reads.md",
                "../docs/operations/evidence-verifier-release.md")) {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(doc),
                    java.nio.charset.StandardCharsets.UTF_8);
            // ONE form: 「REASON_CODES（N 値）」 or 「登録簿 … （N 値）」. A looser "any N 値 near
            // reasonCode" read the design document's "5 値を落とした" — the five that were
            // MISSING — as the registry's size (measured).
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:REASON_CODES|登録簿)[^（。]{0,40}（(\\d+) 値）").matcher(text);
            int stated = 0;
            while (m.find()) {
                stated++;
                org.junit.jupiter.api.Assertions.assertEquals(size, Integer.parseInt(m.group(1)),
                        doc + " states the registry as " + m.group(1) + " values and it has " + size);
            }
            assertTrue(stated >= 1, doc + " no longer states the registry's size in the form "
                    + "「REASON_CODES（N 値）」, so its number has drifted out of this check's reach");
        }
    }
}
