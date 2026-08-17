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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §2's attribute-length rules (v2.3.26): the ceiling, the evidence, and — mostly — the things
 * that must NOT be shortened.
 */
public class EndpointAttributeLimitsTest {

    private static final int CEILING = EndpointAttribute.MAX_DISPLAY_CODE_UNITS;

    private static String longText(int length) {
        return "v".repeat(length);
    }

    // ---------------------------------------------------------------- truncation

    @Nested
    class Truncation {

        @Test
        public void anOverlongDisplayValueIsCutAndItsOriginalDigested() {
            String original = longText(CEILING + 500);
            LineageEndpoint endpoint = LineageEndpoint.document("bedroom", "doc-1",
                    Map.of("name", "a.txt", "versionLabel", original));

            assertEquals(CEILING, ((String) endpoint.attributes().get("versionLabel")).length());
            assertEquals(EndpointAttribute.evidenceDigest(original),
                    endpoint.attributes().get("versionLabelOriginalSha256"),
                    "the digest is of the ORIGINAL — a digest of the truncated value would"
                            + " prove nothing about what was lost");
        }

        @Test
        public void aValueAtTheCeilingIsUntouchedAndUnmarked() {
            LineageEndpoint endpoint = LineageEndpoint.document("bedroom", "doc-1",
                    Map.of("name", "a.txt", "versionLabel", longText(CEILING)));
            assertEquals(CEILING, ((String) endpoint.attributes().get("versionLabel")).length());
            assertNull(endpoint.attributes().get("versionLabelOriginalSha256"),
                    "no truncation, no evidence — presence IS the marker");
        }

        @Test
        public void aSurrogatePairIsNeverSplit() {
            // A pair straddling the ceiling: cutting at exactly CEILING would leave the high
            // half alone, which is not valid UTF-16 and hashes differently everywhere.
            String head = longText(CEILING - 1);
            String value = head + "😀" + "tail";
            LineageEndpoint endpoint = LineageEndpoint.document("bedroom", "doc-1",
                    Map.of("name", "a.txt", "folderPath", value));
            String stored = (String) endpoint.attributes().get("folderPath");
            assertEquals(CEILING - 1, stored.length(), "the cut moved back off the pair");
            assertFalse(Character.isHighSurrogate(stored.charAt(stored.length() - 1)));
        }

        @Test
        public void bothDocumentDisplayValuesTruncateIndependently() {
            String a = longText(CEILING + 1);
            String b = longText(CEILING + 2);
            LineageEndpoint endpoint = LineageEndpoint.document("bedroom", "doc-1",
                    Map.of("name", "a.txt", "versionLabel", a, "folderPath", b));
            assertEquals(EndpointAttribute.evidenceDigest(a),
                    endpoint.attributes().get("versionLabelOriginalSha256"));
            assertEquals(EndpointAttribute.evidenceDigest(b),
                    endpoint.attributes().get("folderPathOriginalSha256"));
            assertNotEquals(endpoint.attributes().get("versionLabelOriginalSha256"),
                    endpoint.attributes().get("folderPathOriginalSha256"));
        }

        @Test
        public void theArchiveNameTruncatesThroughItsFactory() {
            String original = longText(CEILING + 10);
            LineageEndpoint endpoint = LineageEndpoint.archive("bedroom", "arc-1", "doc-1",
                    1000L, original);
            assertEquals(CEILING, ((String) endpoint.attributes().get("name")).length());
            assertEquals(EndpointAttribute.evidenceDigest(original),
                    endpoint.attributes().get("nameOriginalSha256"));
        }
    }

    // ---------------------------------------------------------------- what is NOT truncated

    @Nested
    class Preserved {

        /** A required value shortened here would be a different document. */
        @Test
        public void aRequiredValueIsPassedThroughWhole() {
            String original = longText(CEILING + 100);
            LineageEndpoint endpoint = LineageEndpoint.document("bedroom", "doc-1", original);
            assertEquals(original, endpoint.attributes().get("name"));
            assertNull(endpoint.attributes().get("nameOriginalSha256"));
        }

