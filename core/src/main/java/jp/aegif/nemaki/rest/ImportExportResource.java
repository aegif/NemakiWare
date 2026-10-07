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
import jp.aegif.nemaki.rest.purview.journal.LineageEndpoint;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageFact;
import jp.aegif.nemaki.rest.purview.journal.LineageFactEmission;
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

    /**
     * True when the exception indicates the uploaded file is not a valid
     * archive (corrupt / wrong format) — a client error worth a 400 rather
     * than a 500. Checks the exception chain for zip/archive parse failures.
     */
    private boolean isMalformedArchive(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.zip.ZipException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("zip end header not found")
                        || m.contains("not in gzip format")
                        || m.contains("invalid entry")
                        || m.contains("invalid cen header")
                        || m.contains("central directory")
                        || m.contains("error in opening zip file")
                        || m.contains("archive is not")
                        || m.contains("truncated")) {
                    return true;
                }
            }
        }
        return false;
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
     * Whether the journal owns lineage for this repository, in which case the direct-Purview
     * helpers must skip (duplicate emission otherwise). Never throws, and the two null-ish
     * cases route differently on purpose: a <em>deliberately absent</em> config (no bean, no
     * Spring context) means lineage was never set up and direct publish stays available, while
     * a <em>failed lookup</em> answers {@code true} — failing toward "skip the direct publish"
     * rather than toward a duplicate emission when the journal is in fact active.
     */
    boolean journalOwnsLineage(String repositoryId) {
        org.springframework.context.ApplicationContext ctx;
        try {
            ctx = SpringContext.getApplicationContext();
        } catch (RuntimeException e) {
            log.warn("Lineage config lookup failed (treating as journal-owned): " + e.getMessage());
            return true;
        }
        if (ctx == null) {
            return false;
        }
        LineageConfig lc;
        try {
            lc = ctx.getBean(LineageConfig.class);
        } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
            return false;
        } catch (RuntimeException e) {
            log.warn("Lineage config lookup failed (treating as journal-owned): " + e.getMessage());
            return true;
        }
        try {
            return lc.getModeForRepository(repositoryId) != LineageMode.DISABLED;
        } catch (RuntimeException e) {
            log.warn("Lineage mode resolution failed (treating as journal-owned): " + e.getMessage());
            return true;
        }
    }

    /** The active emitter for this repository, or {@code null} when lineage is off. */
    private LineageEmitter resolveLineageEmitter(String repositoryId) {
        try {
            LineageConfig config = getLineageConfig();
            if (config == null) return null;
            LineageMode mode = config.getModeForRepository(repositoryId);
            if (mode == LineageMode.DISABLED) return null;
            LineageJournalStore store = SpringContext.getApplicationContext()
                    .getBean(LineageJournalStore.class);
            @SuppressWarnings("unchecked")
            java.util.List<LineageTargetSink> sinks = (java.util.List<LineageTargetSink>)
                    (java.util.List<?>) SpringContext.getApplicationContext()
                    .getBeansOfType(LineageTargetSink.class).values().stream().toList();
            return config.createEmitterForMode(mode, store, sinks);
        } catch (Exception e) {
            log.warn("Lineage emitter resolution failed (non-fatal): " + e.getMessage());
            return null;
        }
    }

    /** Lineage-only config read; never throws (fail-open — a producer must not fail the op). */
    private java.util.List<String> lineageTargets() {
        try {
            LineageConfig lc = getLineageConfig();
            return lc != null ? lc.getTargets() : java.util.List.of();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** The created content as typed endpoints — what the v2 import shape carries as outputs. */
    private static java.util.List<LineageEndpoint> createdContentEndpoints(
            String repositoryId, java.util.List<ImportExportUtils.CreatedObject> created) {
        java.util.List<LineageEndpoint> endpoints = new java.util.ArrayList<>(created.size());
        for (ImportExportUtils.CreatedObject c : created) {
            endpoints.add(c.folder()
                    ? LineageEndpoint.folder(repositoryId, c.objectId(), c.name())
                    : LineageEndpoint.document(repositoryId, c.objectId(), c.name()));
        }
        return endpoints;
    }

    /** The exported content as typed endpoints — what the v2 export shape carries as inputs. */
    private static java.util.List<LineageEndpoint> exportedContentEndpoints(
            String repositoryId, java.util.List<ImportExportUtils.ExportedObject> exported) {
        java.util.List<LineageEndpoint> endpoints = new java.util.ArrayList<>(exported.size());
        for (ImportExportUtils.ExportedObject e : exported) {
            endpoints.add(e.folder()
                    ? LineageEndpoint.folder(repositoryId, e.objectId(), e.name())
                    : LineageEndpoint.document(repositoryId, e.objectId(), e.name()));
        }
        return endpoints;
    }

    // ------------------------------------------------------------------
    // Lineage fact factories. Package-visible and static on purpose: the preservation tests
    // exercise THESE — the exact construction production runs — so a changed legacy string,
    // guard value, snapshot key or artifact kind fails a test instead of splitting every
    // catalog Process identity at the next deploy.
    // ------------------------------------------------------------------

    static LineageFact uploadedImportFact(String repositoryId, String folderId, String importMode,
            String requestedBy, long objCount,
            java.util.List<ImportExportUtils.CreatedObject> createdObjects,
            java.util.List<String> targets, String operationId, String occurredAt) {
        java.util.Map<String, String> v1Snapshot = new java.util.LinkedHashMap<>();
        v1Snapshot.put("importMode", importMode);
        v1Snapshot.put("objectCount", String.valueOf(objCount));
        v1Snapshot.put("requestedBy", requestedBy);
        return new LineageFact(
                repositoryId,
                LineageProcessType.IMPORT_UPLOADED,
                operationId,
                occurredAt,
                java.util.List.of(LineageEndpoint.importArtifact(
                        repositoryId, operationId, importMode, null)),
                createdContentEndpoints(repositoryId, createdObjects),
                targets,
                null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.IMPORT_UPLOADED,
                        java.util.List.of("upload://" + importMode),
                        java.util.List.of(LineageEvent.qualifiedName(repositoryId, folderId)),
                        v1Snapshot));
    }

    static LineageFact filesystemImportFact(String repositoryId, String folderId, String sourceDir,
            String requestedBy, long objCount,
            java.util.List<ImportExportUtils.CreatedObject> createdObjects,
            java.util.List<String> targets, String operationId, String occurredAt) {
        java.util.Map<String, String> v1Snapshot = new java.util.LinkedHashMap<>();
        v1Snapshot.put("sourcePath", sourceDir);
        v1Snapshot.put("objectCount", String.valueOf(objCount));
        v1Snapshot.put("requestedBy", requestedBy);
        return new LineageFact(
                repositoryId,
                LineageProcessType.IMPORT_FILESYSTEM,
                operationId,
                occurredAt,
                java.util.List.of(LineageEndpoint.importArtifact(
                        repositoryId, operationId, "filesystem", null)),
                createdContentEndpoints(repositoryId, createdObjects),
                targets,
                null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.IMPORT_FILESYSTEM,
                        java.util.List.of("file://" + sourceDir),
                        java.util.List.of(LineageEvent.qualifiedName(repositoryId, folderId)),
                        v1Snapshot));
    }

    static LineageFact zipFolderExportFact(String repositoryId, String folderId, String folderName,
            String requestedBy, long legacyObjectCount,
            java.util.List<ImportExportUtils.ExportedObject> exportedObjects,
            String zipFileName, java.util.List<String> targets, String operationId,
            String occurredAt) {
        java.util.List<LineageEndpoint> movedContent =
                exportedContentEndpoints(repositoryId, exportedObjects);
        // An empty folder exports nothing but v1 still emitted (the legacy id set counts the
        // root): the folder itself is then the only honest typed input.
        java.util.List<LineageEndpoint> typedInputs = movedContent.isEmpty()
                ? java.util.List.of(LineageEndpoint.folder(repositoryId, folderId, folderName))
                : movedContent;
        java.util.Map<String, String> v1Snapshot = new java.util.LinkedHashMap<>();
        v1Snapshot.put("folderName", folderName);
        v1Snapshot.put("objectCount", String.valueOf(legacyObjectCount));
        v1Snapshot.put("requestedBy", requestedBy);
        return new LineageFact(
                repositoryId,
                LineageProcessType.EXPORT_ZIP_FOLDER,
                operationId,
                occurredAt,
                typedInputs,
                java.util.List.of(LineageEndpoint.exportArtifact(
                        repositoryId, operationId, "ZIP", zipFileName, (long) movedContent.size())),
                targets,
                null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.EXPORT_ZIP_FOLDER,
                        java.util.List.of(LineageEvent.qualifiedName(repositoryId, folderId)),
                        java.util.List.of(),
                        v1Snapshot));
    }

    static LineageFact selectedObjectsExportFact(String repositoryId,
            java.util.List<String> selectedObjectIds, String requestedBy, long legacyObjectCount,
            java.util.List<ImportExportUtils.ExportedObject> exportedObjects,
            String zipFileName, java.util.List<String> targets, String operationId,
            String occurredAt) {
        java.util.List<String> v1Inputs = new java.util.ArrayList<>(selectedObjectIds.size());
        for (String selectedId : selectedObjectIds) {
            v1Inputs.add(LineageEvent.qualifiedName(repositoryId, selectedId));
        }
        java.util.Map<String, String> v1Snapshot = new java.util.LinkedHashMap<>();
        v1Snapshot.put("objectCount", String.valueOf(legacyObjectCount));
        v1Snapshot.put("requestedBy", requestedBy);
        java.util.List<LineageEndpoint> movedContent =
                exportedContentEndpoints(repositoryId, exportedObjects);
        return new LineageFact(
                repositoryId,
                LineageProcessType.EXPORT_SELECTED_OBJECTS,
                operationId,
                occurredAt,
                movedContent,
                java.util.List.of(LineageEndpoint.exportArtifact(
                        repositoryId, operationId, "ZIP", zipFileName, (long) movedContent.size())),
                targets,
                null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.EXPORT_SELECTED_OBJECTS,
                        v1Inputs,
                        java.util.List.of(),
                        v1Snapshot));
    }

    static LineageFact filesystemExportFact(String repositoryId, String folderId, String targetDir,
            String requestedBy, long objCount,
            java.util.List<ImportExportUtils.ExportedObject> exportedObjects,
            java.util.List<String> targets, String operationId, String occurredAt) {
        java.util.Map<String, String> v1Snapshot = new java.util.LinkedHashMap<>();
        v1Snapshot.put("targetPath", targetDir);
        v1Snapshot.put("objectCount", String.valueOf(objCount));
        v1Snapshot.put("requestedBy", requestedBy);
        java.util.List<LineageEndpoint> movedContent =
                exportedContentEndpoints(repositoryId, exportedObjects);
        return new LineageFact(
                repositoryId,
                LineageProcessType.EXPORT_FILESYSTEM,
                operationId,
                occurredAt,
                movedContent,
                java.util.List.of(LineageEndpoint.exportArtifact(
                        repositoryId, operationId, "FILESYSTEM", targetDir, (long) movedContent.size())),
                targets,
                null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.EXPORT_FILESYSTEM,
                        java.util.List.of(LineageEvent.qualifiedName(repositoryId, folderId)),
                        java.util.List.of("file://" + targetDir),
                        v1Snapshot));
    }

    private void publishFilesystemImportLineage(
            String repositoryId,
            String folderId,
            String sourcePath,
            String requestedBy,
            ImportResult importResult) {
        // When journal is active, it owns lineage for this processType.
        // Skip direct Purview call to avoid duplicate emission.
        if (journalOwnsLineage(repositoryId)) {
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
        if (journalOwnsLineage(repositoryId)) {
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
        if (journalOwnsLineage(repositoryId)) {
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
        if (journalOwnsLineage(repositoryId)) {
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
        if (journalOwnsLineage(repositoryId)) {
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
        // Method-scoped so failure responses can carry it once issued (§3: once a mutation may
        // have happened, the correlation id is exactly what the caller needs).
        String issuedLineageOperationId = null;

        String csrfError = validateCsrfProtection(request);
        if (csrfError != null) {
            result.put("status", "error");
            result.put("message", "CSRF validation failed: " + csrfError);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(result.toJSONString())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

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

            // Reject unsupported formats BEFORE issuing the operation id: nothing has mutated
            // yet, so this rejection carries no id — and every return after issuance must.
            if (format != ImportFormat.ACP && format != ImportFormat.CUSTOM) {
                result.put("status", "error");
                result.put("message", "Unknown or unsupported archive format");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(result.toJSONString()).build();
            }

            // §3: the lineage operation id is issued when the business operation starts —
            // before the mutation the importer performs — and returned to the caller.
            issuedLineageOperationId = java.util.UUID.randomUUID().toString();
            final String lineageOperationId = issuedLineageOperationId;
            int importedRelationships = 0;
            ImportResult importOutcome = null;
            if (format == ImportFormat.ACP) {
                ImportResult acpResult = zipImporter.importAcpFormat(repositoryId, folderId, tempFile, callContext);
                importOutcome = acpResult;
                importedFolders = acpResult.foldersCreated;
                importedDocuments = acpResult.documentsCreated;
                errors.addAll(acpResult.errors);
                warnings.addAll(acpResult.warnings);
            } else if (format == ImportFormat.CUSTOM) {
                ImportResult customResult = zipImporter.importCustomFormat(repositoryId, folderId, tempFile, callContext);
                importOutcome = customResult;
                importedFolders = customResult.foldersCreated;
                importedDocuments = customResult.documentsCreated;
                importedRelationships = customResult.relationshipsCreated;
                errors.addAll(customResult.errors);
                warnings.addAll(customResult.warnings);
            }

            publishUploadedImportLineage(
                    repositoryId,
                    folderId,
                    format == ImportFormat.ACP ? "acp-upload" : "zip-upload",
                    getCallContextUsername(request),
                    (long) importedFolders + importedDocuments);

            // Lineage: one version-free fact (v1 strings verbatim; the emission guard is v1's).
            {
                long objCount = (long) importedFolders + importedDocuments;
                if (objCount > 0) {
                    String importMode = format == ImportFormat.ACP ? "acp-upload" : "zip-upload";
                    String requestedBy = getCallContextUsername(request);
                    final ImportResult lineageOutcome = importOutcome;
                    LineageFactEmission.emitSafely(resolveLineageEmitter(repositoryId), () ->
                            uploadedImportFact(repositoryId, folderId, importMode, requestedBy,
                                    objCount, lineageOutcome.createdObjects, lineageTargets(),
                                    lineageOperationId, java.time.Instant.now().toString()),
                            "repo=" + repositoryId + " op=" + lineageOperationId + " type=IMPORT_UPLOADED");
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

            result.put("operationId", lineageOperationId);
            return Response.status(Response.Status.OK)
                    .entity(result.toJSONString())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Nemaki-Operation-Id", lineageOperationId)
                    .build();

        } catch (Exception e) {
            // A corrupt / non-archive upload is a CLIENT error (bad file), not a
            // server fault — return 400 for it instead of 500. Genuine server-side
            // failures still surface as 500.
            boolean badArchive = isMalformedArchive(e);
            if (badArchive) {
                log.warn("Import rejected (malformed archive): " + e.getMessage());
            } else {
                log.error("Import failed: " + e.getMessage(), e);
            }
            result.put("status", "error");
            result.put("message", "Import failed: " + e.getMessage());
            if (issuedLineageOperationId != null) {
                result.put("operationId", issuedLineageOperationId);
            }
            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.IMPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, false, e.getMessage());
            }
            Response.ResponseBuilder errorResponse = Response
                    .status(badArchive ? Response.Status.BAD_REQUEST : Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(result.toJSONString())
                    .type(MediaType.APPLICATION_JSON);
            if (issuedLineageOperationId != null) {
                errorResponse.header("X-Nemaki-Operation-Id", issuedLineageOperationId);
            }
            return errorResponse.build();
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
            // §3: issued before the export begins; returned in X-Nemaki-Operation-Id (the body
            // is the ZIP, so the header is the only channel).
            final String lineageOperationId = java.util.UUID.randomUUID().toString();
            final String exportZipFileName = folder.getName() + "_export.zip";

            StreamingOutput streamingOutput = new StreamingOutput() {
                @Override
                public void write(OutputStream output) throws IOException {
                    // close() calls finish(), which writes the ZIP central directory — so
                    // with try-with-resources the client received a 200 and an archive that
                    // OPENS, with the last entry silently truncated. A review measured it:
                    // the "no central directory" this refusal was documented to produce only
                    // held because the response had not been committed yet (a one-document
                    // export fits the container buffer).
                    //
                    // Leaving it unclosed on the refusal path fixed that and leaked the
                    // deflater — a second review caught THAT. Both properties are wanted, so
                    // the archive is always closed, and on the refusal path it is closed
                    // into a stream that has stopped forwarding: the directory is produced
                    // and discarded, the deflater is freed, and the client's response ends
                    // where the failure happened.
                    jp.aegif.nemaki.rest.importexport.ImportExportUtils.DiscardableOutputStream sink =
                            new jp.aegif.nemaki.rest.importexport.ImportExportUtils.DiscardableOutputStream(output);
                    ZipOutputStream zos = new ZipOutputStream(sink);
                    try {
                        Set<String> customTypeIds = new HashSet<>();
                        try {
                            collectCustomTypeIds(repositoryId, folder, customTypeIds);
                        } catch (Exception e) {
                            // Warned and carried on: the walk that decides WHICH type
                            // definitions the archive needs failed, so .nemaki-types/ was
                            // written from a short list — and the package still unpacked.
                            // The archive is the response body; an aborted stream is the only
                            // way it can say "incomplete".
                            throw new ZipExporter.ExportRefusedException(
                                    "the custom types used by this folder could not be"
                                            + " collected, so the archive's type definitions"
                                            + " would be incomplete", e);
                        }

                        Set<String> exportedObjectIds = new HashSet<>();
                        ImportExportUtils.ExportedObjectCollector exportedObjects =
                                new ImportExportUtils.ExportedObjectCollector();
                        zipExporter.exportFolderRecursive(repositoryId, folder, "", zos, callContext,
                                exportedObjectIds, exportedObjects);

                        try {
                            Set<String> relTypeIds = zipExporter.collectAndExportRelationships(repositoryId, exportedObjectIds, zos, callContext);
                            customTypeIds.addAll(relTypeIds);
                        } catch (ZipExporter.ExportRefusedException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new ZipExporter.ExportRefusedException(
                                    "the relationships between the exported objects could not"
                                            + " be written, so the archive would say they have"
                                            + " none", e);
                        }

                        try {
                            if (!customTypeIds.isEmpty()) {
                                zipExporter.exportTypeDefinitions(repositoryId, customTypeIds, zos);
                            }
                        } catch (ZipExporter.ExportRefusedException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new ZipExporter.ExportRefusedException(
                                    "the type definitions this archive refers to could not be"
                                            + " written; the importer cannot restore it", e);
                        }

                        // The artifact exists only once the ZIP central directory is written —
                        // lineage describes a completed export, so finish() comes first.
                        zos.finish();

                        publishZipFolderExportLineage(
                                repositoryId,
                                folderId,
                                exportFolderName,
                                exportUsername,
                                exportedObjectIds.size());

                        // Lineage: one version-free fact. The emission guard and the v1 strings
                        // (folder input, no output, objectCount incl. the root) are v1's,
                        // verbatim; the typed side carries the moved content itself.
                        if (!exportedObjectIds.isEmpty()) {
                            LineageFactEmission.emitSafely(resolveLineageEmitter(repositoryId), () ->
                                    zipFolderExportFact(repositoryId, folderId, exportFolderName,
                                            exportUsername, exportedObjectIds.size(),
                                            exportedObjects.asList(), exportZipFileName,
                                            lineageTargets(), lineageOperationId,
                                            java.time.Instant.now().toString()),
                                    "repo=" + repositoryId + " op=" + lineageOperationId + " type=EXPORT_ZIP_FOLDER");
                        }

                        // Audit after streaming completes successfully
                        AuditLogger audit = getAuditLogger();
                        if (audit != null) {
                            audit.logOperation(AuditOperation.EXPORT_EXECUTE, repositoryId,
                                    exportUsername, folderId, true, null);
                        }
                        zos.close();
                    } catch (Exception e) {
                        // Stop forwarding FIRST, then close: the close writes the central
                        // directory into the discard, so the deflater is freed and the
                        // client still gets a stream that ends without its directory.
                        sink.stopForwarding();
                        closeQuietly(zos);
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

            return Response.ok(streamingOutput)
                    .header("Content-Disposition",
                            jp.aegif.nemaki.rest.importexport.ImportExportUtils
                                    .contentDispositionAttachment(exportZipFileName))
                    .header("X-Nemaki-Operation-Id", lineageOperationId)
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

        String csrfError = validateCsrfProtection(request);
        if (csrfError != null) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"status\":\"error\",\"message\":\"CSRF validation failed: " + csrfError + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

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
            // §3: issued before the export begins; the response body is the ZIP, so the id
            // travels in X-Nemaki-Operation-Id. The file name is evaluated once, here, so the
            // artifact attribute and the Content-Disposition header cannot disagree.
            final String lineageOperationId = java.util.UUID.randomUUID().toString();
            final String exportZipFileName = "export_selected_" + System.currentTimeMillis() + ".zip";
            StreamingOutput streamingOutput = new StreamingOutput() {
                @Override
                public void write(OutputStream output) throws IOException {
                    // close() calls finish(), which writes the ZIP central directory — so
                    // with try-with-resources the client received a 200 and an archive that
                    // OPENS, with the last entry silently truncated. A review measured it:
                    // the "no central directory" this refusal was documented to produce only
                    // held because the response had not been committed yet (a one-document
                    // export fits the container buffer).
                    //
                    // Leaving it unclosed on the refusal path fixed that and leaked the
                    // deflater — a second review caught THAT. Both properties are wanted, so
                    // the archive is always closed, and on the refusal path it is closed
                    // into a stream that has stopped forwarding: the directory is produced
                    // and discarded, the deflater is freed, and the client's response ends
                    // where the failure happened.
                    jp.aegif.nemaki.rest.importexport.ImportExportUtils.DiscardableOutputStream sink =
                            new jp.aegif.nemaki.rest.importexport.ImportExportUtils.DiscardableOutputStream(output);
                    ZipOutputStream zos = new ZipOutputStream(sink);
                    try {
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
                            // The objects-export sibling of the folder-export refusal above.
                            // Fixed one and left the other, which is the shape this batch has
                            // spent five rounds on; the control run is what showed it.
                            throw new ZipExporter.ExportRefusedException(
                                    "the custom types used by the selected objects could not"
                                            + " be collected, so the archive's type"
                                            + " definitions would be incomplete", e);
                        }

                        ContentService cs = getContentService();
                        Set<String> exportedObjectIds = new HashSet<>();
                        ImportExportUtils.ExportedObjectCollector exportedObjects =
                                new ImportExportUtils.ExportedObjectCollector();
                        for (Content c : contents) {
                            if (c instanceof Folder) {
                                Folder folder = (Folder) c;
                                // Sanitize the top-level export name: object names
                                // are user-controllable and must not produce ZIP
                                // entries with traversal/separators.
                                String folderPath = sanitizeExportName(folder.getName());
                                zos.putNextEntry(new java.util.zip.ZipEntry(folderPath + "/"));
                                zos.closeEntry();
                                // A selected folder is itself moved content (unlike the
                                // whole-folder export's container root), and the non-empty
                                // basePath makes the recursion record it.
                                zipExporter.exportFolderRecursive(repositoryId, folder, folderPath, zos, callContext,
                                        exportedObjectIds, exportedObjects);
                            } else if (c instanceof Document) {
                                Document doc = (Document) c;
                                exportedObjectIds.add(doc.getId());
                                exportedObjects.record(doc.getId(), doc.getName(), false);
                                zipExporter.exportSingleDocument(repositoryId, doc, sanitizeExportName(doc.getName()), zos, callContext, cs);
                            }
                        }

                        try {
                            Set<String> relTypeIds = zipExporter.collectAndExportRelationships(repositoryId, exportedObjectIds, zos, callContext);
                            customTypeIds.addAll(relTypeIds);
                        } catch (ZipExporter.ExportRefusedException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new ZipExporter.ExportRefusedException(
                                    "the relationships between the exported objects could not"
                                            + " be written, so the archive would say they have"
                                            + " none", e);
                        }

                        try {
                            if (!customTypeIds.isEmpty()) {
                                zipExporter.exportTypeDefinitions(repositoryId, customTypeIds, zos);
                            }
                        } catch (ZipExporter.ExportRefusedException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new ZipExporter.ExportRefusedException(
                                    "the type definitions this archive refers to could not be"
                                            + " written; the importer cannot restore it", e);
                        }

                        // The artifact exists only once the ZIP central directory is written.
                        zos.finish();

                        publishSelectedObjectsExportLineage(
                                repositoryId,
                                contents,
                                exportUsername,
                                exportedObjectIds.size());

                        // Lineage: one version-free fact. v1 keeps the top-level selection as
                        // its inputs (order and multiplicity verbatim); the typed side carries
                        // everything the ZIP actually holds.
                        if (!exportedObjectIds.isEmpty()) {
                            LineageFactEmission.emitSafely(resolveLineageEmitter(repositoryId), () -> {
                                java.util.List<String> selectedIds = new java.util.ArrayList<>();
                                for (Content c : contents) {
                                    selectedIds.add(c.getId());
                                }
                                return selectedObjectsExportFact(repositoryId, selectedIds,
                                        exportUsername, exportedObjectIds.size(),
                                        exportedObjects.asList(), exportZipFileName,
                                        lineageTargets(), lineageOperationId,
                                        java.time.Instant.now().toString());
                            }, "repo=" + repositoryId + " op=" + lineageOperationId + " type=EXPORT_SELECTED_OBJECTS");
                        }
                        zos.close();
                    } catch (Exception e) {
                        sink.stopForwarding();
                        closeQuietly(zos);
                        log.error("Export streaming failed: " + e.getMessage(), e);
                        throw new IOException("Export failed: " + e.getMessage(), e);
                    }
                }
            };

            return Response.ok(streamingOutput)
                    .header("Content-Disposition",
                            jp.aegif.nemaki.rest.importexport.ImportExportUtils
                                    .contentDispositionAttachment(exportZipFileName))
                    .header("X-Nemaki-Operation-Id", lineageOperationId)
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
        // Method-scoped so failure responses can carry it once issued (§3).
        String issuedLineageOperationId = null;

        String csrfError = validateCsrfProtection(request);
        if (csrfError != null) {
            response.put("status", "error");
            response.put("message", "CSRF validation failed: " + csrfError);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(response.toJSONString())
                    .build();
        }

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

            // §3: issued before the mutation the importer performs.
            issuedLineageOperationId = java.util.UUID.randomUUID().toString();
            final String lineageOperationId = issuedLineageOperationId;
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

            // Lineage: one version-free fact (v1 strings verbatim; guard is v1's). sourcePath
            // has no v2 home yet — importMode carries the artifact's classification and the
            // path stays in the v1 snapshot (the IMPORT_ARTIFACT allowlist has no sourcePath).
            {
                long objCount = (long) importResult.documentsCreated + importResult.foldersCreated;
                if (objCount > 0) {
                    String requestedBy = getCallContextUsername(request);
                    LineageFactEmission.emitSafely(resolveLineageEmitter(repositoryId), () ->
                            filesystemImportFact(repositoryId, folderId, sourceDir.toString(),
                                    requestedBy, objCount, importResult.createdObjects,
                                    lineageTargets(), lineageOperationId,
                                    java.time.Instant.now().toString()),
                            "repo=" + repositoryId + " op=" + lineageOperationId + " type=IMPORT_FILESYSTEM");
                }
            }

            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.IMPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, true, null);
            }

            response.put("operationId", lineageOperationId);
            return Response.ok(response.toJSONString())
                    .header("X-Nemaki-Operation-Id", lineageOperationId)
                    .build();

        } catch (Exception e) {
            log.error("Filesystem import failed: " + e.getMessage(), e);
            response.put("status", "error");
            response.put("message", "Import failed: " + e.getMessage());
            if (issuedLineageOperationId != null) {
                response.put("operationId", issuedLineageOperationId);
            }
            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.IMPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, false, e.getMessage());
            }
            Response.ResponseBuilder errorResponse = Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(response.toJSONString());
            if (issuedLineageOperationId != null) {
                errorResponse.header("X-Nemaki-Operation-Id", issuedLineageOperationId);
            }
            return errorResponse.build();
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
        // Method-scoped so failure responses can carry it once issued (§3).
        String issuedLineageOperationId = null;

        String csrfError = validateCsrfProtection(request);
        if (csrfError != null) {
            response.put("status", "error");
            response.put("message", "CSRF validation failed: " + csrfError);
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(response.toJSONString())
                    .build();
        }

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

            // Every rejection below happens BEFORE the operation id is issued — nothing has
            // mutated yet, so they carry no id, and every return after issuance must.
            if (java.nio.file.Files.exists(targetDir)
                    && !java.nio.file.Files.isDirectory(targetDir)) {
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

            // §3: issued before the first mutation — creating the target directory is already
            // part of the export. createDirectories is called unconditionally: it is a no-op
            // for an existing directory and throws for a file, which closes the TOCTOU window
            // an exists-guard would open (a file racing into place after the check above would
            // otherwise skip creation and let the export run against a non-directory).
            issuedLineageOperationId = java.util.UUID.randomUUID().toString();
            final String lineageOperationId = issuedLineageOperationId;
            java.nio.file.Files.createDirectories(targetDir);

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

            // Lineage: one version-free fact (v1 strings verbatim; guard is v1's). The typed
            // side: the recursively exported content became a FILESYSTEM artifact named by the
            // target directory; the source container travels only in the v1 strings.
            {
                long objCount = (long) exportResult.documentsExported + exportResult.foldersExported;
                if (objCount > 0) {
                    String requestedBy = getCallContextUsername(request);
                    LineageFactEmission.emitSafely(resolveLineageEmitter(repositoryId), () ->
                            filesystemExportFact(repositoryId, folderId, targetDir.toString(),
                                    requestedBy, objCount, exportResult.exportedObjects,
                                    lineageTargets(), lineageOperationId,
                                    java.time.Instant.now().toString()),
                            "repo=" + repositoryId + " op=" + lineageOperationId + " type=EXPORT_FILESYSTEM");
                }
            }

            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.EXPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, true, null);
            }

            response.put("operationId", lineageOperationId);
            return Response.ok(response.toJSONString())
                    .header("X-Nemaki-Operation-Id", lineageOperationId)
                    .build();

        } catch (Exception e) {
            log.error("Filesystem export failed: " + e.getMessage(), e);
            response.put("status", "error");
            response.put("message", "Export failed: " + e.getMessage());
            if (issuedLineageOperationId != null) {
                response.put("operationId", issuedLineageOperationId);
            }
            AuditLogger audit = getAuditLogger();
            if (audit != null) {
                audit.logOperation(AuditOperation.EXPORT_EXECUTE, repositoryId,
                        getCallContextUsername(request), folderId, false, e.getMessage());
            }
            Response.ResponseBuilder errorResponse = Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(response.toJSONString());
            if (issuedLineageOperationId != null) {
                errorResponse.header("X-Nemaki-Operation-Id", issuedLineageOperationId);
            }
            return errorResponse.build();
        }
    }

    // ========== Utility ==========

    /**
     * Closes a refused archive without letting the close itself replace the refusal.
     *
     * <p>Called only after the sink has stopped forwarding, so what this frees is the native
     * deflater and what it writes goes nowhere.
     */
    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // The refusal is the story; a failure to close the discarded wrapper is not.
        }
    }


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
