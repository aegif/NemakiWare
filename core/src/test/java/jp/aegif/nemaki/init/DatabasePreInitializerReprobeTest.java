package jp.aegif.nemaki.init;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Integration-level tests for:
 *   - DatabasePreInitializer → StartupProbeService.reprobe() delegation
 *   - reprobe state transitions (UNREACHABLE, EMPTY, CURRENT, LEGACY)
 *   - Setup token generation, masking, file write, and re-announcement on reprobe
 */
public class DatabasePreInitializerReprobeTest {

    private DatabasePreInitializer initializer;
    private StartupProbeService probeService;
    private ContextRefreshedEvent event;
    private ApplicationContext applicationContext;
    private static String savedDbUser;
    private static String savedDbPass;

    @org.junit.jupiter.api.BeforeAll
    public static void setupCredentials() {
        // RC13+: DatabasePreInitializer's constructor (and the inner
        // resolver paths) require non-empty CouchDB credentials. These
        // tests never actually authenticate (mocks / unreachable hosts);
        // set harmless dev values so the constructor succeeds.
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
        initializer = new DatabasePreInitializer();
        probeService = mock(StartupProbeService.class);

        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.getBeanFactory().registerSingleton("startupProbeService", probeService);
        ctx.refresh();

        applicationContext = ctx;
        event = new ContextRefreshedEvent(ctx);
    }

    // ================================================================
    // reprobeStartupState() delegation
    // ================================================================

    @Test
    public void testReprobeStartupStateDelegatesToProbeService() throws Exception {
        Method reprobe = DatabasePreInitializer.class.getDeclaredMethod("reprobeStartupState", ContextRefreshedEvent.class);
        reprobe.setAccessible(true);
        reprobe.invoke(initializer, event);

        verify(probeService, times(1)).reprobe();
    }

    @Test
    public void testReprobeStartupStateHandlesNullProbeService() throws Exception {
        GenericApplicationContext emptyCtx = new GenericApplicationContext();
        emptyCtx.refresh();
        ContextRefreshedEvent emptyEvent = new ContextRefreshedEvent(emptyCtx);

        Method reprobe = DatabasePreInitializer.class.getDeclaredMethod("reprobeStartupState", ContextRefreshedEvent.class);
        reprobe.setAccessible(true);

        // Should not throw
        reprobe.invoke(initializer, emptyEvent);

        verify(probeService, never()).reprobe();
    }

    @Test
    public void testReprobeStartupStateHandlesProbeException() throws Exception {
        doThrow(new RuntimeException("probe failed")).when(probeService).reprobe();

        Method reprobe = DatabasePreInitializer.class.getDeclaredMethod("reprobeStartupState", ContextRefreshedEvent.class);
        reprobe.setAccessible(true);

        // Should not throw
        reprobe.invoke(initializer, event);
    }

    // ================================================================
    // Full StartupProbeService reprobe state transitions
    // ================================================================

    @Nested
    class RealProbeServiceReprobeTransitions {

        private StartupProbeService realProbeService;

        @BeforeEach
        public void setUp() throws Exception {
            realProbeService = new StartupProbeService();
            Field probed = StartupProbeService.class.getDeclaredField("probed");
            probed.setAccessible(true);
            ((AtomicBoolean) probed.get(realProbeService)).set(false);
        }

        @Test
        public void testReprobeFromUnreachableStaysUnreachable() {
            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, realProbeService.getCurrentState());
            assertTrue(realProbeService.isSetupRequired());

