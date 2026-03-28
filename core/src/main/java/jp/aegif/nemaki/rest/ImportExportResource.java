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
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import jp.aegif.nemaki.audit.AuditLogger;
import jp.aegif.nemaki.audit.AuditOperation;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.purview.lineage.PurviewImportExportLineageService;
import jp.aegif.nemaki.rest.purview.journal.LineageConfig;
import jp.aegif.nemaki.rest.purview.journal.LineageMode;
import jp.aegif.nemaki.rest.purview.journal.LineageEmitter;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageEventBuilder;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.rest.purview.journal.LineageTargetSink;
import jp.aegif.nemaki.rest.importexport.FilesystemExporter;
import jp.aegif.nemaki.rest.importexport.FilesystemImporter;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ExportResult;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;
import jp.aegif.nemaki.rest.importexport.ZipExporter;
import jp.aegif.nemaki.rest.importexport.ZipImporter;
import jp.aegif.nemaki.rest.importexport.ZipImporter.ImportFormat;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipOutputStream;

import static jp.aegif.nemaki.rest.importexport.ImportExportUtils.*;

/**
 * REST Resource for Import/Export operations.
 *
 * Supports:
 * - ACP (Alfresco Content Package) format import
 * - Custom NemakiWare format import/export with distributed JSON metadata
 * - Filesystem-based import/export (admin only)
 *
 * Implementation is delegated to:
 * - {@link ZipImporter} for ZIP-based imports
 * - {@link ZipExporter} for ZIP-based exports
 * - {@link FilesystemImporter} for filesystem imports
 * - {@link FilesystemExporter} for filesystem exports
 * - {@link ImportExportUtils} for shared constants and utilities
 */
