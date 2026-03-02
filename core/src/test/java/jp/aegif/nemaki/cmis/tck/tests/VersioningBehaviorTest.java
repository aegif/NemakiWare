package jp.aegif.nemaki.cmis.tck.tests;

import org.apache.chemistry.opencmis.client.api.*;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavior-fixing tests for checkOut/checkIn/cancelCheckOut/createAttachment.
 *
 * Purpose: Pin the current observable behavior of ContentServiceImpl's
 * versioning and attachment operations so that delegate extraction
 * (VersioningServiceDelegate, AttachmentServiceDelegate) can be validated
 * against this baseline.
 *
 * These are integration tests requiring a running NemakiWare server + CouchDB.
 * Run with: mvn test -Dtest=VersioningBehaviorTest -f core/pom.xml -Pdevelopment
 *
 * @see docs/adr/001-versioning-attachment-delegate-extraction.md
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
public class VersioningBehaviorTest {

    private static Session session;
    private static Folder testFolder;
    private static String testFolderId;
    private static final String TEST_FOLDER_NAME = "behavior-test-" + System.currentTimeMillis();

    @BeforeAll
    public static void setUpSession() {
        Map<String, String> params = new HashMap<>();
        params.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
        params.put(SessionParameter.ATOMPUB_URL, "http://localhost:8080/core/atom/bedroom");
        params.put(SessionParameter.USER, "admin");
        params.put(SessionParameter.PASSWORD, "admin");
        params.put(SessionParameter.REPOSITORY_ID, "bedroom");
        params.put(SessionParameter.CONNECT_TIMEOUT, "30000");
        params.put(SessionParameter.READ_TIMEOUT, "120000");

        // Phase 1: Create session (may throw CmisConnectionException if server is down)
        SessionFactory factory = SessionFactoryImpl.newInstance();
        try {
            session = factory.createSession(params);
        } catch (CmisConnectionException e) {
            if (isConnectivityError(e)) {
                Assumptions.assumeTrue(false, "NemakiWare server not reachable at localhost:8080 — skipping");
            }
            throw e; // authentication / protocol errors → fail fast
        }

        // Phase 2: Verify session works by accessing root folder
        try {
            session.getRootFolder();
        } catch (CmisConnectionException e) {
            if (isConnectivityError(e)) {
                Assumptions.assumeTrue(false, "NemakiWare server not reachable at localhost:8080 — skipping");
            }
            throw e;
        }
        // Authentication, permission, or repository errors beyond this point → fail fast

        // Phase 3: Create isolated test folder (setup regression = test failure)
        Map<String, Object> folderProps = new HashMap<>();
        folderProps.put(PropertyIds.NAME, TEST_FOLDER_NAME);
        folderProps.put(PropertyIds.OBJECT_TYPE_ID, "cmis:folder");
        testFolder = session.getRootFolder().createFolder(folderProps);
        testFolderId = testFolder.getId();
    }

    /**
     * Returns true only if the root cause indicates the server is unreachable
     * (network-level connectivity failure). Authentication errors, protocol
     * mismatches, and other CMIS errors return false so they fail fast.
     *
     * Covers: ConnectException (connection refused), UnknownHostException (DNS failure),
     * NoRouteToHostException (no route), SocketException (Operation not permitted,
     * Network is unreachable, etc.)
     */
    private static boolean isConnectivityError(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConnectException) {
                return true;
            }
            if (cause instanceof java.net.UnknownHostException) {
                return true;
            }
            if (cause instanceof java.net.NoRouteToHostException) {
                return true;
            }
            if (cause instanceof java.net.SocketException) {
                String msg = cause.getMessage();
                if (msg != null) {
                    String normalized = msg.toLowerCase(Locale.ROOT);
                    // Treat only network-level socket failures as connectivity issues.
                    if (normalized.contains("operation not permitted")
                            || normalized.contains("permission denied")
                            || normalized.contains("network is unreachable")
                            || normalized.contains("no route to host")
                            || normalized.contains("connection refused")
                            || normalized.contains("connection timed out")
                            || normalized.contains("host is down")) {
                        return true;
                    }
                }
            }
            if (cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @AfterAll
    public static void tearDown() {
        if (testFolderId != null && session != null) {
            try {
                Folder folder = (Folder) session.getObject(testFolderId);
                folder.deleteTree(true, null, true);
            } catch (Exception e) {
                System.err.println("[VersioningBehaviorTest] Cleanup failed: " + e.getMessage());
            }
        }
        if (session != null) {
            session.clear();
        }
    }

    // ========== Helper Methods ==========

    private Document createTestDocument(String name, String content) {
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, name);
        props.put(PropertyIds.OBJECT_TYPE_ID, "cmis:document");

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ContentStream cs = new ContentStreamImpl(name, BigInteger.valueOf(bytes.length),
                "text/plain", new ByteArrayInputStream(bytes));

        return testFolder.createDocument(props, cs, VersioningState.MAJOR);
    }

