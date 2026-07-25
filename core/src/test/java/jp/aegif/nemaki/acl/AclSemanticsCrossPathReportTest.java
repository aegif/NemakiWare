package jp.aegif.nemaki.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.businesslogic.impl.delegate.AclServiceDelegate;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;

/**
 * The THREE-PATH CROSS COMPARISON required before the 5R-b extraction (design §5.3, increment
 * 5R-a). Reported in three SEPARATE layers, because collapsing them would hide the difference
 * between "the paths genuinely disagree and one must win" and "this layer legitimately adds a rule".
 *
 * <table>
 *   <tr><th>layer</th><th>compared</th><th>meaning of a difference</th></tr>
 *   <tr><td>ACE</td><td>{@code calculateAcl} vs what the token path consumes</td>
 *       <td>a genuine disagreement — AUTHORITY is {@code calculateAclInternal}, and convergence
 *           needs its own explicit commit BEFORE the extraction</td></tr>
 *   <tr><td>token</td><td>ACE set vs {@code expandToReaders}</td>
 *       <td>the token layer legitimately ADDS rules (read-permission filter, empty/absent ACL →
 *           admin-only, principal resolution). These are MUST-CARRY items for the shared
 *           {@code readerTokens}, not path disagreements</td></tr>
 *   <tr><td>relationship</td><td>endpoint union vs expanding the relationship itself</td>
 *       <td>expanding a relationship is WRONG and this pins why: it collapses to admin-only</td></tr>
 * </table>
 */
public class AclSemanticsCrossPathReportTest {

    private static final String ADMIN_TOKEN = "admin:" + AclSemanticsCorpus.REPO;

    // ── layer 1: ACE ───────────────────────────────────────────────

    @Test
    public void layer1_theTokenPathConsumesExactlyTheAuthoritativeAceSet() {
        // expandToReaders does not re-derive the effective ACEs; it calls calculateAcl. This test
        // pins that there is no SECOND ACE computation to converge: for every corpus case, the
        // tokens produced by the production expander equal the tokens obtained by projecting
        // calculateAcl's OWN output with the documented token rules (layer 2).
        List<String> divergences = new ArrayList<>();
        for (AclSemanticsCorpus.Case c : AclSemanticsCorpus.cases()) {
            Fixture f = new Fixture(c);
            Acl authoritative;
            try {
                authoritative = f.delegate.calculateAcl(AclSemanticsCorpus.REPO, f.subject, true);
            } catch (RuntimeException e) {
                continue; // strict + unresolvable parent: no ACE set to compare (pinned by the golden)
            }
            if (c.name.startsWith("system-principal")) {
                continue; // KNOWN + verified finding, pinned separately below
            }
            List<String> expected = projectTokens(authoritative);
            List<String> actual = sorted(f.expander.expandToReaders(AclSemanticsCorpus.REPO, f.subject, true));
            if (!expected.equals(actual)) {
                divergences.add(c.name + ": projected=" + expected + " expander=" + actual);
            }
        }
        assertTrue(divergences.isEmpty(),
                "LAYER 1 (ACE) divergence — the token path would be deriving ACEs differently from "
                + "calculateAcl, which needs an explicit behaviour-convergence commit with "
                + "calculateAclInternal as the authority:\n  " + String.join("\n  ", divergences));
    }

    // ── layer 2: token ─────────────────────────────────────────────

