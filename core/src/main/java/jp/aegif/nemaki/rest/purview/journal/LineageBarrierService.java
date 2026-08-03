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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * §6-a's rollout state machine, in its v3.3 normative form: <b>one AP</b> (A-2 Slice 4a).
 *
 * <p>The multi-node barrier in §6-a stays on the page as future design; what is implemented is
 * the single-AP subset the design froze — {@code expectedNodes} is this node and nothing else,
 * CAS conditions 1 and 3–11 apply, and condition 2 (membership revision) does not, because a
 * single AP has no membership to change. {@code prepare} therefore takes <b>no membership
 * argument</b>: there is no route by which an operator can invent, empty or extend the set.
 *
 * <h3>Every mutation is a reread-recompute loop</h3>
 *
 * <p>{@code ack} and {@code activate} read at a fresh {@code _rev}, validate, compute
 * everything the write depends on AT THAT MOMENT — readiness, capabilities, digest, clocks —
 * and CAS once against that revision. <b>A 409 restarts the whole loop, recomputation
 * included.</b> Replaying a previously computed ACK onto a document it never read is how a
 * stale readiness verdict, an expired freshness window or a superseded generation gets
 * committed.
 */
@Component
public class LineageBarrierService {

    private static final Logger logger = LoggerFactory.getLogger(LineageBarrierService.class);

    private static final int MAX_CAS_ATTEMPTS = 5;

    /** The spool RECORD versions this build reads and writes (the codec admits only 1). */
    private static final Set<Integer> SPOOL_RECORD_SCHEMA_VERSIONS = Set.of(1);

    /** The EVENT versions this build can materialize a spooled fact into. */
    private static final Set<Integer> MATERIALIZE_EVENT_SCHEMA_VERSIONS = Set.of(1, 2);

    /** The outcome of a barrier operation: the new view, or the exact violations. */
    public record BarrierOutcome(boolean applied, LineageWriteVersionBarrier barrier,
                                 List<String> violations) {
        public BarrierOutcome {
            violations = List.copyOf(violations);
        }

        static BarrierOutcome ok(LineageWriteVersionBarrier barrier) {
            return new BarrierOutcome(true, barrier, List.of());
        }

        static BarrierOutcome refused(String... violations) {
            return new BarrierOutcome(false, null, List.of(violations));
        }

        static BarrierOutcome refused(List<String> violations) {
            return new BarrierOutcome(false, null, violations);
        }
    }

    @Autowired(required = false)
    private LineageBarrierStore store;

    @Autowired(required = false)
    private LineageBarrierReader reader;

    @Autowired(required = false)
    private LineageNodeIdentity identity;

    @Autowired(required = false)
    private LineageBinaryDigest binaryDigest;

    @Autowired(required = false)
    private LineageCapabilityProvider capabilities;

    @Autowired(required = false)
    private LineageDrestReadiness readiness;

    @Autowired(required = false)
    private LineageConfig lineageConfig;

    @Autowired(required = false)
    private LineageSpoolMachinery spoolMachinery;

    private java.util.function.LongSupplier clockMs = System::currentTimeMillis;

    public LineageBarrierService() {
    }

    LineageBarrierService(LineageBarrierStore store, LineageBarrierReader reader,
                          LineageNodeIdentity identity, LineageBinaryDigest binaryDigest,
                          LineageCapabilityProvider capabilities,
                          LineageDrestReadiness readiness, LineageConfig lineageConfig,
                          LineageSpoolMachinery spoolMachinery,
                          java.util.function.LongSupplier clockMs) {
        this.store = store;
        this.reader = reader;
        this.identity = identity;
        this.binaryDigest = binaryDigest;
        this.capabilities = capabilities;
        this.readiness = readiness;
        this.lineageConfig = lineageConfig;
        this.spoolMachinery = spoolMachinery;
        this.clockMs = clockMs;
    }

    /** The required capability set is a CONSTANT of this binary; a document cannot narrow it. */
    public Set<String> requiredCapabilities() {
        return capabilities == null ? Set.of() : capabilities.wiredCapabilities();
    }

