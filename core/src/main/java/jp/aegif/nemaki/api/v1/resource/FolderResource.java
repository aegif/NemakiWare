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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
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
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.ObjectListResponse;
import jp.aegif.nemaki.api.v1.model.response.ObjectResponse;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/folders")
@Tag(name = "folders", description = "Folder-specific operations (convenience API)")
@Produces(MediaType.APPLICATION_JSON)
public class FolderResource {
    
    private static final Logger logger = Logger.getLogger(FolderResource.class.getName());
    private static final SimpleDateFormat ISO_DATE_FORMAT;
    
    static {
        ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    @Autowired
    private ObjectService objectService;
    
    @Autowired
    private NavigationService navigationService;
    
    @Autowired
    private RepositoryService repositoryService;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create folder",
            description = "Creates a new folder in the specified parent folder"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Folder created successfully",
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
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Parent folder not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response createFolder(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Parent folder ID", required = true)
            @QueryParam("parentId") String parentId,
            Map<String, PropertyValue> properties) {
        
        logger.info("API v1: Creating folder in repository " + repositoryId + " under parent " + parentId);
        
        try {
            validateRepository(repositoryId);
            
            if (parentId == null || parentId.isEmpty()) {
                throw ApiException.invalidArgument("parentId is required");
            }
            
            CallContext callContext = getCallContext();
            Properties cmisProperties = convertToProperties(properties);
            
            String folderId = objectService.createFolder(
                    callContext, repositoryId, cmisProperties, parentId,
                    null, null, null, null);
            
            ObjectData createdFolder = objectService.getObject(
                    callContext, repositoryId, folderId, null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            ObjectResponse response = mapToObjectResponse(createdFolder, repositoryId, true);
            
            String baseUri = uriInfo.getBaseUri().toString();
            return Response.created(java.net.URI.create(baseUri + "repositories/" + repositoryId + "/folders/" + folderId))
                    .entity(response)
                    .build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error creating folder: " + e.getMessage());
            throw ApiException.internalError("Failed to create folder: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{folderId}")
    @Operation(
            summary = "Get folder",
            description = "Gets the specified folder information"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Folder information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Folder not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getFolder(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID", required = true)
            @PathParam("folderId") String folderId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions) {
        
        logger.info("API v1: Getting folder " + folderId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ObjectData objectData = objectService.getObject(
                    callContext, repositoryId, folderId, filter,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, false, false, null);
            
            if (objectData == null) {
                throw ApiException.objectNotFound(folderId, repositoryId);
            }
            
            ObjectResponse response = mapToObjectResponse(objectData, repositoryId, includeAllowableActions);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting folder " + folderId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get folder: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{folderId}/children")
    @Operation(
            summary = "Get folder children",
            description = "Gets the list of child objects contained in the specified folder"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of child objects",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Folder not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getChildren(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID", required = true)
            @PathParam("folderId") String folderId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Order by clause", example = "cmis:name ASC")
            @QueryParam("orderBy") String orderBy,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Include path segments")
            @QueryParam("includePathSegments") @DefaultValue("false") Boolean includePathSegments,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount) {
        
        logger.info("API v1: Getting children of folder " + folderId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Holder<ObjectData> parentHolder = new Holder<>();
            ObjectInFolderList children = navigationService.getChildren(
                    callContext, repositoryId, folderId, filter, orderBy,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, includePathSegments,
                    BigInteger.valueOf(maxItems), BigInteger.valueOf(skipCount),
                    parentHolder, null);
            
            List<ObjectResponse> childResponses = new ArrayList<>();
            if (children != null && children.getObjects() != null) {
                for (ObjectInFolderData child : children.getObjects()) {
                    if (child.getObject() != null) {
                        childResponses.add(mapToObjectResponse(child.getObject(), repositoryId, includeAllowableActions));
                    }
                }
            }
            
            ObjectListResponse response = new ObjectListResponse();
            response.setObjects(childResponses);
            response.setNumItems(children != null && children.getNumItems() != null 
                    ? children.getNumItems().longValue() : (long) childResponses.size());
            response.setHasMoreItems(children != null && children.hasMoreItems() != null 
                    ? children.hasMoreItems() : false);
            response.setSkipCount((long) skipCount);
            response.setMaxItems((long) maxItems);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + folderId + "/children?maxItems=" + maxItems + "&skipCount=" + skipCount));
            links.put("folder", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + folderId));
            
            if (response.getHasMoreItems()) {
                int nextSkip = skipCount + maxItems;
                links.put("next", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + folderId + "/children?maxItems=" + maxItems + "&skipCount=" + nextSkip));
            }
            if (skipCount > 0) {
                int prevSkip = Math.max(0, skipCount - maxItems);
                links.put("prev", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + folderId + "/children?maxItems=" + maxItems + "&skipCount=" + prevSkip));
            }
            
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting children of folder " + folderId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get folder children: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{folderId}/descendants")
    @Operation(
            summary = "Get folder descendants",
            description = "Gets the set of descendant objects contained in the specified folder or any of its child folders"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Tree of descendant objects",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Folder not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getDescendants(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID", required = true)
            @PathParam("folderId") String folderId,
            @Parameter(description = "Depth of descendants to return (-1 for all)")
            @QueryParam("depth") @DefaultValue("-1") Integer depth,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Include path segments")
            @QueryParam("includePathSegments") @DefaultValue("false") Boolean includePathSegments) {
        
        logger.info("API v1: Getting descendants of folder " + folderId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Holder<ObjectData> ancestorHolder = new Holder<>();
            List<ObjectInFolderContainer> descendants = navigationService.getDescendants(
                    callContext, repositoryId, folderId, BigInteger.valueOf(depth),
                    filter, includeAllowableActions, IncludeRelationships.NONE,
                    null, includePathSegments, false, ancestorHolder, null);
            
            List<Map<String, Object>> descendantTree = mapDescendants(descendants, repositoryId, includeAllowableActions);
            
            Map<String, Object> response = new HashMap<>();
            response.put("folderId", folderId);
            response.put("depth", depth);
            response.put("descendants", descendantTree);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + folderId + "/descendants?depth=" + depth));
            links.put("folder", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + folderId));
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting descendants of folder " + folderId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get folder descendants: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{folderId}/tree")
    @Operation(
            summary = "Get folder tree",
            description = "Gets the set of descendant folder objects contained in the specified folder"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Tree of descendant folders",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Folder not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getFolderTree(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID", required = true)
            @PathParam("folderId") String folderId,
            @Parameter(description = "Depth of tree to return (-1 for all)")
            @QueryParam("depth") @DefaultValue("-1") Integer depth,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Include path segments")
            @QueryParam("includePathSegments") @DefaultValue("false") Boolean includePathSegments) {
        
        logger.info("API v1: Getting folder tree for " + folderId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Holder<ObjectData> ancestorHolder = new Holder<>();
            List<ObjectInFolderContainer> folderTree = navigationService.getDescendants(
                    callContext, repositoryId, folderId, BigInteger.valueOf(depth),
                    filter, includeAllowableActions, IncludeRelationships.NONE,
                    null, includePathSegments, true, ancestorHolder, null);
            
            List<Map<String, Object>> tree = mapDescendants(folderTree, repositoryId, includeAllowableActions);
            
            Map<String, Object> response = new HashMap<>();
            response.put("folderId", folderId);
            response.put("depth", depth);
            response.put("tree", tree);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + folderId + "/tree?depth=" + depth));
            links.put("folder", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + folderId));
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting folder tree for " + folderId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get folder tree: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{folderId}/parent")
    @Operation(
            summary = "Get folder parent",
            description = "Gets the parent folder object for the specified folder"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Parent folder information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Folder not found or is root folder",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getFolderParent(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID", required = true)
            @PathParam("folderId") String folderId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter) {
        
        logger.info("API v1: Getting parent of folder " + folderId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ObjectData parent = navigationService.getFolderParent(
                    callContext, repositoryId, folderId, filter, null);
            
            if (parent == null) {
                throw ApiException.objectNotFound(folderId + " (parent)", repositoryId);
            }
            
            ObjectResponse response = mapToObjectResponse(parent, repositoryId, false);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting parent of folder " + folderId + ": " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("root")) {
                throw ApiException.invalidArgument("Root folder has no parent");
            }
            throw ApiException.internalError("Failed to get folder parent: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{folderId}/tree")
    @Operation(
            summary = "Delete folder tree",
            description = "Deletes the specified folder and all of its child and descendant objects"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Folder tree deleted (may contain failed deletions)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Folder not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteTree(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID", required = true)
            @PathParam("folderId") String folderId,
            @Parameter(description = "Delete all versions of documents")
            @QueryParam("allVersions") @DefaultValue("true") Boolean allVersions,
            @Parameter(description = "How to handle unfiling (unfile, deletesinglefiled, delete)")
            @QueryParam("unfileObjects") @DefaultValue("delete") String unfileObjects,
            @Parameter(description = "Continue on failure")
            @QueryParam("continueOnFailure") @DefaultValue("false") Boolean continueOnFailure) {
        
        logger.info("API v1: Deleting tree for folder " + folderId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            UnfileObject unfileOption = UnfileObject.DELETE;
            if ("unfile".equalsIgnoreCase(unfileObjects)) {
                unfileOption = UnfileObject.UNFILE;
            } else if ("deletesinglefiled".equalsIgnoreCase(unfileObjects)) {
                unfileOption = UnfileObject.DELETESINGLEFILED;
            }
            
            FailedToDeleteData failedToDelete = objectService.deleteTree(
                    callContext, repositoryId, folderId, allVersions,
                    unfileOption, continueOnFailure, null);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("folderId", folderId);
            
            if (failedToDelete != null && failedToDelete.getIds() != null && !failedToDelete.getIds().isEmpty()) {
                response.put("success", false);
                response.put("failedToDeleteIds", failedToDelete.getIds());
            }
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting tree for folder " + folderId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to delete folder tree: " + e.getMessage(), e);
        }
    }
    
    private void validateRepository(String repositoryId) {
        if (!repositoryService.hasThisRepositoryId(repositoryId)) {
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
    
    private List<Map<String, Object>> mapDescendants(List<ObjectInFolderContainer> containers, 
            String repositoryId, Boolean includeAllowableActions) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        if (containers != null) {
            for (ObjectInFolderContainer container : containers) {
                Map<String, Object> item = new HashMap<>();
                
                if (container.getObject() != null && container.getObject().getObject() != null) {
                    item.put("object", mapToObjectResponse(container.getObject().getObject(), 
                            repositoryId, includeAllowableActions));
                    
                    if (container.getObject().getPathSegment() != null) {
                        item.put("pathSegment", container.getObject().getPathSegment());
                    }
                }
                
                if (container.getChildren() != null && !container.getChildren().isEmpty()) {
                    item.put("children", mapDescendants(container.getChildren(), repositoryId, includeAllowableActions));
                }
                
                result.add(item);
            }
        }
        
        return result;
    }
    
    private ObjectResponse mapToObjectResponse(ObjectData objectData, String repositoryId, Boolean includeAllowableActions) {
        ObjectResponse response = new ObjectResponse();
        
        Properties properties = objectData.getProperties();
        if (properties != null) {
            response.setObjectId(getStringProperty(properties, PropertyIds.OBJECT_ID));
            response.setBaseTypeId(getStringProperty(properties, PropertyIds.BASE_TYPE_ID));
            response.setObjectTypeId(getStringProperty(properties, PropertyIds.OBJECT_TYPE_ID));
            response.setName(getStringProperty(properties, PropertyIds.NAME));
            response.setDescription(getStringProperty(properties, PropertyIds.DESCRIPTION));
            response.setCreatedBy(getStringProperty(properties, PropertyIds.CREATED_BY));
            response.setCreationDate(getDateProperty(properties, PropertyIds.CREATION_DATE));
            response.setLastModifiedBy(getStringProperty(properties, PropertyIds.LAST_MODIFIED_BY));
            response.setLastModificationDate(getDateProperty(properties, PropertyIds.LAST_MODIFICATION_DATE));
            response.setChangeToken(getStringProperty(properties, PropertyIds.CHANGE_TOKEN));
            response.setParentId(getStringProperty(properties, PropertyIds.PARENT_ID));
            response.setPath(getStringProperty(properties, PropertyIds.PATH));
            
            Map<String, PropertyValue> propertyMap = new HashMap<>();
            for (PropertyData<?> prop : properties.getPropertyList()) {
                propertyMap.put(prop.getId(), mapPropertyValue(prop));
            }
            response.setProperties(propertyMap);
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        String objectId = response.getObjectId();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + objectId));
        links.put("children", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + objectId + "/children"));
        links.put("descendants", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + objectId + "/descendants"));
        links.put("tree", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + objectId + "/tree"));
        links.put("parent", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + objectId + "/parent"));
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
        } else if (prop.getFirstValue() instanceof Long || prop.getFirstValue() instanceof Integer) {
            type = "integer";
        } else if (prop.getFirstValue() instanceof Double || prop.getFirstValue() instanceof Float) {
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
    
    private Properties convertToProperties(Map<String, PropertyValue> propertyMap) {
        PropertiesImpl properties = new PropertiesImpl();
        
        if (propertyMap != null) {
            for (Map.Entry<String, PropertyValue> entry : propertyMap.entrySet()) {
                String propertyId = entry.getKey();
                PropertyValue pv = entry.getValue();
                
                if (pv == null || pv.getValue() == null) {
                    continue;
                }
                
                String type = pv.getType() != null ? pv.getType() : "string";
                Object value = pv.getValue();
                boolean isMultiValued = "multi".equals(pv.getCardinality()) || value instanceof List;
                
                switch (type) {
                    case "id":
                        if (isMultiValued) {
                            properties.addProperty(new PropertyIdImpl(propertyId, convertToStringList(value)));
                        } else {
                            properties.addProperty(new PropertyIdImpl(propertyId, value.toString()));
                        }
                        break;
                    case "boolean":
                        if (isMultiValued) {
                            properties.addProperty(new PropertyBooleanImpl(propertyId, convertToBooleanList(value)));
                        } else {
                            properties.addProperty(new PropertyBooleanImpl(propertyId, convertToBoolean(value)));
                        }
                        break;
                    case "integer":
                        if (isMultiValued) {
                            properties.addProperty(new PropertyIntegerImpl(propertyId, convertToBigIntegerList(value)));
                        } else {
                            properties.addProperty(new PropertyIntegerImpl(propertyId, convertToBigInteger(value)));
                        }
                        break;
                    case "decimal":
                        if (isMultiValued) {
                            properties.addProperty(new PropertyDecimalImpl(propertyId, convertToBigDecimalList(value)));
                        } else {
                            properties.addProperty(new PropertyDecimalImpl(propertyId, convertToBigDecimal(value)));
                        }
                        break;
                    case "datetime":
                        if (isMultiValued) {
                            properties.addProperty(new PropertyDateTimeImpl(propertyId, convertToCalendarList(value)));
                        } else {
                            properties.addProperty(new PropertyDateTimeImpl(propertyId, convertToCalendar(value)));
                        }
                        break;
                    case "html":
                        if (isMultiValued) {
                            properties.addProperty(new PropertyHtmlImpl(propertyId, convertToStringList(value)));
                        } else {
                            properties.addProperty(new PropertyHtmlImpl(propertyId, value.toString()));
                        }
                        break;
                    case "uri":
                        if (isMultiValued) {
                            properties.addProperty(new PropertyUriImpl(propertyId, convertToStringList(value)));
                        } else {
                            properties.addProperty(new PropertyUriImpl(propertyId, value.toString()));
                        }
                        break;
                    case "string":
                    default:
                        if (isMultiValued) {
                            properties.addProperty(new PropertyStringImpl(propertyId, convertToStringList(value)));
                        } else {
                            properties.addProperty(new PropertyStringImpl(propertyId, value.toString()));
                        }
                        break;
                }
            }
        }
        
        if (!properties.getProperties().containsKey(PropertyIds.OBJECT_TYPE_ID)) {
            properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
        }
        
        return properties;
    }
    
    @SuppressWarnings("unchecked")
    private List<String> convertToStringList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(item != null ? item.toString() : null);
            }
            return result;
        }
        return List.of(value.toString());
    }
    
    private Boolean convertToBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
    
    @SuppressWarnings("unchecked")
    private List<Boolean> convertToBooleanList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Boolean> result = new ArrayList<>();
            for (Object item : list) {
                result.add(convertToBoolean(item));
            }
            return result;
        }
        return List.of(convertToBoolean(value));
    }
    
    private BigInteger convertToBigInteger(Object value) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        return new BigInteger(value.toString());
    }
    
    @SuppressWarnings("unchecked")
    private List<BigInteger> convertToBigIntegerList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<BigInteger> result = new ArrayList<>();
            for (Object item : list) {
                result.add(convertToBigInteger(item));
            }
            return result;
        }
        return List.of(convertToBigInteger(value));
    }
    
    private BigDecimal convertToBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return new BigDecimal(value.toString());
    }
    
    @SuppressWarnings("unchecked")
    private List<BigDecimal> convertToBigDecimalList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<BigDecimal> result = new ArrayList<>();
            for (Object item : list) {
                result.add(convertToBigDecimal(item));
            }
            return result;
        }
        return List.of(convertToBigDecimal(value));
    }
    
    private GregorianCalendar convertToCalendar(Object value) {
        if (value instanceof GregorianCalendar) {
            return (GregorianCalendar) value;
        }
        String dateStr = value.toString();
        try {
            GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            calendar.setTime(ISO_DATE_FORMAT.parse(dateStr));
            return calendar;
        } catch (ParseException e) {
            logger.warning("Failed to parse datetime: " + dateStr + ", using current time");
            return new GregorianCalendar();
        }
    }
    
    @SuppressWarnings("unchecked")
    private List<GregorianCalendar> convertToCalendarList(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<GregorianCalendar> result = new ArrayList<>();
            for (Object item : list) {
                result.add(convertToCalendar(item));
            }
            return result;
        }
        return List.of(convertToCalendar(value));
    }
}
