package jp.aegif.nemaki.api.v1.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
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
import jp.aegif.nemaki.api.v1.model.response.LinkInfo;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.constant.RenditionKind;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
@Tag(name = "renditions", description = "Rendition management operations")
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
    private RepositoryService repositoryService;
    
    @Autowired
    private ContentService contentService;
    
    @Autowired
    private ContentDaoService contentDaoService;
    
    @Autowired(required = false)
    private RenditionManager renditionManager;
    
    @Context
    private UriInfo uriInfo;
    
    @Context
    private HttpServletRequest httpRequest;
    
    @GET
    @Path("/{objectId}")
    @Operation(
            summary = "Get renditions for an object",
            description = "Returns all renditions available for the specified object"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of renditions"
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
    public Response getRenditions(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID", required = true)
            @PathParam("objectId") String objectId) {
        
        logger.info("API v1: Getting renditions for object " + objectId);
        
        try {
            validateRepository(repositoryId);
            
            List<Rendition> renditions = contentService.getRenditions(repositoryId, objectId);
            
            List<Map<String, Object>> renditionList = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(renditions)) {
                for (Rendition rendition : renditions) {
                    Map<String, Object> renditionMap = new HashMap<>();
                    renditionMap.put("streamId", rendition.getId());
                    renditionMap.put("renditionDocumentId", rendition.getRenditionDocumentId());
                    
                    String mimeType = rendition.getMimetype();
                    if (mimeType == null || mimeType.isEmpty()) {
                        mimeType = "application/pdf";
                    }
                    renditionMap.put("mimeType", mimeType);
                    renditionMap.put("length", rendition.getLength());
                    renditionMap.put("title", rendition.getTitle());
                    renditionMap.put("kind", rendition.getKind());
                    renditionMap.put("height", rendition.getHeight());
                    renditionMap.put("width", rendition.getWidth());
                    renditionList.add(renditionMap);
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("objectId", objectId);
            response.put("renditions", renditionList);
            response.put("count", renditionList.size());
            
            return Response.ok(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error getting renditions: " + e.getMessage());
            throw ApiException.internalError("Failed to get renditions: " + e.getMessage(), e);
        }
    }
    
    @POST
    @Path("/generate")
    @Operation(
            summary = "Generate PDF rendition",
            description = "Generates a PDF rendition for the specified document"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "201",
                    description = "Rendition generated successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request or unsupported MIME type",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
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
    public Response generateRendition(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId,
            @Parameter(description = "Object ID of the document", required = true)
            @QueryParam("objectId") String objectId,
            @Parameter(description = "Force regeneration even if rendition exists")
            @QueryParam("force") Boolean force) {
        
        logger.info("API v1: Generating rendition for object " + objectId);
        
        try {
            validateRepository(repositoryId);
            
            if (renditionManager == null) {
                throw ApiException.internalError("Rendition manager is not available");
            }
            
            jp.aegif.nemaki.model.Content content = contentService.getContent(repositoryId, objectId);
            if (content == null) {
                throw ApiException.objectNotFound(objectId, repositoryId);
            }
            
            if (!content.isDocument()) {
                throw ApiException.invalidArgument("Object is not a document");
            }
            
            Document document = (Document) content;
            
            boolean forceRegenerate = force != null && force;
            if (!forceRegenerate) {
                List<Rendition> existingRenditions = contentService.getRenditions(repositoryId, objectId);
                if (CollectionUtils.isNotEmpty(existingRenditions)) {
                    for (Rendition r : existingRenditions) {
                        if ("application/pdf".equals(r.getMimetype()) ||
                            RenditionKind.CMIS_PREVIEW.value().equals(r.getKind())) {
                            Map<String, Object> response = new HashMap<>();
                            response.put("objectId", objectId);
                            response.put("renditionId", r.getId());
                            response.put("message", "Rendition already exists");
                            response.put("alreadyExists", true);
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
                throw ApiException.invalidArgument("MIME type not supported for conversion: " + mimeType);
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
            
            String username = getCurrentUserId();
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
            
            SystemCallContext systemContext = new SystemCallContext(repositoryId);
            contentService.update(systemContext, repositoryId, document);
            
            Map<String, Object> response = new HashMap<>();
            response.put("objectId", objectId);
            response.put("renditionId", renditionId);
            response.put("mimeType", "application/pdf");
            response.put("message", "Rendition generated successfully");
            
            return Response.status(Response.Status.CREATED).entity(response).build();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Error generating rendition: " + e.getMessage());
            throw ApiException.internalError("Failed to generate rendition: " + e.getMessage(), e);
        }
    }
    
    @GET
    @Path("/supported-types")
    @Operation(
            summary = "Get supported MIME types",
            description = "Returns a list of MIME types that can be converted to PDF"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "List of supported MIME types"
            )
    })
    public Response getSupportedTypes(
            @Parameter(description = "Repository ID", required = true, example = "bedroom")
            @PathParam("repositoryId") String repositoryId) {
        
        Map<String, Object> response = new HashMap<>();
        response.put("supportedTypes", SUPPORTED_MIME_TYPES);
        return Response.ok(response).build();
    }
    
    private void validateRepository(String repositoryId) {
        if (!repositoryService.hasThisRepositoryId(repositoryId)) {
            throw ApiException.repositoryNotFound(repositoryId);
        }
    }
    
    private String getCurrentUserId() {
        return AuthenticationUtil.getUserIdFromRequest(httpRequest);
    }
}