    /**
     * Creates or re-arms the barrier for this node.
     *
     * <p>{@code approvedBinaryDigests} and {@code additionalRequiredCapabilities} are optional:
     * {@code null} PRESERVES what the document already holds, so re-preparing cannot silently
     * drop an allowlist an operator installed; an explicitly empty list clears it.
     */
    public synchronized BarrierOutcome prepare(Set<String> approvedBinaryDigests,
            Set<String> additionalRequiredCapabilities) {
        if (store == null || identity == null) {
            return BarrierOutcome.refused("the barrier machinery is not wired on this node");
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            LineageWriteVersionBarrier current = readBarrier();
            long generation;
            Set<String> approved;
            Set<String> required;
            if (current == null) {
                generation = 1L;
                approved = approvedBinaryDigests == null ? Set.of() : approvedBinaryDigests;
                required = union(requiredCapabilities(), additionalRequiredCapabilities);
            } else {
                if (current.state() == LineageWriteVersionBarrier.State.ACTIVE
                        && current.writeSchemaVersion() != 1) {
                    return BarrierOutcome.refused("re-arming an ACTIVE barrier requires"
                            + " writeSchemaVersion == 1 — roll back first");
                }
                generation = Math.addExact(current.generation(), 1L);
                approved = approvedBinaryDigests == null ? current.approvedBinaryDigests()
                        : approvedBinaryDigests;
                // null PRESERVES the document's set; an explicit set REPLACES the operator's
                // additions. The binary's baseline is unioned in either way and can never be
                // narrowed by a document.
                required = additionalRequiredCapabilities == null
                        ? union(requiredCapabilities(), current.requiredCapabilities())
                        : union(requiredCapabilities(), additionalRequiredCapabilities);
            }
            List<LineageWriteVersionBarrier.NodeRef> nodes = List.of(identity.selfRef());
            LineageWriteVersionBarrier next = new LineageWriteVersionBarrier(
                    current == null ? null : current.rev(),
                    LineageWriteVersionBarrier.State.PREPARING,
                    generation,
                    current == null ? 1 : current.writeSchemaVersion(),
                    current == null ? 1 : current.minReaderSchemaVersion(),
                    nodes,
                    LineageWriteVersionBarrier.membershipDigestOf(nodes),
                    required, approved,
                    // Re-arming collects fresh ACKs: the old ones go in the SAME write as the
                    // generation bump, so no window exists in which a stale ACK could count.
                    Map.of());
            if (current == null && !store.writeWitnessIfAbsent(clockMs.getAsLong())) {
                // The witness precedes the barrier, always: a barrier that became durable
                // without one could be deleted and the deployment would look pristine again.
                // If it cannot be made durable, no barrier is created.
                return BarrierOutcome.refused("the barrier witness could not be made durable"
                        + " — refusing to create a barrier whose deletion would be"
                        + " undetectable");
            }
            if (writeBarrier(next)) {
                return BarrierOutcome.ok(reread());
            }
        }
        return BarrierOutcome.refused("the barrier changed under every attempt — retry");
    }

    /**
     * Records THIS node's ACK, computed fresh at the revision it is written against.
     *
     * <p>A red readiness gate, an unmeasurable distribution, a state other than
     * {@code PREPARING} or a generation mismatch all refuse WITHOUT touching the document:
     * condition 10 demands fresh readiness at ACK time, and persisting a red ACK so that
     * activation can reject it later would be recording a fact we already know is wrong.
     */
    public synchronized BarrierOutcome ack() {
        if (store == null || identity == null || capabilities == null) {
            return BarrierOutcome.refused("the barrier machinery is not wired on this node");
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            LineageWriteVersionBarrier current = readBarrier();
            if (current == null) {
                return BarrierOutcome.refused("no barrier exists — prepare one first");
            }
            if (current.state() != LineageWriteVersionBarrier.State.PREPARING) {
                return BarrierOutcome.refused("the barrier is " + current.state()
                        + ", and only a PREPARING barrier collects ACKs");
            }
            String nodeId = identity.nodeId();
            if (current.expectedNodes().stream().noneMatch(n -> n.nodeId().equals(nodeId))) {
                return BarrierOutcome.refused("this node (" + nodeId + ") is not in"
                        + " expectedNodes");
            }
            // Everything the write depends on, computed at THIS revision.
            LineageDrestReadiness.Readiness verdict = readiness == null
                    ? new LineageDrestReadiness.Readiness(false,
                            List.of("no readiness gate is wired"))
                    : readiness.evaluate();
            if (!verdict.ready()) {
                return BarrierOutcome.refused(prefix("readiness: ", verdict.violations()));
            }
            String digest;
            try {
                digest = binaryDigest == null ? null : binaryDigest.digest();
            } catch (LineageBinaryDigest.UnmeasurableException unmeasurable) {
                digest = null;
                logger.error("BINARY_DIGEST_UNAVAILABLE: {}", unmeasurable.getMessage());
            }
            if (digest == null) {
                return BarrierOutcome.refused("BINARY_DIGEST_UNAVAILABLE — this deployment's"
                        + " distribution cannot be measured, so condition 9 cannot be honoured");
            }
            boolean spoolReady = spoolMachinery != null && spoolMachinery.probeReadiness();
            if (!spoolReady) {
                return BarrierOutcome.refused("the spool volume failed its"
                        + " write/link/fsync probe");
            }
            long now = clockMs.getAsLong();
            LineageWriteVersionBarrier.Ack ack = new LineageWriteVersionBarrier.Ack(
                    current.generation(), identity.bootId(), digest,
                    capabilities.wiredCapabilities(),
                    lineageConfig == null ? Set.of(1, 2) : lineageConfig.getReadSchemaVersions(),
                    SPOOL_RECORD_SCHEMA_VERSIONS, MATERIALIZE_EVENT_SCHEMA_VERSIONS,
                    true, true, List.of(), now, now + ackTtlMs());
            Map<String, LineageWriteVersionBarrier.Ack> acks =
                    new LinkedHashMap<>(current.acks());
            acks.put(nodeId, ack);
            LineageWriteVersionBarrier next = withAcks(current, acks);
            if (writeBarrier(next)) {
                return BarrierOutcome.ok(reread());
            }
        }
        return BarrierOutcome.refused("the barrier changed under every attempt — retry");
    }

