package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class LineagePublishStatusTest {

    @Test
    public void testTerminalStates() {
        assertTrue(LineagePublishStatus.PUBLISHED.isTerminal());
        assertTrue(LineagePublishStatus.SKIPPED.isTerminal());
        assertTrue(LineagePublishStatus.DISCARDED.isTerminal());
    }

    @Test
    public void testNonTerminalStates() {
        assertFalse(LineagePublishStatus.PENDING.isTerminal());
        assertFalse(LineagePublishStatus.PROJECTING.isTerminal());
        assertFalse(LineagePublishStatus.FAILED.isTerminal());
    }
}
