package jp.aegif.nemaki.rest.controller;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.PurviewGovernanceBulkItemView;
import jp.aegif.nemaki.rest.purview.PurviewGovernanceService;
import jp.aegif.nemaki.rest.purview.PurviewGovernanceView;

@RestController
@RequestMapping("/v1/repo/{repositoryId}/purview/governance")
@CrossOrigin(origins = "*", maxAge = 3600)
public class PurviewGovernanceController {

    private final PurviewGovernanceService purviewGovernanceService;

    private HttpServletRequest httpRequest;

    @Autowired
    public PurviewGovernanceController(PurviewGovernanceService purviewGovernanceService) {
        this.purviewGovernanceService = purviewGovernanceService;
    }

    @Autowired
    public void setHttpRequest(HttpServletRequest httpRequest) {
        this.httpRequest = httpRequest;
    }

    @GetMapping("/{objectId}")
    public ResponseEntity<Map<String, Object>> getGovernance(
            @PathVariable("repositoryId") String repositoryId,
            @PathVariable("objectId") String objectId) {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null || callContext.getUsername() == null || callContext.getUsername().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse("Authentication required"));
        }

        try {
            PurviewGovernanceView view = purviewGovernanceService.getGovernance(repositoryId, objectId, callContext);
            return ResponseEntity.ok(toResponse(view));
        } catch (CmisObjectNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse("Object not found"));
        } catch (CmisPermissionDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse("Permission denied"));
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> getGovernanceBulk(
            @PathVariable("repositoryId") String repositoryId,
            @RequestBody PurviewGovernanceBulkRequest requestBody) {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null || callContext.getUsername() == null || callContext.getUsername().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse("Authentication required"));
        }
        if (requestBody == null || requestBody.getObjectIds() == null || requestBody.getObjectIds().isEmpty()) {
            return ResponseEntity.badRequest().body(errorResponse("objectIds is required"));
        }

        List<PurviewGovernanceBulkItemView> items = purviewGovernanceService.getGovernanceBulk(
                repositoryId,
                requestBody.getObjectIds(),
                callContext);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items.stream().map(this::toBulkResponse).toList());
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toResponse(PurviewGovernanceView view) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("featureEnabled", view.isFeatureEnabled());
        response.put("available", view.isAvailable());
        response.put("supportedObjectType", view.isSupportedObjectType());
        response.put("entityFound", view.isEntityFound());
        response.put("repositoryId", view.getRepositoryId());
        response.put("objectId", view.getObjectId());
        response.put("objectBaseType", view.getObjectBaseType());
        response.put("entityTypeName", view.getEntityTypeName());
        response.put("qualifiedName", view.getQualifiedName());
        response.put("atlasBasePath", view.getAtlasBasePath());
        response.put("message", view.getMessage());
        response.put("classifications", view.getClassifications());
        response.put("glossaryTerms", view.getGlossaryTerms());
        response.put("labels", view.getLabels());
        response.put("businessMetadata", view.getBusinessMetadata());
        return response;
    }

    private Map<String, Object> toBulkResponse(PurviewGovernanceBulkItemView item) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("objectId", item.getObjectId());
        response.put("status", item.getStatus());
        response.put("message", item.getMessage());
        if (item.getGovernance() != null) {
            response.putAll(toResponse(item.getGovernance()));
        }
        return response;
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return response;
    }

    public static class PurviewGovernanceBulkRequest {
        private List<String> objectIds;

        public List<String> getObjectIds() {
            return objectIds;
        }

        public void setObjectIds(List<String> objectIds) {
            this.objectIds = objectIds;
        }
    }
}