    private ContentStream createContentStream(String name, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ContentStreamImpl(name, BigInteger.valueOf(bytes.length),
                "text/plain", new ByteArrayInputStream(bytes));
    }

    // ========== CheckOut Behavior Tests ==========

    @Test
    public void test01_checkOut_createsPwcWithCorrectProperties() {
        Document doc = createTestDocument("checkout-test.txt", "initial content");
        assertNotNull(doc, "Document should be created");
        assertEquals("1.0", doc.getVersionLabel(), "Initial version should be 1.0");
        assertFalse(doc.isVersionSeriesCheckedOut(), "Document should not be checked out initially");

        // CheckOut
        ObjectId pwcId = doc.checkOut();
        assertNotNull(pwcId, "PWC ID should be returned");

        // Refresh and verify checkout state
        doc.refresh();
        assertTrue(doc.isVersionSeriesCheckedOut(), "Version series should be checked out");

        // Get PWC and verify properties
        Document pwc = (Document) session.getObject(pwcId);
        assertNotNull(pwc, "PWC should exist");
        // NemakiWare behavior: PWC versionLabel is null (not set until checkIn)
        assertNull(pwc.getVersionLabel(), "PWC version label should be null in NemakiWare");
        assertEquals("checkout-test.txt", pwc.getName(), "PWC name should match original");
        assertFalse(pwc.isLatestVersion(), "PWC should not be latest version");

        // Cleanup: cancel checkout
        pwc.cancelCheckOut();
    }

    @Test
    public void test02_checkOut_preservesContentInPwc() throws Exception {
        String originalContent = "content to preserve during checkout";
        Document doc = createTestDocument("checkout-content.txt", originalContent);

        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);

        // Verify PWC has same content
        ContentStream pwcStream = pwc.getContentStream();
        assertNotNull(pwcStream, "PWC should have content stream");
        String pwcContent = new String(pwcStream.getStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(originalContent, pwcContent, "PWC content should match original");

        pwc.cancelCheckOut();
    }

    @Test
    public void test03_checkOut_setsCheckedOutBy() {
        Document doc = createTestDocument("checkout-by.txt", "content");

        ObjectId pwcId = doc.checkOut();
        doc.refresh();

        String checkedOutBy = doc.getVersionSeriesCheckedOutBy();
        assertEquals("admin", checkedOutBy, "Checked out by should be admin");

        Document pwc = (Document) session.getObject(pwcId);
        pwc.cancelCheckOut();
    }

    // ========== CancelCheckOut Behavior Tests ==========

    @Test
    public void test04_cancelCheckOut_removesPwcAndResetsState() {
        Document doc = createTestDocument("cancel-test.txt", "content");
        ObjectId pwcId = doc.checkOut();

        Document pwc = (Document) session.getObject(pwcId);
        pwc.cancelCheckOut();

        // Refresh original document
        doc.refresh();
        assertFalse(doc.isVersionSeriesCheckedOut(), "Version series should not be checked out after cancel");

        // Verify PWC no longer exists
        try {
            session.getObject(pwcId);
            fail("PWC should have been deleted after cancelCheckOut");
        } catch (Exception e) {
            // Expected: PWC deleted
        }
    }

    @Test
    public void test05_cancelCheckOut_preservesOriginalVersion() {
        String originalContent = "original content preserved";
        Document doc = createTestDocument("cancel-preserve.txt", originalContent);
        String originalVersionLabel = doc.getVersionLabel();

        doc.checkOut();
        doc.refresh();
        Document pwc = (Document) session.getObject(doc.getVersionSeriesCheckedOutId());
        pwc.cancelCheckOut();

        // Re-read document
        doc.refresh();
        assertEquals(originalVersionLabel, doc.getVersionLabel(), "Version label should remain unchanged after cancel");
        assertTrue(doc.isLatestVersion(), "Should still be latest version after cancel");
    }

    // ========== CheckIn Behavior Tests ==========

    @Test
    public void test06_checkIn_majorVersion_incrementsVersionLabel() {
        Document doc = createTestDocument("checkin-major.txt", "v1 content");
        assertEquals("1.0", doc.getVersionLabel(), "Initial version should be 1.0");

        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);

