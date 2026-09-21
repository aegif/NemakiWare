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
 * declared registry, the schema is held equal to it by a lock in the CLI module, and this class
 * holds the registry to the code — a code nothing can construct is a code nothing can emit.
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
}
