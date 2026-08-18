package jp.aegif.nemaki.rest.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A MISSING id must mean "not found", never a crash.
 *
 * <p>Found while measuring the ingest path (2026-08-18): {@code POST /v1/repo/{repo}/ingest}
 * answered <b>HTTP 500 with a stack trace</b> when the request simply omitted
 * {@code profileId} or {@code connectorId}, while a request carrying a <i>wrong</i> id
 * answered a clean 404. The cause was {@code Map.of(...)}, which rejects null values with an
 * NPE — thrown one line before the caller's own {@code if (x == null) return error(...)}
 * branch could ever run. {@code findBySystemAndArchetype} in the same class had guarded
 * against exactly this all along; the two {@code get} methods were the outliers.
 *
 * <p>These tests reach the defect without any CouchDB: the NPE fires inside {@code Map.of}
 * before the selector query touches a client, so an unwired instance is enough. Revert either
 * guard and the corresponding test fails with NPE instead of passing.
 */
class IngestDefinitionLookupNullIdTest {

    @Test
    @DisplayName("ConnectorDefinitionServiceImpl.get(null) returns null instead of throwing")
    void connectorLookupTreatsNullIdAsNotFound() {
        ConnectorDefinitionServiceImpl service = new ConnectorDefinitionServiceImpl();

        assertNull(service.get(null),
                "a missing connectorId must resolve to not-found, so the caller can answer 400/404");
    }

    @Test
    @DisplayName("ImportProfileDefinitionServiceImpl.get(null) returns null instead of throwing")
    void profileLookupTreatsNullIdAsNotFound() {
        ImportProfileDefinitionServiceImpl service = new ImportProfileDefinitionServiceImpl();

        assertNull(service.get(null),
                "a missing profileId must resolve to not-found, so the caller can answer 400/404");
    }
}
