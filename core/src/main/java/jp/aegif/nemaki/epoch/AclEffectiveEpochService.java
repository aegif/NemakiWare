package jp.aegif.nemaki.epoch;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Effective-epoch resolution: the AUTHORITATIVE walk, the PENDING GATE, the effective-epoch
 * computation, and the dependency REVALIDATION (design {@code docs/design/acl-epoch-fencing.md}
 * §4.1 + §4.2 steps 1/2/4 — increment 3).
 *
 * <p>This is the read-side half of the unified write contract. It deliberately implements ONLY:
 * <ol>
 *   <li><b>Walk</b> (§4.2 step 1) — record, for every dependency (self + inheriting ancestors; for
 *       a relationship also both endpoint chains), its {@code _rev}, {@code aclSourceEpoch},
 *       {@code aclEpochState}, parent id and inheritance flag. Reads go STRAIGHT to CouchDB, never
 *       through the ACL/content caches, so the snapshot is authoritative (§4.6).</li>
 *   <li><b>Pending gate</b> (§4.2 step 1) — if ANY dependency is {@code PENDING_EPOCH} or
 *       {@code FINALIZED_NEEDS_RECONCILE} (mid-CAS ambiguity), DO NOT WRITE: an
 *       {@link AclEpochPendingException} tells the caller to back off and retry.</li>
 *   <li><b>Compute</b> (§4.1/§4.2 step 2) — {@code effectiveEpoch(X) = max(aclSourceEpoch over X +
 *       inheriting ancestors)}; for a relationship {@code max(effective(source),
 *       effective(target), R.aclSourceEpoch)}. All values come from ONE repository counter, so
 *       they are comparable and monotonic.</li>
 *   <li><b>Revalidate</b> (§4.2 step 4) — re-read every recorded dependency and require an
 *       identical {@code _rev} / epoch / state / topology; ANY difference means the caller must
 *       restart from the walk.</li>
 * </ol>
 *
 * <p>It does NOT do the Solr realtime-GET, the {@code _version_} CAS, or the fence decision
 * (§4.2 steps 3/5/6 + §4.3) — that is the ACL-UPDATE increment.
 *
 * <p><b>Fail-closed staging (sign-off invariant 9):</b> a standalone bean with NO production
 * callers, no scheduler / init / cron, and NO writes of any kind — every method here only READS
 * CouchDB. It never touches Solr, the reconcile queue, or the ACL cache.
 *
 * <h3>Fail-closed rules (all deliberate; each throws rather than guessing)</h3>
 * <ul>
 *   <li>{@code aclSourceEpoch} ABSENT = {@code 0} (§4.1 pre-migration content). PRESENT but null /
 *       non-integer / negative = corruption → {@link AclEpochAnomalyException} (the presence
 *       contract of increments 2e/2f: the SDK stores an explicit JSON null as a PRESENT entry).</li>
 *   <li>An unknown / non-String {@code aclEpochState}, or a QUARANTINED dependency, is
 *       untrustworthy → {@link AclEpochAnomalyException}. (A quarantined ANCESTOR therefore blocks
 *       its whole subtree's ACL-index refresh until repaired — chosen deliberately over computing
 *       a fence value from a document the epoch machine has already declared corrupt.)</li>
 *   <li>An inheriting object whose parent cannot be READ (a transient failure or a dangling
 *       parent) → {@link AclEpochUnavailableException} (retryable), matching the strict
 *       {@code calculateAcl} contract: silently dropping inherited grants is not acceptable.</li>
 *   <li>A cycle, or a chain longer than {@link #getMaxAncestorHops()}, is a data inconsistency →
 *       {@link AclEpochAnomalyException} (bounded walk, never an infinite loop).</li>
 *   <li>A relationship endpoint that genuinely does NOT exist is a DANGLING endpoint and
 *       contributes nothing (matching {@code SolrUtil.relationshipReaders}); an endpoint that
 *       cannot be read is {@link AclEpochUnavailableException}.</li>
 *   <li>The TARGET object genuinely not existing returns {@code null} (deleted — the caller
 *       completes rather than retries); a read failure throws.</li>
 * </ul>
 */
public class AclEffectiveEpochService {

    private static final Logger logger = LoggerFactory.getLogger(AclEffectiveEpochService.class);

    /** CouchDB content fields the authoritative walk reads. */
    static final String FIELD_PARENT_ID = "parentId";
    static final String FIELD_ACL_INHERITED = "aclInherited";
    static final String FIELD_SOURCE_ID = "sourceId";
    static final String FIELD_TARGET_ID = "targetId";

    /** Bound on the inheriting-ancestor chain (cycle / runaway protection). */
    public static final int DEFAULT_MAX_ANCESTOR_HOPS = 128;

    private CloudantClientPool connectorPool;
    private int maxAncestorHops = DEFAULT_MAX_ANCESTOR_HOPS;

    public void setConnectorPool(CloudantClientPool connectorPool) { this.connectorPool = connectorPool; }

    public int getMaxAncestorHops() { return maxAncestorHops; }

    /** Non-positive values fall back to the default (config hardening, as for the queue settings). */
    public void setMaxAncestorHops(int maxAncestorHops) {
        this.maxAncestorHops = maxAncestorHops > 0 ? maxAncestorHops : DEFAULT_MAX_ANCESTOR_HOPS;
    }

    // ── outcome types ──────────────────────────────────────────────

    /** Why a dependency is part of the snapshot (diagnostics; not a correctness input). */
    public enum DependencyRole { SELF, ANCESTOR, RELATIONSHIP_SOURCE, RELATIONSHIP_TARGET }

    /**
     * One authoritative dependency reading. Every field is compared verbatim by
     * {@link #revalidate}, so a change to ANY of them (including a move, which changes the child's
     * {@code parentId} and therefore its {@code _rev}) forces the caller to restart.
     */
    public static final class Dependency {
        public final String id;
        public final String rev;
        public final long sourceEpoch;
        public final String state;        // null = not in the epoch machine (normal content)
        public final String parentId;     // null = none
        public final Boolean aclInherited; // null = absent (defaults to TRUE, as in calculateAcl)
        public final String sourceId;     // relationship endpoint (null otherwise)
        public final String targetId;
        public final DependencyRole role;

        Dependency(String id, String rev, long sourceEpoch, String state, String parentId,
                   Boolean aclInherited, String sourceId, String targetId, DependencyRole role) {
            this.id = id; this.rev = rev; this.sourceEpoch = sourceEpoch; this.state = state;
            this.parentId = parentId; this.aclInherited = aclInherited;
            this.sourceId = sourceId; this.targetId = targetId; this.role = role;
        }

        /** Verbatim equality of everything the fence depends on (used by {@link #revalidate}). */
        boolean sameAs(Dependency o) {
            return o != null
                    && Objects.equals(id, o.id)
                    && Objects.equals(rev, o.rev)
                    && sourceEpoch == o.sourceEpoch
                    && Objects.equals(state, o.state)
                    && Objects.equals(parentId, o.parentId)
                    && Objects.equals(aclInherited, o.aclInherited)
                    && Objects.equals(sourceId, o.sourceId)
                    && Objects.equals(targetId, o.targetId);
        }

        @Override public String toString() {
            return "Dependency[" + id + "@" + rev + " epoch=" + sourceEpoch + " state=" + state
                    + " role=" + role + "]";
        }
    }

    /** The recorded step-1 snapshot plus its computed effective epoch. */
    public static final class Snapshot {
        public final String repositoryId;
        public final String objectId;
        /** Every dependency, in deterministic walk order (self first). */
        public final List<Dependency> dependencies;
        public final long effectiveEpoch;

        Snapshot(String repositoryId, String objectId, List<Dependency> dependencies, long effectiveEpoch) {
            this.repositoryId = repositoryId;
            this.objectId = objectId;
            this.dependencies = Collections.unmodifiableList(dependencies);
            this.effectiveEpoch = effectiveEpoch;
        }
    }

    /**
     * A dependency is mid-mutation ({@code PENDING_EPOCH}, or {@code FINALIZED_NEEDS_RECONCILE}
     * whose CAS outcome is still ambiguous): the PENDING GATE. The caller must NOT write; it backs
     * off and retries once the finalizer has advanced the marker.
     */
    public static final class AclEpochPendingException extends RuntimeException {
        public final String dependencyId;
        public final String state;
        public AclEpochPendingException(String dependencyId, String state) {
            super("ACL epoch pending gate: dependency " + dependencyId + " is " + state
                    + " — refusing to compute/write an effective epoch from a mid-mutation source");
            this.dependencyId = dependencyId;
            this.state = state;
        }
    }

    /**
     * A dependency could not be read authoritatively (transient infrastructure failure, or an
     * inheriting object whose parent is unreadable). RETRYABLE — distinct from a data anomaly,
     * which needs repair.
     */
    public static final class AclEpochUnavailableException extends RuntimeException {
        public AclEpochUnavailableException(String message) { super(message); }
        public AclEpochUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /** Corrupt / untrustworthy epoch data — fail-closed, needs repair (never guessed around). */
    public static final class AclEpochAnomalyException extends RuntimeException {
        public AclEpochAnomalyException(String message) { super(message); }
    }

    // ── step 1 + 2: authoritative walk, pending gate, effective epoch ──

    /**
     * Walk the authoritative sources of {@code objectId}, apply the pending gate, and compute the
     * effective epoch (§4.1/§4.2 steps 1-2).
     *
     * @return the snapshot, or {@code null} if the target object genuinely does not exist
     *         (deleted — the caller completes instead of retrying)
     * @throws AclEpochPendingException     a dependency is mid-mutation (back off + retry)
     * @throws AclEpochUnavailableException a dependency could not be read (retry)
     * @throws AclEpochAnomalyException     corrupt epoch data / cycle / hop-cap exceeded (repair)
     */
    public Snapshot snapshot(String repositoryId, String objectId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("objectId is required");
        }
        Document target = read(repositoryId, objectId, "target");
        if (target == null) {
            return null; // genuinely deleted
        }

        List<Dependency> deps = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Dependency self = toDependency(target, DependencyRole.SELF);
        deps.add(self);
        seen.add(self.id);

        long effective;
        Map<String, Object> p = target.getProperties();
        String sourceId = str(p, FIELD_SOURCE_ID);
        String targetId = str(p, FIELD_TARGET_ID);
        if (sourceId != null && targetId != null) {
            // A relationship (exactly the two fields CouchRelationship persists). It has no ACL of
            // its own in the inheritance sense: read permission is read(source) OR read(target), so
            // the fence value is the max over BOTH endpoint chains and the relationship's own epoch.
            long src = walkChain(repositoryId, sourceId, DependencyRole.RELATIONSHIP_SOURCE, deps, seen);
            long tgt = walkChain(repositoryId, targetId, DependencyRole.RELATIONSHIP_TARGET, deps, seen);
            effective = Math.max(self.sourceEpoch, Math.max(src, tgt));
        } else {
            effective = Math.max(self.sourceEpoch,
                    walkAncestors(repositoryId, self, DependencyRole.ANCESTOR, deps, seen));
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Effective epoch {} for {}/{} over {} dependencies",
                    effective, repositoryId, objectId, deps.size());
        }
        return new Snapshot(repositoryId, objectId, deps, effective);
    }

    /**
     * Walk one endpoint chain of a relationship: the endpoint itself plus its inheriting ancestors.
     * A genuinely missing endpoint is DANGLING and contributes nothing (0) — consistent with the
     * reader union in {@code SolrUtil.relationshipReaders}; a read failure throws.
     */
    private long walkChain(String repositoryId, String endpointId, DependencyRole role,
                           List<Dependency> deps, Set<String> seen) {
        if (endpointId == null || endpointId.isBlank()) {
            return 0L;
        }
        if (seen.contains(endpointId)) {
            return 0L; // already recorded (e.g. a self-relationship); its epoch is already folded in
        }
        Document doc = read(repositoryId, endpointId, "relationship endpoint");
        if (doc == null) {
            return 0L; // dangling endpoint — contributes nothing
        }
        Dependency d = toDependency(doc, role);
        deps.add(d);
        seen.add(d.id);
        return Math.max(d.sourceEpoch, walkAncestors(repositoryId, d, role, deps, seen));
    }

    /**
     * Walk the INHERITING ancestor chain of {@code start}, recording each node, and return the max
     * {@code aclSourceEpoch} over it. Mirrors the authoritative inheritance rule of
     * {@code AclServiceDelegate.calculateAclInternal}: stop at a node that does not inherit
     * ({@code aclInherited == false}, which the root folder always has) or that has no parent.
     * Bounded by {@link #getMaxAncestorHops()} with a visited set, so a cycle or a runaway chain
     * fails closed instead of looping.
     */
    private long walkAncestors(String repositoryId, Dependency start, DependencyRole role,
                               List<Dependency> deps, Set<String> seen) {
        long max = 0L;
        Dependency node = start;
        for (int hop = 0; hop < maxAncestorHops; hop++) {
            // Absent aclInherited defaults to TRUE (calculateAcl's getAclInheritedWithDefault).
            if (Boolean.FALSE.equals(node.aclInherited)) {
                return max; // top of the inheriting chain
            }
            String parentId = node.parentId;
            if (parentId == null || parentId.isBlank()) {
                return max; // root / orphan — nothing further to inherit from
            }
            if (seen.contains(parentId)) {
                // Either a cycle, or a node already recorded via the other endpoint chain of a
                // relationship. A cycle in the SAME chain is corruption; a shared ancestor between
                // two endpoint chains is legitimate and its epoch is already folded in.
                if (isAncestorOfSameChain(deps, parentId, role)) {
                    throw new AclEpochAnomalyException("ACL inheritance cycle detected at " + parentId
                            + " while walking " + start.id);
                }
                return max;
            }
            Document parentDoc = read(repositoryId, parentId, "inheriting parent");
            if (parentDoc == null) {
                // An inheriting object MUST have a readable parent — dropping the inherited grants
                // would compute an under-visible fence value (strict calculateAcl contract).
                throw new AclEpochUnavailableException("inheriting object " + node.id
                        + " has an unreadable parent " + parentId + " — cannot compute effective epoch");
            }
            Dependency parent = toDependency(parentDoc, role == DependencyRole.SELF
                    ? DependencyRole.ANCESTOR : role);
            deps.add(parent);
            seen.add(parent.id);
            max = Math.max(max, parent.sourceEpoch);
            node = parent;
        }
        throw new AclEpochAnomalyException("ACL inheritance chain from " + start.id + " exceeds "
                + maxAncestorHops + " hops — refusing to compute an effective epoch");
    }

    /** True if {@code id} was recorded in THIS chain (a real cycle) rather than a sibling chain. */
    private static boolean isAncestorOfSameChain(List<Dependency> deps, String id, DependencyRole role) {
        for (Dependency d : deps) {
            if (id.equals(d.id)) {
                return d.role == role || d.role == DependencyRole.SELF || d.role == DependencyRole.ANCESTOR;
            }
        }
        return false;
    }

    // ── step 4: revalidation ───────────────────────────────────────

    /**
     * Re-read EVERY dependency recorded by {@link #snapshot} and require it to be byte-identical
     * (§4.2 step 4). Any difference — a new {@code _rev}, a changed epoch / state, a move (which
     * changes the child's {@code parentId} and thus its {@code _rev}), an inheritance flip, a
     * relationship re-point, or a deletion — means the snapshot is stale.
     *
     * <p>Topology changes need no separate check: inserting, removing or re-parenting an ancestor
     * necessarily rewrites a recorded document, so its {@code _rev} differs.
     *
     * @return {@code true} if every dependency is unchanged (the caller may proceed to the Solr
     *         RTG + CAS), {@code false} if the caller must RESTART from {@link #snapshot}
     * @throws AclEpochPendingException     a dependency became mid-mutation (back off + retry)
     * @throws AclEpochUnavailableException a dependency could not be re-read (retry)
     * @throws AclEpochAnomalyException     a dependency became corrupt (repair)
     */
    public boolean revalidate(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        for (Dependency recorded : snapshot.dependencies) {
            Document now = read(snapshot.repositoryId, recorded.id, "revalidated dependency");
            if (now == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Revalidation: dependency {} vanished — restart", recorded.id);
                }
                return false; // deleted under us → restart
            }
            // Re-applies the pending gate and every fail-closed rule to the CURRENT document.
            Dependency fresh = toDependency(now, recorded.role);
            if (!recorded.sameAs(fresh)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Revalidation: {} changed ({} → {}) — restart", recorded.id, recorded, fresh);
                }
                return false;
            }
        }
        return true;
    }

    // ── reading + strict field extraction ──────────────────────────

    /**
     * Build a dependency reading from a raw CouchDB document, applying the PENDING GATE and every
     * fail-closed field rule.
     */
    private Dependency toDependency(Document doc, DependencyRole role) {
        Map<String, Object> p = doc.getProperties() != null ? doc.getProperties() : new LinkedHashMap<>();
        String id = doc.getId();

        // Quarantined = the epoch machine has already declared this document untrustworthy.
        if (p.containsKey(AclEpochState.FIELD_QUARANTINED)
                && !Boolean.FALSE.equals(p.get(AclEpochState.FIELD_QUARANTINED))) {
            throw new AclEpochAnomalyException("dependency " + id + " is quarantined / carries a "
                    + "malformed quarantine marker — refusing to derive an effective epoch from it");
        }

        String state = null;
        if (p.containsKey(AclEpochState.FIELD_STATE)) { // PRESENT-null is corruption, not "absent"
            Object raw = p.get(AclEpochState.FIELD_STATE);
            if (!(raw instanceof String) || !AclEpochState.isKnown((String) raw)) {
                throw new AclEpochAnomalyException("dependency " + id
                        + " has an unknown / non-String aclEpochState: " + raw);
            }
            state = (String) raw;
            // PENDING GATE (§4.2 step 1): PENDING_EPOCH has no epoch yet; FINALIZED_NEEDS_RECONCILE
            // is mid-CAS-ambiguous. RECONCILE_ENQUEUED is settled and does NOT gate.
            if (AclEpochState.PENDING_EPOCH.equals(state)
                    || AclEpochState.FINALIZED_NEEDS_RECONCILE.equals(state)) {
                throw new AclEpochPendingException(id, state);
            }
        }

        long epoch = 0L;
        if (p.containsKey(AclEpochState.FIELD_SOURCE_EPOCH)) { // absent = 0 (pre-migration, §4.1)
            Object raw = p.get(AclEpochState.FIELD_SOURCE_EPOCH);
            try {
                epoch = AclEpochCounterService.parseExactLong(raw);
            } catch (RuntimeException e) {
                throw new AclEpochAnomalyException("dependency " + id
                        + " has a non-integer aclSourceEpoch (value=" + raw + ")");
            }
            if (epoch < 0) {
                throw new AclEpochAnomalyException("dependency " + id
                        + " has a negative aclSourceEpoch " + epoch);
            }
        }

        Object rawInherited = p.get(FIELD_ACL_INHERITED);
        Boolean aclInherited;
        if (!p.containsKey(FIELD_ACL_INHERITED) || rawInherited == null) {
            aclInherited = null; // absent → defaults to TRUE (calculateAcl semantics)
        } else if (rawInherited instanceof Boolean) {
            aclInherited = (Boolean) rawInherited;
        } else {
            throw new AclEpochAnomalyException("dependency " + id
                    + " has a non-Boolean aclInherited: " + rawInherited);
        }

        if (doc.getRev() == null || doc.getRev().isBlank()) {
            throw new AclEpochAnomalyException("dependency " + id + " has no _rev — cannot be revalidated");
        }
        return new Dependency(id, doc.getRev(), epoch, state, str(p, FIELD_PARENT_ID), aclInherited,
                str(p, FIELD_SOURCE_ID), str(p, FIELD_TARGET_ID), role);
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return (v instanceof String && !((String) v).isBlank()) ? (String) v : null;
    }

    /**
     * Authoritative read: straight to CouchDB, NEVER through the content / ACL caches (§4.6).
     * Tri-state: the document, {@code null} for a genuine 404, and a throw for any other failure
     * (a read error must never be mistaken for "deleted").
     */
    private Document read(String repositoryId, String docId, String what) {
        CloudantClientWrapper client = contentClient(repositoryId);
        try {
            return client.getClient().getDocument(new GetDocumentOptions.Builder()
                    .db(client.getDatabaseName()).docId(docId).build()).execute().getResult();
        } catch (NotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            throw new AclEpochUnavailableException("failed to read " + what + " " + docId
                    + " in '" + repositoryId + "': " + e.getMessage(), e);
        }
    }

    private CloudantClientWrapper contentClient(String repositoryId) {
        if (connectorPool == null) {
            throw new IllegalStateException("connectorPool not wired on AclEffectiveEpochService");
        }
        CloudantClientWrapper client = connectorPool.getClient(repositoryId);
        if (client == null) {
            throw new IllegalStateException("content DB client not available for repository '"
                    + repositoryId + "'");
        }
        return client;
    }
}
