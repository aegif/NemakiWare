package jp.aegif.nemaki.api.v1;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;

/**
 * JUnit 5 {@link ExecutionCondition} that checks whether a NemakiWare instance is reachable.
 *
 * <p>The probe URL defaults to {@code http://localhost:8080/core/atom/bedroom} and can be
 * overridden via the {@code nemaki.test.baseUrl} system property (or the
 * {@code NEMAKI_TEST_BASE_URL} environment variable).</p>
 */
class NemakiRunningCondition implements ExecutionCondition {

    /** Cached result so we only probe once per JVM. */
    private static volatile ConditionEvaluationResult cached;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        ConditionEvaluationResult result = cached;
        if (result != null) {
            return result;
        }
        synchronized (NemakiRunningCondition.class) {
            result = cached;
            if (result != null) {
                return result;
            }
            result = probe();
            cached = result;
            return result;
        }
    }

    private static ConditionEvaluationResult probe() {
        String baseUrl = configValue("nemaki.test.baseUrl", "NEMAKI_TEST_BASE_URL", "http://localhost:8080/core");
        String user = configValue("nemaki.test.username", "NEMAKI_TEST_USERNAME", "admin");
        String pass = configValue("nemaki.test.password", "NEMAKI_TEST_PASSWORD", "admin");
        String repoId = configValue("nemaki.test.repositoryId", "NEMAKI_TEST_REPOSITORY_ID", "bedroom");
        String probeUrl = baseUrl + "/atom/" + repoId;

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(probeUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            String auth = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
            conn.setRequestProperty("Authorization", "Basic " + auth);
            int status = conn.getResponseCode();
            conn.disconnect();
            if (status == 200) {
                return ConditionEvaluationResult.enabled("NemakiWare is reachable at " + probeUrl);
            }
            return ConditionEvaluationResult.disabled(
                    "NemakiWare returned HTTP " + status + " at " + probeUrl + " — skipping integration tests");
        } catch (Exception e) {
            return ConditionEvaluationResult.disabled(
                    "NemakiWare is not reachable at " + probeUrl + " (" + e.getMessage() + ") — skipping integration tests");
        }
    }

    private static String configValue(String sysProp, String envVar, String defaultValue) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isEmpty()) {
            v = System.getenv(envVar);
        }
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }
}
