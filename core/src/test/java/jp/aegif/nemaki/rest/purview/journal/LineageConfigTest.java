package jp.aegif.nemaki.rest.purview.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.util.PropertyManager;

public class LineageConfigTest {

    @Test
    public void testDefaultModeIsDisabled() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertEquals(LineageMode.DISABLED, config.getMode());
    }

    @Test
    public void testDynamicModeOverride() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.mode")).thenReturn("journaled");
        setField(config, "propertyManager", pm);

        assertEquals(LineageMode.JOURNALED, config.getMode());
    }

    @Test
    public void testInvalidModeFallsBackToDisabled() throws Exception {
        LineageConfig config = newConfig("INVALID_VALUE", "", 90, false, false, "");
        assertEquals(LineageMode.DISABLED, config.getMode());
    }

    @Test
    public void testNullModeFallsBackToDisabled() throws Exception {
        LineageConfig config = newConfig(null, "", 90, false, false, "");
        assertEquals(LineageMode.DISABLED, config.getMode());
    }

    @Test
    public void testTargetsParsing() throws Exception {
        LineageConfig config = newConfig("journaled", "purview, atlas , dataplex", 90, false, false, "");
        List<String> targets = config.getTargets();
        assertEquals(3, targets.size());
        assertEquals("purview", targets.get(0));
        assertEquals("atlas", targets.get(1));
        assertEquals("dataplex", targets.get(2));
    }

    @Test
    public void testEmptyTargetsReturnsEmptyList() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertTrue(config.getTargets().isEmpty());
    }

    @Test
    public void testRetentionDaysDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertEquals(90, config.getRetentionDays());
    }

    @Test
    public void testRetentionDaysDynamicOverride() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.retention.days")).thenReturn("30");
        setField(config, "propertyManager", pm);

        assertEquals(30, config.getRetentionDays());
    }

    @Test
    public void testCaptureVersionEventsDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertFalse(config.isCaptureVersionEvents());
    }

    @Test
    public void testCaptureGenericRelationshipsDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertFalse(config.isCaptureGenericRelationships());
    }

    @Test
    public void testPurgeCronDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertEquals("", config.getPurgeCron());
    }

    @Test
    public void testPurgeCronDynamic() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "0 0 4 * * ?");
        assertEquals("0 0 4 * * ?", config.getPurgeCron());
    }

    @Test
    public void testGetModeForRepositoryUsesGlobalDefault() throws Exception {
        LineageConfig config = newConfig("journaled", "", 90, false, false, "");
        assertEquals(LineageMode.JOURNALED, config.getModeForRepository("bedroom"));
    }

    @Test
    public void testGetModeForRepositoryUsesOverride() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.mode.override.bedroom")).thenReturn("journaled");
        // Global mode not overridden
        when(pm.readValue("lineage.mode")).thenReturn(null);
        setField(config, "propertyManager", pm);

        assertEquals(LineageMode.JOURNALED, config.getModeForRepository("bedroom"));
        assertEquals(LineageMode.DISABLED, config.getModeForRepository("canopy"));
    }

    @Test
    public void testGetModeForRepositoryNullRepoFallsBackToGlobal() throws Exception {
        LineageConfig config = newConfig("direct", "", 90, false, false, "");
        assertEquals(LineageMode.DIRECT, config.getModeForRepository(null));
        assertEquals(LineageMode.DIRECT, config.getModeForRepository(""));
    }

    @Test
    public void testPriority1RepoOverrideBeatsPriority2Global() throws Exception {
        // startup default = disabled, global dynamic = direct, repo override = journaled
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.mode")).thenReturn("direct");                    // Priority 2
        when(pm.readValue("lineage.mode.override.bedroom")).thenReturn("journaled"); // Priority 1
        setField(config, "propertyManager", pm);

        // Priority 1 wins for bedroom
        assertEquals(LineageMode.JOURNALED, config.getModeForRepository("bedroom"));
        // Priority 2 wins for canopy (no override)
        assertEquals(LineageMode.DIRECT, config.getModeForRepository("canopy"));
    }

    @Test
    public void testPriority2GlobalBeatsPriority3Startup() throws Exception {
        // startup default = disabled, global dynamic = journaled
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.mode")).thenReturn("journaled"); // Priority 2
        setField(config, "propertyManager", pm);

        // Priority 2 wins (no repo override)
        assertEquals(LineageMode.JOURNALED, config.getModeForRepository("bedroom"));
    }

    @Test
    public void testPriority3StartupUsedWhenNoDynamic() throws Exception {
        // startup default = direct, no dynamic overrides
        LineageConfig config = newConfig("direct", "", 90, false, false, "");
        // No PropertyManager set → Priority 3 (startup default)
        assertEquals(LineageMode.DIRECT, config.getModeForRepository("bedroom"));
    }

    @Test
    public void testDynamicChangeTakesEffectImmediately() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        PropertyManager pm = mock(PropertyManager.class);
        setField(config, "propertyManager", pm);

        // Initially returns null → startup default
        when(pm.readValue("lineage.mode")).thenReturn(null);
        assertEquals(LineageMode.DISABLED, config.getMode());

        // Simulate config change (no restart)
        when(pm.readValue("lineage.mode")).thenReturn("journaled");
        assertEquals(LineageMode.JOURNALED, config.getMode());

        // Change again
        when(pm.readValue("lineage.mode")).thenReturn("direct");
        assertEquals(LineageMode.DIRECT, config.getMode());
    }

    // --- Backlog management tests ---

    @Test
    public void testBacklogMaxRetryCountDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertEquals(5, config.getBacklogMaxRetryCount());
    }

    @Test
    public void testBacklogMaxRetryAgeHoursDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertEquals(72, config.getBacklogMaxRetryAgeHours());
    }

    @Test
    public void testBacklogMaxDocsDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertEquals(10000, config.getBacklogMaxDocs());
    }

    @Test
    public void testBacklogMaxSizeMbDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");
        assertEquals(100, config.getBacklogMaxSizeMb());
    }

    @Test
    public void testBacklogMaxRetryCountDynamicOverride() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.backlog.max-retry-count")).thenReturn("10");
        setField(config, "propertyManager", pm);

        assertEquals(10, config.getBacklogMaxRetryCount());
    }

    @Test
    public void testBacklogMaxRetryAgeHoursDynamicOverride() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.backlog.max-retry-age-hours")).thenReturn("24");
        setField(config, "propertyManager", pm);

        assertEquals(24, config.getBacklogMaxRetryAgeHours());
    }

    @Test
    public void testBacklogZeroDisablesThreshold() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.backlog.max-retry-count")).thenReturn("0");
        when(pm.readValue("lineage.backlog.max-docs")).thenReturn("0");
        setField(config, "propertyManager", pm);

        assertEquals(0, config.getBacklogMaxRetryCount());
        assertEquals(0, config.getBacklogMaxDocs());
    }

    @Test
    public void testBacklogInvalidIntFallsBackToDefault() throws Exception {
        LineageConfig config = newConfig("disabled", "", 90, false, false, "");

        PropertyManager pm = mock(PropertyManager.class);
        when(pm.readValue("lineage.backlog.max-retry-count")).thenReturn("not_a_number");
        setField(config, "propertyManager", pm);

        // Falls back to startup default (5)
        assertEquals(5, config.getBacklogMaxRetryCount());
    }

    private LineageConfig newConfig(String mode, String targets, int retentionDays,
                                     boolean captureVersionEvents, boolean captureGenericRelationships,
                                     String purgeCron) throws Exception {
        LineageConfig config = new LineageConfig();
        setField(config, "mode", mode != null ? mode : "disabled");
        setField(config, "targets", targets);
        setField(config, "retentionDays", retentionDays);
        setField(config, "captureVersionEvents", captureVersionEvents);
        setField(config, "captureGenericRelationships", captureGenericRelationships);
        setField(config, "purgeCron", purgeCron);
        setField(config, "snapshotMaxNameLength", 512);
        setField(config, "snapshotMaxPathLength", 2048);
        setField(config, "snapshotCapturePath", true);
        setField(config, "backlogMaxRetryCount", 5);
        setField(config, "backlogMaxRetryAgeHours", 72);
        setField(config, "backlogMaxDocs", 10000);
        setField(config, "backlogMaxSizeMb", 100);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
