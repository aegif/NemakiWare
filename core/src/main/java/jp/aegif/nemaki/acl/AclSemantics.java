package jp.aegif.nemaki.acl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.util.constant.PrincipalId;

/**
 * The ONE implementation of the ACL semantics (design {@code docs/design/acl-epoch-fencing.md}
 * §5.3 — increments 5R-b and 5S):
 * <ul>
 *   <li>the inheritance MERGE ({@link #mergeAces}) and the system-principal CONVERSION
 *       ({@link #convertSystemPrincipalIds}) — 5R-b step 1;</li>
 *   <li>the TOKEN projection ({@link #readerTokens} with its {@link PrincipalResolver}) and the
 *       relationship endpoint UNION ({@link #relationshipReaders}) — 5R-b step 2;</li>
 *   <li>the SHAPE of the traversal ({@link ChainNode}, {@link #effectiveAces}, {@link #resolveAcl})
 *       — 5S. Sharing the merge alone was not enough: the recursion has three branches and a second
 *       implementation of THOSE drifts just as easily.</li>
 * </ul>
 *
 * <p>The point of this class is to separate SEMANTICS from FETCH. The merge and the branch selection
 * used to live inside {@code AclServiceDelegate}, welded to a traversal that resolves ancestors
 * through the CACHED content DAO. The ACL-epoch work needs the same semantics over an
 * AUTHORITATIVE (raw CouchDB) traversal, and re-implementing the merge there is precisely how the
 * two sides diverge — every defect found in increments 3a/3b/4b was a symptom of two traversals
 * disagreeing. The traversal therefore stays with each caller; the meaning of the ACEs lives here.
 *
 * <p><b>This is a BEHAVIOUR-PRESERVING extraction.</b> It is pinned by
 * {@code AclSemanticsGoldenTest}: the golden must not move by one byte. In particular two
 * non-obvious properties of the original code are preserved deliberately:
 * <ul>
 *   <li>{@link #mergeAces} CONVERTS THE TARGET LIST IN PLACE before merging. The target is the
 *       node's own live {@code Acl.getLocalAces()} list, so the conversion is visible on the
 *       Content object afterwards (and therefore on the cached Content). Making the merge
 *       side-effect-free here would be a behaviour change, not a cleanup.</li>
 *   <li>The result is built through a {@code HashMap}, so the ORDER of the returned list is not
 *       contractual and must not be relied upon.</li>
 * </ul>
 *
 * <p>The direct/inherited convention follows the original: everything in {@code target} becomes
 * {@code direct = true}; everything in {@code source} that the target does not already name becomes
 * {@code direct = false}; a principal named by both is taken from the TARGET (the nearer node wins).
 */
public final class AclSemantics {

    private AclSemantics() {}

    /**
     * Merge a node's own ACEs ({@code target}) with the ACEs inherited from above ({@code source}).
     *
     * <p>Byte-for-byte the original {@code AclServiceDelegate.mergeAcl}, including the in-place
     * conversion of {@code target} described on the class Javadoc.
     *
     * @param target       the nearer node's ACEs — MUTATED IN PLACE by the system-principal
     *                     conversion, exactly as before
     * @param source       the ACEs coming from further up the chain
     * @param anyoneId     the repository's "anyone" principal id ({@code principal.anyone})
     * @param anonymousId  the repository's "anonymous" principal id ({@code principal.anonymous})
     */
    public static List<Ace> mergeAces(List<Ace> target, List<Ace> source,
                                      String anyoneId, String anonymousId) {
        HashMap<String, Ace> result = new HashMap<String, Ace>();

        convertSystemPrincipalIds(target, anyoneId, anonymousId);

        HashMap<String, Ace> targetMap = buildAceMap(target);
        HashMap<String, Ace> sourceMap = buildAceMap(source);

        for (Map.Entry<String, Ace> t : targetMap.entrySet()) {
            Ace ace = deepCopy(t.getValue());
            ace.setDirect(true);
            result.put(t.getKey(), ace);
        }

        for (Map.Entry<String, Ace> s : sourceMap.entrySet()) {
            if (!targetMap.containsKey(s.getKey())) {
                Ace ace = deepCopy(s.getValue());
                ace.setDirect(false);
                result.put(s.getKey(), ace);
            }
        }

        List<Ace> resultList = new ArrayList<Ace>();
        for (Map.Entry<String, Ace> r : result.entrySet()) {
            resultList.add(r.getValue());
        }
        return resultList;
    }

