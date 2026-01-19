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
import jp.aegif.nemaki.cmis.service.VersioningService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
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
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/documents")
@Tag(name = "documents", description = "Document-specific operations including versioning (convenience API)")
@Produces(MediaType.APPLICATION_JSON)
public class DocumentResource {
    
    private static final Logger logger = Logger.getLogger(DocumentResource.class.getName());
    private static final SimpleDateFormat ISO_DATE_FORMAT;
    
    static {
        ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    @Autowired
    private ObjectService objectService;
    
    @Autowired
    private VersioningService versioningService;
    
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
            summary = "Create document",
            description = "Creates a new document in the specified parent folder"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Document created successfully",
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
    public Response createDocument(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Parent folder ID", required = true)
            @QueryParam("parentId") String parentId,
            @Parameter(description = "Versioning state (none, checkedout, minor, major)")
            @QueryParam("versioningState") @DefaultValue("major") String versioningStateStr,
            Map<String, PropertyValue> properties) {
        
        logger.info("API v1: Creating document in repository " + repositoryId + " under parent " + parentId);
        
        try {
            validateRepository(repositoryId);
            
            if (parentId == null || parentId.isEmpty()) {
                throw ApiException.invalidArgument("parentId is required");
            }
            
            CallContext callContext = getCallContext();
            Properties cmisProperties = convertToProperties(properties, "cmis:document");
            
            VersioningState versioningState = VersioningState.MAJOR;
            if ("none".equalsIgnoreCase(versioningStateStr)) {
                versioningState = VersioningState.NONE;
            } else if ("checkedout".equalsIgnoreCase(versioningStateStr)) {
                versioningState = VersioningState.CHECKEDOUT;
            } else if ("minor".equalsIgnoreCase(versioningStateStr)) {
                versioningState = VersioningState.MINOR;
            }
            
            String documentId = objectService.createDocument(
                    callContext, repositoryId, cmisProperties, parentId,
                    null, versioningState, null, null, null, null);
            
            ObjectData createdDocument = objectService.getObject(
                    callContext, repositoryId, documentId, null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            ObjectResponse response = mapToObjectResponse(createdDocument, repositoryId, true);
            
            String baseUri = uriInfo.getBaseUri().toString();
            return Response.created(java.net.URI.create(baseUri + "repositories/" + repositoryId + "/documents/" + documentId))
                    .entity(response)
                    .build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error creating document: " + e.getMessage());
            throw ApiException.internalError("Failed to create document: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{documentId}")
    @Operation(
            summary = "Get document",
            description = "Gets the specified document information"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Document information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getDocument(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document ID", required = true)
            @PathParam("documentId") String documentId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions) {
        
        logger.info("API v1: Getting document " + documentId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ObjectData objectData = objectService.getObject(
                    callContext, repositoryId, documentId, filter,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, false, false, null);
            
            if (objectData == null) {
                throw ApiException.objectNotFound(documentId, repositoryId);
            }
            
            ObjectResponse response = mapToObjectResponse(objectData, repositoryId, includeAllowableActions);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting document " + documentId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get document: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/{documentId}/checkout")
    @Operation(
            summary = "Check out document",
            description = "Creates a private working copy (PWC) of the document"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Document checked out successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Document already checked out",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response checkOut(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document ID", required = true)
            @PathParam("documentId") String documentId) {
        
        logger.info("API v1: Checking out document " + documentId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Holder<String> objectIdHolder = new Holder<>(documentId);
            Holder<Boolean> contentCopiedHolder = new Holder<>();
            
            versioningService.checkOut(callContext, repositoryId, objectIdHolder, contentCopiedHolder, null);
            
            String pwcId = objectIdHolder.getValue();
            
            ObjectData pwcObject = objectService.getObject(
                    callContext, repositoryId, pwcId, null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            Map<String, Object> response = new HashMap<>();
            response.put("pwcId", pwcId);
            response.put("originalObjectId", documentId);
            response.put("contentCopied", contentCopiedHolder.getValue());
            
            Properties props = pwcObject.getProperties();
            if (props != null) {
                response.put("versionSeriesId", getStringProperty(props, PropertyIds.VERSION_SERIES_ID));
                response.put("versionSeriesCheckedOutId", getStringProperty(props, PropertyIds.VERSION_SERIES_CHECKED_OUT_ID));
                response.put("versionSeriesCheckedOutBy", getStringProperty(props, PropertyIds.VERSION_SERIES_CHECKED_OUT_BY));
                response.put("isVersionSeriesCheckedOut", getBooleanProperty(props, PropertyIds.IS_VERSION_SERIES_CHECKED_OUT));
            }
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("pwc", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + pwcId));
            links.put("original", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + documentId));
            links.put("checkin", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + pwcId + "/checkin"));
            links.put("cancelCheckout", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + pwcId + "/cancelCheckout"));
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (CmisUpdateConflictException e) {
            logger.warning("Document already checked out: " + documentId);
            throw ApiException.conflict("Document is already checked out");
        } catch (CmisVersioningException e) {
            logger.warning("Versioning error checking out document " + documentId + ": " + e.getMessage());
            throw ApiException.conflict("Versioning conflict: " + e.getMessage());
        } catch (Exception e) {
            logger.severe("Error checking out document " + documentId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to check out document: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/{documentId}/checkin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Check in document",
            description = "Checks in the private working copy (PWC) and creates a new version"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Document checked in successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Document is not checked out",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response checkIn(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "PWC Document ID", required = true)
            @PathParam("documentId") String documentId,
            @Parameter(description = "Create major version")
            @QueryParam("major") @DefaultValue("true") Boolean major,
            @Parameter(description = "Checkin comment")
            @QueryParam("checkinComment") String checkinComment,
            Map<String, PropertyValue> properties) {
        
        logger.info("API v1: Checking in document " + documentId + " to repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            Holder<String> objectIdHolder = new Holder<>(documentId);
            Properties cmisProperties = properties != null && !properties.isEmpty() 
                    ? convertToProperties(properties, null) : null;
            
            versioningService.checkIn(callContext, repositoryId, objectIdHolder, major,
                    cmisProperties, null, checkinComment, null, null, null, null);
            
            String newVersionId = objectIdHolder.getValue();
            
            ObjectData newVersionObject = objectService.getObject(
                    callContext, repositoryId, newVersionId, null,
                    true, IncludeRelationships.NONE, null, false, false, null);
            
            Map<String, Object> response = new HashMap<>();
            response.put("objectId", newVersionId);
            response.put("isMajorVersion", major);
            response.put("checkinComment", checkinComment);
            
            Properties props = newVersionObject.getProperties();
            if (props != null) {
                response.put("versionSeriesId", getStringProperty(props, PropertyIds.VERSION_SERIES_ID));
                response.put("versionLabel", getStringProperty(props, PropertyIds.VERSION_LABEL));
                response.put("isLatestVersion", getBooleanProperty(props, PropertyIds.IS_LATEST_VERSION));
                response.put("isLatestMajorVersion", getBooleanProperty(props, PropertyIds.IS_LATEST_MAJOR_VERSION));
            }
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + newVersionId));
            links.put("content", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + newVersionId + "/content"));
            
            String versionSeriesId = getStringProperty(props, PropertyIds.VERSION_SERIES_ID);
            if (versionSeriesId != null) {
                links.put("versionSeries", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + versionSeriesId + "/versions"));
            }
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (CmisVersioningException e) {
            logger.warning("Versioning error checking in document " + documentId + ": " + e.getMessage());
            throw ApiException.invalidArgument("Document is not checked out or versioning error: " + e.getMessage());
        } catch (CmisUpdateConflictException e) {
            logger.warning("Update conflict checking in document " + documentId + ": " + e.getMessage());
            throw ApiException.conflict("Update conflict: " + e.getMessage());
        } catch (Exception e) {
            logger.severe("Error checking in document " + documentId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to check in document: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/{documentId}/cancelCheckout")
    @Operation(
            summary = "Cancel checkout",
            description = "Cancels the checkout and deletes the private working copy (PWC)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Checkout cancelled successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON)
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Document is not checked out",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response cancelCheckOut(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "PWC Document ID", required = true)
            @PathParam("documentId") String documentId) {
        
        logger.info("API v1: Cancelling checkout for document " + documentId + " in repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ObjectData pwcObject = objectService.getObject(
                    callContext, repositoryId, documentId, null,
                    false, IncludeRelationships.NONE, null, false, false, null);
            
            String versionSeriesId = null;
            if (pwcObject != null && pwcObject.getProperties() != null) {
                versionSeriesId = getStringProperty(pwcObject.getProperties(), PropertyIds.VERSION_SERIES_ID);
            }
            
            versioningService.cancelCheckOut(callContext, repositoryId, documentId, null);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("cancelledPwcId", documentId);
            response.put("versionSeriesId", versionSeriesId);
            response.put("isVersionSeriesCheckedOut", false);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            if (versionSeriesId != null) {
                links.put("versionSeries", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + versionSeriesId + "/versions"));
            }
            response.put("_links", links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (CmisVersioningException e) {
            logger.warning("Versioning error cancelling checkout for document " + documentId + ": " + e.getMessage());
            throw ApiException.invalidArgument("Document is not checked out or versioning error: " + e.getMessage());
        } catch (CmisUpdateConflictException e) {
            logger.warning("Update conflict cancelling checkout for document " + documentId + ": " + e.getMessage());
            throw ApiException.conflict("Update conflict: " + e.getMessage());
        } catch (Exception e) {
            logger.severe("Error cancelling checkout for document " + documentId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to cancel checkout: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{documentId}/versions")
    @Operation(
            summary = "Get all versions",
            description = "Gets all versions of the specified document"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of document versions",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getAllVersions(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document ID or Version Series ID", required = true)
            @PathParam("documentId") String documentId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions) {
        
        logger.info("API v1: Getting all versions for document " + documentId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            List<ObjectData> versions = versioningService.getAllVersions(
                    callContext, repositoryId, documentId, null,
                    filter, includeAllowableActions, null);
            
            List<ObjectResponse> versionResponses = new ArrayList<>();
            if (versions != null) {
                for (ObjectData version : versions) {
                    versionResponses.add(mapToObjectResponse(version, repositoryId, includeAllowableActions));
                }
            }
            
            ObjectListResponse response = new ObjectListResponse();
            response.setObjects(versionResponses);
            response.setNumItems((long) versionResponses.size());
            response.setHasMoreItems(false);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + documentId + "/versions"));
            links.put("document", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + documentId));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting versions for document " + documentId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get document versions: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{documentId}/latestVersion")
    @Operation(
            summary = "Get latest version",
            description = "Gets the latest version of the specified document"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Latest version information",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getLatestVersion(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document ID or Version Series ID", required = true)
            @PathParam("documentId") String documentId,
            @Parameter(description = "Get latest major version only")
            @QueryParam("major") @DefaultValue("false") Boolean major,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions) {
        
        logger.info("API v1: Getting latest version for document " + documentId + " from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ObjectData latestVersion = versioningService.getObjectOfLatestVersion(
                    callContext, repositoryId, documentId, null, major,
                    filter, includeAllowableActions, IncludeRelationships.NONE,
                    null, false, false, null);
            
            if (latestVersion == null) {
                throw ApiException.objectNotFound(documentId, repositoryId);
            }
            
            ObjectResponse response = mapToObjectResponse(latestVersion, repositoryId, includeAllowableActions);
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting latest version for document " + documentId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get latest version: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/checkedout")
    @Operation(
            summary = "Get checked out documents",
            description = "Gets all documents that are currently checked out"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of checked out documents",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ObjectListResponse.class)
                    )
            )
    })
    public Response getCheckedOutDocs(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Folder ID to limit search (optional)")
            @QueryParam("folderId") String folderId,
            @Parameter(description = "Property filter (comma-separated property IDs)")
            @QueryParam("filter") String filter,
            @Parameter(description = "Order by clause", example = "cmis:lastModificationDate DESC")
            @QueryParam("orderBy") String orderBy,
            @Parameter(description = "Include allowable actions")
            @QueryParam("includeAllowableActions") @DefaultValue("false") Boolean includeAllowableActions,
            @Parameter(description = "Maximum number of items to return")
            @QueryParam("maxItems") @DefaultValue("100") Integer maxItems,
            @Parameter(description = "Number of items to skip")
            @QueryParam("skipCount") @DefaultValue("0") Integer skipCount) {
        
        logger.info("API v1: Getting checked out documents from repository " + repositoryId);
        
        try {
            validateRepository(repositoryId);
            CallContext callContext = getCallContext();
            
            ObjectList checkedOutDocs = navigationService.getCheckedOutDocs(
                    callContext, repositoryId, folderId, filter, orderBy,
                    includeAllowableActions, IncludeRelationships.NONE,
                    null, BigInteger.valueOf(maxItems), BigInteger.valueOf(skipCount), null);
            
            List<ObjectResponse> docResponses = new ArrayList<>();
            if (checkedOutDocs != null && checkedOutDocs.getObjects() != null) {
                for (ObjectData doc : checkedOutDocs.getObjects()) {
                    docResponses.add(mapToObjectResponse(doc, repositoryId, includeAllowableActions));
                }
            }
            
            ObjectListResponse response = new ObjectListResponse();
            response.setObjects(docResponses);
            response.setNumItems(checkedOutDocs != null && checkedOutDocs.getNumItems() != null 
                    ? checkedOutDocs.getNumItems().longValue() : (long) docResponses.size());
            response.setHasMoreItems(checkedOutDocs != null && checkedOutDocs.hasMoreItems() != null 
                    ? checkedOutDocs.hasMoreItems() : false);
            response.setSkipCount((long) skipCount);
            response.setMaxItems((long) maxItems);
            
            String baseUri = uriInfo.getBaseUri().toString();
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/checkedout?maxItems=" + maxItems + "&skipCount=" + skipCount));
            
            if (response.getHasMoreItems()) {
                int nextSkip = skipCount + maxItems;
                links.put("next", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/checkedout?maxItems=" + maxItems + "&skipCount=" + nextSkip));
            }
            if (skipCount > 0) {
                int prevSkip = Math.max(0, skipCount - maxItems);
                links.put("prev", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/checkedout?maxItems=" + maxItems + "&skipCount=" + prevSkip));
            }
            
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting checked out documents: " + e.getMessage());
            throw ApiException.internalError("Failed to get checked out documents: " + e.getMessage(), e);
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
            
            Map<String, PropertyValue> propertyMap = new HashMap<>();
            for (PropertyData<?> prop : properties.getPropertyList()) {
                propertyMap.put(prop.getId(), mapPropertyValue(prop));
            }
            response.setProperties(propertyMap);
        }
        
        String baseUri = uriInfo.getBaseUri().toString();
        String objectId = response.getObjectId();
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + objectId));
        links.put("content", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/objects/" + objectId + "/content"));
        links.put("versions", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + objectId + "/versions"));
        links.put("latestVersion", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + objectId + "/latestVersion"));
        links.put("checkout", LinkInfo.of(baseUri + "repositories/" + repositoryId + "/documents/" + objectId + "/checkout"));
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
    
    private Properties convertToProperties(Map<String, PropertyValue> propertyMap, String defaultTypeId) {
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
        
        if (defaultTypeId != null && !properties.getProperties().containsKey(PropertyIds.OBJECT_TYPE_ID)) {
            properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, defaultTypeId));
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
