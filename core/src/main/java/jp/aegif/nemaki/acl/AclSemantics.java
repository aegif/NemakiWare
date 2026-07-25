package jp.aegif.nemaki.acl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.util.constant.PrincipalId;

/**
 * The ONE implementation of the ACL semantics: the inheritance MERGE and the system-principal
 * CONVERSION (design {@code docs/design/acl-epoch-fencing.md} §5.3 — increment 5R-b).
 *
 * <p>The point of this class is to separate SEMANTICS from FETCH. Until now the merge lived inside
 * {@code AclServiceDelegate.calculateAclInternal}, welded to a traversal that resolves ancestors
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
     * behaviour, and hardening it belongs with the principal tri-state work (§5.2 wiring gate), not
     * with a behaviour-preserving extraction.
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
        /** True if the id resolves to a user. Tried FIRST. */
        boolean isUser(String repositoryId, String principalId);
        /** True if the id resolves to a group. Tried only when {@link #isUser} is false. */
        boolean isGroup(String repositoryId, String principalId);
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
     *       is DROPPED (the under-grant the principal tri-state work will convert to a
     *       distinguishable failure).</li>
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
        if (resolver != null && resolver.isUser(repositoryId, principalId)) {
            readers.add(formatUserReader(repositoryId, principalId));
            return;
        }
        if (resolver != null && resolver.isGroup(repositoryId, principalId)) {
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
