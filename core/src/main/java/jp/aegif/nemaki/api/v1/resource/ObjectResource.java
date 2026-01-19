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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.PropertyValue;
import jp.aegif.nemaki.api.v1.model.response.AllowableActionsResponse;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.api.v1.model.response.ObjectListResponse;
import jp.aegif.nemaki.api.v1.model.response.ObjectResponse;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
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

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/objects")
@Tag(name = "objects", description = "CMIS object operations")
@Produces(MediaType.APPLICATION_JSON)
public class ObjectResource {
    
    private static final Logger logger = Logger.getLogger(ObjectResource.class.getName());
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
    
    @GET
    @Path("/{objectId}")
    @Operation(
            summary = "Get object",
            description = "Gets the specified information for the object specified by ID"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Object information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
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
                    description = "Object not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getObject(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Include ACL")
            @QueryParam("includeAcl") @DefaultValue("false") Boolean includeAcl) {
        
        logger.info("API v1: Getting object " + objectId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ObjectData objectData = objectService.getObject(
                    callContext, repositoryId, objectId, filter,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, false, includeAcl, null);
            
            if (objectData == null) {
                throw ApiException.objectNotFound(objectId, repositoryId);
            }
            
            ObjectResponse response = mapToObjectResponse(objectData, repositoryId, includeAllowableActions);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting object " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get object: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/byPath")
    @Operation(
            summary = "Get object by path",
            description = "Gets the specified information for the object specified by path"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Object information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
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
    public Response getObjectByPath(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object path", required = true, example = "/invoices/2026/invoice-001.pdf")
            @QueryParam("path") String path,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Include ACL")
            @QueryParam("includeAcl") @DefaultValue("false") Boolean includeAcl) {
        
        logger.info("API v1: Getting object by path " + path + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            
            if (path == null || path.isEmpty()) {
                throw ApiException.invalidArgument("Path parameter is required");
            }
            
            CallContext callContext = getCallContext();
            
            ObjectData objectData = objectService.getObjectByPath(
                    callContext, repositoryId, path, filter,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, false, includeAcl, null);
            
            if (objectData == null) {
                throw ApiException.objectNotFound("path:" + path, repositoryId);
            }
            
            ObjectResponse response = mapToObjectResponse(objectData, repositoryId, includeAllowableActions);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting object by path " + path + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get object by path: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{objectId}")
    @Operation(
            summary = "Delete object",
            description = "Deletes the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Object deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Object not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Permission denied",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteObject(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Delete all versions")
            @QueryParam("allVersions") @DefaultValue("true") Boolean allVersions) {
        
        logger.info("API v1: Deleting object " + objectId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            objectService.deleteObject(callContext, repositoryId, objectId, allVersions, null);
            
            return Response.noContent().build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting object " + objectId + ": " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                throw ApiException.objectNotFound(objectId, repositoryId);
            }
            throw ApiException.internalError("Failed to delete object: " + e.getMessage(), e);
        }
    }
    
    @PUT
    @Path("/{objectId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Update object properties",
            description = "Updates the properties of the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Object updated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
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
    public Response updateProperties(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Change token for optimistic locking")
            @QueryParam("changeToken") String changeToken,
            Map<String, PropertyValue> properties) {
        
        logger.info("API v1: Updating properties for object " + objectId + " in repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Properties cmisProperties = convertToProperties(properties);
            Holder<String> objectIdHolder = new Holder<>(objectId);
            Holder<String> changeTokenHolder = changeToken != null ? new Holder<>(changeToken) : null;
            
            objectService.updateProperties(callContext, repositoryId, objectIdHolder, cmisProperties, changeTokenHolder, null);
            
            ObjectData updatedObject = objectService.getObject(
                    callContext, repositoryId, objectIdHolder.getValue(), null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            ObjectResponse response = mapToObjectResponse(updatedObject, repositoryId, true);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error updating object " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to update object: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/{objectId}/move")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Move object",
            description = "Moves the specified object from one folder to another"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Object moved successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Object or folder not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response moveObject(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Source folder ID", required = true)
            @QueryParam("sourceFolderId") String sourceFolderId,
            @Parameter(description = "Target folder ID", required = true)
            @QueryParam("targetFolderId") String targetFolderId) {
        
        logger.info("API v1: Moving object " + objectId + " from " + sourceFolderId + " to " + targetFolderId);
        
        try {
            validateRepository(repositoryId);
            
            if (sourceFolderId == null || sourceFolderId.isEmpty()) {
                throw ApiException.invalidArgument("sourceFolderId is required");
            }
            if (targetFolderId == null || targetFolderId.isEmpty()) {
                throw ApiException.invalidArgument("targetFolderId is required");
            }
            
            CallContext callContext = getCallContext();
            Holder<String> objectIdHolder = new Holder<>(objectId);
            
            objectService.moveObject(callContext, repositoryId, objectIdHolder, sourceFolderId, targetFolderId, null);
            
            ObjectData movedObject = objectService.getObject(
                    callContext, repositoryId, objectIdHolder.getValue(), null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            ObjectResponse response = mapToObjectResponse(movedObject, repositoryId, true);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error moving object " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to move object: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{objectId}/allowableActions")
    @Operation(
            summary = "Get allowable actions",
            description = "Gets the list of allowable actions for the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Allowable actions",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = AllowableActionsResponse.class)
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
    public Response getAllowableActions(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId) {
        
        logger.info("API v1: Getting allowable actions for object " + objectId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            AllowableActions allowableActions = objectService.getAllowableActions(callContext, repositoryId, objectId);
            
            if (allowableActions == null) {
                throw ApiException.objectNotFound(objectId, repositoryId);
            }
            
            AllowableActionsResponse response = mapAllowableActions(allowableActions);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting allowable actions for " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get allowable actions: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{objectId}/content")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(
            summary = "Get content stream",
            description = "Gets the content stream for the specified document object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Content stream",
                    content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Object or content not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getContentStream(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Stream ID for renditions")
            @QueryParam("streamId") String streamId,
            @Parameter(description = "Offset in bytes")
            @QueryParam("offset") BigInteger offset,
            @Parameter(description = "Length in bytes")
            @QueryParam("length") BigInteger length) {
        
        logger.info("API v1: Getting content stream for object " + objectId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ContentStream contentStream = objectService.getContentStream(
                    callContext, repositoryId, objectId, streamId, offset, length);
            
            if (contentStream == null) {
                throw ApiException.objectNotFound(objectId + " (content)", repositoryId);
            }
            
            StreamingOutput streamingOutput = output -> {
                try (InputStream input = contentStream.getStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                    }
                }
            };
            
            Response.ResponseBuilder responseBuilder = Response.ok(streamingOutput);
            
            if (contentStream.getMimeType() != null) {
                responseBuilder.type(contentStream.getMimeType());
            }
            if (contentStream.getFileName() != null) {
                responseBuilder.header("Content-Disposition", 
                        "attachment; filename=\"" + contentStream.getFileName() + "\"");
            }
                        if (contentStream.getLength() >= 0) {
                            responseBuilder.header("Content-Length", contentStream.getLength());
                        }
            
            return responseBuilder.build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting content stream for " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get content stream: " + e.getMessage(), e);
        }
    }
    
    @PUT
    @Path("/{objectId}/content")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(
            summary = "Set content stream",
            description = "Sets the content stream for the specified document object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Content stream set successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
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
    public Response setContentStream(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Overwrite existing content")
            @QueryParam("overwriteFlag") @DefaultValue("true") Boolean overwriteFlag,
            @Parameter(description = "Change token for optimistic locking")
            @QueryParam("changeToken") String changeToken,
            @Parameter(description = "Content MIME type")
            @QueryParam("mimeType") @DefaultValue("application/octet-stream") String mimeType,
            @Parameter(description = "File name")
            @QueryParam("fileName") String fileName,
            InputStream contentInputStream) {
        
        logger.info("API v1: Setting content stream for object " + objectId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ContentStream contentStream = new ContentStreamImpl(fileName, null, mimeType, contentInputStream);
            
            Holder<String> objectIdHolder = new Holder<>(objectId);
            Holder<String> changeTokenHolder = changeToken != null ? new Holder<>(changeToken) : null;
            
            objectService.setContentStream(callContext, repositoryId, objectIdHolder, 
                    overwriteFlag, contentStream, changeTokenHolder, null);
            
            ObjectData updatedObject = objectService.getObject(
                    callContext, repositoryId, objectIdHolder.getValue(), null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            ObjectResponse response = mapToObjectResponse(updatedObject, repositoryId, true);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error setting content stream for " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to set content stream: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{objectId}/content")
    @Operation(
            summary = "Delete content stream",
            description = "Deletes the content stream for the specified document object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Content stream deleted successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
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
    public Response deleteContentStream(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Change token for optimistic locking")
            @QueryParam("changeToken") String changeToken) {
        
        logger.info("API v1: Deleting content stream for object " + objectId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Holder<String> objectIdHolder = new Holder<>(objectId);
            Holder<String> changeTokenHolder = changeToken != null ? new Holder<>(changeToken) : null;
            
            objectService.deleteContentStream(callContext, repositoryId, objectIdHolder, changeTokenHolder, null);
            
            ObjectData updatedObject = objectService.getObject(
                    callContext, repositoryId, objectIdHolder.getValue(), null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            ObjectResponse response = mapToObjectResponse(updatedObject, repositoryId, true);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting content stream for " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to delete content stream: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{objectId}/parents")
    @Operation(
            summary = "Get object parents",
            description = "Gets the parent folder(s) for the specified non-folder, fileable object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of parent folders",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectListResponse.class)
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
    public Response getObjectParents(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions) {
        
        logger.info("API v1: Getting parents for object " + objectId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            List<ObjectParentData> parents = navigationService.getObjectParents(
                    callContext, repositoryId, objectId, filter,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, true, null);
            
            List<ObjectResponse> parentResponses = new ArrayList<>();
            if (parents != null) {
                for (ObjectParentData parent : parents) {
                    if (parent.getObject() != null) {
                        parentResponses.add(mapToObjectResponse(parent.getObject(), repositoryId, includeAllowableActions));
                    }
                }
            }
            
            ObjectListResponse response = new ObjectListResponse();
            response.setObjects(parentResponses);
            response.setNumItems((long) parentResponses.size());
            response.setHasMoreItems(false);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting parents for " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get object parents: " + e.getMessage(), e);
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
            
            response.setIsLatestVersion(getBooleanProperty(properties, PropertyIds.IS_LATEST_VERSION));
            response.setIsLatestMajorVersion(getBooleanProperty(properties, PropertyIds.IS_LATEST_MAJOR_VERSION));
            response.setIsMajorVersion(getBooleanProperty(properties, PropertyIds.IS_MAJOR_VERSION));
            response.setVersionLabel(getStringProperty(properties, PropertyIds.VERSION_LABEL));
            response.setVersionSeriesId(getStringProperty(properties, PropertyIds.VERSION_SERIES_ID));
            response.setIsVersionSeriesCheckedOut(getBooleanProperty(properties, PropertyIds.IS_VERSION_SERIES_CHECKED_OUT));
            response.setVersionSeriesCheckedOutBy(getStringProperty(properties, PropertyIds.VERSION_SERIES_CHECKED_OUT_BY));
            response.setVersionSeriesCheckedOutId(getStringProperty(properties, PropertyIds.VERSION_SERIES_CHECKED_OUT_ID));
            response.setCheckinComment(getStringProperty(properties, PropertyIds.CHECKIN_COMMENT));
            
            response.setContentStreamLength(getLongProperty(properties, PropertyIds.CONTENT_STREAM_LENGTH));
            response.setContentStreamMimeType(getStringProperty(properties, PropertyIds.CONTENT_STREAM_MIME_TYPE));
            response.setContentStreamFileName(getStringProperty(properties, PropertyIds.CONTENT_STREAM_FILE_NAME));
            response.setContentStreamId(getStringProperty(properties, PropertyIds.CONTENT_STREAM_ID));
            
            response.setIsImmutable(getBooleanProperty(properties, PropertyIds.IS_IMMUTABLE));
            response.setSecondaryTypeIds(getMultiStringProperty(properties, PropertyIds.SECONDARY_OBJECT_TYPE_IDS));
            
            Map<String, PropertyValue> propertyMap = new HashMap<>();
            for (PropertyData<?> prop : properties.getPropertyList()) {
                propertyMap.put(prop.getId(), mapPropertyValue(prop));
            }
            response.setProperties(propertyMap);
        }
        
        if (includeAllowableActions != null && includeAllowableActions && objectData.getAllowableActions() != null) {
            response.setAllowableActions(mapAllowableActions(objectData.getAllowableActions()));
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        String objectId = response.getObjectId();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId));
        links.put("allowableActions", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId + "/allowableActions"));
        links.put("parents", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId + "/parents"));
        
        if ("cmis:document".equals(response.getBaseTypeId())) {
            links.put("content", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId + "/content"));
            links.put("versions", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + objectId + "/versions"));
        }
        if ("cmis:folder".equals(response.getBaseTypeId())) {
            links.put("children", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + objectId + "/children"));
            links.put("descendants", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/folders/" + objectId + "/descendants"));
        }
        
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
    
    private Boolean getBooleanProperty(Properties properties, String propertyId) {
        PropertyData<?> prop = properties.getProperties().get(propertyId);
        if (prop != null && prop.getFirstValue() instanceof Boolean) {
            return (Boolean) prop.getFirstValue();
        }
        return null;
    }
    
    private Long getLongProperty(Properties properties, String propertyId) {
        PropertyData<?> prop = properties.getProperties().get(propertyId);
        if (prop != null && prop.getFirstValue() != null) {
            Object value = prop.getFirstValue();
            if (value instanceof Long) {
                return (Long) value;
            } else if (value instanceof BigInteger) {
                return ((BigInteger) value).longValue();
            } else if (value instanceof Number) {
                return ((Number) value).longValue();
            }
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
    
    @SuppressWarnings("unchecked")
    private List<String> getMultiStringProperty(Properties properties, String propertyId) {
        PropertyData<?> prop = properties.getProperties().get(propertyId);
        if (prop != null && prop.getValues() != null) {
            List<String> result = new ArrayList<>();
            for (Object value : prop.getValues()) {
                if (value != null) {
                    result.add(value.toString());
                }
            }
            return result.isEmpty() ? null : result;
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
    
    private AllowableActionsResponse mapAllowableActions(AllowableActions allowableActions) {
        AllowableActionsResponse response = new AllowableActionsResponse();
        Set<Action> actions = allowableActions.getAllowableActions();
        if (actions == null) {
            actions = Collections.emptySet();
        }
        
        response.setCanDeleteObject(actions.contains(Action.CAN_DELETE_OBJECT));
        response.setCanUpdateProperties(actions.contains(Action.CAN_UPDATE_PROPERTIES));
        response.setCanGetFolderTree(actions.contains(Action.CAN_GET_FOLDER_TREE));
        response.setCanGetProperties(actions.contains(Action.CAN_GET_PROPERTIES));
        response.setCanGetObjectRelationships(actions.contains(Action.CAN_GET_OBJECT_RELATIONSHIPS));
        response.setCanGetObjectParents(actions.contains(Action.CAN_GET_OBJECT_PARENTS));
        response.setCanGetFolderParent(actions.contains(Action.CAN_GET_FOLDER_PARENT));
        response.setCanGetDescendants(actions.contains(Action.CAN_GET_DESCENDANTS));
        response.setCanMoveObject(actions.contains(Action.CAN_MOVE_OBJECT));
        response.setCanDeleteContentStream(actions.contains(Action.CAN_DELETE_CONTENT_STREAM));
        response.setCanCheckOut(actions.contains(Action.CAN_CHECK_OUT));
        response.setCanCancelCheckOut(actions.contains(Action.CAN_CANCEL_CHECK_OUT));
        response.setCanCheckIn(actions.contains(Action.CAN_CHECK_IN));
        response.setCanSetContentStream(actions.contains(Action.CAN_SET_CONTENT_STREAM));
        response.setCanGetAllVersions(actions.contains(Action.CAN_GET_ALL_VERSIONS));
        response.setCanAddObjectToFolder(actions.contains(Action.CAN_ADD_OBJECT_TO_FOLDER));
        response.setCanRemoveObjectFromFolder(actions.contains(Action.CAN_REMOVE_OBJECT_FROM_FOLDER));
        response.setCanGetContentStream(actions.contains(Action.CAN_GET_CONTENT_STREAM));
        response.setCanApplyPolicy(actions.contains(Action.CAN_APPLY_POLICY));
        response.setCanGetAppliedPolicies(actions.contains(Action.CAN_GET_APPLIED_POLICIES));
        response.setCanRemovePolicy(actions.contains(Action.CAN_REMOVE_POLICY));
        response.setCanGetChildren(actions.contains(Action.CAN_GET_CHILDREN));
        response.setCanCreateDocument(actions.contains(Action.CAN_CREATE_DOCUMENT));
        response.setCanCreateFolder(actions.contains(Action.CAN_CREATE_FOLDER));
        response.setCanCreateRelationship(actions.contains(Action.CAN_CREATE_RELATIONSHIP));
        response.setCanDeleteTree(actions.contains(Action.CAN_DELETE_TREE));
        response.setCanGetRenditions(actions.contains(Action.CAN_GET_RENDITIONS));
        response.setCanGetAcl(actions.contains(Action.CAN_GET_ACL));
        response.setCanApplyAcl(actions.contains(Action.CAN_APPLY_ACL));
        
        return response;
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
            // Synchronize on ISO_DATE_FORMAT since SimpleDateFormat is not thread-safe
            synchronized (ISO_DATE_FORMAT) {
                calendar.setTime(ISO_DATE_FORMAT.parse(dateStr));
            }
            return calendar;
        } catch (ParseException e) {
            logger.warning("Failed to parse datetime: " + dateStr);
            throw ApiException.invalidArgument("Invalid datetime format: " + dateStr + ". Expected ISO 8601 format (e.g., 2024-01-15T10:30:00Z)");
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
