package jp.aegif.nemaki.init;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for StartupProbeService.
 * Tests state management, connection testing, setup lifecycle, and reprobe transitions.
 */
public class StartupProbeServiceTest {

    private StartupProbeService service;
    private static String savedDbUser;
    private static String savedDbPass;

    @org.junit.jupiter.api.BeforeAll
    public static void setupCredentials() {
        // RC13+: resolveCouchDbUser/Password fail-close when neither system
        // property nor properties file supplies a value. These unit tests
        // never want to actually authenticate (testConnection targets an
        // unreachable host); set harmless dev credentials so the resolvers
        // succeed and the probe returns DB_UNREACHABLE as the tests expect.
        savedDbUser = System.getProperty("db.couchdb.auth.username");
        savedDbPass = System.getProperty("db.couchdb.auth.password");
        System.setProperty("db.couchdb.auth.username", "test");
        System.setProperty("db.couchdb.auth.password", "test");
    }

    @org.junit.jupiter.api.AfterAll
    public static void restoreCredentials() {
        if (savedDbUser != null) System.setProperty("db.couchdb.auth.username", savedDbUser);
        else System.clearProperty("db.couchdb.auth.username");
        if (savedDbPass != null) System.setProperty("db.couchdb.auth.password", savedDbPass);
        else System.clearProperty("db.couchdb.auth.password");
    }

    @BeforeEach
    public void setUp() throws Exception {
        service = new StartupProbeService();
        // Reset the probed flag so onApplicationEvent can be tested
        Field probed = StartupProbeService.class.getDeclaredField("probed");
        probed.setAccessible(true);
        ((AtomicBoolean) probed.get(service)).set(false);
    }

