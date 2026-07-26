package jp.aegif.nemaki.epoch;

import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.acl.AclSemantics;
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
    /** The persisted ACL: {@code {"entries":[{"principal":…,"permissions":[…]}]}} ({@code CouchAcl}). */
    static final String FIELD_ACL = "acl";
    static final String FIELD_ACL_ENTRIES = "entries";
    static final String FIELD_ACE_PRINCIPAL = "principal";
    static final String FIELD_ACE_PERMISSIONS = "permissions";
    static final String FIELD_SOURCE_ID = "sourceId";
    static final String FIELD_TARGET_ID = "targetId";
    /** Persisted BASE-type discriminator (set by the model constructors, independent of objectType). */
    static final String FIELD_TYPE = "type";
    static final String FIELD_OBJECT_TYPE = "objectType";

    /** Bound on the inheriting-ancestor chain (cycle / runaway protection). */
    public static final int DEFAULT_MAX_ANCESTOR_HOPS = 128;

    private CloudantClientPool connectorPool;
    private jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repositoryInfoMap;
    private int maxAncestorHops = DEFAULT_MAX_ANCESTOR_HOPS;

    public void setConnectorPool(CloudantClientPool connectorPool) { this.connectorPool = connectorPool; }

    /**
     * Needed for the READERS projection only (increment 5S): the root-folder id (the walk's
     * equivalent of {@code contentService.isRoot}) and the configured
     * {@code principal.anyone} / {@code principal.anonymous} ids. The epoch computation itself does
     * not use it, so a snapshot can still be taken without it.
     */
    public void setRepositoryInfoMap(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }

    public int getMaxAncestorHops() { return maxAncestorHops; }

    /** Non-positive values fall back to the default (config hardening, as for the queue settings). */
    public void setMaxAncestorHops(int maxAncestorHops) {
        this.maxAncestorHops = maxAncestorHops > 0 ? maxAncestorHops : DEFAULT_MAX_ANCESTOR_HOPS;
    }

    // ── outcome types ──────────────────────────────────────────────

    /** Why a dependency is part of the snapshot (diagnostics; not a correctness input). */
    public enum DependencyRole { SELF, ANCESTOR, RELATIONSHIP_SOURCE, RELATIONSHIP_TARGET }

    /**
     * The CMIS base kind of a raw content document. The DISCRIMINATOR PRECEDENCE and the accepted
     * spellings are taken from {@code ContentDaoServiceImpl.getContent} — {@code type} if present,
     * else {@code objectType}, including the legacy short forms ({@code "folder"},
     * {@code "document"}, …) — so the epoch walk and the real content layer agree on what a
     * document IS (review 3b [P1]).
     *
     * <p>The epoch side is deliberately STRICTER in one respect (review 3c): the DAO falls back to
     * a generic {@code CouchContent} for an unknown type, whereas an unrecognised / absent
     * discriminator here is an ANOMALY. A fence value derived from a document whose kind we had to
     * guess is worse than no fence value at all.
     */
    public enum ContentKind { FOLDER, DOCUMENT, ITEM, RELATIONSHIP, POLICY }

    /**
     * One authoritative dependency reading. Every field is compared verbatim by
     * {@link #revalidate}, so a change to ANY of them (including a move, which changes the child's
     * {@code parentId} and therefore its {@code _rev}) forces the caller to restart.
     */
    public static final class Dependency {
        public final String id;
        /**
         * {@code false} = a NEGATIVE dependency: this id was resolved during the walk and did NOT
         * exist (a dangling relationship endpoint). It is recorded so revalidation can prove it is
         * STILL absent — otherwise an endpoint recreated under the same id would leave the
         * relationship document untouched, revalidation would pass, and we would CAS a fence value
         * computed without that endpoint's chain (review 3a [P1]).
         */
        public final boolean exists;
        public final String rev;          // null for a negative dependency
        public final long sourceEpoch;    // 0 for a negative dependency
        public final String state;        // null = not in the epoch machine (normal content)
        public final String parentId;     // null = none
        public final Boolean aclInherited; // null = absent (defaults to TRUE, as in calculateAcl)
        public final String sourceId;     // relationship endpoint (null otherwise)
        public final String targetId;
        /** The CMIS base kind; {@code null} only for a NEGATIVE (recorded-absent) dependency. */
        public final ContentKind kind;
        public final DependencyRole role;
        /**
         * The node's RAW LOCAL ACEs, exactly as persisted (increment 5S). This is what makes the
         * readers a SECOND PROJECTION OF THE SAME READ rather than a second traversal: the epoch and
         * the reader tokens are computed from this one snapshot, at this one {@code _rev}.
         *
         * <p>Never merged and never converted here — merging and the system-principal conversion are
         * {@link jp.aegif.nemaki.acl.AclSemantics}' job, so that the CMIS runtime and this side run
         * the same code. Empty for a negative dependency.
         *
         * <p>Deliberately NOT part of {@link #sameAs}: any ACL edit rewrites the document and so
         * changes {@code _rev}, which IS compared. Adding ACE-by-ACE equality would be redundant and
         * would need an {@code equals} on {@code Ace} that does not exist.
         */
        public final List<jp.aegif.nemaki.model.Ace> localAces;

        Dependency(String id, String rev, boolean exists, long sourceEpoch, String state,
                   String parentId, Boolean aclInherited, String sourceId, String targetId,
                   ContentKind kind, DependencyRole role, List<jp.aegif.nemaki.model.Ace> localAces) {
            this.id = id; this.rev = rev; this.exists = exists; this.sourceEpoch = sourceEpoch;
            this.state = state; this.parentId = parentId; this.aclInherited = aclInherited;
            this.sourceId = sourceId; this.targetId = targetId;
            this.kind = kind; this.role = role;
            this.localAces = localAces == null
                    ? Collections.<jp.aegif.nemaki.model.Ace>emptyList()
                    : Collections.unmodifiableList(localAces);
        }

        /** A recorded absence (a dangling relationship endpoint). */
        static Dependency absent(String id, DependencyRole role) {
            return new Dependency(id, null, false, 0L, null, null, null, null, null, null, role, null);
        }

        /** Verbatim equality of everything the fence depends on (used by {@link #revalidate}). */
        boolean sameAs(Dependency o) {
            return o != null
                    && Objects.equals(id, o.id)
                    && exists == o.exists
                    && Objects.equals(rev, o.rev)
                    && sourceEpoch == o.sourceEpoch
                    && Objects.equals(state, o.state)
                    && Objects.equals(parentId, o.parentId)
                    && Objects.equals(aclInherited, o.aclInherited)
                    && Objects.equals(sourceId, o.sourceId)
                    && Objects.equals(targetId, o.targetId)
                    && kind == o.kind;
        }

        @Override public String toString() {
            return "Dependency[" + id + (exists ? "@" + rev : " ABSENT") + " epoch=" + sourceEpoch
                    + " state=" + state + " role=" + role + "]";
        }
    }

    /** The recorded step-1 snapshot plus its computed effective epoch. */
    public static final class Snapshot {
        public final String repositoryId;
        public final String objectId;
        /** Every dependency, in deterministic walk order (self first). */
        public final List<Dependency> dependencies;
        public final long effectiveEpoch;
        /** Repository configuration captured with the walk (null when not wired — see readers()). */
        private final String rootFolderId;
        private final String anyoneId;
        private final String anonymousId;

        Snapshot(String repositoryId, String objectId, List<Dependency> dependencies, long effectiveEpoch,
                 String rootFolderId, String anyoneId, String anonymousId) {
            this.repositoryId = repositoryId;
            this.objectId = objectId;
            this.dependencies = Collections.unmodifiableList(dependencies);
            this.effectiveEpoch = effectiveEpoch;
            this.rootFolderId = rootFolderId;
            this.anyoneId = anyoneId;
            this.anonymousId = anonymousId;
        }

        /**
         * The reader tokens for this object — the SECOND PROJECTION of the walk that produced
         * {@link #effectiveEpoch} (design §5.3, increment 5S).
         *
         * <p>This is the whole point of Option A. The epoch and the readers are computed from the
         * SAME dependency documents at the SAME {@code _rev}s, so they cannot describe different
         * states of the repository, and there is no second traversal to drift (increments 3a/3b/4b
         * were all two-traversal defects). The removed {@code ReadersComputer} SPI could not promise
         * this: it handed the computation to an implementor who had to be trusted to bypass the
         * cache and walk the same chain.
         *
         * <p>Runs through {@link AclSemantics#resolveAcl} — NOT {@code effectiveAces} — because the
         * system-principal conversion that {@code resolveAcl} applies at the end is load-bearing:
         * {@code mergeAces} converts only its TARGET, and an ancestor's ACEs are always the SOURCE,
         * so an INHERITED {@code CMIS_ANYONE} leaves {@code effectiveAces} unconverted. It would then
         * miss {@code readerTokens}' {@code "cmis:anyone"} literal (different spelling), miss USER,
         * miss GROUP, be dropped, and collapse the whole set to admin-only — a silent stale-DENY.
         * Pinned by {@code AclSemanticsResolveAclIsRequiredTest}.
         *
         * <p>{@code strict = true}: every inheriting ancestor was already recorded by the walk (an
         * unreadable one threw {@link AclEpochUnavailableException} there), so a missing link here
         * is an internal inconsistency, not a cache miss to shrug off.
         *
         * @throws IllegalStateException  the repository info was not wired (see
         *                                {@link #setRepositoryInfoMap})
         */
        public List<String> readers(AclSemantics.PrincipalResolver resolver) {
            if (rootFolderId == null) {
                throw new IllegalStateException("repositoryInfoMap not wired on AclEffectiveEpochService "
                        + "— the readers projection needs the root-folder id and the configured "
                        + "anyone/anonymous principal ids");
            }
            Map<String, Dependency> byId = index();
            Dependency self = byId.get(objectId);
            if (self == null) {
                throw new IllegalStateException("snapshot of " + objectId + " does not contain itself");
            }
            if (self.kind == ContentKind.RELATIONSHIP) {
                // A relationship has no ACL of its own in the readers sense: read(source) OR
                // read(target). Its own local ACEs are deliberately NOT an input (expanding a
                // relationship as ordinary content collapses to admin-only and loses BOTH chains —
                // the 5R-a cross-path report). A dangling endpoint contributes nothing.
                return AclSemantics.relationshipReaders(
                        readersOf(byId, self.sourceId, resolver),
                        readersOf(byId, self.targetId, resolver));
            }
            return readersOf(byId, objectId, resolver);
        }

        /**
         * Whether an EMPTY reader set from {@link #readers} is the authoritative answer rather than
         * a failed computation (increment 5S step 3).
         *
         * <p>{@link AclSemantics#readerTokens} never returns empty — rule 2 falls back to admin-only
         * — so the ONLY way {@link #readers} yields nothing is a relationship whose source and target
         * BOTH contribute nothing, i.e. both endpoints are genuinely absent. That is exactly the
         * fail-closed value production already writes ({@code SolrUtil.unionReaders}): the query-side
         * {@code readers} filter then excludes the relationship for every non-admin caller.
         *
         * <p>The writer needs this because it otherwise refuses an empty ACL group as a partial
         * computation. Without the distinction, a relationship whose endpoints were both deleted
         * could never be reconciled — its task would fail and retry for ever.
         */
        public boolean emptyReadersIsAuthoritative() {
            Map<String, Dependency> byId = index();
            Dependency self = byId.get(objectId);
            if (self == null || self.kind != ContentKind.RELATIONSHIP) {
                return false;
            }
            return endpoint(byId, self.sourceId) == null && endpoint(byId, self.targetId) == null;
        }

        /** id → dependency, in walk order. */
        private Map<String, Dependency> index() {
            Map<String, Dependency> byId = new LinkedHashMap<>();
            for (Dependency d : dependencies) {
                byId.put(d.id, d);
            }
            return byId;
        }

        /**
         * The dependency an endpoint / chain root resolves to, or {@code null} when it contributes
         * NOTHING — an absent id, an unrecorded id, or a recorded ABSENCE (a dangling endpoint).
         * Shared by {@link #readersOf} and {@link #emptyReadersIsAuthoritative} so the two can never
         * disagree about what "contributes nothing" means.
         */
        private static Dependency endpoint(Map<String, Dependency> byId, String id) {
            Dependency d = id == null ? null : byId.get(id);
            return (d == null || !d.exists) ? null : d;
        }

        /** Readers of one recorded chain root, or {@code null} for a dangling / unrecorded id. */
        private List<String> readersOf(Map<String, Dependency> byId, String id,
                                       AclSemantics.PrincipalResolver resolver) {
            Dependency d = endpoint(byId, id);
            if (d == null) {
                return null;
            }
            jp.aegif.nemaki.model.Acl acl =
                    AclSemantics.resolveAcl(new EpochChainNode(byId, d, rootFolderId), true,
                            anyoneId, anonymousId);
            return AclSemantics.readerTokens(repositoryId, acl.getAllAces(), resolver);
        }
    }

    /**
     * The ACL-epoch view of one inheritance-chain node: the SAME {@link AclSemantics.ChainNode} the
     * CMIS runtime implements over live {@code Content}, but backed by documents this service
     * already read authoritatively (increment 5S).
     *
     * <p>One deliberate difference from the CMIS adapter, in FETCH not in semantics: every call
     * hands out FRESH copies of the ACEs. {@code mergeAces} converts its target IN PLACE — harmless
     * for the CMIS side, whose target is the live Content's own list — but a {@link Snapshot} is a
     * record that may be projected more than once (a CAS restart re-projects it), and mutating the
     * record would make the second projection depend on the first.
     */
    private static final class EpochChainNode implements AclSemantics.ChainNode {
        private final Map<String, Dependency> byId;
        private final Dependency dep;
        private final String rootFolderId;

        EpochChainNode(Map<String, Dependency> byId, Dependency dep, String rootFolderId) {
            this.byId = byId; this.dep = dep; this.rootFolderId = rootFolderId;
        }

        @Override public String id() { return dep.id; }

        @Override public List<jp.aegif.nemaki.model.Ace> localAces() {
            List<jp.aegif.nemaki.model.Ace> copies = new ArrayList<>(dep.localAces.size());
            for (jp.aegif.nemaki.model.Ace a : dep.localAces) {
                copies.add(AclSemantics.deepCopy(a));
            }
            return copies;
        }

        @Override public jp.aegif.nemaki.model.Acl storedAcl() {
            jp.aegif.nemaki.model.Acl acl = new jp.aegif.nemaki.model.Acl();
            acl.getLocalAces().addAll(localAces());
            return acl;
        }

        /** {@code ContentServiceImpl.isRoot}: the root-folder id AND a folder. */
        @Override public boolean root() {
            return dep.kind == ContentKind.FOLDER && rootFolderId.equals(dep.id);
        }

        /**
         * {@code AclServiceDelegate.getAclInheritedWithDefault}: the ROOT is never inheriting
         * whatever its stored flag says, and an ABSENT flag defaults to TRUE. (That method's two
         * remaining branches are byte-identical, so
         * {@code capability.extended.permission.inheritance.toplevel} has no effect and is not
         * replicated here — see the 5R-a report.)
         */
        @Override public boolean inherited() {
            if (root()) {
                return false;
            }
            return !Boolean.FALSE.equals(dep.aclInherited);
        }

        @Override public String parentId() { return dep.parentId; }

        @Override public AclSemantics.ChainNode parent() {
            Dependency parent = dep.parentId == null ? null : byId.get(dep.parentId);
            return (parent == null || !parent.exists) ? null : new EpochChainNode(byId, parent, rootFolderId);
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
        if (self.kind == ContentKind.RELATIONSHIP) {
            // A relationship has no ACL of its own in the inheritance sense: read permission is
            // read(source) OR read(target), so the fence value is the max over BOTH endpoint chains
            // and the relationship's own epoch. toDependency() has already guaranteed both endpoint
            // ids are present and well-formed.
            long src = walkChain(repositoryId, self.sourceId, DependencyRole.RELATIONSHIP_SOURCE, deps, seen);
            long tgt = walkChain(repositoryId, self.targetId, DependencyRole.RELATIONSHIP_TARGET, deps, seen);
            effective = Math.max(self.sourceEpoch, Math.max(src, tgt));
        } else {
            effective = Math.max(self.sourceEpoch,
                    walkAncestors(repositoryId, self, DependencyRole.ANCESTOR, deps, seen));
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Effective epoch {} for {}/{} over {} dependencies",
                    effective, repositoryId, objectId, deps.size());
        }
        String rootFolderId = null, anyoneId = null, anonymousId = null;
        if (repositoryInfoMap != null) {
            jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info = repositoryInfoMap.get(repositoryId);
            if (info != null) {
                rootFolderId = info.getRootFolderId();
                anyoneId = info.getPrincipalIdAnyone();
                anonymousId = info.getPrincipalIdAnonymous();
            }
        }
        return new Snapshot(repositoryId, objectId, deps, effective, rootFolderId, anyoneId, anonymousId);
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
            // DANGLING: contributes nothing to the epoch, but the absence is RECORDED so
            // revalidation can prove it is still absent (review 3a [P1]).
            deps.add(Dependency.absent(endpointId, role));
            seen.add(endpointId);
            return 0L;
        }
        // toDependency() resolves + validates the CMIS kind, so a non-content document at an
        // endpoint id is already an anomaly there; any of the five content kinds is a legal
        // relationship endpoint.
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
        int added = 0;
        while (true) {
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
            // The cap is checked HERE — only when another ancestor is actually REQUIRED — so a
            // chain of EXACTLY maxAncestorHops ancestors succeeds and only hops+1 fails (review
            // 3b [P2]: the previous loop bound rejected an exactly-at-the-limit chain, permanently
            // blocking a legitimately deep subtree from being re-indexed).
            if (added >= maxAncestorHops) {
                throw new AclEpochAnomalyException("ACL inheritance chain from " + start.id
                        + " exceeds " + maxAncestorHops + " hops — refusing to compute an effective epoch");
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
            // The real ACL computation resolves the parent through getFolder(), which returns null
            // for a NON-folder and then fails closed under strict mode. Requiring FOLDER here keeps
            // the epoch walk's dependency set identical to the readers computation's (review 3b [P1]).
            if (parent.kind != ContentKind.FOLDER) {
                throw new AclEpochAnomalyException("inheriting object " + node.id + " has a parent "
                        + parentId + " that is not a folder (" + parent.kind + ") — the readers "
                        + "computation resolves parents via getFolder(), so the dependency sets "
                        + "would diverge");
            }
            deps.add(parent);
            seen.add(parent.id);
            max = Math.max(max, parent.sourceEpoch);
            node = parent;
            added++;
        }
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
                if (recorded.exists) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Revalidation: dependency {} vanished — restart", recorded.id);
                    }
                    return false; // deleted under us → restart
                }
                continue; // a recorded ABSENCE that is still absent — unchanged
            }
            if (!recorded.exists) {
                // A dangling endpoint was (re)created under the same id: the relationship document
                // itself is untouched, so ONLY this negative dependency can catch it (review 3a [P1]).
                if (logger.isDebugEnabled()) {
                    logger.debug("Revalidation: absent dependency {} now exists — restart", recorded.id);
                }
                return false;
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
     * fail-closed field rule. All epoch-field validation goes through the SHARED
     * {@link AclEpochFields} validator (review 3a [P1]), so this consumer enforces exactly the
     * invariants the state machine's owner does — in particular a {@code RECONCILE_ENQUEUED}
     * dependency (which does NOT gate, and therefore feeds a fence value directly) must carry a
     * canonical UUID mutation id AND a strictly positive epoch.
     */
    private Dependency toDependency(Document doc, DependencyRole role) {
        Map<String, Object> p = doc.getProperties() != null ? doc.getProperties() : new LinkedHashMap<>();
        String id = doc.getId();

        // PRESENCE alone disqualifies: absent = usable, true = quarantined, ANY other present
        // value = a malformed marker. Accepting `false` as normal (the pre-3a behaviour) would let
        // a corrupt-but-false-marked document contribute a high epoch and fence out later correct
        // writers (review 3a [P1]).
        AclEpochFields.requireNotQuarantined(id, p);

        // A state-less document is ordinary settled content, so a state is NOT required here.
        AclEpochFields.Values v = AclEpochFields.validate(id, p, false);
        if (AclEpochState.PENDING_EPOCH.equals(v.state)
                || AclEpochState.FINALIZED_NEEDS_RECONCILE.equals(v.state)) {
            // PENDING GATE (§4.2 step 1): PENDING_EPOCH has no epoch yet; FINALIZED_NEEDS_RECONCILE
            // is mid-CAS-ambiguous. RECONCILE_ENQUEUED is settled and does NOT gate.
            throw new AclEpochPendingException(id, v.state);
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

        // Topology fields are STRICT (review 3a [P1]): a present-null / non-String / blank value is
        // corruption, never silently degraded to "absent" — degrading sourceId/targetId would drop
        // an entire endpoint chain from the fence value.
        String parentId = strictOptionalId(id, p, FIELD_PARENT_ID);
        String sourceId = strictOptionalId(id, p, FIELD_SOURCE_ID);
        String targetId = strictOptionalId(id, p, FIELD_TARGET_ID);

        ContentKind kind = resolveKind(id, p);
        if (kind == ContentKind.RELATIONSHIP) {
            // A relationship's fence value is the max over BOTH endpoint chains, so a missing
            // endpoint id is not "no endpoint" — it means we cannot compute the value at all.
            if (sourceId == null || targetId == null) {
                throw new AclEpochAnomalyException("relationship " + id
                        + " is missing sourceId/targetId — cannot compute an effective epoch");
            }
        } else if (sourceId != null || targetId != null) {
            // Relationship-only fields on a non-relationship: we cannot tell whether the endpoint
            // chains belong in the fence value, so fail closed rather than silently ignore them.
            throw new AclEpochAnomalyException("non-relationship " + id + " carries relationship "
                    + "endpoint fields (sourceId/targetId) — refusing to guess its dependencies");
        }

        return new Dependency(id, doc.getRev(), true, v.epoch, v.state, parentId, aclInherited,
                sourceId, targetId, kind, role, parseLocalAces(id, p));
    }

    /**
     * Parse the persisted ACL into RAW local ACEs (increment 5S), mirroring
     * {@code CouchAcl.convertToNemakiAcl} — including its rule that every stored entry is a LOCAL
     * ace with {@code direct = true}.
     *
     * <p>An ABSENT {@code acl}, or an absent {@code entries}, yields an empty list: that is
     * {@code convertToNemakiAcl} returning null, which the CMIS side turns into an empty list (after
     * logging). A PRESENT but malformed value is an ANOMALY instead — the same presence contract the
     * rest of this class uses. Guessing here would produce an under-granted reader set and, unlike a
     * throw, would look like a successful computation.
     */
    private static List<jp.aegif.nemaki.model.Ace> parseLocalAces(String docId, Map<String, Object> p) {
        List<jp.aegif.nemaki.model.Ace> aces = new ArrayList<>();
        if (!p.containsKey(FIELD_ACL)) {
            return aces;
        }
        Object rawAcl = p.get(FIELD_ACL);
        if (rawAcl == null) {
            return aces; // present-null acl == convertToNemakiAcl's null == "no ACEs"
        }
        if (!(rawAcl instanceof Map)) {
            throw new AclEpochAnomalyException("dependency " + docId + " has a non-object acl: " + rawAcl);
        }
        Object rawEntries = ((Map<?, ?>) rawAcl).get(FIELD_ACL_ENTRIES);
        if (rawEntries == null) {
            return aces;
        }
        if (!(rawEntries instanceof List)) {
            throw new AclEpochAnomalyException("dependency " + docId + " has non-list acl.entries: " + rawEntries);
        }
        for (Object rawEntry : (List<?>) rawEntries) {
            if (!(rawEntry instanceof Map)) {
                throw new AclEpochAnomalyException("dependency " + docId + " has a non-object ACL entry: " + rawEntry);
            }
            Map<?, ?> entry = (Map<?, ?>) rawEntry;
            Object principal = entry.get(FIELD_ACE_PRINCIPAL);
            if (!(principal instanceof String) || ((String) principal).isBlank()) {
                throw new AclEpochAnomalyException("dependency " + docId
                        + " has an ACL entry with a null / non-String / blank principal: " + principal);
            }
            Object permissions = entry.get(FIELD_ACE_PERMISSIONS);
            List<String> perms = new ArrayList<>();
            if (permissions != null) {
                if (!(permissions instanceof List)) {
                    throw new AclEpochAnomalyException("dependency " + docId + " has non-list permissions for '"
                            + principal + "': " + permissions);
                }
                for (Object perm : (List<?>) permissions) {
                    if (!(perm instanceof String)) {
                        throw new AclEpochAnomalyException("dependency " + docId
                                + " has a non-String permission for '" + principal + "': " + perm);
                    }
                    perms.add((String) perm);
                }
            }
            // CouchAcl: a DB-stored ACE is a local ace, direct = true.
            aces.add(new jp.aegif.nemaki.model.Ace((String) principal, perms, true));
        }
        return aces;
    }

    /**
     * Resolve the CMIS base kind of a raw content document, STRICTLY (review 3b [P1]).
     *
     * <p>Mirrors {@code ContentDaoServiceImpl.getContent} exactly — the effective discriminator is
     * {@code type} if present, else {@code objectType}, and the legacy short forms
     * ({@code "folder"}, {@code "document"}, …) that DAO accepts are accepted here too — so the
     * epoch walk and the real content layer can never disagree about what a document IS. A
     * SUBTYPED object keeps its base type in {@code type} ({@code Relationship}'s constructor
     * writes {@code cmis:relationship} while {@code objectType} carries {@code
     * nemaki:hasAttachment}), so subtypes resolve correctly.
     *
     * <p>An ABSENT / present-null / non-String / UNRECOGNISED discriminator is an ANOMALY: the
     * walk must never GUESS a document's kind at runtime (a guess of "ordinary content" silently
     * drops a relationship's endpoint chains from the fence value). Data predating the
     * discriminator needs an explicit migration, not a runtime fallback.
     */
    private static ContentKind resolveKind(String docId, Map<String, Object> p) {
        String effective = kindDiscriminator(docId, p, FIELD_TYPE);
        if (effective == null) {
            effective = kindDiscriminator(docId, p, FIELD_OBJECT_TYPE);
        }
        if (effective == null) {
            throw new AclEpochAnomalyException("dependency " + docId + " has no type/objectType "
                    + "discriminator — refusing to guess its CMIS kind (an explicit migration is "
                    + "required for pre-discriminator data)");
        }
        switch (effective) {
            case "cmis:folder":       case "folder":       return ContentKind.FOLDER;
            case "cmis:document":     case "document":     return ContentKind.DOCUMENT;
            case "cmis:relationship": case "relationship": return ContentKind.RELATIONSHIP;
            case "cmis:policy":       case "policy":       return ContentKind.POLICY;
            case "cmis:item":                              return ContentKind.ITEM;
            default:
                throw new AclEpochAnomalyException("dependency " + docId
                        + " has an unrecognised CMIS base type '" + effective + "'");
        }
    }

    /** A discriminator field: absent → {@code null}; present must be a non-blank String. */
    private static String kindDiscriminator(String docId, Map<String, Object> p, String key) {
        if (!p.containsKey(key)) {
            return null;
        }
        Object v = p.get(key);
        if (!(v instanceof String) || ((String) v).isBlank()) {
            throw new AclEpochAnomalyException("dependency " + docId + " has a present-but-invalid "
                    + key + " (null / non-String / blank): " + v);
        }
        return (String) v;
    }

    /**
     * Read an optional id-valued topology field STRICTLY: absent is fine ({@code null}), but a
     * PRESENT value must be a non-blank String (an explicit JSON null is PRESENT under the SDK
     * contract). Never degrades corruption to "absent".
     */
    private static String strictOptionalId(String docId, Map<String, Object> p, String key) {
        if (!p.containsKey(key)) {
            return null;
        }
        Object v = p.get(key);
        if (!(v instanceof String) || ((String) v).isBlank()) {
            throw new AclEpochAnomalyException("dependency " + docId + " has a present-but-invalid "
                    + key + " (null / non-String / blank): " + v);
        }
        return (String) v;
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
