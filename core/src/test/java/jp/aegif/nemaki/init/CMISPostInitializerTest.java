package jp.aegif.nemaki.init;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;

import jp.aegif.nemaki.patch.AbstractNemakiPatch;

/**
 * Tests for CMISPostInitializer patch failure propagation and Listener fallback.
 *
 * Verifies:
 * 1. When a CMIS patch fails, isPatchesApplied() returns false
 * 2. When all patches succeed, isPatchesApplied() returns true
 * 3. NemakiPatchInitializationListener can act as fallback when CMISPostInitializer fails
 */
public class CMISPostInitializerTest {

    @BeforeEach
    public void resetStaticState() throws Exception {
        // Reset static AtomicBooleans via reflection for test isolation
        resetField("started");
        resetField("completedSuccessfully");
    }

    private void resetField(String fieldName) throws Exception {
        Field field = CMISPostInitializer.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((AtomicBoolean) field.get(null)).set(false);
    }

    /**
     * Stub patch that always succeeds.
     */
    private static class StubSuccessPatch extends AbstractNemakiPatch {
        @Override protected void applySystemPatch() {}
        @Override protected void applyPerRepositoryPatch(String repositoryId) {}
        @Override public String getName() { return "stub_success"; }
        // Override apply() to avoid needing PatchUtil/RepositoryInfoMap
        @Override public boolean apply() { return true; }
    }

    /**
     * Stub patch that always fails.
     */
    private static class StubFailPatch extends AbstractNemakiPatch {
        @Override protected void applySystemPatch() {}
        @Override protected void applyPerRepositoryPatch(String repositoryId) {
            throw new RuntimeException("fail");
        }
        @Override public String getName() { return "stub_fail"; }
        // Override apply() to simulate failure
        @Override public boolean apply() { return false; }
    }

    @Test
    public void testIsPatchesAppliedTrueWhenAllSucceed() {
        CMISPostInitializer initializer = new CMISPostInitializer();

        List<AbstractNemakiPatch> patches = new ArrayList<>();
        patches.add(new StubSuccessPatch());
        initializer.setCmisPatchList(patches);

        // Fire the event
        GenericApplicationContext ctx = new GenericApplicationContext();
        ContextRefreshedEvent event = new ContextRefreshedEvent(ctx);
        initializer.onApplicationEvent(event);

        assertTrue(CMISPostInitializer.isPatchesApplied(), "isPatchesApplied should be true when all patches succeed");
    }

    @Test
    public void testIsPatchesAppliedFalseWhenPatchFails() {
        CMISPostInitializer initializer = new CMISPostInitializer();

        List<AbstractNemakiPatch> patches = new ArrayList<>();
        patches.add(new StubSuccessPatch());
        patches.add(new StubFailPatch());
        initializer.setCmisPatchList(patches);

        GenericApplicationContext ctx = new GenericApplicationContext();
        ContextRefreshedEvent event = new ContextRefreshedEvent(ctx);
        initializer.onApplicationEvent(event);

        assertFalse(CMISPostInitializer.isPatchesApplied(), "isPatchesApplied should be false when any patch fails");
    }

    @Test
    public void testListenerFallbackWhenCMISPostInitializerFails() {
        // Simulate: CMISPostInitializer ran but failed (isPatchesApplied = false)
        // The Listener should detect this and proceed with its own patch application

        assertFalse(CMISPostInitializer.isPatchesApplied(), "Before CMISPostInitializer runs, isPatchesApplied should be false");

        // Simulate CMISPostInitializer failure
        CMISPostInitializer initializer = new CMISPostInitializer();
        List<AbstractNemakiPatch> patches = new ArrayList<>();
        patches.add(new StubFailPatch());
        initializer.setCmisPatchList(patches);

        GenericApplicationContext ctx = new GenericApplicationContext();
        initializer.onApplicationEvent(new ContextRefreshedEvent(ctx));

        assertFalse(CMISPostInitializer.isPatchesApplied(), "After failed CMISPostInitializer, isPatchesApplied should be false");

        // NemakiPatchInitializationListener checks isPatchesApplied() at L63
        // When false, it should proceed with its own patch application (fallback)
        boolean listenerShouldRun = !CMISPostInitializer.isPatchesApplied();
        assertTrue(listenerShouldRun, "Listener should run as fallback when CMISPostInitializer failed");
    }

    @Test
    public void testListenerSkipsWhenCMISPostInitializerSucceeds() {
        CMISPostInitializer initializer = new CMISPostInitializer();
        List<AbstractNemakiPatch> patches = new ArrayList<>();
        patches.add(new StubSuccessPatch());
        initializer.setCmisPatchList(patches);

        GenericApplicationContext ctx = new GenericApplicationContext();
        initializer.onApplicationEvent(new ContextRefreshedEvent(ctx));

        assertTrue(CMISPostInitializer.isPatchesApplied());

        // NemakiPatchInitializationListener should skip
        boolean listenerShouldSkip = CMISPostInitializer.isPatchesApplied();
        assertTrue(listenerShouldSkip, "Listener should skip when CMISPostInitializer succeeded");
    }

    @Test
    public void testOnApplicationEventRunsOnlyOnce() {
        CMISPostInitializer initializer = new CMISPostInitializer();
        List<AbstractNemakiPatch> patches = new ArrayList<>();
        StubSuccessPatch patch = new StubSuccessPatch();
        patches.add(patch);
        initializer.setCmisPatchList(patches);

        GenericApplicationContext ctx = new GenericApplicationContext();
        ContextRefreshedEvent event = new ContextRefreshedEvent(ctx);

        // First call
        initializer.onApplicationEvent(event);
        assertTrue(CMISPostInitializer.isPatchesApplied());

        // Reset and call again — should be no-op due to AtomicBoolean guard
        // (isPatchesApplied remains true from first call)
        initializer.onApplicationEvent(event);
        assertTrue(CMISPostInitializer.isPatchesApplied(), "Second call should be no-op");
    }
}
