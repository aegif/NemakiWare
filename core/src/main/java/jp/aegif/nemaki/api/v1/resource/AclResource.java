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
import jakarta.ws.rs.PUT;
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
import jp.aegif.nemaki.api.v1.model.request.AclRequest;
import jp.aegif.nemaki.api.v1.model.response.AclResponse;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/objects/{objectId}/acl")
@Tag(name = "acl", description = "CMIS ACL operations")
@Produces(MediaType.APPLICATION_JSON)
public class AclResource {
    
    private static final Logger logger = Logger.getLogger(AclResource.class.getName());
    
    @Autowired
    private AclService aclService;
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @GET
    @Operation(
            summary = "Get ACL",
            description = "Gets the ACL currently applied to the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "ACL information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AclResponse.class)
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
    public Response getAcl(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Only return basic permissions")
            @QueryParam("onlyBasicPermissions") Boolean onlyBasicPermissions) {
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Acl acl = aclService.getAcl(callContext, repositoryId, objectId, onlyBasicPermissions, null);
            
            if (acl == null) {
                throw ApiException.objectNotFound(objectId, repositoryId);
            }
            
            AclResponse response = mapToAclResponse(acl, repositoryId, objectId);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting ACL for object " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get ACL: " + e.getMessage(), e);
        }
    }
    
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Apply ACL",
            description = "Applies a new ACL to the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Updated ACL information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AclResponse.class)
                    )
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
                    description = "Object not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response applyAcl(
            @Parameter(description = "Repository ID", required = true)
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "ACL to apply", required = true)
            AclRequest aclRequest) {
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            if (aclRequest == null || aclRequest.getAces() == null || aclRequest.getAces().isEmpty()) {
                throw ApiException.invalidArgument("ACL entries (aces) are required");
            }
            
            Acl acesToApply = convertToAcl(aclRequest);
            AclPropagation propagation = parseAclPropagation(aclRequest.getAclPropagation());
            
            Acl updatedAcl = aclService.applyAcl(callContext, repositoryId, objectId, acesToApply, propagation);
            
            AclResponse response = mapToAclResponse(updatedAcl, repositoryId, objectId);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error applying ACL to object " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to apply ACL: " + e.getMessage(), e);
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
    
    private AclResponse mapToAclResponse(Acl acl, String repositoryId, String objectId) {
        AclResponse response = new AclResponse();
        response.setObjectId(objectId);
        response.setExact(acl.isExact());
        
        List<AclResponse.AceEntry> aces = new ArrayList<>();
        if (acl.getAces() != null) {
            for (Ace ace : acl.getAces()) {
                AclResponse.AceEntry entry = new AclResponse.AceEntry();
                entry.setPrincipalId(ace.getPrincipalId());
                entry.setPermissions(ace.getPermissions());
                entry.setDirect(ace.isDirect());
                aces.add(entry);
            }
        }
        response.setAces(aces);
        
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId + "/acl"));
        links.put("object", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId));
        response.setLinks(links);
        
        return response;
    }
    
    private Acl convertToAcl(AclRequest request) {
        AccessControlListImpl acl = new AccessControlListImpl();
        List<Ace> aces = new ArrayList<>();
        
        for (AclRequest.AceEntry entry : request.getAces()) {
            if (entry.getPrincipalId() == null || entry.getPrincipalId().isEmpty()) {
                throw ApiException.invalidArgument("Principal ID is required for each ACE");
            }
            if (entry.getPermissions() == null || entry.getPermissions().isEmpty()) {
                throw ApiException.invalidArgument("Permissions are required for each ACE");
            }
            
            AccessControlPrincipalDataImpl principal = new AccessControlPrincipalDataImpl(entry.getPrincipalId());
            AccessControlEntryImpl ace = new AccessControlEntryImpl(principal, entry.getPermissions());
            aces.add(ace);
        }
        
        acl.setAces(aces);
        return acl;
    }
    
    private AclPropagation parseAclPropagation(String propagation) {
        if (propagation == null || propagation.isEmpty()) {
            return AclPropagation.REPOSITORYDETERMINED;
        }
        try {
            return AclPropagation.valueOf(propagation.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidArgument("Invalid ACL propagation value: " + propagation + 
                    ". Valid values are: REPOSITORYDETERMINED, OBJECTONLY, PROPAGATE");
        }
    }
}
