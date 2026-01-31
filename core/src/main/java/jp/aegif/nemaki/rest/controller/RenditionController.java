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
 *     Claude Code - Spring @RestController implementation for Rendition API
 ******************************************************************************/
package jp.aegif.nemaki.rest.controller;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.rendition.RenditionManager;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.util.constant.RenditionKind;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * Spring @RestController for Rendition API
 * Provides endpoints for getting and generating PDF renditions for Office documents
 */
@RestController
@RequestMapping("/v1/repo/{repositoryId}/renditions")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RenditionController {

    private static final Log log = LogFactory.getLog(RenditionController.class);

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
        "application/rtf"
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

    /**
     * Get all renditions for a document
     *
     * @param repositoryId Repository ID
     * @param objectId Document object ID
     * @return List of renditions with metadata
     */
    @GetMapping("/{objectId}")
    public ResponseEntity<Map<String, Object>> getRenditions(
            @PathVariable("repositoryId") String repositoryId,
            @PathVariable("objectId") String objectId) {

        Map<String, Object> response = new HashMap<>();

        try {
            log.info("[RenditionController] Getting renditions for objectId=" + objectId + " in repo=" + repositoryId);

            // Check if document exists first
            Content content = getContentService().getContent(repositoryId, objectId);
            if (content == null) {
                response.put("status", "error");
                response.put("message", "Document not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            List<Rendition> renditions = getContentService().getRenditions(repositoryId, objectId);

            List<Map<String, Object>> renditionList = new ArrayList<>();

            if (CollectionUtils.isNotEmpty(renditions)) {
                for (Rendition rendition : renditions) {
                    Map<String, Object> renditionMap = new HashMap<>();
                    renditionMap.put("streamId", rendition.getId());
                    renditionMap.put("renditionDocumentId", rendition.getRenditionDocumentId());
                    renditionMap.put("mimeType", rendition.getMimetype());
                    renditionMap.put("contentMimeType", rendition.getMimetype());
                    renditionMap.put("length", rendition.getLength());
                    renditionMap.put("title", rendition.getTitle());
                    renditionMap.put("kind", rendition.getKind());
                    renditionMap.put("height", rendition.getHeight());
                    renditionMap.put("width", rendition.getWidth());
                    renditionList.add(renditionMap);
                }
            }

            response.put("status", "success");
            response.put("renditions", renditionList);
            response.put("count", renditionList.size());

            log.info("[RenditionController] Found " + renditionList.size() + " renditions for objectId=" + objectId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[RenditionController] Error getting renditions for objectId=" + objectId, e);
            response.put("status", "error");
            response.put("message", "Failed to retrieve renditions");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Generate PDF rendition for a document
     *
     * @param repositoryId Repository ID
     * @param objectId Document object ID
     * @param force If true, regenerate even if rendition exists
     * @return Generation result
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateRendition(
            @PathVariable("repositoryId") String repositoryId,
            @RequestParam("objectId") String objectId,
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {

        Map<String, Object> response = new HashMap<>();

        try {
            log.info("[RenditionController] Generating rendition for objectId=" + objectId + ", force=" + force);

            // Get the document
            Content content = getContentService().getContent(repositoryId, objectId);

            if (content == null) {
                response.put("status", "error");
                response.put("message", "Document not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (!content.isDocument()) {
                response.put("status", "error");
                response.put("message", "Object is not a document");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            Document document = (Document) content;

            // Check if rendition already exists (unless force=true)
            if (!force) {
                List<Rendition> existingRenditions = getContentService().getRenditions(repositoryId, objectId);
                if (CollectionUtils.isNotEmpty(existingRenditions)) {
                    for (Rendition r : existingRenditions) {
                        if ("application/pdf".equals(r.getMimetype()) ||
                            RenditionKind.CMIS_PREVIEW.value().equals(r.getKind())) {
                            log.info("[RenditionController] PDF rendition already exists for objectId=" + objectId);
                            response.put("status", "success");
                            response.put("message", "Rendition already exists");
                            response.put("renditionId", r.getId());
                            return ResponseEntity.ok(response);
                        }
                    }
                }
            }

            // Get attachment for the document
            String attachmentId = document.getAttachmentNodeId();
            if (attachmentId == null) {
                response.put("status", "error");
                response.put("message", "Document has no content");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            AttachmentNode attachment = getContentService().getAttachment(repositoryId, attachmentId);
            if (attachment == null) {
                response.put("status", "error");
                response.put("message", "Document attachment not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // Check if MIME type is convertible
            String mimeType = attachment.getMimeType();
            RenditionManager renditionManager = getRenditionManager();

            if (!renditionManager.checkConvertible(mimeType)) {
                response.put("status", "error");
                response.put("message", "MIME type not supported for conversion: " + mimeType);
                response.put("supportedTypes", SUPPORTED_MIME_TYPES);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // Create content stream for conversion
            ContentStream contentStream = new ContentStreamImpl(
                document.getName(),
                BigInteger.valueOf(attachment.getLength()),
                mimeType,
                attachment.getInputStream()
            );

            // Convert to PDF
            log.info("[RenditionController] Converting " + mimeType + " to PDF for document: " + document.getName());
            ContentStream pdfStream = renditionManager.convertToPdf(contentStream, document.getName());

            if (pdfStream == null) {
                response.put("status", "error");
                response.put("message", "PDF conversion failed");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            // Create rendition object
            Rendition rendition = new Rendition();
            rendition.setTitle("PDF Preview");
            rendition.setKind(RenditionKind.CMIS_PREVIEW.value());
            rendition.setMimetype("application/pdf");
            rendition.setLength(pdfStream.getLength());

            // Set signature
            SystemCallContext callContext = new SystemCallContext(repositoryId);
            rendition.setCreator(callContext.getUsername());
            rendition.setModifier(callContext.getUsername());
            java.util.GregorianCalendar now = new java.util.GregorianCalendar();
            rendition.setCreated(now);
            rendition.setModified(now);

            // Save rendition to database
            String renditionId = getContentDaoService().createRendition(repositoryId, rendition, pdfStream);

            // Update document with rendition reference
            List<String> renditionIds = document.getRenditionIds();
            if (renditionIds == null) {
                renditionIds = new ArrayList<>();
            }
            renditionIds.add(renditionId);
            document.setRenditionIds(renditionIds);

            // Update document
            getContentService().update(callContext, repositoryId, document);

            log.info("[RenditionController] Successfully created PDF rendition: " + renditionId);

            response.put("status", "success");
            response.put("message", "Rendition generated successfully");
            response.put("renditionId", renditionId);
            response.put("mimeType", "application/pdf");

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (Exception e) {
            log.error("[RenditionController] Error generating rendition for objectId=" + objectId, e);
            response.put("status", "error");
            response.put("message", "Failed to generate rendition");
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get supported MIME types for rendition
     */
    @GetMapping("/supported-types")
    public ResponseEntity<Map<String, Object>> getSupportedTypes(@PathVariable("repositoryId") String repositoryId) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("supportedTypes", SUPPORTED_MIME_TYPES);
        response.put("enabled", true); // Rendition generation is enabled if LibreOffice is available
        return ResponseEntity.ok(response);
    }

    /**
     * Batch generate renditions for multiple documents (Admin only)
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batchGenerateRenditions(
            @PathVariable("repositoryId") String repositoryId,
            @RequestParam("objectIds") List<String> objectIds,
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {

        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String objectId : objectIds) {
            try {
                ResponseEntity<Map<String, Object>> result = generateRendition(repositoryId, objectId, force);
                // SpotBugs: NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE - Add null check for getBody()
                Map<String, Object> resultBody = result.getBody();
                if (resultBody == null) {
                    log.warn("generateRendition returned null body for objectId: " + objectId);
                    resultBody = new HashMap<>();
                    resultBody.put("status", "error");
                    resultBody.put("error", "Response body is null");
                }
                resultBody.put("objectId", objectId);
                results.add(resultBody);

                if ("success".equals(resultBody.get("status"))) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("objectId", objectId);
                errorResult.put("status", "error");
                errorResult.put("error", e.getMessage());
                results.add(errorResult);
                failCount++;
            }
        }

        response.put("status", failCount == 0 ? "success" : "partial");
        response.put("successCount", successCount);
        response.put("failCount", failCount);
        response.put("results", results);

        return ResponseEntity.ok(response);
    }
}
