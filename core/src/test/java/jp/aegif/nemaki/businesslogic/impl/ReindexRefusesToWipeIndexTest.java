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
package jp.aegif.nemaki.businesslogic.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;

/**
 * A full reindex must not clear an index it has found nothing to rebuild.
 *
 * <h2>The failure this guards</h2>
 *
 * <p>Reproduced on a running server: with the CouchDB {@code children} view answering with zero
 * rows, a full reindex of a 164-object repository reported {@code totalDocuments=1},
 * {@code indexedCount=1}, {@code errorCount=0}, {@code status=completed} — and left one document
 * in Solr. The repository's data was untouched in CouchDB; only the search index was destroyed,
 * and every field the operator can read said the operation succeeded.
 *
 * <p>Three things have to line up, and all three are real:
 * <ol>
 *   <li>{@code ContentDaoServiceImpl.getChildren} catches and returns an empty list, so a failed
 *       read is indistinguishable from an empty folder;</li>
 *   <li>{@code startFullReindex} clears the index BEFORE walking the tree;</li>
 *   <li>the walk's own catch never fires, because no exception ever reaches it.</li>
 * </ol>
 *
 * <p><b>Propagating the exception would not have helped.</b> A CouchDB view whose map function
 * throws answers HTTP 200 with an empty row set — there is no exception to propagate. That was
 * measured, not assumed, and it is why the guard compares against the live index instead of
 * relying on error handling.
 *
 * <h2>Why this test drives the real method</h2>
 *
 * <p>The guard sits between the count and the clear, so a test that stubs either one away proves
 * nothing. This one wires the actual {@code startFullReindex}, makes the enumeration return
 * nothing (exactly as the swallowed failure does), tells Solr the index holds 164 objects, and
 * asserts that {@code clearIndex}'s delete never happens. Deleting the guard makes
 * {@link #anEmptyEnumerationDoesNotClearAPopulatedIndex()} fail on that verify.
 */
class ReindexRefusesToWipeIndexTest {

    private static final String REPO = "canopy";
    private static final String ROOT = "root-folder-id";

    private SolrIndexMaintenanceServiceImpl svc;
    private ContentService contentService;
    private SolrUtil solrUtil;
    private SolrClient solrClient;

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        solrUtil = mock(SolrUtil.class);
        solrClient = mock(SolrClient.class);
        RepositoryInfoMap infoMap = mock(RepositoryInfoMap.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        lenient().when(infoMap.get(REPO).getRootFolderId()).thenReturn(ROOT);

        Folder root = new Folder();
        root.setId(ROOT);
        root.setType("cmis:folder");
        root.setObjectType("cmis:folder");
        lenient().when(contentService.getFolder(REPO, ROOT)).thenReturn(root);
        lenient().when(solrUtil.getSolrClient()).thenReturn(solrClient);

        svc = new SolrIndexMaintenanceServiceImpl();
        svc.setContentService(contentService);
        svc.setSolrUtil(solrUtil);
        svc.setRepositoryInfoMap(infoMap);
    }

    /** Make the Solr count query answer with {@code numFound}. */
    private void indexHolds(long numFound) throws Exception {
        SolrDocumentList docs = new SolrDocumentList();
        docs.setNumFound(numFound);
        QueryResponse response = mock(QueryResponse.class);
        when(response.getResults()).thenReturn(docs);
        when(solrClient.query(any(SolrQuery.class))).thenReturn(response);
    }

    private void waitForReindex() throws Exception {
        for (int i = 0; i < 200; i++) {
            ReindexStatus st = svc.getReindexStatus(REPO);
            if (st != null && !"running".equals(st.getStatus())) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /**
     * The case that was reproduced live: the walk sees nothing, the index holds plenty.
     */
    @Test
    void anEmptyEnumerationDoesNotClearAPopulatedIndex() throws Exception {
        when(contentService.getChildren(anyString(), anyString())).thenReturn(List.<Content>of());
        indexHolds(164);

        assertTrue(svc.startFullReindex(REPO));
        waitForReindex();

        // The whole point: the index survives.
        verify(solrClient, never()).deleteByQuery(anyString());

        ReindexStatus st = svc.getReindexStatus(REPO);
        assertNotNull(st);
        assertEquals("error", st.getStatus(),
                "a reindex that found nothing to rebuild must not report success — reporting "
                        + "completed is how the wipe went unnoticed");
        assertTrue(st.getErrorMessage() != null && st.getErrorMessage().contains("164"),
                "the operator needs both numbers to understand what was refused, got: "
                        + st.getErrorMessage());
    }

    /**
     * The other half. Without this, "never clear anything" would pass the test above and quietly
     * break every legitimate reindex.
     */
    @Test
    void anEnumerationThatMatchesTheIndexProceeds() throws Exception {
        Folder child = new Folder();
        child.setId("child-folder");
        child.setType("cmis:folder");
        child.setObjectType("cmis:folder");
        when(contentService.getChildren(REPO, ROOT)).thenReturn(List.<Content>of(child));
        when(contentService.getChildren(REPO, "child-folder")).thenReturn(List.<Content>of());
        lenient().when(contentService.getFolder(REPO, "child-folder")).thenReturn(child);
        indexHolds(2);

        assertTrue(svc.startFullReindex(REPO));
        waitForReindex();

        verify(solrClient, atLeastOnce()).deleteByQuery(anyString());
        assertEquals("completed", svc.getReindexStatus(REPO).getStatus());
    }

    /**
     * A first-ever reindex starts from an empty index and must not be blocked by the guard.
     */
    @Test
    void anEmptyIndexIsNotProtectedFromItsFirstReindex() throws Exception {
        when(contentService.getChildren(anyString(), anyString())).thenReturn(List.<Content>of());
        indexHolds(0);

        assertTrue(svc.startFullReindex(REPO));
        waitForReindex();

        verify(solrClient, atLeastOnce()).deleteByQuery(anyString());
        assertEquals("completed", svc.getReindexStatus(REPO).getStatus());
    }

    /**
     * If Solr cannot be asked, the count is unknown — and an unknown count must not be read as an
     * empty index, which would wave through the exact case the guard exists to stop.
     */
    @Test
    void anUnreadableIndexCountRefusesRatherThanAssumingEmpty() throws Exception {
        when(contentService.getChildren(anyString(), anyString())).thenReturn(List.<Content>of());
        when(solrClient.query(any(SolrQuery.class))).thenThrow(new RuntimeException("Solr is down"));

        assertTrue(svc.startFullReindex(REPO));
        waitForReindex();

        verify(solrClient, never()).deleteByQuery(anyString());
        assertEquals("error", svc.getReindexStatus(REPO).getStatus());
    }
}
