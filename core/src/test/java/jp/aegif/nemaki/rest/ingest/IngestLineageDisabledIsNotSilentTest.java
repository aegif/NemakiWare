/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Lineage is disabled" is only benign when we could actually READ that it is disabled.
 *
 * <p>The startup default for {@code lineage.mode} is {@code disabled}, and the dynamic read that
 * would override it swallows every failure and returns an empty configuration. So an unreachable
 * CouchDB produces the same answer as a deliberate switch-off — and {@code DISABLED} is the single
 * branch of the resolution that reports nothing at all, precisely because it is supposed to mean a
 * deliberate choice. An operator who believed lineage was on would get silence (external review).
 *
 * <h2>What this does NOT claim</h2>
 *
 * <p>Reporting the ambiguity does not make lineage work, and does not tell the caller which of the
 * two it was — only that the answer is not evidence of a deliberate configuration. The document is
 * committed by this point either way; that is the subject of the capture outbox, not of this test.
 */
class IngestLineageDisabledIsNotSilentTest {

    @AfterEach
    void clearContext() throws Exception {
        // The context is a static field; leaving a mock behind would leak into other tests.
        new SpringContext().setApplicationContext(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Builds a context whose LineageConfig resolves to DISABLED, readable or not as asked. */
    private static LineageConfig disabledConfig(Boolean readFailed) throws Exception {
        LineageConfig config = new LineageConfig();
        setField(config, "mode", "disabled");
        if (readFailed != null) {
            PropertyManager pm = mock(PropertyManager.class);
            Configuration conf = new Configuration();
            conf.setLoadFailed(readFailed);
            when(pm.getConfiguration(anyString())).thenReturn(conf);
            // The mode read itself must find nothing, so it falls through to the startup default —
            // which is exactly the situation being tested.
            when(pm.readValue(anyString())).thenReturn(null);
            setField(config, "propertyManager", pm);
        }
        return config;
    }

    private static Object resolve(LineageConfig config) throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(LineageConfig.class)).thenReturn(config);
        new SpringContext().setApplicationContext(ctx);

        Method m = IngestLineageEmitter.class.getDeclaredMethod("resolveEmitterReporting",
                String.class);
        m.setAccessible(true);
        return m.invoke(new IngestLineageEmitter(), "bedroom");
    }

    private static String reasonOf(Object resolution) throws Exception {
        Method m = resolution.getClass().getDeclaredMethod("failureReason");
        m.setAccessible(true);
        return (String) m.invoke(resolution);
    }

    @Test
    @DisplayName("disabled, with a configuration that was readable, stays silent")
    void deliberateDisablementIsStillBenign() throws Exception {
        // The control. Without this the change would be indistinguishable from "always complain",
        // which would bury the real signal in noise from every deployment that runs without
        // lineage on purpose.
        Object resolution = resolve(disabledConfig(false));

        assertNull(reasonOf(resolution),
                "an operator who switched lineage off must not be told anything is wrong");
    }

    @Test
    @DisplayName("disabled, because the configuration could not be read, is reported")
    void unreadableConfigurationIsNotMistakenForDisablement() throws Exception {
        Object resolution = resolve(disabledConfig(true));

        String reason = reasonOf(resolution);
        assertNotNull(reason,
                "a failed configuration read reaching the caller as plain 'disabled' is the whole "
                        + "defect: the one answer nothing reports on");
        assertTrue(reason.contains("could not be read"), reason);
        assertFalse(reason.isBlank());
    }

    @Test
    @DisplayName("no property manager is a determinate answer, not an unreadable one")
    void absentPropertyManagerIsAnAnswer() throws Exception {
        // Corrected. With no property manager the mode comes from the @Value startup default,
        // which IS the operator's deploy-time configuration — so reporting it as "we could not
        // find out" was wrong. It mattered once that verdict became a refusal: it would have
        // failed every ingest in a deployment with no dynamic configuration at all (external
        // review).
        Object resolution = resolve(disabledConfig(null));

        assertNull(reasonOf(resolution));
    }

    @Test
    @DisplayName("a configuration read that throws counts as unreadable")
    void throwingReadIsUnreadable() throws Exception {
        LineageConfig config = new LineageConfig();
        setField(config, "mode", "disabled");
        PropertyManager pm = mock(PropertyManager.class);
        when(pm.getConfiguration(anyString())).thenThrow(new RuntimeException("couchdb down"));
        when(pm.readValue(anyString())).thenReturn(null);
        setField(config, "propertyManager", pm);

        assertNotNull(reasonOf(resolve(config)));
    }

    @Test
    @DisplayName("an enabled mode is unaffected by the readability question")
    void enabledModeIsNotTouched() throws Exception {
        // The new check sits inside the DISABLED branch only; if it ever moved outward it would
        // start reporting against working deployments.
        LineageConfig config = new LineageConfig();
        setField(config, "mode", "journaled");
        PropertyManager pm = mock(PropertyManager.class);
        Configuration failed = new Configuration();
        failed.setLoadFailed(true);
        when(pm.getConfiguration(anyString())).thenReturn(failed);
        when(pm.readValue(anyString())).thenReturn(null);
        setField(config, "propertyManager", pm);

        String reason = reasonOf(resolve(config));

        assertFalse(reason != null && reason.contains("could not be read"),
                "the disabled-specific check must not fire for a mode that is on. Got: " + reason);
    }
}
