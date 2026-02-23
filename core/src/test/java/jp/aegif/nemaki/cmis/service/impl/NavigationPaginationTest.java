package jp.aegif.nemaki.cmis.service.impl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests for the oversampling pagination logic used in NavigationServiceImpl.getChildrenInternal().
 *
 * Since getChildrenInternal is private, these tests validate the pagination algorithm
 * extracted into a testable helper. The tests cover:
 * 1. Page boundary: no duplicate or missing items across pages
 * 2. hasMoreItems correctness when reduce is not applied (totalCount=0 fallback)
 * 3. hasMoreItems=false on the exact last page
 * 4. Admin vs non-admin permission filtering effect on pagination
 */
public class NavigationPaginationTest {

    /**
     * Simulates the oversampling pagination loop from NavigationServiceImpl.
     * This is a direct extraction of the algorithm for unit testing.
     *
     * @param allItems       All items in the folder (simulates DB)
     * @param permittedIds   Items the user is permitted to see (null = admin, sees all)
     * @param maxItems       Page size
     * @param skipCount      Items to skip (after permission filter)
     * @param totalCount     Count from _count reduce (0 = reduce not applied)
     * @return PaginationResult with items, hasMoreItems, and scan metadata
     */
    static PaginationResult simulateOversampling(
            List<String> allItems, List<String> permittedIds,
            int maxItems, int skipCount, long totalCount) {

        boolean isAdmin = (permittedIds == null);
        boolean totalCountAccurate = (totalCount > 0);

        // Fallback probe logic (mirrors NavigationServiceImpl L148-167)
        if (totalCount == 0) {
            if (allItems.isEmpty()) {
                return new PaginationResult(new ArrayList<>(), false, 0);
            }
            int FULL_FETCH_THRESHOLD = 500;
            if (allItems.size() <= FULL_FETCH_THRESHOLD) {
                totalCount = allItems.size();
            } else {
                totalCount = Long.MAX_VALUE;
            }
        }

        int oversampleFactor = isAdmin ? 1 : 3;
        int dbLimit = maxItems * oversampleFactor;
        int dbSkip = 0;
        int filteredSkipped = 0;

        List<String> pageContents = new ArrayList<>();
        long scanned = 0;
        boolean pageFilled = false;

        while (!pageFilled) {
            // Simulate getChildrenPaged(dbSkip, dbLimit)
            int end = Math.min(dbSkip + dbLimit, allItems.size());
            List<String> batch = (dbSkip < allItems.size())
                    ? allItems.subList(dbSkip, end)
                    : new ArrayList<>();
            if (batch.isEmpty()) break;

            int processedInBatch = 0;
            for (String item : batch) {
                processedInBatch++;
                if (!isAdmin && !permittedIds.contains(item)) continue;

                if (filteredSkipped < skipCount) {
                    filteredSkipped++;
                    continue;
                }

                pageContents.add(item);
                if (pageContents.size() >= maxItems) {
                    pageFilled = true;
                    break;
                }
            }

            scanned += processedInBatch;
            dbSkip += processedInBatch;

            if (batch.size() < dbLimit) break;
        }

        // hasMore logic (mirrors NavigationServiceImpl L268-279)
        boolean hasMore;
        if (pageFilled) {
            if (totalCountAccurate && scanned >= totalCount) {
                hasMore = false;
            } else {
                hasMore = true;
            }
        } else if (totalCountAccurate) {
            hasMore = scanned < totalCount;
        } else {
            hasMore = false;
        }

        return new PaginationResult(pageContents, hasMore, scanned);
    }

    static class PaginationResult {
        final List<String> items;
        final boolean hasMoreItems;
        final long scanned;

        PaginationResult(List<String> items, boolean hasMoreItems, long scanned) {
            this.items = items;
            this.hasMoreItems = hasMoreItems;
            this.scanned = scanned;
        }
    }