    /**
     * Rewrite the in-database system principal ids to the repository's configured ids, IN PLACE.
     *
     * <p>{@code CMIS_ANYONE} → {@code principal.anyone} (shipped: {@code GROUP_EVERYONE}) and
     * {@code CMIS_ANONYMOUS} → {@code principal.anonymous} (shipped: {@code anonymous}). Note that
     * the shipped "anyone" id is a real GroupItem, which is why the reader-token layer emits a
     * GROUP token for it rather than a dedicated {@code anyone:} token — see the cross-path report.
     *
     * <p>No guard on a null/unresolvable configured id: writing null through is the CURRENT
     * behaviour. Hardening it is a MISCONFIGURATION concern ({@code principal.anyone} unset or
     * unresolvable) that 5R-a flagged and 5T did not cover — 5T tri-stated the principal DAO, not the
     * configured system-principal ids. Recorded in design §10 as a known adjacent issue; the token
     * layer is safe today (a null id short-circuits, an unresolvable one is NOT_FOUND and dropped).
     *
     * <p><b>The one deliberate deviation from the original</b> (review P3, stated plainly rather
     * than glossed): the original had no {@code aces == null} check and would have thrown an NPE.
     * This returns quietly instead. No production path passes null — every caller hands in a list
     * obtained from an {@code Acl} — so the deviation is unreachable today; it is called out here
     * so "behaviour-preserving" is not overstated.
     */
    public static void convertSystemPrincipalIds(List<Ace> aces, String anyoneId, String anonymousId) {
        if (aces == null) {
            return;
        }
        for (Ace ace : aces) {
            if (PrincipalId.ANONYMOUS_IN_DB.equals(ace.getPrincipalId())) {
                ace.setPrincipalId(anonymousId);
            }
            if (PrincipalId.ANYONE_IN_DB.equals(ace.getPrincipalId())) {
                ace.setPrincipalId(anyoneId);
            }
        }
    }

    // ── traversal SHAPE (increment 5S) ─────────────────────────────

    /**
     * One node of an inheritance chain, as the ACL semantics need to see it. The TRAVERSAL stays
     * with each caller — the CMIS delegate resolves a parent lazily through the cached
     * {@code getFolder}, the ACL-epoch side hands over documents it already read authoritatively —
     * but the SHAPE of the recursion below is shared, which is the whole point of 5S.
     *
     * <p>Sharing only {@link #mergeAces} was not enough: the shape has three branches (root /
     * non-inheriting, inheriting-with-no-parent, and the ordinary merge), and a second
     * implementation of THOSE is exactly the increment-3b defect class — two traversals that agree
     * on the merge but disagree on when to stop.
     */
    public interface ChainNode {
        /** Identity, used only for the strict failure message. */
        String id();

        /**
         * The node's own LOCAL ACEs. Never null — a caller whose stored ACL is missing substitutes
         * an empty list (and logs, as the delegate always has).
         *
         * <p>MUTATED IN PLACE by the system-principal conversion inside {@link #mergeAces}, exactly
         * as before.
         */
        List<Ace> localAces();

        /**
         * The node's STORED {@code Acl}, returned as-is by {@link #resolveAcl}'s root /
         * non-inheriting branch (the delegate's {@code content.getAcl()}).
         */
        jp.aegif.nemaki.model.Acl storedAcl();

        /** {@code contentService.isRoot} for this node. */
        boolean root();

        /** {@code getAclInheritedWithDefault} for this node (an ABSENT flag means TRUE). */
        boolean inherited();

        /** The parent id, or {@code null}. */
        String parentId();

