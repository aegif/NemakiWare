package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Verify the {@code warnIfMultiReplica()} startup probe:
 *
 * <ul>
 *   <li>silent when {@code nemakiware.deployment.singleReplica} is unset
 *       or set to {@code true} (the default operator stance);</li>
 *   <li>silent when multi-replica is declared but
 *       {@code nemakiware.deployment.stickySession=true} is set
 *       (operator-acknowledged HA configuration);</li>
 *   <li>logs a loud WARN block when multi-replica is declared without
 *       sticky session — the configuration that would silently break
 *       SAML strict mode and replay protection.</li>
 * </ul>
 */
class SamlAuthnRequestRegistryMultiReplicaWarnTest {

    private static final String SINGLE_PROP = "nemakiware.deployment.singleReplica";
    private static final String STICKY_PROP = "nemakiware.deployment.stickySession";

    private ListAppender<ILoggingEvent> appender;
    private Logger samlRegistryLogger;
    private String savedSingle;
    private String savedSticky;

    @BeforeEach
    void setUp() {
        savedSingle = System.getProperty(SINGLE_PROP);
        savedSticky = System.getProperty(STICKY_PROP);
        System.clearProperty(SINGLE_PROP);
        System.clearProperty(STICKY_PROP);

        samlRegistryLogger = (Logger) LoggerFactory.getLogger(SamlAuthnRequestRegistry.class);
        appender = new ListAppender<>();
        appender.start();
        samlRegistryLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        samlRegistryLogger.detachAppender(appender);
        appender.stop();
        if (savedSingle != null) System.setProperty(SINGLE_PROP, savedSingle); else System.clearProperty(SINGLE_PROP);
        if (savedSticky != null) System.setProperty(STICKY_PROP, savedSticky); else System.clearProperty(STICKY_PROP);
    }

    @Test
    void noWarn_whenSingleReplicaIsDefault() {
        new SamlAuthnRequestRegistry(60).clear();
        assertTrue(warnLines().isEmpty(),
                "default config (singleReplica unset) must not warn — got " + warnLines());
    }

    @Test
    void noWarn_whenSingleReplicaTrue() {
        System.setProperty(SINGLE_PROP, "true");
        new SamlAuthnRequestRegistry(60).clear();
        assertTrue(warnLines().isEmpty(), warnLines().toString());
    }

    @Test
    void noWarn_whenMultiReplicaButStickySessionDeclared() {
        System.setProperty(SINGLE_PROP, "false");
        System.setProperty(STICKY_PROP, "true");
        new SamlAuthnRequestRegistry(60).clear();
        assertTrue(warnLines().isEmpty(),
                "multi-replica + sticky session is acknowledged HA — must not warn");
    }

    @Test
    void warns_whenMultiReplicaWithoutSticky() {
        System.setProperty(SINGLE_PROP, "false");
        new SamlAuthnRequestRegistry(60).clear();
        List<String> warnings = warnLines();
        assertFalse(warnings.isEmpty(), "multi-replica without sticky must warn");
        assertTrue(warnings.stream().anyMatch(l -> l.contains("multi-replica")),
                "warning text must mention multi-replica: " + warnings);
        assertTrue(warnings.stream().anyMatch(l -> l.contains("sticky session")),
                "warning text must mention sticky session: " + warnings);
    }

    @Test
    void warns_whenMultiReplicaWithStickySessionFalse() {
        System.setProperty(SINGLE_PROP, "false");
        System.setProperty(STICKY_PROP, "false");
        new SamlAuthnRequestRegistry(60).clear();
        assertFalse(warnLines().isEmpty(),
                "explicit stickySession=false must NOT silence the warning");
    }

    private List<String> warnLines() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }
}
