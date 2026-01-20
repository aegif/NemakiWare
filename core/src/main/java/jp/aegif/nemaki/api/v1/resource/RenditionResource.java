package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
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
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.exception.ProblemDetail;
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.util.constant.CallContextKey;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.util.constant.RenditionKind;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
@Path("/repositories/{repositoryId}/renditions")
@Tag(name = "renditions", description = "Document rendition management operations")
@Produces(MediaType.APPLICATION_JSON)
public class RenditionResource {
    
    private static final Logger logger = Logger.getLogger(RenditionResource.class.getName());
    
    private static final List<String> SUPPORTED_MIME_TYPES = Arrays.asList(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "text/plain",
        "text/csv",
        "text/rtf",
        "application/rtf"
    );
    
    @Autowired
    private ContentService contentService;
    
    @Autowired
    private ContentDaoService contentDaoService;
    
    @Autowired
    private RenditionManager renditionManager;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    private void checkAdminAuthorization() {
        CallContext callContext = (CallContext) httpRequest.getAttribute("CallContext");
        if (callContext == null) {
            throw ApiException.unauthorized("Authentication required for rendition management operations");
        }
        Boolean isAdmin = (Boolean) callContext.get(CallContextKey.IS_ADMIN);
        if (isAdmin == null || !isAdmin) {
            throw ApiException.permissionDenied("Only administrators can perform rendition management operations");
        }
    }
    
    @GET
    @Path("/document/{objectId}")
    @Operation(
            summary = "List renditions for document",
            description = "Returns all renditions available for the specified document"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Renditions retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RenditionListResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response listRenditions(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document object ID", required = true)
            @PathParam("objectId") String objectId) {
        
        logger.info("API v1: Getting renditions for document " + objectId + " in repository " + repositoryId);
        
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                throw ApiException.objectNotFound(objectId, repositoryId);
            }
            
            if (!content.isDocument()) {
                throw ApiException.invalidArgument("Object " + objectId + " is not a document");
            }
            
            List<Rendition> renditions = contentService.getRenditions(repositoryId, objectId);
            
            List<RenditionResponse> renditionResponses = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(renditions)) {
                for (Rendition rendition : renditions) {
                    renditionResponses.add(convertToRenditionResponse(rendition, repositoryId, objectId));
                }
            }
            
            RenditionListResponse response = new RenditionListResponse();
            response.setRenditions(renditionResponses);
            response.setCount(renditionResponses.size());
            response.setDocumentId(objectId);
            
