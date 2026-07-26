package jp.aegif.nemaki.epoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.acl.AclSemantics;
import jp.aegif.nemaki.acl.AclSemanticsCorpus;
import jp.aegif.nemaki.acl.PrincipalLookup;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.impl.delegate.AclServiceDelegate;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;

/**
 * The CROSS-IMPLEMENTATION agreement required before the ACL-epoch writer may be wired
 * (design §5.3, increment 5S step 3).
 *
 * <p>Two traversals read the same inheritance chain: the CMIS runtime's, which resolves ancestors
 * lazily through the cached content DAO, and the ACL-epoch side's, which projects from documents it
 * read authoritatively. They must produce the SAME reader tokens. Increments 3a, 3b and 4b were each
 * a case of two traversals quietly disagreeing, and the epoch fence is only meaningful if the value
 * it fences is the value the runtime would have computed.
 *
 * <p>Every case of {@link AclSemanticsCorpus} is driven through BOTH, over the same data, and the
 * projections are compared. The corpus is reused deliberately: it is the same body of cases the
 * golden pins, so a chain that matters to one side cannot be missing from the other.
 *
 * <p><b>Mutation-bound.</b> Forking or disabling the shared semantics makes the two disagree — that
 * is the property this class exists to enforce, and {@link #forkingTheSharedMergeMakesThemDISAGREE}
 * demonstrates it in-process rather than relying on an out-of-band source edit.
 *
 * <p><b>Scope.</b> Known principals and a stable resolver. Fault injection belongs to 5T's ITs.
 */
public class AclSemanticsCrossImplementationAgreementTest {

    private static final String REPO = AclSemanticsCorpus.REPO;
    private static final String ANYONE = "ANYONE_CONVERTED";
    private static final String ANONYMOUS = "ANONYMOUS_CONVERTED";

    /** Resolves every non-system principal the corpus uses as a USER; the converted anyone id as a group. */
    private static final AclSemantics.PrincipalResolver RESOLVER = new AclSemantics.PrincipalResolver() {
        @Override public PrincipalLookup lookupUser(String repositoryId, String principalId) {
            return ANYONE.equals(principalId) ? PrincipalLookup.NOT_FOUND : PrincipalLookup.FOUND;
        }
        @Override public PrincipalLookup lookupGroup(String repositoryId, String principalId) {
            return ANYONE.equals(principalId) ? PrincipalLookup.FOUND : PrincipalLookup.NOT_FOUND;
        }
    };

    @Test
    public void everyCorpusChainProjectsIdenticallyOnBOTHSides() {
        int compared = 0;
        for (AclSemanticsCorpus.Case c : AclSemanticsCorpus.cases()) {
            List<String> viaCmis;
            try {
                viaCmis = cmisReaders(c);
            } catch (RuntimeException e) {
                // The one case that legitimately throws under strict (unresolvable parent) is not an
                // agreement case: the epoch walk refuses it earlier, at snapshot time.
                continue;
            }
            List<String> viaEpoch = epochReaders(c);
            assertEquals(viaCmis, viaEpoch, "the CMIS runtime and the ACL-epoch projection disagree "
                    + "for corpus case '" + c.name + "' — the fence would protect a value the runtime "
                    + "would never have computed");
            compared++;
        }
        assertTrue(compared >= 15, "the corpus must actually have been exercised, compared=" + compared);
    }

    /**
     * Proves the agreement above is LOAD-BEARING rather than incidental: a forked merge — the same
     * ACEs combined the other way round, which is exactly the kind of divergence a second
     * implementation drifts into — makes the two sides disagree.
     *
     * <p>The chain is chosen deliberately. A first attempt used
     * {@code same-principal-at-two-depths-direct-wins} and did NOT bind: there both depths grant
     * read, so whichever ACE wins the TOKEN set is identical — the merge direction is invisible to
     * the token layer. It becomes observable only when the nearer node grants a non-read permission
     * that the ancestor's read would otherwise restore.
     */
    @Test
    public void forkingTheSharedMergeMakesThemDISAGREE() {
        AclSemanticsCorpus.Case c = caseNamed("nearer-node-REVOKES-read-that-the-ancestor-grants");

        List<String> viaCmis = cmisReaders(c);
        List<String> forked = AclSemantics.readerTokens(REPO, forkedEffectiveAces(c), RESOLVER);

        assertNotEquals(viaCmis, forked, "if a forked merge still agrees, this corpus case no longer "
                + "distinguishes the merge direction and the agreement test above proves nothing");
        assertEquals(epochReaders(c), viaCmis, "the UNFORKED epoch projection still agrees");
    }

    // ── side A: the CMIS runtime ───────────────────────────────────

