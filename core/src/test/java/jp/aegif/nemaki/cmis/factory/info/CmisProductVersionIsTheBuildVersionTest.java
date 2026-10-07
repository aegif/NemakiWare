package jp.aegif.nemaki.cmis.factory.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jp.aegif.nemaki.rest.BuildInfoResource;
import jp.aegif.nemaki.util.SpringPropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * CMIS {@code productVersion} is the version this build was made from, not a literal in a
 * repositories file — and a {@code product.version} setting is reported as ignored.
 *
 * <p>It used to be {@code product.version} in {@code repositories-default.yml}: written as 3.3.0
 * for the 3.3.0 release and never bumped, so 3.3.1 and 3.4.0 both told every CMIS client
 * (repositoryInfo, and the REST repository endpoint that copies it) that they were 3.3.0. Nothing
 * checked the literal against the build.
 *
 * <p>The fixture sets {@code product.version} in the default block — to the build's own version,
 * filtered in by Maven — and in one repository (8.8.8). A reading that honours the per-repository
 * value fails on {@code canopy}. The build's own version is the one value the first version of the
 * WARN stayed silent for (an empty YAML value reaches this code as the string "null", which it
 * already warned about), so it comes first and the WARN is counted from the log itself — a flag
 * beside the log would stay true with the log line gone.
 */
class CmisProductVersionIsTheBuildVersionTest {

    @Test
    @DisplayName("every repository reports the build's version, and the ignored setting is warned about once")
    void productVersionIsTheBuildVersion() throws IOException {
        String built = BuildInfoResource.getVersion();
        assertTrue(built.matches("\\d+\\.\\d+\\.\\d+.*"),
                "version.properties was not filtered on this classpath (got '" + built + "'), so "
                        + "this test cannot tell the build's version from a fallback");

        // The premise: the default block really carries the build's version. If the filtering ever
        // stops, the literal "${project.version}" would be warned about by any condition, and the
        // WARN below would stop telling the conditions apart.
        try (InputStream fixture = getClass().getClassLoader()
                .getResourceAsStream("repository-info/repositories-default.yml")) {
            assertNotNull(fixture, "the fixture is not on the test classpath");
            String text = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("product.version: " + built + "\n"),
                    "the fixture's default block does not carry the build's version " + built
                            + " — testResources filtering did not run:\n" + text);
        }

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(RepositoryInfoMap.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SpringPropertyManager pm = mock(SpringPropertyManager.class);
            when(pm.readValue(PropertyKey.REPOSITORY_DEFINITION_DEFAULT))
                    .thenReturn("repository-info/repositories-default.yml");
            when(pm.readValue(PropertyKey.REPOSITORY_DEFINITION))
                    .thenReturn("repository-info/repositories.yml");
            RepositoryInfoMap map = new RepositoryInfoMap();
            map.setPropertyManager(pm);
            map.init();

            for (String id : new String[] {"bedroom", "canopy"}) {
                RepositoryInfo info = map.get(id);
                assertNotNull(info, id + " was not loaded from the fixture");
                assertEquals(built, info.getProductVersion(),
                        id + " reports productVersion '" + info.getProductVersion() + "' but this build is "
                                + built);
            }

            // Ignored, but not silently: the release notes promise one startup WARN whenever the
            // setting is present, whatever its value, so an operator can find the dead line.
            List<String> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("product.version"))
                    .toList();
            assertEquals(1, warnings.size(),
                    "expected one startup WARN that product.version is not read, got: " + warnings);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
