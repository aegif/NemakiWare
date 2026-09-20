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
package jp.aegif.nemaki.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence profile v1 spec, measured against the product AND against an implementation
 * written from the spec alone (plan Phase 2).
 *
 * <h2>The gate this answers</h2>
 *
 * <p>Phase 2's gate is that a third party can write a verifier from the spec without the
 * product. A document cannot demonstrate that; the only demonstration is a second
 * implementation that reads the same vectors and agrees. {@code reference_verify.py} is written
 * from {@code docs/design/evidence-profile-v1.md} — tags, big-endian length prefixes, hex
 * concatenation at the nodes — and this test RUNS it.
 *
 * <p>The lineage side established this pattern with {@code reference_hash.py}, with one
 * difference worth naming: that script is run by hand, so between runs it says nothing. This
 * one is executed on every build, and a machine without {@code python3} FAILS rather than
 * passing quietly — "could not check the cross-language claim" is not "the claim holds".
 */
class EvidenceProfileV1VectorsTest {

    private static final Path VECTORS =
            Path.of("src/test/resources/evidence/profile-v1-vectors.json");
    private static final Path REFERENCE =
            Path.of("src/test/resources/evidence/reference_verify.py");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vectors() throws Exception {
        assertTrue(Files.exists(VECTORS), "the shared vectors are not at " + VECTORS);
        return (Map<String, Object>) jp.aegif.nemaki.config.ObjectMapperFactory
                .createDefaultObjectMapper()
                .readValue(Files.readString(VECTORS, StandardCharsets.UTF_8), Map.class);
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** What the product computes for every vector in the shared file. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> computedByTheProduct(Map<String, Object> vectors) {
        Map<String, String> computed = new LinkedHashMap<>();

        ((Map<String, Object>) vectors.get("canonicalHash")).forEach((name, raw) -> {
            List<Object> parts = (List<Object>) ((Map<String, Object>) raw).get("parts");
            computed.put(name, LineageCanonicalHash.hash(parts.toArray()));
        });

        ((Map<String, Object>) vectors.get("entryHash")).forEach((name, raw) -> {
            Map<String, Object> spec = (Map<String, Object>) raw;
            computed.put(name, EvidenceLedgerEntry.computeEntryHash(
                    asText(spec.get("domain")), asLong(spec.get("sequence")),
                    spec.get("subjectKind") == null ? null
                            : EvidenceLedgerEntry.SubjectKind.valueOf(asText(spec.get("subjectKind"))),
                    asText(spec.get("subjectId")), asText(spec.get("payloadDigest")),
                    asText(spec.get("occurredAt")), asText(spec.get("prevEntryHash"))));
        });

        ((Map<String, Object>) vectors.get("checkpointHash")).forEach((name, raw) -> {
            Map<String, Object> spec = (Map<String, Object>) raw;
            computed.put(name, EvidenceCheckpoint.computeHash(
                    asText(spec.get("domain")), asLong(spec.get("fromSequence")),
                    asLong(spec.get("toSequence")), asText(spec.get("merkleRoot")),
                    asText(spec.get("prevCheckpointHash")), asText(spec.get("createdAt"))));
        });

        ((Map<String, Object>) vectors.get("merkle")).forEach((name, raw) -> {
            List<String> leaves = (List<String>) ((Map<String, Object>) raw).get("leaves");
            computed.put(name, MerkleTree.root(leaves));
        });

        return computed;
    }

    @Test
    @DisplayName("the product computes exactly what the shared vectors record")
    @SuppressWarnings("unchecked")
    void theProductAgreesWithTheVectors() throws Exception {
        Map<String, Object> vectors = vectors();
        Map<String, String> expected = (Map<String, String>) vectors.get("expected");
        Map<String, String> computed = computedByTheProduct(vectors);

        List<String> wrong = new ArrayList<>();
        expected.forEach((name, value) -> {
            String actual = computed.get(name);
            if (actual == null) {
                wrong.add(name + ": the file expects a value nothing computed");
            } else if (!actual.equals(value)) {
                wrong.add(name + ":\n    product " + actual + "\n    file    " + value);
            }
        });
        computed.keySet().forEach(name -> {
            if (!expected.containsKey(name)) {
                wrong.add(name + ": computed but absent from the expected map");
            }
        });

        assertTrue(wrong.isEmpty(), "the product and docs/design/evidence-profile-v1.md's "
                + "vectors disagree. These vectors are what a third party implements against, "
                + "so a change here is a change to what every external verifier must do:\n  "
                + String.join("\n  ", wrong));
    }

    @Test
    @DisplayName("an implementation written from the spec alone gets the same values")
    void theSpecIsEnoughToReimplement() throws Exception {
        assertTrue(Files.exists(REFERENCE), "the reference implementation is not at " + REFERENCE);

        ProcessBuilder python = new ProcessBuilder("python3", REFERENCE.toString());
        python.redirectErrorStream(true);
        Process process;
        try {
            process = python.start();
        } catch (java.io.IOException noPython) {
            // NOT a skip. The whole point of this test is the cross-language claim; a build that
            // cannot check it must not report that it holds.
            throw new AssertionError("python3 is not available, so the claim that the spec is "
                    + "enough to reimplement from could not be checked here. It is not a claim "
                    + "this build may pass on faith.", noPython);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();

        assertEquals(0, status, "an implementation written from the spec disagrees with the "
                + "product. Either the spec is wrong, or it is incomplete, and both mean a third "
                + "party writing a verifier from it gets different answers:\n" + output);
    }
}