            Map<String, LinkInfo> links = new HashMap<>();
            links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/renditions/document/" + objectId));
            links.put("document", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/objects/" + objectId));
            response.setLinks(links);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting renditions for document " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get renditions: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{renditionId}")
    @Operation(
            summary = "Get rendition metadata",
            description = "Returns metadata for the specified rendition"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Rendition metadata retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RenditionResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Rendition not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response getRendition(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Rendition ID", required = true)
            @PathParam("renditionId") String renditionId) {
        
        logger.info("API v1: Getting rendition " + renditionId + " in repository " + repositoryId);
        
        try {
            Rendition rendition = contentDaoService.getRendition(repositoryId, renditionId);
            if (rendition == null) {
                throw ApiException.objectNotFound(renditionId, repositoryId);
            }
            
            RenditionResponse response = convertToRenditionResponse(rendition, repositoryId, null);
            
            return Response.ok(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting rendition " + renditionId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get rendition: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/{renditionId}/content")
    @Operation(
            summary = "Get rendition content",
            description = "Returns the binary content of the specified rendition"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Rendition content retrieved successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Rendition not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getRenditionContent(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Rendition ID", required = true)
            @PathParam("renditionId") String renditionId) {
        
        logger.info("API v1: Getting rendition content " + renditionId + " in repository " + repositoryId);
        
        try {
            Rendition rendition = contentDaoService.getRendition(repositoryId, renditionId);
            if (rendition == null) {
                throw ApiException.objectNotFound(renditionId, repositoryId);
            }
            
            InputStream inputStream = rendition.getInputStream();
            if (inputStream == null) {
                throw ApiException.internalError("Rendition content not available");
            }
            
            String mimeType = rendition.getMimetype();
            if (mimeType == null || mimeType.isEmpty()) {
                mimeType = "application/pdf";
            }
            
            StreamingOutput streamingOutput = new StreamingOutput() {
                @Override
                public void write(OutputStream output) throws IOException {
                    try (InputStream in = inputStream) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            output.write(buffer, 0, bytesRead);
                        }
                    }
                }
            };
            
            return Response.ok(streamingOutput)
                    .type(mimeType)
                    .header("Content-Disposition", "attachment; filename=\"" + (rendition.getTitle() != null ? rendition.getTitle() : "rendition") + "\"")
                    .build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting rendition content " + renditionId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to get rendition content: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/document/{objectId}/generate")
    @Operation(
            summary = "Generate PDF rendition",
            description = "Generates a PDF rendition for the specified document. Supports Office documents and text files."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Rendition generated successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = RenditionResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Document type not supported for conversion",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Document not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response generateRendition(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Document object ID", required = true)
            @PathParam("objectId") String objectId,
            @Parameter(description = "Force regeneration even if rendition exists")
            @QueryParam("force") @DefaultValue("false") boolean force) {
        
        logger.info("API v1: Generating rendition for document " + objectId + " in repository " + repositoryId + ", force=" + force);
        
        checkAdminAuthorization();
        
        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                throw ApiException.objectNotFound(objectId, repositoryId);
            }
            
            if (!content.isDocument()) {
                throw ApiException.invalidArgument("Object " + objectId + " is not a document");
            }
            
            Document document = (Document) content;
            
            if (!force) {
                List<Rendition> existingRenditions = contentService.getRenditions(repositoryId, objectId);
                if (CollectionUtils.isNotEmpty(existingRenditions)) {
                    for (Rendition r : existingRenditions) {
                        if ("application/pdf".equals(r.getMimetype()) ||
                            RenditionKind.CMIS_PREVIEW.value().equals(r.getKind())) {
                            logger.info("PDF rendition already exists for document " + objectId);
                            RenditionResponse response = convertToRenditionResponse(r, repositoryId, objectId);
                            return Response.ok(response).build();
                        }
                    }
                }
            }
            
            String attachmentId = document.getAttachmentNodeId();
            if (attachmentId == null) {
                throw ApiException.invalidArgument("Document has no content");
            }
            
            AttachmentNode attachment = contentService.getAttachment(repositoryId, attachmentId);
            if (attachment == null) {
                throw ApiException.objectNotFound(attachmentId, repositoryId);
            }
            
            String mimeType = attachment.getMimeType();
            if (!renditionManager.checkConvertible(mimeType)) {
                throw ApiException.invalidArgument("MIME type not supported for conversion: " + mimeType + ". Supported types: " + SUPPORTED_MIME_TYPES);
            }
            
            ContentStream contentStream = new ContentStreamImpl(
                document.getName(),
                BigInteger.valueOf(attachment.getLength()),
                mimeType,
                attachment.getInputStream()
            );
            
            ContentStream pdfStream = renditionManager.convertToPdf(contentStream, document.getName());
            if (pdfStream == null) {
                throw ApiException.internalError("PDF conversion failed");
            }
            
            Rendition rendition = new Rendition();
            rendition.setTitle("PDF Preview");
            rendition.setKind(RenditionKind.CMIS_PREVIEW.value());
            rendition.setMimetype("application/pdf");
            rendition.setLength(pdfStream.getLength());
            
            String username = getAuthenticatedUsername();
            rendition.setCreator(username);
            rendition.setModifier(username);
            GregorianCalendar now = new GregorianCalendar();
            rendition.setCreated(now);
            rendition.setModified(now);
            
            String renditionId = contentDaoService.createRendition(repositoryId, rendition, pdfStream);
            
            List<String> renditionIds = document.getRenditionIds();
            if (renditionIds == null) {
                renditionIds = new ArrayList<>();
            }
            renditionIds.add(renditionId);
            document.setRenditionIds(renditionIds);
            
            contentService.update(new SystemCallContext(repositoryId), repositoryId, document);
            
            rendition.setId(renditionId);
            RenditionResponse response = convertToRenditionResponse(rendition, repositoryId, objectId);
            
            return Response.status(Response.Status.CREATED).entity(response).build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error generating rendition for document " + objectId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to generate rendition: " + e.getMessage(), e);
        }
    }
    
    @DELETE
    @Path("/{renditionId}")
    @Operation(
            summary = "Delete rendition",
            description = "Deletes the specified rendition"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "204",
                    description = "Rendition deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Rendition not found",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public Response deleteRendition(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Rendition ID", required = true)
            @PathParam("renditionId") String renditionId) {
        
        logger.info("API v1: Deleting rendition " + renditionId + " in repository " + repositoryId);
        
        checkAdminAuthorization();
        
        try {
            Rendition rendition = contentDaoService.getRendition(repositoryId, renditionId);
            if (rendition == null) {
                throw ApiException.objectNotFound(renditionId, repositoryId);
            }
            
            String documentId = rendition.getRenditionDocumentId();
            if (documentId != null) {
                Content content = contentService.getContent(repositoryId, documentId);
                if (content != null && content.getRenditionIds() != null) {
                    List<String> updatedRenditionIds = new ArrayList<>(content.getRenditionIds());
                    updatedRenditionIds.remove(renditionId);
                    content.setRenditionIds(updatedRenditionIds);
                    contentDaoService.update(repositoryId, content);
                }
            }
            
            contentDaoService.delete(repositoryId, renditionId);
            
            return Response.noContent().build();
            
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error deleting rendition " + renditionId + ": " + e.getMessage());
            throw ApiException.internalError("Failed to delete rendition: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/supported-types")
    @Operation(
            summary = "Get supported MIME types",
            description = "Returns the list of MIME types supported for PDF rendition generation"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Supported types retrieved successfully",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SupportedTypesResponse.class)
                    )
            )
    })
    public Response getSupportedTypes(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        SupportedTypesResponse response = new SupportedTypesResponse();
        List<String> dynamicTypes = renditionManager.getSupportedMimeTypes();
        if (dynamicTypes == null || dynamicTypes.isEmpty()) {
            response.setSupportedTypes(SUPPORTED_MIME_TYPES);
        } else {
            response.setSupportedTypes(dynamicTypes);
        }
        
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/renditions/supported-types"));
        response.setLinks(links);
        
        return Response.ok(response).build();
    }
    
    private RenditionResponse convertToRenditionResponse(Rendition rendition, String repositoryId, String documentId) {
        RenditionResponse response = new RenditionResponse();
        response.setRenditionId(rendition.getId());
        response.setMimeType(rendition.getMimetype() != null ? rendition.getMimetype() : "application/pdf");
        response.setLength(rendition.getLength());
        response.setTitle(rendition.getTitle());
        response.setKind(rendition.getKind());
        response.setHeight(rendition.getHeight());
        response.setWidth(rendition.getWidth());
        response.setRenditionDocumentId(rendition.getRenditionDocumentId());
        
        Map<String, LinkInfo> links = new HashMap<>();
        links.put("self", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/renditions/" + rendition.getId()));
        links.put("content", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/renditions/" + rendition.getId() + "/content"));
        if (documentId != null) {
            links.put("document", new LinkInfo("/api/v1/cmis/repositories/" + repositoryId + "/objects/" + documentId));
        }
        response.setLinks(links);
        
        return response;
    }
    
    private String getAuthenticatedUsername() {
        if (httpRequest != null && httpRequest.getUserPrincipal() != null) {
            return httpRequest.getUserPrincipal().getName();
        }
        return "system";
    }
    
    @Schema(description = "Rendition response")
    public static class RenditionResponse {
        @Schema(description = "Rendition ID")
        private String renditionId;
        
        @Schema(description = "MIME type of the rendition")
        private String mimeType;
        
        @Schema(description = "Size in bytes")
        private long length;
        
        @Schema(description = "Rendition title")
        private String title;
        
        @Schema(description = "Rendition kind (e.g., cmis:preview)")
        private String kind;
        
        @Schema(description = "Height in pixels (for image renditions)")
        private long height;
        
        @Schema(description = "Width in pixels (for image renditions)")
        private long width;
        
        @Schema(description = "Associated document ID")
        private String renditionDocumentId;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public String getRenditionId() { return renditionId; }
        public void setRenditionId(String renditionId) { this.renditionId = renditionId; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public long getLength() { return length; }
        public void setLength(long length) { this.length = length; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public long getHeight() { return height; }
        public void setHeight(long height) { this.height = height; }
        public long getWidth() { return width; }
        public void setWidth(long width) { this.width = width; }
        public String getRenditionDocumentId() { return renditionDocumentId; }
        public void setRenditionDocumentId(String renditionDocumentId) { this.renditionDocumentId = renditionDocumentId; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Rendition list response")
    public static class RenditionListResponse {
        @Schema(description = "List of renditions")
        private List<RenditionResponse> renditions;
        
        @Schema(description = "Total count of renditions")
        private int count;
        
        @Schema(description = "Document ID")
        private String documentId;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public List<RenditionResponse> getRenditions() { return renditions; }
        public void setRenditions(List<RenditionResponse> renditions) { this.renditions = renditions; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public String getDocumentId() { return documentId; }
        public void setDocumentId(String documentId) { this.documentId = documentId; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
    
    @Schema(description = "Supported types response")
    public static class SupportedTypesResponse {
        @Schema(description = "List of supported MIME types for rendition generation")
        private List<String> supportedTypes;
        
        @Schema(description = "HATEOAS links")
        private Map<String, LinkInfo> links;
        
        public List<String> getSupportedTypes() { return supportedTypes; }
        public void setSupportedTypes(List<String> supportedTypes) { this.supportedTypes = supportedTypes; }
        public Map<String, LinkInfo> getLinks() { return links; }
        public void setLinks(Map<String, LinkInfo> links) { this.links = links; }
    }
}