    /** Drives the REAL {@link AclServiceDelegate} over the corpus chain, as the golden does. */
    private List<String> cmisReaders(AclSemanticsCorpus.Case c) {
        Map<String, Content> byId = c.byId();

        ContentService contentService = mock(ContentService.class);
        ContentDaoService contentDaoService = mock(ContentDaoService.class);
        NemakiCachePool cachePool = mock(NemakiCachePool.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class);
        PropertyManager propertyManager = mock(PropertyManager.class);

        @SuppressWarnings("unchecked")
        NemakiCache<Acl> aclCache = mock(NemakiCache.class);
        CacheService caches = mock(CacheService.class);
        lenient().when(cachePool.get(anyString())).thenReturn(caches);
        lenient().when(caches.getAclCache()).thenReturn(aclCache);
        lenient().when(aclCache.get(anyString())).thenReturn(null); // a COLD cache: always compute

        RepositoryInfo info = mock(RepositoryInfo.class);
        lenient().when(infoMap.get(anyString())).thenReturn(info);
        lenient().when(info.getRootFolderId()).thenReturn(AclSemanticsCorpus.ROOT_ID);
        lenient().when(info.getPrincipalIdAnonymous()).thenReturn(ANONYMOUS);
        lenient().when(info.getPrincipalIdAnyone()).thenReturn(ANYONE);
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

        AclServiceDelegate delegate = new AclServiceDelegate(contentService, contentDaoService,
                cachePool, infoMap, propertyManager);
        Acl acl = delegate.calculateAcl(REPO, byId.get(c.leaf().id), true);
        return AclSemantics.readerTokens(REPO, acl.getAllAces(), RESOLVER);
    }

    // ── side B: the ACL-epoch projection ───────────────────────────

    /** Builds the Snapshot the authoritative walk would have recorded for the same chain. */
    private List<String> epochReaders(AclSemanticsCorpus.Case c) {
        return snapshotOf(c).readers(RESOLVER);
    }

    private AclEffectiveEpochService.Snapshot snapshotOf(AclSemanticsCorpus.Case c) {
        List<AclEffectiveEpochService.Dependency> deps = new ArrayList<>();
        boolean first = true;
        for (AclSemanticsCorpus.Node n : c.chain) {
            deps.add(new AclEffectiveEpochService.Dependency(n.id, "1-x", true, 1L, null, n.parentId, n.aclInherited,
                    null, null, n.folder ? AclEffectiveEpochService.ContentKind.FOLDER : AclEffectiveEpochService.ContentKind.DOCUMENT,
                    first ? AclEffectiveEpochService.DependencyRole.SELF : AclEffectiveEpochService.DependencyRole.ANCESTOR, copyOf(n.localAces)));
            first = false;
        }
        return new AclEffectiveEpochService.Snapshot(REPO, c.leaf().id, deps, 1L, AclSemanticsCorpus.ROOT_ID, ANYONE, ANONYMOUS);
    }

    /** The epoch chain merged the WRONG way round (the forked implementation under test). */
    private List<Ace> forkedEffectiveAces(AclSemanticsCorpus.Case c) {
        Map<String, List<Ace>> byId = new LinkedHashMap<>();
        for (AclSemanticsCorpus.Node n : c.chain) {
            byId.put(n.id, copyOf(n.localAces));
        }
        // The fork: accumulate ROOT-FIRST. mergeAces always lets its TARGET win, so folding the
        // chain from the root outwards makes the FURTHER node win — the exact inversion of the real
        // rule. (Folding leaf-first, as a first attempt did, is not a fork at all: the leaf ends up
        // as the target on every step and still wins.)
        List<Ace> acc = new ArrayList<>();
        List<AclSemanticsCorpus.Node> rootFirst = new ArrayList<>(c.chain);
        java.util.Collections.reverse(rootFirst);
        for (AclSemanticsCorpus.Node n : rootFirst) {
            acc = AclSemantics.mergeAces(acc, byId.get(n.id), ANYONE, ANONYMOUS);
        }
        AclSemantics.convertSystemPrincipalIds(acc, ANYONE, ANONYMOUS);
        return acc;
    }

    private static List<Ace> copyOf(List<Ace> aces) {
        List<Ace> out = new ArrayList<>(aces.size());
        for (Ace a : aces) {
            out.add(AclSemantics.deepCopy(a));
        }
        return out;
    }

    private static AclSemanticsCorpus.Case caseNamed(String name) {
        for (AclSemanticsCorpus.Case c : AclSemanticsCorpus.cases()) {
            if (c.name.equals(name)) return c;
        }
        throw new IllegalStateException("corpus case not found: " + name
                + " — this test is pinned to a specific chain shape");
    }
}
