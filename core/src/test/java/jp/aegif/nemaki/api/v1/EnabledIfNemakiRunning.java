package jp.aegif.nemaki.api.v1;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Conditionally enables a test class/method only when a NemakiWare instance is reachable.
 *
 * <p>The check performs an HTTP GET to the health endpoint
 * (default: {@code http://localhost:8080/core/atom/bedroom}) using Basic auth.
 * If the server responds with HTTP 200, the test runs; otherwise it is skipped
 * with a descriptive message.</p>
 *
 * <p>The target URL, username and password are read from the same system‐property /
 * environment‐variable pairs used by {@link ApiV1TestBase}.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(NemakiRunningCondition.class)
public @interface EnabledIfNemakiRunning {
}
