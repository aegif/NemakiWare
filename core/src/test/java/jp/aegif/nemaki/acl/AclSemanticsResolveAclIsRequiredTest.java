package jp.aegif.nemaki.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.util.constant.PrincipalId;

/**
 * WHY the ACL-epoch readers projection must go through {@link AclSemantics#resolveAcl} and NOT
 * through {@link AclSemantics#effectiveAces} alone (increment 5S step 2).
 *
 * <p>The review suggested {@code effectiveAces + readerTokens} would be enough. It is not, and the
 * gap is a fail-CLOSED one, so it would not have announced itself: an object would simply stop
 * being findable by everyone except an admin.
 *
 * <p>The mechanism is an asymmetry in {@link AclSemantics#mergeAces}: it converts the system
 * principal ids of its TARGET in place, never of its SOURCE. An ancestor's ACEs always arrive as the
 * SOURCE, so an INHERITED {@code CMIS_ANYONE} is still {@code CMIS_ANYONE} when
 * {@code effectiveAces} returns. The conversion that fixes it lives at the very end of
 * {@code calculateAcl} — i.e. inside {@link AclSemantics#resolveAcl}.
 *
 * <p>And {@code readerTokens} cannot recover: its literal branch matches {@code "cmis:anyone"}
 * (the CMIS spec spelling) while the database spelling is {@code "CMIS_ANYONE"}. So an unconverted
 * id falls through to the USER probe, then the GROUP probe, matches neither, and is DROPPED — after
 * which rule 2 turns the whole set into ADMIN-ONLY.
 *
 * <p>This test does not assert which token is "right" in the abstract; it asserts that the two
 * projections DISAGREE, and that the {@code resolveAcl} one is the projection the CMIS runtime
 * actually produces (which is the contract the index has to match).
 */
public class AclSemanticsResolveAclIsRequiredTest {

    private static final String REPO = "conv-repo";
    /** The shipped {@code principal.anyone}: a REAL GroupItem, hence a group token. */
    private static final String ANYONE_CONFIGURED = "GROUP_EVERYONE";
    private static final String ANONYMOUS_CONFIGURED = "anonymous";

    /** Resolves GROUP_EVERYONE as a group and nothing else — the shipped configuration. */
    private static final AclSemantics.PrincipalResolver RESOLVER = new AclSemantics.PrincipalResolver() {
        @Override public PrincipalLookup lookupUser(String repositoryId, String principalId) {
            return PrincipalLookup.NOT_FOUND;
        }
        @Override public PrincipalLookup lookupGroup(String repositoryId, String principalId) {
            return ANYONE_CONFIGURED.equals(principalId) ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
        }
    };

    @Test
    public void inheritedSystemPrincipalIsLostWhenTheFinalConversionIsSkipped() {
        Node root = new Node("root", null, false, true, ace(PrincipalId.ANYONE_IN_DB, "cmis:read"));
        Node leaf = new Node("leaf", root, true, false);

        List<String> viaEffectiveAcesOnly = AclSemantics.readerTokens(REPO,
                AclSemantics.effectiveAces(leaf, false, ANYONE_CONFIGURED, ANONYMOUS_CONFIGURED),
                RESOLVER);

        // Rebuild — mergeAces mutates ACE objects in place, so each projection needs a fresh chain.
        Node root2 = new Node("root", null, false, true, ace(PrincipalId.ANYONE_IN_DB, "cmis:read"));
        Node leaf2 = new Node("leaf", root2, true, false);

        List<String> viaResolveAcl = AclSemantics.readerTokens(REPO,
                AclSemantics.resolveAcl(leaf2, false, ANYONE_CONFIGURED, ANONYMOUS_CONFIGURED).getAllAces(),
                RESOLVER);

        assertNotEquals(viaResolveAcl, viaEffectiveAcesOnly,
                "if these ever agree, the asymmetry this test exists for has been fixed elsewhere "
                        + "and the epoch side may be simplified — until then effectiveAces alone is wrong");

        // effectiveAces alone: the inherited CMIS_ANYONE is unconverted, matches neither the
        // "cmis:anyone" literal nor any user/group, is dropped, and rule 2 fails the set closed.
        assertEquals(AclSemantics.adminOnlyReaders(REPO), viaEffectiveAcesOnly,
                "an unconverted inherited system principal collapses to ADMIN-ONLY — a stale-DENY, "
                        + "which is exactly the failure class that hides itself");

        // resolveAcl: the id is converted to the configured GROUP_EVERYONE and emitted as a group
        // token, which is what the query side grants to every authenticated user.
        assertTrue(viaResolveAcl.contains(AclSemantics.formatGroupReader(REPO, ANYONE_CONFIGURED)),
                "expected the converted group token, got " + viaResolveAcl);
    }

    @Test
    public void aDirectSystemPrincipalIsConvertedByTheMergeItself() {
        // The counterpart, so the asymmetry is pinned in BOTH directions: a system principal on the
        // node itself arrives as the merge TARGET and IS converted in place, so both projections
        // agree here. Only the INHERITED direction needs resolveAcl.
        Node root = new Node("root", null, false, true, ace("someone", "cmis:read"));
        Node leaf = new Node("leaf", root, true, false, ace(PrincipalId.ANYONE_IN_DB, "cmis:read"));

        List<String> viaEffectiveAcesOnly = AclSemantics.readerTokens(REPO,
                AclSemantics.effectiveAces(leaf, false, ANYONE_CONFIGURED, ANONYMOUS_CONFIGURED),
                RESOLVER);

        assertTrue(viaEffectiveAcesOnly.contains(AclSemantics.formatGroupReader(REPO, ANYONE_CONFIGURED)),
                "a DIRECT system principal is converted by mergeAces' in-place target conversion, "
                        + "got " + viaEffectiveAcesOnly);
    }

    // ── minimal ChainNode over a fixed chain ───────────────────────

    private static Ace ace(String principalId, String... permissions) {
        Ace a = new Ace();
        a.setPrincipalId(principalId);
        a.setPermissions(new ArrayList<String>(Arrays.asList(permissions)));
        return a;
    }

    private static final class Node implements AclSemantics.ChainNode {
        private final String id;
        private final Node parent;
        private final boolean inherited;
        private final boolean root;
        private final Acl acl = new Acl();

        Node(String id, Node parent, boolean inherited, boolean root, Ace... aces) {
            this.id = id; this.parent = parent; this.inherited = inherited; this.root = root;
            this.acl.getLocalAces().addAll(Arrays.asList(aces));
        }

        @Override public String id() { return id; }
        @Override public List<Ace> localAces() { return acl.getLocalAces(); }
        @Override public Acl storedAcl() { return acl; }
        @Override public boolean root() { return root; }
        @Override public boolean inherited() { return inherited; }
        @Override public String parentId() { return parent == null ? null : parent.id(); }
        @Override public AclSemantics.ChainNode parent() { return parent; }
    }
}