    /**
     * {@code PREPARING → ACTIVE}: conditions 1 and 3–11 against ONE revision, with readiness
     * re-evaluated here and not merely read out of the ACK, then both version flags in a
     * single write.
     */
    public synchronized BarrierOutcome activate() {
        if (store == null || identity == null) {
            return BarrierOutcome.refused("the barrier machinery is not wired on this node");
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            LineageWriteVersionBarrier current = readBarrier();
            if (current == null) {
                return BarrierOutcome.refused("no barrier exists — prepare one first");
            }
            List<String> violations = activationViolations(current);
            if (!violations.isEmpty()) {
                return BarrierOutcome.refused(violations);
            }
            LineageWriteVersionBarrier next = new LineageWriteVersionBarrier(current.rev(),
                    LineageWriteVersionBarrier.State.ACTIVE, current.generation(),
                    2, 2, current.expectedNodes(), current.expectedMembershipDigest(),
                    current.requiredCapabilities(), current.approvedBinaryDigests(),
                    current.acks());
            if (writeBarrier(next)) {
                logger.warn("Lineage write-version barrier ACTIVATED: v2 writes are open and"
                        + " minReaderSchemaVersion is now 2 — this is one-way");
                return BarrierOutcome.ok(reread());
            }
        }
        return BarrierOutcome.refused("the barrier changed under every attempt — retry");
    }

    /**
     * {@code ACTIVE → ACTIVE}: writes go back to v1. No conditions — this is movement toward
     * safety. {@code minReaderSchemaVersion}, {@code generation} and the ACKs are untouched;
     * discarding ACKs is the job of re-arming, not of rolling back.
     */
    public synchronized BarrierOutcome rollback() {
        if (store == null) {
            return BarrierOutcome.refused("the barrier machinery is not wired on this node");
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            LineageWriteVersionBarrier current = readBarrier();
            if (current == null) {
                return BarrierOutcome.refused("no barrier exists");
            }
            if (current.state() != LineageWriteVersionBarrier.State.ACTIVE) {
                return BarrierOutcome.refused("only an ACTIVE barrier can be rolled back;"
                        + " this one is " + current.state());
            }
            if (current.writeSchemaVersion() == 1) {
                return BarrierOutcome.ok(current); // already there
            }
            LineageWriteVersionBarrier next = new LineageWriteVersionBarrier(current.rev(),
                    LineageWriteVersionBarrier.State.ACTIVE, current.generation(),
                    1, current.minReaderSchemaVersion(), current.expectedNodes(),
                    current.expectedMembershipDigest(), current.requiredCapabilities(),
                    current.approvedBinaryDigests(), current.acks());
            if (writeBarrier(next)) {
                return BarrierOutcome.ok(reread());
            }
        }
        return BarrierOutcome.refused("the barrier changed under every attempt — retry");
    }

