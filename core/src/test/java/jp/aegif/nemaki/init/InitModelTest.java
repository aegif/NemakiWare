package jp.aegif.nemaki.init;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for init package model classes: CouchDbConnectionResult, RepositoryOverview.
 */
public class InitModelTest {

    // ---- CouchDbConnectionResult ----

    @Test
    public void testCouchDbConnectionResultReachable() {
        CouchDbConnectionResult result = new CouchDbConnectionResult();
        result.setReachable(true);
        result.setCouchDbVersion("3.3.3");
        result.setResponseTimeMs(42);

        assertTrue(result.isReachable());
        assertEquals("3.3.3", result.getCouchDbVersion());
        assertNull(result.getErrorMessage());
        assertEquals(42, result.getResponseTimeMs());
    }

    @Test
    public void testCouchDbConnectionResultUnreachable() {
        CouchDbConnectionResult result = new CouchDbConnectionResult();
        result.setReachable(false);
        result.setErrorMessage("Connection refused");
        result.setResponseTimeMs(5001);

        assertFalse(result.isReachable());
        assertNull(result.getCouchDbVersion());
        assertEquals("Connection refused", result.getErrorMessage());
        assertEquals(5001, result.getResponseTimeMs());
    }

    @Test
    public void testCouchDbConnectionResultDefaults() {
        CouchDbConnectionResult result = new CouchDbConnectionResult();
        assertFalse(result.isReachable());
        assertNull(result.getCouchDbVersion());
        assertNull(result.getErrorMessage());
        assertEquals(0, result.getResponseTimeMs());
    }

    // ---- RepositoryOverview ----

    @Test
    public void testRepositoryOverviewCurrent() {
        RepositoryOverview ov = new RepositoryOverview();
        ov.setRepositoryId("bedroom");
        ov.setName("bedroom");
        ov.setDocumentCount(150);
        ov.setViewCount(38);
        ov.setRequiredViewCount(38);
        ov.setHasSystemFolder(true);
        ov.setJudgment("current");

        assertEquals("bedroom", ov.getRepositoryId());
        assertEquals("bedroom", ov.getName());
        assertEquals(150, ov.getDocumentCount());
        assertEquals(38, ov.getViewCount());
        assertEquals(38, ov.getRequiredViewCount());
        assertTrue(ov.isHasSystemFolder());
        assertEquals("current", ov.getJudgment());
    }

    @Test
    public void testRepositoryOverviewEmpty() {
        RepositoryOverview ov = new RepositoryOverview();
        ov.setRepositoryId("canopy");
        ov.setDocumentCount(0);
        ov.setViewCount(0);
        ov.setRequiredViewCount(38);
        ov.setHasSystemFolder(false);
        ov.setJudgment("empty");

        assertEquals("canopy", ov.getRepositoryId());
        assertEquals(0, ov.getDocumentCount());
        assertEquals("empty", ov.getJudgment());
        assertFalse(ov.isHasSystemFolder());
    }

    @Test
    public void testRepositoryOverviewLegacy() {
        RepositoryOverview ov = new RepositoryOverview();
        ov.setRepositoryId("bedroom");
        ov.setViewCount(20);
        ov.setRequiredViewCount(38);
        ov.setJudgment("legacy");

        assertEquals(20, ov.getViewCount());
        assertEquals("legacy", ov.getJudgment());
    }

    @Test
    public void testRepositoryOverviewDefaults() {
        RepositoryOverview ov = new RepositoryOverview();
        assertNull(ov.getRepositoryId());
        assertNull(ov.getName());
        assertEquals(0, ov.getDocumentCount());
        assertEquals(0, ov.getViewCount());
        assertEquals(0, ov.getRequiredViewCount());
        assertFalse(ov.isHasSystemFolder());
        assertNull(ov.getJudgment());
    }
}
