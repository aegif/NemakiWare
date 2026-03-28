package jp.aegif.nemaki.rest.purview.journal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LineageTargetSinkResultTest {

    @Test
    void success_factory() {
        LineageTargetSinkResult result = LineageTargetSinkResult.success(3, "Published 3 entities");
        assertTrue(result.success());
        assertEquals(3, result.entityCount());
        assertEquals("Published 3 entities", result.message());
    }

    @Test
    void failure_factory() {
        LineageTargetSinkResult result = LineageTargetSinkResult.failure("Connection refused");
        assertFalse(result.success());
        assertEquals(0, result.entityCount());
        assertEquals("Connection refused", result.message());
    }

    @Test
    void skipped_factory() {
        LineageTargetSinkResult result = LineageTargetSinkResult.skipped("target not configured");
        assertTrue(result.success());
        assertEquals(0, result.entityCount());
        assertTrue(result.message().startsWith("skipped:"));
    }
}