@Path("/repo/{repositoryId}/importexport")
public class ImportExportResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(ImportExportResource.class);

    private ContentService contentService;

    private final ZipImporter zipImporter = new ZipImporter();
    private final ZipExporter zipExporter = new ZipExporter();
    private final FilesystemImporter filesystemImporter = new FilesystemImporter();
    private final FilesystemExporter filesystemExporter = new FilesystemExporter();
    private PurviewImportExportLineageService purviewImportExportLineageService;

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setPurviewImportExportLineageService(PurviewImportExportLineageService purviewImportExportLineageService) {
        this.purviewImportExportLineageService = purviewImportExportLineageService;
    }

    private ContentService getContentService() {
        if (contentService != null) {
            return contentService;
        }
        return ImportExportUtils.getContentService();
    }

    private AuditLogger getAuditLogger() {
        try {
            return SpringContext.getApplicationContext()
                    .getBean("auditLogger", AuditLogger.class);
        } catch (Exception e) {
            return null;
        }
    }

    protected PurviewImportExportLineageService getPurviewImportExportLineageService() {
        if (purviewImportExportLineageService != null) {
            return purviewImportExportLineageService;
        }
        try {
            return SpringContext.getApplicationContext()
                    .getBean(PurviewImportExportLineageService.class);
        } catch (Exception e) {
            return null;
        }
    }

    private LineageConfig getLineageConfig() {
        try {
            return SpringContext.getApplicationContext()
                    .getBean(LineageConfig.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Emit a lineage event using the per-repository effective mode.
     * If the mode for the event's repository is DISABLED, the call is a no-op.
     */
    private void emitLineageEvent(LineageEvent event) {
        try {
            LineageConfig config = getLineageConfig();
            if (config == null) return;
            LineageMode mode = config.getModeForRepository(event.repositoryId());
            if (mode == LineageMode.DISABLED) return;

            LineageJournalStore store = SpringContext.getApplicationContext()
                    .getBean(LineageJournalStore.class);
            @SuppressWarnings("unchecked")
            java.util.List<LineageTargetSink> sinks = (java.util.List<LineageTargetSink>)
                    (java.util.List<?>) SpringContext.getApplicationContext()
                    .getBeansOfType(LineageTargetSink.class).values().stream().toList();
            LineageEmitter emitter = config.createEmitterForMode(mode, store, sinks);
            if (emitter.isActive()) {
                emitter.emit(event);
            }
        } catch (Exception e) {
            log.warn("Lineage event emission failed (non-fatal): " + e.getMessage());
        }
    }

    private void publishFilesystemImportLineage(
            String repositoryId,
            String folderId,
            String sourcePath,
            String requestedBy,
            ImportResult importResult) {
        // When journal is active, it owns lineage for this processType.
        // Skip direct Purview call to avoid duplicate emission.
        LineageConfig lc = getLineageConfig();
        if (lc != null && lc.getModeForRepository(repositoryId) != LineageMode.DISABLED) {
            return;
        }
        PurviewImportExportLineageService service = getPurviewImportExportLineageService();
        if (service == null || importResult == null) {
            return;
        }

        long objectCount = (long) importResult.documentsCreated + importResult.foldersCreated;
        if (objectCount <= 0L) {
            return;
        }

        try {
            service.upsertFilesystemImportLineage(repositoryId, folderId, sourcePath, requestedBy, objectCount);
        } catch (RuntimeException e) {
            log.warn("Purview filesystem import lineage publish failed: " + e.getMessage(), e);
        }
    }

    private void publishFilesystemExportLineage(
            String repositoryId,
            String folderId,
            String targetPath,
            String requestedBy,
            ExportResult exportResult) {
        LineageConfig lc = getLineageConfig();
        if (lc != null && lc.getModeForRepository(repositoryId) != LineageMode.DISABLED) {
            return;
        }
        PurviewImportExportLineageService service = getPurviewImportExportLineageService();
        if (service == null || exportResult == null) {
            return;
        }

        long objectCount = (long) exportResult.documentsExported + exportResult.foldersExported;
        if (objectCount <= 0L) {
            return;
        }

        try {
            service.upsertFilesystemExportLineage(repositoryId, folderId, targetPath, requestedBy, objectCount);
        } catch (RuntimeException e) {
            log.warn("Purview filesystem export lineage publish failed: " + e.getMessage(), e);
        }
    }

    protected void publishZipFolderExportLineage(
            String repositoryId,
            String folderId,
            String folderName,
            String requestedBy,
            long objectCount) {
        LineageConfig lc = getLineageConfig();
        if (lc != null && lc.getModeForRepository(repositoryId) != LineageMode.DISABLED) {
            return;
        }
        PurviewImportExportLineageService service = getPurviewImportExportLineageService();
        if (service == null || objectCount <= 0L) {
            return;
        }

        try {
            service.upsertZipFolderExportLineage(repositoryId, folderId, folderName, requestedBy, objectCount);
        } catch (RuntimeException e) {
            log.warn("Purview ZIP folder export lineage publish failed: " + e.getMessage(), e);
        }
    }

    protected void publishUploadedImportLineage(
            String repositoryId,
            String folderId,
            String importMode,
            String requestedBy,
            long objectCount) {
        LineageConfig lc = getLineageConfig();
        if (lc != null && lc.getModeForRepository(repositoryId) != LineageMode.DISABLED) {
            return;
        }
        PurviewImportExportLineageService service = getPurviewImportExportLineageService();
        if (service == null || objectCount <= 0L) {
            return;
        }

        try {
            service.upsertUploadedImportLineage(repositoryId, folderId, importMode, requestedBy, objectCount);
        } catch (RuntimeException e) {
            log.warn("Purview uploaded import lineage publish failed: " + e.getMessage(), e);
        }
    }

    protected void publishSelectedObjectsExportLineage(
            String repositoryId,
            List<? extends Content> contents,
            String requestedBy,
            long objectCount) {
        LineageConfig lc = getLineageConfig();
        if (lc != null && lc.getModeForRepository(repositoryId) != LineageMode.DISABLED) {
            return;
        }
        PurviewImportExportLineageService service = getPurviewImportExportLineageService();
        if (service == null || contents == null || contents.isEmpty() || objectCount <= 0L) {
            return;
        }

        try {
            service.upsertSelectedObjectsExportLineage(repositoryId, contents, requestedBy, objectCount);
        } catch (RuntimeException e) {
            log.warn("Purview selected objects export lineage publish failed: " + e.getMessage(), e);
        }
    }

    // ========== REST Endpoints ==========

    /**
     * Import content from ACP or custom format ZIP file.
     */
    @POST
    @Path("/import/{folderId}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public Response importContent(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("folderId") String folderId,
            @FormDataParam("file") InputStream fileInputStream,
            @FormDataParam("file") FormDataContentDisposition fileDetail,
            @Context HttpServletRequest request) {

        log.info("Import request received for repository: " + repositoryId + ", folder: " + folderId);

        JSONObject result = new JSONObject();
        JSONArray errors = new JSONArray();
        JSONArray warnings = new JSONArray();
        int importedFolders = 0;
        int importedDocuments = 0;
        File tempFile = null;

        try {
            ContentService cs = getContentService();
            if (cs == null) {
                result.put("status", "error");
                result.put("message", "ContentService not available");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(result.toJSONString()).build();
            }

            Folder targetFolder = cs.getFolder(repositoryId, folderId);
            if (targetFolder == null) {
                result.put("status", "error");
                result.put("message", "Target folder not found: " + folderId);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(result.toJSONString()).build();
            }

            CallContext callContext = createCallContext(request, repositoryId);

            if (!hasCreateChildrenPermission(cs, repositoryId, callContext, targetFolder)) {
                result.put("status", "error");
                result.put("message", "You do not have permission to create content in this folder");
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(result.toJSONString()).build();
            }

            // Stream ZIP to temp file
            tempFile = Files.createTempFile("nemaki-import-", ".zip").toFile();
            long totalSize = 0;
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fileInputStream.read(buffer)) != -1) {
                    totalSize += len;
                    if (totalSize > MAX_UPLOAD_SIZE) {
                        result.put("status", "error");
                        result.put("message", "File too large. Maximum size: " + (MAX_UPLOAD_SIZE / 1024 / 1024) + "MB");
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity(result.toJSONString()).build();
                    }
                    fos.write(buffer, 0, len);
                }
            }

            ImportFormat format = zipImporter.detectFormat(tempFile);
            log.info("Detected import format: " + format);

            int importedRelationships = 0;
            if (format == ImportFormat.ACP) {
                ImportResult acpResult = zipImporter.importAcpFormat(repositoryId, folderId, tempFile, callContext);
                importedFolders = acpResult.foldersCreated;
                importedDocuments = acpResult.documentsCreated;
                errors.addAll(acpResult.errors);
                warnings.addAll(acpResult.warnings);
            } else if (format == ImportFormat.CUSTOM) {
                ImportResult customResult = zipImporter.importCustomFormat(repositoryId, folderId, tempFile, callContext);
                importedFolders = customResult.foldersCreated;
                importedDocuments = customResult.documentsCreated;
                importedRelationships = customResult.relationshipsCreated;
                errors.addAll(customResult.errors);
                warnings.addAll(customResult.warnings);
            } else {
                result.put("status", "error");
                result.put("message", "Unknown or unsupported archive format");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(result.toJSONString()).build();
            }

            publishUploadedImportLineage(
                    repositoryId,
                    folderId,
                    format == ImportFormat.ACP ? "acp-upload" : "zip-upload",
                    getCallContextUsername(request),
                    (long) importedFolders + importedDocuments);

            // Lineage Journal: IMPORT_UPLOADED
            {
                long objCount = (long) importedFolders + importedDocuments;
                if (objCount > 0) {
                    String importMode = format == ImportFormat.ACP ? "acp-upload" : "zip-upload";
                    LineageConfig lc = getLineageConfig();
                    LineageEventBuilder b = new LineageEventBuilder()
                            .repositoryId(repositoryId)
                            .processType(LineageProcessType.IMPORT_UPLOADED)
                            .addInput("upload://" + importMode)
                            .addOutputObject(repositoryId, folderId)
                            .snapshotAttribute("importMode", importMode)
                            .snapshotAttribute("objectCount", String.valueOf(objCount))
                            .snapshotAttribute("requestedBy", getCallContextUsername(request));
                    if (lc != null) {
                        b.targets(lc.getTargets());
                    }
                    emitLineageEvent(b.build());
                }
            }

            String status = "success";
            if (!errors.isEmpty()) {
                status = "partial";
            } else if (!warnings.isEmpty()) {
                status = "partial";
            }
            result.put("status", status);
            result.put("message", "Import completed");
            result.put("foldersCreated", importedFolders);
            result.put("documentsCreated", importedDocuments);
            if (importedRelationships > 0) {
                result.put("relationshipsCreated", importedRelationships);
            }
            if (!errors.isEmpty()) {
                result.put("errors", errors);
            }
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }

            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.IMPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, true, null);
            }

            return Response.status(Response.Status.OK)
                    .entity(result.toJSONString())
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (Exception e) {
            log.error("Import failed: " + e.getMessage(), e);
            result.put("status", "error");
            result.put("message", "Import failed: " + e.getMessage());
            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.IMPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, false, e.getMessage());
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(result.toJSONString())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Export folder contents as custom NemakiWare format ZIP.
     */
    @GET
    @Path("/export/{folderId}")
    @Produces("application/zip")
    public Response exportContent(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("folderId") String folderId,
            @Context HttpServletRequest request) {

        log.info("Export request received for repository: " + repositoryId + ", folder: " + folderId);

        try {
            ContentService cs = getContentService();
            if (cs == null) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"status\":\"error\",\"message\":\"ContentService not available\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            Folder folder = cs.getFolder(repositoryId, folderId);
            if (folder == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"status\":\"error\",\"message\":\"Folder not found\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            CallContext callContext = createCallContext(request, repositoryId);

            if (!hasReadPermission(cs, repositoryId, callContext, folder)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"status\":\"error\",\"message\":\"You do not have read permission on this folder\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            // Capture username before response is committed (request may not be available in StreamingOutput)
            final String exportUsername = getCallContextUsername(request);
            final String exportFolderName = folder.getName();

            StreamingOutput streamingOutput = new StreamingOutput() {
                @Override
                public void write(OutputStream output) throws IOException {
                    try (ZipOutputStream zos = new ZipOutputStream(output)) {
                        Set<String> customTypeIds = new HashSet<>();
                        try {
                            collectCustomTypeIds(repositoryId, folder, customTypeIds);
                        } catch (Exception e) {
                            log.warn("Failed to collect custom type definitions: " + e.getMessage(), e);
                        }

                        Set<String> exportedObjectIds = new HashSet<>();
                        zipExporter.exportFolderRecursive(repositoryId, folder, "", zos, callContext, exportedObjectIds);

                        try {
                            Set<String> relTypeIds = zipExporter.collectAndExportRelationships(repositoryId, exportedObjectIds, zos, callContext);
                            customTypeIds.addAll(relTypeIds);
                        } catch (Exception e) {
                            log.warn("Failed to export relationships: " + e.getMessage(), e);
                        }

                        try {
                            if (!customTypeIds.isEmpty()) {
                                zipExporter.exportTypeDefinitions(repositoryId, customTypeIds, zos);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to export type definitions: " + e.getMessage(), e);
                        }

                        publishZipFolderExportLineage(
                                repositoryId,
                                folderId,
                                exportFolderName,
                                exportUsername,
                                exportedObjectIds.size());

                        // Lineage Journal: EXPORT_ZIP_FOLDER
                        if (!exportedObjectIds.isEmpty()) {
                            LineageConfig lc = getLineageConfig();
                            LineageEventBuilder b = new LineageEventBuilder()
                                    .repositoryId(repositoryId)
                                    .processType(LineageProcessType.EXPORT_ZIP_FOLDER)
                                    .addInputObject(repositoryId, folderId)
                                    .snapshotAttribute("folderName", exportFolderName)
                                    .snapshotAttribute("objectCount", String.valueOf(exportedObjectIds.size()))
                                    .snapshotAttribute("requestedBy", exportUsername);
                            if (lc != null) {
                                b.targets(lc.getTargets());
                            }
                            emitLineageEvent(b.build());
                        }

                        // Audit after streaming completes successfully
                        AuditLogger audit = getAuditLogger();
                        if (audit != null) {
                            audit.logOperation(AuditOperation.EXPORT_EXECUTE, repositoryId,
                                    exportUsername, folderId, true, null);
                        }
                    } catch (Exception e) {
                        log.error("Export streaming failed: " + e.getMessage(), e);
                        AuditLogger audit = getAuditLogger();
                        if (audit != null) {
                            audit.logOperation(AuditOperation.EXPORT_EXECUTE, repositoryId,
                                    exportUsername, folderId, false, e.getMessage());
                        }
                        throw new IOException("Export failed: " + e.getMessage(), e);
                    }
                }
            };

            String fileName = folder.getName() + "_export.zip";

            return Response.ok(streamingOutput)
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .build();

        } catch (Exception e) {
            log.error("Export failed: " + e.getMessage(), e);
            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.EXPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, false, e.getMessage());
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"status\":\"error\",\"message\":\"Export failed: " + e.getMessage() + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /**
     * Export selected objects (documents and/or folders) as a ZIP archive.
     */
    @POST
    @Path("/export/objects")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/zip")
    public Response exportSelectedObjects(
            @PathParam("repositoryId") String repositoryId,
            @Context HttpServletRequest request,
            String body) {

        log.info("Export selected objects request for repository: " + repositoryId);

        try {
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(body);
            JSONArray objectIds = (JSONArray) json.get("objectIds");

            if (objectIds == null || objectIds.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"status\":\"error\",\"message\":\"objectIds is required and must not be empty\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            ContentService cs = getContentService();
            if (cs == null) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"status\":\"error\",\"message\":\"ContentService not available\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            CallContext callContext = createCallContext(request, repositoryId);

            List<Content> contents = new ArrayList<>();
            for (Object idObj : objectIds) {
                String objectId = (String) idObj;
                Content content = cs.getContent(repositoryId, objectId);
                if (content == null) {
                    log.warn("Export: object not found: " + objectId);
                    continue;
                }
                if (!hasReadPermission(cs, repositoryId, callContext, content)) {
                    log.info("Export: skipping '" + content.getName() + "' (no read permission)");
                    continue;
                }
                contents.add(content);
            }

            if (contents.isEmpty()) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"status\":\"error\",\"message\":\"No accessible objects to export\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            final String exportUsername = getCallContextUsername(request);
            StreamingOutput streamingOutput = new StreamingOutput() {
                @Override
                public void write(OutputStream output) throws IOException {
                    try (ZipOutputStream zos = new ZipOutputStream(output)) {
                        Set<String> customTypeIds = new HashSet<>();
                        try {
                            for (Content c : contents) {
                                if (c instanceof Folder) {
                                    collectCustomTypeIds(repositoryId, (Folder) c, customTypeIds);
                                } else if (c instanceof Document) {
                                    String objectType = c.getObjectType();
                                    if (objectType != null && !objectType.equals("cmis:document")) {
                                        customTypeIds.add(objectType);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to collect custom type definitions: " + e.getMessage(), e);
                        }

                        ContentService cs = getContentService();
                        Set<String> exportedObjectIds = new HashSet<>();
                        for (Content c : contents) {
                            if (c instanceof Folder) {
                                Folder folder = (Folder) c;
                                String folderPath = folder.getName();
                                zos.putNextEntry(new java.util.zip.ZipEntry(folderPath + "/"));
                                zos.closeEntry();
                                zipExporter.exportFolderRecursive(repositoryId, folder, folderPath, zos, callContext, exportedObjectIds);
                            } else if (c instanceof Document) {
                                Document doc = (Document) c;
                                exportedObjectIds.add(doc.getId());
                                zipExporter.exportSingleDocument(repositoryId, doc, doc.getName(), zos, callContext, cs);
                            }
                        }

                        try {
                            Set<String> relTypeIds = zipExporter.collectAndExportRelationships(repositoryId, exportedObjectIds, zos, callContext);
                            customTypeIds.addAll(relTypeIds);
                        } catch (Exception e) {
                            log.warn("Failed to export relationships: " + e.getMessage(), e);
                        }

                        try {
                            if (!customTypeIds.isEmpty()) {
                                zipExporter.exportTypeDefinitions(repositoryId, customTypeIds, zos);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to export type definitions: " + e.getMessage(), e);
                        }

                        publishSelectedObjectsExportLineage(
                                repositoryId,
                                contents,
                                exportUsername,
                                exportedObjectIds.size());

                        // Lineage Journal: EXPORT_SELECTED_OBJECTS
                        if (!exportedObjectIds.isEmpty()) {
                            LineageConfig lc = getLineageConfig();
                            LineageEventBuilder b = new LineageEventBuilder()
                                    .repositoryId(repositoryId)
                                    .processType(LineageProcessType.EXPORT_SELECTED_OBJECTS)
                                    .snapshotAttribute("objectCount", String.valueOf(exportedObjectIds.size()))
                                    .snapshotAttribute("requestedBy", exportUsername);
                            for (Content c : contents) {
                                b.addInputObject(repositoryId, c.getId());
                            }
                            if (lc != null) {
                                b.targets(lc.getTargets());
                            }
                            emitLineageEvent(b.build());
                        }
                    } catch (Exception e) {
                        log.error("Export streaming failed: " + e.getMessage(), e);
                        throw new IOException("Export failed: " + e.getMessage(), e);
                    }
                }
            };

            String fileName = "export_selected_" + System.currentTimeMillis() + ".zip";
            return Response.ok(streamingOutput)
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .build();

        } catch (ParseException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"status\":\"error\",\"message\":\"Invalid JSON body\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (Exception e) {
            log.error("Export selected objects failed: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"status\":\"error\",\"message\":\"Export failed: " + e.getMessage() + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    /**
     * Import content from a local filesystem directory (admin only).
     */
    @POST
    @Path("/filesystem/import/{folderId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public Response importFromFilesystem(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("folderId") String folderId,
            JSONObject requestBody,
            @Context HttpServletRequest request) {

        JSONObject response = new JSONObject();

        try {
            JSONArray adminErrMsg = new JSONArray();
            if (!checkAdmin(adminErrMsg, request)) {
                response.put("status", "error");
                response.put("message", "Admin access required for filesystem operations");
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(response.toJSONString())
                        .build();
            }

            CallContext callContext = createCallContext(request, repositoryId);

            if (requestBody == null) {
                response.put("status", "error");
                response.put("message", "Request body is required");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            String sourcePath = (String) requestBody.get("sourcePath");
            if (sourcePath == null || sourcePath.isEmpty()) {
                response.put("status", "error");
                response.put("message", "sourcePath is required");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            java.nio.file.Path sourceDir = Paths.get(sourcePath).toAbsolutePath().normalize();

            if (!isPathWithinAllowedRoots(sourceDir)) {
                response.put("status", "error");
                response.put("message", "Source path is not within allowed filesystem roots. " +
                        "Allowed roots: " + ALLOWED_FILESYSTEM_ROOTS);
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(response.toJSONString())
                        .build();
            }

            if (!java.nio.file.Files.exists(sourceDir)) {
                response.put("status", "error");
                response.put("message", "Source path does not exist: " + sourcePath);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }
            if (!java.nio.file.Files.isDirectory(sourceDir)) {
                response.put("status", "error");
                response.put("message", "Source path is not a directory: " + sourcePath);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            log.info("Starting filesystem import from: " + sourceDir + " to folder: " + folderId);

            ImportResult importResult = filesystemImporter.importFromFilesystemDirectory(repositoryId, folderId, sourceDir, callContext);

            response.put("status", importResult.errors.isEmpty() ? (importResult.warnings.isEmpty() ? "success" : "partial") : "error");
            response.put("foldersCreated", importResult.foldersCreated);
            response.put("documentsCreated", importResult.documentsCreated);
            if (!importResult.errors.isEmpty()) {
                JSONArray errorsArray = new JSONArray();
                errorsArray.addAll(importResult.errors);
                response.put("errors", errorsArray);
            }
            if (!importResult.warnings.isEmpty()) {
                JSONArray warningsArray = new JSONArray();
                warningsArray.addAll(importResult.warnings);
                response.put("warnings", warningsArray);
            }

            log.info("Filesystem import completed: " + importResult.documentsCreated + " documents, " +
                    importResult.foldersCreated + " folders created");

            publishFilesystemImportLineage(
                    repositoryId,
                    folderId,
                    sourceDir.toString(),
                    getCallContextUsername(request),
                    importResult);

            // Lineage Journal: IMPORT_FILESYSTEM
            {
                long objCount = (long) importResult.documentsCreated + importResult.foldersCreated;
                if (objCount > 0) {
                    LineageConfig lc = getLineageConfig();
                    LineageEventBuilder b = new LineageEventBuilder()
                            .repositoryId(repositoryId)
                            .processType(LineageProcessType.IMPORT_FILESYSTEM)
                            .addInput("file://" + sourceDir)
                            .addOutputObject(repositoryId, folderId)
                            .snapshotAttribute("sourcePath", sourceDir.toString())
                            .snapshotAttribute("objectCount", String.valueOf(objCount))
                            .snapshotAttribute("requestedBy", getCallContextUsername(request));
                    if (lc != null) {
                        b.targets(lc.getTargets());
                    }
                    emitLineageEvent(b.build());
                }
            }

            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.IMPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, true, null);
            }

            return Response.ok(response.toJSONString()).build();

        } catch (Exception e) {
            log.error("Filesystem import failed: " + e.getMessage(), e);
            response.put("status", "error");
            response.put("message", "Import failed: " + e.getMessage());
            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.IMPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, false, e.getMessage());
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(response.toJSONString())
                    .build();
        }
    }

    /**
     * Export content to a local filesystem directory (admin only).
     */
    @POST
    @Path("/filesystem/export/{folderId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @SuppressWarnings("unchecked")
    public Response exportToFilesystem(
            @PathParam("repositoryId") String repositoryId,
            @PathParam("folderId") String folderId,
            JSONObject requestBody,
            @Context HttpServletRequest request) {

        JSONObject response = new JSONObject();

        try {
            JSONArray adminErrMsg = new JSONArray();
            if (!checkAdmin(adminErrMsg, request)) {
                response.put("status", "error");
                response.put("message", "Admin access required for filesystem operations");
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(response.toJSONString())
                        .build();
            }

            CallContext callContext = createCallContext(request, repositoryId);

            if (requestBody == null) {
                response.put("status", "error");
                response.put("message", "Request body is required");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            String targetPath = (String) requestBody.get("targetPath");
            if (targetPath == null || targetPath.isEmpty()) {
                response.put("status", "error");
                response.put("message", "targetPath is required");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            Boolean allowOverwrite = (Boolean) requestBody.get("allowOverwrite");
            if (allowOverwrite == null) {
                allowOverwrite = false;
            }

            java.nio.file.Path targetDir = Paths.get(targetPath).toAbsolutePath().normalize();

            if (!isPathWithinAllowedRoots(targetDir)) {
                response.put("status", "error");
                response.put("message", "Target path is not within allowed filesystem roots. " +
                        "Allowed roots: " + ALLOWED_FILESYSTEM_ROOTS);
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(response.toJSONString())
                        .build();
            }

            if (!java.nio.file.Files.exists(targetDir)) {
                java.nio.file.Files.createDirectories(targetDir);
            }
            if (!java.nio.file.Files.isDirectory(targetDir)) {
                response.put("status", "error");
                response.put("message", "Target path is not a directory: " + targetPath);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            log.info("Starting filesystem export from folder: " + folderId + " to: " + targetDir +
                    " (allowOverwrite=" + allowOverwrite + ")");

            ContentService cs = getContentService();
            Folder folder = cs.getFolder(repositoryId, folderId);
            if (folder == null) {
                response.put("status", "error");
                response.put("message", "Folder not found: " + folderId);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(response.toJSONString())
                        .build();
            }

            ExportResult exportResult = filesystemExporter.exportToFilesystemDirectory(repositoryId, folder, targetDir, callContext, allowOverwrite);

            response.put("status", exportResult.errors.isEmpty() ? "success" : "partial");
            response.put("foldersExported", exportResult.foldersExported);
            response.put("documentsExported", exportResult.documentsExported);
            response.put("targetPath", targetDir.toString());
            if (!exportResult.errors.isEmpty()) {
                JSONArray errorsArray = new JSONArray();
                errorsArray.addAll(exportResult.errors);
                response.put("errors", errorsArray);
            }

            log.info("Filesystem export completed: " + exportResult.documentsExported + " documents, " +
                    exportResult.foldersExported + " folders exported");

            publishFilesystemExportLineage(
                    repositoryId,
                    folderId,
                    targetDir.toString(),
                    getCallContextUsername(request),
                    exportResult);

            // Lineage Journal: EXPORT_FILESYSTEM
            {
                long objCount = (long) exportResult.documentsExported + exportResult.foldersExported;
                if (objCount > 0) {
                    LineageConfig lc = getLineageConfig();
                    LineageEventBuilder b = new LineageEventBuilder()
                            .repositoryId(repositoryId)
                            .processType(LineageProcessType.EXPORT_FILESYSTEM)
                            .addInputObject(repositoryId, folderId)
                            .addOutput("file://" + targetDir)
                            .snapshotAttribute("targetPath", targetDir.toString())
                            .snapshotAttribute("objectCount", String.valueOf(objCount))
                            .snapshotAttribute("requestedBy", getCallContextUsername(request));
                    if (lc != null) {
                        b.targets(lc.getTargets());
                    }
                    emitLineageEvent(b.build());
                }
            }

            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.EXPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, true, null);
            }

            return Response.ok(response.toJSONString()).build();

        } catch (Exception e) {
            log.error("Filesystem export failed: " + e.getMessage(), e);
            response.put("status", "error");
            response.put("message", "Export failed: " + e.getMessage());
            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.EXPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, false, e.getMessage());
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(response.toJSONString())
                    .build();
        }
    }

    // ========== Utility ==========

    private CallContext createCallContext(HttpServletRequest request, String repositoryId) {
        CallContext filterContext = (CallContext) request.getAttribute("CallContext");
        if (filterContext != null) {
            return filterContext;
        }
        return new CallContext() {
            @Override
            public String getBinding() { return "browser"; }
            @Override
            public boolean isObjectInfoRequired() { return false; }
            @Override
            public Object get(String key) {
                if ("repositoryId".equals(key)) {
                    return repositoryId;
                }
                return null;
            }
            @Override
            public CmisVersion getCmisVersion() { return CmisVersion.CMIS_1_1; }
            @Override
            public String getRepositoryId() { return repositoryId; }
            @Override
            public String getUsername() { return null; }
            @Override
            public String getPassword() { return null; }
            @Override
            public String getLocale() { return "ja"; }
            @Override
            public BigInteger getOffset() { return null; }
            @Override
            public BigInteger getLength() { return null; }
            @Override
            public File getTempDirectory() { return null; }
            @Override
            public boolean encryptTempFiles() { return false; }
            @Override
            public int getMemoryThreshold() { return 4 * 1024 * 1024; }
            @Override
            public long getMaxContentSize() { return -1; }
        };
    }
}
