package jp.aegif.nemaki.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.PurviewGovernanceService;
import jp.aegif.nemaki.rest.purview.PurviewGovernanceView;

public class PurviewGovernanceControllerTest {

    private PurviewGovernanceService governanceService;
    private PurviewGovernanceController controller;

    @BeforeEach
    public void setUp() {
        governanceService = mock(PurviewGovernanceService.class);
        controller = new PurviewGovernanceController(governanceService);
    }

    @Test
    public void testGetGovernanceReturnsForbiddenWhenAuthenticationIsMissing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(null);
        controller.setHttpRequest(request);

        ResponseEntity<Map<String, Object>> response = controller.getGovernance("bedroom", "doc-001");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    public void testGetGovernanceReturnsReadOnlyMetadata() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.getUsername()).thenReturn("alice");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        when(governanceService.getGovernance("bedroom", "doc-001", callContext)).thenReturn(new PurviewGovernanceView(
                true,
                true,
                true,
                true,
                "bedroom",
                "doc-001",
                "cmis:document",
                "nemaki_document",
                "nemaki://bedroom/objects/doc-001",
                "datamap/api/atlas/v2",
                "Purview governance metadata loaded",
                List.of(Map.of("typeName", "HighlyConfidential", "entityStatus", "ACTIVE")),
                List.of(Map.of("displayText", "Quarterly Report")),
                List.of("finance"),
                Map.of("nemakiGovernance", Map.of("ownerDepartment", "Finance"))));

        ResponseEntity<Map<String, Object>> response = controller.getGovernance("bedroom", "doc-001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("featureEnabled"));
        assertTrue((Boolean) response.getBody().get("available"));
        assertTrue((Boolean) response.getBody().get("entityFound"));
        assertEquals("nemaki_document", response.getBody().get("entityTypeName"));
        assertEquals("datamap/api/atlas/v2", response.getBody().get("atlasBasePath"));
        assertEquals(1, ((List<?>) response.getBody().get("classifications")).size());
    }

    @Test
    public void testGetGovernanceReturnsNotFoundWhenObjectDoesNotExist() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.getUsername()).thenReturn("alice");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        when(governanceService.getGovernance("bedroom", "missing-doc", callContext))
                .thenThrow(new CmisObjectNotFoundException("Object not found"));

        ResponseEntity<Map<String, Object>> response = controller.getGovernance("bedroom", "missing-doc");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertFalse(response.getBody().containsKey("featureEnabled"));
        assertEquals("Object not found", response.getBody().get("message"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGetGovernanceBulkReturnsItems() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.getUsername()).thenReturn("alice");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        PurviewGovernanceController.PurviewGovernanceBulkRequest bulkRequest =
                new PurviewGovernanceController.PurviewGovernanceBulkRequest();
        bulkRequest.setObjectIds(List.of("doc-001", "missing-001"));

        when(governanceService.getGovernanceBulk("bedroom", List.of("doc-001", "missing-001"), callContext))
                .thenReturn(List.of(
                        new jp.aegif.nemaki.rest.purview.PurviewGovernanceBulkItemView(
                                "doc-001",
                                "OK",
                                "loaded",
                                new PurviewGovernanceView(
                                        true,
                                        true,
                                        true,
                                        true,
                                        "bedroom",
                                        "doc-001",
                                        "cmis:document",
                                        "nemaki_document",
                                        "nemaki://bedroom/objects/doc-001",
                                        "datamap/api/atlas/v2",
                                        "loaded",
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        Map.of())),
                        new jp.aegif.nemaki.rest.purview.PurviewGovernanceBulkItemView(
                                "missing-001",
                                "NOT_FOUND",
                                "Object not found",
                                null)));

        ResponseEntity<Map<String, Object>> response = controller.getGovernanceBulk("bedroom", bulkRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertEquals(2, items.size());
        assertEquals("OK", items.get(0).get("status"));
        assertEquals("NOT_FOUND", items.get(1).get("status"));
    }

    @Test
    public void testGetGovernanceBulkReturnsBadRequestWhenObjectIdsMissing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.getUsername()).thenReturn("alice");
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        ResponseEntity<Map<String, Object>> response = controller.getGovernanceBulk(
                "bedroom",
                new PurviewGovernanceController.PurviewGovernanceBulkRequest());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("objectIds is required", response.getBody().get("message"));
    }
}
