package jp.aegif.nemaki.businesslogic.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.SolrIndexMaintenanceService.ReindexStatus;
import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit tests for SolrIndexMaintenanceServiceImpl - Reindex Operations
 * 
 * Tests the startFullReindex, startFolderReindex, and cancelReindex methods
 * with proper Mockito mocking of dependencies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SolrIndexMaintenanceServiceImplReindexTest {

    /**
     * A batch that wrote {@code written} documents and skipped nothing.
     *
     * <p>The constructor is package-private on purpose (the outcome is produced by SolrUtil, not
     * by callers), and this test lives in a different package, so it is built reflectively rather
     * than by widening the production API for a stub.
     */
    private static jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil.BatchOutcome batchOutcome(int written) {
        return batchOutcome(written, 0, 0);
    }

    private static jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil.BatchOutcome batchOutcome(
            int written, int skippedStale, int fenceBlocked) {
        try {
            java.lang.reflect.Constructor<jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil.BatchOutcome> c =
                    jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil.BatchOutcome.class
                            .getDeclaredConstructor(int.class, int.class, int.class);
            c.setAccessible(true);
            return c.newInstance(written, skippedStale, fenceBlocked);
        } catch (Exception e) {
            throw new IllegalStateException("BatchOutcome shape changed", e);
        }
    }

    /**
     * A document the content fence correctly declined to overwrite is not a failure.
     *
     * <p>SKIP_STALE means Solr already holds a strictly newer generation, so leaving it alone is
     * the right outcome. The error total used to be computed as {@code batch.size() - written},
     * which had no way to tell that apart from a document that genuinely failed — so a healthy
     * reindex reported errors, and an operator reading the status could not tell whether
     * anything was actually wrong.
     */
    @Test
    public void skippedStaleDocumentsAreNotCountedAsErrors() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(rootFolder.getName()).thenReturn("Root");

        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        Document doc2 = mock(Document.class);
        when(doc2.getId()).thenReturn("doc-2");
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID))
                .thenReturn(Arrays.asList(doc1, doc2));

        // Derived from the ACTUAL batch rather than assuming how the reindex splits it: every
        // document is correctly skipped as already-newer, nothing is written. Hard-coding the
        // counts made this test depend on the batching, not on the accounting rule it is about.
        when(solrUtil.indexDocumentsBatch(eq(TEST_REPO_ID), anyList(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> {
                    int size = ((List<?>) inv.getArgument(1)).size();
                    return batchOutcome(0, size, 0);
                });

        assertTrue(service.startFullReindex(TEST_REPO_ID));
        awaitReindexCompletion(TEST_REPO_ID, 5);

        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals(0, status.getErrorCount(),
                "every document was correctly skipped, so there are no errors; the old"
                        + " subtraction counted each skip as a failure");
    }

    /**
     * A fence-blocked document IS an error, and is reported as one.
     *
     * <p>FAIL_CLOSED means no authoritative content_incarnation could be established, so the
     * document was excluded rather than stamped. Unlike SKIP_STALE this needs attention: the
     * verification pass only re-indexes documents MISSING from Solr, and a fence-blocked one
     * usually exists there, so nothing else will pick it up. (SolrUtil hands it to
     * reconciliation for that reason; here we only assert the accounting.)
     */
    @Test
    public void fenceBlockedDocumentsAreCountedAsErrors() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(rootFolder.getName()).thenReturn("Root");

        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID))
                .thenReturn(Arrays.asList(doc1));

        // Every document blocked by the fence, derived from the actual batch.
        when(solrUtil.indexDocumentsBatch(eq(TEST_REPO_ID), anyList(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> {
                    int size = ((List<?>) inv.getArgument(1)).size();
                    return batchOutcome(0, 0, size);
                });

        assertTrue(service.startFullReindex(TEST_REPO_ID));
        awaitReindexCompletion(TEST_REPO_ID, 5);

        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertTrue(status.getErrorCount() > 0,
                "fence-blocked documents are not retried by anything downstream, so a reindex"
                        + " that reported no errors would be the wrong answer");
    }
    
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
    private Folder rootFolder;
    
    @Mock
    private Folder subFolder;
    
    @Mock
    private Document document;
    
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
    
    @Test
    public void testStartFullReindexReturnsTrue() {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFullReindex(TEST_REPO_ID);
        
        assertTrue(started, "startFullReindex should return true");
    }
    
    @Test
    public void testStartFullReindexSetsRunningStatus() {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        service.startFullReindex(TEST_REPO_ID);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("running", status.getStatus());
    }
    
    @Test
    public void testStartFullReindexPreventsDoubleStart() {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean first = service.startFullReindex(TEST_REPO_ID);
        boolean second = service.startFullReindex(TEST_REPO_ID);
        
        assertTrue(first, "First start should succeed");
        assertFalse(second, "Second start should fail while running");
    }
    
    @Test
    public void testStartFullReindexWithEmptyRepository() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFullReindex(TEST_REPO_ID);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
        assertEquals(1, status.getTotalDocuments()); // root folder itself is counted
    }
    
    @Test
    public void testStartFullReindexWithDocuments() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(rootFolder.getName()).thenReturn("Root");
        
        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        Document doc2 = mock(Document.class);
        when(doc2.getId()).thenReturn("doc-2");
        
        List<Content> children = Arrays.asList(doc1, doc2);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(children);
        when(solrUtil.indexDocumentsBatch(eq(TEST_REPO_ID), anyList(), anyInt(), anyBoolean()))
                .thenReturn(batchOutcome(2));

        boolean started = service.startFullReindex(TEST_REPO_ID);
        assertTrue(started);

        awaitReindexCompletion(TEST_REPO_ID, 5);

        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
        assertEquals(3, status.getTotalDocuments()); // root folder + 2 documents
    }
    
    @Test
    public void testStartFolderReindexReturnsTrue() {
        String folderId = "folder-123";
        Folder folder = mock(Folder.class);
        when(contentService.getFolder(TEST_REPO_ID, folderId)).thenReturn(folder);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFolderReindex(TEST_REPO_ID, folderId, true);
        
        assertTrue(started, "startFolderReindex should return true");
    }
    
    @Test
    public void testStartFolderReindexRecursive() throws Exception {
        String folderId = "folder-123";
        Folder folder = mock(Folder.class);
        when(folder.getName()).thenReturn("TestFolder");
        when(contentService.getFolder(TEST_REPO_ID, folderId)).thenReturn(folder);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFolderReindex(TEST_REPO_ID, folderId, true);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
    }
    
    @Test
    public void testStartFolderReindexNonRecursive() throws Exception {
        String folderId = "folder-456";
        Folder folder = mock(Folder.class);
        when(folder.getName()).thenReturn("TestFolder");
        when(contentService.getFolder(TEST_REPO_ID, folderId)).thenReturn(folder);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        boolean started = service.startFolderReindex(TEST_REPO_ID, folderId, false);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
    }
    
    @Test
    public void testStartFolderReindexWithNonExistentFolder() throws Exception {
        String folderId = "non-existent-folder";
        when(contentService.getFolder(TEST_REPO_ID, folderId)).thenReturn(null);
        
        boolean started = service.startFolderReindex(TEST_REPO_ID, folderId, true);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("error", status.getStatus());
        assertTrue(status.getErrorMessage().contains("Folder not found"));
    }
    
    @Test
    public void testCancelReindexWhenIdle() {
        boolean cancelled = service.cancelReindex(TEST_REPO_ID);
        
        assertFalse(cancelled, "Cancel should return false when no reindex is running");
    }
    
    @Test
    public void testCancelReindexWhenRunning() {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getChildren(eq(TEST_REPO_ID), anyString())).thenReturn(new ArrayList<>());
        
        service.startFullReindex(TEST_REPO_ID);
        
        boolean cancelled = service.cancelReindex(TEST_REPO_ID);
        
        assertTrue(cancelled, "Cancel should return true when reindex is running");
    }
    
    @Test
    public void testCancelReindexForNonExistentRepo() {
        boolean cancelled = service.cancelReindex("non-existent-repo");
        
        assertFalse(cancelled, "Cancel should return false for non-existent repo");
    }
    
    @Test
    public void testReindexStatusAfterCancel() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        
        List<Content> manyDocs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Document doc = mock(Document.class);
            lenient().when(doc.getId()).thenReturn("doc-" + i);
            manyDocs.add(doc);
        }
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(manyDocs);
        
        service.startFullReindex(TEST_REPO_ID);
        service.cancelReindex(TEST_REPO_ID);
        
        awaitReindexCompletion(TEST_REPO_ID, 10);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertTrue("cancelled".equals(status.getStatus()) || "completed".equals(status.getStatus()), "Status should be cancelled or completed");
    }
    
    @Test
    public void testStartFullReindexWithRootFolderNotFound() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(null);
        
        boolean started = service.startFullReindex(TEST_REPO_ID);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("error", status.getStatus());
        assertEquals("Root folder not found", status.getErrorMessage());
    }
    
    @Test
    public void testStartFullReindexWithSubfolders() throws Exception {
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootFolder);
        when(rootFolder.getId()).thenReturn(ROOT_FOLDER_ID);
        when(rootFolder.getName()).thenReturn("Root");
        
        Folder subFolder = mock(Folder.class);
        when(subFolder.getId()).thenReturn("sub-folder-1");
        when(subFolder.getName()).thenReturn("SubFolder");
        
        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        
        List<Content> rootChildren = Arrays.asList(subFolder, doc1);
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(rootChildren);
        
        when(contentService.getFolder(TEST_REPO_ID, "sub-folder-1")).thenReturn(subFolder);
        when(contentService.getChildren(TEST_REPO_ID, "sub-folder-1")).thenReturn(new ArrayList<>());
        
        // Derived from the ACTUAL batch, like the sibling above. Hard-coded, this said "1 of
        // the batch was written" while the walk flushes TWO (the sub-folder is added to
        // subFolders AND to the batch buffer), so it quietly asserted that one document failed
        // — in a test about recursion. That stray count is what a status change was once
        // withdrawn over.
        when(solrUtil.indexDocumentsBatch(eq(TEST_REPO_ID), anyList(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> batchOutcome(((List<?>) inv.getArgument(1)).size()));

        boolean started = service.startFullReindex(TEST_REPO_ID);
        assertTrue(started);
        
        awaitReindexCompletion(TEST_REPO_ID, 5);
        
        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals("completed", status.getStatus());
    }
    
    @Test
    public void testMultipleRepositoriesCanReindexSimultaneously() {
        String repo1 = "repo-1";
        String repo2 = "repo-2";
        
        RepositoryInfo info1 = mock(RepositoryInfo.class);
        RepositoryInfo info2 = mock(RepositoryInfo.class);
        when(repositoryInfoMap.get(repo1)).thenReturn(info1);
        when(repositoryInfoMap.get(repo2)).thenReturn(info2);
        when(info1.getRootFolderId()).thenReturn("root-1");
        when(info2.getRootFolderId()).thenReturn("root-2");
        
        Folder root1 = mock(Folder.class);
        Folder root2 = mock(Folder.class);
        when(root1.getId()).thenReturn("root-1");
        when(root2.getId()).thenReturn("root-2");
        when(contentService.getFolder(repo1, "root-1")).thenReturn(root1);
        when(contentService.getFolder(repo2, "root-2")).thenReturn(root2);
        when(contentService.getChildren(eq(repo1), anyString())).thenReturn(new ArrayList<>());
        when(contentService.getChildren(eq(repo2), anyString())).thenReturn(new ArrayList<>());
        
        boolean started1 = service.startFullReindex(repo1);
        boolean started2 = service.startFullReindex(repo2);
        
        assertTrue(started1, "First repo should start");
        assertTrue(started2, "Second repo should also start");
    }
    
    private void awaitReindexCompletion(String repositoryId, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000);
        while (System.currentTimeMillis() < deadline) {
            ReindexStatus status = service.getReindexStatus(repositoryId);
            if (!"running".equals(status.getStatus())) {
                return;
            }
            Thread.sleep(100);
        }
        ReindexStatus finalStatus = service.getReindexStatus(repositoryId);
        fail("Reindex did not complete within " + timeoutSeconds + " seconds. " +
             "Current status: " + finalStatus.getStatus() + 
             ", indexed: " + finalStatus.getIndexedCount() + "/" + finalStatus.getTotalDocuments());
    }

    @Test
    public void aRunThatFailedToIndexDocumentsIsNotReportedAsCompleted() throws Exception {
        // The status is what the UI colours green. A reindex that could not index some of the
        // documents it walked used to end on the same word as one that indexed everything, so
        // an operator running the REQUIRED upgrade reindex (CLAUDE.md) saw "Completed" over a
        // partial index — and the count that contradicts it is one field away, unread.
        //
        // Batch of 2, one written: one document failed.
        Folder root = mock(Folder.class);
        when(root.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(root);
        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        Document doc2 = mock(Document.class);
        when(doc2.getId()).thenReturn("doc-2");
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID))
                .thenReturn(Arrays.asList(doc1, doc2));
        when(solrUtil.indexDocumentsBatch(eq(TEST_REPO_ID), anyList(), anyInt(), anyBoolean()))
                .thenReturn(batchOutcome(1));

        assertTrue(service.startFullReindex(TEST_REPO_ID));
        awaitReindexCompletion(TEST_REPO_ID, 5);

        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals(1, status.getErrorCount(),
                "fixture check: no document failed, so this test is not looking at the case it "
                        + "exists for");
        assertEquals("completed_with_errors", status.getStatus(),
                "a reindex that failed on " + status.getErrorCount() + " document(s) reported "
                        + "itself as a clean completion: " + status.getErrors());
    }

    @Test
    public void anErrorListThatWasCutOffSaysSo() throws Exception {
        // The cap was silent: a run with thousands of failures showed the full count beside a
        // hundred messages, with nothing explaining the difference — so a reader either doubts
        // the count or takes the hundred for all of them. The sibling in this same change,
        // FixityScanReport's findingsTruncated, reached the opposite conclusion about the
        // identical problem, and its reason applies word for word: a reader COULD infer the cap
        // by comparing two numbers, and nobody reads a report that way.
        java.lang.reflect.Method noted = SolrIndexMaintenanceServiceImpl.class
                .getDeclaredMethod("withTruncationNoted", List.class, long.class);
        noted.setAccessible(true);

        List<String> short_ = new ArrayList<>(List.of("one failure"));
        @SuppressWarnings("unchecked")
        List<String> unchanged = (List<String>) noted.invoke(null, short_, 1L);
        assertEquals(1, unchanged.size(),
                "a list that was NOT cut off gained a note saying it was: " + unchanged);

        // The boundary. EXACTLY 100 failures fit in exactly 100 messages, so nothing was
        // dropped and claiming otherwise is an overclaim in the direction of doubt. The first
        // version keyed on the list SIZE and said "only the first 100 are kept" here.
        List<String> exactly = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            exactly.add("failure " + i);
        }
        @SuppressWarnings("unchecked")
        List<String> untouched = (List<String>) noted.invoke(null, exactly, 100L);
        assertEquals(100, untouched.size(),
                "a full-but-complete list was told it had been cut off: "
                        + untouched.get(untouched.size() - 1));

        List<String> full = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            full.add("failure " + i);
        }
        @SuppressWarnings("unchecked")
        List<String> capped = (List<String>) noted.invoke(null, full, 5000L);
        assertEquals(101, capped.size(), "the cut-off list gained no note at all");
        String note = capped.get(capped.size() - 1);
        assertTrue(note.contains("4900"),
                "the note does not say how many failures are counted but not described: " + note);

        // ...and the reindex has to USE it. Driving the helper alone let the production call
        // sites be reverted with the suite still green — a helper nothing calls is not a
        // protection, and silent truncation would be back. Reaching 100 messages through a real
        // reindex would need 100 BATCHES (the list gains one message per batch, not per
        // document), so the wiring is checked where it is written.
        String impl = jp.aegif.nemaki.util.test.JavaSource.withoutComments(
                jp.aegif.nemaki.util.test.JavaSource.read(
                        "src/main/java/jp/aegif/nemaki/businesslogic/impl/"
                                + "SolrIndexMaintenanceServiceImpl.java"));
        // Scoped to the calls that hand over the ACCUMULATED list. The first version counted
        // every setErrors and failed on the two that install a fresh empty one at the start of
        // a run — correct code, and wrapping them would mean nothing. An assertion that flags
        // right code teaches the next reader to widen it until it flags nothing at all.
        // Any setErrors whose argument is a bare identifier -- not "errors" by name, which
        // pinned a local variable's spelling: renaming it to errorMessages would have made the
        // count zero and switched this assertion off in silence.
        int raw = (int) java.util.regex.Pattern
                .compile("setErrors\\(\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*\\)")
                .matcher(impl).results().count();
        int wrapped = impl.split("setErrors\\(withTruncationNoted\\(", -1).length - 1;
        assertTrue(wrapped >= 3,
                "only " + wrapped + " setErrors call(s) go through the truncation note; the "
                        + "production call sites can be reverted with this suite still green, "
                        + "and silent truncation is back");
        assertEquals(0, raw,
                raw + " setErrors call(s) hand over the raw accumulated list, so a run that "
                        + "lost messages to the cap reports a short list as the whole of it");
    }

    @Test
    public void aShortListingIsAReindexFailureNotASmallerFolder() throws Exception {
        // Rows the repository cannot decode are absent from getChildren without any exception,
        // so the walk passed them in silence: not indexed, not counted, run "completed". The
        // documents stay missing from search until the folder is reindexed for some unrelated
        // reason — and nothing tells anyone to do that.
        Folder root = mock(Folder.class);
        when(root.getId()).thenReturn(ROOT_FOLDER_ID);
        when(contentService.getFolder(TEST_REPO_ID, ROOT_FOLDER_ID)).thenReturn(root);
        Document doc1 = mock(Document.class);
        when(doc1.getId()).thenReturn("doc-1");
        when(contentService.getChildren(TEST_REPO_ID, ROOT_FOLDER_ID))
                .thenReturn(java.util.Collections.singletonList(doc1));
        when(contentService.lastUnreadableChildCount()).thenReturn(2);
        when(solrUtil.indexDocumentsBatch(eq(TEST_REPO_ID), anyList(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> batchOutcome(((List<?>) inv.getArgument(1)).size()));

        assertTrue(service.startFullReindex(TEST_REPO_ID));
        awaitReindexCompletion(TEST_REPO_ID, 5);

        ReindexStatus status = service.getReindexStatus(TEST_REPO_ID);
        assertEquals(2, status.getErrorCount(),
                "two children the repository could not decode left no trace on the run: "
                        + status.getErrors());
        assertEquals("completed_with_errors", status.getStatus(),
                "a reindex that silently walked past unreadable children reported a clean "
                        + "completion: " + status.getErrors());
    }
}
