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
     * REQUIRED (increment 5S; tightened by review P1-1). Supplies the root-folder id — used by BOTH
     * the walk's inheritance-stop rule and the readers projection — and the configured
     * {@code principal.anyone} / {@code principal.anonymous} ids.
     *
     * <p>It was briefly documented as needed "for the readers projection only". That was wrong: the
     * walk decided where inheritance stops WITHOUT it, so the dependency set and the projection could
     * stop at different nodes on a corrupt root. {@link #snapshot} now fails fast if it is missing.
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
        /**
         * Whether the CMIS runtime would call this a folder — {@code NodeBase.isFolder()}, i.e. the
         * persisted {@code type} being EXACTLY {@code cmis:folder} (review P1-2).
         *
         * <p>Deliberately NOT {@code kind == FOLDER}. {@link #resolveKind} follows the DAO and also
         * accepts the legacy short form {@code "folder"} and an {@code objectType} fallback, but
         * {@code NodeBase.isFolder()} does neither and {@code new Folder(content)} does not normalise
         * the type. A document stored as {@code {"type":"folder"}} is therefore a folder to the epoch
         * walk and NOT a folder to the CMIS runtime; using {@code kind} for the root test would have
         * reproduced P1-1 with the sides swapped — CMIS climbing past a corrupt root while the epoch
         * walk stopped.
         */
        public final boolean cmisFolder;
        public final DependencyRole role;
        /**
         * The node's RAW LOCAL ACEs, exactly as persisted (increment 5S). This is what makes the
         * readers a SECOND PROJECTION OF THE SAME READ: the epoch and the reader tokens are computed
         * from this one snapshot, at this one {@code _rev}. (It does NOT eliminate the second
         * TRAVERSAL — see {@code Snapshot.readers} for exactly what is and is not unified.)
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

        /**
          * Every construction site must state {@code cmisFolder} EXPLICITLY (review P2-4). A shorter
          * overload used to derive it as {@code kind == FOLDER} — the exact expression review P1-2
          * found to disagree with {@code NodeBase.isFolder()} on a legacy {@code {"type":"folder"}}
          * document. It survived the fix because its remaining callers happened to be unaffected,
          * which is precisely how a new construction site in the wiring increment would have
          * reintroduced the divergence in silence.
          */
        Dependency(String id, String rev, boolean exists, long sourceEpoch, String state,
                   String parentId, Boolean aclInherited, String sourceId, String targetId,
                   ContentKind kind, boolean cmisFolder, DependencyRole role,
                   List<jp.aegif.nemaki.model.Ace> localAces) {
            this.id = id; this.rev = rev; this.exists = exists; this.sourceEpoch = sourceEpoch;
            this.state = state; this.parentId = parentId; this.aclInherited = aclInherited;
            this.sourceId = sourceId; this.targetId = targetId;
            this.kind = kind; this.cmisFolder = cmisFolder; this.role = role;
            this.localAces = localAces == null
                    ? Collections.<jp.aegif.nemaki.model.Ace>emptyList()
                    : Collections.unmodifiableList(localAces);
        }

        /** A recorded absence (a dangling relationship endpoint). */
        static Dependency absent(String id, DependencyRole role) {
                // A recorded ABSENCE: no document, so nothing is a folder and nothing is the root.
            return new Dependency(id, null, false, 0L, null, null, null, null, null, null, false,
                    role, null);
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
                    && kind == o.kind
                    && cmisFolder == o.cmisFolder;
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
         * states of the repository. The removed {@code ReadersComputer} SPI could not promise this:
         * it handed the computation to an implementor who had to be trusted to bypass the cache and
         * walk the same chain.
         *
         * <p><b>Precisely what is and is not unified</b> (review P1-1 corrected an overclaim here —
         * this used to say "there is no second traversal to drift"):
         * <ul>
         *   <li>the READ is single: one authoritative pass produces both projections;</li>
         *   <li>the SEMANTICS are single: both sides run {@link AclSemantics};</li>
         *   <li>the inheritance-STOP RULE is single since P1-1: {@link #inheritsFromParent};</li>
         *   <li>but the WALK — deciding the dependency SET — is still its own code
         *       ({@code walkAncestors}), separate from the projection's parent chasing. They now
         *       agree by construction on where to stop, and an under-collecting walk fails closed
         *       (the projection cannot resolve a parent it needs), but they are not one implementation.</li>
         * </ul>
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
         * @throws AclEpochWiringException  the repository info was not wired (see
         *                                  {@link #setRepositoryInfoMap}). A WIRING fault, not a
         *                                  per-task condition: no retry or quarantine will fix it
         */
        public List<String> readers(AclSemantics.PrincipalResolver resolver) {
            if (rootFolderId == null) {
                // Unreachable through snapshot(), which now requires the map up front; retained for a
                // Snapshot constructed directly (the tests do) so the failure is still typed.
                throw new AclEpochWiringException("repositoryInfoMap not wired on "
                        + "AclEffectiveEpochService — the readers projection needs the root-folder id "
                        + "and the configured anyone/anonymous principal ids");
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

        /** The SHARED root test (review P1-1). */
        @Override public boolean root() {
            return isRoot(dep, rootFolderId);
        }

        /**
         * {@code AclServiceDelegate.getAclInheritedWithDefault}: the ROOT is never inheriting
         * whatever its stored flag says, and an ABSENT flag defaults to TRUE. (That method's two
         * remaining branches are byte-identical, so
         * {@code capability.extended.permission.inheritance.toplevel} has no effect and is not
         * replicated here — see the 5R-a report.)
         */
        @Override public boolean inherited() {
            return inheritsFromParent(dep, rootFolderId);
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

    // ── the ONE inheritance-stop rule (increment 5S review P1-1) ───

    /**
     * Is this dependency the repository ROOT? Matches {@code ContentServiceImpl.isRoot} exactly: the
     * root-folder id AND {@code NodeBase.isFolder()} — the persisted {@code type} being EXACTLY
     * {@code cmis:folder} (see {@link Dependency#cmisFolder}; review P1-2 corrected an earlier
     * version that used the DAO-compatible {@code kind} and so disagreed on a legacy
     * {@code {"type":"folder"}} document).
     */
    static boolean isRoot(Dependency d, String rootFolderId) {
        return d.cmisFolder && rootFolderId != null && rootFolderId.equals(d.id);
    }

    /**
     * Does this dependency inherit from its parent? Mirrors
     * {@code AclServiceDelegate.getAclInheritedWithDefault}: the ROOT never inherits whatever its
     * stored flag says, and an ABSENT flag defaults to TRUE.
     *
     * <p><b>Shared by the WALK and the READERS PROJECTION on purpose (review P1-1).</b> They used to
     * decide independently — the walk on {@code aclInherited} alone, the projection additionally on
     * root — so a corrupt root (one carrying a {@code parentId} and an {@code aclInherited} that is
     * not {@code false}) made the walk climb PAST the root and raise the effective epoch while the
     * projection stopped at it. Both are now the same predicate over the same recorded fields.
     */
    static boolean inheritsFromParent(Dependency d, String rootFolderId) {
        return !isRoot(d, rootFolderId) && !Boolean.FALSE.equals(d.aclInherited);
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
        return snapshot(repositoryId, objectId, null);
    }

    /**
     * As {@link #snapshot(String, String)}, reusing {@code memo}'s ancestor reads within one
     * traversal (design §4.6). A {@code null} memo is exactly today's behaviour — every caller
     * that does not opt in reads everything fresh, so this cannot change anything by accident.
     *
     * <p>The caller owns the memo's lifetime AND its invalidation: whoever revalidates must call
     * {@link TraversalMemo#invalidateAll()} when a revalidation refuses, or the next snapshot
     * serves the same stale ancestor and the writer cannot make progress.
     */
    public Snapshot snapshot(String repositoryId, String objectId, TraversalMemo memo) {
        try {
            return snapshotInternal(repositoryId, objectId, memo);
        } catch (AclEpochQuarantineBlockedException e) {
            // §5.1 item 2. Re-stated with the object it was serving, counted, and logged ONCE PER
            // BLOCKING ANCESTOR — a quarantined folder can block thousands of descendants, and a log
            // line each would bury the single id an operator actually needs.
            AclEpochQuarantineBlockedException withObject = e.withBlockedObject(objectId);
            quarantineBlockedCount.incrementAndGet();
            String blocker = withObject.getQuarantinedId();
            if (blocker != null && quarantineBlockersSeen.add(blocker)) {
                logger.warn("ACL-index refresh BLOCKED by quarantined document {} in '{}' — repairing "
                        + "that ONE document unblocks its whole subtree (first occurrence; further "
                        + "blocks by the same document are counted, not logged)", blocker, repositoryId);
            }
            throw withObject;
        }
    }

    /**
     * Distinct QUARANTINED documents that have blocked at least one walk, and how many walks they
     * blocked (design §5.1 item 2). Exposed so an operator can find the handful of ids whose repair
     * unblocks everything, rather than reading a thousand identical failures.
     */
    public Map<String, Object> quarantineMetrics() {
        return Map.of(
                "quarantineBlockedTasks", quarantineBlockedCount.get(),
                "quarantineBlockingIds", List.copyOf(quarantineBlockersSeen),
                // OBSERVATION ONLY, and unrelated to quarantine despite sharing this endpoint:
                // uncached CouchDB document reads performed by the authoritative walk. Per JVM,
                // resets to zero on restart, and never used for any decision — it rides here
                // because this is where the walk's own numbers already surface.
                //
                // The walk is deliberately cache-bypassing (§4.6), so this is the floor cost of
                // fencing, and it is the number ledger item A3 is about: the claim there is
                // 2x(1+ancestors) per node, from snapshot and revalidation each reading the
                // whole ancestor chain. Divide the delta across a propagation by the nodes
                // touched to check that against the actual shape of a repository.
                "authoritativeReads", authoritativeReadCount.get());
    }

    /** Forget a repaired blocker, so a LATER re-quarantine of the same document logs again. */
    public void forgetQuarantineBlocker(String docId) {
        quarantineBlockersSeen.remove(docId);
    }

    private final java.util.concurrent.atomic.AtomicLong quarantineBlockedCount =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong authoritativeReadCount =
            new java.util.concurrent.atomic.AtomicLong();
    private final Set<String> quarantineBlockersSeen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Snapshot snapshotInternal(String repositoryId, String objectId, TraversalMemo memo) {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("objectId is required");
        }
        // The root-folder id is REQUIRED, not optional: the walk's inheritance-stop rule needs it
        // (review P1-1), and so does the readers projection. Failing here rather than silently
        // walking with a different stop rule than the projection.
        String rootFolderId = requireRootFolderId(repositoryId);

        // The SELF read is NOT memoised — see TraversalMemo: doing so would retain every
        // descendant the re-drive visits, turning an ancestor-sized map into a subtree-sized one.
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
            long src = walkChain(repositoryId, self.sourceId, DependencyRole.RELATIONSHIP_SOURCE,
                    deps, seen, rootFolderId, memo);
            long tgt = walkChain(repositoryId, self.targetId, DependencyRole.RELATIONSHIP_TARGET,
                    deps, seen, rootFolderId, memo);
            effective = Math.max(self.sourceEpoch, Math.max(src, tgt));
        } else {
            effective = Math.max(self.sourceEpoch,
                    walkAncestors(repositoryId, self, DependencyRole.ANCESTOR, deps, seen, rootFolderId, memo));
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Effective epoch {} for {}/{} over {} dependencies",
                    effective, repositoryId, objectId, deps.size());
        }
        // rootFolderId was already resolved (and required) at the top of this method.
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info = repositoryInfoMap.get(repositoryId);
        return new Snapshot(repositoryId, objectId, deps, effective, rootFolderId,
                info.getPrincipalIdAnyone(), info.getPrincipalIdAnonymous());
    }

    /**
     * Walk one endpoint chain of a relationship: the endpoint itself plus its inheriting ancestors.
     * A genuinely missing endpoint is DANGLING and contributes nothing (0) — consistent with the
     * reader union in {@code SolrUtil.relationshipReaders}; a read failure throws.
     */
    private long walkChain(String repositoryId, String endpointId, DependencyRole role,
                           List<Dependency> deps, Set<String> seen, String rootFolderId,
                           TraversalMemo memo) {
        if (endpointId == null || endpointId.isBlank()) {
            return 0L;
        }
        if (seen.contains(endpointId)) {
            return 0L; // already recorded (e.g. a self-relationship); its epoch is already folded in
        }
        Document doc = readAncestor(repositoryId, endpointId, "relationship endpoint", memo);
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
        return Math.max(d.sourceEpoch, walkAncestors(repositoryId, d, role, deps, seen, rootFolderId, memo));
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
                               List<Dependency> deps, Set<String> seen, String rootFolderId,
                               TraversalMemo memo) {
        long max = 0L;
        Dependency node = start;
        int added = 0;
        while (true) {
            // The SHARED stop rule (review P1-1) — the same predicate the readers projection uses,
            // so the dependency set and the projection can never stop at different nodes.
            if (!inheritsFromParent(node, rootFolderId)) {
                return max; // top of the inheriting chain (the root, or a non-inheriting node)
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
            Document parentDoc = readAncestor(repositoryId, parentId, "inheriting parent", memo);
            if (parentDoc == null) {
                // An inheriting object MUST have a readable parent — dropping the inherited grants
                // would compute an under-visible fence value (strict calculateAcl contract).
                throw new AclEpochUnavailableException("inheriting object " + node.id
                        + " has an unreadable parent " + parentId + " — cannot compute effective epoch");
            }
            Dependency parent = toDependency(parentDoc, role == DependencyRole.SELF
                    ? DependencyRole.ANCESTOR : role);
            // DELIBERATE ASYMMETRY, do not "make consistent" (review P3): the ROOT test uses
            // `cmisFolder` (NodeBase.isFolder(), an exact cmis:folder match) because that is what
            // ContentServiceImpl.isRoot uses, but the PARENT test below uses `kind` because that is
            // what ContentDaoServiceImpl.getFolder effectively accepts — it returns a Folder for a
            // legacy {"type":"folder"} document too, so calculateAcl keeps walking through it.
            // Aligning either one to the other creates a NEW divergence in the opposite direction.
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
        // parentId is NULLABLE-PRESENT: see parentId(). sourceId/targetId are not — a relationship
        // endpoint that is explicitly null is corruption, since a relationship without an endpoint
        // is not a thing the model can express.
        String parentId = parentId(id, p);
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

        // NodeBase.isFolder(): the persisted `type` EXACTLY equal to cmis:folder. Neither the legacy
        // short form nor the objectType fallback counts, because the CMIS runtime does not accept
        // them either (review P1-2).
        boolean cmisFolder = jp.aegif.nemaki.util.constant.NodeType.CMIS_FOLDER.value()
                .equals(p.get(FIELD_TYPE));
        return new Dependency(id, doc.getRev(), true, v.epoch, v.state, parentId, aclInherited,
                sourceId, targetId, kind, cmisFolder, role, parseLocalAces(id, p));
    }

    /**
     * Parse the persisted ACL into RAW local ACEs (increment 5S), following
     * {@code CouchAcl.convertToNemakiAcl} — including its rule that every stored entry is a LOCAL
     * ace with {@code direct = true}.
     *
     * <p>An ABSENT {@code acl}, or an absent / present-null {@code entries}, yields an empty list.
     *
     * <p><b>The CMIS side agrees</b> — traced through the real DAO rather than inferred (review
     * P2-1). Two earlier revisions of this paragraph were both wrong: the first claimed
     * {@code convertToNemakiAcl} "turns into an empty list" (it returns {@code null}), the second
     * claimed the resulting null {@code Acl} makes {@code resolveAcl}'s root branch NPE. It does not,
     * because the DAO never hands out a null {@code Acl}: {@code CouchContent}'s {@code @JsonCreator}
     * only assigns {@code this.acl} when {@code entries instanceof List}, so all three shapes leave it
     * null, and {@code CouchContent.convert()} then substitutes {@code new Acl()}.
     *
     * <p>So both sides end with ZERO ACEs, which rule 2 turns into admin-only. There is no divergence
     * to document and nothing here is "more robust" than the CMIS layer — a claim this Javadoc made,
     * and design §10.3 recorded as a known adjacent issue, until the DAO was actually read.
     *
     * <p><b>Where this deliberately differs from CouchAcl, and where it deliberately does not</b>
     * (review P2-5 — an earlier revision called this a "mirror" and was STRICTER than CouchAcl in
     * two places, which would have permanently excluded from the index an object the CMIS layer
     * serves perfectly well; §5.1 makes that a whole-subtree blast radius):
     * <ul>
     *   <li><b>principal coercion — MATCHED for the shapes that occur.</b> {@code CouchAcl} does
     *       {@code .toString()}, so a non-String principal becomes its string form and a BLANK one is
     *       kept. Both are accepted here too. The two sides can still differ on the STRING FORM of an
     *       exotic JSON number ({@code 1e5}, {@code 1.50}), because each layer's deserializer chooses
     *       its own numeric type before {@code toString()} runs. Canonical integers agree, and a
     *       principal id is not a number in any real deployment, so this is recorded rather than
     *       normalised — normalising would mean inventing a rule neither side has. Rejecting them bought nothing: neither can produce a reader token on either
     *       side (an EMPTY id short-circuits in {@code addReaderFromPrincipal} — note that check is
     *       {@code isEmpty()}, not {@code isBlank()}, so a whitespace-only id reaches the lookups and
     *       is then NOT_FOUND and dropped), so the only effect of throwing was to convert "no token"
     *       into "no index update at all".</li>
     *   <li><b>a NULL principal — anomaly.</b> {@code CouchAcl} throws an NPE on it, so this is not
     *       a case the CMIS layer tolerates either; a named anomaly is simply a better failure.</li>
     *   <li><b>structural forms — anomaly.</b> A non-object {@code acl}, non-list {@code entries}, a
     *       non-object entry, or a non-String permission all make the CMIS side fail too (an
     *       unchecked cast that blows up at the first read). Guessing would produce an under-granted
     *       reader set that looks like a successful computation.</li>
     * </ul>
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
            if (principal == null) {
                // CouchAcl NPEs here; a named anomaly is the same fail-closed outcome, said clearly.
                throw new AclEpochAnomalyException("dependency " + docId
                        + " has an ACL entry with a null principal");
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
            // CouchAcl: `principal.toString()`, and a DB-stored ACE is a local ace, direct = true.
            aces.add(new jp.aegif.nemaki.model.Ace(principal.toString(), perms, true));
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
     * {@code parentId}, where an explicit JSON null means NO PARENT — the same as absent.
     *
     * <p>The strict "present-null is corruption" rule (increment 2e) is right for the fields the
     * epoch machinery writes ITSELF, where a null can only be damage. {@code parentId} is CMIS
     * topology written by the DAO, and "no parent" is the normal, correct state of a ROOT FOLDER —
     * whether it lands in CouchDB as an absent key or an explicit null is a serialization detail of
     * whichever path created the repository.
     *
     * <p>Found by running the gate-2 migration on the dev stack: {@code bedroom}'s root has the key
     * ABSENT and stamped fine, while {@code canopy}'s root — created by a different path — has it
     * PRESENT-null and threw. Since every walk climbs to the root, that one document would have
     * failed EVERY ACL update in that repository the moment the writer was wired. No unit test could
     * see it: they build model objects, which cannot express the distinction.
     *
     * <p>The root/orphan question is not decided here. Absent and null are the same input to the
     * inheritance-stop rule ({@code isRoot}) and to branch 2 of {@code effectiveAces} (inheriting
     * with no parent → the raw local ACEs), which is where that distinction belongs. What stays
     * strict is the part that really is corruption: a non-String or blank value.
     */
    private static String parentId(String docId, Map<String, Object> p) {
        if (!p.containsKey(FIELD_PARENT_ID) || p.get(FIELD_PARENT_ID) == null) {
            return null;
        }
        Object v = p.get(FIELD_PARENT_ID);
        if (!(v instanceof String) || ((String) v).isBlank()) {
            throw new AclEpochAnomalyException("dependency " + docId + " has a present-but-invalid "
                    + FIELD_PARENT_ID + " (non-String / blank): " + v);
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

    /**
     * Per-traversal reuse of ANCESTOR and RELATIONSHIP-ENDPOINT reads (design §4.6, ledger A3).
     *
     * <p>The authoritative walk is cache-bypassing on purpose, and that is not negotiable: an ACL
     * writer computing from a stale cache could stamp a max-epoch wrong readers set and fence out
     * the correct writer. What §4.6 DOES permit is narrower — "within ONE traversal, a child may
     * reuse the ancestor chain read by its parent; per-node revalidation still applies before each
     * node's CAS". Everything below exists to stay inside that sentence.
     *
     * <h2>What is memoised, and what is deliberately not</h2>
     *
     * <ul>
     *   <li><b>Ancestors and relationship endpoints only.</b> The SELF read at the top of each
     *       snapshot is NOT memoised, so a leaf document is never retained for its own sake.</li>
     *   <li><b>Absences are not memoised.</b> A recorded ABSENCE that is later created under the
     *       same id is exactly the case a negative dependency exists to catch; keeping it out of
     *       the map removes a whole invalidation case rather than getting it subtly wrong.</li>
     *   <li><b>Roles are not memoised.</b> The payload is the raw CouchDB document; the caller
     *       rebuilds its {@code Dependency} with ITS role. {@code Dependency.role} drives
     *       {@code isAncestorOfSameChain}, so handing a relationship-source role to a target
     *       chain would turn a legitimate shared ancestor into a reported cycle.</li>
     * </ul>
     *
     * <h2>Why it is a map and not a chain prefix</h2>
     *
     * <p>"A child's ancestor chain is a prefix of its parent's" is false here: inheritance breaks
     * stop the walk part-way, and a relationship has TWO endpoint chains. A map keyed by id
     * depends on none of that — only on "this document was already read in this traversal".
     *
     * <h2>Invalidation is mandatory, not an optimisation detail</h2>
     *
     * <p>Without {@link #invalidateAll()} on a failed revalidation this makes the writer LOOP:
     * snapshot serves a stale ancestor from the map, revalidate reads CouchDB and correctly
     * refuses, the writer restarts, and snapshot serves the same stale ancestor again. Detecting
     * the change without being able to move past it is worse than not caching at all.
     *
     * <p>The whole map is cleared rather than the differing ids. Surgical invalidation needs to
     * know exactly which dependencies changed, and a subset computed slightly wrong is a silent
     * stale read — the failure this class must not be able to cause. Clearing costs one
     * traversal's re-reads on an event (a concurrent ACL change mid-traversal) that is rare, and
     * degrades to today's behaviour rather than to a wrong answer.
     *
     * <h2>It is BOUNDED, because excluding the target is not enough</h2>
     *
     * <p>An earlier version of this class claimed that skipping the SELF read kept the map at the
     * size of the ancestor chain. That is false for a folder-heavy tree, and review caught it:
     * every visited FOLDER becomes an ancestor as soon as one of its children is snapshotted. A
     * root holding 50,000 folders with one document each retains ~50,001 raw CouchDB documents —
     * property maps and ACLs included — for the whole traversal, with nothing trimming them.
     * Unbounded growth during a 100k-node propagation risks an {@link OutOfMemoryError}, which
     * the traversal's {@code catch (Exception)} does not catch and which would abandon the
     * propagation part-way. A read cache must not be able to do that.
     *
     * <p>So it is an LRU with a hard cap. The access pattern makes that nearly free: what gets
     * reused is the upper chain shared by everything, which stays hot, while the per-branch
     * folders that caused the growth are exactly the entries with one hit each. Eviction costs a
     * re-read, never a wrong answer.
     *
     * <p>NOT thread-safe and NOT shared: one instance belongs to one traversal on one thread. A
     * {@code ThreadLocal} would be the obvious shortcut and is the one thing that must not be
     * done — on a pooled executor it outlives the traversal and starts serving unrelated work.
     */
    public static final class TraversalMemo {
        /**
         * Entries retained. Deep enough to hold any realistic ancestor chain many times over
         * (the hop cap is far below this), small enough that the worst case is bounded memory
         * rather than the repository's folder count.
         */
        static final int DEFAULT_MAX_ENTRIES = 2_048;

        private final int maxEntries;
        private final java.util.LinkedHashMap<String, Document> byId;
        private long hits;
        private long invalidations;
        private long evictions;

        public TraversalMemo() {
            this(DEFAULT_MAX_ENTRIES);
        }

        TraversalMemo(int maxEntries) {
            this.maxEntries = Math.max(1, maxEntries);
            // access-order LRU: the shared upper chain stays, transient per-branch folders go
            this.byId = new java.util.LinkedHashMap<String, Document>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Document> eldest) {
                    if (size() > TraversalMemo.this.maxEntries) {
                        TraversalMemo.this.evictions++;
                        return true;
                    }
                    return false;
                }
            };
        }

        Document get(String id) {
            Document d = byId.get(id);
            if (d != null) {
                hits++;
            }
            return d;
        }

        void put(String id, Document doc) {
            if (doc != null) {
                byId.put(id, doc);
            }
        }

        public long evictions() {
            return evictions;
        }

        /** Drop everything. MUST be called whenever a revalidation refuses the snapshot. */
        public void invalidateAll() {
            byId.clear();
            invalidations++;
        }

        public long hits() {
            return hits;
        }

        public long size() {
            return byId.size();
        }

        public long invalidations() {
            return invalidations;
        }
    }

    /**
     * Read an ancestor / endpoint, reusing this traversal's earlier read of the same document.
     *
     * <p>A memo hit does NOT go through {@link #read}, so it does not increment
     * {@code authoritativeReads} — that counter has to keep meaning "documents actually fetched"
     * or the before/after measurement of this optimisation measures nothing.
     */
    private Document readAncestor(String repositoryId, String docId, String what, TraversalMemo memo) {
        if (memo != null) {
            Document cached = memo.get(docId);
            if (cached != null) {
                return cached;
            }
        }
        Document doc = read(repositoryId, docId, what);
        if (memo != null && isMemoisable(doc)) {
            memo.put(docId, doc); // put() ignores null: absences are deliberately not memoised
        }
        return doc;
    }

    /**
     * Whether a document may be reused for the rest of this traversal.
     *
     * <p>A dependency in {@code PENDING_EPOCH} or {@code FINALIZED_NEEDS_RECONCILE} is mid-mutation:
     * the pending gate throws on it, and the finalizer is actively moving it. Memoising one is
     * memoising a value already known to be about to change, and the cost is not a wrong answer
     * but a stalled traversal — the gate would then throw for EVERY later descendant under that
     * ancestor, for the whole traversal, even after CouchDB has settled it. Before the memo those
     * descendants re-read and made progress.
     *
     * <p>Read from the raw properties rather than through {@code AclEpochFields.validate}: this
     * runs before the walk has decided anything about the document, and a malformed marker must
     * still reach the validator that reports it properly rather than being swallowed here.
     */
    private static boolean isMemoisable(Document doc) {
        if (doc == null) {
            return false;
        }
        Object state = doc.getProperties() == null
                ? null : doc.getProperties().get(AclEpochState.FIELD_STATE);
        if (state == null) {
            return true; // state-less = ordinary settled content
        }
        String v = state.toString();
        return !AclEpochState.PENDING_EPOCH.equals(v)
                && !AclEpochState.FINALIZED_NEEDS_RECONCILE.equals(v);
    }

    private Document read(String repositoryId, String docId, String what) {
        // Counted so the cost of the authoritative walk can be measured directly. It cannot be
        // measured from CouchDB's server-wide database_reads: on an idle dev stack that counter
        // still moves at ~6 reads/s from schedulers, and one ACL propagation over 26 nodes is
        // ~185 reads spread across a 300s settle — the background swamps the signal and the
        // subtraction goes negative. A counter on the exact call site has no such problem.
        authoritativeReadCount.incrementAndGet();
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

    /**
     * The repository's root-folder id. REQUIRED (review P1-1 / P2-4): both the walk's stop rule and
     * the readers projection depend on it, so a missing wiring must fail loudly rather than let the
     * two sides diverge.
     */
    private String requireRootFolderId(String repositoryId) {
        if (repositoryInfoMap == null) {
            throw new AclEpochWiringException("repositoryInfoMap not wired on AclEffectiveEpochService "
                    + "— it is REQUIRED: the inheritance-stop rule and the readers projection both "
                    + "need the root-folder id");
        }
        jp.aegif.nemaki.cmis.factory.info.RepositoryInfo info = repositoryInfoMap.get(repositoryId);
        if (info == null || info.getRootFolderId() == null || info.getRootFolderId().isBlank()) {
            throw new AclEpochWiringException("no usable RepositoryInfo / root-folder id for repository '"
                    + repositoryId + "'");
        }
        return info.getRootFolderId();
    }

    private CloudantClientWrapper contentClient(String repositoryId) {
        if (connectorPool == null) {
            throw new AclEpochWiringException("connectorPool not wired on AclEffectiveEpochService");
        }
        CloudantClientWrapper client = connectorPool.getClient(repositoryId);
        if (client == null) {
            throw new AclEpochWiringException("content DB client not available for repository '"
                    + repositoryId + "'");
        }
        return client;
    }
}