            realProbeService.reprobe();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, realProbeService.getCurrentState());
            assertTrue(realProbeService.isSetupRequired());
        }

        @Test
        @SuppressWarnings("unchecked")
        public void testReprobeFromEmptyToUnreachable() throws Exception {
            forceState(realProbeService, StartupProbeService.StartupState.DB_CONNECTED_EMPTY);
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_EMPTY, realProbeService.getCurrentState());

            realProbeService.reprobe();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, realProbeService.getCurrentState());
            assertTrue(realProbeService.isSetupRequired());
        }

        @Test
        public void testReprobeFromCurrentToUnreachable() throws Exception {
            realProbeService.markSetupComplete();
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_CURRENT, realProbeService.getCurrentState());
            assertFalse(realProbeService.isSetupRequired());

            realProbeService.reprobe();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, realProbeService.getCurrentState());
            assertTrue(realProbeService.isSetupRequired());
        }

        @Test
        @SuppressWarnings("unchecked")
        public void testReprobeFromLegacyToUnreachable() throws Exception {
            forceState(realProbeService, StartupProbeService.StartupState.DB_CONNECTED_LEGACY);
            assertEquals(StartupProbeService.StartupState.DB_CONNECTED_LEGACY, realProbeService.getCurrentState());

            realProbeService.reprobe();

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, realProbeService.getCurrentState());
            assertTrue(realProbeService.isSetupRequired());
        }

        @Test
        public void testReprobeViaDatabasePreInitializerEvent() throws Exception {
            GenericApplicationContext ctx = new GenericApplicationContext();
            ctx.getBeanFactory().registerSingleton("startupProbeService", realProbeService);
            ctx.refresh();
            ContextRefreshedEvent evt = new ContextRefreshedEvent(ctx);

            realProbeService.markSetupComplete();
            assertFalse(realProbeService.isSetupRequired());

            Method reprobe = DatabasePreInitializer.class.getDeclaredMethod(
                    "reprobeStartupState", ContextRefreshedEvent.class);
            reprobe.setAccessible(true);
            reprobe.invoke(initializer, evt);

            assertEquals(StartupProbeService.StartupState.DB_UNREACHABLE, realProbeService.getCurrentState());
            assertTrue(realProbeService.isSetupRequired());
        }

        /**
         * Verify that reprobe from Normal→Setup re-announces the token
         * by checking the token file is written.
         */
        @Test
        public void testReprobeFromNormalToSetupReAnnouncesToken(@TempDir Path tmpDir) throws Exception {
            // Point catalina.base to temp dir for token file
            String oldBase = System.getProperty("catalina.base");
            try {
                System.setProperty("catalina.base", tmpDir.toString());
                Files.createDirectories(tmpDir.resolve("conf"));

                realProbeService.markSetupComplete();
                assertFalse(realProbeService.isSetupRequired());

                // reprobe — no CouchDB → will flip to UNREACHABLE (setupRequired=true)
                realProbeService.reprobe();

                assertTrue(realProbeService.isSetupRequired(),
                        "Should have reverted to setupRequired after DB went away");

                // Token file should be written during re-announcement
                Path tokenFile = tmpDir.resolve("conf").resolve("setup-token");
                assertTrue(Files.exists(tokenFile),
                        "Token file should be written when entering Setup Mode via reprobe");

                String fileContent = Files.readString(tokenFile).trim();
                assertEquals(realProbeService.getSetupToken(), fileContent,
                        "Token file should contain the full token");
            } finally {
                if (oldBase != null) {
                    System.setProperty("catalina.base", oldBase);
                } else {
                    System.clearProperty("catalina.base");
                }
            }
        }

        /**
         * Verify that reprobe staying in Setup Mode does NOT re-announce
         * (wasSetupRequired == true → no duplicate file write).
         */
        @Test
        public void testReprobeStayingInSetupDoesNotReAnnounce(@TempDir Path tmpDir) throws Exception {
            String oldBase = System.getProperty("catalina.base");
            try {
                System.setProperty("catalina.base", tmpDir.toString());
                Files.createDirectories(tmpDir.resolve("conf"));

                // Already in Setup Mode
                assertTrue(realProbeService.isSetupRequired());

                // Write a marker to detect if the file is overwritten
                Path tokenFile = tmpDir.resolve("conf").resolve("setup-token");
                Files.writeString(tokenFile, "marker");

                realProbeService.reprobe();

                // Should still be in Setup Mode
                assertTrue(realProbeService.isSetupRequired());

                // Token file should not be re-written (no transition occurred)
                String content = Files.readString(tokenFile).trim();
                assertEquals("marker", content,
                        "Token file should not be overwritten when reprobe stays in Setup Mode");
            } finally {
                if (oldBase != null) {
                    System.setProperty("catalina.base", oldBase);
                } else {
                    System.clearProperty("catalina.base");
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void forceState(StartupProbeService svc, StartupProbeService.StartupState state) throws Exception {
            Field stateField = StartupProbeService.class.getDeclaredField("currentState");
            stateField.setAccessible(true);
            ((AtomicReference<StartupProbeService.StartupState>) stateField.get(svc)).set(state);
        }
    }

    // ================================================================
    // Setup token generation & security
    // ================================================================

    @Nested
    class SetupTokenSecurity {

        @Test
        public void testSetupTokenIsGenerated() {
            StartupProbeService svc = new StartupProbeService();
            String token = svc.getSetupToken();
            assertNotNull(token, "Setup token should be generated at construction time");
            assertFalse(token.isEmpty());
            assertTrue(token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                    "Token should be UUID format: " + token);
        }

        @Test
        public void testSetupTokenIsStablePerInstance() {
            StartupProbeService svc = new StartupProbeService();
            assertEquals(svc.getSetupToken(), svc.getSetupToken(),
                    "Token should be the same on repeated calls");
        }

        @Test
        public void testSetupTokenIsDifferentPerInstance() {
            StartupProbeService svc1 = new StartupProbeService();
            StartupProbeService svc2 = new StartupProbeService();
            assertNotEquals(svc1.getSetupToken(), svc2.getSetupToken(),
                    "Different instances should have different tokens");
        }

        @Test
        public void testTokenFileContainsFullToken(@TempDir Path tmpDir) throws Exception {
            String oldBase = System.getProperty("catalina.base");
            try {
                System.setProperty("catalina.base", tmpDir.toString());
                Files.createDirectories(tmpDir.resolve("conf"));

                StartupProbeService svc = new StartupProbeService();
                String path = svc.writeTokenFile();

                assertNotNull(path, "writeTokenFile should return file path");
                Path tokenFile = Path.of(path);
                assertTrue(Files.exists(tokenFile));

                String content = Files.readString(tokenFile).trim();
                assertEquals(svc.getSetupToken(), content,
                        "Token file must contain the exact full token");
            } finally {
                if (oldBase != null) {
                    System.setProperty("catalina.base", oldBase);
                } else {
                    System.clearProperty("catalina.base");
                }
            }
        }

        @Test
        public void testTokenFileWritesFallbackToTmpDir() throws Exception {
            String oldBase = System.getProperty("catalina.base");
            try {
                System.clearProperty("catalina.base");

                StartupProbeService svc = new StartupProbeService();
                String path = svc.writeTokenFile();

                assertNotNull(path, "writeTokenFile should fall back to java.io.tmpdir");
                Path tokenFile = Path.of(path);
                assertTrue(Files.exists(tokenFile));

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
    }
}
