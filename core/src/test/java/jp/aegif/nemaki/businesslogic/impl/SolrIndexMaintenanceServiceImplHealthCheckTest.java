package jp.aegif.nemaki.businesslogic.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.IndexHealthStatus;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Document;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for SolrIndexMaintenanceServiceImpl - Health Check Operations
 *
 * Tests the checkIndexHealth method with proper Mockito mocking of dependencies.
 * The implementation uses cursor-based pagination to iterate through Solr documents,
 * so tests must create real SolrDocumentList instances with real SolrDocument objects
 * rather than mocking the iteration.
 */
@ExtendWith(MockitoExtension.class)
public class SolrIndexMaintenanceServiceImplHealthCheckTest {

    private static final String TEST_REPO_ID = "test-repo";
    private static final String ROOT_FOLDER_ID = "root-folder-id";

    @Mock
    private ContentService contentService;

    @Mock
    private SolrUtil solrUtil;

    @Mock
    private RepositoryInfoMap repositoryInfoMap;

    @Mock
    private RepositoryInfo repositoryInfo;

    @Mock
    private SolrClient solrClient;

    @Mock
    private QueryResponse queryResponse;

    @Mock
    private Folder rootFolder;

    @InjectMocks
    private SolrIndexMaintenanceServiceImpl service;

    @BeforeEach
    public void setUp() {
        when(repositoryInfoMap.get(TEST_REPO_ID)).thenReturn(repositoryInfo);
        when(repositoryInfo.getRootFolderId()).thenReturn(ROOT_FOLDER_ID);
    }

    @AfterEach
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    /**
     * Creates a real SolrDocumentList with SolrDocument objects containing object_id values.
     * Uses real objects (not mocks) so iteration works correctly.
     */
    private SolrDocumentList createSolrDocumentList(String... objectIds) {
        SolrDocumentList list = new SolrDocumentList();
        for (String id : objectIds) {
            SolrDocument doc = new SolrDocument();
            doc.addField("object_id", id);
            list.add(doc);
        }
        list.setNumFound(objectIds.length);
        return list;
    }

