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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third implementation, against the same vectors the other two read.
 *
 * <p>Phase 2 put two implementations on those vectors: the product, and a Python reference
 * written from the specification. This module is the third, and it is the one that matters for
 * the claim — it is what a receiving organisation would actually run, and it shares no code
 * with the writer.
 *
 * <p>The vectors live where plan §10 says they belong — {@code docs/evidence-profile/v1/vectors/}
 * — and all three readers open THAT file. One file with three readers; a copy per module would
 * be a vector file that can disagree with itself.
 */
class ThreeImplementationsAgreeTest {

    private static final Path VECTORS =
            Path.of("../docs/evidence-profile/v1/vectors/profile-v1-vectors.json");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vectors() throws Exception {
        assertTrue(Files.exists(VECTORS), "the shared vectors are not at " + VECTORS
                + ". This module must be held to the SAME file as the product and the Python "
                + "reference; a copy of its own would let the two drift and still agree");
        return (Map<String, Object>) Json.parse(
                Files.readString(VECTORS, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("this verifier computes what the shared vectors record")
    void theVerifierAgreesWithTheVectors() throws Exception {
        Map<String, Object> vectors = vectors();
        Map<String, Object> expected = (Map<String, Object>) vectors.get("expected");
        Map<String, String> computed = new java.util.LinkedHashMap<>();

        ((Map<String, Object>) vectors.get("canonicalHash")).forEach((name, raw) -> {
            List<Object> parts = (List<Object>) ((Map<String, Object>) raw).get("parts");
            computed.put(name, Canonical.hash(parts.toArray()));
        });
        ((Map<String, Object>) vectors.get("entryHash")).forEach((name, raw) -> {
            Map<String, Object> spec = (Map<String, Object>) raw;
            computed.put(name, Canonical.hash("LEDGER_ENTRY_V1", spec.get("domain"),
                    spec.get("sequence"), spec.get("subjectKind"), spec.get("subjectId"),
                    spec.get("payloadDigest"), spec.get("occurredAt"),
                    spec.get("prevEntryHash")));
        });
        ((Map<String, Object>) vectors.get("checkpointHash")).forEach((name, raw) -> {
            Map<String, Object> spec = (Map<String, Object>) raw;
            computed.put(name, Canonical.hash("LEDGER_CHECKPOINT_V1", spec.get("domain"),
                    spec.get("fromSequence"), spec.get("toSequence"), spec.get("merkleRoot"),
                    spec.get("prevCheckpointHash"), spec.get("createdAt")));
        });
        ((Map<String, Object>) vectors.get("merkle")).forEach((name, raw) -> {
            List<String> leaves = (List<String>) ((Map<String, Object>) raw).get("leaves");
            computed.put(name, Merkle.root(leaves));
        });
        ((Map<String, Object>) vectors.get("documentDigest")).forEach((name, raw) -> {
            String json = String.valueOf(((Map<String, Object>) raw).get("json"));
            computed.put(name, Canonical.documentDigest(Json.parse(json)));
        });

        List<String> wrong = new ArrayList<>();
        expected.forEach((name, value) -> {
            String actual = computed.get(name);
            if (actual == null) {
                wrong.add(name + ": the vectors expect a value this verifier never computed");
            } else if (!actual.equals(value)) {
                wrong.add(name + ":\n    verifier " + actual + "\n    vectors  " + value);
            }
        });
        computed.keySet().forEach(name -> {
            if (!expected.containsKey(name)) {
                wrong.add(name + ": computed but absent from the expected map");
            }
        });
        assertTrue(wrong.isEmpty(), "an implementation that shares no code with the writer "
                + "disagrees with it. Either the specification is wrong or it is incomplete, "
                + "and both mean a third party gets different answers:\n  "
                + String.join("\n  ", wrong));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("this verifier refuses the documents the spec says have no canonical form")
    void theVerifierRefusesWhatTheSpecRefuses() throws Exception {
        Map<String, Object> refusals = (Map<String, Object>) vectors().get("documentRefusals");
        List<String> accepted = new ArrayList<>();
        refusals.forEach((name, raw) -> {
            Map<String, Object> spec = (Map<String, Object>) raw;
            try {
                Canonical.documentDigest(Json.parse(String.valueOf(spec.get("json"))));
                accepted.add(name + " (" + spec.get("because") + ")");
            } catch (Json.NotCanonicalisable | Canonical.NotEncodable expected) {
                // The refusal is the behaviour under test.
            }
        });
        assertTrue(accepted.isEmpty(),
                "a verifier that accepts a document with two readings has committed to "
                        + "whichever its parser chose:\n  " + String.join("\n  ", accepted));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("this verifier's proof check agrees with the vectors, both directions")
    void theProofRoundTripAgrees() throws Exception {
        Map<String, Object> proof = (Map<String, Object>) vectors().get("proof");
        List<Merkle.Step> steps = new ArrayList<>();
        for (Object raw : (List<Object>) proof.get("path")) {
            Map<String, Object> step = (Map<String, Object>) raw;
            steps.add(new Merkle.Step(String.valueOf(step.get("siblingHash")),
                    Boolean.TRUE.equals(step.get("siblingIsLeft"))));
        }
        String leaf = String.valueOf(proof.get("leaf"));
        assertTrue(Merkle.verifies(leaf, steps, String.valueOf(proof.get("root"))),
                "the spec's steps do not reach the recorded root");
        assertEquals(false, Merkle.verifies(leaf, steps, "0".repeat(64)),
                "and a wrong root was accepted, which would make every proof pass");
    }
}
