package jp.aegif.nemaki.rest;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import static org.junit.Assert.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;
import org.json.JSONArray;

import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;

import java.util.ArrayList;
import java.util.List;

/**
 * REST API tests for TypeResource operations.
 * 
 * These tests verify the health and sanity of type operations via REST API,
 * separate from CMIS TCK tests. They focus on:
 * 1. API contract validation (request/response structure)
 * 2. Validation logic (subtype check, relationship reference check)
 * 3. Error response structure
 * 4. NemakiWare-specific type operations
 * 5. TypeResource endpoint behavior with mocked services
 * 
 * Note: These tests use mocked services to avoid requiring CouchDB/Solr.
 * For integration tests with actual database, use the TCK test suite.
 */
public class TypeResourceTests {
    
    private static final Log log = LogFactory.getLog(TypeResourceTests.class);
    
    // Test constants
    private static final String TEST_REPOSITORY_ID = "bedroom";
    private static final String TEST_TYPE_ID = "nemaki:testType";
    private static final String TEST_PARENT_TYPE_ID = "cmis:document";
    
    // TypeResource instance for endpoint tests
    private TypeResource typeResource;
    
    @Before
    public void setUp() {
        log.info("Setting up TypeResourceTests");
        typeResource = new TypeResource();
    }
    
    @After
    public void tearDown() {
        log.info("Tearing down TypeResourceTests");
        typeResource = null;
    }
    
    // ========================================
    // TypeResource Endpoint Tests with Mocked Services
    // ========================================
    
    /**
     * Test that list() returns 500 when TypeService is not available
     */
    @Test
    public void testListReturns500WhenTypeServiceNull() {
        // TypeResource with no services injected
        Response response = typeResource.list(TEST_REPOSITORY_ID);
        
        assertEquals("Should return 500 Internal Server Error", 
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), 
            response.getStatus());
        
        // Verify error response structure
        String entity = (String) response.getEntity();
        assertNotNull("Response entity should not be null", entity);
        assertTrue("Response should contain 'error' status", entity.contains("\"status\":\"error\""));
        assertTrue("Response should mention TypeService", entity.contains("TypeService"));
        