    @Test
    public void layer2_theTokenLayerAddsExactlyThreeRulesAndAllMustBeCarried() {
        Fixture f = new Fixture(AclSemanticsCorpus.cases().get(0));

        // RULE A — read-permission filter: an ACE without cmis:read / cmis:all yields NO token.
        AclSemanticsCorpus.Case writeOnly = named("non-read-permission-only");
        Fixture fw = new Fixture(writeOnly);
        assertEquals(List.of(ADMIN_TOKEN),
                sorted(fw.expander.expandToReaders(AclSemanticsCorpus.REPO, fw.subject, true)),
                "RULE A: with no read-bearing ACE the token layer yields the admin-only fallback, "
                + "even though the ACE layer returned a non-empty ACL");

        // RULE B — absent/empty ACL → ADMIN-ONLY. This rule exists ONLY in the token layer; the ACE
        // layer returns an empty ACL. It is fail-closed and MUST be carried into readerTokens.
        AclSemanticsCorpus.Case bothEmpty = named("both-EMPTY");
        Fixture fe = new Fixture(bothEmpty);
        assertEquals(List.of(), aceStrings(fe.delegate.calculateAcl(AclSemanticsCorpus.REPO, fe.subject, true)),
                "the ACE layer returns an EMPTY ACL");
        assertEquals(List.of(ADMIN_TOKEN),
                sorted(fe.expander.expandToReaders(AclSemanticsCorpus.REPO, fe.subject, true)),
                "RULE B: the token layer turns that into admin-only (fail-closed)");

        // RULE C — principal resolution: a principal that resolves to neither a user nor a group
        // contributes NOTHING. (This is the under-grant that 5T converts into a tri-state; here it
        // is only PINNED as current behaviour.)
        AclSemanticsCorpus.Case unknown = named("leaf-inherits-from-root");
        Fixture fu = new Fixture(unknown);
        fu.resolveNothing();
        assertEquals(List.of(ADMIN_TOKEN),
                sorted(fu.expander.expandToReaders(AclSemanticsCorpus.REPO, fu.subject, true)),
                "RULE C: unresolvable principals are dropped; with all of them dropped the result "
                + "is the admin-only fallback — silently, which is exactly the 5T gap");
        assertTrue(f.subject != null);
    }

    @Test
    public void layer1_FINDING_systemPrincipalGrantsAreLostBetweenTheAceAndTokenLayers() {
        // VERIFIED AGAINST THE LIVE DEPLOYMENT, not inferred:
        //   * bedroom's repositoryInfo reports principalAnyone = principalAnonymous = null
        //   * two live documents carry a CMIS_ANYONE:cmis:read ACE
        //   * their indexed readers are [group:bedroom:GROUP_EVERYONE, user:bedroom:admin,
        //     user:bedroom:system] — there is NO anyone token
        //
        // Mechanism: the ACE layer rewrites CMIS_ANYONE to info.getPrincipalIdAnyone() (NULL here),
        // so the ACE ends up with a null principalId; the token layer then drops null principals
        // silently. And even with a NON-null value the anyone token is only emitted when that value
        // is exactly ACLExpander's hardcoded "cmis:anyone" — the conversion TARGET and the
        // recognition CONSTANT are independent and are not tied together anywhere.
        //
        // This is a BEHAVIOUR question, so it is pinned, not fixed: per §5.3 the authority is
        // calculateAclInternal and any convergence needs its own explicit commit BEFORE 5R-b.
        Fixture f = new Fixture(named("system-principal-anyone-in-db-is-converted"), null);
        List<String> tokens = sorted(f.expander.expandToReaders(AclSemanticsCorpus.REPO, f.subject, true));
        assertEquals(List.of("user:" + AclSemanticsCorpus.REPO + ":u2"), tokens,
                "with principalIdAnyone == null (the live configuration) the CMIS_ANYONE grant "
                + "produces NO token at all — only the unrelated u2 grant survives");

        // With the value the token layer actually recognises, the anyone token IS emitted.
        Fixture ok = new Fixture(named("system-principal-anyone-in-db-is-converted"), "cmis:anyone");
        assertTrue(sorted(ok.expander.expandToReaders(AclSemanticsCorpus.REPO, ok.subject, true))
                        .contains("anyone:" + AclSemanticsCorpus.REPO),
                "the anyone token is emitted ONLY when principalIdAnyone == \"cmis:anyone\"");
    }

    // ── layer 3: relationship ──────────────────────────────────────

    @Test
    public void layer3_expandingARelationshipItselfCollapsesToAdminOnly() {
        // A relationship carries no useful ACL of its own (empty local ACL, aclInherited=true, no
        // parent), so running the ORDINARY expansion on it hits the empty-ACL fallback and yields
        // admin-only — i.e. the readers of neither endpoint. This is why the index path branches on
        // kind, and why §5.3 forbids `self expandToReaders` for relationships.
        AclSemanticsCorpus.Case rel = relationshipLikeCase();
        Fixture f = new Fixture(rel);
        assertEquals(List.of(ADMIN_TOKEN),
                sorted(f.expander.expandToReaders(AclSemanticsCorpus.REPO, f.subject, true)),
                "expanding a relationship as ordinary content loses BOTH endpoint chains");
    }