    /**
     * Sets up Solr mocks for cursor-based iteration returning a single page of results.
     * The cursor mark is returned as "*" (same as initial) to signal end of iteration.
     */
    private void setupSolrResponse(SolrDocumentList docList) throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenReturn(queryResponse);
        when(queryResponse.getResults()).thenReturn(docList);
        when(queryResponse.getNextCursorMark()).thenReturn("*");
    }

    @Test
    public void testCheckIndexHealthWhenHealthy() throws Exception {
        // CouchDB: root folder + 5 children = 6 total
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);

        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);

        // Solr: 6 documents matching all CouchDB IDs
        setupSolrResponse(createSolrDocumentList(
            ROOT_FOLDER_ID, "doc-0", "doc-1", "doc-2", "doc-3", "doc-4"));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertNotNull(health);
        assertEquals(TEST_REPO_ID, health.getRepositoryId());
        assertEquals(6, health.getSolrDocumentCount());
        assertEquals(6, health.getCouchDbDocumentCount());
        assertTrue(health.isHealthy());
        assertEquals(0, health.getMissingInSolr());
        assertEquals(0, health.getOrphanedInSolr());
    }

    @Test
    public void testCheckIndexHealthWithMissingDocuments() throws Exception {
        // CouchDB: root folder + 5 children = 6 total
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);

        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);

        // Solr: only 4 documents (missing doc-3 and doc-4)
        setupSolrResponse(createSolrDocumentList(
            ROOT_FOLDER_ID, "doc-0", "doc-1", "doc-2"));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertNotNull(health);
        assertFalse(health.isHealthy());
        assertEquals(4, health.getSolrDocumentCount());
        assertEquals(6, health.getCouchDbDocumentCount());
        assertEquals(2, health.getMissingInSolr());
        assertEquals(0, health.getOrphanedInSolr());
    }

    @Test
    public void testCheckIndexHealthWithOrphanedDocuments() throws Exception {
        // CouchDB: root folder + 3 children = 4 total
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);

        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);

        // Solr: 6 documents (4 matching + 2 orphaned)
        setupSolrResponse(createSolrDocumentList(
            ROOT_FOLDER_ID, "doc-0", "doc-1", "doc-2", "orphan-1", "orphan-2"));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertNotNull(health);
        assertFalse(health.isHealthy());
        assertEquals(6, health.getSolrDocumentCount());
        assertEquals(4, health.getCouchDbDocumentCount());
        assertEquals(0, health.getMissingInSolr());
        assertEquals(2, health.getOrphanedInSolr());
    }

    @Test
    public void testCheckIndexHealthWithNullSolrClient() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(null);

        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(new ArrayList<>());

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertNotNull(health);
        assertEquals(0, health.getSolrDocumentCount());
    }

    @Test
    public void testCheckIndexHealthWithSolrException() throws Exception {
        when(solrUtil.getSolrClient()).thenReturn(solrClient);
        when(solrClient.query(any())).thenThrow(new RuntimeException("Solr connection failed"));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertNotNull(health);
        assertFalse(health.isHealthy());
        assertTrue(health.getMessage().contains("Error checking health"));
    }

    @Test
    public void testCheckIndexHealthWithEmptyRepository() throws Exception {
        // CouchDB: root folder only, no children = 1 total
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(new ArrayList<>());

        // Solr: 1 document (root folder only)
        setupSolrResponse(createSolrDocumentList(ROOT_FOLDER_ID));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertNotNull(health);
        assertTrue(health.isHealthy());
        assertEquals(1, health.getSolrDocumentCount());
        assertEquals(1, health.getCouchDbDocumentCount());
    }

    @Test
    public void testCheckIndexHealthWithNullRootFolder() throws Exception {
        // CouchDB: null root folder = 0 total
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(null);

        // Solr: 3 documents (all orphaned since CouchDB has 0)
        setupSolrResponse(createSolrDocumentList("doc-0", "doc-1", "doc-2"));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertNotNull(health);
        assertEquals(3, health.getSolrDocumentCount());
        assertEquals(0, health.getCouchDbDocumentCount());
    }

    @Test
    public void testCheckIndexHealthSetsCheckTime() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(new ArrayList<>());

        // Solr: root folder only
        setupSolrResponse(createSolrDocumentList(ROOT_FOLDER_ID));

        long beforeCheck = System.currentTimeMillis();
        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);
        long afterCheck = System.currentTimeMillis();

        assertTrue(health.getCheckTime() >= beforeCheck);
        assertTrue(health.getCheckTime() <= afterCheck);
    }

    @Test
    public void testCheckIndexHealthMessageForHealthyIndex() throws Exception {
        // CouchDB: root folder + 3 children = 4 total
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);

        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);

        // Solr: 4 documents matching all CouchDB IDs
        setupSolrResponse(createSolrDocumentList(ROOT_FOLDER_ID, "doc-0", "doc-1", "doc-2"));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertTrue(health.getMessage().contains("healthy"));
    }

    @Test
    public void testCheckIndexHealthMessageForUnhealthyIndex() throws Exception {
        // CouchDB: root folder + 3 children = 4 total
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);

        List<Content> children = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Document doc = mock(Document.class);
            when(doc.getId()).thenReturn("doc-" + i);
            children.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);

        // Solr: only 2 documents (missing doc-1 and doc-2)
        setupSolrResponse(createSolrDocumentList(ROOT_FOLDER_ID, "doc-0"));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertTrue(health.getMessage().contains("mismatch"));
    }

    @Test
    public void testCheckIndexHealthWithNestedFolders() throws Exception {
        // CouchDB: root folder + sub-folder + 4 docs = 6 total
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);

        Folder subFolder = mock(Folder.class);
        when(subFolder.getId()).thenReturn("sub-folder-1");

        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        Document doc2 = mock(Document.class);
        when(doc2.getId()).thenReturn("doc-2");

        List<Content> rootChildren = Arrays.asList(subFolder, doc1, doc2);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootChildren);

        Document doc3 = mock(Document.class);
        when(doc3.getId()).thenReturn("doc-3");
        Document doc4 = mock(Document.class);
        when(doc4.getId()).thenReturn("doc-4");

        List<Content> subChildren = Arrays.asList(doc3, doc4);
        when(contentService.getChildren(TEST_REPO_ID, "sub-folder-1")).thenReturn(subChildren);

        // Solr: 6 documents matching all CouchDB IDs
        setupSolrResponse(createSolrDocumentList(
            ROOT_FOLDER_ID, "sub-folder-1", "doc-1", "doc-2", "doc-3", "doc-4"));

        IndexHealthStatus health = service.checkIndexHealth(TEST_REPO_ID);

        assertNotNull(health);
        assertEquals(6, health.getSolrDocumentCount());
        assertEquals(6, health.getCouchDbDocumentCount());
        assertTrue(health.isHealthy());
    }
}
