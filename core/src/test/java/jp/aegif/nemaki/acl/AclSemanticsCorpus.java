package jp.aegif.nemaki.acl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.constant.PrincipalId;

/**
 * The DETERMINISTIC corpus of ACL inheritance chains used to pin the CURRENT behaviour of the ACL
 * semantics before they are extracted into pure functions (design §5.3, increment 5R-a).
 *
 * <p>This file contains DATA ONLY — no assertions and no production imports beyond the model — so
 * that the same corpus can drive the golden capture (5R-a), the post-extraction comparison (5R-b)
 * and, later, the shared-function tests. It must stay deterministic: no randomness, no clock, and a
 * stable iteration order, because its output is committed as a golden file.
 *
 * <p><b>Scope (fixed by review):</b> known principals and a stable principal DAO only. Fault
 * injection is deliberately absent — {@code UNAVAILABLE → throw} is a 5T behaviour CHANGE with its
 * own ITs, and mixing it in here would blur the "zero behaviour change" claim of 5R.
 */
public final class AclSemanticsCorpus {

    private AclSemanticsCorpus() {}

    public static final String REPO = "corpus-repo";
    public static final String ROOT_ID = "root-folder";

    /** One node of a chain: its id, its local ACEs, and whether it inherits. */
    public static final class Node {
        public final String id;
        public final String parentId;
        public final Boolean aclInherited; // null = absent (defaults to TRUE in the current code)
        public final boolean folder;
        public final List<Ace> localAces;

        Node(String id, String parentId, Boolean aclInherited, boolean folder, List<Ace> localAces) {
            this.id = id;
            this.parentId = parentId;
            this.aclInherited = aclInherited;
            this.folder = folder;
            this.localAces = localAces;
        }

        /** The model object the production code expects (a Folder or a Document). */
        public Content toContent() {
            Content c = folder ? new Folder() : new Document();
            c.setId(id);
            c.setName(id);
            c.setParentId(parentId);
            c.setAclInherited(aclInherited);
            Acl acl = new Acl();
            for (Ace a : localAces) {
                acl.getLocalAces().add(copy(a));
            }
            c.setAcl(acl);
            return c;
        }
    }

    /** A named case: the chain (leaf first, root last) whose LEAF is the subject. */
    public static final class Case {
        public final String name;
        public final List<Node> chain;

        Case(String name, List<Node> chain) {
            this.name = name;
            this.chain = chain;
        }

        public Node leaf() { return chain.get(0); }

        /** id → Content, for the mocked {@code getFolder} lookup. */
        public Map<String, Content> byId() {
            Map<String, Content> m = new LinkedHashMap<>();
            for (Node n : chain) {
                m.put(n.id, n.toContent());
            }
            return m;
        }
    }

    // ── ACE builders ───────────────────────────────────────────────

    public static Ace ace(String principalId, String... permissions) {
        Ace a = new Ace();
        a.setPrincipalId(principalId);
        a.setPermissions(new ArrayList<>(Arrays.asList(permissions)));
        return a;
    }

    /**
     * An ACE whose STORED {@code direct} flag is set explicitly.
     *
     * <p>{@link #ace} leaves {@code direct} at its default {@code false}, which is also what the
     * merge produces for a source-side ACE — so a case built only from {@link #ace} cannot tell
     * "returned RAW" from "returned merged". That blind spot hid one of the three branches of the
     * inheritance recursion (increment 5S step 1 reported it; this closes it).
     */
    public static Ace aceDirect(String principalId, boolean direct, String... permissions) {
        Ace a = ace(principalId, permissions);
        a.setDirect(direct);
        return a;
    }

    private static Ace copy(Ace a) {
        Ace c = new Ace();
        c.setPrincipalId(a.getPrincipalId());
        c.setPermissions(a.getPermissions() == null ? null : new ArrayList<>(a.getPermissions()));
        c.setDirect(a.isDirect());
        return c;
    }

