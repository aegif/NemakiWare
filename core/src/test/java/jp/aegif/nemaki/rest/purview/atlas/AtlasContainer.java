package jp.aegif.nemaki.rest.purview.atlas;

import java.time.Duration;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;

/**
 * Testcontainers singleton for Apache Atlas 2.3.0.
 * The container is started once per JVM and reused across all test classes.
 * Atlas internally starts HBase, Solr, and Kafka, so startup takes 2-5 minutes.
 */
public final class AtlasContainer {

    private static final String IMAGE = "sburn/apache-atlas:2.3.0";
    private static final int PORT = 21000;
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> ATLAS = new GenericContainer<>(IMAGE)
            .withExposedPorts(PORT)
            .withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"))
            .waitingFor(new HttpWaitStrategy()
                    .forPort(PORT)
                    .forPath("/api/atlas/v2/types/typedefs/headers")
                    .forStatusCode(200)
                    .withBasicCredentials(USERNAME, PASSWORD)
                    .withStartupTimeout(Duration.ofMinutes(7)));

    static {
        ATLAS.start();
    }

    private AtlasContainer() {
    }

    public static String getEndpoint() {
        return "http://" + ATLAS.getHost() + ":" + ATLAS.getMappedPort(PORT);
    }

    public static String getBasicAuthHeader() {
        String credentials = USERNAME + ":" + PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }
}