    /**
     * CAS conditions 1 and 3–11 (2 is multi-node only), each as its own named violation.
     * Public so the admin status route can show what currently blocks activation.
     */
    public List<String> activationViolations(LineageWriteVersionBarrier barrier) {
        List<String> violations = new ArrayList<>();
        // 1
        if (barrier.state() != LineageWriteVersionBarrier.State.PREPARING) {
            violations.add("condition 1: the barrier is " + barrier.state()
                    + ", not PREPARING");
        }
        Set<String> required = union(requiredCapabilities(), barrier.requiredCapabilities());
        long now = clockMs.getAsLong();
        for (LineageWriteVersionBarrier.NodeRef node : barrier.expectedNodes()) {
            LineageWriteVersionBarrier.Ack ack = barrier.acks().get(node.nodeId());
            if (ack == null) {
                violations.add("condition 3: no ACK from '" + node.nodeId() + "'");
                continue;
            }
            // 3
            if (ack.generation() != barrier.generation()) {
                violations.add("condition 3: the ACK from '" + node.nodeId() + "' answered"
                        + " generation " + ack.generation() + ", not " + barrier.generation());
            }
            // 4
            if (!ack.bootId().equals(node.bootId())) {
                violations.add("condition 4: the ACK from '" + node.nodeId() + "' is from"
                        + " boot " + ack.bootId() + ", not " + node.bootId());
            }
            // 5
            if (!ack.isFresh(now)) {
                violations.add("condition 5: the ACK from '" + node.nodeId() + "' expired at "
                        + ack.expiresAtMs());
            }
            // 6
            if (!ack.readSchemaVersions().contains(2)) {
                violations.add("condition 6: '" + node.nodeId() + "' does not read v2");
            }
            // 7
            if (!ack.spoolReady()) {
                violations.add("condition 7: '" + node.nodeId() + "' has no usable spool");
            }
            if (!ack.materializeEventSchemaVersions().contains(2)) {
                violations.add("condition 7: '" + node.nodeId() + "' cannot materialize a v2"
                        + " event from a spooled fact");
            }
            // 8
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(ack.capabilities());
            if (!missing.isEmpty()) {
                violations.add("condition 8: '" + node.nodeId() + "' is missing " + missing);
            }
            // 9
            if (!barrier.approvedBinaryDigests().isEmpty()
                    && !barrier.approvedBinaryDigests().contains(ack.binaryDigest())) {
                violations.add("condition 9: the binary running on '" + node.nodeId()
                        + "' is not in approvedBinaryDigests");
            }
            // 10 — as RECORDED...
            if (!ack.drestReady()) {
                violations.add("condition 10: '" + node.nodeId() + "' acked with a red"
                        + " readiness gate: " + ack.drestViolations());
            }
        }
        // 10 — ...and as it is RIGHT NOW on this node. The ACK is evidence about a moment;
        // activation opens v2 writes in this one.
        if (barrier.expectedNodes().stream()
                .anyMatch(n -> identity != null && n.nodeId().equals(safeNodeId()))) {
            LineageDrestReadiness.Readiness verdict = readiness == null
                    ? new LineageDrestReadiness.Readiness(false,
                            List.of("no readiness gate is wired"))
                    : readiness.evaluate();
            if (!verdict.ready()) {
                violations.addAll(prefix("condition 10 (re-evaluated at activation): ",
                        verdict.violations()));
            }
        }
        // 11 is not a check but an effect: both flags move in the one write below.
        return violations;
    }

    private String safeNodeId() {
        try {
            return identity.nodeId();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The barrier as it stands, or null when absent. Failures propagate. */
    public LineageWriteVersionBarrier readBarrier() {
        Map<String, Object> raw = store.readBarrierRaw();
        return raw == null ? null : LineageBarrierCodec.decode(raw);
    }

    /**
     * Invalidates the reader's memo and rereads. The invalidation is what stops an emitter
     * from routing on a view this write has just superseded.
     */
    private LineageWriteVersionBarrier reread() {
        if (reader != null) {
            reader.invalidate();
        }
        return readBarrier();
    }

    private boolean writeBarrier(LineageWriteVersionBarrier next) {
        LineageWriteVersionBarrier current = next.rev() == null ? null : readBarrier();
        if (current != null && next.minReaderSchemaVersion() < current.minReaderSchemaVersion()) {
            // Monotonicity is a TRANSITION rule, checked against the revision being written.
            throw new IllegalStateException("minReaderSchemaVersion cannot go from "
                    + current.minReaderSchemaVersion() + " to " + next.minReaderSchemaVersion()
                    + " — it only ever increases");
        }
        return store.casBarrier(LineageBarrierCodec.encode(next));
    }

    private LineageWriteVersionBarrier withAcks(LineageWriteVersionBarrier barrier,
            Map<String, LineageWriteVersionBarrier.Ack> acks) {
        return new LineageWriteVersionBarrier(barrier.rev(), barrier.state(),
                barrier.generation(), barrier.writeSchemaVersion(),
                barrier.minReaderSchemaVersion(), barrier.expectedNodes(),
                barrier.expectedMembershipDigest(), barrier.requiredCapabilities(),
                barrier.approvedBinaryDigests(), acks);
    }

    private long ackTtlMs() {
        return LineageWriteVersionBarrier.DEFAULT_ACK_TTL_MS;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> union = new LinkedHashSet<>(a);
        if (b != null) {
            union.addAll(b);
        }
        return union;
    }

    private static List<String> prefix(String prefix, List<String> values) {
        List<String> prefixed = new ArrayList<>();
        for (String value : values) {
            prefixed.add(prefix + value);
        }
        return prefixed;
    }
}
