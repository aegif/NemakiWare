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
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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

    // Size limits for import (prevent OOM)
    private static final long MAX_UPLOAD_SIZE = 500 * 1024 * 1024; // 500MB max upload
    private static final long MAX_SINGLE_FILE_SIZE = 100 * 1024 * 1024; // 100MB max per file

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
        ContentService cs = getContentService();

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
        try {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            // Check file size limit (if known)
            long entrySize = entry.getSize();
            if (entrySize > MAX_SINGLE_FILE_SIZE) {
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
                    if (totalRead > MAX_SINGLE_FILE_SIZE) {
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

    /**
     * Read a single entry from a ZipFile (opens and closes the file).
     * Use this only when you don't have an open ZipFile instance.
     */
    private byte[] readZipEntry(File zipFile, String entryName) {
        try (ZipFile zf = new ZipFile(zipFile)) {
            return readZipEntry(zf, entryName);
        } catch (IOException e) {
            log.warn("Failed to open ZIP file: " + zipFile, e);
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

                    // Read file content on-demand from open ZipFile (fix: OOM + performance)
                    byte[] content = readZipEntry(zf, path);
                    if (content == null) {
                        result.warnings.add("Could not read file content: " + path);
                        continue;
                    }
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

                    // Import version history (pass open ZipFile for performance)
                    importVersionHistory(repositoryId, path, zf, metadataMap, 
                            newDoc, callContext, result);

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
        return fileName.matches(".*\\.v\\d+$") || fileName.matches(".*\\.v\\d+\\..*");
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

            // Skip CMIS system properties that are auto-generated
            if (propName.equals(PropertyIds.OBJECT_ID) ||
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

    private void importVersionHistory(String repositoryId, String basePath, 
            ZipFile zf, Map<String, JSONObject> metadataMap,
            Document document, CallContext callContext, ImportResult result) {

        // Look for version files by scanning the open ZipFile (fix: ZipFile performance)
        String baseFileName = getFileName(basePath);
        String parentPath = getParentPath(basePath);

        List<String> versionPaths = new ArrayList<>();
        try {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                if (!entry.isDirectory() && path.startsWith(parentPath) && isVersionFileFor(path, baseFileName)) {
                    versionPaths.add(path);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan ZIP for version files: " + e.getMessage());
            return;
        }

        if (versionPaths.isEmpty()) {
            return;
        }

        // Sort versions by version number
        versionPaths.sort((a, b) -> {
            int vA = extractVersionNumber(a);
            int vB = extractVersionNumber(b);
            return vA - vB;
        });

        // Note: Version history import is complex and may require check-out/check-in cycles
        // For now, we log a warning that versions were found but not imported
        if (!versionPaths.isEmpty()) {
            result.warnings.add("Version history found for " + basePath + " but version import not yet implemented");
        }
    }

    private boolean isVersionFileFor(String path, String baseFileName) {
        String fileName = getFileName(path);
        // Match patterns like "file.txt.v1" or "file.v1.txt"
        return fileName.startsWith(baseFileName + VERSION_PREFIX) ||
               fileName.matches(baseFileName.replaceAll("\\.[^.]+$", "") + "\\.v\\d+\\..*");
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
            for (Map.Entry<String, Object> entry : doc.getSubTypeProperties().entrySet()) {
                if (entry.getValue() != null) {
                    properties.put(entry.getKey(), entry.getValue().toString());
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

    // ========== Utility Methods ==========

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
        // Create a simple CallContext from request (fix: no admin fallback)
        // If user is not authenticated, getUsername() returns null
        return new CallContext() {
            @Override
            public String getBinding() { return "browser"; }
            @Override
            public boolean isObjectInfoRequired() { return false; }
            @Override
            public Object get(String key) {
                if ("username".equals(key)) {
                    return request.getRemoteUser();
                }
                if ("repositoryId".equals(key)) {
                    return repositoryId;
                }
                return null;
            }
            @Override
            public String getRepositoryId() { return repositoryId; }
            @Override
            public String getUsername() {
                return request.getRemoteUser();
            }
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
}
