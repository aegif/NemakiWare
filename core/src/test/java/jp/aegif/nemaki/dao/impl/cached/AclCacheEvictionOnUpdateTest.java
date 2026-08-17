/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.dao.impl.cached;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.cache.NemakiCachePool;

/**
 * Writing content through the cache layer must not leave a stale effective ACL behind.
 *
 * <h2>The path this protects</h2>
 *
 * <p>{@code AclService.applyAcl} evicts {@code aclCache} itself and walks descendants, so ACL
 * changes made through CMIS are fine. It is not the only writer: the ZIP and filesystem
 * importers and the canonical ingest pipeline set ACEs on a content object and call
 * {@code contentService.update} directly, deliberately skipping applyAcl's permission check and
 * epoch bookkeeping. Those writes used to leave the previously memoised effective ACL in place —
 * in the same JVM — and that memo is the input to the authorization gate.
 *
 * <p>It is not only a re-import concern. Creating an object indexes it, indexing computes its
 * readers through {@code calculateAcl}, and that populates {@code aclCache} — so an importer that
 * applies an ACL immediately after creation hits the same stale entry on a brand new object.
 *
 * <p>This asserts the eviction actually happens against a real call, rather than asserting that a
 * line of code is present.
 */
class AclCacheEvictionOnUpdateTest {

    private ContentDaoServiceImpl cached;
    private ContentDaoService backing;
    private Map<String, Object> aclEntries;
    private Map<String, Object> contentEntries;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        backing = mock(ContentDaoService.class);
        aclEntries = new HashMap<>();
        contentEntries = new HashMap<>();

        NemakiCache<jp.aegif.nemaki.model.Acl> aclCache = mock(NemakiCache.class);
        org.mockito.Mockito.doAnswer(inv -> {
            aclEntries.remove(inv.getArgument(0, String.class));
            return null;
        }).when(aclCache).remove(anyString());

        NemakiCache<jp.aegif.nemaki.model.Content> contentCache = mock(NemakiCache.class);
        org.mockito.Mockito.doAnswer(inv -> {
            contentEntries.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return null;
        }).when(contentCache).put(anyString(), any());

        NemakiCache<org.apache.chemistry.opencmis.commons.data.ObjectData> objectDataCache =
                mock(NemakiCache.class);

        CacheService cacheService = mock(CacheService.class);
        when(cacheService.getAclCache()).thenReturn(aclCache);
        when(cacheService.getContentCache()).thenReturn(contentCache);
        when(cacheService.getObjectDataCache()).thenReturn(objectDataCache);

        NemakiCachePool pool = mock(NemakiCachePool.class);
        when(pool.get(anyString())).thenReturn(cacheService);

        cached = new ContentDaoServiceImpl();
        cached.setNonCachedContentDaoService(backing);
        cached.setNemakiCachePool(pool);
    }

    @Test
    @DisplayName("Document の update で aclCache のエントリが実際に消える")
    void updatingADocumentEvictsItsMemoisedAcl() {
        Document doc = new Document();
        doc.setId("doc-1");
        when(backing.update(anyString(), any(Document.class))).thenReturn(doc);

        aclEntries.put("doc-1", "STALE-EFFECTIVE-ACL");
        cached.update("repo", doc);

        assertNull(aclEntries.get("doc-1"),
                "an importer writing ACEs through update() must not leave the previously"
                        + " memoised effective ACL behind — it is what the authorization gate reads");
        assertEquals("doc-1", ((Document) contentEntries.get("doc-1")).getId(),
                "the content cache is still refreshed as before");
    }

    @Test
    @DisplayName("Folder の update でも同様に消える")
    void updatingAFolderEvictsItsMemoisedAcl() {
        Folder folder = new Folder();
        folder.setId("folder-1");
        when(backing.update(anyString(), any(Folder.class))).thenReturn(folder);

        aclEntries.put("folder-1", "STALE-EFFECTIVE-ACL");
        cached.update("repo", folder);

        assertNull(aclEntries.get("folder-1"));
    }

    @Test
    @DisplayName("他のオブジェクトのキャッシュは巻き添えにしない")
    void anUnrelatedObjectIsUntouched() {
        Document doc = new Document();
        doc.setId("doc-1");
        when(backing.update(anyString(), any(Document.class))).thenReturn(doc);

        aclEntries.put("doc-1", "STALE");
        aclEntries.put("doc-2", "KEEP");
        cached.update("repo", doc);

        assertNull(aclEntries.get("doc-1"));
        assertEquals("KEEP", aclEntries.get("doc-2"),
                "eviction is per object; descendants are handled by applyAcl, and no bypassing"
                        + " path writes ACLs onto folders");
    }
}