        // CheckIn as major version
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "checkin-major.txt");
        ContentStream newContent = createContentStream("checkin-major.txt", "v2 content");
        ObjectId newVersionId = pwc.checkIn(true, props, newContent, "Major version check-in");

        Document newVersion = (Document) session.getObject(newVersionId);
        assertEquals("2.0", newVersion.getVersionLabel(), "New version label should be 2.0");
        assertTrue(newVersion.isLatestVersion(), "New version should be latest");
        assertTrue(newVersion.isLatestMajorVersion(), "New version should be latest major");
    }

    @Test
    public void test07_checkIn_minorVersion_incrementsMinorLabel() {
        Document doc = createTestDocument("checkin-minor.txt", "v1 content");

        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);

        // CheckIn as minor version
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "checkin-minor.txt");
        ContentStream newContent = createContentStream("checkin-minor.txt", "v1.1 content");
        ObjectId newVersionId = pwc.checkIn(false, props, newContent, "Minor version check-in");

        Document newVersion = (Document) session.getObject(newVersionId);
        assertEquals("1.1", newVersion.getVersionLabel(), "New version label should be 1.1");
        assertTrue(newVersion.isLatestVersion(), "New version should be latest");
    }

    @Test
    public void test08_checkIn_updatesContent() throws Exception {
        Document doc = createTestDocument("checkin-content.txt", "original content");

        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);

        String updatedContent = "updated content after checkin";
        ContentStream newCs = createContentStream("checkin-content.txt", updatedContent);
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "checkin-content.txt");
        ObjectId newVersionId = pwc.checkIn(true, props, newCs, "Content update");

        Document newVersion = (Document) session.getObject(newVersionId);
        ContentStream resultStream = newVersion.getContentStream();
        String resultContent = new String(resultStream.getStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(updatedContent, resultContent, "Content should be updated after checkIn");
    }

    @Test
    public void test09_checkIn_resetsCheckedOutState() {
        Document doc = createTestDocument("checkin-reset.txt", "content");

        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);

        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "checkin-reset.txt");
        ContentStream newCs = createContentStream("checkin-reset.txt", "new content");
        ObjectId newVersionId = pwc.checkIn(true, props, newCs, "Reset check");

        Document newVersion = (Document) session.getObject(newVersionId);
        assertFalse(newVersion.isVersionSeriesCheckedOut(), "Version series should not be checked out after checkIn");
    }

    @Test
    public void test10_checkIn_previousVersionNotLatest() {
        Document doc = createTestDocument("checkin-prev.txt", "v1");
        String v1Id = doc.getId();

        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "checkin-prev.txt");
        ContentStream newCs = createContentStream("checkin-prev.txt", "v2");
        pwc.checkIn(true, props, newCs, "New version");

        // Verify previous version is no longer latest
        Document v1 = (Document) session.getObject(v1Id);
        v1.refresh();
        assertFalse(v1.isLatestVersion(), "Previous version should NOT be latest");
        assertFalse(v1.isLatestMajorVersion(), "Previous version should NOT be latest major");
    }

    @Test
    public void test11_checkIn_versionHistory_ordered() {
        Document doc = createTestDocument("checkin-history.txt", "v1");

        // Create v2
        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "checkin-history.txt");
        ContentStream cs2 = createContentStream("checkin-history.txt", "v2");
        ObjectId v2Id = pwc.checkIn(true, props, cs2, "Version 2");

        // Create v3
        Document v2 = (Document) session.getObject(v2Id);
        ObjectId pwcId2 = v2.checkOut();
        Document pwc2 = (Document) session.getObject(pwcId2);
        ContentStream cs3 = createContentStream("checkin-history.txt", "v3");
        ObjectId v3Id = pwc2.checkIn(true, props, cs3, "Version 3");

        Document v3 = (Document) session.getObject(v3Id);
        List<Document> versions = v3.getAllVersions();

        assertNotNull(versions, "Version history should not be null");
        assertTrue(versions.size() >= 3, "Should have at least 3 versions");

        // CMIS spec: versions are ordered latest-first
        assertEquals("3.0", versions.get(0).getVersionLabel(), "First version in list should be latest (3.0)");
    }

    // ========== Attachment / Content Stream Tests ==========

    @Test
    public void test12_contentStream_updateViaCheckIn() throws Exception {
        Document doc = createTestDocument("attachment-test.txt", "initial");

        // NemakiWare behavior: content update on versioned documents is via checkOut/checkIn
        String newContent = "updated attachment content with special chars: 日本語テスト";
        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);

        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "attachment-test.txt");
        ContentStream newCs = createContentStream("attachment-test.txt", newContent);
        ObjectId newVersionId = pwc.checkIn(true, props, newCs, "Content update");

        Document newVersion = (Document) session.getObject(newVersionId);
        ContentStream resultStream = newVersion.getContentStream();
        assertNotNull(resultStream, "Content stream should exist");
        String result = new String(resultStream.getStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(newContent, result, "Content should match after checkIn");
    }

    @Test
    public void test13_contentStream_mimeTypePreserved() throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "test-mime.html");
        props.put(PropertyIds.OBJECT_TYPE_ID, "cmis:document");

        byte[] htmlContent = "<html><body>test</body></html>".getBytes(StandardCharsets.UTF_8);
        ContentStream cs = new ContentStreamImpl("test-mime.html",
                BigInteger.valueOf(htmlContent.length),
                "text/html", new ByteArrayInputStream(htmlContent));

        Document doc = testFolder.createDocument(props, cs, VersioningState.MAJOR);
        ContentStream resultStream = doc.getContentStream();
        assertEquals("text/html", resultStream.getMimeType(), "MIME type should be preserved");
    }

    @Test
    public void test14_contentStream_lengthReported() throws Exception {
        String content = "known length content for verification";
        Document doc = createTestDocument("length-test.txt", content);

        ContentStream resultStream = doc.getContentStream();
        assertNotNull(resultStream, "Content stream should exist");
        long reportedLength = resultStream.getLength();
        assertTrue(reportedLength > 0, "Content length should be positive");
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, reportedLength, "Content length should match");
    }

    @Test
    public void test15_contentStream_emptyContent() {
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "empty-content.txt");
        props.put(PropertyIds.OBJECT_TYPE_ID, "cmis:document");

        // Create document with empty content
        byte[] emptyBytes = new byte[0];
        ContentStream cs = new ContentStreamImpl("empty-content.txt",
                BigInteger.ZERO, "text/plain", new ByteArrayInputStream(emptyBytes));

        Document doc = testFolder.createDocument(props, cs, VersioningState.MAJOR);
        assertNotNull(doc, "Document with empty content should be created");
    }

    // ========== Combined Scenario Tests ==========

    @Test
    public void test16_fullVersioningCycle_checkOutCheckInCancel() throws Exception {
        // Create initial document
        Document doc = createTestDocument("full-cycle.txt", "version 1.0");
        String versionSeriesId = doc.getVersionSeriesId();
        assertNotNull(versionSeriesId, "Version series ID should exist");

        // Cycle 1: CheckOut -> CheckIn (major)
        ObjectId pwcId1 = doc.checkOut();
        Document pwc1 = (Document) session.getObject(pwcId1);
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "full-cycle.txt");
        ContentStream cs2 = createContentStream("full-cycle.txt", "version 2.0");
        ObjectId v2Id = pwc1.checkIn(true, props, cs2, "Major update");

        Document v2 = (Document) session.getObject(v2Id);
        assertEquals("2.0", v2.getVersionLabel());

        // Cycle 2: CheckOut -> Cancel
        ObjectId pwcId2 = v2.checkOut();
        Document pwc2 = (Document) session.getObject(pwcId2);
        pwc2.cancelCheckOut();

        v2.refresh();
        assertFalse(v2.isVersionSeriesCheckedOut(), "Should not be checked out after cancel");
        assertEquals("2.0", v2.getVersionLabel(), "Version label should still be 2.0 after cancel");

        // Cycle 3: CheckOut -> CheckIn (minor)
        ObjectId pwcId3 = v2.checkOut();
        Document pwc3 = (Document) session.getObject(pwcId3);
        ContentStream cs3 = createContentStream("full-cycle.txt", "version 2.1");
        ObjectId v3Id = pwc3.checkIn(false, props, cs3, "Minor update");

        Document v3 = (Document) session.getObject(v3Id);
        assertEquals("2.1", v3.getVersionLabel());
        assertTrue(v3.isLatestVersion(), "Latest version should be v2.1");

        // Verify version history
        List<Document> history = v3.getAllVersions();
        assertTrue(history.size() >= 3, "Should have at least 3 versions");
    }

    @Test
    public void test17_checkIn_checkinCommentPreserved() {
        Document doc = createTestDocument("comment-test.txt", "content");

        ObjectId pwcId = doc.checkOut();
        Document pwc = (Document) session.getObject(pwcId);

        String checkinComment = "Fixed bug #1234 - important regression fix";
        Map<String, Object> props = new HashMap<>();
        props.put(PropertyIds.NAME, "comment-test.txt");
        ContentStream newCs = createContentStream("comment-test.txt", "new content");
        ObjectId newVersionId = pwc.checkIn(true, props, newCs, checkinComment);

        Document newVersion = (Document) session.getObject(newVersionId);
        assertEquals(checkinComment, newVersion.getCheckinComment(), "Check-in comment should be preserved");
    }
}