    private List<String> makeItems(int count) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add("item-" + i);
        }
        return items;
    }

    // ==========================================================================
    // Test 1: Page boundary — no duplicates or missing items
    // ==========================================================================

    @Test
    public void testNoDuplicatesOrMissingAcrossPages() {
        List<String> allItems = makeItems(250);
        int maxItems = 100;
        long totalCount = 250; // _count reduce applied

        List<String> allCollected = new ArrayList<>();
        int skipCount = 0;

        while (true) {
            PaginationResult result = simulateOversampling(
                    allItems, null, maxItems, skipCount, totalCount);
            allCollected.addAll(result.items);

            if (!result.hasMoreItems || result.items.isEmpty()) break;
            skipCount += result.items.size();
        }

        assertEquals("All 250 items should be collected across pages", 250, allCollected.size());

        // Check no duplicates
        long distinct = allCollected.stream().distinct().count();
        assertEquals("No duplicates across pages", 250, distinct);

        // Check order preserved
        for (int i = 0; i < 250; i++) {
            assertEquals("Item order preserved", "item-" + i, allCollected.get(i));
        }
    }

    @Test
    public void testNoDuplicatesWithPermissionFiltering() {
        // 300 items total, user can see every other item (150 permitted)
        List<String> allItems = makeItems(300);
        List<String> permitted = new ArrayList<>();
        for (int i = 0; i < 300; i += 2) {
            permitted.add("item-" + i);
        }

        int maxItems = 50;
        long totalCount = 300;

        List<String> allCollected = new ArrayList<>();
        int skipCount = 0;

        while (true) {
            PaginationResult result = simulateOversampling(
                    allItems, permitted, maxItems, skipCount, totalCount);
            allCollected.addAll(result.items);

            if (!result.hasMoreItems || result.items.isEmpty()) break;
            skipCount += result.items.size();
        }

        assertEquals("150 permitted items should be collected", 150, allCollected.size());
        long distinct = allCollected.stream().distinct().count();
        assertEquals("No duplicates in permission-filtered pages", 150, distinct);

        // Verify all collected are permitted
        for (String item : allCollected) {
            assertTrue("Only permitted items collected", permitted.contains(item));
        }
    }

    // ==========================================================================
    // Test 2: hasMoreItems correctness when reduce not applied (totalCount=0)
    // ==========================================================================

    @Test
    public void testHasMoreItemsFalseWhenReduceNotApplied_SmallFolder() {
        // Folder with 50 items, reduce not applied (totalCount=0)
        List<String> allItems = makeItems(50);
        int maxItems = 100;

        PaginationResult result = simulateOversampling(
                allItems, null, maxItems, 0, 0); // totalCount=0 = no reduce

        assertEquals("All 50 items returned", 50, result.items.size());
        assertFalse("hasMoreItems should be false for small folder without reduce", result.hasMoreItems);
    }

    @Test
    public void testHasMoreItemsTrueWhenReduceNotApplied_LargeFolder_FirstPage() {
        // Folder with 1000 items, reduce not applied
        List<String> allItems = makeItems(1000);
        int maxItems = 100;

        PaginationResult result = simulateOversampling(
                allItems, null, maxItems, 0, 0);

        assertEquals("First page has maxItems items", 100, result.items.size());
        assertTrue("hasMoreItems should be true for large folder first page", result.hasMoreItems);
    }

    @Test
    public void testHasMoreItemsTrueOnLastFullPageWithoutReduce() {
        // 200 items, no reduce, page through.
        // Without accurate totalCount, the algorithm cannot know if the last full page
        // is truly the last one, so it conservatively returns hasMore=true.
        // Only a partial page (batch.size() < dbLimit) terminates with hasMore=false.
        List<String> allItems = makeItems(200);
        int maxItems = 100;

        // Page 1
        PaginationResult page1 = simulateOversampling(allItems, null, maxItems, 0, 0);
        assertEquals(100, page1.items.size());
        assertTrue("Page 1 hasMore should be true", page1.hasMoreItems);

        // Page 2: last full page — but without reduce, can't detect it's the last one
        PaginationResult page2 = simulateOversampling(allItems, null, maxItems, 100, 0);
        assertEquals(100, page2.items.size());
        // pageFilled=true, totalCountAccurate=false → hasMore=true (conservative)
        assertTrue("Without reduce, last full page conservatively has hasMore=true", page2.hasMoreItems);

        // Page 3: beyond data — now returns empty, hasMore=false
        PaginationResult page3 = simulateOversampling(allItems, null, maxItems, 200, 0);
        assertEquals(0, page3.items.size());
        assertFalse("Beyond data should have hasMore=false", page3.hasMoreItems);
    }

    // ==========================================================================
    // Test 3: hasMoreItems=false on the exact last full page
    // ==========================================================================

    @Test
    public void testHasMoreItemsFalseOnExactLastFullPageWithReduce() {
        // Exactly 200 items, maxItems=100, totalCount=200 (reduce applied)
        List<String> allItems = makeItems(200);
        int maxItems = 100;
        long totalCount = 200;

        // Page 1: skip=0
        PaginationResult page1 = simulateOversampling(allItems, null, maxItems, 0, totalCount);
        assertEquals(100, page1.items.size());
        assertTrue("Page 1 should have more", page1.hasMoreItems);

        // Page 2: skip=100
        PaginationResult page2 = simulateOversampling(allItems, null, maxItems, 100, totalCount);
        assertEquals(100, page2.items.size());
        // scanned=200 >= totalCount=200 and pageFilled=true → hasMore=false
        assertFalse("Exact last full page should have hasMore=false", page2.hasMoreItems);
    }

    @Test
    public void testHasMoreItemsFalseWhenFewerItemsThanMaxItems() {
        // 50 items, maxItems=100, totalCount=50
        List<String> allItems = makeItems(50);

        PaginationResult result = simulateOversampling(allItems, null, 100, 0, 50);

        assertEquals(50, result.items.size());
        assertFalse("Fewer items than maxItems → hasMore=false", result.hasMoreItems);
    }

    // ==========================================================================
    // Test 4: Empty folder
    // ==========================================================================

    @Test
    public void testEmptyFolder() {
        PaginationResult result = simulateOversampling(
                new ArrayList<>(), null, 100, 0, 0);
        assertTrue("Empty folder returns empty list", result.items.isEmpty());
        assertFalse("Empty folder hasMore=false", result.hasMoreItems);
    }

    @Test
    public void testEmptyFolderWithReduceApplied() {
        // reduce says 0, but we still test via the normal path
        PaginationResult result = simulateOversampling(
                new ArrayList<>(), null, 100, 0, 0);
        assertTrue(result.items.isEmpty());
        assertFalse(result.hasMoreItems);
    }

    // ==========================================================================
    // Test 5: skipCount edge cases
    // ==========================================================================

    @Test
    public void testSkipCountBeyondAvailable() {
        List<String> allItems = makeItems(50);

        PaginationResult result = simulateOversampling(allItems, null, 100, 100, 50);

        assertTrue("Skip beyond available items → empty", result.items.isEmpty());
        assertFalse(result.hasMoreItems);
    }

    @Test
    public void testSkipCountExactly() {
        List<String> allItems = makeItems(150);

        // Skip 100, maxItems=100 → should get 50 items
        PaginationResult result = simulateOversampling(allItems, null, 100, 100, 150);

        assertEquals(50, result.items.size());
        assertEquals("item-100", result.items.get(0));
        assertFalse(result.hasMoreItems);
    }
}