    private static Node folder(String id, String parentId, Boolean inherited, Ace... aces) {
        return new Node(id, parentId, inherited, true, new ArrayList<>(Arrays.asList(aces)));
    }

    private static Node doc(String id, String parentId, Boolean inherited, Ace... aces) {
        return new Node(id, parentId, inherited, false, new ArrayList<>(Arrays.asList(aces)));
    }

    /** The repository root: no parent, and the production code treats it as non-inheriting. */
    private static Node root(Ace... aces) {
        return folder(ROOT_ID, null, Boolean.FALSE, aces);
    }

    // ── the corpus ─────────────────────────────────────────────────

    /**
     * Every case is a chain LEAF-FIRST. The dimensions crossed here are the ones the extraction
     * must preserve exactly: inherit true / false / ABSENT, root vs non-root, a principal appearing
     * at several depths (direct must win over inherited), permission sets that differ between
     * depths, the system principals that {@code convertSystemPrincipalId} rewrites, an empty local
     * ACL, and chains deep enough that a merge-order mistake shows up.
     */
    public static List<Case> cases() {
        List<Case> out = new ArrayList<>();

        out.add(new Case("leaf-inherits-from-root",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE, ace("u1", "cmis:read")),
                        root(ace("u2", "cmis:read")))));

        out.add(new Case("leaf-does-not-inherit",
                List.of(doc("leaf", ROOT_ID, Boolean.FALSE, ace("u1", "cmis:read")),
                        root(ace("u2", "cmis:read")))));

        out.add(new Case("leaf-inherited-flag-ABSENT-defaults-to-inherit",
                List.of(doc("leaf", ROOT_ID, null, ace("u1", "cmis:read")),
                        root(ace("u2", "cmis:read")))));

        out.add(new Case("three-level-chain",
                List.of(doc("leaf", "mid", Boolean.TRUE, ace("u1", "cmis:read")),
                        folder("mid", ROOT_ID, Boolean.TRUE, ace("u2", "cmis:write")),
                        root(ace("u3", "cmis:all")))));

        out.add(new Case("mid-breaks-inheritance-root-must-not-contribute",
                List.of(doc("leaf", "mid", Boolean.TRUE, ace("u1", "cmis:read")),
                        folder("mid", ROOT_ID, Boolean.FALSE, ace("u2", "cmis:write")),
                        root(ace("u3", "cmis:all")))));

        out.add(new Case("same-principal-at-two-depths-direct-wins",
                List.of(doc("leaf", "mid", Boolean.TRUE, ace("dup", "cmis:read")),
                        folder("mid", ROOT_ID, Boolean.TRUE, ace("dup", "cmis:all")),
                        root(ace("u3", "cmis:read")))));

        out.add(new Case("same-principal-at-three-depths",
                List.of(doc("leaf", "mid", Boolean.TRUE, ace("dup", "cmis:read")),
                        folder("mid", "upper", Boolean.TRUE, ace("dup", "cmis:write")),
                        folder("upper", ROOT_ID, Boolean.TRUE, ace("dup", "cmis:all")),
                        root(ace("dup", "cmis:read")))));

        out.add(new Case("leaf-with-EMPTY-local-acl",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE),
                        root(ace("u2", "cmis:read")))));

        out.add(new Case("root-with-EMPTY-local-acl",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE, ace("u1", "cmis:read")),
                        root())));

        out.add(new Case("both-EMPTY",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE), root())));

        out.add(new Case("system-principal-anyone-in-db-is-converted",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE, ace(PrincipalId.ANYONE_IN_DB, "cmis:read")),
                        root(ace("u2", "cmis:read")))));

        out.add(new Case("system-principal-anonymous-in-db-is-converted",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE, ace(PrincipalId.ANONYMOUS_IN_DB, "cmis:read")),
                        root(ace("u2", "cmis:read")))));

        out.add(new Case("system-principal-INHERITED-is-converted",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE, ace("u1", "cmis:read")),
                        root(ace(PrincipalId.ANYONE_IN_DB, "cmis:read")))));

        out.add(new Case("multi-permission-ace",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE, ace("u1", "cmis:read", "cmis:write")),
                        root(ace("u2", "cmis:all", "cmis:read")))));

        out.add(new Case("several-principals-per-level",
                List.of(doc("leaf", "mid", Boolean.TRUE, ace("a", "cmis:read"), ace("b", "cmis:write")),
                        folder("mid", ROOT_ID, Boolean.TRUE, ace("c", "cmis:read"), ace("a", "cmis:all")),
                        root(ace("d", "cmis:read"), ace("b", "cmis:all")))));

        out.add(new Case("the-ROOT-itself-is-the-subject",
                List.of(root(ace("u1", "cmis:read")))));

        out.add(new Case("folder-subject-not-document",
                List.of(folder("leaf", ROOT_ID, Boolean.TRUE, ace("u1", "cmis:read")),
                        root(ace("u2", "cmis:read")))));

        out.add(new Case("orphan-no-parent-but-inherits",
                List.of(doc("leaf", null, Boolean.TRUE, ace("u1", "cmis:read")))));

        // BINDS BRANCH 2 of the recursion (inheriting, but no parent id): that branch returns the
        // node's RAW local ACEs — no direct flags assigned, no system-principal conversion — while
        // every other branch goes through the merge, which deep-copies and REASSIGNS `direct`.
        // A stored `direct = true` is therefore preserved here and would be flipped to false by the
        // merge, so this case fails the moment branch 2 stops returning raw.
        //
        // The CMIS_ANYONE entry does NOT bind the "no conversion" half: branch 2 skips the merge's
        // in-place conversion, but calculateAcl converts acl.getAllAces() at the very end, so the
        // golden shows the CONVERTED id either way. It is kept only to record that fact explicitly
        // — the branch-local omission is invisible from outside calculateAcl.
        out.add(new Case("orphan-inherits-branch2-returns-RAW-aces",
                List.of(doc("leaf", null, Boolean.TRUE,
                        aceDirect("u1", true, "cmis:read"),
                        aceDirect(PrincipalId.ANYONE_IN_DB, true, "cmis:read")))));

        out.add(new Case("deep-chain-six-levels",
                List.of(doc("leaf", "n4", Boolean.TRUE, ace("u0", "cmis:read")),
                        folder("n4", "n3", Boolean.TRUE, ace("u4", "cmis:read")),
                        folder("n3", "n2", Boolean.TRUE, ace("u3", "cmis:write")),
                        folder("n2", "n1", Boolean.TRUE, ace("u2", "cmis:read")),
                        folder("n1", ROOT_ID, Boolean.TRUE, ace("u1", "cmis:all")),
                        root(ace("ur", "cmis:read")))));

        out.add(new Case("deep-chain-with-a-break-in-the-middle",
                List.of(doc("leaf", "n4", Boolean.TRUE, ace("u0", "cmis:read")),
                        folder("n4", "n3", Boolean.TRUE, ace("u4", "cmis:read")),
                        folder("n3", "n2", Boolean.FALSE, ace("u3", "cmis:write")),
                        folder("n2", "n1", Boolean.TRUE, ace("u2", "cmis:read")),
                        folder("n1", ROOT_ID, Boolean.TRUE, ace("u1", "cmis:all")),
                        root(ace("ur", "cmis:read")))));

        // The ONE place where strict and non-strict legitimately differ today: an inheriting node
        // whose parent does not resolve. Non-strict degrades to local ACEs; strict throws. This is
        // ancestor resolution (in scope for the golden), NOT principal-DAO fault injection (5T).
        out.add(new Case("inheriting-node-with-UNRESOLVABLE-parent",
                List.of(doc("leaf", "no-such-parent", Boolean.TRUE, ace("u1", "cmis:read")))));

        out.add(new Case("non-read-permission-only",
                List.of(doc("leaf", ROOT_ID, Boolean.TRUE, ace("u1", "cmis:write")),
                        root(ace("u2", "cmis:write")))));

        return out;
    }
}