        /**
         * The parent node, or {@code null} if it cannot be resolved. Note that the production
         * {@code getFolder} collapses a genuine 404 AND a transient read failure into null, which
         * is precisely why {@code strict} exists below.
         */
        ChainNode parent();
    }

    /**
     * Resolve a node's effective {@code Acl} — the shape of
     * {@code AclServiceDelegate.calculateAcl}, minus the caching, which stays with the delegate.
     *
     * <p>Behaviour-preserving: pinned by {@code AclSemanticsGoldenTest}.
     *
     * @param strict an inheriting node whose parent does not resolve THROWS instead of degrading to
     *               local-ACEs-only (the search-index re-drive contract)
     */
    public static jp.aegif.nemaki.model.Acl resolveAcl(ChainNode node, boolean strict,
                                                       String anyoneId, String anonymousId) {
        jp.aegif.nemaki.model.Acl acl;
        if (!node.root() && node.inherited()) {
            List<Ace> result = effectiveAces(node, strict, anyoneId, anonymousId);
            acl = new jp.aegif.nemaki.model.Acl();
            for (Ace r : result) {
                if (r.isDirect()) {
                    acl.getLocalAces().add(r);
                } else {
                    acl.getInheritedAces().add(r);
                }
            }
        } else {
            acl = node.storedAcl();
        }
        convertSystemPrincipalIds(acl.getAllAces(), anyoneId, anonymousId);
        return acl;
    }

    /**
     * The inheritance recursion itself — byte-for-byte the shape of
     * {@code AclServiceDelegate.calculateAclInternal}.
     *
     * <p>Three branches, all preserved deliberately:
     * <ol>
     *   <li><b>root or non-inheriting</b> — the node's ACEs become {@code direct = true} and are
     *       merged as the SOURCE under everything accumulated below them;</li>
     *   <li><b>inheriting but parentless</b> — the RAW local ACEs are returned, with no direct
     *       flags and no conversion. This asymmetry is real behaviour (corpus case
     *       {@code orphan-no-parent-but-inherits}), not an oversight to tidy up here;</li>
     *   <li><b>otherwise</b> — {@code merge(target = own ACEs, source = the ancestors' result)}, so
     *       the nearer node wins.</li>
     * </ol>
     */
    public static List<Ace> effectiveAces(ChainNode node, boolean strict,
                                          String anyoneId, String anonymousId) {
        return effectiveAcesInternal(node, new ArrayList<Ace>(), strict, anyoneId, anonymousId);
    }

    private static List<Ace> effectiveAcesInternal(ChainNode node, List<Ace> result, boolean strict,
                                                   String anyoneId, String anonymousId) {
        List<Ace> aces = node.localAces();

        if (node.root() || !node.inherited()) {
            List<Ace> rootAces = new ArrayList<Ace>();
            for (Ace ace : aces) {
                Ace rootAce = deepCopy(ace);
                rootAce.setDirect(true);
                rootAces.add(rootAce);
            }
            return mergeAces(result, rootAces, anyoneId, anonymousId);
        }
        if (node.parentId() == null) {
            return aces;
        }
        ChainNode parent = node.parent();
        if (parent == null) {
            if (strict) {
                throw new IllegalStateException("Strict ACL: parent " + node.parentId()
                        + " of " + node.id() + " is unreadable — cannot compute inherited ACL");
            }
            return aces;
        }
        return mergeAces(aces,
                effectiveAcesInternal(parent, new ArrayList<Ace>(), strict, anyoneId, anonymousId),
                anyoneId, anonymousId);
    }

    /** Last-wins map keyed by principal id (the original {@code buildAceMap}). */
    private static HashMap<String, Ace> buildAceMap(List<Ace> aces) {
        HashMap<String, Ace> map = new HashMap<String, Ace>();
        for (Ace ace : aces) {
            map.put(ace.getPrincipalId(), ace);
        }
        return map;
    }

    // ── token layer ────────────────────────────────────────────────

