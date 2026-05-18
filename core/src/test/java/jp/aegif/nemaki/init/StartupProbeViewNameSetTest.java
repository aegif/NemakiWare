package jp.aegif.nemaki.init;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the RC4 (R3) behaviour: dump-derived view name sets used by
 * {@link DatabasePreInitializer} for completeness checks must reflect
 * what the shipped {@code bedroom_init.dump} / {@code archive_init.dump}
 * actually declare. The check that used to be a count threshold
 * ({@link StartupProbeService#REQUIRED_VIEWS_MAIN}=38) drifted as new
 * views were added to the dump (now 40). This test guarantees the
 * loader reads the actual dump contents and that the resulting set
 * is a superset of the legacy threshold.
 *
 * <p>The dump files ship as classpath resources under
 * {@code /initialization/}. If a future release strips them from the
 * WAR (or moves them), {@code expectedMainViewNames()} returns the
 * empty set; the downstream check falls back to the legacy integer
 * threshold so the gate never silently passes everything.
 */
class StartupProbeViewNameSetTest {

    @Test
    void expectedMainViewNames_returnsActualDumpContents() {
        Set<String> names = StartupProbeService.expectedMainViewNames();
        assertNotNull(names, "expected name set must never be null");
        assertFalse(names.isEmpty(),
                "bedroom_init.dump should ship on the classpath; if you intentionally stripped "
                        + "it, the loader will fall back to the legacy threshold and this test "
                        + "must be updated to reflect that decision");
        assertTrue(names.size() >= StartupProbeService.REQUIRED_VIEWS_MAIN,
                "dump declares fewer views (" + names.size() + ") than the legacy threshold ("
                        + StartupProbeService.REQUIRED_VIEWS_MAIN
                        + "); the threshold has drifted ABOVE the dump — fix the dump first");
        // Spot-check a couple of well-known view names that have been
        // stable since 2.x and that downstream code relies on.
        assertTrue(names.contains("children"),
                "dump should declare the 'children' view");
        assertTrue(names.contains("relationships"),
                "dump should declare the 'relationships' view");
    }

    @Test
    void expectedClosetViewNames_returnsActualDumpContents() {
        Set<String> names = StartupProbeService.expectedClosetViewNames();
        assertNotNull(names);
        // The archive dump ships with fewer view definitions than the
        // legacy closet threshold suggests in some configurations.
        // We assert non-emptiness only — the integer threshold is the
        // backstop when the dump is sparse.
        assertFalse(names.isEmpty(),
                "archive_init.dump should ship on the classpath");
    }

    @Test
    void expectedViewNamesFromDump_returnsEmptyForMissingResource() {
        AtomicReference<Set<String>> cache = new AtomicReference<>();
        Set<String> names = StartupProbeService.expectedViewNamesFromDump(
                "/initialization/does-not-exist.dump", cache);
        assertNotNull(names);
        assertTrue(names.isEmpty(),
                "missing dump should yield an empty set so the caller can fall back");
    }

    @Test
    void expectedMainViewNames_isCached() {
        // Two consecutive calls must return the same instance — the
        // dump is read once at JVM scope; re-reading on every probe
        // would be a needless I/O cost.
        Set<String> first = StartupProbeService.expectedMainViewNames();
        Set<String> second = StartupProbeService.expectedMainViewNames();
        assertSame(first, second, "expectedMainViewNames must return the cached instance");
    }

    @Test
    void expectedMainViewNames_isImmutable() {
        Set<String> names = StartupProbeService.expectedMainViewNames();
        assertThrows(UnsupportedOperationException.class, () -> names.add("malicious"),
                "callers must not be able to mutate the cached set");
    }
}
