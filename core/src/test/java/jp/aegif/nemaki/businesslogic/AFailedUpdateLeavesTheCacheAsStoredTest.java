package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.impl.ContentServiceImpl;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.util.cache.CacheService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;

/**
 * A failed update leaves the content cache answering what is stored, not what the update wrote in memory before it failed.
 *
 * <p>modifyProperties writes the request onto the object it is given, the one the cached DAO hands out and the heap tier
 * keeps by reference. When the update failed after the first write — a secondary type that could not be read (closing batch
 * 31 made the store raise that, where it had dropped the type), or the store refusing the update — the cached object kept
 * the changes the caller was told had failed, and the next update that succeeded stored them (closing batch 31 review,
 * Codex P2). The cache here is an Ehcache heap cache as the product builds it, behind the real cached DAO; the object
 * leaves it through the product's own {@code CacheService.removeCmisAndContentCache}.
 */
class AFailedUpdateLeavesTheCacheAsStoredTest {

    private CacheManager cacheManager;
    private ContentDaoService stored;
    private ContentDaoService cached;
    private ContentServiceImpl service;
    private TypeManager types;

    private static Document asStored() {
        Document document = new Document();
        document.setId("doc-1");
        document.setType("cmis:document");
        document.setObjectType("cmis:document");
        document.setName("minutes.txt");
        document.setDescription("as stored");
        document.setChangeToken("1");
        document.setSubTypeProperties(new ArrayList<>());
        document.setAspects(new ArrayList<>());
        document.setSecondaryIds(new ArrayList<>());
        return document;
    }

    @BeforeEach
    void wire() throws Exception {
        cacheManager = CacheManagerBuilder.newCacheManagerBuilder().build(true);
        Cache<String, Object> heap = cacheManager.createCache("bedroom_contentCache", CacheConfigurationBuilder
                .newCacheConfigurationBuilder(String.class, Object.class, ResourcePoolsBuilder.heap(100)).build());
        NemakiCache<Content> contentCache = new NemakiCache<>(true, heap);
        CacheService repositoryCache = mock(CacheService.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(repositoryCache.getContentCache()).thenReturn(contentCache);
        doCallRealMethod().when(repositoryCache).removeCmisAndContentCache(anyString());
        NemakiCachePool pool = mock(NemakiCachePool.class);
        when(pool.get("bedroom")).thenReturn(repositoryCache);

        stored = mock(ContentDaoService.class);
        when(stored.getContent("bedroom", "doc-1")).thenAnswer(invocation -> asStored());
        jp.aegif.nemaki.dao.impl.cached.ContentDaoServiceImpl cachedDao = new jp.aegif.nemaki.dao.impl.cached.ContentDaoServiceImpl();
        cachedDao.setNonCachedContentDaoService(stored);
        cachedDao.setNemakiCachePool(pool);
        cached = cachedDao;

        service = new ContentServiceImpl();
        types = mock(TypeManager.class);
        for (String fieldName : new String[]{"contentDaoService", "nemakiCachePool", "typeManager", "helper", "solrUtil"}) {
            Field f = ContentServiceImpl.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, switch (fieldName) {
                case "contentDaoService" -> cached;
                case "nemakiCachePool" -> pool;
                case "typeManager" -> types;
                default -> mock(f.getType(), org.mockito.Mockito.RETURNS_DEEP_STUBS);
            });
        }
        PropertyDefinition<?> description = mock(PropertyDefinition.class);
        when(description.getUpdatability()).thenReturn(Updatability.READWRITE);
        TypeDefinition documentType = mock(TypeDefinition.class);
        org.mockito.Mockito.<Map<String, PropertyDefinition<?>>>when(documentType.getPropertyDefinitions())
                .thenReturn(Map.of("cmis:description", description));
        when(types.getTypeDefinition("bedroom", "cmis:document")).thenReturn(documentType);
        when(types.getSpecificPropertyDefinitions("cmis:document")).thenReturn(new ArrayList<>());
    }

    @AfterEach
    void close() {
        cacheManager.close();
    }

    private static PropertiesImpl describedAs(String text) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyStringImpl("cmis:description", text));
        return properties;
    }

    @Test
    @DisplayName("an update that fails at a secondary type it cannot read leaves the cache answering what is stored")
    void aFailureAtTheSecondaryTypesLeavesTheCacheAsStored() {
        when(types.getTypeDefinition("bedroom", "nemaki:unreadable")).thenThrow(
                new IllegalStateException("the type definitions could not be read"));
        Content content = cached.getContent("bedroom", "doc-1");
        PropertiesImpl properties = describedAs("written by an update that failed");
        properties.addProperty(new PropertyIdImpl("cmis:secondaryObjectTypeIds", List.of("nemaki:unreadable")));

        assertThrows(IllegalStateException.class,
                () -> service.updateProperties(mock(CallContext.class), "bedroom", properties, content));
        Content readAgain = cached.getContent("bedroom", "doc-1");
        assertEquals("as stored", readAgain.getDescription(),
                "the cache answers the description an update wrote before it failed — the next update would store it");
    }

    @Test
    @DisplayName("an update the store refuses leaves the cache answering what is stored, change token included")
    void aRefusalByTheStoreLeavesTheCacheAsStored() {
        when(stored.update(anyString(), any(Document.class))).thenThrow(new CmisRuntimeException("the store refused"));
        Content content = cached.getContent("bedroom", "doc-1");

        assertThrows(CmisRuntimeException.class, () -> service.updateProperties(mock(CallContext.class), "bedroom",
                describedAs("written by an update that failed"), content));
        Content readAgain = cached.getContent("bedroom", "doc-1");
        assertEquals("as stored", readAgain.getDescription(),
                "the cache answers the description of an update the store refused");
        assertEquals("1", readAgain.getChangeToken(),
                "the cache answers the change token of an update the store refused, so the caller's token no longer matches");
    }
}