    /**
     * How a (already system-converted) principal id resolves. Supplied by the caller so this class
     * stays free of I/O: the CMIS runtime resolves through the cached principal service, the
     * ACL-epoch side will resolve authoritatively.
     *
     * <p>NOTE the ORDER the production code uses and this contract preserves: USER is tried FIRST,
     * then GROUP. Reversing it would change which token a principal that somehow exists as both
     * receives.
     */
    public interface PrincipalResolver {
        /**
         * TRI-STATE user probe. Tried FIRST (the production order).
         *
         * <p>{@link PrincipalLookup#NOT_FOUND} means "served, genuinely absent" and falls through to
         * the group probe; {@link PrincipalLookup#UNAVAILABLE} means "could not be served" and makes
         * {@link AclSemantics#readerTokens} THROW rather than silently shorten the token set.
         */
        PrincipalLookup lookupUser(String repositoryId, String principalId);

        /** TRI-STATE group probe. Tried only when {@link #lookupUser} is NOT_FOUND. */
        PrincipalLookup lookupGroup(String repositoryId, String principalId);
    }

    /**
     * Project effective ACEs to reader tokens — the three rules the cross-path report pinned as
     * belonging to THIS layer (increment 5R-a):
     * <ol>
     *   <li><b>read filter</b> — an ACE without {@code cmis:read} / {@code cmis:all} yields nothing;</li>
     *   <li><b>fail-closed fallback</b> — a null/empty ACE set, OR nothing surviving rule 1 and 3,
     *       becomes ADMIN-ONLY. Never an empty token list, which would make the object invisible;</li>
     *   <li><b>principal resolution</b> — the literal {@code cmis:anyone}/{@code cmis:anonymous}
     *       ids map to the anyone token; otherwise USER then GROUP; an id that resolves to neither
     *       is DROPPED when the lookup was SERVED ({@link PrincipalLookup#NOT_FOUND}) — correct for
     *       a genuinely deleted principal. When the lookup could NOT be served
     *       ({@link PrincipalLookup#UNAVAILABLE}, i.e. a missing design document / view / database)
     *       this THROWS {@link PrincipalUnavailableException} instead of dropping, because dropping
     *       would degrade every principal to "absent" and hand rule 2 an admin-only set as if it had
     *       been computed. A TRANSIENT fault never reaches here at all: it propagates out of the DAO
     *       as {@code CmisRuntimeException}. No failure mode of this rule can WIDEN the token set —
     *       5T is an availability / index-health guarantee, not a confidentiality one.</li>
     * </ol>
     *
     * <p>The anyone branch is preserved VERBATIM even though it is dead code in the shipped
     * configuration ({@code principal.anyone = GROUP_EVERYONE}, so the converted id arrives here as
     * a GROUP and leaves as a group token). Removing it — or conversely routing the shipped anyone
     * id to an {@code anyone:} token — would be a behaviour change requiring a full reindex.
     */
    public static List<String> readerTokens(String repositoryId, List<Ace> aces,
                                            PrincipalResolver resolver) {
        if (aces == null || aces.isEmpty()) {
            return adminOnlyReaders(repositoryId);
        }
        java.util.Set<String> readers = new java.util.LinkedHashSet<String>();
        for (Ace ace : aces) {
            if (!hasReadPermission(ace)) {
                continue;
            }
            addReaderFromPrincipal(repositoryId, ace.getPrincipalId(), readers, resolver);
        }
        if (readers.isEmpty()) {
            return adminOnlyReaders(repositoryId);
        }
        return new ArrayList<String>(readers);
    }

    /**
     * Increment 5T. A null outcome from a resolver is treated as UNAVAILABLE, not as absence: a
     * resolver that cannot answer must never be read as "the principal does not exist".
     */
    private static PrincipalLookup require(PrincipalLookup outcome, String principalId, String kind) {
        if (outcome == null || outcome == PrincipalLookup.UNAVAILABLE) {
            throw new PrincipalUnavailableException("principal " + kind + " lookup could not be served for '"
                    + principalId + "' — refusing to project an under-granted reader set");
        }
        return outcome;
    }

