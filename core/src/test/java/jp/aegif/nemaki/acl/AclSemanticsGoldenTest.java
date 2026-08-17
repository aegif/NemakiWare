package jp.aegif.nemaki.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
 * GOLDEN capture of the CURRENT ACL inheritance semantics (design §5.3, increment 5R-a).
 *
 * <p>This test exists so that the extraction in 5R-b can be proved behaviour-preserving rather than
 * merely claimed to be. It drives the REAL {@link AclServiceDelegate} over
 * {@link AclSemanticsCorpus} and asserts the result against a committed golden file.
 *
 * <p>The golden is captured as DATA, not as "run the old implementation and compare": once 5R-b
 * replaces the internals (and eventually deletes the old private methods), the golden still pins
 * the behaviour. Regenerate deliberately with
 * {@code -Dnemaki.acl.golden.write=true} — a diff in that file during 5R-b means the extraction
 * changed behaviour and must be re-examined, or (if the change is intended) it belongs in an
 * explicit behaviour-convergence commit that lands BEFORE the extraction.
 *
 * <p><b>Scope (fixed by review):</b> known principals, stable DAO. No fault injection —
 * {@code UNAVAILABLE → throw} is a 5T change with its own ITs.
 */
public class AclSemanticsGoldenTest {

    private static final String GOLDEN_RESOURCE = "acl-semantics-golden.txt";
    private static final Path GOLDEN_SOURCE_PATH =
            Paths.get("src/test/resources", GOLDEN_RESOURCE);

    @Test
    public void currentAclSemanticsMatchTheGolden() throws Exception {
        String actual = renderAllCases();

        if (Boolean.getBoolean("nemaki.acl.golden.write")) {
            Files.createDirectories(GOLDEN_SOURCE_PATH.getParent());
            Files.writeString(GOLDEN_SOURCE_PATH, actual, StandardCharsets.UTF_8);
            System.out.println("[golden] wrote " + GOLDEN_SOURCE_PATH.toAbsolutePath());
            return;
        }

        String expected = readGolden();
        assertNotNull(expected, "golden resource " + GOLDEN_RESOURCE + " is missing — regenerate it "
                + "with -Dnemaki.acl.golden.write=true and COMMIT it before changing any ACL code");
        assertEquals(expected, actual, "the ACL inheritance semantics changed. During 5R-b this "
                + "means the extraction was NOT behaviour-preserving; an intended change belongs in "
                + "an explicit behaviour-convergence commit that lands BEFORE the extraction");
    }

    /** Renders every corpus case, both strict and non-strict, in a stable textual form. */
    private String renderAllCases() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ACL semantics golden — see AclSemanticsGoldenTest\n");
        sb.append("# format: <case> | strict=<bool> => [<principal>:<direct|inherited>:<perms>, ...]\n");
        for (AclSemanticsCorpus.Case c : AclSemanticsCorpus.cases()) {
            for (boolean strict : new boolean[] { false, true }) {
                sb.append(c.name).append(" | strict=").append(strict).append(" => ");
                try {
                    sb.append(render(calculate(c, strict)));
                } catch (RuntimeException e) {
                    // A throw is part of the behaviour being pinned (strict + unresolvable parent).
                    sb.append("<throws ").append(e.getClass().getSimpleName()).append('>');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Drive the REAL delegate over one corpus case. */
    private Acl calculate(AclSemanticsCorpus.Case c, boolean strict) {
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
        // A COLD cache: the non-strict path must actually compute, not return a cached Acl.
        lenient().when(aclCache.get(anyString())).thenReturn(null);

        RepositoryInfo info = mock(RepositoryInfo.class);
        lenient().when(infoMap.get(anyString())).thenReturn(info);
        lenient().when(info.getRootFolderId()).thenReturn(AclSemanticsCorpus.ROOT_ID);
        lenient().when(info.getPrincipalIdAnonymous()).thenReturn("ANONYMOUS_CONVERTED");
        lenient().when(info.getPrincipalIdAnyone()).thenReturn("ANYONE_CONVERTED");

        lenient().when(propertyManager.readBoolean(any())).thenReturn(false);

        // isRoot / isTopLevel / getFolder mirror the production semantics over the corpus chain.
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
        Content subject = byId.get(c.leaf().id);
        return delegate.calculateAcl(AclSemanticsCorpus.REPO, subject, strict);
    }

    /** Stable, order-independent rendering (the merge uses a HashMap, so order is not contractual). */
    private String render(Acl acl) {
        if (acl == null) {
            return "<null>";
        }
        List<String> parts = new ArrayList<>();
        for (Ace a : acl.getAllAces()) {
            List<String> perms = a.getPermissions() == null
                    ? List.of() : new ArrayList<>(a.getPermissions());
            Collections.sort(perms);
            parts.add(a.getPrincipalId() + ":" + (a.isDirect() ? "direct" : "inherited")
                    + ":" + String.join("+", perms));
        }
        Collections.sort(parts);
        return parts.toString();
    }

    private String readGolden() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(GOLDEN_RESOURCE)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
