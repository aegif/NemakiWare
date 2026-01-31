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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * REST Resource for Import/Export operations.
 * 
 * Supports:
 * - ACP (Alfresco Content Package) format import
 * - Custom NemakiWare format import/export with distributed JSON metadata
 */
@Path("/repo/{repositoryId}/importexport")
public class ImportExportResource extends ResourceBase {

    private static final Log log = LogFactory.getLog(ImportExportResource.class);

    private ContentService contentService;

    // Custom format naming conventions
    private static final String META_SUFFIX = ".meta.json";
    private static final String VERSION_PREFIX = ".v";
    private static final String TYPE_DEFINITIONS_DIR = ".nemaki-types/";
    private static final String TYPE_DEFINITION_SUFFIX = ".type.json";

    // Base type IDs that should not be exported
    private static final Set<String> BASE_TYPE_IDS = new HashSet<>();
    static {
        BASE_TYPE_IDS.add("cmis:document");
        BASE_TYPE_IDS.add("cmis:folder");
        BASE_TYPE_IDS.add("cmis:relationship");
        BASE_TYPE_IDS.add("cmis:policy");
        BASE_TYPE_IDS.add("cmis:item");
        BASE_TYPE_IDS.add("cmis:secondary");
    }

    // Size limits for import (prevent OOM)
    private static final long MAX_UPLOAD_SIZE = 500 * 1024 * 1024; // 500MB max upload
    private static final long MAX_SINGLE_FILE_SIZE = 100 * 1024 * 1024; // 100MB max per file

    // Allowed filesystem root paths for import/export (sandbox protection)
    // Configure via system property: nemakiware.filesystem.allowed.roots
    // Default: /tmp/nemakiware-import, /tmp/nemakiware-export
    private static final List<String> ALLOWED_FILESYSTEM_ROOTS;
    static {
        String configuredRoots = System.getProperty("nemakiware.filesystem.allowed.roots");
        if (configuredRoots != null && !configuredRoots.isEmpty()) {
            ALLOWED_FILESYSTEM_ROOTS = new ArrayList<>();
            for (String root : configuredRoots.split(",")) {
                String trimmed = root.trim();
                if (!trimmed.isEmpty()) {
                    ALLOWED_FILESYSTEM_ROOTS.add(trimmed);
                }
            }
        } else {
            // Default allowed roots
            ALLOWED_FILESYSTEM_ROOTS = new ArrayList<>();
            ALLOWED_FILESYSTEM_ROOTS.add("/tmp/nemakiware-import");
            ALLOWED_FILESYSTEM_ROOTS.add("/tmp/nemakiware-export");
        }
    }

    // Permission mapping from Alfresco to NemakiWare
    private static final Map<String, String> PERMISSION_MAPPING = new HashMap<>();
    static {
        PERMISSION_MAPPING.put("Consumer", "cmis:read");
        PERMISSION_MAPPING.put("Contributor", "cmis:write");
        PERMISSION_MAPPING.put("Coordinator", "cmis:all");
        PERMISSION_MAPPING.put("Editor", "cmis:write");
        PERMISSION_MAPPING.put("Collaborator", "cmis:write");
    }

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    private ContentService getContentService() {
        if (contentService != null) {
            return contentService;
        }
        try {
            ContentService service = SpringContext.getApplicationContext()
                    .getBean("ContentService", ContentService.class);
            if (service != null) {
                log.debug("ContentService retrieved from SpringContext successfully");
                return service;
            }
        } catch (Exception e) {
            log.error("Failed to get ContentService from SpringContext: " + e.getMessage(), e);
        }
        try {
            ContentService service = SpringContext.getApplicationContext()
                    .getBean("contentService", ContentService.class);
            if (service != null) {
                log.debug("ContentService retrieved from SpringContext with lowercase name");
                return service;
            }
        } catch (Exception e) {
            log.debug("Could not find contentService with lowercase name: " + e.getMessage());
        }
        log.error("ContentService is null and SpringContext fallback failed");
        return null;
    }

    private TypeService getTypeService() {
        try {
            return SpringContext.getApplicationContext()
                    .getBean("TypeService", TypeService.class);
        } catch (Exception e) {
            log.debug("Failed to get TypeService: " + e.getMessage());
        }
        try {
            return SpringContext.getApplicationContext()
                    .getBean("typeService", TypeService.class);
        } catch (Exception e) {
            log.debug("Failed to get typeService: " + e.getMessage());
        }
        return null;
    }

    private TypeManager getTypeManager() {
        try {
            return SpringContext.getApplicationContext()
                    .getBean("TypeManager", TypeManager.class);
        } catch (Exception e) {
            log.debug("Failed to get TypeManager: " + e.getMessage());
        }
        try {
            return SpringContext.getApplicationContext()
                    .getBean("typeManager", TypeManager.class);
        } catch (Exception e) {
            log.debug("Failed to get typeManager: " + e.getMessage());
        }
        return null;
    }