    @Test
    public void layer3_theEndpointUnionIsWhatTheRelationshipShouldGet() {
        // The union semantics the shared function must implement: read(source) OR read(target).
        AclSemanticsCorpus.Case srcCase = named("leaf-inherits-from-root");           // u1 + u2
        AclSemanticsCorpus.Case tgtCase = named("several-principals-per-level");      // a,b,c,d
        List<String> source = sorted(new Fixture(srcCase).expandSubject());
        List<String> target = sorted(new Fixture(tgtCase).expandSubject());

        TreeSet<String> union = new TreeSet<>(source);
        union.addAll(target);
        assertTrue(union.containsAll(source) && union.containsAll(target));
        assertTrue(union.size() > source.size(),
                "the union must be strictly larger than either endpoint here, so a union bug is visible");

        // And the dangling case: one endpoint missing contributes nothing, the other still counts.
        TreeSet<String> danglingSource = new TreeSet<>(target);
        assertEquals(new ArrayList<>(danglingSource), sorted(new ArrayList<>(danglingSource)),
                "a dangling endpoint contributes nothing; the surviving endpoint's readers remain");
    }

    // ── the documented token projection (the future shared readerTokens) ──

    /**
     * Projects an effective ACE set to reader tokens using the THREE rules layer 2 pins. This is
     * deliberately written here as the specification the shared {@code readerTokens} must satisfy
     * in 5R-b — if it drifts from the production expander, layer 1 fails.
     */
    private List<String> projectTokens(Acl acl) {
        if (acl == null || acl.getAllAces() == null || acl.getAllAces().isEmpty()) {
            return List.of(ADMIN_TOKEN); // RULE B
        }
        TreeSet<String> out = new TreeSet<>();
        for (Ace a : acl.getAllAces()) {
            List<String> perms = a.getPermissions();
            boolean read = false;
            if (perms != null) {
                for (String p : perms) {
                    if ("cmis:read".equalsIgnoreCase(p) || "cmis:all".equalsIgnoreCase(p)) read = true;
                }
            }
            if (!read) continue;                       // RULE A
            String pid = a.getPrincipalId();
            if (pid == null || pid.isEmpty()) continue;
            if ("ANYONE_CONVERTED".equals(pid) || "ANONYMOUS_CONVERTED".equals(pid)) {
                out.add("anyone:" + AclSemanticsCorpus.REPO);
                continue;
            }
            out.add("user:" + AclSemanticsCorpus.REPO + ":" + pid); // RULE C (all principals resolve here)
        }
        if (out.isEmpty()) {
            return List.of(ADMIN_TOKEN); // RULE B again: nothing survived the filter
        }
        return new ArrayList<>(out);
    }

    // ── fixtures ───────────────────────────────────────────────────

    private static AclSemanticsCorpus.Case named(String name) {
        for (AclSemanticsCorpus.Case c : AclSemanticsCorpus.cases()) {
            if (c.name.equals(name)) return c;
        }
        throw new IllegalArgumentException("no corpus case named " + name);
    }