        /**
         * §2 says an endpoint that alone exceeds the limit becomes a durable
         * {@code UNRESOLVED(OVERSIZE)}. That is the planner's job and it already does it —
         * this pins that a huge REQUIRED value still gets there rather than being shortened or
         * refused on the way.
         */
        @Test
        public void aHugeRequiredValueReachesThePlannerAsOversize() {
            // Above the 1 MiB chunk budget, below LineageFactSpool's 32 MiB record limit.
            String huge = longText(2 * 1024 * 1024);
            LineageEndpoint endpoint = LineageEndpoint.document("bedroom", "doc-1", huge);
            assertEquals(huge, endpoint.attributes().get("name"), "never shortened");

            LineageFact fact = new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED,
                    "op-1", "2026-08-01T00:00:00Z",
                    List.of(LineageEndpoint.importArtifact("bedroom", "op-1", "zip", Map.of())),
                    List.of(endpoint), List.of("atlas"), null,
                    new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                            List.of("i"), List.of("o"), Map.of(), null));
            LineageSpoolPayloadV1 payload = LineageSpoolPayloadV1.of(fact);
            assertThrows(LineageChunkPlanner.OversizeException.class, () ->
                    LineageChunkPlanner.partition(payload,
                            new LineageChunkPlanner.ChunkLimits(1000L, 1024L * 1024L),
                            "11111111-2222-3333-4444-555555555555"),
                    "the planner is the sole authority on terminal oversize");
        }

        /** An identifier, not a display value: a shortened one names a different series. */
        @Test
        public void versionSeriesIdIsNeverTruncated() {
            String original = longText(CEILING + 1);
            LineageEndpoint endpoint = LineageEndpoint.document("bedroom", "doc-1",
                    Map.of("name", "a.txt", "versionSeriesId", original));
            assertEquals(original, endpoint.attributes().get("versionSeriesId"));
        }

        /** Machine-interpreted state: a shortened state is a state nobody defined. */
        @Test
        public void archiveStateIsNeverTruncated() {
            String original = longText(CEILING + 1);
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("archivedAt", 1000L);
            attributes.put("originalObjectId", "doc-1");
            attributes.put("archiveState", original);
            LineageEndpoint endpoint = new LineageEndpoint(EndpointKind.ARCHIVE,
                    LineageEndpoint.archiveQualifiedName("bedroom", "arc-1"), "bedroom",
                    "arc-1", null, attributes);
            assertEquals(original, endpoint.attributes().get("archiveState"));
        }

        /**
         * {@code externalPath} mirrors the identity stable key under an equality the
         * constructor enforces — truncating one side would make construction throw, and the
         * producer's emit path swallows that, losing the whole fact.
         */
        @Test
        public void externalPathIsNeverTruncatedBecauseItMirrorsIdentity() {
            String path = "/mnt/" + longText(CEILING);
            LineageEndpoint endpoint = LineageEndpoint.filesystemPath("bedroom", path);
            assertEquals(path, endpoint.attributes().get("externalPath"),
                    "whole — the constructor requires it to match the stable key");
            assertTrue(((String) endpoint.attributes().get("externalStableKey")).endsWith(path),
                    "the stable key carries the same path under its source-system prefix");
        }
    }

    // ---------------------------------------------------------------- idempotence

    @Nested
    class FixedPoint {

        @Test
        public void normalizingAnAlreadyNormalizedEndpointChangesNothing() {
            String original = longText(CEILING + 77);
            LineageEndpoint once = LineageEndpoint.document("bedroom", "doc-1",
                    Map.of("name", "a.txt", "versionLabel", original));
            LineageEndpoint twice = LineageEndpoint.document("bedroom", "doc-1",
                    once.attributes());
            assertEquals(once.attributes(), twice.attributes(),
                    "a re-emit must not truncate the truncated value again — the same business"
                            + " fact would then digest two ways");
            assertEquals(LineageEventDigest.endpointRecords(List.of(once)),
                    LineageEventDigest.endpointRecords(List.of(twice)));
        }

        @Test
        public void evidenceThatDescribesSomethingElseIsRefused() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "a.txt");
            attributes.put("versionLabel", longText(CEILING + 1));
            attributes.put("versionLabelOriginalSha256", EndpointAttribute
                    .evidenceDigest("something the caller did not measure"));
            assertThrows(IllegalArgumentException.class, () ->
                    LineageEndpoint.document("bedroom", "doc-1", attributes));
        }

        @Test
        public void aMalformedCompanionIsRefused() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "a.txt");
            attributes.put("versionLabel", "short");
            attributes.put("versionLabelOriginalSha256", "not-a-digest");
            assertThrows(IllegalArgumentException.class, () ->
                    LineageEndpoint.document("bedroom", "doc-1", attributes));
        }

        @Test
        public void anOrphanCompanionIsRefused() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "a.txt");
            attributes.put("versionLabelOriginalSha256",
                    EndpointAttribute.evidenceDigest("something"));
            assertThrows(IllegalArgumentException.class, () ->
                    LineageEndpoint.document("bedroom", "doc-1", attributes),
                    "evidence without its value claims a shortening that never happened");
        }

        @Test
        public void aMalformedOrphanCompanionIsAlsoRefused() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "a.txt");
            attributes.put("versionLabelOriginalSha256", "not-a-digest");
            assertThrows(IllegalArgumentException.class, () ->
                    LineageEndpoint.document("bedroom", "doc-1", attributes));
        }

        /**
         * {@code name} is REQUIRED on a document, so it is never truncated and can never have
         * evidence. A companion for it is a claim about a shortening that cannot happen.
         */
        @Test
        public void aCompanionForANonTruncatableAttributeIsRefused() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "a.txt");
            attributes.put("nameOriginalSha256", EndpointAttribute.evidenceDigest("a.txt"));
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> LineageEndpoint.document("bedroom", "doc-1", attributes));
            assertTrue(refused.getMessage().contains("not a truncatable attribute"),
                    refused.getMessage());
        }

        @Test
        public void aCompanionIsItselfNeverTruncated() {
            // Companions are PRESERVE, so nothing ever grows OriginalSha256OriginalSha256.
            assertTrue(EndpointAttribute.isEvidenceName("versionLabelOriginalSha256"));
            assertNull(EndpointKind.CMIS_DOCUMENT
                    .attribute("versionLabelOriginalSha256OriginalSha256"));
        }
    }

    // ---------------------------------------------------------------- identity and digests

    @Nested
    class IdentityImpact {

        /**
         * Truncation touches CONTENT digests only. {@code processKey}, {@code deliveryId} and
         * {@code spoolRecordId} all hash qualified names, and no qualified name is built from
         * a truncatable attribute — so the same business fact keeps the same identity while
         * its content digest follows its content.
         */
        @Test
        public void identityIsUnchangedWhileContentDigestsFollowTheContent() {
            String original = longText(CEILING + 3);
            LineageFact truncated = factWith(original);
            LineageFact short_ = factWith("v1.0");

            LineageSpoolPayloadV1 a = LineageSpoolPayloadV1.of(truncated);
            LineageSpoolPayloadV1 b = LineageSpoolPayloadV1.of(short_);
            assertEquals(b.spoolRecordId(), a.spoolRecordId(),
                    "spoolRecordId hashes qualified names, which truncation never touches");
            assertNotEquals(b.payloadDigest(), a.payloadDigest(),
                    "the content digest follows the content");

            LineageEventV2 eventA = LineageSpoolMaterializer.v2EventOf(a, EVENT_ID);
            LineageEventV2 eventB = LineageSpoolMaterializer.v2EventOf(b, EVENT_ID);
            assertEquals(eventB.processKey(), eventA.processKey());
            assertEquals(eventB.deliveryId(), eventA.deliveryId());
            assertNotEquals(eventB.creationPayloadDigest(), eventA.creationPayloadDigest());
        }

        private static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";

        private LineageFact factWith(String versionLabel) {
            return new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED, "op-1",
                    "2026-08-01T00:00:00Z",
                    List.of(LineageEndpoint.importArtifact("bedroom", "op-1", "zip", Map.of())),
                    List.of(LineageEndpoint.document("bedroom", "doc-1",
                            Map.of("name", "a.txt", "versionLabel", versionLabel))),
                    List.of("atlas"), null,
                    new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                            List.of("i"), List.of("o"), Map.of(), null));
        }
    }

    // ---------------------------------------------------------------- the boundary holds

    @Nested
    class ProducerBoundary {

        /**
         * Normalization lives at the producer factories, so a production class that builds an
         * endpoint with the canonical constructor silently opts out of §2. The two decode
         * paths must do exactly that — they rebuild STORED records, and renormalizing one
         * would break its persisted digest — and nobody else may.
         */
        @Test
        public void onlyTheDecodePathsBuildEndpointsByHand() throws Exception {
            Path main = Path.of("src/main/java");
            // Exact relative paths, not basenames: a same-named file elsewhere in the tree
            // must not inherit an exemption meant for these three.
            List<String> allowed = List.of(
                    "jp/aegif/nemaki/rest/purview/journal/LineageEndpoint.java",
                    "jp/aegif/nemaki/rest/purview/journal/LineageSpoolCodec.java",
                    "jp/aegif/nemaki/rest/purview/journal/CouchLineageEventV2.java");
            List<String> offenders = new java.util.ArrayList<>();
            try (var walk = Files.walk(main)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String name = main.relativize(file).toString().replace('\\', '/');
                    if (allowed.contains(name)) {
                        continue;
                    }
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (source.contains("new LineageEndpoint(")) {
                        offenders.add(name);
                    }
                }
            }
            assertEquals(List.of(), offenders,
                    "these build an endpoint by hand and so skip §2's attribute limits — use"
                            + " a LineageEndpoint factory instead");
        }

        /**
         * The behaviour the exemption exists for: a STORED record whose value predates the
         * ceiling must come back unchanged and still verify. Normalizing on read would rewrite
         * it and its persisted digest would stop matching — the corruption this whole placement
         * decision avoids.
         */
        @Test
        public void theSpoolCodecDoesNotNormalizeAStoredOverlongValue() {
            LineageSpoolPayloadV1 stored = storedPayloadWithOverlongValue();
            String json = LineageSpoolCodec.encode(stored);
            LineageSpoolPayloadV1 decoded = LineageSpoolCodec.decode(json);

            assertEquals(CEILING + 40, ((String) decoded.outputs().get(0).attributes()
                    .get("versionLabel")).length(), "decode left the stored value alone");
            assertNull(decoded.outputs().get(0).attributes().get("versionLabelOriginalSha256"));
            assertTrue(decoded.selfVerifies(), "its persisted digest still verifies");
            assertEquals(stored.payloadDigest(), decoded.payloadDigest());
        }

        @Test
        public void theV2CodecDoesNotNormalizeAStoredOverlongValue() {
            LineageSpoolPayloadV1 stored = storedPayloadWithOverlongValue();
            LineageEventV2 event = LineageSpoolMaterializer.v2EventOf(stored,
                    "11111111-2222-3333-4444-555555555555");
            LineageEventV2 roundTripped =
                    CouchLineageEventV2.fromMap(CouchLineageEventV2.toMap(event));

            assertEquals(CEILING + 40, ((String) roundTripped.outputs().get(0).attributes()
                    .get("versionLabel")).length());
            assertEquals(event.creationPayloadDigest(),
                    roundTripped.creationPayloadDigest(),
                    "a renormalizing decode would have moved the content digest");
        }

        /**
         * A payload as it would sit on disk from before the ceiling existed: built through the
         * canonical constructor, which is what the decoders use, so nothing normalizes it.
         */
        private LineageSpoolPayloadV1 storedPayloadWithOverlongValue() {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", "a.txt");
            attributes.put("versionLabel", longText(CEILING + 40));
            LineageEndpoint legacy = new LineageEndpoint(EndpointKind.CMIS_DOCUMENT,
                    LineageEndpoint.objectQualifiedName("bedroom", "doc-1"), "bedroom",
                    "doc-1", null, attributes);
            return LineageSpoolPayloadV1.of(new LineageFact("bedroom",
                    LineageProcessType.IMPORT_UPLOADED, "op-1", "2026-08-01T00:00:00Z",
                    List.of(LineageEndpoint.importArtifact("bedroom", "op-1", "zip", Map.of())),
                    List.of(legacy), List.of("atlas"), null,
                    new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                            List.of("i"), List.of("o"), Map.of(), null)));
        }
    }
}