    /**
     * Import content from ACP or custom format ZIP file.
     * 
     * @param repositoryId Repository ID
     * @param folderId Target folder ID to import into
     * @param fileInputStream Uploaded file stream
     * @param fileDetail File metadata
     * @param request HTTP request for CallContext
     * @return JSON response with import results
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

            // Verify target folder exists
            Folder targetFolder = cs.getFolder(repositoryId, folderId);
            if (targetFolder == null) {
                result.put("status", "error");
                result.put("message", "Target folder not found: " + folderId);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(result.toJSONString()).build();
            }

            // Create CallContext and verify authentication (fix: no admin fallback)
            CallContext callContext = createCallContext(request, repositoryId);
            if (callContext.getUsername() == null) {
                result.put("status", "error");
                result.put("message", "Authentication required");
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(result.toJSONString()).build();
            }

            // Stream ZIP to temp file instead of memory (fix: OOM risk)
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

            // Detect format and process using temp file
            ImportFormat format = detectFormat(tempFile);
            log.info("Detected import format: " + format);

            if (format == ImportFormat.ACP) {
                ImportResult acpResult = importAcpFormat(repositoryId, folderId, tempFile, callContext);
                importedFolders = acpResult.foldersCreated;
                importedDocuments = acpResult.documentsCreated;
                errors.addAll(acpResult.errors);
                warnings.addAll(acpResult.warnings);
            } else if (format == ImportFormat.CUSTOM) {
                ImportResult customResult = importCustomFormat(repositoryId, folderId, tempFile, callContext);
                importedFolders = customResult.foldersCreated;
                importedDocuments = customResult.documentsCreated;
                errors.addAll(customResult.errors);
                warnings.addAll(customResult.warnings);
            } else {
                result.put("status", "error");
                result.put("message", "Unknown or unsupported archive format");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(result.toJSONString()).build();
            }

            // Fix: return partial if warnings exist (even without errors)
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
            if (!errors.isEmpty()) {
                result.put("errors", errors);
            }
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }

            return Response.status(Response.Status.OK)
                    .entity(result.toJSONString())
                    .type(MediaType.APPLICATION_JSON)
                    .build();

        } catch (Exception e) {
            log.error("Import failed: " + e.getMessage(), e);
            result.put("status", "error");
            result.put("message", "Import failed: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(result.toJSONString())
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } finally {
            // Clean up temp file
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Export folder contents as custom NemakiWare format ZIP.
     * 
     * @param repositoryId Repository ID
     * @param folderId Folder ID to export
     * @param request HTTP request
     * @return ZIP file stream
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

            StreamingOutput streamingOutput = new StreamingOutput() {
                @Override
                public void write(OutputStream output) throws IOException {
                    try (ZipOutputStream zos = new ZipOutputStream(output)) {
                        // Export type definitions first
                        try {
                            Set<String> customTypeIds = new HashSet<>();
                            collectCustomTypeIds(repositoryId, folder, customTypeIds);
                            if (!customTypeIds.isEmpty()) {
                                exportTypeDefinitions(repositoryId, customTypeIds, zos);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to export type definitions: " + e.getMessage(), e);
                        }
                        exportFolderRecursive(repositoryId, folder, "", zos, callContext);
                    } catch (Exception e) {
                        log.error("Export streaming failed: " + e.getMessage(), e);
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
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"status\":\"error\",\"message\":\"Export failed: " + e.getMessage() + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    // ========== Format Detection ==========

    private enum ImportFormat {
        ACP, CUSTOM, UNKNOWN
    }

    private ImportFormat detectFormat(File zipFile) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile)) {
            boolean hasPackageXml = false;
            boolean hasMetaJson = false;

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".xml") && !name.contains("/")) {
                    // Root-level XML file suggests ACP format
                    hasPackageXml = true;
                }
                if (name.endsWith(META_SUFFIX)) {
                    hasMetaJson = true;
                }
            }

            if (hasPackageXml && !hasMetaJson) {
                return ImportFormat.ACP;
            } else if (hasMetaJson) {
                return ImportFormat.CUSTOM;
            }
        }
        return ImportFormat.UNKNOWN;
    }

    // ========== ACP Import ==========

    private ImportResult importAcpFormat(String repositoryId, String targetFolderId,
            File zipFile, CallContext callContext) throws Exception {

        ImportResult result = new ImportResult();

        String packageXmlName = null;
        byte[] xmlData = null;

        // First pass: find and read package XML (small file, safe to keep in memory)
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                
                // Sanitize path (fix: ZIP path traversal)
                if (!isValidZipEntryName(name)) {
                    result.warnings.add("Skipping invalid path: " + name);
                    continue;
                }
                
                if (!entry.isDirectory() && name.endsWith(".xml") && !name.contains("/")) {
                    packageXmlName = name;
                    try (InputStream is = zf.getInputStream(entry)) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, len);
                        }
                        xmlData = baos.toByteArray();
                    }
                    break;
                }
            }
        }

        if (packageXmlName == null || xmlData == null) {
            result.errors.add("No package XML file found in ACP archive");
            return result;
        }

        // Parse XML
        org.dom4j.Document xmlDoc;
        try {
            SAXReader reader = new SAXReader();
            xmlDoc = reader.read(new ByteArrayInputStream(xmlData));
        } catch (DocumentException e) {
            result.errors.add("Failed to parse package XML: " + e.getMessage());
            return result;
        }

        // Get package name (directory containing binaries)
        String packageName = packageXmlName.replace(".xml", "");

        // Process nodes from XML (reads files on-demand from ZipFile)
        // Keep ZipFile open during entire import for performance (fix: ZipFile performance)
        Element root = xmlDoc.getRootElement();
        Map<String, String> uuidToObjectId = new HashMap<>();
        uuidToObjectId.put("", targetFolderId); // Root maps to target folder

        try (ZipFile zf = new ZipFile(zipFile)) {
            processAcpNodes(repositoryId, root, targetFolderId, packageName, zf, 
                    uuidToObjectId, callContext, result);
        }

        return result;
    }

    /**
     * Validate ZIP entry name to prevent path traversal attacks.
     * Enhanced validation for Windows paths and normalized path checking.
     */
    private boolean isValidZipEntryName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        // Reject paths with .. or absolute paths
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
            return false;
        }
        // Reject paths with null bytes
        if (name.contains("\0")) {
            return false;
        }
        // Reject Windows-style paths with backslash or drive letters (fix: enhanced path validation)
        if (name.contains("\\") || name.contains(":")) {
            return false;
        }
        // Additional check using path normalization
        try {
            java.nio.file.Path normalized = java.nio.file.Paths.get(name).normalize();
            if (normalized.startsWith("..") || normalized.isAbsolute()) {
                return false;
            }
        } catch (Exception e) {
            // Invalid path format
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void processAcpNodes(String repositoryId, Element element, String parentFolderId,
            String packageName, ZipFile zf, Map<String, String> uuidToObjectId,
            CallContext callContext, ImportResult result) {

        ContentService cs = getContentService();

        for (Iterator<Element> it = element.elementIterator(); it.hasNext();) {
            Element child = it.next();
            String tagName = child.getName();

            // Handle folder nodes
            if ("folder".equals(tagName) || tagName.endsWith(":folder")) {
                try {
                    String uuid = getAcpAttribute(child, "node-uuid");
                    String name = getAcpChildName(child);
                    if (name == null) {
                        name = "folder_" + System.currentTimeMillis();
                    }

                    // Create folder
                    PropertiesImpl props = new PropertiesImpl();
                    props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
                    props.addProperty(new PropertyStringImpl(PropertyIds.NAME, name));

                    Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
                    Folder newFolder = cs.createFolder(callContext, repositoryId, props, parentFolder, 
                            null, null, null, null);

                    if (uuid != null) {
                        uuidToObjectId.put(uuid, newFolder.getId());
                    }
                    result.foldersCreated++;

                    // Process ACL
                    processAcpAcl(repositoryId, child, newFolder.getId(), callContext, result);

                    // Recursively process children (pass open ZipFile for performance)
                    processAcpNodes(repositoryId, child, newFolder.getId(), packageName, 
                            zf, uuidToObjectId, callContext, result);

                } catch (Exception e) {
                    log.error("Failed to create folder: " + e.getMessage(), e);
                    result.errors.add("Failed to create folder: " + e.getMessage());
                }
            }

            // Handle content/document nodes
            if ("content".equals(tagName) || tagName.endsWith(":content")) {
                try {
                    String uuid = getAcpAttribute(child, "node-uuid");
                    String name = getAcpChildName(child);
                    if (name == null) {
                        name = "document_" + System.currentTimeMillis();
                    }

                    // Find content stream reference
                    String contentRef = getAcpContentReference(child);
                    byte[] content = null;
                    String mimeType = "application/octet-stream";

                    if (contentRef != null) {
                        // Read file content on-demand from open ZipFile (fix: OOM + performance)
                        String filePath = packageName + "/" + contentRef;
                        content = readZipEntry(zf, filePath);
                        if (content == null) {
                            content = readZipEntry(zf, contentRef);
                        }
                        mimeType = guessMimeType(contentRef);
                    }

                    // Create document
                    PropertiesImpl props = new PropertiesImpl();
                    props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document"));
                    props.addProperty(new PropertyStringImpl(PropertyIds.NAME, name));

                    // Add custom properties from XML
                    addAcpProperties(child, props);

                    ContentStream contentStream = null;
                    if (content != null) {
                        contentStream = new ContentStreamImpl(name, BigInteger.valueOf(content.length),
                                mimeType, new ByteArrayInputStream(content));
                    }

                    Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
                    Document newDoc = cs.createDocument(callContext, repositoryId, props, parentFolder,
                            contentStream, VersioningState.MAJOR, null, null, null);

                    if (uuid != null) {
                        uuidToObjectId.put(uuid, newDoc.getId());
                    }
                    result.documentsCreated++;

                    // Process ACL
                    processAcpAcl(repositoryId, child, newDoc.getId(), callContext, result);

                } catch (Exception e) {
                    log.error("Failed to create document: " + e.getMessage(), e);
                    result.errors.add("Failed to create document: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Read a single entry from an already-open ZipFile.
     * This avoids reopening the ZipFile for each entry (fix: performance).
     * Also monitors stream size when entry.getSize() returns -1 (fix: size -1 handling).
     */
    private byte[] readZipEntry(ZipFile zf, String entryName) {
        return readZipEntryWithLimit(zf, entryName, MAX_SINGLE_FILE_SIZE);
    }

    /**
     * Read a single entry from an already-open ZipFile with a custom size limit.
     * Package-private for testing purposes.
     */
    byte[] readZipEntryWithLimit(ZipFile zf, String entryName, long maxSize) {
        try {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            // Check file size limit (if known)
            long entrySize = entry.getSize();
            if (entrySize > maxSize) {
                log.warn("Skipping large file: " + entryName + " (size: " + entrySize + ")");
                return null;
            }
            try (InputStream is = zf.getInputStream(entry)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                long totalRead = 0;
                while ((len = is.read(buffer)) != -1) {
                    totalRead += len;
                    // Monitor size during read if entry.getSize() was -1 (unknown)
                    if (totalRead > maxSize) {
                        log.warn("Skipping file exceeding size limit during read: " + entryName);
                        return null;
                    }
                    baos.write(buffer, 0, len);
                }
                return baos.toByteArray();
            }
        } catch (IOException e) {
            log.warn("Failed to read ZIP entry: " + entryName, e);
            return null;
        }
    }

    private String getAcpAttribute(Element element, String attrName) {
        // Try with namespace prefix
        String value = element.attributeValue("alf:" + attrName);
        if (value == null) {
            value = element.attributeValue(attrName);
        }
        return value;
    }

    private String getAcpChildName(Element element) {
        // Try view:childName attribute
        String name = element.attributeValue("view:childName");
        if (name == null) {
            name = element.attributeValue("childName");
        }
        // Try cm:name property
        if (name == null) {
            Element nameEl = element.element("name");
            if (nameEl == null) {
                for (Iterator<Element> it = element.elementIterator(); it.hasNext();) {
                    Element child = it.next();
                    if (child.getName().endsWith(":name") || "name".equals(child.getName())) {
                        name = child.getTextTrim();
                        break;
                    }
                }
            } else {
                name = nameEl.getTextTrim();
            }
        }
        return name;
    }

    private String getAcpContentReference(Element element) {
        // Look for content element with attachment reference
        for (Iterator<Element> it = element.elementIterator(); it.hasNext();) {
            Element child = it.next();
            if ("content".equals(child.getName()) || child.getName().endsWith(":content")) {
                Element attachment = child.element("attachment");
                if (attachment != null) {
                    return attachment.attributeValue("filename");
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addAcpProperties(Element element, PropertiesImpl props) {
        for (Iterator<Element> it = element.elementIterator("property"); it.hasNext();) {
            Element propEl = it.next();
            String propName = propEl.attributeValue("name");
            String propValue = propEl.getTextTrim();
            if (propName != null && propValue != null && !propName.startsWith("cm:")) {
                // Map to CMIS property if possible
                props.addProperty(new PropertyStringImpl(propName, propValue));
            }
        }
    }

    private void processAcpAcl(String repositoryId, Element element, String objectId,
            CallContext callContext, ImportResult result) {
        // Look for ACL element
        Element aclEl = element.element("acl");
        if (aclEl == null) {
            return;
        }

        try {
            ContentService cs = getContentService();
            Content content = cs.getContent(repositoryId, objectId);
            if (content == null) {
                return;
            }

            List<Ace> aces = new ArrayList<>();

            // Process permissions
            for (Iterator<Element> it = aclEl.elementIterator("permission"); it.hasNext();) {
                Element permEl = it.next();
                String authority = permEl.attributeValue("authority");
                String access = permEl.attributeValue("access");

                if (authority != null && access != null) {
                    String mappedPermission = PERMISSION_MAPPING.getOrDefault(access, "cmis:read");

                    // Handle user/group prefix
                    String principalId = authority;
                    if (authority.startsWith("user:")) {
                        principalId = authority.substring(5);
                    } else if (authority.startsWith("GROUP_")) {
                        principalId = authority;
                    }

                    Ace ace = new Ace();
                    ace.setPrincipalId(principalId);
                    List<String> permissions = new ArrayList<>();
                    permissions.add(mappedPermission);
                    ace.setPermissions(permissions);
                    ace.setDirect(true);
                    aces.add(ace);
                }
            }

            if (!aces.isEmpty()) {
                Acl acl = content.getAcl();
                if (acl == null) {
                    acl = new Acl();
                }
                acl.setLocalAces(aces);
                content.setAcl(acl);
                cs.updateInternal(repositoryId, content);
            }

        } catch (Exception e) {
            log.warn("Failed to apply ACL: " + e.getMessage());
            result.warnings.add("Failed to apply ACL for object " + objectId + ": " + e.getMessage());
        }
    }

    // ========== Custom Format Import ==========

    private ImportResult importCustomFormat(String repositoryId, String targetFolderId,
            File zipFile, CallContext callContext) throws Exception {

        ImportResult result = new ImportResult();
        ContentService cs = getContentService();

        // First pass: collect entry names and parse metadata files (small, safe to keep in memory)
        List<String> entryNames = new ArrayList<>();
        Map<String, JSONObject> metadataMap = new HashMap<>();

        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                
                // Sanitize path (fix: ZIP path traversal)
                if (!isValidZipEntryName(name)) {
                    result.warnings.add("Skipping invalid path: " + name);
                    continue;
                }
                
                if (!entry.isDirectory()) {
                    // Skip .nemaki-types/ entries from regular file processing
                    if (name.startsWith(TYPE_DEFINITIONS_DIR)) {
                        continue;
                    }
                    entryNames.add(name);

                    // Parse metadata files (fix: apply size limit to JSON files too)
                    if (name.endsWith(META_SUFFIX)) {
                        // Check JSON file size limit
                        long jsonSize = entry.getSize();
                        if (jsonSize > MAX_SINGLE_FILE_SIZE) {
                            result.warnings.add("Skipping large metadata file: " + name + " (size: " + jsonSize + ")");
                            continue;
                        }
                        try (InputStream is = zf.getInputStream(entry)) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            byte[] buffer = new byte[8192];
                            int len;
                            long totalRead = 0;
                            while ((len = is.read(buffer)) != -1) {
                                totalRead += len;
                                // Monitor size during read if entry.getSize() was -1 (unknown)
                                if (totalRead > MAX_SINGLE_FILE_SIZE) {
                                    result.warnings.add("Skipping metadata file exceeding size limit: " + name);
                                    break;
                                }
                                baos.write(buffer, 0, len);
                            }
                            if (totalRead <= MAX_SINGLE_FILE_SIZE) {
                                JSONParser parser = new JSONParser();
                                JSONObject meta = (JSONObject) parser.parse(new String(baos.toByteArray(), "UTF-8"));
                                String baseName = name.substring(0, name.length() - META_SUFFIX.length());
                                metadataMap.put(baseName, meta);
                            }
                        } catch (ParseException e) {
                            result.warnings.add("Failed to parse metadata: " + name);
                        }
                    }
                }
            }
        }

        // Build folder structure map
        Map<String, String> pathToFolderId = new HashMap<>();
        pathToFolderId.put("", targetFolderId);

        // Process files (sorted to ensure folders are created before their contents)
        entryNames.sort((a, b) -> {
            int depthA = a.split("/").length;
            int depthB = b.split("/").length;
            return depthA - depthB;
        });

        // Keep ZipFile open during entire import for performance (fix: ZipFile performance)
        try (ZipFile zf = new ZipFile(zipFile)) {
            // Import type definitions before importing files
            importTypeDefinitions(repositoryId, zf, result);

            for (String path : entryNames) {
                // Skip metadata and version files
                if (path.endsWith(META_SUFFIX) || isVersionFile(path)) {
                    continue;
                }

                try {
                    // Ensure parent folders exist
                    String parentPath = getParentPath(path);
                    String parentFolderId = ensureFolderPath(repositoryId, parentPath, targetFolderId,
                            pathToFolderId, callContext, result);

                    // Get filename
                    String fileName = getFileName(path);

                    // Get metadata if available
                    JSONObject metadata = metadataMap.get(path);

                    // Check for version files
                    List<String> versionPaths = findVersionFilesFor(path, zf);

                    // Determine initial content: use .v1 if versions exist, otherwise main file
                    byte[] initialContent;
                    if (!versionPaths.isEmpty()) {
                        String v1Path = versionPaths.get(0); // oldest version
                        initialContent = readZipEntry(zf, v1Path);
                        if (initialContent == null) {
                            // Fallback to main file if .v1 can't be read
                            initialContent = readZipEntry(zf, path);
                        }
                    } else {
                        initialContent = readZipEntry(zf, path);
                    }

                    if (initialContent == null) {
                        result.warnings.add("Could not read file content: " + path);
                        continue;
                    }
                    String mimeType = guessMimeType(fileName);

                    // Determine object type from metadata (use custom type if available)
                    String objectTypeId = "cmis:document";
                    if (metadata != null) {
                        JSONObject metaProps = (JSONObject) metadata.get("properties");
                        if (metaProps != null) {
                            Object typeIdObj = metaProps.get(PropertyIds.OBJECT_TYPE_ID);
                            if (typeIdObj != null && !typeIdObj.toString().isEmpty()) {
                                objectTypeId = typeIdObj.toString();
                            }
                        }
                    }

                    PropertiesImpl props = new PropertiesImpl();
                    props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, objectTypeId));
                    props.addProperty(new PropertyStringImpl(PropertyIds.NAME, fileName));

                    // Apply custom properties from metadata
                    if (metadata != null) {
                        applyCustomProperties(metadata, props);
                    }

                    ContentStream contentStream = new ContentStreamImpl(fileName,
                            BigInteger.valueOf(initialContent.length), mimeType, new ByteArrayInputStream(initialContent));

                    Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
                    Document newDoc = cs.createDocument(callContext, repositoryId, props, parentFolder,
                            contentStream, VersioningState.MAJOR, null, null, null);

                    result.documentsCreated++;

                    // Apply ACL from metadata
                    if (metadata != null) {
                        applyCustomAcl(repositoryId, newDoc.getId(), metadata, callContext, result);
                    }

                    // Import version history with checkOut/checkIn cycles
                    importVersionHistory(repositoryId, path, zf,
                            newDoc, callContext, result, metadataMap, versionPaths);

                } catch (Exception e) {
                    log.error("Failed to import file: " + path + " - " + e.getMessage(), e);
                    result.errors.add("Failed to import: " + path + " - " + e.getMessage());
                }
            }
        }

        return result;
    }

    private boolean isVersionFile(String path) {
        String fileName = getFileName(path);
        // Match version files like "file.txt.v1" but not "file.txt.v1.meta.json"
        return fileName.matches(".*\\.v\\d+$");
    }

    private String getParentPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash > 0 ? path.substring(0, lastSlash) : "";
    }

    private String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String ensureFolderPath(String repositoryId, String path, String rootFolderId,
            Map<String, String> pathToFolderId, CallContext callContext, ImportResult result) throws Exception {

        if (path.isEmpty()) {
            return rootFolderId;
        }

        if (pathToFolderId.containsKey(path)) {
            return pathToFolderId.get(path);
        }

        ContentService cs = getContentService();

        // Ensure parent exists first
        String parentPath = getParentPath(path);
        String parentFolderId = ensureFolderPath(repositoryId, parentPath, rootFolderId, 
                pathToFolderId, callContext, result);

        // Create this folder
        String folderName = getFileName(path);

        PropertiesImpl props = new PropertiesImpl();
        props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
        props.addProperty(new PropertyStringImpl(PropertyIds.NAME, folderName));

        Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
        Folder newFolder = cs.createFolder(callContext, repositoryId, props, parentFolder, 
                null, null, null, null);

        pathToFolderId.put(path, newFolder.getId());
        result.foldersCreated++;

        return newFolder.getId();
    }

    @SuppressWarnings("unchecked")
    private void applyCustomProperties(JSONObject metadata, PropertiesImpl props) {
        JSONObject properties = (JSONObject) metadata.get("properties");
        if (properties == null) {
            return;
        }

        for (Object key : properties.keySet()) {
            String propName = (String) key;
            Object propValue = properties.get(propName);

            // Skip CMIS system properties that are auto-generated or already set with correct type
            if (propName.equals(PropertyIds.OBJECT_ID) ||
                propName.equals(PropertyIds.OBJECT_TYPE_ID) ||
                propName.equals(PropertyIds.NAME) ||
                propName.equals(PropertyIds.BASE_TYPE_ID) ||
                propName.equals(PropertyIds.CREATION_DATE) ||
                propName.equals(PropertyIds.LAST_MODIFICATION_DATE) ||
                propName.equals(PropertyIds.CREATED_BY) ||
                propName.equals(PropertyIds.LAST_MODIFIED_BY)) {
                continue;
            }

            if (propValue != null) {
                props.addProperty(new PropertyStringImpl(propName, propValue.toString()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyCustomAcl(String repositoryId, String objectId, JSONObject metadata,
            CallContext callContext, ImportResult result) {

        JSONArray aclArray = (JSONArray) metadata.get("acl");
        if (aclArray == null || aclArray.isEmpty()) {
            return;
        }

        try {
            ContentService cs = getContentService();
            Content content = cs.getContent(repositoryId, objectId);
            if (content == null) {
                return;
            }

            List<Ace> aces = new ArrayList<>();

            for (Object item : aclArray) {
                JSONObject aceJson = (JSONObject) item;
                String principalId = (String) aceJson.get("principalId");
                JSONArray permissionsArray = (JSONArray) aceJson.get("permissions");

                if (principalId != null && permissionsArray != null) {
                    Ace ace = new Ace();
                    ace.setPrincipalId(principalId);
                    List<String> permissions = new ArrayList<>();
                    for (Object perm : permissionsArray) {
                        permissions.add((String) perm);
                    }
                    ace.setPermissions(permissions);
                    ace.setDirect(true);
                    aces.add(ace);
                }
            }

            if (!aces.isEmpty()) {
                Acl acl = content.getAcl();
                if (acl == null) {
                    acl = new Acl();
                }
                acl.setLocalAces(aces);
                content.setAcl(acl);
                cs.updateInternal(repositoryId, content);
            }

        } catch (Exception e) {
            log.warn("Failed to apply ACL: " + e.getMessage());
            result.warnings.add("Failed to apply ACL for object " + objectId + ": " + e.getMessage());
        }
    }

    /**
     * Import type definitions from .nemaki-types/ directory in the ZIP.
     * Only creates types that don't already exist.
     */
    @SuppressWarnings("unchecked")
    private void importTypeDefinitions(String repositoryId, ZipFile zf, ImportResult result) {
        TypeService ts = getTypeService();
        if (ts == null) {
            log.debug("TypeService not available, skipping type definition import");
            return;
        }

        // Collect type definition entries
        List<String> typeEntries = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(TYPE_DEFINITIONS_DIR) && name.endsWith(TYPE_DEFINITION_SUFFIX)) {
                typeEntries.add(name);
            }
        }

        if (typeEntries.isEmpty()) {
            return;
        }

        log.info("Found " + typeEntries.size() + " type definition(s) to import");
        int typesCreated = 0;
        int typesSkipped = 0;

        // Sort to ensure parent types are created before child types
        // (simple alphabetical sort; parents usually have shorter names)
        typeEntries.sort(Comparator.naturalOrder());

        for (String entryName : typeEntries) {
            try {
                byte[] jsonBytes = readZipEntry(zf, entryName);
                if (jsonBytes == null) {
                    result.warnings.add("Could not read type definition: " + entryName);
                    continue;
                }

                JSONParser parser = new JSONParser();
                JSONObject typeJson = (JSONObject) parser.parse(new String(jsonBytes, "UTF-8"));

                String typeId = (String) typeJson.get("id");
                if (typeId == null || typeId.trim().isEmpty()) {
                    result.warnings.add("Type definition missing 'id' field: " + entryName);
                    continue;
                }

                // Check if type already exists
                NemakiTypeDefinition existing = ts.getTypeDefinition(repositoryId, typeId);
                if (existing != null) {
                    log.info("Type already exists, skipping: " + typeId);
                    typesSkipped++;
                    continue;
                }

                // Build NemakiTypeDefinition
                NemakiTypeDefinition tdf = new NemakiTypeDefinition();
                tdf.setTypeId(typeId);
                tdf.setLocalName((String) typeJson.get("localName"));
                tdf.setDisplayName((String) typeJson.get("displayName"));
                tdf.setDescription((String) typeJson.get("description"));

                String baseId = (String) typeJson.get("baseId");
                if ("cmis:document".equals(baseId)) {
                    tdf.setBaseId(BaseTypeId.CMIS_DOCUMENT);
                } else if ("cmis:folder".equals(baseId)) {
                    tdf.setBaseId(BaseTypeId.CMIS_FOLDER);
                } else if ("cmis:relationship".equals(baseId)) {
                    tdf.setBaseId(BaseTypeId.CMIS_RELATIONSHIP);
                } else if ("cmis:policy".equals(baseId)) {
                    tdf.setBaseId(BaseTypeId.CMIS_POLICY);
                } else if ("cmis:item".equals(baseId)) {
                    tdf.setBaseId(BaseTypeId.CMIS_ITEM);
                } else if ("cmis:secondary".equals(baseId)) {
                    tdf.setBaseId(BaseTypeId.CMIS_SECONDARY);
                }

                String parentId = (String) typeJson.get("parentId");
                if (parentId != null) {
                    tdf.setParentId(parentId);
                }

                // Boolean properties
                if (typeJson.containsKey("creatable")) {
                    tdf.setCreatable((Boolean) typeJson.get("creatable"));
                }
                if (typeJson.containsKey("queryable")) {
                    tdf.setQueryable((Boolean) typeJson.get("queryable"));
                }
                if (typeJson.containsKey("fulltextIndexed")) {
                    tdf.setFulltextIndexed((Boolean) typeJson.get("fulltextIndexed"));
                }
                if (typeJson.containsKey("includedInSupertypeQuery")) {
                    tdf.setIncludedInSupertypeQuery((Boolean) typeJson.get("includedInSupertypeQuery"));
                }
                if (typeJson.containsKey("controllablePolicy")) {
                    tdf.setControllablePolicy((Boolean) typeJson.get("controllablePolicy"));
                }
                if (typeJson.containsKey("controllableACL")) {
                    tdf.setControllableACL((Boolean) typeJson.get("controllableACL"));
                }

                // Create property definitions first
                List<String> propertyNodeIds = new ArrayList<>();
                Object propDefsObj = typeJson.get("propertyDefinitions");
                if (propDefsObj instanceof JSONArray) {
                    JSONArray propDefs = (JSONArray) propDefsObj;
                    for (Object propObj : propDefs) {
                        JSONObject propJson = (JSONObject) propObj;
                        String propId = (String) propJson.get("id");
                        if (propId == null) continue;

                        // Build NemakiPropertyDefinition
                        NemakiPropertyDefinitionCore core = new NemakiPropertyDefinitionCore();
                        core.setPropertyId(propId);

                        String propTypeStr = (String) propJson.get("propertyType");
                        if (propTypeStr != null) {
                            try {
                                core.setPropertyType(PropertyType.fromValue(propTypeStr));
                            } catch (Exception e) {
                                core.setPropertyType(PropertyType.STRING);
                            }
                        } else {
                            core.setPropertyType(PropertyType.STRING);
                        }

                        String cardStr = (String) propJson.get("cardinality");
                        if (cardStr != null) {
                            try {
                                core.setCardinality(Cardinality.fromValue(cardStr));
                            } catch (Exception e) {
                                core.setCardinality(Cardinality.SINGLE);
                            }
                        } else {
                            core.setCardinality(Cardinality.SINGLE);
                        }
                        core.setQueryName(propId);

                        NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();
                        String updStr = (String) propJson.get("updatability");
                        if (updStr != null) {
                            try {
                                detail.setUpdatability(Updatability.fromValue(updStr));
                            } catch (Exception e) {
                                detail.setUpdatability(Updatability.READWRITE);
                            }
                        } else {
                            detail.setUpdatability(Updatability.READWRITE);
                        }

                        Object reqObj = propJson.get("required");
                        detail.setRequired(reqObj instanceof Boolean ? (Boolean) reqObj : false);

                        Object queryObj = propJson.get("queryable");
                        detail.setQueryable(queryObj instanceof Boolean ? (Boolean) queryObj : true);

                        NemakiPropertyDefinition npd = new NemakiPropertyDefinition(core, detail);
                        NemakiPropertyDefinitionDetail createdDetail = ts.createPropertyDefinition(repositoryId, npd);

                        if (createdDetail != null) {
                            // Get the core to find the detail node IDs
                            NemakiPropertyDefinitionCore createdCore = ts.getPropertyDefinitionCoreByPropertyId(repositoryId, propId);
                            if (createdCore != null) {
                                List<NemakiPropertyDefinitionDetail> details = ts.getPropertyDefinitionDetailByCoreNodeId(repositoryId, createdCore.getId());
                                if (details != null && !details.isEmpty()) {
                                    propertyNodeIds.add(details.get(0).getId());
                                }
                            }
                        }
                    }
                }

                tdf.setProperties(propertyNodeIds);

                // Create the type definition
                ts.createTypeDefinition(repositoryId, tdf);
                typesCreated++;
                log.info("Created type definition: " + typeId);

            } catch (Exception e) {
                log.error("Failed to import type definition from: " + entryName, e);
                result.warnings.add("Failed to import type definition: " + entryName + " - " + e.getMessage());
            }
        }

        // Refresh type cache
        try {
            TypeManager tm = getTypeManager();
            if (tm != null) {
                tm.refreshTypes();
                log.info("Type cache refreshed after importing " + typesCreated + " type(s)");
            }
        } catch (Exception e) {
            log.warn("Failed to refresh type cache: " + e.getMessage());
        }

        if (typesCreated > 0) {
            result.warnings.add("Imported " + typesCreated + " type definition(s)" +
                    (typesSkipped > 0 ? ", skipped " + typesSkipped + " existing" : ""));
        } else if (typesSkipped > 0) {
            result.warnings.add("All " + typesSkipped + " type definition(s) already exist, skipped");
        }
    }

    /**
     * Find version files for a given base path in the ZIP, sorted by version number.
     * Excludes metadata files (.meta.json) - only returns actual content version files (.v1, .v2, ...).
     */
    private List<String> findVersionFilesFor(String basePath, ZipFile zf) {
        String baseFileName = getFileName(basePath);
        String parentPath = getParentPath(basePath);
        String prefix = parentPath.isEmpty() ? "" : parentPath + "/";

        List<String> versionPaths = new ArrayList<>();
        try {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                if (!entry.isDirectory() && path.startsWith(prefix)
                        && !path.endsWith(META_SUFFIX)
                        && isVersionFileFor(path, baseFileName)) {
                    versionPaths.add(path);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan ZIP for version files: " + e.getMessage());
        }

        // Sort by version number
        versionPaths.sort((a, b) -> extractVersionNumber(a) - extractVersionNumber(b));
        return versionPaths;
    }

    /**
     * Import version history using checkOut/checkIn cycles.
     * Version files (.v1, .v2, ...) represent older versions; main file is the latest.
     * Document was already created with .v1 content (or main file if no versions).
     */
    private void importVersionHistory(String repositoryId, String basePath,
            ZipFile zf, Document document, CallContext callContext, ImportResult result,
            Map<String, JSONObject> metadataMap, List<String> versionPaths) {

        if (versionPaths.isEmpty()) {
            return;
        }

        ContentService cs = getContentService();
        if (cs == null) {
            result.warnings.add("ContentService not available for version import: " + basePath);
            return;
        }

        String fileName = getFileName(basePath);
        String mimeType = guessMimeType(fileName);

        try {
            // Document was created with .v1 content (version 1.0).
            // Now checkIn .v2, .v3, ... and finally the main file as the latest version.

            // Start from .v2 (skip .v1 which was used for initial creation)
            for (int i = 1; i < versionPaths.size(); i++) {
                String versionPath = versionPaths.get(i);
                byte[] versionContent = readZipEntry(zf, versionPath);
                if (versionContent == null) {
                    result.warnings.add("Could not read version file: " + versionPath);
                    continue;
                }

                // Read version metadata
                JSONObject versionMeta = null;
                String versionMetaPath = versionPath + META_SUFFIX;
                byte[] metaBytes = readZipEntry(zf, versionMetaPath);
                if (metaBytes != null) {
                    try {
                        JSONParser parser = new JSONParser();
                        versionMeta = (JSONObject) parser.parse(new String(metaBytes, "UTF-8"));
                    } catch (Exception e) {
                        log.debug("Failed to parse version metadata: " + versionMetaPath);
                    }
                }

                boolean isMajor = true;
                String checkinComment = null;
                if (versionMeta != null) {
                    Object majorObj = versionMeta.get("isMajorVersion");
                    if (majorObj instanceof Boolean) {
                        isMajor = (Boolean) majorObj;
                    }
                    checkinComment = (String) versionMeta.get("checkinComment");
                }

                // checkOut
                Document pwc = cs.checkOut(callContext, repositoryId, document.getId(), null);
                String pwcId = pwc.getId();

                // checkIn with version content
                ContentStream versionStream = new ContentStreamImpl(fileName,
                        BigInteger.valueOf(versionContent.length), mimeType,
                        new ByteArrayInputStream(versionContent));

                Holder<String> objectIdHolder = new Holder<>(pwcId);
                Document checkedIn = cs.checkIn(callContext, repositoryId, objectIdHolder, isMajor,
                        null, versionStream, checkinComment, null, null, null, null);

                // Update document reference for next iteration
                document = checkedIn;
            }

            // Finally, checkIn the main file content as the latest version
            byte[] mainContent = readZipEntry(zf, basePath);
            if (mainContent != null) {
                // Read main file metadata for version info
                JSONObject mainMeta = metadataMap.get(basePath);
                boolean isMajor = true;
                String checkinComment = null;
                if (mainMeta != null) {
                    JSONObject versionInfo = (JSONObject) mainMeta.get("versionInfo");
                    if (versionInfo != null) {
                        Object majorObj = versionInfo.get("isMajorVersion");
                        if (majorObj instanceof Boolean) {
                            isMajor = (Boolean) majorObj;
                        }
                        checkinComment = (String) versionInfo.get("checkinComment");
                    }
                }

                // checkOut
                Document pwc = cs.checkOut(callContext, repositoryId, document.getId(), null);
                String pwcId = pwc.getId();

                ContentStream mainStream = new ContentStreamImpl(fileName,
                        BigInteger.valueOf(mainContent.length), mimeType,
                        new ByteArrayInputStream(mainContent));

                Holder<String> objectIdHolder = new Holder<>(pwcId);
                cs.checkIn(callContext, repositoryId, objectIdHolder, isMajor,
                        null, mainStream, checkinComment, null, null, null, null);
            }

            log.info("Imported " + versionPaths.size() + " version(s) for: " + basePath);

        } catch (Exception e) {
            log.error("Failed to import version history for: " + basePath, e);
            result.warnings.add("Failed to import version history for: " + basePath + " - " + e.getMessage());
        }
    }

    private boolean isVersionFileFor(String path, String baseFileName) {
        String fileName = getFileName(path);
        // Only match actual version content files like "file.txt.v1", not "file.txt.v1.meta.json"
        return fileName.matches(baseFileName + "\\.v\\d+$");
    }

    private int extractVersionNumber(String path) {
        String fileName = getFileName(path);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\.v(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(fileName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    // ========== Custom Format Export ==========

    /**
     * Recursively collect custom (non-base) type IDs from documents in a folder tree.
     */
    private void collectCustomTypeIds(String repositoryId, Folder folder, Set<String> customTypeIds) {
        ContentService cs = getContentService();
        if (cs == null) return;

        List<Content> children = cs.getChildren(repositoryId, folder.getId());
        for (Content child : children) {
            if (child instanceof Folder) {
                collectCustomTypeIds(repositoryId, (Folder) child, customTypeIds);
            } else if (child instanceof Document) {
                String objectType = ((Document) child).getObjectType();
                if (objectType != null && !BASE_TYPE_IDS.contains(objectType)) {
                    customTypeIds.add(objectType);
                }
            }
        }
    }

    /**
     * Export type definitions to .nemaki-types/ directory in the ZIP.
     */
    @SuppressWarnings("unchecked")
    private void exportTypeDefinitions(String repositoryId, Set<String> customTypeIds,
            ZipOutputStream zos) throws Exception {

        TypeService ts = getTypeService();
        if (ts == null) {
            log.warn("TypeService not available, skipping type definition export");
            return;
        }

        // Also collect parent types that are custom
        Set<String> allTypeIds = new HashSet<>(customTypeIds);
        for (String typeId : customTypeIds) {
            NemakiTypeDefinition typeDef = ts.getTypeDefinition(repositoryId, typeId);
            if (typeDef != null && typeDef.getParentId() != null
                    && !BASE_TYPE_IDS.contains(typeDef.getParentId())) {
                allTypeIds.add(typeDef.getParentId());
            }
        }

        // Create .nemaki-types/ directory entry
        zos.putNextEntry(new ZipEntry(TYPE_DEFINITIONS_DIR));
        zos.closeEntry();

        for (String typeId : allTypeIds) {
            NemakiTypeDefinition typeDef = ts.getTypeDefinition(repositoryId, typeId);
            if (typeDef == null) {
                log.warn("Type definition not found for export: " + typeId);
                continue;
            }

            JSONObject typeJson = buildTypeDefinitionJson(repositoryId, typeDef, ts);
            String entryName = TYPE_DEFINITIONS_DIR + typeId + TYPE_DEFINITION_SUFFIX;
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(typeJson.toJSONString().getBytes("UTF-8"));
            zos.closeEntry();

            log.info("Exported type definition: " + typeId);
        }
    }

    /**
     * Build JSON representation of a type definition for export.
     */
    @SuppressWarnings("unchecked")
    private JSONObject buildTypeDefinitionJson(String repositoryId, NemakiTypeDefinition typeDef,
            TypeService ts) {

        JSONObject typeJson = new JSONObject();
        typeJson.put("id", typeDef.getTypeId());
        typeJson.put("localName", typeDef.getLocalName());
        typeJson.put("displayName", typeDef.getDisplayName());
        typeJson.put("description", typeDef.getDescription());
        typeJson.put("baseId", typeDef.getBaseId() != null ? typeDef.getBaseId().value() : null);
        typeJson.put("parentId", typeDef.getParentId());

        typeJson.put("creatable", typeDef.isCreatable());
        typeJson.put("queryable", typeDef.isQueryable());
        typeJson.put("controllableACL", typeDef.isControllableACL());
        typeJson.put("controllablePolicy", typeDef.isControllablePolicy());
        typeJson.put("fulltextIndexed", typeDef.isFulltextIndexed());
        typeJson.put("includedInSupertypeQuery", typeDef.isIncludedInSupertypeQuery());

        // Property definitions
        JSONArray propertiesArray = new JSONArray();
        List<String> propertyIds = typeDef.getProperties();
        if (propertyIds != null) {
            for (String propertyDetailId : propertyIds) {
                try {
                    NemakiPropertyDefinitionDetail detail = ts.getPropertyDefinitionDetail(repositoryId, propertyDetailId);
                    if (detail != null) {
                        NemakiPropertyDefinitionCore core = ts.getPropertyDefinitionCore(repositoryId, detail.getCoreNodeId());
                        if (core != null) {
                            JSONObject propJson = new JSONObject();
                            propJson.put("id", core.getPropertyId());
                            propJson.put("localName", core.getPropertyId());
                            propJson.put("displayName", core.getPropertyId());
                            propJson.put("propertyType", core.getPropertyType() != null ? core.getPropertyType().value() : "string");
                            propJson.put("cardinality", core.getCardinality() != null ? core.getCardinality().value() : "single");
                            propJson.put("updatability", detail.getUpdatability() != null ? detail.getUpdatability().value() : "readwrite");
                            propJson.put("required", detail.isRequired());
                            propJson.put("queryable", detail.isQueryable());
                            propertiesArray.add(propJson);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to export property definition: " + propertyDetailId, e);
                }
            }
        }
        typeJson.put("propertyDefinitions", propertiesArray);

        return typeJson;
    }

    @SuppressWarnings("unchecked")
    private void exportFolderRecursive(String repositoryId, Folder folder, String basePath,
            ZipOutputStream zos, CallContext callContext) throws Exception {

        ContentService cs = getContentService();
        List<Content> children = cs.getChildren(repositoryId, folder.getId());

        for (Content child : children) {
            String childPath = basePath.isEmpty() ? child.getName() : basePath + "/" + child.getName();

            if (child instanceof Folder) {
                // Create folder entry
                zos.putNextEntry(new ZipEntry(childPath + "/"));
                zos.closeEntry();

                // Recurse into folder
                exportFolderRecursive(repositoryId, (Folder) child, childPath, zos, callContext);

            } else if (child instanceof Document) {
                Document doc = (Document) child;

                // Export document content (fix: InputStream resource leak)
                if (doc.getAttachmentNodeId() != null) {
                    try {
                        var attachment = cs.getAttachment(repositoryId, doc.getAttachmentNodeId());
                        if (attachment != null && attachment.getInputStream() != null) {
                            zos.putNextEntry(new ZipEntry(childPath));
                            byte[] buffer = new byte[8192];
                            int len;
                            try (InputStream is = attachment.getInputStream()) {
                                while ((len = is.read(buffer)) != -1) {
                                    zos.write(buffer, 0, len);
                                }
                            }
                            zos.closeEntry();
                        }
                    } catch (Exception e) {
                        log.warn("Failed to export content for: " + childPath, e);
                    }
                }

                // Export metadata
                JSONObject metadata = buildDocumentMetadata(repositoryId, doc, callContext);
                String metaPath = childPath + META_SUFFIX;
                zos.putNextEntry(new ZipEntry(metaPath));
                zos.write(metadata.toJSONString().getBytes("UTF-8"));
                zos.closeEntry();

                // Export version history
                exportVersionHistory(repositoryId, doc, childPath, zos, callContext);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildDocumentMetadata(String repositoryId, Document doc, CallContext callContext) {
        JSONObject metadata = new JSONObject();

        // Properties
        JSONObject properties = new JSONObject();
        properties.put(PropertyIds.NAME, doc.getName());
        properties.put(PropertyIds.OBJECT_TYPE_ID, doc.getObjectType());
        if (doc.getDescription() != null) {
            properties.put(PropertyIds.DESCRIPTION, doc.getDescription());
        }
        // Add custom properties
        if (doc.getSubTypeProperties() != null) {
            for (jp.aegif.nemaki.model.Property prop : doc.getSubTypeProperties()) {
                if (prop.getValue() != null) {
                    properties.put(prop.getKey(), prop.getValue().toString());
                }
            }
        }
        metadata.put("properties", properties);

        // ACL
        if (doc.getAcl() != null && doc.getAcl().getLocalAces() != null) {
            JSONArray aclArray = new JSONArray();
            for (Ace ace : doc.getAcl().getLocalAces()) {
                JSONObject aceJson = new JSONObject();
                aceJson.put("principalId", ace.getPrincipalId());
                JSONArray permsArray = new JSONArray();
                if (ace.getPermissions() != null) {
                    permsArray.addAll(ace.getPermissions());
                }
                aceJson.put("permissions", permsArray);
                aceJson.put("direct", ace.isDirect());
                aclArray.add(aceJson);
            }
            metadata.put("acl", aclArray);
        }

        // Version info
        JSONObject versionInfo = new JSONObject();
        versionInfo.put("versionLabel", doc.getVersionLabel());
        versionInfo.put("versionSeriesId", doc.getVersionSeriesId());
        versionInfo.put("isLatestVersion", doc.isLatestVersion());
        versionInfo.put("isMajorVersion", doc.isMajorVersion());
        if (doc.getCheckinComment() != null) {
            versionInfo.put("checkinComment", doc.getCheckinComment());
        }
        metadata.put("versionInfo", versionInfo);

        // Relationships
        try {
            ContentService cs = getContentService();
            List<Relationship> relationships = cs.getRelationsipsOfObject(repositoryId, doc.getId(), null);
            if (relationships != null && !relationships.isEmpty()) {
                JSONArray relArray = new JSONArray();
                for (Relationship rel : relationships) {
                    JSONObject relJson = new JSONObject();
                    relJson.put("type", rel.getObjectType());
                    relJson.put("sourceId", rel.getSourceId());
                    relJson.put("targetId", rel.getTargetId());
                    relArray.add(relJson);
                }
                metadata.put("relationships", relArray);
            }
        } catch (Exception e) {
            log.debug("Failed to get relationships for export: " + e.getMessage());
        }

        return metadata;
    }

    @SuppressWarnings("unchecked")
    private void exportVersionHistory(String repositoryId, Document doc, String basePath,
            ZipOutputStream zos, CallContext callContext) {

        try {
            ContentService cs = getContentService();
            VersionSeries vs = cs.getVersionSeries(repositoryId, doc);
            if (vs == null) {
                return;
            }

            List<Document> allVersions = cs.getAllVersions(callContext, repositoryId, vs.getId());
            if (allVersions == null || allVersions.size() <= 1) {
                return; // Only current version exists
            }

            // Sort versions by creationDate for consistent ordering (fix: version sorting)
            // Use creationDate as primary sort key to avoid string comparison issues with versionLabel
            // (e.g., "10.0" vs "2.0" would sort incorrectly with string comparison)
            allVersions.sort((a, b) -> {
                // Primary: sort by creation date
                if (a.getCreated() != null && b.getCreated() != null) {
                    return a.getCreated().compareTo(b.getCreated());
                }
                // Fallback: try to parse versionLabel as numeric
                String labelA = a.getVersionLabel();
                String labelB = b.getVersionLabel();
                if (labelA != null && labelB != null) {
                    try {
                        // Try to parse as version numbers (e.g., "1.0", "2.0")
                        double vA = Double.parseDouble(labelA);
                        double vB = Double.parseDouble(labelB);
                        return Double.compare(vA, vB);
                    } catch (NumberFormatException e) {
                        // Fall back to string comparison if not numeric
                        return labelA.compareTo(labelB);
                    }
                }
                return 0;
            });

            int versionNum = 1;
            for (Document version : allVersions) {
                // Skip the latest version (already exported as main file)
                if (version.isLatestVersion()) {
                    continue;
                }

                // Export version content (fix: InputStream resource leak)
                String versionPath = basePath + VERSION_PREFIX + versionNum;
                if (version.getAttachmentNodeId() != null) {
                    try {
                        var attachment = cs.getAttachment(repositoryId, version.getAttachmentNodeId());
                        if (attachment != null && attachment.getInputStream() != null) {
                            zos.putNextEntry(new ZipEntry(versionPath));
                            byte[] buffer = new byte[8192];
                            int len;
                            try (InputStream is = attachment.getInputStream()) {
                                while ((len = is.read(buffer)) != -1) {
                                    zos.write(buffer, 0, len);
                                }
                            }
                            zos.closeEntry();
                        }
                    } catch (Exception e) {
                        log.warn("Failed to export version content: " + versionPath, e);
                    }
                }

                // Export version metadata
                JSONObject versionMeta = new JSONObject();
                versionMeta.put("versionLabel", version.getVersionLabel());
                versionMeta.put("checkinComment", version.getCheckinComment());
                versionMeta.put("isMajorVersion", version.isMajorVersion());

                String versionMetaPath = versionPath + META_SUFFIX;
                zos.putNextEntry(new ZipEntry(versionMetaPath));
                zos.write(versionMeta.toJSONString().getBytes("UTF-8"));
                zos.closeEntry();

                versionNum++;
            }

        } catch (Exception e) {
            log.warn("Failed to export version history for: " + basePath, e);
        }
    }

    // ========== Filesystem-based Import/Export (Admin Only) ==========

    /**
     * Import content from a local filesystem directory (admin only).
     * Uses the custom NemakiWare format without ZIP compression.
     * 
     * @param repositoryId Repository ID
     * @param folderId Target folder ID to import into
     * @param sourcePath Local filesystem path to import from
     * @param request HTTP request for CallContext
     * @return JSON response with import results
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
            // Admin-only check
            CallContext callContext = createCallContext(request, repositoryId);
            String username = callContext.getUsername();
            if (username == null || username.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Authentication required");
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(response.toJSONString())
                        .build();
            }
            if (!"admin".equals(username)) {
                response.put("status", "error");
                response.put("message", "Admin access required for filesystem operations");
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(response.toJSONString())
                        .build();
            }

            // Check for null request body (MEDIUM 4)
            if (requestBody == null) {
                response.put("status", "error");
                response.put("message", "Request body is required");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            // Get source path from request body
            String sourcePath = (String) requestBody.get("sourcePath");
            if (sourcePath == null || sourcePath.isEmpty()) {
                response.put("status", "error");
                response.put("message", "sourcePath is required");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            // Validate path (security check)
            java.nio.file.Path sourceDir = Paths.get(sourcePath).toAbsolutePath().normalize();

            // Sandbox protection: check path is within allowed roots (HIGH 1)
            if (!isPathWithinAllowedRoots(sourceDir)) {
                response.put("status", "error");
                response.put("message", "Source path is not within allowed filesystem roots. " +
                        "Allowed roots: " + ALLOWED_FILESYSTEM_ROOTS);
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(response.toJSONString())
                        .build();
            }

            if (!Files.exists(sourceDir)) {
                response.put("status", "error");
                response.put("message", "Source path does not exist: " + sourcePath);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }
            if (!Files.isDirectory(sourceDir)) {
                response.put("status", "error");
                response.put("message", "Source path is not a directory: " + sourcePath);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            log.info("Starting filesystem import from: " + sourceDir + " to folder: " + folderId);

            // Import from filesystem
            ImportResult result = importFromFilesystemDirectory(repositoryId, folderId, sourceDir, callContext);

            // Build response
            response.put("status", result.errors.isEmpty() ? (result.warnings.isEmpty() ? "success" : "partial") : "error");
            response.put("foldersCreated", result.foldersCreated);
            response.put("documentsCreated", result.documentsCreated);
            if (!result.errors.isEmpty()) {
                JSONArray errorsArray = new JSONArray();
                errorsArray.addAll(result.errors);
                response.put("errors", errorsArray);
            }
            if (!result.warnings.isEmpty()) {
                JSONArray warningsArray = new JSONArray();
                warningsArray.addAll(result.warnings);
                response.put("warnings", warningsArray);
            }

            log.info("Filesystem import completed: " + result.documentsCreated + " documents, " + 
                    result.foldersCreated + " folders created");

            return Response.ok(response.toJSONString()).build();

        } catch (Exception e) {
            log.error("Filesystem import failed: " + e.getMessage(), e);
            response.put("status", "error");
            response.put("message", "Import failed: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(response.toJSONString())
                    .build();
        }
    }

    /**
     * Export content to a local filesystem directory (admin only).
     * Uses the custom NemakiWare format without ZIP compression.
     * 
     * @param repositoryId Repository ID
     * @param folderId Source folder ID to export
     * @param targetPath Local filesystem path to export to
     * @param request HTTP request for CallContext
     * @return JSON response with export results
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
            // Admin-only check
            CallContext callContext = createCallContext(request, repositoryId);
            String username = callContext.getUsername();
            if (username == null || username.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Authentication required");
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(response.toJSONString())
                        .build();
            }
            if (!"admin".equals(username)) {
                response.put("status", "error");
                response.put("message", "Admin access required for filesystem operations");
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(response.toJSONString())
                        .build();
            }

            // Check for null request body (MEDIUM 4)
            if (requestBody == null) {
                response.put("status", "error");
                response.put("message", "Request body is required");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            // Get target path and overwrite option from request body
            String targetPath = (String) requestBody.get("targetPath");
            if (targetPath == null || targetPath.isEmpty()) {
                response.put("status", "error");
                response.put("message", "targetPath is required");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            // Get overwrite option (MEDIUM 6) - default to false for safety
            Boolean allowOverwrite = (Boolean) requestBody.get("allowOverwrite");
            if (allowOverwrite == null) {
                allowOverwrite = false;
            }

            // Validate and create target directory
            java.nio.file.Path targetDir = Paths.get(targetPath).toAbsolutePath().normalize();

            // Sandbox protection: check path is within allowed roots (HIGH 1)
            if (!isPathWithinAllowedRoots(targetDir)) {
                response.put("status", "error");
                response.put("message", "Target path is not within allowed filesystem roots. " +
                        "Allowed roots: " + ALLOWED_FILESYSTEM_ROOTS);
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(response.toJSONString())
                        .build();
            }

            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }
            if (!Files.isDirectory(targetDir)) {
                response.put("status", "error");
                response.put("message", "Target path is not a directory: " + targetPath);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(response.toJSONString())
                        .build();
            }

            log.info("Starting filesystem export from folder: " + folderId + " to: " + targetDir + 
                    " (allowOverwrite=" + allowOverwrite + ")");

            // Get source folder
            ContentService cs = getContentService();
            Folder folder = cs.getFolder(repositoryId, folderId);
            if (folder == null) {
                response.put("status", "error");
                response.put("message", "Folder not found: " + folderId);
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(response.toJSONString())
                        .build();
            }

            // Export to filesystem
            ExportResult result = exportToFilesystemDirectory(repositoryId, folder, targetDir, callContext, allowOverwrite);

            // Build response
            response.put("status", result.errors.isEmpty() ? "success" : "partial");
            response.put("foldersExported", result.foldersExported);
            response.put("documentsExported", result.documentsExported);
            response.put("targetPath", targetDir.toString());
            if (!result.errors.isEmpty()) {
                JSONArray errorsArray = new JSONArray();
                errorsArray.addAll(result.errors);
                response.put("errors", errorsArray);
            }

            log.info("Filesystem export completed: " + result.documentsExported + " documents, " + 
                    result.foldersExported + " folders exported");

            return Response.ok(response.toJSONString()).build();

        } catch (Exception e) {
            log.error("Filesystem export failed: " + e.getMessage(), e);
            response.put("status", "error");
            response.put("message", "Export failed: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(response.toJSONString())
                    .build();
        }
    }

    /**
     * Import from a filesystem directory recursively.
     */
    private ImportResult importFromFilesystemDirectory(String repositoryId, String targetFolderId,
            java.nio.file.Path sourceDir, CallContext callContext) throws Exception {

        ImportResult result = new ImportResult();
        ContentService cs = getContentService();

        // Collect files and metadata
        List<java.nio.file.Path> files = new ArrayList<>();
        Map<String, JSONObject> metadataMap = new HashMap<>();

        // Use try-with-resources to properly close the stream (HIGH 2)
        try (Stream<java.nio.file.Path> walkStream = Files.walk(sourceDir)) {
            walkStream.forEach(path -> {
                if (Files.isRegularFile(path)) {
                    String relativePath = sourceDir.relativize(path).toString().replace("\\", "/");
                    files.add(path);

                    // Parse metadata files with size limit check (HIGH 3)
                    if (relativePath.endsWith(META_SUFFIX)) {
                        try {
                            // Check metadata file size before reading
                            long metaSize = Files.size(path);
                            if (metaSize > MAX_SINGLE_FILE_SIZE) {
                                result.warnings.add("Skipping large metadata file: " + relativePath);
                                return;
                            }
                            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                            JSONParser parser = new JSONParser();
                            JSONObject meta = (JSONObject) parser.parse(content);
                            String baseName = relativePath.substring(0, relativePath.length() - META_SUFFIX.length());
                            metadataMap.put(baseName, meta);
                        } catch (Exception e) {
                            result.warnings.add("Failed to parse metadata: " + relativePath);
                        }
                    }
                }
            });
        }

        // Build folder structure map
        Map<String, String> pathToFolderId = new HashMap<>();
        pathToFolderId.put("", targetFolderId);

        // Sort files by depth
        files.sort((a, b) -> {
            int depthA = sourceDir.relativize(a).getNameCount();
            int depthB = sourceDir.relativize(b).getNameCount();
            return depthA - depthB;
        });

        // Process files
        for (java.nio.file.Path filePath : files) {
            String relativePath = sourceDir.relativize(filePath).toString().replace("\\", "/");

            // Skip metadata and version files
            if (relativePath.endsWith(META_SUFFIX) || isVersionFile(relativePath)) {
                continue;
            }

            try {
                // Ensure parent folders exist
                String parentPath = getParentPath(relativePath);
                String parentFolderId = ensureFolderPath(repositoryId, parentPath, targetFolderId,
                        pathToFolderId, callContext, result);

                // Get filename
                String fileName = getFileName(relativePath);

                // Get metadata if available
                JSONObject metadata = metadataMap.get(relativePath);

                // Read file content
                if (Files.size(filePath) > MAX_SINGLE_FILE_SIZE) {
                    result.warnings.add("Skipping large file: " + relativePath);
                    continue;
                }
                byte[] content = Files.readAllBytes(filePath);
                String mimeType = guessMimeType(fileName);

                PropertiesImpl props = new PropertiesImpl();
                props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document"));
                props.addProperty(new PropertyStringImpl(PropertyIds.NAME, fileName));

                // Apply custom properties from metadata
                if (metadata != null) {
                    applyCustomProperties(metadata, props);
                }

                ContentStream contentStream = new ContentStreamImpl(fileName,
                        BigInteger.valueOf(content.length), mimeType, new ByteArrayInputStream(content));

                Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
                Document newDoc = cs.createDocument(callContext, repositoryId, props, parentFolder,
                        contentStream, VersioningState.MAJOR, null, null, null);

                result.documentsCreated++;

                // Apply ACL from metadata
                if (metadata != null) {
                    applyCustomAcl(repositoryId, newDoc.getId(), metadata, callContext, result);
                }

                // Import version history from filesystem
                importVersionHistoryFromFilesystem(repositoryId, relativePath, sourceDir,
                        newDoc, callContext, result);

            } catch (Exception e) {
                log.error("Failed to import file: " + relativePath + " - " + e.getMessage(), e);
                result.errors.add("Failed to import: " + relativePath + " - " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Import version history from filesystem.
     */
    private void importVersionHistoryFromFilesystem(String repositoryId, String basePath, java.nio.file.Path sourceDir,
            Document doc, CallContext callContext, ImportResult result) {

        try {
            // Find version files
            List<java.nio.file.Path> versionFiles = new ArrayList<>();
            String baseFileName = getFileName(basePath);
            String parentPath = getParentPath(basePath);
            java.nio.file.Path parentDir = parentPath.isEmpty() ? sourceDir : sourceDir.resolve(parentPath);

            // Use try-with-resources to properly close the stream (HIGH 2)
            if (Files.exists(parentDir)) {
                try (Stream<java.nio.file.Path> listStream = Files.list(parentDir)) {
                    listStream.forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (isVersionFileFor(fileName, baseFileName)) {
                            versionFiles.add(path);
                        }
                    });
                }
            }

            if (versionFiles.isEmpty()) {
                return;
            }

            // Sort by version number
            versionFiles.sort((a, b) -> {
                int vA = extractVersionNumber(a.getFileName().toString());
                int vB = extractVersionNumber(b.getFileName().toString());
                return vA - vB;
            });

            // Log warning - version import not fully implemented
            result.warnings.add("Version history found for " + basePath + " (" + versionFiles.size() + 
                    " versions) - version import requires check-out/check-in cycles (not implemented)");

        } catch (Exception e) {
            log.warn("Failed to import version history for: " + basePath, e);
            result.warnings.add("Failed to import version history for: " + basePath);
        }
    }

    /**
     * Export to a filesystem directory recursively.
     * 
     * @param allowOverwrite If false, existing files will not be overwritten (MEDIUM 6)
     */
    @SuppressWarnings("unchecked")
    private ExportResult exportToFilesystemDirectory(String repositoryId, Folder folder,
            java.nio.file.Path targetDir, CallContext callContext, boolean allowOverwrite) throws Exception {

        ExportResult result = new ExportResult();
        exportFolderToFilesystem(repositoryId, folder, targetDir, callContext, result, allowOverwrite);
        return result;
    }

    /**
     * Export a folder and its contents to filesystem.
     * 
     * @param allowOverwrite If false, existing files will not be overwritten (MEDIUM 6)
     */
    @SuppressWarnings("unchecked")
    private void exportFolderToFilesystem(String repositoryId, Folder folder, java.nio.file.Path targetDir,
            CallContext callContext, ExportResult result, boolean allowOverwrite) throws Exception {

        ContentService cs = getContentService();
        List<Content> children = cs.getChildren(repositoryId, folder.getId());

        for (Content child : children) {
            // Skip children with null name (e.g., system user items without a name field)
            if (child.getName() == null) {
                log.debug("Skipping child with null name (id=" + child.getId() + ", type=" + child.getType() + ")");
                continue;
            }

            // Skip .system folder (contains internal user/group items, not exportable content)
            if (child instanceof Folder && ".system".equals(child.getName())) {
                log.debug("Skipping .system folder during export");
                continue;
            }

            // Skip non-folder/non-document items (e.g., cmis:item user/group objects)
            if (!(child instanceof Folder) && !(child instanceof Document)) {
                log.debug("Skipping non-folder/non-document item: " + child.getName() + " (type=" + child.getType() + ")");
                continue;
            }

            java.nio.file.Path childPath = targetDir.resolve(child.getName());

            if (child instanceof Folder) {
                // Create folder
                Files.createDirectories(childPath);
                result.foldersExported++;

                // Recurse into folder
                exportFolderToFilesystem(repositoryId, (Folder) child, childPath, callContext, result, allowOverwrite);

            } else if (child instanceof Document) {
                Document doc = (Document) child;

                // Check if file exists and overwrite is not allowed (MEDIUM 6)
                if (Files.exists(childPath) && !allowOverwrite) {
                    result.errors.add("File already exists (overwrite not allowed): " + child.getName());
                    continue;
                }

                // Export document content
                if (doc.getAttachmentNodeId() != null) {
                    try {
                        var attachment = cs.getAttachment(repositoryId, doc.getAttachmentNodeId());
                        if (attachment != null && attachment.getInputStream() != null) {
                            // Use CREATE_NEW if overwrite not allowed, otherwise default behavior
                            StandardOpenOption[] options = allowOverwrite 
                                ? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING }
                                : new StandardOpenOption[] { StandardOpenOption.CREATE_NEW };
                            try (InputStream is = attachment.getInputStream();
                                 OutputStream os = Files.newOutputStream(childPath, options)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, len);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to export content for: " + childPath, e);
                        result.errors.add("Failed to export content: " + child.getName());
                        continue;
                    }
                }

                // Export metadata
                JSONObject metadata = buildDocumentMetadata(repositoryId, doc, callContext);
                java.nio.file.Path metaPath = targetDir.resolve(child.getName() + META_SUFFIX);
                // Check metadata file exists
                if (Files.exists(metaPath) && !allowOverwrite) {
                    result.errors.add("Metadata file already exists (overwrite not allowed): " + child.getName() + META_SUFFIX);
                } else {
                    try (FileWriter writer = new FileWriter(metaPath.toFile(), StandardCharsets.UTF_8)) {
                        writer.write(metadata.toJSONString());
                    }
                }

                result.documentsExported++;

                // Export version history
                exportVersionHistoryToFilesystem(repositoryId, doc, targetDir, callContext, result, allowOverwrite);
            }
        }
    }

    /**
     * Export version history to filesystem.
     * 
     * @param allowOverwrite If false, existing files will not be overwritten (MEDIUM 6)
     */
    @SuppressWarnings("unchecked")
    private void exportVersionHistoryToFilesystem(String repositoryId, Document doc, java.nio.file.Path targetDir,
            CallContext callContext, ExportResult result, boolean allowOverwrite) {

        try {
            ContentService cs = getContentService();
            VersionSeries vs = cs.getVersionSeries(repositoryId, doc);
            if (vs == null) {
                return;
            }

            List<Document> allVersions = cs.getAllVersions(callContext, repositoryId, vs.getId());
            if (allVersions == null || allVersions.size() <= 1) {
                return; // Only current version exists
            }

            // Sort versions by creationDate
            allVersions.sort((a, b) -> {
                if (a.getCreated() != null && b.getCreated() != null) {
                    return a.getCreated().compareTo(b.getCreated());
                }
                String labelA = a.getVersionLabel();
                String labelB = b.getVersionLabel();
                if (labelA != null && labelB != null) {
                    try {
                        double vA = Double.parseDouble(labelA);
                        double vB = Double.parseDouble(labelB);
                        return Double.compare(vA, vB);
                    } catch (NumberFormatException e) {
                        return labelA.compareTo(labelB);
                    }
                }
                return 0;
            });

            int versionNum = 1;
            for (Document version : allVersions) {
                // Skip the latest version (already exported as main file)
                if (version.isLatestVersion()) {
                    continue;
                }

                // Export version content
                String versionFileName = doc.getName() + VERSION_PREFIX + versionNum;
                java.nio.file.Path versionPath = targetDir.resolve(versionFileName);

                // Check if file exists and overwrite is not allowed (MEDIUM 6)
                if (Files.exists(versionPath) && !allowOverwrite) {
                    result.errors.add("Version file already exists (overwrite not allowed): " + versionFileName);
                    versionNum++;
                    continue;
                }

                if (version.getAttachmentNodeId() != null) {
                    try {
                        var attachment = cs.getAttachment(repositoryId, version.getAttachmentNodeId());
                        if (attachment != null && attachment.getInputStream() != null) {
                            StandardOpenOption[] options = allowOverwrite 
                                ? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING }
                                : new StandardOpenOption[] { StandardOpenOption.CREATE_NEW };
                            try (InputStream is = attachment.getInputStream();
                                 OutputStream os = Files.newOutputStream(versionPath, options)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, len);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to export version content: " + versionFileName, e);
                    }
                }

                // Export version metadata
                JSONObject versionMeta = new JSONObject();
                versionMeta.put("versionLabel", version.getVersionLabel());
                versionMeta.put("checkinComment", version.getCheckinComment());
                versionMeta.put("isMajorVersion", version.isMajorVersion());

                java.nio.file.Path versionMetaPath = targetDir.resolve(versionFileName + META_SUFFIX);
                // Check metadata file exists
                if (Files.exists(versionMetaPath) && !allowOverwrite) {
                    result.errors.add("Version metadata file already exists (overwrite not allowed): " + versionFileName + META_SUFFIX);
                } else {
                    try (FileWriter writer = new FileWriter(versionMetaPath.toFile(), StandardCharsets.UTF_8)) {
                        writer.write(versionMeta.toJSONString());
                    }
                }

                versionNum++;
            }

        } catch (Exception e) {
            log.warn("Failed to export version history for: " + doc.getName(), e);
        }
    }

    // ========== Utility Methods ==========

    /**
     * Check if a path is within one of the allowed filesystem roots (HIGH 1 - sandbox protection).
     * This prevents access to arbitrary filesystem locations.
     * 
     * @param path The path to check (should already be normalized)
     * @return true if the path is within an allowed root, false otherwise
     */
    private boolean isPathWithinAllowedRoots(java.nio.file.Path path) {
        String pathStr = path.toAbsolutePath().normalize().toString();
        for (String allowedRoot : ALLOWED_FILESYSTEM_ROOTS) {
            java.nio.file.Path rootPath = Paths.get(allowedRoot).toAbsolutePath().normalize();
            String rootStr = rootPath.toString();
            // Check if path starts with the allowed root
            if (pathStr.startsWith(rootStr)) {
                // Make sure it's actually within the directory, not just a prefix match
                // e.g., /tmp/nemakiware-import should not match /tmp/nemakiware-import-other
                if (pathStr.length() == rootStr.length() || 
                    pathStr.charAt(rootStr.length()) == java.io.File.separatorChar) {
                    return true;
                }
            }
        }
        return false;
    }

    private String guessMimeType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    private CallContext createCallContext(HttpServletRequest request, String repositoryId) {
        // Use CallContext set by AuthenticationFilter (which handles Basic auth and token auth)
        CallContext filterContext = (CallContext) request.getAttribute("CallContext");
        if (filterContext != null) {
            return filterContext;
        }
        // Fallback: create minimal context (username will be null → triggers 401)
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
            public java.io.File getTempDirectory() { return null; }
            @Override
            public boolean encryptTempFiles() { return false; }
            @Override
            public int getMemoryThreshold() { return 4 * 1024 * 1024; }
            @Override
            public long getMaxContentSize() { return -1; }
        };
    }

    // ========== Result Classes ==========

    private static class ImportResult {
        int foldersCreated = 0;
        int documentsCreated = 0;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
    }

    private static class ExportResult {
        int foldersExported = 0;
        int documentsExported = 0;
        List<String> errors = new ArrayList<>();
    }
}
