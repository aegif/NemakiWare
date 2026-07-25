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
