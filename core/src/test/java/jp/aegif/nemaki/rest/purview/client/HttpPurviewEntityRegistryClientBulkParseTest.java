package jp.aegif.nemaki.rest.purview.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HttpPurviewEntityRegistryClientBulkParseTest {

    private HttpPurviewEntityRegistryClient client;

    @BeforeEach
    public void setUp() {
        client = new HttpPurviewEntityRegistryClient(
                HttpClient.newHttpClient(), new PurviewTokenCache(), new PurviewHttpRetryHandler());
    }

    @Test
    public void testParseBulkResponseAllSuccess() {
        String body = """
                {
                    "mutatedEntities": {
                        "CREATE": [
                            {"guid": "g1", "typeName": "nemaki_document"},
                            {"guid": "g2", "typeName": "nemaki_folder"}
                        ],
                        "UPDATE": [
                            {"guid": "g3", "typeName": "nemaki_document"}
                        ]
                    }
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 3);

        assertTrue(result.isSuccess());
        assertFalse(result.hasFailures());
        assertEquals(3, result.getPublishedCount());
        assertEquals(0, result.getFailureCount());
        assertTrue(result.getFailedItems().isEmpty());
    }

    @Test
    public void testParseBulkResponsePartialFailure() {
        String body = """
                {
                    "mutatedEntities": {
                        "CREATE": [
                            {"guid": "g1", "typeName": "nemaki_document", "attributes": {"qualifiedName": "nemaki://bedroom/objects/doc-ok"}}
                        ]
                    },
                    "failedEntityOperations": [
                        {
                            "typeName": "nemaki_folder",
                            "attributes": {"qualifiedName": "nemaki://bedroom/objects/folder-bad"},
                            "errorMessage": "Invalid attribute value"
                        },
                        {
                            "typeName": "nemaki_document",
                            "qualifiedName": "nemaki://bedroom/objects/doc-bad",
                            "message": "Duplicate entity"
                        }
                    ]
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 3);

        assertTrue(result.isSuccess());
        assertTrue(result.hasFailures());
        assertEquals(1, result.getPublishedCount());
        assertEquals(2, result.getFailureCount());
        assertEquals(2, result.getFailedItems().size());

        PurviewEntityPublishResult.FailedItem first = result.getFailedItems().get(0);
        assertEquals("nemaki://bedroom/objects/folder-bad", first.getQualifiedName());
        assertEquals("nemaki_folder", first.getTypeName());
        assertEquals("Invalid attribute value", first.getErrorMessage());

        PurviewEntityPublishResult.FailedItem second = result.getFailedItems().get(1);
        assertEquals("nemaki://bedroom/objects/doc-bad", second.getQualifiedName());
        assertEquals("nemaki_document", second.getTypeName());
        assertEquals("Duplicate entity", second.getErrorMessage());

        // P2 fix: entityGuids must be preserved even on partial failure
        assertEquals(1, result.getEntityGuids().size());
        assertEquals("g1", result.getEntityGuids().get("nemaki://bedroom/objects/doc-ok"));
    }

    @Test
    public void testParseBulkResponseNullBody() {
        PurviewEntityPublishResult result = client.parseBulkResponse(null, 5);

        assertTrue(result.isSuccess());
        assertFalse(result.hasFailures());
        assertEquals(0, result.getPublishedCount());
    }

    @Test
    public void testParseBulkResponseEmptyBody() {
        PurviewEntityPublishResult result = client.parseBulkResponse("", 5);

        assertTrue(result.isSuccess());
        assertFalse(result.hasFailures());
        assertEquals(0, result.getPublishedCount());
    }

    @Test
    public void testParseBulkResponseMalformedJson() {
        PurviewEntityPublishResult result = client.parseBulkResponse("{not valid json", 3);

        assertFalse(result.isSuccess());
        assertEquals(0, result.getPublishedCount());
    }

    @Test
    public void testParseBulkResponseEmptyMutatedEntitiesAndEmptyFailedOps() {
        String body = """
                {
                    "mutatedEntities": {},
                    "failedEntityOperations": []
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 4);

        assertTrue(result.isSuccess());
        assertFalse(result.hasFailures());
        assertEquals(0, result.getPublishedCount());
    }

    @Test
    public void testParseBulkResponseAllFailed() {
        String body = """
                {
                    "mutatedEntities": {},
                    "failedEntityOperations": [
                        {
                            "typeName": "nemaki_document",
                            "attributes": {"qualifiedName": "nemaki://bedroom/objects/doc-1"},
                            "errorMessage": "Schema validation failed"
                        }
                    ]
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 1);

        assertTrue(result.isSuccess());
        assertTrue(result.hasFailures());
        assertEquals(0, result.getPublishedCount());
        assertEquals(1, result.getFailureCount());
        assertEquals("Schema validation failed", result.getFailedItems().get(0).getErrorMessage());
    }

    @Test
    public void testParseBulkResponseNoMutatedEntitiesKeyWithFailures() {
        String body = """
                {
                    "failedEntityOperations": [
                        {
                            "typeName": "nemaki_document",
                            "attributes": {"qualifiedName": "nemaki://bedroom/objects/doc-1"},
                            "errorMessage": "Bad request"
                        }
                    ]
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 3);

        assertTrue(result.isSuccess());
        assertTrue(result.hasFailures());
        assertEquals(0, result.getPublishedCount());
        assertEquals(1, result.getFailureCount());
    }

    @Test
    public void testPartialSuccessMessageFormat() {
        PurviewEntityPublishResult result = PurviewEntityPublishResult.partialSuccess(
                2, 1,
                java.util.List.of(new PurviewEntityPublishResult.FailedItem("qn", "type", "err")),
                "partial failure: 1 entities failed");

        assertTrue(result.isSuccess());
        assertTrue(result.hasFailures());
        assertEquals("partial failure: 1 entities failed", result.getMessage());
    }

    @Test
    public void testFailedItemsAreImmutable() {
        PurviewEntityPublishResult result = PurviewEntityPublishResult.success(1, "ok");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.getFailedItems().add(new PurviewEntityPublishResult.FailedItem("qn", "t", "e")));
    }

    @Test
    public void testParseBulkResponseExtractsEntityGuids() {
        String body = """
                {
                    "mutatedEntities": {
                        "CREATE": [
                            {"guid": "g1", "typeName": "nemaki_document", "attributes": {"qualifiedName": "nemaki://bedroom/objects/doc-001"}},
                            {"guid": "g2", "typeName": "nemaki_folder", "attributes": {"qualifiedName": "nemaki://bedroom/objects/folder-001"}}
                        ],
                        "UPDATE": [
                            {"guid": "g3", "typeName": "nemaki_document", "attributes": {"qualifiedName": "nemaki://bedroom/objects/doc-002"}}
                        ]
                    }
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 3);

        assertTrue(result.isSuccess());
        assertEquals(3, result.getPublishedCount());
        assertEquals("g1", result.getEntityGuids().get("nemaki://bedroom/objects/doc-001"));
        assertEquals("g2", result.getEntityGuids().get("nemaki://bedroom/objects/folder-001"));
        assertEquals("g3", result.getEntityGuids().get("nemaki://bedroom/objects/doc-002"));
        assertEquals(3, result.getEntityGuids().size());
    }

    @Test
    public void testParseBulkResponseSkipsEntriesWithoutAttributes() {
        // Atlas sometimes returns entries with only typeName and guid, no attributes
        String body = """
                {
                    "mutatedEntities": {
                        "CREATE": [
                            {"guid": "g1", "typeName": "nemaki_document", "attributes": {"qualifiedName": "nemaki://bedroom/objects/doc-001"}},
                            {"guid": "g2", "typeName": "nemaki_folder"}
                        ]
                    }
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 2);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getPublishedCount());
        assertEquals(1, result.getEntityGuids().size());
        assertEquals("g1", result.getEntityGuids().get("nemaki://bedroom/objects/doc-001"));
    }

    @Test
    public void testEntityGuidsEmptyWhenNoMutatedEntities() {
        String body = """
                {
                    "mutatedEntities": {}
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 0);

        assertTrue(result.isSuccess());
        assertTrue(result.getEntityGuids().isEmpty());
    }

    @Test
    public void testParseBulkResponseExtractsGuidFromTopLevelQualifiedName() {
        // Atlas 2.3 may return qualifiedName at top level instead of inside attributes
        String body = """
                {
                    "mutatedEntities": {
                        "CREATE": [
                            {"guid": "g1", "typeName": "nemaki_document", "qualifiedName": "nemaki://bedroom/objects/doc-atlas-001"},
                            {"guid": "g2", "typeName": "nemaki_folder", "qualifiedName": "nemaki://bedroom/objects/folder-atlas-001"}
                        ]
                    }
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 2);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getPublishedCount());
        assertEquals(2, result.getEntityGuids().size());
        assertEquals("g1", result.getEntityGuids().get("nemaki://bedroom/objects/doc-atlas-001"));
        assertEquals("g2", result.getEntityGuids().get("nemaki://bedroom/objects/folder-atlas-001"));
    }

    @Test
    public void testParseBulkResponseMixedAttributeAndTopLevelQualifiedName() {
        // Mixed: some entities have attributes.qualifiedName, some have top-level qualifiedName
        String body = """
                {
                    "mutatedEntities": {
                        "CREATE": [
                            {"guid": "g1", "typeName": "nemaki_document", "attributes": {"qualifiedName": "nemaki://bedroom/objects/doc-purview"}},
                            {"guid": "g2", "typeName": "nemaki_folder", "qualifiedName": "nemaki://bedroom/objects/folder-atlas"}
                        ],
                        "UPDATE": [
                            {"guid": "g3", "typeName": "nemaki_document"}
                        ]
                    }
                }
                """;
        PurviewEntityPublishResult result = client.parseBulkResponse(body, 3);

        assertTrue(result.isSuccess());
        assertEquals(3, result.getPublishedCount());
        assertEquals(2, result.getEntityGuids().size());
        assertEquals("g1", result.getEntityGuids().get("nemaki://bedroom/objects/doc-purview"));
        assertEquals("g2", result.getEntityGuids().get("nemaki://bedroom/objects/folder-atlas"));
    }

    @Test
    public void testEntityGuidsAreImmutable() {
        PurviewEntityPublishResult result = PurviewEntityPublishResult.success(1, "ok");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> result.getEntityGuids().put("qn", "guid"));
    }
}