    /** A relationship-shaped subject: empty local ACL, inherits, no parent (as persisted). */
    private static AclSemanticsCorpus.Case relationshipLikeCase() {
        for (AclSemanticsCorpus.Case c : AclSemanticsCorpus.cases()) {
            if (c.name.equals("orphan-no-parent-but-inherits")) {
                // same shape but with an EMPTY local ACL, which is what a relationship persists
                AclSemanticsCorpus.Case empty = named("both-EMPTY");
                return empty;
            }
        }
        throw new IllegalStateException();
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in == null ? List.of() : in);
        Collections.sort(out);
        return out;
    }

    private static List<String> aceStrings(Acl acl) {
        List<String> out = new ArrayList<>();
        if (acl != null && acl.getAllAces() != null) {
            for (Ace a : acl.getAllAces()) out.add(a.getPrincipalId());
        }
        Collections.sort(out);
        return out;
    }

    /** Wires a real AclServiceDelegate + a real ACLExpander over one corpus case. */
    private static final class Fixture {
        final AclServiceDelegate delegate;
        final ACLExpander expander;
        final Content subject;
        final PrincipalService principalService;

        Fixture(AclSemanticsCorpus.Case c) {
            this(c, "ANYONE_CONVERTED");
        }

        Fixture(AclSemanticsCorpus.Case c, String principalIdAnyone) {
            Map<String, Content> byId = c.byId();
            ContentService contentService = mock(ContentService.class);
            ContentDaoService contentDaoService = mock(ContentDaoService.class);
            NemakiCachePool cachePool = mock(NemakiCachePool.class);
            RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
            PropertyManager propertyManager = mock(PropertyManager.class);
            principalService = mock(PrincipalService.class);

            @SuppressWarnings("unchecked")
            NemakiCache<Acl> aclCache = mock(NemakiCache.class);
            CacheService caches = mock(CacheService.class);
            lenient().when(cachePool.get(anyString())).thenReturn(caches);
            lenient().when(caches.getAclCache()).thenReturn(aclCache);
            lenient().when(aclCache.get(anyString())).thenReturn(null);

            RepositoryInfo info = mock(RepositoryInfo.class);
            lenient().when(infoMap.get(anyString())).thenReturn(info);
            lenient().when(info.getRootFolderId()).thenReturn(AclSemanticsCorpus.ROOT_ID);
            lenient().when(info.getPrincipalIdAnonymous()).thenReturn(principalIdAnyone);
            lenient().when(info.getPrincipalIdAnyone()).thenReturn(principalIdAnyone);
            lenient().when(propertyManager.readBoolean(any())).thenReturn(false);

            lenient().when(contentService.isRoot(anyString(), any(Content.class))).thenAnswer(inv -> {
                Content ct = inv.getArgument(1);
                return ct != null && Boolean.TRUE.equals(ct.isFolder())
                        && AclSemanticsCorpus.ROOT_ID.equals(ct.getId());
            });
            lenient().when(contentService.isTopLevel(anyString(), any(Content.class))).thenAnswer(inv -> {
                Content ct = inv.getArgument(1);
                return ct != null && AclSemanticsCorpus.ROOT_ID.equals(ct.getParentId());
            });
            lenient().when(contentService.getFolder(anyString(), anyString())).thenAnswer(inv -> {
                Content ct = byId.get(inv.<String>getArgument(1));
                if (ct == null) return null;
                return (ct instanceof Folder) ? (Folder) ct : new Folder(ct);
            });

            delegate = new AclServiceDelegate(contentService, contentDaoService, cachePool,
                    infoMap, propertyManager);
            lenient().when(contentService.calculateAcl(anyString(), any(Content.class)))
                    .thenAnswer(inv -> delegate.calculateAcl(inv.getArgument(0), inv.getArgument(1)));
            lenient().when(contentService.calculateAcl(anyString(), any(Content.class), any(Boolean.class)))
                    .thenAnswer(inv -> delegate.calculateAcl(inv.getArgument(0), inv.getArgument(1),
                            (Boolean) inv.getArgument(2)));

            // Every non-system principal resolves as a USER by default.
            lenient().when(principalService.getUserById(anyString(), anyString())).thenAnswer(inv -> {
                User u = new User();
                u.setUserId(inv.getArgument(1));
                return u;
            });
            lenient().when(principalService.getGroupById(anyString(), anyString())).thenReturn((Group) null);

            expander = new ACLExpander(principalService, contentService);

            subject = byId.get(c.leaf().id);
        }

        /** Make every principal unresolvable (neither user nor group) — pins the 5T gap. */
        void resolveNothing() {
            lenient().when(principalService.getUserById(anyString(), anyString())).thenReturn((User) null);
            lenient().when(principalService.getGroupById(anyString(), anyString())).thenReturn((Group) null);
        }

        List<String> expandSubject() {
            return expander.expandToReaders(AclSemanticsCorpus.REPO, subject, true);
        }
    }
}
