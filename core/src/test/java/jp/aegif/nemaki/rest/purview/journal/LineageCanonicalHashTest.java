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
package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Freezes the serialization every lineage identity is built from.
 *
 * <p>The hex constants below were produced by this encoding and are checked in deliberately. They
 * are not documentation: a change to the encoding makes every {@code processKey} and
 * {@code deliveryId} already written to CouchDB unreachable, so it may only happen together with a
 * bump of {@link LineageIdentity#IDEMPOTENCY_KEY_VERSION} and a migration. A red test here is that
 * decision being made by accident.
 */
public class LineageCanonicalHashTest {

    // ------------------------------------------------------------------
    // The property the encoding exists for
    // ------------------------------------------------------------------

    /**
     * The collision that a delimiter cannot avoid.
     *
     * <p>With {@code join(":")} both of these are {@code "ab:c"} against {@code "a:bc"} only while
     * the delimiter is absent from the data — and ":" occurs in CMIS names, paths and URIs. Under
     * length prefixes the two are different byte strings regardless of content.
     */
    @Test
    public void differentSplitsOfTheSameCharactersDoNotCollide() {
        assertNotEquals(LineageCanonicalHash.hash("ab", "c"),
                LineageCanonicalHash.hash("a", "bc"));
    }

    /** {@code operationId} absent and {@code operationId} empty are not the same fact. */
    @Test
    public void nullAndEmptyStringAreDistinct() {
        assertNotEquals(LineageCanonicalHash.hash((Object) null), LineageCanonicalHash.hash(""));
    }

    /** An absent list and an empty one are likewise distinct. */
    @Test
    public void nullAndEmptyListAreDistinct() {
        assertNotEquals(LineageCanonicalHash.hash((Object) null),
                LineageCanonicalHash.hash(List.of()));
    }

    /** A one-element list is not its element — otherwise nesting would be invisible. */
    @Test
    public void listOfOneIsNotItsElement() {
        assertNotEquals(LineageCanonicalHash.hash(List.of("a")), LineageCanonicalHash.hash("a"));
    }

    /** Map iteration order is not part of the identity; keys are sorted before encoding. */
    @Test
    public void mapKeyOrderDoesNotChangeTheHash() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");
        assertEquals(LineageCanonicalHash.hash(ab), LineageCanonicalHash.hash(ba));
    }

    /** Widening is deliberate: a chunk index of 0 must hash the same whether int or long. */
    @Test
    public void integerAndLongOfTheSameValueAgree() {
        assertEquals(LineageCanonicalHash.hash(1L), LineageCanonicalHash.hash(1));
    }

    /**
     * Anything else is rejected rather than stringified. A hash whose input depends on
     * {@code toString()} silently changes identity the day someone edits a {@code toString}.
     */
    @Test
    public void unhashableTypesAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.hash(new java.util.Date(0L)));
        assertTrue(e.getMessage().contains("unhashable type"), e.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.hash(Map.of(1, "int key")));
    }

    // ------------------------------------------------------------------
    // Golden vectors — the encoding itself
    // ------------------------------------------------------------------

    /**
     * The vectors, read from the file the Python reference implementation also reads.
     *
     * <p>Sharing the fixture is what makes the cross-language claim hold over time. Constants
     * inlined here would agree with {@code reference_hash.py} only until one of the two was edited
     * — and the script, running nowhere, would not have noticed.
     *
     * <pre>
     *   python3 core/src/test/resources/lineage/reference_hash.py
     * </pre>
     *
     * <p>exits non-zero on any disagreement with the same file.
     */
    @Test
    public void goldenVectorsMatchTheSharedFixture() throws Exception {
        Map<String, String> fixture = sharedFixture();
        Map<String, String> computed = new LinkedHashMap<>();
        computed.put("hash_empty", LineageCanonicalHash.hash());
        computed.put("hash_ab_c", LineageCanonicalHash.hash("ab", "c"));
        computed.put("hash_a_bc", LineageCanonicalHash.hash("a", "bc"));
        computed.put("hash_null", LineageCanonicalHash.hash((Object) null));
        computed.put("hash_emptystring", LineageCanonicalHash.hash(""));
        computed.put("hash_list_empty", LineageCanonicalHash.hash(List.of()));
        computed.put("hash_list_a", LineageCanonicalHash.hash(List.of("a")));
        computed.put("hash_long_1", LineageCanonicalHash.hash(1L));
        computed.put("hash_long_max", LineageCanonicalHash.hash(Long.MAX_VALUE));
        computed.put("hash_long_min", LineageCanonicalHash.hash(Long.MIN_VALUE));
        computed.put("hash_long_neg1", LineageCanonicalHash.hash(-1L));
        computed.put("hash_bool_true", LineageCanonicalHash.hash(Boolean.TRUE));
        computed.put("hash_unicode", LineageCanonicalHash.hash("契約書"));
        computed.put("hash_map_ab", LineageCanonicalHash.hash(Map.of("a", "1", "b", "2")));
        computed.put("len300", LineageCanonicalHash.hash("a".repeat(300)));
        computed.put("len70000", LineageCanonicalHash.hash("a".repeat(70000)));
        computed.put("len16MiB", LineageCanonicalHash.hash("a".repeat(16 * 1024 * 1024)));

        String processKey = LineageIdentity.processKey("bedroom",
                LineageProcessType.IMPORT_UPLOADED, "op-fixed",
                List.of(LineageEndpoint.document("bedroom", "in-1", "n")),
                List.of(LineageEndpoint.document("bedroom", "out-1", "n")), 2, 0, 1);
        computed.put("processKey", processKey);
        String original = LineageIdentity.originalDeliveryId(processKey, List.of("atlas"));
        computed.put("originalDeliveryId", original);
        computed.put("replayDeliveryId",
                LineageIdentity.replayDeliveryId(original, "atlas", 1));
        computed.put("repairDeliveryId",
                LineageIdentity.repairDeliveryId("lineage_dl:fixed", 1));

        // §6-a spool identity (D-spool). Declared out of canonical order and with targets
        // needing trim/dedupe/sort — both on purpose; the formulas must normalise.
        LineageEndpoint spoolInDoc = new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                "nemaki://bedroom/objects/doc-in", "bedroom", "doc-in", null,
                java.util.Map.of("name", "契約書.txt", "versionLabel", "1.0"));
        LineageEndpoint spoolInExt = new LineageEndpoint(EndpointKind.EXTERNAL_ASSET,
                "nemaki://bedroom/external-assets/c2xhY2s6ZjE", "bedroom", null, null,
                java.util.Map.of("sourceSystem", "slack", "externalStableKey", "slack:f1"));
        LineageEndpoint spoolOutArtifact = new LineageEndpoint(EndpointKind.EXPORT_ARTIFACT,
                "nemaki://bedroom/export-artifacts/op-fixed", "bedroom", null, "op-fixed",
                java.util.Map.of("artifactKind", "ZIP", "name", "out.zip", "objectCount", 2L));
        List<LineageEndpoint> spoolInputs = List.of(spoolInDoc, spoolInExt);
        List<LineageEndpoint> spoolOutputs = List.of(spoolOutArtifact);
        String spoolRecordId = LineageSpoolIdentity.spoolRecordId("bedroom",
                LineageProcessType.IMPORT_UPLOADED, "op-fixed", spoolInputs, spoolOutputs,
                List.of(" purview", "atlas", "atlas"), 0L, 1L, "2026-08-01T00:00:00Z");
        computed.put("spoolRecordId", spoolRecordId);
        java.util.Map<String, String> legacySnapshot = new LinkedHashMap<>();
        legacySnapshot.put("importMode", "zip-upload");
        legacySnapshot.put("objectCount", "2");
        LineageFact.LegacyV1Projection legacyNoPreset = new LineageFact.LegacyV1Projection(
                LineageProcessType.IMPORT_UPLOADED,
                List.of("upload://zip-upload", "upload://zip-upload"),
                List.of("nemaki://bedroom/objects/folder-1"),
                legacySnapshot, null);
        LineageFact.LegacyV1Projection legacyPreset = new LineageFact.LegacyV1Projection(
                LineageProcessType.IMPORT_UPLOADED,
                List.of("upload://zip-upload", "upload://zip-upload"),
                List.of("nemaki://bedroom/objects/folder-1"),
                legacySnapshot, "evt-1");
        computed.put("spoolPayloadDigest_minimal", LineageSpoolIdentity.payloadDigest(
                spoolRecordId, 1L, spoolInputs, spoolOutputs, null, null));
        computed.put("spoolPayloadDigest_full", LineageSpoolIdentity.payloadDigest(
                spoolRecordId, 1L, spoolInputs, spoolOutputs, "corr-1", legacyNoPreset));
        computed.put("spoolPayloadDigest_legacyPreset", LineageSpoolIdentity.payloadDigest(
                spoolRecordId, 1L, spoolInputs, spoolOutputs, "corr-1", legacyPreset));

        // v2.3.21 (D-rest-4): v1EventDigest + the V2-domain plan digest.
        String matEventId = "11111111-2222-3333-4444-555555555555";
        computed.put("v1EventDigest_minimal", LineageSpoolIdentity.v1EventDigest(matEventId,
                "bedroom:IMPORT_UPLOADED:100:200", "bedroom",
                LineageProcessType.IMPORT_UPLOADED, List.of("upload://zip-upload"),
                List.of("nemaki://bedroom/objects/folder-1"), java.util.Map.of(),
                "2026-08-01T00:00:00Z", ""));
        String v1Full = LineageSpoolIdentity.v1EventDigest(matEventId,
                "bedroom:IMPORT_UPLOADED:100:200", "bedroom",
                LineageProcessType.IMPORT_UPLOADED,
                List.of("upload://zip-upload", "upload://zip-upload"),
                List.of("nemaki://bedroom/objects/folder-1"), legacySnapshot,
                "2026-08-01T00:00:00Z", "corr-1");
        computed.put("v1EventDigest_full", v1Full);
        String factDigestFull = computed.get("spoolPayloadDigest_full");
        computed.put("materializationPlanDigest_v1",
                LineageSpoolIdentity.materializationPlanDigest(spoolRecordId, factDigestFull,
                        1, matEventId, List.of(
                                new jp.aegif.nemaki.rest.purview.journal
                                        .LineageMaterializationDecision.V1Entry(matEventId,
                                        v1Full).asRecord())));
        computed.put("materializationPlanDigest_v2",
                LineageSpoolIdentity.materializationPlanDigest(spoolRecordId, factDigestFull,
                        2, "22222222-3333-4444-5555-666666666666", List.of(
                                new jp.aegif.nemaki.rest.purview.journal
                                        .LineageMaterializationDecision.V2Entry(0,
                                        "d".repeat(64), "e".repeat(64)).asRecord())));

        assertEquals(fixture.keySet(), computed.keySet(),
                "the fixture and this test cover different vectors");
        for (Map.Entry<String, String> entry : computed.entrySet()) {
            assertEquals(fixture.get(entry.getKey()), entry.getValue(),
                    entry.getKey() + " no longer matches the shared fixture — every processKey and"
                            + " deliveryId already written is now unreachable");
        }
    }

    /** Minimal reader: the fixture is a flat object of string values, checked in beside the script. */
    private static Map<String, String> sharedFixture() throws Exception {
        String json = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/test/resources/lineage/identity-golden-vectors.json"),
                java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> vectors = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (matcher.find()) {
            vectors.put(matcher.group(1), matcher.group(2));
        }
        assertEquals(29, vectors.size(), "unexpected fixture size: " + vectors.size());
        return vectors;
    }

    /**
     * Each of these pins one rule of the encoding. Together they catch a changed type tag, a
     * changed length width, a flipped byte order and a dropped UTF-8 step, which a
     * self-consistency test comparing two calls to the same method would all miss.
     */
    @Test
    public void goldenVectors() {
        // no parts: LIST(0)
        assertEquals("a665e6b115dd56fd3e0c89be631e6eda8e9666b822e0bd7026bf0822c4bbc68f",
                LineageCanonicalHash.hash());
        // string length prefix
        assertEquals("14bacf0c3af5f2736b210f0edb9e7e12caabdb4c763bab2754209e1fc20d4a02",
                LineageCanonicalHash.hash("ab", "c"));
        assertEquals("6e7c1d9fe517528d9a880ae855040033a961e483bffb67edf7572d07f43a9fa2",
                LineageCanonicalHash.hash("a", "bc"));
        // null tag
        assertEquals("9d0689e46d7c710571256af5b8e8638f0dbc6b008f5ea4688c1c70f3005943e4",
                LineageCanonicalHash.hash((Object) null));
        assertEquals("ca28a8559e1114f44c49b1aa3f956ed12e1a4be970e5aa43cde5ca59792f35e7",
                LineageCanonicalHash.hash(""));
        // list count prefix
        assertEquals("27d8a154dde600ce538d04a901471b5ec6652c3881a9a418cf3d63234e02feae",
                LineageCanonicalHash.hash(List.of()));
        assertEquals("e74231b56629413037452b5f8435820d98db459a48043e89d5e0a1b760514df5",
                LineageCanonicalHash.hash(List.of("a")));
        // integer width and byte order
        assertEquals("46cb5d92d64dfd94961c89587d55ebaf54bee80d4db929a45e5566d452382917",
                LineageCanonicalHash.hash(1L));
        assertEquals("b8fa50c6a012291fb71c66a059be4c34eb1d78388aae4726c132b18157439356",
                LineageCanonicalHash.hash(Long.MAX_VALUE));
        assertEquals("d2db404374a9874c647ee397c625f3715bcc4ecf0af9f196daa9beff144d3ab8",
                LineageCanonicalHash.hash(Long.MIN_VALUE));
        assertEquals("fc29b622875162bd06a8f5a531337346378c966c53a730a32a614aa2ededeb64",
                LineageCanonicalHash.hash(-1L));
        // bool tag
        assertEquals("6c614b295f14d90080516a9007bc0f473fcc8cb5f860354b6cc6c9f2ca7ab521",
                LineageCanonicalHash.hash(Boolean.TRUE));
        // UTF-8, not the platform charset and not UTF-16
        assertEquals("880b823ac4e0231efaa5ec90885bdf7ef3306e81ae9a42c4e964cd9f9913c2b3",
                LineageCanonicalHash.hash("契約書"));
        // map, keys sorted
        assertEquals("7ac485b14cd2a455f161023678fa408ae2c27319bebdb9bd4f8e510a78abb592",
                LineageCanonicalHash.hash(Map.of("a", "1", "b", "2")));
    }

    /**
     * The upper bytes of the 4-byte length prefix.
     *
     * <p>Every other vector here uses inputs under 256 bytes, where the top three length bytes are
     * all zero — so a shift by the wrong amount would leave them all green. 300 bytes exercises
     * the second byte, 70,000 the third, and 16 MiB the most significant one. The last costs
     * about 40 ms and is the only way to reach that byte at all, since {@code (n << 24) & 0xFF}
     * and {@code (n >>> 24) & 0xFF} agree for every value below it.
     */
    @Test
    public void goldenVectorsForLongValues() {
        assertEquals("419cc03152cac8e49eccc392b034049468c2f300039f3d2dbaa40c1ab9abc1be",
                LineageCanonicalHash.hash("a".repeat(300)));
        assertEquals("e5d26b5415e396a4bebc6e9ab84e6c0c6e4e19d1a41449e4aea62cbbb1309e1f",
                LineageCanonicalHash.hash("a".repeat(70000)));
        assertEquals("4b4cd84bbb9d603f281bc01b44544b0770404f489e22a1c501fc296d4509de8f",
                LineageCanonicalHash.hash("a".repeat(16 * 1024 * 1024)));
    }

    /** A null map key cannot be encoded, and the rejection must say so rather than throw NPE. */
    @Test
    public void aNullMapKeyIsRejected() {
        Map<String, Object> withNullKey = new HashMap<>();
        withNullKey.put(null, "v");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.hash(withNullKey));
        assertTrue(e.getMessage().contains("null"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // Canonical orderings
    // ------------------------------------------------------------------

    @Test
    public void qualifiedNamesAreSortedSoProducerOrderDoesNotMatter() {
        LineageEndpoint a = LineageEndpoint.document("bedroom", "a", "n");
        LineageEndpoint b = LineageEndpoint.document("bedroom", "b", "n");
        assertEquals(LineageCanonicalHash.canonicalQualifiedNames(List.of(a, b)),
                LineageCanonicalHash.canonicalQualifiedNames(List.of(b, a)));
    }

    /**
     * A repeated endpoint is a producer bug, not something to collapse: silently deduplicating it
     * would change the arity the catalog sees without anyone noticing.
     */
    @Test
    public void duplicateQualifiedNamesAreRejectedRatherThanDeduplicated() {
        LineageEndpoint a = LineageEndpoint.document("bedroom", "a", "n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.canonicalQualifiedNames(List.of(a, a)));
        assertTrue(e.getMessage().contains("duplicate endpoint"), e.getMessage());
    }

    @Test
    public void nullEndpointIsRejected() {
        List<LineageEndpoint> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.canonicalQualifiedNames(withNull));
    }

    /**
     * Targets are deduplicated, unlike endpoints: {@code ["atlas","atlas"]} is one delivery
     * obligation, and configuration listing a target twice is not a data error.
     */
    @Test
    public void targetSetIsSortedTrimmedAndDeduplicated() {
        assertEquals(List.of("atlas", "purview"),
                LineageCanonicalHash.canonicalTargetSet(List.of("purview", " atlas ", "atlas")));
    }

    /**
     * A blank target is rejected rather than dropped. Dropping it would produce a deliveryId for a
     * smaller target set than the one actually being delivered to, and the record would then
     * disagree with its own {@code publishStatusByTarget}.
     */
    @Test
    public void blankTargetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.canonicalTargetSet(List.of("atlas", " ")));

        List<String> withNull = new ArrayList<>();
        withNull.add("atlas");
        withNull.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.canonicalTargetSet(withNull));
    }

    /** A null set is a caller bug, and should say so rather than surface as a NullPointerException. */
    @Test
    public void aNullTargetSetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.canonicalTargetSet(null));
    }

    /**
     * Sorting is by unsigned UTF-8 byte order, which is the same rule in every language.
     *
     * <p>Java's natural String order would put these the other way round: U+1D400 is encoded in
     * UTF-16 as a surrogate pair starting 0xD835, which compares below U+FF21, while in UTF-8 it
     * starts 0xF0 and U+FF21 starts 0xEF. A repair tool written in Python or Go sorts by code
     * point and would agree with the UTF-8 answer, not the UTF-16 one.
     */
    @Test
    public void sortingIsByUnsignedUtf8ByteOrder() {
        assertEquals(List.of("Ａ", "𝐀"),
                LineageCanonicalHash.canonicalTargetSet(List.of("𝐀", "Ａ")));
        assertEquals(List.of("Ａ", "𝐀"),
                LineageCanonicalHash.canonicalTargetSet(List.of("Ａ", "𝐀")));
    }

    /** A prefix sorts before what extends it; length is the tie-break after the shared bytes. */
    @Test
    public void aPrefixSortsBeforeTheLongerString() {
        assertEquals(List.of("a", "ab", "abc"),
                LineageCanonicalHash.canonicalTargetSet(List.of("abc", "a", "ab")));
    }

    /** ASCII is where every ordering rule agrees, which is why no golden vector moved. */
    @Test
    public void asciiOrderingIsUnaffected() {
        assertEquals(List.of("atlas", "dataplex", "purview"),
                LineageCanonicalHash.canonicalTargetSet(List.of("purview", "atlas", "dataplex")));
    }

    @Test
    public void aNullEndpointListIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LineageCanonicalHash.canonicalQualifiedNames(null));
    }
}
