package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.PropertyValue;
import jp.aegif.nemaki.api.v1.model.request.PolicyApplyRequest;
import jp.aegif.nemaki.api.v1.model.request.PolicyCreateRequest;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.ObjectResponse;
import jp.aegif.nemaki.api.v1.model.response.PolicyListResponse;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.PolicyService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/policies")
@Tag(name = "policies", description = "CMIS policy operations")
@Produces(MediaType.APPLICATION_JSON)
public class PolicyResource {
    
    private static final Logger logger = Logger.getLogger(PolicyResource.class.getName());
    private static final SimpleDateFormat ISO_DATE_FORMAT;
    
    static {
        ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    @Autowired
    private PolicyService policyService;
    
    @Autowired
    private ObjectService objectService;
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create policy",
            description = "Creates a policy object of the specified type"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Policy created",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createPolicy(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Policy to create", required = true)
            PolicyCreateRequest request) {
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            if (request == null) {
                throw ApiException.invalidArgument("Request body is required");
            }
            if (request.getName() == null || request.getName().isEmpty()) {
                throw ApiException.invalidArgument("Policy name is required");
            }
            
            Properties properties = convertToProperties(request);
            
            String policyId = objectService.createPolicy(
                    callContext, repositoryId, properties, request.getFolderId(), 
                    null, null, null, null);
            
            ObjectData policyData = objectService.getObject(
                    callContext, repositoryId, policyId, null, Boolean.FALSE, 
                    null, null, Boolean.FALSE, Boolean.FALSE, null);
            
            ObjectResponse response = mapToObjectResponse(policyData, repositoryId);
            
            return Response.status(Response.Status.CREATED).entity(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error creating policy: " + e.getMessage());
            throw ApiException.internalError("Failed to create policy: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/apply")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Apply policy",
            description = "Applies a policy to the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Policy applied successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Policy or object not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response applyPolicy(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Policy apply request", required = true)
            PolicyApplyRequest request) {
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            if (request == null) {
                throw ApiException.invalidArgument("Request body is required");
            }
            if (request.getPolicyId() == null || request.getPolicyId().isEmpty()) {
                throw ApiException.invalidArgument("Policy ID (policyId) is required");
            }
            if (request.getObjectId() == null || request.getObjectId().isEmpty()) {
                throw ApiException.invalidArgument("Object ID (objectId) is required");
            }
            
            policyService.applyPolicy(callContext, repositoryId, request.getPolicyId(), request.getObjectId(), null);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("policyId", request.getPolicyId());
            result.put("objectId", request.getObjectId());
            result.put("message", "Policy applied successfully");
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("policy", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + request.getPolicyId()));
            links.put("object", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + request.getObjectId()));
            links.put("appliedPolicies", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/policies/object/" + request.getObjectId()));
            result.put("_links", links);
            
            return Response.ok(result).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error applying policy: " + e.getMessage());
            throw ApiException.internalError("Failed to apply policy: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Remove policy",
            description = "Removes a policy from the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Policy removed successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Policy or object not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response removePolicy(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Policy remove request", required = true)
            PolicyApplyRequest request) {
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            if (request == null) {
                throw ApiException.invalidArgument("Request body is required");
            }
            if (request.getPolicyId() == null || request.getPolicyId().isEmpty()) {
                throw ApiException.invalidArgument("Policy ID (policyId) is required");
            }
            if (request.getObjectId() == null || request.getObjectId().isEmpty()) {
                throw ApiException.invalidArgument("Object ID (objectId) is required");
            }
            
            policyService.removePolicy(callContext, repositoryId, request.getPolicyId(), request.getObjectId(), null);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("policyId", request.getPolicyId());
            result.put("objectId", request.getObjectId());
            result.put("message", "Policy removed successfully");
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("policy", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + request.getPolicyId()));
            links.put("object", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + request.getObjectId()));
            result.put("_links", links);
            
            return Response.ok(result).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error removing policy: " + e.getMessage());
            throw ApiException.internalError("Failed to remove policy: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/object/{objectId}")
    @Operation(
            summary = "Get applied policies",
            description = "Gets the list of policies currently applied to the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of applied policies",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PolicyListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Object not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getAppliedPolicies(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Property filter")
            @QueryParam("filter") String filter) {
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            List<ObjectData> policies = policyService.getAppliedPolicies(
                    callContext, repositoryId, objectId, filter, null);
            
            PolicyListResponse response = mapToPolicyListResponse(policies, repositoryId, objectId);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting applied policies for object " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get applied policies: " + e.getMessage(), e);
        }
    }
    
    private void validateRepository(String repositoryId) {
        if (repositoryService.getRepositoryInfo(repositoryId) == null) {
            throw ApiException.repositoryNotFound(repositoryId);
        }
    }
    
    private CallContext getCallContext() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return callContext;
    }
    
    private Properties convertToProperties(PolicyCreateRequest request) {
        PropertiesImpl properties = new PropertiesImpl();
        
        String typeId = request.getTypeId() != null ? request.getTypeId() : "cmis:policy";
        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, typeId));
        properties.addProperty(new PropertyStringImpl(PropertyIds.NAME, request.getName()));
        
        if (request.getDescription() != null) {
            properties.addProperty(new PropertyStringImpl(PropertyIds.DESCRIPTION, request.getDescription()));
        }
        
        if (request.getPolicyText() != null) {
            properties.addProperty(new PropertyStringImpl(PropertyIds.POLICY_TEXT, request.getPolicyText()));
        }
        
        if (request.getProperties() != null) {
            for (Map.Entry<String, PropertyValue> entry : request.getProperties().entrySet()) {
                String propertyId = entry.getKey();
                PropertyValue pv = entry.getValue();
                if (pv != null && pv.getValue() != null) {
                    properties.addProperty(new PropertyStringImpl(propertyId, pv.getValue().toString()));
                }
            }
        }
        
        return properties;
    }
    
    private PolicyListResponse mapToPolicyListResponse(List<ObjectData> policies, String repositoryId, String objectId) {
        PolicyListResponse response = new PolicyListResponse();
        response.setObjectId(objectId);
        
        List<ObjectResponse> items = new ArrayList<>();
        if (policies != null) {
            for (ObjectData policy : policies) {
                items.add(mapToObjectResponse(policy, repositoryId));
            }
        }
        response.setPolicies(items);
        response.setNumPolicies(items.size());
        
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/policies/object/" + objectId));
        links.put("object", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId));
        response.setLinks(links);
        
        return response;
    }
    
    private ObjectResponse mapToObjectResponse(ObjectData objectData, String repositoryId) {
        ObjectResponse response = new ObjectResponse();
        
        Properties properties = objectData.getProperties();
        if (properties != null) {
            response.setObjectId(getStringProperty(properties, PropertyIds.OBJECT_ID));
            response.setObjectTypeId(getStringProperty(properties, PropertyIds.OBJECT_TYPE_ID));
            response.setBaseTypeId(getStringProperty(properties, PropertyIds.BASE_TYPE_ID));
            response.setName(getStringProperty(properties, PropertyIds.NAME));
            response.setDescription(getStringProperty(properties, PropertyIds.DESCRIPTION));
            response.setCreatedBy(getStringProperty(properties, PropertyIds.CREATED_BY));
            response.setCreationDate(getDateProperty(properties, PropertyIds.CREATION_DATE));
            response.setLastModifiedBy(getStringProperty(properties, PropertyIds.LAST_MODIFIED_BY));
            response.setLastModificationDate(getDateProperty(properties, PropertyIds.LAST_MODIFICATION_DATE));
            
            Map<String, PropertyValue> propertyMap = new HashMap<>();
            for (PropertyData<?> prop : properties.getPropertyList()) {
                propertyMap.put(prop.getId(), mapPropertyValue(prop));
            }
            response.setProperties(propertyMap);
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        String objectId = response.getObjectId();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId));
        response.setLinks(links);
        
        return response;
    }
    
    private String getStringProperty(Properties properties, String propertyId) {
        PropertyData<?> prop = properties.getProperties().get(propertyId);
        if (prop != null && prop.getFirstValue() != null) {
            return prop.getFirstValue().toString();
        }
        return null;
    }
    
    private String getDateProperty(Properties properties, String propertyId) {
        PropertyData<?> prop = properties.getProperties().get(propertyId);
        if (prop != null && prop.getFirstValue() instanceof GregorianCalendar) {
            GregorianCalendar cal = (GregorianCalendar) prop.getFirstValue();
            synchronized (ISO_DATE_FORMAT) {
                return ISO_DATE_FORMAT.format(cal.getTime());
            }
        }
        return null;
    }
    
    private PropertyValue mapPropertyValue(PropertyData<?> prop) {
        Object value = prop.getValues() != null && prop.getValues().size() > 1 
                ? prop.getValues() 
                : prop.getFirstValue();
        
        String type = "string";
        if (prop.getFirstValue() instanceof Boolean) {
            type = "boolean";
        } else if (prop.getFirstValue() instanceof Long || prop.getFirstValue() instanceof Integer || prop.getFirstValue() instanceof BigInteger) {
            type = "integer";
        } else if (prop.getFirstValue() instanceof Double || prop.getFirstValue() instanceof Float || prop.getFirstValue() instanceof BigDecimal) {
            type = "decimal";
        } else if (prop.getFirstValue() instanceof GregorianCalendar) {
            GregorianCalendar cal = (GregorianCalendar) prop.getFirstValue();
            synchronized (ISO_DATE_FORMAT) {
                value = ISO_DATE_FORMAT.format(cal.getTime());
            }
            type = "datetime";
        }
        
        PropertyValue pv = new PropertyValue(value, type);
        pv.setPropertyDefinitionId(prop.getId());
        pv.setLocalName(prop.getLocalName());
        pv.setDisplayName(prop.getDisplayName());
        pv.setQueryName(prop.getQueryName());
        
        return pv;
    }
}
