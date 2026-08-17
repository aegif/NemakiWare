/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Claude Code - Jersey REST Resource for Rendition API
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.rendition.ExtendedRenditionManager;
import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.util.constant.RenditionKind;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;

/**
 * Jersey REST Resource for Rendition API
 * Provides endpoints for getting and generating PDF renditions for Office documents
 */
@Path("/repo/{repositoryId}/renditions")
public class RenditionResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(RenditionResource.class);

    // Supported MIME types for PDF conversion
    private static final List<String> SUPPORTED_MIME_TYPES = Arrays.asList(
        // Microsoft Office formats
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // docx
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // xlsx
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", // pptx
        "application/msword", // doc
        "application/vnd.ms-excel", // xls
        "application/vnd.ms-powerpoint", // ppt
        // OpenDocument formats
        "application/vnd.oasis.opendocument.text", // odt
        "application/vnd.oasis.opendocument.spreadsheet", // ods
        "application/vnd.oasis.opendocument.presentation", // odp
        // Text formats
        "text/plain",
        "text/csv",
        "text/rtf",
        "application/rtf",
        // CAD formats (SVG rendition)
        "image/vnd.dxf",
        "application/x-dwg",
        "application/x-jww",
        "application/x-sfc",
        "application/x-p21",
        // Diagram formats (SVG rendition)
        "text/x-plantuml",
        "text/vnd.graphviz"
    );

    private ContentService getContentService() {
        return SpringContext.getApplicationContext()
                .getBean("ContentService", ContentService.class);
    }

    private ContentDaoService getContentDaoService() {
        return SpringContext.getApplicationContext()
                .getBean("ContentDaoService", ContentDaoService.class);
    }

    private RenditionManager getRenditionManager() {
        return SpringContext.getApplicationContext()
                .getBean("RenditionManager", RenditionManager.class);
    }

    private ExceptionService getExceptionService() {
        return SpringContext.getApplicationContext()
                .getBean("ExceptionService", ExceptionService.class);
    }

    /**
     * Get all renditions for a document
     */
    @GET
    @Path("/{objectId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRenditions(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("objectId") String objectId,
            @Context HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            // SECURITY FIX: Require authentication and check object-level read permission
            CallContext callContext = (CallContext) request.getAttribute("CallContext");
            if (callContext == null) {
                response.put("status", "error");
                response.put("message", "Authentication required");
                return Response.status(Response.Status.FORBIDDEN).entity(response).build();
            }

            log.info("[RenditionResource] Getting renditions for objectId=" + objectId + " in repo=" + repositoryId);

            // Check object-level read permission
            Content content = getContentService().getContent(repositoryId, objectId);
            if (content == null) {
                response.put("status", "error");
                response.put("message", "Document not found");
                return Response.status(Response.Status.NOT_FOUND).entity(response).build();
            }
            try {
                getExceptionService().permissionDenied(callContext, repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, content);
            } catch (CmisPermissionDeniedException e) {
                response.put("status", "error");
                response.put("message", "Permission denied");
                return Response.status(Response.Status.FORBIDDEN).entity(response).build();
            }

            List<Rendition> renditions = getContentService().getRenditions(repositoryId, objectId);

            List<Map<String, Object>> renditionList = new ArrayList<>();

            if (CollectionUtils.isNotEmpty(renditions)) {
                for (Rendition rendition : renditions) {
                    log.info("[RenditionResource] Rendition ID=" + rendition.getId() +
                            ", mimeType=" + rendition.getMimetype() +
                            ", kind=" + rendition.getKind() +
                            ", title=" + rendition.getTitle());

                    Map<String, Object> renditionMap = new HashMap<>();
                    renditionMap.put("streamId", rendition.getId());
                    renditionMap.put("renditionDocumentId", rendition.getRenditionDocumentId());
                    // Always include mimeType, default to application/pdf for renditions
                    String mimeType = rendition.getMimetype();
                    if (mimeType == null || mimeType.isEmpty()) {
                        mimeType = "application/pdf"; // Default for preview renditions
                    }
                    renditionMap.put("mimeType", mimeType);
                    renditionMap.put("contentMimeType", mimeType);
                    renditionMap.put("length", rendition.getLength());
                    renditionMap.put("title", rendition.getTitle());
                    renditionMap.put("kind", rendition.getKind());
                    renditionMap.put("height", rendition.getHeight());
                    renditionMap.put("width", rendition.getWidth());
                    // Include timestamps for freshness checking by UI
                    if (rendition.getModified() != null) {
                        renditionMap.put("modified", rendition.getModified().getTimeInMillis());
                    }
                    if (rendition.getCreated() != null) {
                        renditionMap.put("created", rendition.getCreated().getTimeInMillis());
                    }
                    renditionList.add(renditionMap);
                }
            }

            response.put("status", "success");
            response.put("renditions", renditionList);
            response.put("count", renditionList.size());

            log.info("[RenditionResource] Found " + renditionList.size() + " renditions for objectId=" + objectId);

            return Response.ok(response).build();

        } catch (Exception e) {
            log.error("[RenditionResource] Error getting renditions for objectId=" + objectId, e);
            response.put("status", "error");
            response.put("message", "Failed to retrieve renditions");
            response.put("error", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(response).build();
        }
    }

    /**
     * Generate PDF rendition for a document.
     * When force=true, removes any existing preview renditions before generating a fresh one.
     */
    @POST
    @Path("/generate")
    @Produces(MediaType.APPLICATION_JSON)
    public Response generateRendition(
            @PathParam("repositoryId") String repositoryId,
            @QueryParam("objectId") String objectId,
            @QueryParam("force") Boolean forceParam,
            @Context HttpServletRequest request) {

        boolean force = forceParam != null && forceParam;
        Map<String, Object> response = new HashMap<>();
        String csrfError = validateCsrfProtection(request);
        if (csrfError != null) {
            response.put("status", "error");
            response.put("message", "CSRF validation failed: " + csrfError);
            return Response.status(Response.Status.FORBIDDEN).entity(response).build();
        }

        try {
            // SECURITY FIX: Require authentication and use user's CallContext for permission checks
            CallContext callContext = (CallContext) request.getAttribute("CallContext");
            if (callContext == null) {
                response.put("status", "error");
                response.put("message", "Authentication required");
                return Response.status(Response.Status.FORBIDDEN).entity(response).build();
            }

            log.info("[RenditionResource] Generating rendition for objectId=" + objectId + ", force=" + force + ", user=" + callContext.getUsername());

            // Get the document
            Content content = getContentService().getContent(repositoryId, objectId);

            if (content == null) {
                response.put("status", "error");
                response.put("message", "Document not found");
                return Response.status(Response.Status.NOT_FOUND).entity(response).build();
            }

            // SECURITY FIX: Check object-level read permission before generating rendition
            try {
                getExceptionService().permissionDenied(callContext, repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, content);
            } catch (CmisPermissionDeniedException e) {
                response.put("status", "error");
                response.put("message", "Permission denied");
                return Response.status(Response.Status.FORBIDDEN).entity(response).build();
            }

            // SECURITY FIX: Check content-level read permission.
            // Rendition generation reads the document content to convert it to PDF,
            // so CAN_VIEW_CONTENT_OBJECT (canGetContentStream) permission is required.
            try {
                getExceptionService().permissionDenied(callContext, repositoryId, PermissionMapping.CAN_VIEW_CONTENT_OBJECT, content);
            } catch (CmisPermissionDeniedException e) {
                response.put("status", "error");
                response.put("message", "Permission denied: content read access required");
                return Response.status(Response.Status.FORBIDDEN).entity(response).build();
            }

            if (!content.isDocument()) {
                response.put("status", "error");
                response.put("message", "Object is not a document");
                return Response.status(Response.Status.BAD_REQUEST).entity(response).build();
            }

            Document document = (Document) content;

            // Check existing renditions
            List<Rendition> existingRenditions = getContentService().getRenditions(repositoryId, objectId);
            if (!force && CollectionUtils.isNotEmpty(existingRenditions)) {
                for (Rendition r : existingRenditions) {
                    if ("application/pdf".equals(r.getMimetype()) ||
                        RenditionKind.CMIS_PREVIEW.value().equals(r.getKind())) {
                        log.info("[RenditionResource] PDF rendition already exists for objectId=" + objectId);
                        response.put("status", "success");
                        response.put("message", "Rendition already exists");
                        response.put("renditionId", r.getId());
                        return Response.ok(response).build();
                    }
                }
            }

            // When force=true, remove existing preview renditions before generating fresh one
            if (force && CollectionUtils.isNotEmpty(existingRenditions)) {
                List<String> renditionIds = document.getRenditionIds();
                if (renditionIds != null) {
                    List<String> toRemove = new ArrayList<>();
                    for (Rendition r : existingRenditions) {
                        if (RenditionKind.CMIS_PREVIEW.value().equals(r.getKind())) {
                            toRemove.add(r.getId());
                            log.info("[RenditionResource] Removing stale preview rendition: " + r.getId());
                        }
                    }
                    renditionIds.removeAll(toRemove);
                    document.setRenditionIds(renditionIds);
                }
            }

            // Get attachment for the document
            String attachmentId = document.getAttachmentNodeId();
            if (attachmentId == null) {
                response.put("status", "error");
                response.put("message", "Document has no content");
                return Response.status(Response.Status.BAD_REQUEST).entity(response).build();
            }

            // Ref first, body later. Every one of these endpoints used to open the
            // attachment body BEFORE testing convertibility, then return early for an
            // unsupported MIME type without closing it — one leaked CouchDB connection per
            // rejected request, and the MIME type it was testing came from the document,
            // not from the body. Same defect as createPreviewAtomic; fixing that one alone
            // left these three.
            AttachmentNode attachmentRef = getContentService().getAttachmentRef(repositoryId, attachmentId);
            if (attachmentRef == null) {
                response.put("status", "error");
                response.put("message", "Document attachment not found");
                return Response.status(Response.Status.NOT_FOUND).entity(response).build();
            }

            // Check if MIME type is convertible
            String mimeType = attachmentRef.getMimeType();
            RenditionManager renditionManager = getRenditionManager();

            // Check SVG convertibility first (CAD/diagram formats), then PDF
            boolean isSvgConvertible = false;
            if (renditionManager instanceof ExtendedRenditionManager) {
                isSvgConvertible = ((ExtendedRenditionManager) renditionManager)
                        .checkSvgConvertible(mimeType, document.getName());
            }

            if (!isSvgConvertible && !renditionManager.checkConvertible(mimeType)) {
                response.put("status", "error");
                response.put("message", "MIME type not supported for conversion: " + mimeType);
                response.put("supportedTypes", SUPPORTED_MIME_TYPES);
                return Response.status(Response.Status.BAD_REQUEST).entity(response).build();
            }

            // Convertible, so the body is actually going to be read: open it now.
            AttachmentNode attachment = getContentService().getAttachment(repositoryId, attachmentId);
            if (attachment == null) {
                response.put("status", "error");
                response.put("message", "Document attachment not found");
                return Response.status(Response.Status.NOT_FOUND).entity(response).build();
            }

            // Read attachment content into byte array to avoid consuming cached InputStream
            byte[] contentBytes;
            try (InputStream is = attachment.getInputStream()) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                contentBytes = baos.toByteArray();
            }

            // Create content stream for conversion from byte array
            ContentStream contentStream = new ContentStreamImpl(
                document.getName(),
                BigInteger.valueOf(contentBytes.length),
                mimeType,
                new java.io.ByteArrayInputStream(contentBytes)
            );

            // Convert to SVG or PDF depending on format
            ContentStream renditionStream;
            String renditionMimeType;
            String renditionTitle;

            if (isSvgConvertible) {
                log.info("[RenditionResource] Converting " + mimeType + " to SVG for document: " + document.getName());
                renditionStream = ((ExtendedRenditionManager) renditionManager).convertToSvg(contentStream, document.getName());
                renditionMimeType = "image/svg+xml";
                renditionTitle = "SVG Preview";
            } else {
                log.info("[RenditionResource] Converting " + mimeType + " to PDF for document: " + document.getName());
                renditionStream = renditionManager.convertToPdf(contentStream, document.getName());
                renditionMimeType = "application/pdf";
                renditionTitle = "PDF Preview";
            }

            if (renditionStream == null) {
                response.put("status", "error");
                response.put("message", (isSvgConvertible ? "SVG" : "PDF") + " conversion failed");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(response).build();
            }

            // Build + persist rendition + link to document via shared ContentService
            // (signature stamped with the authenticated user; document updated under the user's CallContext)
            Rendition rendition = getContentService().createPreviewRendition(repositoryId, document,
                    renditionStream, renditionMimeType, renditionTitle, callContext.getUsername(), callContext);
            String renditionId = rendition.getId();

            log.info("[RenditionResource] Successfully created " + renditionMimeType + " rendition: " + renditionId);

            response.put("status", "success");
            response.put("message", "Rendition generated successfully");
            response.put("renditionId", renditionId);
            response.put("mimeType", renditionMimeType);

            return Response.status(Response.Status.CREATED).entity(response).build();

        } catch (Exception e) {
            log.error("[RenditionResource] Error generating rendition for objectId=" + objectId, e);
            response.put("status", "error");
            response.put("message", "Failed to generate rendition");
            response.put("error", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(response).build();
        }
    }

    /**
     * Get supported MIME types for rendition
     */
    @GET
    @Path("/supported-types")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSupportedTypes(@PathParam("repositoryId") String repositoryId) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("supportedTypes", SUPPORTED_MIME_TYPES);
        return Response.ok(response).build();
    }
}
