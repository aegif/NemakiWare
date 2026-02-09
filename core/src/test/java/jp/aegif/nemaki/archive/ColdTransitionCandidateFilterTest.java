package jp.aegif.nemaki.archive;

import jp.aegif.nemaki.model.Archive;
import org.junit.Test;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.Calendar;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests the filtering logic for cold-move candidates.
 *
 * The key rule: archives that already have coldArchivedAt set (i.e., already
 * copied/moved to cold storage) must NOT be selected again as candidates.
 * This prevents infinite re-processing of COPY-mode archives that remain
 * in ARCHIVED_LOCAL state.
 */
public class ColdTransitionCandidateFilterTest {

    /**
     * Simulates the filtering logic in ContentDaoServiceImpl.getArchivesForColdTransition().
     */
    private List<Archive> filterCandidates(List<Archive> localArchives, GregorianCalendar cutoffDate) {
        List<Archive> candidates = new ArrayList<>();
        for (Archive a : localArchives) {
            if (a.getColdArchivedAt() != null) {
                continue;
            }
            if (a.getArchivedAt() != null && a.getArchivedAt().before(cutoffDate)) {
                candidates.add(a);
            }
        }
        return candidates;
    }

    private Archive createArchive(String id, int daysAgoArchived, GregorianCalendar coldArchivedAt) {
        Archive archive = new Archive();
        archive.setId(id);
        archive.setOriginalId("orig-" + id);
        archive.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);

        GregorianCalendar archivedAt = new GregorianCalendar();
        archivedAt.add(Calendar.DAY_OF_YEAR, -daysAgoArchived);
        archive.setArchivedAt(archivedAt);

        if (coldArchivedAt != null) {
            archive.setColdArchivedAt(coldArchivedAt);
        }

        return archive;
    }

    @Test
    public void testNormalArchive_selectedAsCandidate() {
        GregorianCalendar cutoff = new GregorianCalendar();
        cutoff.add(Calendar.DAY_OF_YEAR, -30);

        List<Archive> archives = new ArrayList<>();
        archives.add(createArchive("a1", 60, null));  // 60 days old, no cold copy

        List<Archive> candidates = filterCandidates(archives, cutoff);
        assertEquals("Normal old archive should be selected", 1, candidates.size());
    }

    @Test
    public void testRecentArchive_notSelected() {
        GregorianCalendar cutoff = new GregorianCalendar();
        cutoff.add(Calendar.DAY_OF_YEAR, -30);

        List<Archive> archives = new ArrayList<>();
        archives.add(createArchive("a1", 10, null));  // 10 days old, too recent

        List<Archive> candidates = filterCandidates(archives, cutoff);
        assertEquals("Recent archive should not be selected", 0, candidates.size());
    }

    @Test
    public void testCopyModeArchive_alreadyCopied_notSelectedAgain() {
        GregorianCalendar cutoff = new GregorianCalendar();
        cutoff.add(Calendar.DAY_OF_YEAR, -30);

        GregorianCalendar coldArchivedAt = new GregorianCalendar();
        coldArchivedAt.add(Calendar.DAY_OF_YEAR, -5);  // Copied 5 days ago

        List<Archive> archives = new ArrayList<>();
        // COPY-mode archive: ARCHIVED_LOCAL with coldArchivedAt set
        archives.add(createArchive("a1", 60, coldArchivedAt));

        List<Archive> candidates = filterCandidates(archives, cutoff);
        assertEquals("COPY-mode archive with coldArchivedAt should NOT be re-selected", 0, candidates.size());
    }

    @Test
    public void testMixedArchives_onlyUnprocessedSelected() {
        GregorianCalendar cutoff = new GregorianCalendar();
        cutoff.add(Calendar.DAY_OF_YEAR, -30);

        GregorianCalendar coldArchivedAt = new GregorianCalendar();

        List<Archive> archives = new ArrayList<>();
        archives.add(createArchive("a1", 60, null));           // Eligible: old, not yet copied
        archives.add(createArchive("a2", 60, coldArchivedAt)); // Not eligible: already copied
        archives.add(createArchive("a3", 10, null));           // Not eligible: too recent
        archives.add(createArchive("a4", 90, null));           // Eligible: old, not yet copied
        archives.add(createArchive("a5", 45, coldArchivedAt)); // Not eligible: already copied

        List<Archive> candidates = filterCandidates(archives, cutoff);
        assertEquals("Only unprocessed old archives should be selected", 2, candidates.size());
        assertEquals("a1", candidates.get(0).getId());
        assertEquals("a4", candidates.get(1).getId());
    }

    @Test
    public void testEmptyList_returnsEmpty() {
        GregorianCalendar cutoff = new GregorianCalendar();
        cutoff.add(Calendar.DAY_OF_YEAR, -30);

        List<Archive> candidates = filterCandidates(new ArrayList<>(), cutoff);
        assertTrue("Empty input should produce empty output", candidates.isEmpty());
    }

    @Test
    public void testArchiveWithNullArchivedAt_notSelected() {
        GregorianCalendar cutoff = new GregorianCalendar();
        cutoff.add(Calendar.DAY_OF_YEAR, -30);

        Archive archive = new Archive();
        archive.setId("a1");
        archive.setArchiveState(Archive.STATE_ARCHIVED_LOCAL);
        // archivedAt is null (legacy archive)

        List<Archive> archives = new ArrayList<>();
        archives.add(archive);

        List<Archive> candidates = filterCandidates(archives, cutoff);
        assertEquals("Archive with null archivedAt should not be selected", 0, candidates.size());
    }
}