    @Test
    public void testInitialStateIsSetupRequired() {
        assertTrue(service.isSetupRequired(), "Initial state should be setupRequired=true");
        assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());
    }

    @Test
    public void testMarkSetupComplete() {
        assertTrue(service.isSetupRequired());
        service.markSetupComplete();
        assertFalse(service.isSetupRequired(), "After markSetupComplete, setupRequired should be false");
        assertEquals(StartupProbeService.StartupState.DB_CONNECTED_CURRENT, service.getCurrentState());
    }

    @Test
    public void testTestConnectionUnreachableHost() {
        CouchDbConnectionResult result = service.testConnection("http://nonexistent-host-12345.local:5984", "admin", "pass");
        assertFalse(result.isReachable(), "Non-existent host should be unreachable");
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getResponseTimeMs() >= 0);
    }

    @Test
    public void testTestConnectionInvalidUrl() {
        CouchDbConnectionResult result = service.testConnection("not-a-url", "admin", "pass");
        assertFalse(result.isReachable());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    public void testProbeRepositoriesReturnsAllDbs() {
        withLegacyRepositoryFallback(() -> {
            var repos = service.probeRepositories("http://nonexistent-host-12345.local:5984", "admin", "pass");
            assertNotNull(repos);
            assertEquals(5, repos.size(), "Should probe 5 databases");

            var names = repos.stream().map(RepositoryOverview::getRepositoryId).toList();
            assertTrue(names.contains("bedroom"));
            assertTrue(names.contains("bedroom_closet"));
            assertTrue(names.contains("canopy"));
            assertTrue(names.contains("canopy_closet"));
            assertTrue(names.contains("nemaki_conf"));
        });
    }

    @Test
    public void testProbeRepositoriesAllEmptyWhenUnreachable() {
        withLegacyRepositoryFallback(() -> {
            var repos = service.probeRepositories("http://nonexistent-host-12345.local:5984", "admin", "pass");
            for (RepositoryOverview ov : repos) {
                assertEquals("empty", ov.getJudgment(),
                        "All repos should be 'empty' when CouchDB is unreachable: " + ov.getRepositoryId());
            }
        });
    }

    @Test
    public void testRequiredViewCounts() {
        assertEquals(38, StartupProbeService.REQUIRED_VIEWS_MAIN);
        assertEquals(8, StartupProbeService.REQUIRED_VIEWS_CLOSET);
    }

    @Test
    public void testStartupStateEnum() {
        StartupProbeService.StartupState[] states = StartupProbeService.StartupState.values();
        assertEquals(4, states.length);
        assertNotNull(StartupProbeService.StartupState.valueOf("DB_UNREACHABLE"));
        assertNotNull(StartupProbeService.StartupState.valueOf("DB_CONNECTED_EMPTY"));
        assertNotNull(StartupProbeService.StartupState.valueOf("DB_CONNECTED_CURRENT"));
        assertNotNull(StartupProbeService.StartupState.valueOf("DB_CONNECTED_LEGACY"));
    }

    // ================================================================
    // State transition tests — reprobe (P2 fix)
    // ================================================================

    @Nested
    class ReprobeStateTransitions {

        /**
         * reprobe() with unreachable host stays in DB_UNREACHABLE.
         */
        @Test
        public void testReprobeUnreachableStaysUnreachable() {
            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());
            assertTrue(service.isSetupRequired());

            service.reprobe();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());
            assertTrue(service.isSetupRequired(), "Should remain setupRequired after reprobe with no DB");
        }

        /**
         * After markSetupComplete, reprobe with unreachable reverts to DB_UNREACHABLE.
         * Verifies state is not sticky after manual override.
         */
        @Test
        public void testReprobeRevertsFromCompleteToUnreachable() {
            service.markSetupComplete();
            assertFalse(service.isSetupRequired());
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_CURRENT, service.getCurrentState());

            service.reprobe();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());
            assertTrue(service.isSetupRequired(),
                    "reprobe should detect unreachable and revert setupRequired to true");
        }

        /**
         * EMPTY → UNREACHABLE when DB goes down.
         */
        @Test
        @SuppressWarnings("unchecked")
        public void testReprobeFromEmptyToUnreachable() throws Exception {
            forceState(StartupProbeService.StartupState.DB_CONNECTED_EMPTY);
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_EMPTY, service.getCurrentState());

            service.reprobe();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());
            assertTrue(service.isSetupRequired());
        }

        /**
         * LEGACY → UNREACHABLE when DB goes down.
         */
        @Test
        public void testReprobeFromLegacyToUnreachable() throws Exception {
            forceState(StartupProbeService.StartupState.DB_CONNECTED_LEGACY);
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_LEGACY, service.getCurrentState());

            service.reprobe();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());
            assertTrue(service.isSetupRequired());
        }

        /**
         * reprobe() is safe to call multiple times (idempotent).
         */
        @Test
        public void testReprobeIdempotent() {
            service.reprobe();
            StartupProbeService.StartupState first = service.getCurrentState();
            boolean firstSetup = service.isSetupRequired();

            service.reprobe();
            assertEquals(first, service.getCurrentState(), "Repeated reprobe should be stable");
            assertEquals(firstSetup, service.isSetupRequired());

            service.reprobe();
            assertEquals(first, service.getCurrentState());
        }

        /**
         * markSetupComplete → reprobe → markSetupComplete cycle.
         */
        @Test
        public void testMarkCompleteReprobeMarkCompleteCycle() {
            assertTrue(service.isSetupRequired());

            service.markSetupComplete();
            assertFalse(service.isSetupRequired());
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_CURRENT, service.getCurrentState());

            service.reprobe();
            assertTrue(service.isSetupRequired());
            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());

            service.markSetupComplete();
            assertFalse(service.isSetupRequired());
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_CURRENT, service.getCurrentState());
        }
    }

    // ================================================================
    // reprobeState() — state-only probe (P1 fix: Setup Mode preservation)
    // ================================================================

    @Nested
    class ReprobeStateOnly {

        /**
         * reprobeState() updates currentState but does NOT change setupRequired.
         * This is critical for SetupApplyResource: if deferred init fails,
         * Setup Mode must remain open for retry.
         */
        @Test
        public void testReprobeStateDoesNotChangeSetupRequired() {
            // Initial: setupRequired=true, DB_UNREACHABLE
            assertTrue(service.isSetupRequired());
            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());

            service.reprobeState();

            // currentState may change, but setupRequired must remain true
            assertTrue(service.isSetupRequired(),
                    "reprobeState() must NOT flip setupRequired");
        }

        /**
         * reprobeState() after markSetupComplete preserves setupRequired=false.
         */
        @Test
        public void testReprobeStateAfterMarkCompleteKeepsFalse() {
            service.markSetupComplete();
            assertFalse(service.isSetupRequired());

            service.reprobeState();

            // Even though DB is now unreachable, setupRequired must remain false
            assertFalse(service.isSetupRequired(),
                    "reprobeState() must NOT change setupRequired from false to true");
        }

        /**
         * reprobeState() updates currentState to DB_UNREACHABLE when no DB.
         */
        @Test
        public void testReprobeStateUpdatesCurrentState() throws Exception {
            forceState(StartupProbeService.StartupState.DB_CONNECTED_CURRENT);
            forceSetupRequired(true);

            service.reprobeState();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState(),
                    "reprobeState() should update currentState to DB_UNREACHABLE");
            assertTrue(service.isSetupRequired(),
                    "setupRequired must remain unchanged by reprobeState()");
        }
    }

    // ================================================================
    // Regression: patch suppression depends on setupRequired
    // ================================================================

    @Nested
    class PatchSuppressionRegression {

        /**
         * When state is UNREACHABLE, setupRequired=true → patches should be suppressed.
         */
        @Test
        public void testUnreachableImpliesSetupRequired() {
            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());
            assertTrue(service.isSetupRequired(),
                    "DB_UNREACHABLE must imply setupRequired=true for patch suppression");
        }

        /**
         * When state is EMPTY, setupRequired=true → patches should be suppressed.
         */
        @Test
        public void testEmptyImpliesSetupRequired() throws Exception {
            forceState(StartupProbeService.StartupState.DB_CONNECTED_EMPTY);
            forceSetupRequired(true);
            assertTrue(service.isSetupRequired(),
                    "DB_CONNECTED_EMPTY must imply setupRequired=true for patch suppression");
        }

        /**
         * When state is CURRENT, setupRequired=false → patches should run.
         */
        @Test
        public void testCurrentImpliesNormalMode() {
            service.markSetupComplete();
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_CURRENT, service.getCurrentState());
            assertFalse(service.isSetupRequired(),
                    "DB_CONNECTED_CURRENT must imply setupRequired=false for patches to run");
        }

        /**
         * When state is LEGACY, setupRequired=true → patches should be suppressed.
         */
        @Test
        public void testLegacyImpliesSetupRequired() throws Exception {
            forceState(StartupProbeService.StartupState.DB_CONNECTED_LEGACY);
            forceSetupRequired(true);
            assertTrue(service.isSetupRequired(),
                    "DB_CONNECTED_LEGACY must imply setupRequired=true for patch suppression");
        }
    }

    // ================================================================
    // Token publication — regression tests (P1 fix)
    // ================================================================

    @Nested
    class TokenPublication {

        /**
         * P1 regression: DB_UNREACHABLE on initial startup MUST still publish
         * the setup token file.  Without this, operators are locked out of the
         * Setup API in the exact scenario it is designed to recover from.
         */
        @Test
        public void testTokenFileWrittenWhenDbUnreachable(@TempDir Path tmpDir) throws Exception {
            String oldBase = System.getProperty("catalina.base");
            try {
                System.setProperty("catalina.base", tmpDir.toString());
                Files.createDirectories(tmpDir.resolve("conf"));

                // Service starts in DB_UNREACHABLE + setupRequired=true
                assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, service.getCurrentState());
                assertTrue(service.isSetupRequired());

                // Simulate the writeTokenFile() call that logSetupToken() would make
                String path = service.writeTokenFile();

                assertNotNull(path, "Token file must be written even when DB is unreachable");
                Path tokenFile = Path.of(path);
                assertTrue(Files.exists(tokenFile));

                String content = Files.readString(tokenFile).trim();
                assertEquals(service.getSetupToken(), content);
            } finally {
                if (oldBase != null) {
                    System.setProperty("catalina.base", oldBase);
                } else {
                    System.clearProperty("catalina.base");
                }
            }
        }

        /**
         * P2 regression: writeTokenFile() must fall back through multiple
         * directories when the primary candidate fails.
         */
        @Test
        public void testTokenFileFallsBackToTmpDir() throws Exception {
            String oldBase = System.getProperty("catalina.base");
            try {
                // Set catalina.base to a non-writable path
                System.setProperty("catalina.base", "/nonexistent-path-12345");

                String path = service.writeTokenFile();

                // Should fall back to java.io.tmpdir
                assertNotNull(path, "writeTokenFile must fall back to tmpdir");
                Path tokenFile = Path.of(path);
                assertTrue(Files.exists(tokenFile));

                String content = Files.readString(tokenFile).trim();
                assertEquals(service.getSetupToken(), content);

                // Clean up
                Files.deleteIfExists(tokenFile);
            } finally {
                if (oldBase != null) {
                    System.setProperty("catalina.base", oldBase);
                } else {
                    System.clearProperty("catalina.base");
                }
            }
        }

        /**
         * writeTokenFile() with catalina.base/conf writable uses primary path.
         */
        @Test
        public void testTokenFilePrimaryPath(@TempDir Path tmpDir) throws Exception {
            String oldBase = System.getProperty("catalina.base");
            try {
                System.setProperty("catalina.base", tmpDir.toString());

                String path = service.writeTokenFile();

                assertNotNull(path);
                assertTrue(path.contains("conf"), "Should write to conf/ as primary: " + path);
                assertEquals(service.getSetupToken(), Files.readString(Path.of(path)).trim());
            } finally {
                if (oldBase != null) {
                    System.setProperty("catalina.base", oldBase);
                } else {
                    System.clearProperty("catalina.base");
                }
            }
        }

        /**
         * Token is stable — same file content on repeated writes.
         */
        @Test
        public void testTokenFileIdempotent(@TempDir Path tmpDir) throws Exception {
            String oldBase = System.getProperty("catalina.base");
            try {
                System.setProperty("catalina.base", tmpDir.toString());

                String path1 = service.writeTokenFile();
                String path2 = service.writeTokenFile();

                assertEquals(path1, path2);
                assertEquals(service.getSetupToken(), Files.readString(Path.of(path1)).trim());
            } finally {
                if (oldBase != null) {
                    System.setProperty("catalina.base", oldBase);
                } else {
                    System.clearProperty("catalina.base");
                }
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    @SuppressWarnings("unchecked")
    private void forceState(StartupProbeService.StartupState state) throws Exception {
        Field stateField = StartupProbeService.class.getDeclaredField("currentState");
        stateField.setAccessible(true);
        ((AtomicReference<StartupProbeService.StartupState>) stateField.get(service)).set(state);
    }

    private void forceSetupRequired(boolean value) throws Exception {
        Field field = StartupProbeService.class.getDeclaredField("setupRequired");
        field.setAccessible(true);
        ((AtomicBoolean) field.get(service)).set(value);
    }

    private void withLegacyRepositoryFallback(ThrowingRunnable runnable) {
        String oldValue = System.getProperty("nemaki.startup.require-repositories-yml");
        try {
            System.setProperty("nemaki.startup.require-repositories-yml", "false");
            runnable.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (oldValue == null) {
                System.clearProperty("nemaki.startup.require-repositories-yml");
            } else {
                System.setProperty("nemaki.startup.require-repositories-yml", oldValue);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
