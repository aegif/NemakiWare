package jp.aegif.nemaki.rest.purview.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.util.constant.SystemConst;

public class PurviewStateStoreImplTest {

    private ContentDaoService contentDaoService;
    private CloudantClientPool connectorPool;
    private CloudantClientWrapper configClient;
    private PurviewStateStoreImpl stateStore;
    private Map<String, Map<String, Object>> documents;

    @BeforeEach
    public void setUp() {
        contentDaoService = mock(ContentDaoService.class);
        connectorPool = mock(CloudantClientPool.class);
        configClient = mock(CloudantClientWrapper.class);
        documents = new LinkedHashMap<>();

        when(connectorPool.getClient(SystemConst.NEMAKI_CONF_DB)).thenReturn(configClient);
        when(contentDaoService.getConfiguration(SystemConst.NEMAKI_CONF_DB)).thenAnswer(invocation -> createSystemConfiguration());
        when(configClient.get(eq(Map.class), anyString())).thenAnswer(invocation -> documents.get(invocation.getArgument(1)));
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> document = new LinkedHashMap<>((Map<String, Object>) invocation.getArgument(1));
            document.put("_id", id);
            document.put("_rev", "1-created");
            documents.put(id, document);
            return null;
        }).when(configClient).create(anyString(), anyMap());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> document = new LinkedHashMap<>((Map<String, Object>) invocation.getArgument(0));
            String currentRev = document.get("_rev") == null ? "0" : document.get("_rev").toString().split("-")[0];
            int nextRev = Integer.parseInt(currentRev) + 1;
            document.put("_rev", nextRev + "-updated");
            documents.put(document.get("_id").toString(), document);
            return null;
        }).when(configClient).update(anyMap());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> document = (Map<String, Object>) invocation.getArgument(0);
            documents.remove(document.get("_id").toString());
            return null;
        }).when(configClient).delete(any());

        stateStore = new PurviewStateStoreImpl(contentDaoService, connectorPool);
    }

    @Test
    public void testPutAllAndReadBackValues() {
        stateStore.putAll(Map.of(
                "purview.test.string", "value-1",
                "purview.test.int", 7));

        assertEquals("value-1", stateStore.getString("purview.test.string"));
        assertEquals(7, stateStore.getInt("purview.test.int"));
        assertTrue(documents.containsKey("system_config_purview_test_string"));
        assertEquals("purview.test.string", documents.get("system_config_purview_test_string").get("key"));
        assertEquals("value-1", documents.get("system_config_purview_test_string").get("value"));
    }

    @Test
    public void testRemoveAllClearsPersistedKeys() {
        stateStore.putAll(Map.of(
                "purview.test.string", "value-1",
                "purview.test.int", 7));

        stateStore.removeAll(java.util.List.of("purview.test.string", "purview.test.int"));
        ArgumentCaptor<Map> deletedDocumentCaptor = ArgumentCaptor.forClass(Map.class);

        assertEquals("", stateStore.getString("purview.test.string"));
        assertEquals(0, stateStore.getInt("purview.test.int"));
        verify(configClient, times(2)).delete(deletedDocumentCaptor.capture());
        assertTrue(deletedDocumentCaptor.getAllValues().stream()
                .anyMatch(document -> "system_config_purview_test_string".equals(document.get("_id"))));
        assertTrue(!documents.containsKey("system_config_purview_test_string"));
    }

    @Test
    public void testPutAllUpdatesExistingConfigurationDocument() {
        documents.put("system_config_purview_test_string", new LinkedHashMap<>(Map.of(
                "_id", "system_config_purview_test_string",
                "_rev", "3-existing",
                "type", "configuration",
                "key", "purview.test.string",
                "value", "stale-value",
                "created", "2026-03-20T00:00:00Z",
                "creator", "system",
                "modified", "2026-03-20T00:00:00Z",
                "modifier", "system")));

        stateStore.putAll(Map.of("purview.test.string", "fresh-value"));

        assertEquals("fresh-value", stateStore.getString("purview.test.string"));
        assertEquals("4-updated", documents.get("system_config_purview_test_string").get("_rev"));
        verify(configClient).update(anyMap());
    }

    @Test
    public void testGetAllReturnsSnapshotOfPersistedValues() {
        stateStore.putAll(Map.of(
                "purview.test.string", "value-1",
                "purview.test.int", 7));

        Map<String, Object> snapshot = stateStore.getAll();
        snapshot.put("purview.test.string", "mutated");

        assertEquals("value-1", stateStore.getString("purview.test.string"));
        assertEquals("value-1", stateStore.getAll().get("purview.test.string"));
        assertEquals(7, stateStore.getAll().get("purview.test.int"));
    }

    private Configuration createSystemConfiguration() {
        Configuration configuration = new Configuration();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> document : documents.values()) {
            if (!"configuration".equals(document.get("type"))) {
                continue;
            }
            if (document.get("repositoryId") != null) {
                continue;
            }
            Object key = document.get("key");
            if (key != null) {
                values.put(key.toString(), document.get("value"));
            }
        }
        configuration.setConfiguration(values);
        return configuration;
    }
}