    /** Rule 1: {@code cmis:read} or {@code cmis:all}, case-insensitive (the original). */
    public static boolean hasReadPermission(Ace ace) {
        List<String> permissions = ace.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        for (String permission : permissions) {
            if (PERMISSION_READ.equalsIgnoreCase(permission) || PERMISSION_ALL.equalsIgnoreCase(permission)) {
                return true;
            }
        }
        return false;
    }

    /** Rule 3, in the production order: anyone/anonymous literal, then USER, then GROUP, else drop. */
    private static void addReaderFromPrincipal(String repositoryId, String principalId,
                                               java.util.Set<String> readers, PrincipalResolver resolver) {
        if (principalId == null || principalId.isEmpty()) {
            return;
        }
        if (PRINCIPAL_ANYONE.equalsIgnoreCase(principalId) || PRINCIPAL_ANONYMOUS.equalsIgnoreCase(principalId)) {
            readers.add(formatAnyoneReader(repositoryId));
            return;
        }
        if (resolver == null) {
            return;
        }
        PrincipalLookup user = require(resolver.lookupUser(repositoryId, principalId), principalId, "user");
        if (user == PrincipalLookup.FOUND) {
            readers.add(formatUserReader(repositoryId, principalId));
            return;
        }
        PrincipalLookup group = require(resolver.lookupGroup(repositoryId, principalId), principalId, "group");
        if (group == PrincipalLookup.FOUND) {
            readers.add(formatGroupReader(repositoryId, principalId));
        }
    }

    /**
     * The union of a relationship's endpoint reader tokens (dedup, source order first). A null side
     * contributes nothing; two null sides yield an EMPTY list, which is fail-closed — the query-side
     * readers filter then excludes the relationship for every non-admin caller.
     *
     * <p>A relationship's OWN local ACL is deliberately not an input: the cross-path report showed
     * that expanding a relationship as ordinary content collapses to admin-only, losing BOTH
     * endpoint chains.
     */
    public static List<String> relationshipReaders(List<String> sourceReaders, List<String> targetReaders) {
        java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<String>();
        if (sourceReaders != null) {
            union.addAll(sourceReaders);
        }
        if (targetReaders != null) {
            union.addAll(targetReaders);
        }
        return new ArrayList<String>(union);
    }

    // ── token formatting (the canonical, repository-scoped forms) ──

    public static final String PREFIX_USER = "user:";
    public static final String PREFIX_GROUP = "group:";
    public static final String READER_ANYONE = "anyone";
    public static final String READER_ADMIN = "admin";
    private static final String PERMISSION_READ = "cmis:read";
    private static final String PERMISSION_ALL = "cmis:all";
    private static final String PRINCIPAL_ANYONE = "cmis:anyone";
    private static final String PRINCIPAL_ANONYMOUS = "cmis:anonymous";

    public static String formatUserReader(String repositoryId, String userId) {
        return PREFIX_USER + repositoryId + ":" + userId;
    }

    public static String formatGroupReader(String repositoryId, String groupId) {
        return PREFIX_GROUP + repositoryId + ":" + groupId;
    }

    public static String formatAnyoneReader(String repositoryId) {
        return READER_ANYONE + ":" + repositoryId;
    }

    public static String formatAdminReader(String repositoryId) {
        return READER_ADMIN + ":" + repositoryId;
    }

    /** Rule 2's fail-closed value: admin only, never an empty list. */
    public static List<String> adminOnlyReaders(String repositoryId) {
        List<String> readers = new ArrayList<String>();
        readers.add(formatAdminReader(repositoryId));
        return readers;
    }

    /** Copy an ACE, normalizing a null/empty permission list to an empty one (the original). */
    public static Ace deepCopy(Ace ace) {
        Ace result = new Ace();
        result.setPrincipalId(ace.getPrincipalId());
        result.setDirect(ace.isDirect());
        List<String> permissions = ace.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            result.setPermissions(new ArrayList<String>());
        } else {
            result.setPermissions(new ArrayList<String>(permissions));
        }
        return result;
    }
}