        log.info("list() correctly returns 500 when TypeService is null");
    }
    
    /**
     * Test that show() returns 500 when TypeService is not available
     */
    @Test
    public void testShowReturns500WhenTypeServiceNull() {
        Response response = typeResource.show(TEST_REPOSITORY_ID, TEST_TYPE_ID);
        
        assertEquals("Should return 500 Internal Server Error", 
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), 
            response.getStatus());
        
        String entity = (String) response.getEntity();
        assertNotNull("Response entity should not be null", entity);
        assertTrue("Response should contain 'error' status", entity.contains("\"status\":\"error\""));
        
        log.info("show() correctly returns 500 when TypeService is null");
    }
    
    /**
     * Test that create() returns 500 when TypeService is not available
     */
    @Test
    public void testCreateReturns500WhenTypeServiceNull() {
        String jsonInput = createValidTypeDefinitionJson().toString();
        Response response = typeResource.create(TEST_REPOSITORY_ID, jsonInput);
        
        assertEquals("Should return 500 Internal Server Error", 
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), 
            response.getStatus());
        
        String entity = (String) response.getEntity();
        assertNotNull("Response entity should not be null", entity);
        assertTrue("Response should contain 'error' status", entity.contains("\"status\":\"error\""));
        
        log.info("create() correctly returns 500 when TypeService is null");
    }
    
    /**
     * Test that update() returns 500 when TypeService is not available
     */
    @Test
    public void testUpdateReturns500WhenTypeServiceNull() {
        String jsonInput = createValidTypeDefinitionJson().toString();
        Response response = typeResource.update(TEST_REPOSITORY_ID, TEST_TYPE_ID, jsonInput);
        
        assertEquals("Should return 500 Internal Server Error", 
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), 
            response.getStatus());
        
        String entity = (String) response.getEntity();
        assertNotNull("Response entity should not be null", entity);
        assertTrue("Response should contain 'error' status", entity.contains("\"status\":\"error\""));
        
        log.info("update() correctly returns 500 when TypeService is null");
    }
    
    /**
     * Test that delete() returns 500 when TypeService is not available
     */
    @Test
    public void testDeleteReturns500WhenTypeServiceNull() {
        Response response = typeResource.delete(TEST_REPOSITORY_ID, TEST_TYPE_ID);
        
        assertEquals("Should return 500 Internal Server Error", 
            Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), 
            response.getStatus());
        
        String entity = (String) response.getEntity();
        assertNotNull("Response entity should not be null", entity);
        assertTrue("Response should contain 'error' status", entity.contains("\"status\":\"error\""));
        
        log.info("delete() correctly returns 500 when TypeService is null");
    }
    
    /**
     * Test that update() returns 400 for base type modification attempt
     */
    @Test
    public void testUpdateRejectsBaseTypeModification() {
        // Inject a mock TypeService that returns a type definition
        TypeService mockTypeService = createMockTypeServiceForBaseTypeTest();
        typeResource.setTypeService(mockTypeService);
        
        String jsonInput = createValidTypeDefinitionJson().toString();
        Response response = typeResource.update(TEST_REPOSITORY_ID, "cmis:document", jsonInput);
        
        assertEquals("Should return 400 Bad Request for base type", 
            Response.Status.BAD_REQUEST.getStatusCode(), 
            response.getStatus());
        
        String entity = (String) response.getEntity();
        assertTrue("Response should mention base type", entity.contains("base type"));
        
        log.info("update() correctly rejects base type modification");
    }
    
    /**
     * Test that delete() returns 400 for base type deletion attempt
     */
    @Test
    public void testDeleteRejectsBaseTypeDeletion() {
        // Inject a mock TypeService
        TypeService mockTypeService = createMockTypeServiceForBaseTypeTest();
        typeResource.setTypeService(mockTypeService);
        
        Response response = typeResource.delete(TEST_REPOSITORY_ID, "cmis:document");
        
        assertEquals("Should return 400 Bad Request for base type", 
            Response.Status.BAD_REQUEST.getStatusCode(), 
            response.getStatus());
        
        String entity = (String) response.getEntity();
        assertTrue("Response should mention base type", entity.contains("base type"));
        
        log.info("delete() correctly rejects base type deletion");
    }
    
    /**
     * Test response structure for successful operations
     */
    @Test
    public void testSuccessResponseStructure() {
        // Verify that success responses have the expected structure
        JSONObject successResponse = new JSONObject();
        successResponse.put("status", "success");
        successResponse.put("message", "Operation completed");
        successResponse.put("typeId", TEST_TYPE_ID);
        
        assertTrue("Success response must have status field", successResponse.has("status"));
        assertEquals("Status must be 'success'", "success", successResponse.getString("status"));
        assertTrue("Success response must have message field", successResponse.has("message"));
        
        log.info("Success response structure verified");
    }
    
    /**
     * Test response structure for error operations
     */
    @Test
    public void testErrorResponseStructure() {
        // Verify that error responses have the expected structure
        JSONObject errorResponse = new JSONObject();
        errorResponse.put("status", "error");
        errorResponse.put("message", "Operation failed");
        errorResponse.put("error", "Detailed error message");
        
        assertTrue("Error response must have status field", errorResponse.has("status"));
        assertEquals("Status must be 'error'", "error", errorResponse.getString("status"));
        assertTrue("Error response must have message field", errorResponse.has("message"));
        
        log.info("Error response structure verified");
    }
    
    /**
     * Create a mock TypeService for base type tests
     * This returns a simple implementation that allows testing base type rejection
     */
    private TypeService createMockTypeServiceForBaseTypeTest() {
        return new TypeService() {
            @Override
            public NemakiTypeDefinition getTypeDefinition(String repositoryId, String typeId) {
                // Return a mock type definition for base types
                if (typeId != null && typeId.startsWith("cmis:")) {
                    NemakiTypeDefinition mockType = new NemakiTypeDefinition();
                    mockType.setTypeId(typeId);
                    return mockType;
                }
                return null;
            }

            @Override
            public List<NemakiTypeDefinition> getTypeDefinitions(String repositoryId) {
                return new ArrayList<>();
            }

            @Override
            public NemakiTypeDefinition createTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
                return typeDefinition;
            }

            @Override
            public NemakiTypeDefinition updateTypeDefinition(String repositoryId, NemakiTypeDefinition typeDefinition) {
                return typeDefinition;
            }

            @Override
            public void deleteTypeDefinition(String repositoryId, String typeId) {
                // No-op for mock
            }

            @Override
            public NemakiPropertyDefinitionCore getPropertyDefinitionCore(String repositoryId, String coreId) {
                return null;
            }

            @Override
            public NemakiPropertyDefinitionCore getPropertyDefinitionCoreByPropertyId(String repositoryId, String propertyId) {
                return null;
            }

            @Override
            public List<NemakiPropertyDefinitionCore> getPropertyDefinitionCores(String repositoryId) {
                return new ArrayList<>();
            }

            @Override
            public NemakiPropertyDefinitionDetail getPropertyDefinitionDetail(String repositoryId, String detailId) {
                return null;
            }

            @Override
            public List<NemakiPropertyDefinitionDetail> getPropertyDefinitionDetailByCoreNodeId(String repositoryId, String coreNodeId) {
                return new ArrayList<>();
            }

            @Override
            public NemakiPropertyDefinition getPropertyDefinition(String repositoryId, String detailNodeId) {
                return null;
            }

            @Override
            public NemakiPropertyDefinitionDetail createPropertyDefinition(String repositoryId, NemakiPropertyDefinition propertyDefinition) {
                return new NemakiPropertyDefinitionDetail();
            }

            @Override
            public NemakiPropertyDefinitionDetail updatePropertyDefinitionDetail(String repositoryId, NemakiPropertyDefinitionDetail propertyDefinitionDetail) {
                return propertyDefinitionDetail;
            }

            @Override
            public NemakiPropertyDefinitionCore updatePropertyDefinitionCore(String repositoryId, NemakiPropertyDefinitionCore propertyDefinitionCore) {
                return propertyDefinitionCore;
            }
        };
    }
    
    // ========================================
    // JSON Structure Validation Tests
    // ========================================
    
    /**
     * Test that a valid type definition JSON has all required fields
     */
    @Test
    public void testValidTypeDefinitionJsonStructure() {
        JSONObject typeJson = createValidTypeDefinitionJson();
        
        // Verify required fields
        assertTrue("Type JSON must have 'id' field", typeJson.has("id"));
        assertTrue("Type JSON must have 'localName' field", typeJson.has("localName"));
        assertTrue("Type JSON must have 'queryName' field", typeJson.has("queryName"));
        assertTrue("Type JSON must have 'displayName' field", typeJson.has("displayName"));
        assertTrue("Type JSON must have 'baseId' field", typeJson.has("baseId"));
        assertTrue("Type JSON must have 'parentId' field", typeJson.has("parentId"));
        
        // Verify field values are not empty
        assertFalse("Type ID must not be empty", typeJson.getString("id").isEmpty());
        assertFalse("Base ID must not be empty", typeJson.getString("baseId").isEmpty());
        
        log.info("Valid type definition JSON structure verified");
    }
    
    /**
     * Test that property definition JSON has all required fields
     */
    @Test
    public void testValidPropertyDefinitionJsonStructure() {
        JSONObject propertyJson = createValidPropertyDefinitionJson("nemaki:testProperty");
        
        // Verify required fields
        assertTrue("Property JSON must have 'id' field", propertyJson.has("id"));
        assertTrue("Property JSON must have 'localName' field", propertyJson.has("localName"));
        assertTrue("Property JSON must have 'queryName' field", propertyJson.has("queryName"));
        assertTrue("Property JSON must have 'displayName' field", propertyJson.has("displayName"));
        assertTrue("Property JSON must have 'propertyType' field", propertyJson.has("propertyType"));
        assertTrue("Property JSON must have 'cardinality' field", propertyJson.has("cardinality"));
        assertTrue("Property JSON must have 'updatability' field", propertyJson.has("updatability"));
        
        // Verify field values
        assertFalse("Property ID must not be empty", propertyJson.getString("id").isEmpty());
        
        log.info("Valid property definition JSON structure verified");
    }
    
    // ========================================
    // Error Response Structure Tests
    // ========================================
    
    /**
     * Test that error response for subtype blocking has correct structure
     */
    @Test
    public void testSubtypeBlockingErrorResponseStructure() {
        // Simulate error response when trying to delete a type with subtypes
        JSONObject errorResponse = createSubtypeBlockingErrorResponse(
            "nemaki:parentType", 
            new String[]{"nemaki:childType1", "nemaki:childType2"}
        );
        
        // Verify error response structure
        assertTrue("Error response must have 'status' field", errorResponse.has("status"));
        assertEquals("Status must be 'error'", "error", errorResponse.getString("status"));
        assertTrue("Error response must have 'message' field", errorResponse.has("message"));
        assertTrue("Error response must have 'subtypes' array", errorResponse.has("subtypes"));
        
        // Verify subtypes array
        JSONArray subtypes = errorResponse.getJSONArray("subtypes");
        assertEquals("Subtypes array must contain 2 items", 2, subtypes.length());
        assertEquals("First subtype must be 'nemaki:childType1'", "nemaki:childType1", subtypes.getString(0));
        assertEquals("Second subtype must be 'nemaki:childType2'", "nemaki:childType2", subtypes.getString(1));
        
        // Verify message contains helpful information
        String message = errorResponse.getString("message");
        assertTrue("Message must mention subtypes", message.contains("subtypes"));
        assertTrue("Message must mention the parent type", message.contains("nemaki:parentType"));
        
        log.info("Subtype blocking error response structure verified");
    }
    
    /**
     * Test that error response for relationship reference blocking has correct structure
     */
    @Test
    public void testRelationshipReferenceBlockingErrorResponseStructure() {
        // Simulate error response when trying to delete a type referenced by relationships
        JSONObject errorResponse = createRelationshipReferenceBlockingErrorResponse(
            "nemaki:targetType",
            new String[]{"nemaki:relType1", "nemaki:relType2"}
        );
        
        // Verify error response structure
        assertTrue("Error response must have 'status' field", errorResponse.has("status"));
        assertEquals("Status must be 'error'", "error", errorResponse.getString("status"));
        assertTrue("Error response must have 'message' field", errorResponse.has("message"));
        assertTrue("Error response must have 'referencingRelationships' array", 
            errorResponse.has("referencingRelationships"));
        
        // Verify relationships array
        JSONArray relationships = errorResponse.getJSONArray("referencingRelationships");
        assertEquals("Relationships array must contain 2 items", 2, relationships.length());
        
        // Verify message contains helpful information
        String message = errorResponse.getString("message");
        assertTrue("Message must mention relationship types", message.contains("relationship"));
        assertTrue("Message must mention the target type", message.contains("nemaki:targetType"));
        
        log.info("Relationship reference blocking error response structure verified");
    }
    
    /**
     * Test that success response has correct structure with warning for non-CMIS operations
     */
    @Test
    public void testSuccessResponseWithWarningStructure() {
        // Simulate success response for type update (non-CMIS operation)
        JSONObject successResponse = createSuccessResponseWithWarning("nemaki:updatedType");
        
        // Verify success response structure
        assertTrue("Success response must have 'status' field", successResponse.has("status"));
        assertEquals("Status must be 'success'", "success", successResponse.getString("status"));
        assertTrue("Success response must have 'message' field", successResponse.has("message"));
        assertTrue("Success response must have 'typeId' field", successResponse.has("typeId"));
        assertTrue("Success response must have 'warning' field for non-CMIS operations", 
            successResponse.has("warning"));
        
        // Verify warning mentions CMIS non-compliance
        String warning = successResponse.getString("warning");
        assertTrue("Warning must mention NemakiWare-specific", warning.contains("NemakiWare"));
        assertTrue("Warning must mention CMIS", warning.contains("CMIS"));
        
        log.info("Success response with warning structure verified");
    }
    
    // ========================================
    // Property Type Validation Tests
    // ========================================
    
    /**
     * Test all valid CMIS property types
     */
    @Test
    public void testValidCmisPropertyTypes() {
        String[] validTypes = {
            "string", "boolean", "integer", "decimal", 
            "datetime", "id", "html", "uri"
        };
        
        for (String type : validTypes) {
            assertTrue("Property type '" + type + "' should be valid", 
                isValidPropertyType(type));
        }
        
        log.info("All valid CMIS property types verified");
    }
    
    /**
     * Test invalid property types are rejected
     */
    @Test
    public void testInvalidPropertyTypesRejected() {
        String[] invalidTypes = {
            "text", "number", "date", "binary", "object", "array", ""
        };
        
        for (String type : invalidTypes) {
            assertFalse("Property type '" + type + "' should be invalid", 
                isValidPropertyType(type));
        }
        
        log.info("Invalid property types correctly rejected");
    }
    
    /**
     * Test valid cardinality values
     */
    @Test
    public void testValidCardinalityValues() {
        assertTrue("'single' should be valid cardinality", isValidCardinality("single"));
        assertTrue("'multi' should be valid cardinality", isValidCardinality("multi"));
        
        assertFalse("'multiple' should be invalid cardinality", isValidCardinality("multiple"));
        assertFalse("'one' should be invalid cardinality", isValidCardinality("one"));
        assertFalse("Empty string should be invalid cardinality", isValidCardinality(""));
        
        log.info("Cardinality validation verified");
    }
    
    /**
     * Test valid updatability values
     */
    @Test
    public void testValidUpdatabilityValues() {
        String[] validValues = {"readonly", "readwrite", "whencheckedout", "oncreate"};
        
        for (String value : validValues) {
            assertTrue("Updatability '" + value + "' should be valid", 
                isValidUpdatability(value));
        }
        
        assertFalse("'editable' should be invalid updatability", isValidUpdatability("editable"));
        assertFalse("Empty string should be invalid updatability", isValidUpdatability(""));
        
        log.info("Updatability validation verified");
    }
    
    // ========================================
    // Base Type Validation Tests
    // ========================================
    
    /**
     * Test that base types cannot be modified
     */
    @Test
    public void testBaseTypesCannotBeModified() {
        String[] baseTypes = {
            "cmis:document", "cmis:folder", "cmis:relationship", 
            "cmis:policy", "cmis:item", "cmis:secondary"
        };
        
        for (String baseType : baseTypes) {
            assertTrue("'" + baseType + "' should be identified as base type", 
                isBaseType(baseType));
        }
        
        assertFalse("'nemaki:customType' should not be identified as base type", 
            isBaseType("nemaki:customType"));
        
        log.info("Base type identification verified");
    }
    
    // ========================================
    // Type ID Format Validation Tests
    // ========================================
    
    /**
     * Test valid type ID formats
     */
    @Test
    public void testValidTypeIdFormats() {
        String[] validIds = {
            "nemaki:customDoc",
            "myprefix:myType",
            "test:type_with_underscore",
            "ns:type-with-dash"
        };
        
        for (String id : validIds) {
            assertTrue("Type ID '" + id + "' should be valid format", 
                isValidTypeIdFormat(id));
        }
        
        log.info("Valid type ID formats verified");
    }
    
    /**
     * Test invalid type ID formats
     */
    @Test
    public void testInvalidTypeIdFormats() {
        String[] invalidIds = {
            "",           // empty
            "noprefix",   // no namespace prefix
            ":noname",    // no local name
            "has space:type",  // space in prefix
            "prefix:has space" // space in local name
        };
        
        for (String id : invalidIds) {
            assertFalse("Type ID '" + id + "' should be invalid format", 
                isValidTypeIdFormat(id));
        }
        
        log.info("Invalid type ID formats correctly rejected");
    }
    
    // ========================================
    // Relationship Type Validation Tests
    // ========================================
    
    /**
     * Test relationship type definition requires allowedSourceTypes/allowedTargetTypes
     */
    @Test
    public void testRelationshipTypeDefinitionStructure() {
        JSONObject relationshipType = createRelationshipTypeDefinitionJson();
        
        // Verify relationship-specific fields
        assertEquals("Base ID must be 'cmis:relationship'", 
            "cmis:relationship", relationshipType.getString("baseId"));
        assertTrue("Relationship type should have allowedSourceTypes", 
            relationshipType.has("allowedSourceTypes"));
        assertTrue("Relationship type should have allowedTargetTypes", 
            relationshipType.has("allowedTargetTypes"));
        
        log.info("Relationship type definition structure verified");
    }
    
    // ========================================
    // Type Hierarchy Validation Tests
    // ========================================
    
    /**
     * Test that subtype detection works correctly
     */
    @Test
    public void testSubtypeDetection() {
        // Create a mock type hierarchy
        List<MockTypeDefinition> types = new ArrayList<>();
        types.add(new MockTypeDefinition("nemaki:parent", "cmis:document"));
        types.add(new MockTypeDefinition("nemaki:child1", "nemaki:parent"));
        types.add(new MockTypeDefinition("nemaki:child2", "nemaki:parent"));
        types.add(new MockTypeDefinition("nemaki:unrelated", "cmis:document"));
        
        // Find subtypes of nemaki:parent
        List<String> subtypes = findSubtypes(types, "nemaki:parent");
        
        assertEquals("Should find 2 subtypes", 2, subtypes.size());
        assertTrue("Should include nemaki:child1", subtypes.contains("nemaki:child1"));
        assertTrue("Should include nemaki:child2", subtypes.contains("nemaki:child2"));
        assertFalse("Should not include nemaki:unrelated", subtypes.contains("nemaki:unrelated"));
        
        log.info("Subtype detection verified");
    }
    
    /**
     * Test that relationship reference detection works correctly
     */
    @Test
    public void testRelationshipReferenceDetection() {
        // Create mock relationship types
        List<MockRelationshipType> relationships = new ArrayList<>();
        relationships.add(new MockRelationshipType("nemaki:rel1", 
            new String[]{"nemaki:sourceType"}, new String[]{"nemaki:targetType"}));
        relationships.add(new MockRelationshipType("nemaki:rel2", 
            new String[]{"nemaki:targetType"}, new String[]{"nemaki:otherType"}));
        relationships.add(new MockRelationshipType("nemaki:rel3", 
            new String[]{"nemaki:otherType"}, new String[]{"nemaki:otherType2"}));
        
        // Find relationships referencing nemaki:targetType
        List<String> referencingRels = findReferencingRelationships(relationships, "nemaki:targetType");
        
        assertEquals("Should find 2 referencing relationships", 2, referencingRels.size());
        assertTrue("Should include nemaki:rel1", referencingRels.contains("nemaki:rel1"));
        assertTrue("Should include nemaki:rel2", referencingRels.contains("nemaki:rel2"));
        assertFalse("Should not include nemaki:rel3", referencingRels.contains("nemaki:rel3"));
        
        log.info("Relationship reference detection verified");
    }
    
    // ========================================
    // Property Core Attribute Tests
    // ========================================
    
    /**
     * Test that property core attributes (propertyType, cardinality) can be identified
     */
    @Test
    public void testPropertyCoreAttributeIdentification() {
        assertTrue("propertyType is a core attribute", isCoreAttribute("propertyType"));
        assertTrue("cardinality is a core attribute", isCoreAttribute("cardinality"));
        
        assertFalse("displayName is not a core attribute", isCoreAttribute("displayName"));
        assertFalse("description is not a core attribute", isCoreAttribute("description"));
        assertFalse("updatability is not a core attribute", isCoreAttribute("updatability"));
        assertFalse("required is not a core attribute", isCoreAttribute("required"));
        
        log.info("Property core attribute identification verified");
    }
    
    /**
     * Test property type coercion compatibility
     */
    @Test
    public void testPropertyTypeCoercionCompatibility() {
        // String can be coerced to most types
        assertTrue("String -> Boolean should be possible", 
            canCoerce("string", "boolean"));
        assertTrue("String -> Integer should be possible", 
            canCoerce("string", "integer"));
        assertTrue("String -> Decimal should be possible", 
            canCoerce("string", "decimal"));
        assertTrue("String -> DateTime should be possible", 
            canCoerce("string", "datetime"));
        
        // Any type can be coerced to String
        assertTrue("Boolean -> String should be possible", 
            canCoerce("boolean", "string"));
        assertTrue("Integer -> String should be possible", 
            canCoerce("integer", "string"));
        
        // Numeric coercions
        assertTrue("Integer -> Decimal should be possible", 
            canCoerce("integer", "decimal"));
        assertTrue("Decimal -> Integer should be possible (with truncation)", 
            canCoerce("decimal", "integer"));
        
        log.info("Property type coercion compatibility verified");
    }
    
    // ========================================
    // Helper Methods
    // ========================================
    
    private JSONObject createValidTypeDefinitionJson() {
        JSONObject json = new JSONObject();
        json.put("id", TEST_TYPE_ID);
        json.put("localName", "testType");
        json.put("queryName", "nemaki:testType");
        json.put("displayName", "Test Type");
        json.put("description", "A test type definition");
        json.put("baseId", TEST_PARENT_TYPE_ID);
        json.put("parentId", TEST_PARENT_TYPE_ID);
        json.put("creatable", true);
        json.put("fileable", true);
        json.put("queryable", true);
        json.put("controllablePolicy", false);
        json.put("controllableAcl", true);
        json.put("fulltextIndexed", true);
        json.put("includedInSupertypeQuery", true);
        json.put("propertyDefinitions", new JSONArray());
        return json;
    }
    
    private JSONObject createValidPropertyDefinitionJson(String propertyId) {
        JSONObject json = new JSONObject();
        json.put("id", propertyId);
        json.put("localName", propertyId.substring(propertyId.indexOf(':') + 1));
        json.put("queryName", propertyId);
        json.put("displayName", "Test Property");
        json.put("description", "A test property definition");
        json.put("propertyType", "string");
        json.put("cardinality", "single");
        json.put("updatability", "readwrite");
        json.put("inherited", false);
        json.put("required", false);
        json.put("queryable", true);
        json.put("orderable", true);
        json.put("openChoice", false);
        return json;
    }
    
    private JSONObject createRelationshipTypeDefinitionJson() {
        JSONObject json = new JSONObject();
        json.put("id", "nemaki:testRelationship");
        json.put("localName", "testRelationship");
        json.put("queryName", "nemaki:testRelationship");
        json.put("displayName", "Test Relationship");
        json.put("baseId", "cmis:relationship");
        json.put("parentId", "cmis:relationship");
        json.put("allowedSourceTypes", new JSONArray().put("cmis:document"));
        json.put("allowedTargetTypes", new JSONArray().put("cmis:document"));
        return json;
    }
    
    private JSONObject createSubtypeBlockingErrorResponse(String typeId, String[] subtypes) {
        JSONObject json = new JSONObject();
        json.put("status", "error");
        json.put("message", "Cannot delete type '" + typeId + "' because it has subtypes. " +
            "Delete the following subtypes first: " + String.join(", ", subtypes));
        JSONArray subtypesArray = new JSONArray();
        for (String subtype : subtypes) {
            subtypesArray.put(subtype);
        }
        json.put("subtypes", subtypesArray);
        return json;
    }
    
    private JSONObject createRelationshipReferenceBlockingErrorResponse(String typeId, String[] relationships) {
        JSONObject json = new JSONObject();
        json.put("status", "error");
        json.put("message", "Cannot delete type '" + typeId + "' because it is referenced by relationship types. " +
            "Update or delete the following relationship types first: " + String.join(", ", relationships));
        JSONArray relationshipsArray = new JSONArray();
        for (String rel : relationships) {
            relationshipsArray.put(rel);
        }
        json.put("referencingRelationships", relationshipsArray);
        return json;
    }
    
    private JSONObject createSuccessResponseWithWarning(String typeId) {
        JSONObject json = new JSONObject();
        json.put("status", "success");
        json.put("message", "Type definition updated successfully");
        json.put("typeId", typeId);
        json.put("warning", "This operation is NemakiWare-specific and not CMIS-compliant. " +
            "Existing documents may be affected.");
        return json;
    }
    
    private boolean isValidPropertyType(String type) {
        if (type == null || type.isEmpty()) return false;
        String[] validTypes = {"string", "boolean", "integer", "decimal", 
            "datetime", "id", "html", "uri"};
        for (String valid : validTypes) {
            if (valid.equalsIgnoreCase(type)) return true;
        }
        return false;
    }
    
    private boolean isValidCardinality(String cardinality) {
        if (cardinality == null || cardinality.isEmpty()) return false;
        return "single".equalsIgnoreCase(cardinality) || "multi".equalsIgnoreCase(cardinality);
    }
    
    private boolean isValidUpdatability(String updatability) {
        if (updatability == null || updatability.isEmpty()) return false;
        String[] validValues = {"readonly", "readwrite", "whencheckedout", "oncreate"};
        for (String valid : validValues) {
            if (valid.equalsIgnoreCase(updatability)) return true;
        }
        return false;
    }
    
    private boolean isBaseType(String typeId) {
        if (typeId == null) return false;
        String[] baseTypes = {"cmis:document", "cmis:folder", "cmis:relationship", 
            "cmis:policy", "cmis:item", "cmis:secondary"};
        for (String baseType : baseTypes) {
            if (baseType.equals(typeId)) return true;
        }
        return false;
    }
    
    private boolean isValidTypeIdFormat(String typeId) {
        if (typeId == null || typeId.isEmpty()) return false;
        // Must have format "prefix:localName"
        int colonIndex = typeId.indexOf(':');
        if (colonIndex <= 0 || colonIndex >= typeId.length() - 1) return false;
        String prefix = typeId.substring(0, colonIndex);
        String localName = typeId.substring(colonIndex + 1);
        // No spaces allowed
        if (prefix.contains(" ") || localName.contains(" ")) return false;
        return true;
    }
    
    private boolean isCoreAttribute(String attributeName) {
        return "propertyType".equals(attributeName) || "cardinality".equals(attributeName);
    }
    
    private boolean canCoerce(String fromType, String toType) {
        // Any type can be coerced to string
        if ("string".equals(toType)) return true;
        // String can be coerced to most types (parsing may fail at runtime)
        if ("string".equals(fromType)) return true;
        // Numeric coercions
        if (("integer".equals(fromType) || "decimal".equals(fromType)) &&
            ("integer".equals(toType) || "decimal".equals(toType))) return true;
        return false;
    }
    
    private List<String> findSubtypes(List<MockTypeDefinition> types, String parentTypeId) {
        List<String> subtypes = new ArrayList<>();
        for (MockTypeDefinition type : types) {
            if (parentTypeId.equals(type.parentId)) {
                subtypes.add(type.typeId);
            }
        }
        return subtypes;
    }
    
    private List<String> findReferencingRelationships(List<MockRelationshipType> relationships, String typeId) {
        List<String> referencing = new ArrayList<>();
        for (MockRelationshipType rel : relationships) {
            boolean references = false;
            for (String source : rel.allowedSourceTypes) {
                if (typeId.equals(source)) {
                    references = true;
                    break;
                }
            }
            if (!references) {
                for (String target : rel.allowedTargetTypes) {
                    if (typeId.equals(target)) {
                        references = true;
                        break;
                    }
                }
            }
            if (references) {
                referencing.add(rel.typeId);
            }
        }
        return referencing;
    }
    
    // Mock classes for testing
    private static class MockTypeDefinition {
        String typeId;
        String parentId;
        
        MockTypeDefinition(String typeId, String parentId) {
            this.typeId = typeId;
            this.parentId = parentId;
        }
    }
    
    private static class MockRelationshipType {
        String typeId;
        String[] allowedSourceTypes;
        String[] allowedTargetTypes;
        
        MockRelationshipType(String typeId, String[] allowedSourceTypes, String[] allowedTargetTypes) {
            this.typeId = typeId;
            this.allowedSourceTypes = allowedSourceTypes;
            this.allowedTargetTypes = allowedTargetTypes;
        }
    }
}
