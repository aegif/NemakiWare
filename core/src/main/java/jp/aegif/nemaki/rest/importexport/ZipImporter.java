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
package jp.aegif.nemaki.rest.importexport;

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
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static jp.aegif.nemaki.rest.importexport.ImportExportUtils.*;

/**
 * Handles ZIP-based import operations (ACP format and custom NemakiWare format).
 */
public class ZipImporter {

    private static final Log log = LogFactory.getLog(ZipImporter.class);

    // Permission mapping from Alfresco to NemakiWare
    private static final Map<String, String> PERMISSION_MAPPING = new HashMap<>();
    static {
        PERMISSION_MAPPING.put("Consumer", "cmis:read");
        PERMISSION_MAPPING.put("Contributor", "cmis:write");
        PERMISSION_MAPPING.put("Coordinator", "cmis:all");
        PERMISSION_MAPPING.put("Editor", "cmis:write");
        PERMISSION_MAPPING.put("Collaborator", "cmis:write");
    }

    public enum ImportFormat {
        ACP, CUSTOM, UNKNOWN
    }

    public ImportFormat detectFormat(File zipFile) throws IOException {
        try (ZipFile zf = new ZipFile(zipFile)) {
            boolean hasPackageXml = false;
            boolean hasMetaJson = false;
            boolean hasAnyFile = false;

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory()) {
                    hasAnyFile = true;
                }
                if (name.endsWith(".xml") && !name.contains("/")) {
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
            } else if (hasAnyFile) {
                log.info("No metadata found in ZIP; treating as plain file import");
                return ImportFormat.CUSTOM;
            }
        }
        return ImportFormat.UNKNOWN;
    }

    // ========== ACP Import ==========

    public ImportResult importAcpFormat(String repositoryId, String targetFolderId,
            File zipFile, CallContext callContext) throws Exception {

        ImportResult result = new ImportResult();

        String packageXmlName = null;
        byte[] xmlData = null;

        // First pass: find and read package XML
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!isValidZipEntryName(name)) {
                    result.warnings.add("Skipping invalid path: " + name);
                    continue;
                }

                if (!entry.isDirectory() && name.endsWith(".xml") && !name.contains("/")) {
                    long entrySize = entry.getSize();
                    if (entrySize > MAX_METADATA_SIZE) {
                        result.errors.add("ACP package XML too large: " + name + " (size: " + entrySize + ", limit: " + MAX_METADATA_SIZE + ")");
                        return result;
                    }
                    packageXmlName = name;
                    try (InputStream is = zf.getInputStream(entry)) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int len;
                        long totalRead = 0;
                        while ((len = is.read(buffer)) != -1) {
                            totalRead += len;
                            if (totalRead > MAX_METADATA_SIZE) {
                                result.errors.add("ACP package XML exceeds size limit during read: " + name);
                                return result;
                            }
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

        // Parse XML through the hardened helper. Extracted in RC6.12 so
        // ZipImporterXxeTest can exercise the exact production path
        // (RC6.11 review P2: the test had its own duplicated SAXReader
        // configuration, so a future deletion of the production
        // setFeature(...) calls would not have failed the test).
        org.dom4j.Document xmlDoc;
        try {
            xmlDoc = parseAcpPackageXml(xmlData);
        } catch (DocumentException e) {
            result.errors.add("Failed to parse package XML: " + e.getMessage());
            return result;
        }

        String packageName = packageXmlName.replace(".xml", "");

        Element root = xmlDoc.getRootElement();
        Map<String, String> uuidToObjectId = new HashMap<>();
        uuidToObjectId.put("", targetFolderId);

        try (ZipFile zf = new ZipFile(zipFile)) {
            processAcpNodes(repositoryId, root, targetFolderId, packageName, zf,
                    uuidToObjectId, callContext, result);
        }

        return result;
    }

    /**
     * Build a {@link SAXReader} with XXE protections enabled.
     *
     * <p>Three SAX features are set:
     * {@code disallow-doctype-decl=true},
     * {@code external-general-entities=false}, and
     * {@code external-parameter-entities=false}. This mirrors the
     * sibling hardening in
     * {@code jp.aegif.nemaki.rest.TypeResource.parse(...)} (present
     * since RC13). Without these features, a non-admin user with
     * {@code cmis:write} on a single target folder could upload a
     * crafted ACP whose package XML embedded a {@code SYSTEM} entity
     * pointing at {@code file:///etc/passwd} or
     * {@code http://internal-host/...}, and the resolved content
     * would be persisted verbatim into the created CMIS folder name
     * — CWE-611 read-capable XXE / SSRF (GHSA, reporter
     * <em>tonghuaroot</em>).
     *
     * <p>Package-private and extracted (RC6.13) so
     * {@code ZipImporterXxeTest} can hold the actual
     * production-configured {@link SAXReader} instance and assert
     * its {@code getXMLReader().getFeature(...)} values directly. A
     * deletion of any individual {@code setFeature(...)} call in
     * this method then fails the readback test for that specific
     * feature — RC6.12's test only proved a test-local probe was
     * configured, which would have allowed (e.g.) removing
     * {@code external-general-entities=false} while leaving
     * {@code disallow-doctype-decl=true} without any test failure.
     */
    static SAXReader configureHardenedSaxReader() throws DocumentException {
        SAXReader reader = new SAXReader();
        try {
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (org.xml.sax.SAXException e) {
            throw new DocumentException("Failed to configure XXE protection on SAXReader", e);
        }
        return reader;
    }

    /**
     * Parse the top-level ACP package XML with XXE protections
     * enabled. Builds a hardened reader via
     * {@link #configureHardenedSaxReader()} and parses the supplied
     * byte stream. See that method's javadoc for the security
     * rationale.
     */
    static org.dom4j.Document parseAcpPackageXml(byte[] xmlData) throws DocumentException {
        SAXReader reader = configureHardenedSaxReader();
        return reader.read(new ByteArrayInputStream(xmlData));
    }

    @SuppressWarnings("unchecked")
    private void processAcpNodes(String repositoryId, Element element, String parentFolderId,
            String packageName, ZipFile zf, Map<String, String> uuidToObjectId,
            CallContext callContext, ImportResult result) {

        ContentService cs = getContentService();

        for (Iterator<Element> it = element.elementIterator(); it.hasNext();) {
            Element child = it.next();
            String tagName = child.getName();

            if ("folder".equals(tagName) || tagName.endsWith(":folder")) {
                try {
                    String uuid = getAcpAttribute(child, "node-uuid");
                    String name = getAcpChildName(child);
                    if (name == null) {
                        name = "folder_" + System.currentTimeMillis();
                    }

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

                    processAcpAcl(repositoryId, child, newFolder.getId(), callContext, result);

                    processAcpNodes(repositoryId, child, newFolder.getId(), packageName,
                            zf, uuidToObjectId, callContext, result);

                } catch (Exception e) {
                    log.error("Failed to create folder: " + e.getMessage(), e);
                    result.errors.add("Failed to create folder: " + e.getMessage());
                }
            }

            if ("content".equals(tagName) || tagName.endsWith(":content")) {
                try {
                    String uuid = getAcpAttribute(child, "node-uuid");
                    String name = getAcpChildName(child);
                    if (name == null) {
                        name = "document_" + System.currentTimeMillis();
                    }

                    String contentRef = getAcpContentReference(child);
                    String mimeType = "application/octet-stream";

                    if (contentRef != null) {
                        mimeType = guessMimeType(contentRef);
                    }

                    PropertiesImpl props = new PropertiesImpl();
                    props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document"));
                    props.addProperty(new PropertyStringImpl(PropertyIds.NAME, name));

                    addAcpProperties(child, props);

                    ContentStream contentStream = null;
                    if (contentRef != null) {
                        String filePath = packageName + "/" + contentRef;
                        contentStream = createContentStreamFromZip(zf, filePath, name, mimeType);
                        if (contentStream == null) {
                            contentStream = createContentStreamFromZip(zf, contentRef, name, mimeType);
                        }
                    }

                    Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
                    Document newDoc = cs.createDocument(callContext, repositoryId, props, parentFolder,
                            contentStream, VersioningState.MAJOR, null, null, null);

                    if (uuid != null) {
                        uuidToObjectId.put(uuid, newDoc.getId());
                    }
                    result.documentsCreated++;

                    processAcpAcl(repositoryId, child, newDoc.getId(), callContext, result);

                } catch (Exception e) {
                    log.error("Failed to create document: " + e.getMessage(), e);
                    result.errors.add("Failed to create document: " + e.getMessage());
                }
            }
        }
    }

    // ========== Custom Format Import ==========

    public ImportResult importCustomFormat(String repositoryId, String targetFolderId,
            File zipFile, CallContext callContext) throws Exception {

        ImportResult result = new ImportResult();
        ContentService cs = getContentService();

        Map<String, String> oldIdToNewId = new HashMap<>();

        List<String> entryNames = new ArrayList<>();
        Map<String, JSONObject> metadataMap = new HashMap<>();

        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!isValidZipEntryName(name)) {
                    result.warnings.add("Skipping invalid path: " + name);
                    continue;
                }

                if (!entry.isDirectory()) {
                    if (name.startsWith(TYPE_DEFINITIONS_DIR) || name.startsWith(RELATIONSHIPS_DIR)) {
                        continue;
                    }
                    entryNames.add(name);

                    if (name.endsWith(META_SUFFIX)) {
                        long jsonSize = entry.getSize();
                        if (jsonSize > MAX_METADATA_SIZE) {
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
                                if (totalRead > MAX_METADATA_SIZE) {
                                    result.warnings.add("Skipping metadata file exceeding size limit: " + name);
                                    break;
                                }
                                baos.write(buffer, 0, len);
                            }
                            if (totalRead <= MAX_METADATA_SIZE) {
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

        Map<String, String> pathToFolderId = new HashMap<>();
        pathToFolderId.put("", targetFolderId);

        entryNames.sort((a, b) -> {
            int depthA = a.split("/").length;
            int depthB = b.split("/").length;
            return depthA - depthB;
        });

        try (ZipFile zf = new ZipFile(zipFile)) {
            importTypeDefinitions(repositoryId, zf, result);

            for (String path : entryNames) {
                if (path.endsWith(META_SUFFIX) || isVersionFile(path)) {
                    continue;
                }

                try {
                    String parentPath = getParentPath(path);
                    String parentFolderId = ensureFolderPath(repositoryId, parentPath, targetFolderId,
                            pathToFolderId, callContext, result, metadataMap, oldIdToNewId);

                    String fileName = getFileName(path);

                    JSONObject metadata = metadataMap.get(path);

                    String oldObjectId = null;
                    if (metadata != null) {
                        JSONObject metaProps = (JSONObject) metadata.get("properties");
                        if (metaProps != null) {
                            Object oldIdObj = metaProps.get(PropertyIds.OBJECT_ID);
                            if (oldIdObj != null && !oldIdObj.toString().isEmpty()) {
                                oldObjectId = oldIdObj.toString();
                            }
                        }
                    }

                    Content existingChild = null;
                    List<Content> siblings = cs.getChildren(repositoryId, parentFolderId);
                    for (Content sibling : siblings) {
                        if (fileName.equals(sibling.getName())) {
                            existingChild = sibling;
                            break;
                        }
                    }

                    List<String> versionPaths = findVersionFilesFor(path, zf);

                    String mimeType = guessMimeType(fileName);

                    String contentEntryPath;
                    if (!versionPaths.isEmpty()) {
                        contentEntryPath = versionPaths.get(0);
                        if (zf.getEntry(contentEntryPath) == null) {
                            contentEntryPath = path;
                        }
                    } else {
                        contentEntryPath = path;
                    }

                    ContentStream contentStream = createContentStreamFromZip(zf, contentEntryPath, fileName, mimeType);
                    if (contentStream == null) {
                        result.warnings.add("Could not read file content: " + path);
                        continue;
                    }

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

                    boolean isOverwrite = (existingChild != null && existingChild instanceof Document);
                    String createName = isOverwrite
                            ? ".importing-" + System.currentTimeMillis() + "-" + fileName
                            : fileName;

                    PropertiesImpl props = new PropertiesImpl();
                    props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, objectTypeId));
                    props.addProperty(new PropertyStringImpl(PropertyIds.NAME, createName));

                    if (metadata != null) {
                        applyCustomProperties(metadata, props, objectTypeId, repositoryId);
                    }

                    Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
                    Document newDoc = cs.createDocument(callContext, repositoryId, props, parentFolder,
                            contentStream, VersioningState.MAJOR, null, null, null);

                    if (isOverwrite) {
                        String existingDocId = existingChild.getId();
                        log.info("Import: overwriting existing document '" + fileName + "' (id=" + existingDocId + ")");
                        try {
                            cs.deleteDocument(callContext, repositoryId, existingDocId, true, false);
                        } catch (Exception e) {
                            log.warn("Failed to delete existing document for overwrite: " + fileName + " - " + e.getMessage());
                            result.warnings.add("Failed to overwrite (cannot delete existing): " + fileName);
                            try {
                                cs.deleteDocument(callContext, repositoryId, newDoc.getId(), true, false);
                            } catch (Exception cleanupEx) {
                                log.warn("Failed to clean up temp document: " + createName + " - " + cleanupEx.getMessage());
                                result.warnings.add("Orphaned temp document: " + createName);
                            }
                            continue;
                        }

                        try {
                            PropertiesImpl renameProps = new PropertiesImpl();
                            renameProps.addProperty(new PropertyStringImpl(PropertyIds.NAME, fileName));
                            cs.updateProperties(callContext, repositoryId, renameProps, newDoc);
                        } catch (Exception e) {
                            log.warn("Failed to rename imported document from temp name: " + createName + " - " + e.getMessage());
                            result.warnings.add("Document imported with temp name (rename failed): " + createName);
                        }
                    }

                    result.documentsCreated++;

                    if (oldObjectId != null) {
                        oldIdToNewId.put(oldObjectId, newDoc.getId());
                    }

                    if (metadata != null) {
                        applyCustomAcl(repositoryId, newDoc.getId(), metadata, callContext, result);
                    }

                    importVersionHistory(repositoryId, path, zf,
                            newDoc, callContext, result, metadataMap, versionPaths);

                } catch (Exception e) {
                    log.error("Failed to import file: " + path + " - " + e.getMessage(), e);
                    result.errors.add("Failed to import: " + path + " - " + e.getMessage());
                }
            }

            importRelationships(repositoryId, zf, oldIdToNewId, callContext, result);
        }

        return result;
    }

    // ========== ZIP Read Utilities ==========

    /**
     * Read a small entry (metadata/JSON) from an already-open ZipFile into memory.
     */
    byte[] readZipEntry(ZipFile zf, String entryName) {
        return readZipEntryWithLimit(zf, entryName, MAX_METADATA_SIZE);
    }

    public byte[] readZipEntryWithLimit(ZipFile zf, String entryName, long maxSize) {
        try {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
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

    /**
     * Create a ContentStream directly from a ZIP entry without loading the entire file into memory.
     */
    private ContentStream createContentStreamFromZip(ZipFile zf, String entryName, String fileName, String mimeType) {
        try {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            long entrySize = entry.getSize();
            if (entrySize > MAX_SINGLE_FILE_SIZE) {
                log.warn("Skipping file exceeding size limit: " + entryName + " (size: " + entrySize + ")");
                return null;
            }
            InputStream is = zf.getInputStream(entry);
            BigInteger length = (entrySize >= 0) ? BigInteger.valueOf(entrySize) : null;
            return new ContentStreamImpl(fileName, length, mimeType, is);
        } catch (IOException e) {
            log.warn("Failed to open ZIP entry stream: " + entryName, e);
            return null;
        }
    }

    // ========== ACP Helper Methods ==========

    private String getAcpAttribute(Element element, String attrName) {
        String value = element.attributeValue("alf:" + attrName);
        if (value == null) {
            value = element.attributeValue(attrName);
        }
        return value;
    }

    private String getAcpChildName(Element element) {
        String name = element.attributeValue("view:childName");
        if (name == null) {
            name = element.attributeValue("childName");
        }
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
                props.addProperty(new PropertyStringImpl(propName, propValue));
            }
        }
    }

    private void processAcpAcl(String repositoryId, Element element, String objectId,
            CallContext callContext, ImportResult result) {
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

            for (Iterator<Element> it = aclEl.elementIterator("permission"); it.hasNext();) {
                Element permEl = it.next();
                String authority = permEl.attributeValue("authority");
                String access = permEl.attributeValue("access");

                if (authority != null && access != null) {
                    String mappedPermission = PERMISSION_MAPPING.getOrDefault(access, "cmis:read");

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

    // ========== Custom Format Helper Methods ==========

    private String ensureFolderPath(String repositoryId, String path, String rootFolderId,
            Map<String, String> pathToFolderId, CallContext callContext, ImportResult result,
            Map<String, JSONObject> metadataMap, Map<String, String> oldIdToNewId) throws Exception {

        if (path.isEmpty()) {
            return rootFolderId;
        }

        if (pathToFolderId.containsKey(path)) {
            return pathToFolderId.get(path);
        }

        ContentService cs = getContentService();

        String parentPath = getParentPath(path);
        String parentFolderId = ensureFolderPath(repositoryId, parentPath, rootFolderId,
                pathToFolderId, callContext, result, metadataMap, oldIdToNewId);

        String folderName = getFileName(path);

        List<Content> siblings = cs.getChildren(repositoryId, parentFolderId);
        for (Content sibling : siblings) {
            if (folderName.equals(sibling.getName()) && sibling instanceof Folder) {
                String existingFolderId = sibling.getId();
                pathToFolderId.put(path, existingFolderId);
                log.info("Import: reusing existing folder '" + folderName + "' (id=" + existingFolderId + ")");

                if (metadataMap != null && oldIdToNewId != null) {
                    JSONObject folderMeta = metadataMap.get(path + "/");
                    if (folderMeta != null) {
                        JSONObject metaProps = (JSONObject) folderMeta.get("properties");
                        if (metaProps != null) {
                            Object oldIdObj = metaProps.get(PropertyIds.OBJECT_ID);
                            if (oldIdObj != null && !oldIdObj.toString().isEmpty()) {
                                oldIdToNewId.put(oldIdObj.toString(), existingFolderId);
                            }
                        }
                    }
                }

                return existingFolderId;
            }
        }

        PropertiesImpl props = new PropertiesImpl();
        props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
        props.addProperty(new PropertyStringImpl(PropertyIds.NAME, folderName));

        Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
        Folder newFolder = cs.createFolder(callContext, repositoryId, props, parentFolder,
                null, null, null, null);

        pathToFolderId.put(path, newFolder.getId());
        result.foldersCreated++;

        if (metadataMap != null && oldIdToNewId != null) {
            JSONObject folderMeta = metadataMap.get(path + "/");
            if (folderMeta != null) {
                JSONObject metaProps = (JSONObject) folderMeta.get("properties");
                if (metaProps != null) {
                    Object oldIdObj = metaProps.get(PropertyIds.OBJECT_ID);
                    if (oldIdObj != null && !oldIdObj.toString().isEmpty()) {
                        oldIdToNewId.put(oldIdObj.toString(), newFolder.getId());
                    }
                }
            }
        }

        return newFolder.getId();
    }

    @SuppressWarnings("unchecked")
    private void applyCustomProperties(JSONObject metadata, PropertiesImpl props,
                                       String typeId, String repositoryId) {
        JSONObject properties = (JSONObject) metadata.get("properties");
        if (properties == null) {
            return;
        }

        for (Object key : properties.keySet()) {
            String propName = (String) key;
            Object propValue = properties.get(propName);

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
                addTypedProperty(props, propName, propValue, typeId, repositoryId);
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

    // ========== Type Definition Import ==========

    @SuppressWarnings("unchecked")
    private void importTypeDefinitions(String repositoryId, ZipFile zf, ImportResult result) {
        TypeService ts = getTypeService();
        if (ts == null) {
            log.debug("TypeService not available, skipping type definition import");
            return;
        }

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

                NemakiTypeDefinition existing = ts.getTypeDefinition(repositoryId, typeId);
                if (existing != null) {
                    log.info("Type already exists, skipping: " + typeId);
                    typesSkipped++;
                    continue;
                }

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

                List<String> propertyNodeIds = new ArrayList<>();
                Object propDefsObj = typeJson.get("propertyDefinitions");
                if (propDefsObj instanceof JSONArray) {
                    JSONArray propDefs = (JSONArray) propDefsObj;
                    for (Object propObj : propDefs) {
                        JSONObject propJson = (JSONObject) propObj;
                        String propId = (String) propJson.get("id");
                        if (propId == null) continue;

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

                ts.createTypeDefinition(repositoryId, tdf);
                typesCreated++;
                log.info("Created type definition: " + typeId);

            } catch (Exception e) {
                log.error("Failed to import type definition from: " + entryName, e);
                result.warnings.add("Failed to import type definition: " + entryName + " - " + e.getMessage());
            }
        }

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

    // ========== Relationship Import ==========

    @SuppressWarnings("unchecked")
    private void importRelationships(String repositoryId, ZipFile zf,
            Map<String, String> oldIdToNewId, CallContext callContext, ImportResult result) {

        List<String> relEntries = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(RELATIONSHIPS_DIR) && name.endsWith(RELATIONSHIP_SUFFIX)) {
                relEntries.add(name);
            }
        }

        if (relEntries.isEmpty()) {
            return;
        }

        log.info("Found " + relEntries.size() + " relationship(s) to import");
        ContentService cs = getContentService();
        if (cs == null) {
            result.warnings.add("ContentService not available for relationship import");
            return;
        }

        int created = 0;
        int failed = 0;

        for (String entryName : relEntries) {
            try {
                byte[] jsonBytes = readZipEntry(zf, entryName);
                if (jsonBytes == null) {
                    result.warnings.add("Could not read relationship: " + entryName);
                    failed++;
                    continue;
                }

                JSONParser parser = new JSONParser();
                JSONObject relJson = (JSONObject) parser.parse(new String(jsonBytes, "UTF-8"));

                String objectType = (String) relJson.get("objectType");
                String sourceId = (String) relJson.get("sourceId");
                String targetId = (String) relJson.get("targetId");

                if (objectType == null || sourceId == null || targetId == null) {
                    result.warnings.add("Relationship missing required fields: " + entryName);
                    failed++;
                    continue;
                }

                String newSourceId = oldIdToNewId.getOrDefault(sourceId, sourceId);
                String newTargetId = oldIdToNewId.getOrDefault(targetId, targetId);

                PropertiesImpl props = new PropertiesImpl();
                props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, objectType));
                props.addProperty(new PropertyIdImpl(PropertyIds.SOURCE_ID, newSourceId));
                props.addProperty(new PropertyIdImpl(PropertyIds.TARGET_ID, newTargetId));

                String relName = (String) relJson.get("name");
                if (relName != null && !relName.isEmpty()) {
                    props.addProperty(new PropertyStringImpl(PropertyIds.NAME, relName));
                }

                JSONObject customProps = (JSONObject) relJson.get("properties");
                if (customProps != null) {
                    for (Object key : customProps.keySet()) {
                        String propName = (String) key;
                        Object propValue = customProps.get(propName);
                        if (propValue != null) {
                            addTypedProperty(props, propName, propValue, objectType, repositoryId);
                        }
                    }
                }

                Relationship newRel = cs.createRelationship(callContext, repositoryId, props, null, null, null, null);
                created++;

                JSONArray aclArray = (JSONArray) relJson.get("acl");
                if (aclArray != null && !aclArray.isEmpty() && newRel != null) {
                    applyCustomAcl(repositoryId, newRel.getId(), relJson, callContext, result);
                }

                log.info("Imported relationship: " + objectType + " (source=" + newSourceId + ", target=" + newTargetId + ")");

            } catch (Exception e) {
                log.error("Failed to import relationship from: " + entryName, e);
                result.warnings.add("Failed to import relationship: " + entryName + " - " + e.getMessage());
                failed++;
            }
        }

        result.relationshipsCreated = created;
        if (created > 0 || failed > 0) {
            log.info("Relationship import: " + created + " created, " + failed + " failed");
        }
    }

    // ========== Version History Import ==========

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

        versionPaths.sort((a, b) -> extractVersionNumber(a) - extractVersionNumber(b));
        return versionPaths;
    }

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
            for (int i = 1; i < versionPaths.size(); i++) {
                String versionPath = versionPaths.get(i);

                ContentStream versionStream = createContentStreamFromZip(zf, versionPath, fileName, mimeType);
                if (versionStream == null) {
                    result.warnings.add("Could not read version file: " + versionPath);
                    continue;
                }

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

                Document pwc = cs.checkOut(callContext, repositoryId, document.getId(), null);
                String pwcId = pwc.getId();

                Holder<String> objectIdHolder = new Holder<>(pwcId);
                Document checkedIn = cs.checkIn(callContext, repositoryId, objectIdHolder, isMajor,
                        null, versionStream, checkinComment, null, null, null, null);

                document = checkedIn;
            }

            ContentStream mainStream = createContentStreamFromZip(zf, basePath, fileName, mimeType);
            if (mainStream != null) {
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

                Document pwc = cs.checkOut(callContext, repositoryId, document.getId(), null);
                String pwcId = pwc.getId();

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
}
