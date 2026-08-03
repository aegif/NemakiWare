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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * §6-a's rollout fence, as a typed value (A-2 Slice 4a).
 *
 * <p>One document, {@code _id = lineage_write_version}, in the journal's own database. It
 * carries two version axes — {@code writeSchemaVersion}, which an operator may move 1 ⇄ 2, and
 * {@code minReaderSchemaVersion}, which only ever increases — plus the ACK set that
 * {@code PREPARING → ACTIVE} checks in one CAS.
 *
 * <p><b>What this type refuses and what it does not.</b> It refuses shapes that are
 * structurally impossible: an unknown state, a version outside {1, 2}, a negative generation,
 * an ACK without its required fields, and the pair {@code writeSchemaVersion == 2} with
 * {@code minReaderSchemaVersion == 1} that CAS condition 11 exists to make unreachable. It
 * does NOT refuse a stale-generation ACK: a decode that dropped those could not diagnose CAS
 * condition 3, and "the persisted document says something the activation must reject" is a
 * verdict, not a parse error. Monotonicity of {@code minReaderSchemaVersion} is likewise a
 * TRANSITION rule — no decoder can know what the previous revision held — and is enforced
 * against the {@code _rev} being written.
 */
public record LineageWriteVersionBarrier(
        String rev,
        State state,
        long generation,
        int writeSchemaVersion,
        int minReaderSchemaVersion,
        List<NodeRef> expectedNodes,
        String expectedMembershipDigest,
        Set<String> requiredCapabilities,
        Set<String> approvedBinaryDigests,
        Map<String, Ack> acks) {

    /** The document id: exactly one barrier exists per journal database. */
    public static final String DOCUMENT_ID = "lineage_write_version";

    /** The witness document id — see {@code LineageBarrierReader}. */
    public static final String WITNESS_DOCUMENT_ID = "lineage_barrier_witness";

    /** The node-identity document id — allocated on first prepare/ack, never at startup. */
    public static final String NODE_IDENTITY_DOCUMENT_ID = "lineage_node_identity";

    /** Default ACK freshness window (§6-a's frozen persistence contract). */
    public static final long DEFAULT_ACK_TTL_MS = 300_000L;

    /** The membership digest's domain — frozen, golden-vectored, never a hash of JSON text. */
    public static final String MEMBERSHIP_DOMAIN = "BARRIER_MEMBERSHIP_V1";

    /** IDLE: created but not collecting. PREPARING: collecting ACKs. ACTIVE: promoted. */
    public enum State { IDLE, PREPARING, ACTIVE }

    /** One expected member: which node, in which boot. */
    public record NodeRef(String nodeId, String bootId) {
        public NodeRef {
            requireText(nodeId, "nodeId");
            requireText(bootId, "bootId");
        }

        Map<String, Object> asRecord() {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("nodeId", nodeId);
            record.put("bootId", bootId);
            return record;
        }
    }

    /**
     * One node's answer, bound to the generation it answered. Without {@code generation} the
     * design's own sentence — "bumping the generation invalidates the old ACKs" — would not
     * hold, because nothing in the ACK would disagree with the new document.
     */
    public record Ack(
            long generation,
            String bootId,
            String binaryDigest,
            Set<String> capabilities,
            Set<Integer> readSchemaVersions,
            Set<Integer> spoolRecordSchemaVersions,
            Set<Integer> materializeEventSchemaVersions,
            boolean spoolReady,
            boolean drestReady,
            List<String> drestViolations,
            long ackedAtMs,
            long expiresAtMs) {

        public Ack {
            if (generation < 0) {
                throw new IllegalArgumentException("ack generation must be >= 0");
            }
            requireText(bootId, "bootId");
            requireText(binaryDigest, "binaryDigest");
            capabilities = Set.copyOf(capabilities);
            readSchemaVersions = Set.copyOf(readSchemaVersions);
            spoolRecordSchemaVersions = Set.copyOf(spoolRecordSchemaVersions);
            materializeEventSchemaVersions = Set.copyOf(materializeEventSchemaVersions);
            drestViolations = List.copyOf(drestViolations);
            if (ackedAtMs <= 0 || expiresAtMs <= 0) {
                throw new IllegalArgumentException("ackedAt/expiresAt are epoch millis and"
                        + " must be positive");
            }
            if (expiresAtMs <= ackedAtMs) {
                throw new IllegalArgumentException("an ACK that expires at or before it was"
                        + " written is never fresh");
            }
            if (drestReady && !drestViolations.isEmpty()) {
                throw new IllegalArgumentException("drestReady with violations is a"
                        + " contradiction");
            }
        }

        boolean isFresh(long nowMs) {
            return nowMs < expiresAtMs;
        }
    }

    public LineageWriteVersionBarrier {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0");
        }
        requireVersion(writeSchemaVersion, "writeSchemaVersion");
        requireVersion(minReaderSchemaVersion, "minReaderSchemaVersion");
        if (writeSchemaVersion == 2 && minReaderSchemaVersion == 1) {
            // Condition 11 promotes both flags in ONE write precisely so this pair cannot
            // exist; a type that can hold it would let a decode resurrect it.
            throw new IllegalArgumentException("writeSchemaVersion=2 with"
                    + " minReaderSchemaVersion=1 is the state the atomic promotion exists to"
                    + " make impossible");
        }
        expectedNodes = List.copyOf(expectedNodes);
        requiredCapabilities = Set.copyOf(requiredCapabilities);
        approvedBinaryDigests = Set.copyOf(approvedBinaryDigests);
        acks = Map.copyOf(acks);
    }

    /** The frozen membership digest of {@code expectedNodes}. */
    public String computeMembershipDigest() {
        return membershipDigestOf(expectedNodes);
    }

    /**
     * {@code hash("BARRIER_MEMBERSHIP_V1", LIST[MAP{nodeId, bootId}])}, the list sorted by
     * {@code nodeId} in unsigned UTF-8 order. Never a hash of serialized JSON.
     */
    public static String membershipDigestOf(List<NodeRef> nodes) {
        List<NodeRef> sorted = new ArrayList<>(nodes);
        sorted.sort((a, b) -> compareUnsignedUtf8(a.nodeId(), b.nodeId()));
        List<Object> records = new ArrayList<>();
        for (NodeRef node : sorted) {
            records.add(new TreeMap<>(node.asRecord()));
        }
        return LineageCanonicalHash.hash(MEMBERSHIP_DOMAIN, records);
    }

    private static int compareUnsignedUtf8(String a, String b) {
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int limit = Math.min(left.length, right.length);
        for (int i = 0; i < limit; i++) {
            int diff = Byte.toUnsignedInt(left[i]) - Byte.toUnsignedInt(right[i]);
            if (diff != 0) {
                return diff;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static void requireVersion(int version, String field) {
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException(field + " must be 1 or 2, got " + version);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
