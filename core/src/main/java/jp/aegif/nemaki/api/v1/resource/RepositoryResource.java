package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.RepositoryInfoResponse;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories")
@Tag(name = "repositories", description = "Repository management operations")
@Produces(MediaType.APPLICATION_JSON)
public class RepositoryResource {
    
    private static final Logger logger = Logger.getLogger(RepositoryResource.class.getName());
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Context
    private UriInfo uriInfo;
    
    @GET
    @Operation(
            summary = "List all repositories",
            description = "Returns a list of all available repositories with their basic information"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of repositories",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            array = @ArraySchema(schema = @Schema(implementation = RepositoryInfoResponse.class))
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response listRepositories() {
        logger.info("API v1: Listing all repositories");
        
        try {
            List<RepositoryInfo> repositoryInfos = repositoryService.getRepositoryInfos();
            
            List<RepositoryInfoResponse> responses = new ArrayList<>();
            for (RepositoryInfo repoInfo : repositoryInfos) {
                responses.add(mapToResponse(repoInfo, false));
            }
            
            return Response.ok(responses).build();
        } catch (Exception e) {
            logger.severe("Error listing repositories: " + e.getMessage());
            throw ApiException.internalError("Failed to list repositories: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{repositoryId}")
    @Operation(
            summary = "Get repository information",
            description = "Returns detailed information about a specific repository including capabilities"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Repository information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RepositoryInfoResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Repository not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getRepository(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Getting repository info for: " + repositoryId);
        
        try {
            if (!repositoryService.hasThisRepositoryId(repositoryId)) {
                throw ApiException.repositoryNotFound(repositoryId);
            }
            
            jp.aegif.nemaki.cmis.factory.info.RepositoryInfo repoInfo = 
                    repositoryService.getRepositoryInfo(repositoryId);
            
            if (repoInfo == null) {
                throw ApiException.repositoryNotFound(repositoryId);
            }
            
            RepositoryInfoResponse response = mapToResponse(repoInfo, true);
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting repository " + repositoryId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get repository: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{repositoryId}/capabilities")
    @Operation(
            summary = "Get repository capabilities",
            description = "Returns the capabilities of a specific repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Repository capabilities",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = jp.aegif.nemaki.api.v1.model.response.RepositoryCapabilities.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Repository not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getRepositoryCapabilities(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Getting capabilities for repository: " + repositoryId);
        
        try {
            if (!repositoryService.hasThisRepositoryId(repositoryId)) {
                throw ApiException.repositoryNotFound(repositoryId);
            }
            
            jp.aegif.nemaki.cmis.factory.info.RepositoryInfo repoInfo = 
                    repositoryService.getRepositoryInfo(repositoryId);
            
            if (repoInfo == null) {
                throw ApiException.repositoryNotFound(repositoryId);
            }
            
            jp.aegif.nemaki.api.v1.model.response.RepositoryCapabilities capabilities = 
                    mapCapabilities(repoInfo.getCapabilities());
            
            return Response.ok(capabilities).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting capabilities for " + repositoryId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get repository capabilities: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{repositoryId}/rootFolder")
    @Operation(
            summary = "Get root folder information",
            description = "Returns information about the root folder of the repository"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Root folder ID and link",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Repository not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getRootFolder(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        logger.info("API v1: Getting root folder for repository: " + repositoryId);
        
        try {
            if (!repositoryService.hasThisRepositoryId(repositoryId)) {
                throw ApiException.repositoryNotFound(repositoryId);
            }
            
            jp.aegif.nemaki.cmis.factory.info.RepositoryInfo repoInfo = 
                    repositoryService.getRepositoryInfo(repositoryId);
            
            if (repoInfo == null) {
                throw ApiException.repositoryNotFound(repositoryId);
            }
            
            String rootFolderId = repoInfo.getRootFolderId();
            String baseUri = uriInfo.getBaseUri().toString();
            
            Map<String, Object> response = new HashMap<>();
            response.put("rootFolderId", rootFolderId);
            response.put("_links", Map.of(
                    "self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/rootFolder"),
                    "folder", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + rootFolderId),
                    "children", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + rootFolderId + "/children")
            ));
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting root folder for " + repositoryId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get root folder: " + e.getMessage(), e);
        }
    }
    
    private RepositoryInfoResponse mapToResponse(RepositoryInfo repoInfo, boolean includeDetails) {
        RepositoryInfoResponse response = new RepositoryInfoResponse();
        
        response.setRepositoryId(repoInfo.getId());
        response.setRepositoryName(repoInfo.getName());
        response.setRepositoryDescription(repoInfo.getDescription());
        response.setVendorName(repoInfo.getVendorName());
        response.setProductName(repoInfo.getProductName());
        response.setProductVersion(repoInfo.getProductVersion());
        response.setRootFolderId(repoInfo.getRootFolderId());
        response.setCmisVersionSupported(repoInfo.getCmisVersionSupported());
        response.setPrincipalIdAnonymous(repoInfo.getPrincipalIdAnonymous());
        response.setPrincipalIdAnyone(repoInfo.getPrincipalIdAnyone());
        response.setThinClientUri(repoInfo.getThinClientUri());
        response.setLatestChangeLogToken(repoInfo.getLatestChangeLogToken());
        
        if (includeDetails) {
            if (repoInfo.getCapabilities() != null) {
                response.setCapabilities(mapCapabilities(repoInfo.getCapabilities()));
            }
            if (repoInfo.getAclCapabilities() != null) {
                response.setAclCapabilities(mapAclCapabilities(repoInfo.getAclCapabilities()));
            }
        }
        
        // Add HATEOAS links
        String baseUri = uriInfo.getBaseUri().toString();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repoInfo.getId()));
        links.put("rootFolder", LinkInfo.of(baseUri + "repositories/" + repoInfo.getId() + "/rootFolder"));
        links.put("types", LinkInfo.of(baseUri + "repositories/" + repoInfo.getId() + "/types"));
        links.put("objects", LinkInfo.of(baseUri + "repositories/" + repoInfo.getId() + "/objects"));
        links.put("query", LinkInfo.of(baseUri + "repositories/" + repoInfo.getId() + "/query"));
        links.put("users", LinkInfo.of(baseUri + "repositories/" + repoInfo.getId() + "/users"));
        links.put("groups", LinkInfo.of(baseUri + "repositories/" + repoInfo.getId() + "/groups"));
        response.setLinks(links);
        
        return response;
    }
    
    private jp.aegif.nemaki.api.v1.model.response.RepositoryCapabilities mapCapabilities(
            org.apache.chemistry.opencmis.commons.data.RepositoryCapabilities caps) {
        if (caps == null) return null;
        
        jp.aegif.nemaki.api.v1.model.response.RepositoryCapabilities response = 
                new jp.aegif.nemaki.api.v1.model.response.RepositoryCapabilities();
        
        if (caps.getAclCapability() != null) {
            response.setCapabilityAcl(caps.getAclCapability().value());
        }
        response.setCapabilityAllVersionsSearchable(caps.isAllVersionsSearchableSupported());
        if (caps.getChangesCapability() != null) {
            response.setCapabilityChanges(caps.getChangesCapability().value());
        }
        if (caps.getContentStreamUpdatesCapability() != null) {
            response.setCapabilityContentStreamUpdatability(caps.getContentStreamUpdatesCapability().value());
        }
        response.setCapabilityGetDescendants(caps.isGetDescendantsSupported());
        response.setCapabilityGetFolderTree(caps.isGetFolderTreeSupported());
        if (caps.getOrderByCapability() != null) {
            response.setCapabilityOrderBy(caps.getOrderByCapability().value());
        }
        response.setCapabilityMultifiling(caps.isMultifilingSupported());
        response.setCapabilityPwcSearchable(caps.isPwcSearchableSupported());
        response.setCapabilityPwcUpdatable(caps.isPwcUpdatableSupported());
        if (caps.getQueryCapability() != null) {
            response.setCapabilityQuery(caps.getQueryCapability().value());
        }
        if (caps.getRenditionsCapability() != null) {
            response.setCapabilityRenditions(caps.getRenditionsCapability().value());
        }
        response.setCapabilityUnfiling(caps.isUnfilingSupported());
        response.setCapabilityVersionSpecificFiling(caps.isVersionSpecificFilingSupported());
        if (caps.getJoinCapability() != null) {
            response.setCapabilityJoin(caps.getJoinCapability().value());
        }
        
        return response;
    }
    
    private jp.aegif.nemaki.api.v1.model.response.AclCapabilities mapAclCapabilities(
            org.apache.chemistry.opencmis.commons.data.AclCapabilities aclCaps) {
        if (aclCaps == null) return null;
        
        jp.aegif.nemaki.api.v1.model.response.AclCapabilities response = 
                new jp.aegif.nemaki.api.v1.model.response.AclCapabilities();
        
        if (aclCaps.getSupportedPermissions() != null) {
            response.setSupportedPermissions(aclCaps.getSupportedPermissions().value());
        }
        if (aclCaps.getAclPropagation() != null) {
            response.setAclPropagation(aclCaps.getAclPropagation().value());
        }
        
        // Map permissions
        if (aclCaps.getPermissions() != null) {
            List<jp.aegif.nemaki.api.v1.model.response.AclCapabilities.PermissionDefinition> permissions = new ArrayList<>();
            for (PermissionDefinition perm : aclCaps.getPermissions()) {
                permissions.add(new jp.aegif.nemaki.api.v1.model.response.AclCapabilities.PermissionDefinition(
                        perm.getId(), perm.getDescription()));
            }
            response.setPermissions(permissions);
        }
        
        // Map permission mapping
        if (aclCaps.getPermissionMapping() != null) {
            Map<String, List<String>> mapping = new HashMap<>();
            for (PermissionMapping pm : aclCaps.getPermissionMapping().values()) {
                mapping.put(pm.getKey(), pm.getPermissions());
            }
            response.setPermissionMapping(mapping);
        }
        
        return response;
    }
}
