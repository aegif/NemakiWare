package jp.aegif.nemaki.init;

import jp.aegif.nemaki.patch.AbstractNemakiPatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pins the RC4 (R1) behaviour: the fallback patch listener auto-collects
 * every {@link AbstractNemakiPatch} bean from the live Spring context
 * rather than driving off a hardcoded array. The previous design
 * silently skipped any patch added to {@code cmisPostInitializer}'s
 * inline list but not to the listener's array, and could not reach
 * patches lacking a top-level bean id.
 *
 * <p>What we verify:
 * <ol>
 *   <li>Patches not in the {@code ORDERED_SEED_PATCHES} array are
 *       picked up via {@code getBeansOfType} and executed.</li>
 *   <li>Seed-order patches are applied first, in declared order, so
 *       the existing dependency contract (System folder first,
 *       NemakiwareStandardTypes before WebhookableSecondaryType etc.)
 *       is preserved.</li>
 *   <li>Non-seed patches are applied alphabetically afterwards for
 *       determinism.</li>
 *   <li>A patch returning {@code apply() = false} does not halt the
 *       run — subsequent patches still execute.</li>
 *   <li>The collection is robust against an empty context (no
 *       {@link NullPointerException}; the listener logs a warning and
 *       returns).</li>
 * </ol>
 */
class NemakiPatchInitializationListenerTest {

    private NemakiPatchInitializationListener listener;
    private WebApplicationContext springContext;

    @BeforeEach
    void setUp() {
        listener = new NemakiPatchInitializationListener();
        springContext = mock(WebApplicationContext.class);
    }

    /** Invoke the private applyPatchesFromSpringContext via reflection. */
    private void applyAll(Map<String, AbstractNemakiPatch> patches) throws Exception {
        when(springContext.getBeansOfType(AbstractNemakiPatch.class)).thenReturn(patches);
        Method m = NemakiPatchInitializationListener.class
                .getDeclaredMethod("applyPatchesFromSpringContext", WebApplicationContext.class);
        m.setAccessible(true);
        m.invoke(listener, springContext);
    }

    /** Track apply() invocations preserving order. */
    private static class RecordingPatch extends AbstractNemakiPatch {
        private final String name;
        private final boolean shouldReturnFailure;
        final List<String> log;

        RecordingPatch(String name, List<String> sharedLog) {
            this(name, false, sharedLog);
        }
        RecordingPatch(String name, boolean shouldReturnFailure, List<String> sharedLog) {
            this.name = name;
            this.shouldReturnFailure = shouldReturnFailure;
            this.log = sharedLog;
        }
        @Override public String getName() { return name; }
        @Override protected void applySystemPatch() { /* no-op */ }
        @Override protected void applyPerRepositoryPatch(String repositoryId) { /* no-op */ }
        @Override public boolean apply() {
            log.add(name);
            return !shouldReturnFailure;
        }
    }

    // ──────────────────────────────────────────────────────────────────

    @Test
    void emptyContext_logsAndReturns() throws Exception {
        applyAll(new LinkedHashMap<>());
        // Just verifying no exception.
    }

    @Test
    void nonSeedPatchInContext_isPickedUp() throws Exception {
        // A patch that doesn't appear in ORDERED_SEED_PATCHES. Before R1
        // this would have been invisible to the fallback listener.
        List<String> applied = new ArrayList<>();
        RecordingPatch p = new RecordingPatch("patch_IngestRelationshipTypes", applied);
        applyAll(Map.of("patch_IngestRelationshipTypes", p));
        assertEquals(List.of("patch_IngestRelationshipTypes"), applied);
    }

    @Test
    void seedOrderPreserved_thenAlphabeticalRemainder() throws Exception {
        List<String> applied = new ArrayList<>();
        // Mix: 2 ordered seeds (out of declared order in the map), 3 non-seed
        // patches (Z first in insertion order, A last) — listener must
        // reorder.
        Map<String, AbstractNemakiPatch> patches = new LinkedHashMap<>();
        patches.put("patch_zzz_Unknown", new RecordingPatch("patch_zzz_Unknown", applied));
        patches.put("patch_StandardCmisViews", new RecordingPatch("patch_StandardCmisViews", applied));
        patches.put("patch_aaa_Custom", new RecordingPatch("patch_aaa_Custom", applied));
        patches.put("patch_SystemFolderSetup", new RecordingPatch("patch_SystemFolderSetup", applied));
        patches.put("patch_mmm_Middle", new RecordingPatch("patch_mmm_Middle", applied));

        applyAll(patches);

        // Expected: seeds first in declared seed order (only the two
        // present ones), then non-seeds alphabetically.
        assertEquals(
                List.of(
                        "patch_SystemFolderSetup",   // seed [0]
                        "patch_StandardCmisViews",    // seed [2]
                        "patch_aaa_Custom",
                        "patch_mmm_Middle",
                        "patch_zzz_Unknown"
                ),
                applied,
                "seeds must run first in declared order, non-seeds alphabetically");
    }

    @Test
    void failingPatch_doesNotHaltSubsequent() throws Exception {
        List<String> applied = new ArrayList<>();
        Map<String, AbstractNemakiPatch> patches = new LinkedHashMap<>();
        patches.put("patch_SystemFolderSetup",
                new RecordingPatch("patch_SystemFolderSetup", false, applied));
        // Middle patch returns false (apply failed)
        patches.put("patch_aaa_Failing",
                new RecordingPatch("patch_aaa_Failing", true, applied));
        patches.put("patch_zzz_AfterFailure",
                new RecordingPatch("patch_zzz_AfterFailure", false, applied));

        applyAll(patches);

        // All 3 must still have been invoked
        assertEquals(
                List.of("patch_SystemFolderSetup", "patch_aaa_Failing", "patch_zzz_AfterFailure"),
                applied,
                "a failing patch must not stop subsequent patches");
    }

    @Test
    void throwingPatch_doesNotHaltSubsequent() throws Exception {
        List<String> applied = new ArrayList<>();
        AbstractNemakiPatch throwing = new AbstractNemakiPatch() {
            @Override public String getName() { return "patch_aaa_Throwing"; }
            @Override protected void applySystemPatch() { /* no-op */ }
            @Override protected void applyPerRepositoryPatch(String repositoryId) { /* no-op */ }
            @Override public boolean apply() {
                applied.add("patch_aaa_Throwing");
                throw new RuntimeException("simulated patch crash");
            }
        };
        Map<String, AbstractNemakiPatch> patches = new LinkedHashMap<>();
        patches.put("patch_SystemFolderSetup",
                new RecordingPatch("patch_SystemFolderSetup", applied));
        patches.put("patch_aaa_Throwing", throwing);
        patches.put("patch_zzz_AfterCrash",
                new RecordingPatch("patch_zzz_AfterCrash", applied));

        applyAll(patches);

        assertEquals(
                List.of("patch_SystemFolderSetup", "patch_aaa_Throwing", "patch_zzz_AfterCrash"),
                applied,
                "a throwing patch must not stop subsequent patches");
    }

    @Test
    void seedPatchAbsent_isSilentlySkipped_nonSeedsStillRun() throws Exception {
        // None of the ordered seeds are present — only one custom patch.
        // Previous behaviour: it would still try to run. Make sure that's
        // preserved (don't fail just because seeds are missing).
        List<String> applied = new ArrayList<>();
        Map<String, AbstractNemakiPatch> patches = Map.of(
                "patch_OnlyCustom", new RecordingPatch("patch_OnlyCustom", applied));
        applyAll(patches);
        assertEquals(List.of("patch_OnlyCustom"), applied);
    }
}
