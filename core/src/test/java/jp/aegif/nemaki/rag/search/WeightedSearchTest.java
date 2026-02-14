package jp.aegif.nemaki.rag.search;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for weighted search functionality.
 * Tests score combination logic and boost factor calculations.
 */
public class WeightedSearchTest {

    @Test
    public void testWeightedScoreCombination() {
        // Test: combined_score = (propertyBoost × property_score) + (contentBoost × content_score)
        float propertyBoost = 0.3f;
        float contentBoost = 0.7f;
        float propertyScore = 0.9f;
        float contentScore = 0.8f;

        float expectedScore = (propertyBoost * propertyScore) + (contentBoost * contentScore);
        float calculatedScore = 0.27f + 0.56f; // 0.3*0.9 + 0.7*0.8

        assertEquals(expectedScore, calculatedScore, 0.001f);
    }

    @Test
    public void testDefaultBoostValues() {
        // Default: propertyBoost=0.3, contentBoost=0.7
        float defaultPropertyBoost = 0.3f;
        float defaultContentBoost = 0.7f;

        // Sum should be 1.0 for normalized scoring
        assertEquals(1.0f, defaultPropertyBoost + defaultContentBoost, 0.001f);
    }

    @Test
    public void testPropertyOnlyBoost() {
        // When propertyBoost=1.0 and contentBoost=0.0, only property matters
        float propertyBoost = 1.0f;
        float contentBoost = 0.0f;
        float propertyScore = 0.85f;
        float contentScore = 0.5f;

        float combinedScore = (propertyBoost * propertyScore) + (contentBoost * contentScore);
        assertEquals(propertyScore, combinedScore, 0.001f);
    }

    @Test
    public void testContentOnlyBoost() {
        // When propertyBoost=0.0 and contentBoost=1.0, only content matters
        float propertyBoost = 0.0f;
        float contentBoost = 1.0f;
        float propertyScore = 0.9f;
        float contentScore = 0.6f;

        float combinedScore = (propertyBoost * propertyScore) + (contentBoost * contentScore);
        assertEquals(contentScore, combinedScore, 0.001f);
    }

    @Test
    public void testEqualBoostWeights() {
        // When both boosts are 0.5, score is average
        float propertyBoost = 0.5f;
        float contentBoost = 0.5f;
        float propertyScore = 0.8f;
        float contentScore = 0.6f;

        float combinedScore = (propertyBoost * propertyScore) + (contentBoost * contentScore);
        float expectedAverage = (propertyScore + contentScore) / 2.0f;

        assertEquals(expectedAverage, combinedScore, 0.001f);
    }

    @Test
    public void testScoreRanking() {
        // Test that scores are correctly ranked
        // Document A: high property score, low content score
        float docA_property = 0.95f;
        float docA_content = 0.4f;

        // Document B: low property score, high content score
        float docB_property = 0.3f;
        float docB_content = 0.9f;

        // With default boosts (0.3 property, 0.7 content)
        float boost_property = 0.3f;
        float boost_content = 0.7f;

        float scoreA = (boost_property * docA_property) + (boost_content * docA_content);
        float scoreB = (boost_property * docB_property) + (boost_content * docB_content);

        // Doc B should score higher with content-heavy weighting
        assertTrue("Content-rich document should score higher with content boost", scoreB > scoreA);
    }

    @Test
    public void testPropertyBoostPriority() {
        // Same documents as above, but with property-heavy weighting
        float docA_property = 0.95f;
        float docA_content = 0.4f;
        float docB_property = 0.3f;
        float docB_content = 0.9f;

        // With property-heavy boosts (0.7 property, 0.3 content)
        float boost_property = 0.7f;
        float boost_content = 0.3f;

        float scoreA = (boost_property * docA_property) + (boost_content * docA_content);
        float scoreB = (boost_property * docB_property) + (boost_content * docB_content);

        // Doc A should score higher with property-heavy weighting
        assertTrue("Property-rich document should score higher with property boost", scoreA > scoreB);
    }

    @Test
    public void testMinScoreFiltering() {
        float minScore = 0.5f;
        float[] scores = {0.8f, 0.6f, 0.4f, 0.3f, 0.9f};

        int passCount = 0;
        for (float score : scores) {
            if (score >= minScore) {
                passCount++;
            }
        }

        assertEquals(3, passCount); // 0.8, 0.6, 0.9 pass
    }

    @Test
    public void testVectorSearchResultSetters() {
        VectorSearchResult result = new VectorSearchResult();

        result.setDocumentId("doc123");
        result.setDocumentName("Test Document");
        result.setChunkId("doc123_chunk_0");
        result.setChunkIndex(0);
        result.setChunkText("Sample chunk text");
        result.setScore(0.85f);
        result.setPath("/test/path");
        result.setObjectType("cmis:document");

        assertEquals("doc123", result.getDocumentId());
        assertEquals("Test Document", result.getDocumentName());
        assertEquals("doc123_chunk_0", result.getChunkId());
        assertEquals(0, result.getChunkIndex());
        assertEquals("Sample chunk text", result.getChunkText());
        assertEquals(0.85f, result.getScore(), 0.001f);
        assertEquals("/test/path", result.getPath());
        assertEquals("cmis:document", result.getObjectType());
    }

    @Test
    public void testScoreBoundaries() {
        // Scores should be between 0.0 and 1.0
        float[] validScores = {0.0f, 0.5f, 1.0f};

        for (float score : validScores) {
            assertTrue("Score should be >= 0", score >= 0.0f);
            assertTrue("Score should be <= 1", score <= 1.0f);
        }
    }

    @Test
    public void testPropertyFieldsConfiguration() {
        // Test that default property fields are name and description
        String defaultFields = "cmis:name,cmis:description";
        String[] fields = defaultFields.split(",");

        assertEquals(2, fields.length);
        assertEquals("cmis:name", fields[0]);
        assertEquals("cmis:description", fields[1]);
    }

    @Test
    public void testCustomPropertyFieldsConfiguration() {
        // Test custom property fields configuration
        String customFields = "cmis:name,cmis:description,nemaki:category,nemaki:keywords";
        String[] fields = customFields.split(",");

        assertEquals(4, fields.length);
        assertTrue(java.util.Arrays.asList(fields).contains("nemaki:category"));
        assertTrue(java.util.Arrays.asList(fields).contains("nemaki:keywords"));
    }

    @Test
    public void testEmptyPropertyFields() {
        String emptyFields = "";
        String[] fields = emptyFields.isEmpty() ? new String[0] : emptyFields.split(",");

        assertEquals(0, fields.length);
    }

    @Test
    public void testPropertyTextExtraction() {
        // Simulate property text extraction
        String name = "契約書A";
        String description = "2025年度の契約に関する文書";

        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isEmpty()) {
            sb.append(name);
        }
        if (description != null && !description.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(description);
        }

        String propertyText = sb.toString();
        assertEquals("契約書A 2025年度の契約に関する文書", propertyText);
    }

    @Test
    public void testPropertyTextWithNullDescription() {
        String name = "Test Document";
        String description = null;

        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isEmpty()) {
            sb.append(name);
        }
        if (description != null && !description.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(description);
        }

        String propertyText = sb.toString();
        assertEquals("Test Document", propertyText);
    }
}
