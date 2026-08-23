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
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;

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
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static jp.aegif.nemaki.rest.importexport.ImportExportUtils.*;

public class FilesystemImporter {

    private static final Log log = LogFactory.getLog(FilesystemImporter.class);

    private ContentService contentService;

    public FilesystemImporter() {
    }

    public FilesystemImporter(ContentService contentService) {
        this.contentService = contentService;
    }

    private ContentService cs() {
        if (contentService != null) {
            return contentService;
        }
        return getContentService();
    }

    public ImportResult importFromFilesystemDirectory(String repositoryId, String targetFolderId,
            java.nio.file.Path sourceDir, CallContext callContext) throws Exception {

        ImportResult result = new ImportResult();
        ContentService cs = cs();

        // Collect files and metadata
        List<java.nio.file.Path> files = new ArrayList<>();
        Map<String, JSONObject> metadataMap = new HashMap<>();

        try (Stream<java.nio.file.Path> walkStream = Files.walk(sourceDir)) {
            walkStream.forEach(path -> {
                if (Files.isRegularFile(path)) {
                    String relativePath = sourceDir.relativize(path).toString().replace("\\", "/");
                    files.add(path);

                    if (relativePath.endsWith(META_SUFFIX)) {
                        try {
                            long metaSize = Files.size(path);
                            if (metaSize > MAX_METADATA_SIZE) {
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

            if (relativePath.endsWith(META_SUFFIX) || isVersionFile(relativePath)) {
                continue;
            }

            try {
                String parentPath = getParentPath(relativePath);
                String parentFolderId = ensureFolderPath(repositoryId, parentPath, targetFolderId,
                        pathToFolderId, callContext, result);

                String fileName = getFileName(relativePath);

                JSONObject metadata = metadataMap.get(relativePath);

                long fileSize = Files.size(filePath);
                if (fileSize > MAX_SINGLE_FILE_SIZE) {
                    result.warnings.add("Skipping large file: " + relativePath);
                    continue;
                }

                String mimeType = guessMimeType(fileName);

                // Determine object type from metadata (preserve custom type)
                String objectTypeId = "cmis:document";
                if (metadata != null) {
                    JSONObject properties = (JSONObject) metadata.get("properties");
                    if (properties != null) {
                        Object typeObj = properties.get(PropertyIds.OBJECT_TYPE_ID);
                        if (typeObj != null) {
                            String metaType = typeObj.toString();
                            if (!metaType.isEmpty()) {
                                objectTypeId = metaType;
                            }
                        }
                    }
                }

                PropertiesImpl props = new PropertiesImpl();
                props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, objectTypeId));
                props.addProperty(new PropertyStringImpl(PropertyIds.NAME, fileName));

                if (metadata != null) {
                    // Same rule as the zip route (ZipImporter.stripEvidenceAssertions): an
                    // import writes no capture ledger rows, so it must not write capture
                    // assertions — admin-only and path-allowlisted here, but the reasoning
                    // does not depend on who is importing (review F-9).
                    List<String> droppedEvidence = ZipImporter.stripEvidenceAssertions(metadata);
                    if (!droppedEvidence.isEmpty()) {
                        String message = "Evidence metadata not imported for '" + relativePath
                                + "': " + String.join(", ", droppedEvidence)
                                + " — this repository did not capture the object, so the import"
                                + " will not assert that it did. The content itself is imported.";
                        result.warnings.add(message);
                        log.info(message);
                    }
                    applyCustomProperties(repositoryId, metadata, props);
                }

                // Stream file content directly without loading into heap (OOM prevention)
                try (InputStream fileStream = Files.newInputStream(filePath)) {
                    ContentStream contentStream = new ContentStreamImpl(fileName,
                            BigInteger.valueOf(fileSize), mimeType, fileStream);

                    Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
                    Document newDoc = cs.createDocument(callContext, repositoryId, props, parentFolder,
                            contentStream, VersioningState.MAJOR, null, null, null);

                    result.documentsCreated++;
                    result.recordCreated(newDoc.getId(), fileName, false, parentFolderId);

                    if (metadata != null) {
                        applyCustomAcl(repositoryId, newDoc.getId(), metadata, callContext, result);
                    }

                    importVersionHistoryFromFilesystem(repositoryId, relativePath, sourceDir,
                            newDoc, callContext, result);
                }

            } catch (Exception e) {
                log.error("Failed to import file: " + relativePath + " - " + e.getMessage(), e);
                result.errors.add("Failed to import: " + relativePath + " - " + e.getMessage());
            }
        }

        return result;
    }

    private String ensureFolderPath(String repositoryId, String path, String rootFolderId,
            Map<String, String> pathToFolderId, CallContext callContext, ImportResult result) throws Exception {

        if (path.isEmpty()) {
            return rootFolderId;
        }

        if (pathToFolderId.containsKey(path)) {
            return pathToFolderId.get(path);
        }

        ContentService cs = cs();

        String parentPath = getParentPath(path);
        String parentFolderId = ensureFolderPath(repositoryId, parentPath, rootFolderId,
                pathToFolderId, callContext, result);

        String folderName = getFileName(path);

        PropertiesImpl props = new PropertiesImpl();
        props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:folder"));
        props.addProperty(new PropertyStringImpl(PropertyIds.NAME, folderName));

        Folder parentFolder = cs.getFolder(repositoryId, parentFolderId);
        Folder newFolder = cs.createFolder(callContext, repositoryId, props, parentFolder,
                null, null, null, null);

        pathToFolderId.put(path, newFolder.getId());
        result.foldersCreated++;
        result.recordCreated(newFolder.getId(), folderName, true, parentFolderId);

        return newFolder.getId();
    }

    @SuppressWarnings("unchecked")
    private void applyCustomProperties(String repositoryId, JSONObject metadata, PropertiesImpl props) {
        JSONObject properties = (JSONObject) metadata.get("properties");
        if (properties == null) {
            return;
        }

        // Get typeId for typed property reconstruction
        String typeId = null;
        Object typeObj = properties.get(PropertyIds.OBJECT_TYPE_ID);
        if (typeObj != null) {
            typeId = typeObj.toString();
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

        org.json.simple.JSONArray aclArray = (org.json.simple.JSONArray) metadata.get("acl");
        if (aclArray == null || aclArray.isEmpty()) {
            return;
        }

        try {
            ContentService cs = cs();
            jp.aegif.nemaki.model.Content content = cs.getContent(repositoryId, objectId);
            if (content == null) {
                return;
            }

            List<jp.aegif.nemaki.model.Ace> aces = new ArrayList<>();

            for (Object item : aclArray) {
                JSONObject aceJson = (JSONObject) item;
                String principalId = (String) aceJson.get("principalId");
                org.json.simple.JSONArray permissionsArray = (org.json.simple.JSONArray) aceJson.get("permissions");

                if (principalId != null && permissionsArray != null) {
                    jp.aegif.nemaki.model.Ace ace = new jp.aegif.nemaki.model.Ace();
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
                if (!isAclApplyAllowed(repositoryId, callContext)) {
                    log.warn("Skipping archive ACL for object " + objectId
                            + ": importer lacks apply-ACL (cmis:canApplyACL) permission");
                    result.warnings.add("ACL not applied for object " + objectId
                            + " (importer lacks apply-ACL permission); default/inherited ACL retained");
                    return;
                }
                jp.aegif.nemaki.model.Acl acl = content.getAcl();
                if (acl == null) {
                    acl = new jp.aegif.nemaki.model.Acl();
                }
                acl.setLocalAces(aces);
                content.setAcl(acl);
                cs.updateInternal(repositoryId, content);
                // This path writes an ACL without going through AclService, so nothing else
                // advances the cache generation — and other replicas would keep serving the
                // pre-import permissions until their entries expired. Bumping here rather than
                // in the DAO keeps ordinary content updates from clearing every replica.
                jp.aegif.nemaki.util.cache.AclCacheGeneration.advance(repositoryId);
            }

        } catch (Exception e) {
            log.warn("Failed to apply ACL: " + e.getMessage());
            result.warnings.add("Failed to apply ACL for object " + objectId + ": " + e.getMessage());
        }
    }

    private void importVersionHistoryFromFilesystem(String repositoryId, String basePath, java.nio.file.Path sourceDir,
            Document doc, CallContext callContext, ImportResult result) {

        try {
            List<java.nio.file.Path> versionFiles = new ArrayList<>();
            String baseFileName = getFileName(basePath);
            String parentPath = getParentPath(basePath);
            java.nio.file.Path parentDir = parentPath.isEmpty() ? sourceDir : sourceDir.resolve(parentPath);

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

            versionFiles.sort((a, b) -> {
                int vA = extractVersionNumber(a.getFileName().toString());
                int vB = extractVersionNumber(b.getFileName().toString());
                return vA - vB;
            });

            result.warnings.add("Version history found for " + basePath + " (" + versionFiles.size() +
                    " versions) - version import requires check-out/check-in cycles (not implemented)");

        } catch (Exception e) {
            log.warn("Failed to import version history for: " + basePath, e);
            result.warnings.add("Failed to import version history for: " + basePath);
        }
    }
}
