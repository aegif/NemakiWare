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
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.VersionSeries;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static jp.aegif.nemaki.rest.importexport.ImportExportUtils.*;

/**
 * Handles ZIP-based export operations (custom NemakiWare format).
 */
public class ZipExporter {

    private static final Log log = LogFactory.getLog(ZipExporter.class);

    // ========== Type Definition Export ==========

    @SuppressWarnings("unchecked")
    public void exportTypeDefinitions(String repositoryId, Set<String> customTypeIds,
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

    // ========== Folder Recursive Export ==========

    public void exportFolderRecursive(String repositoryId, Folder folder, String basePath,
            ZipOutputStream zos, CallContext callContext, Set<String> exportedObjectIds) throws Exception {
        exportFolderRecursive(repositoryId, folder, basePath, zos, callContext,
                exportedObjectIds, null);
    }

    /**
     * @param exportedObjects typed moved-content collector for lineage, or null. The requested
     *                        root (the {@code basePath.isEmpty()} call) is recorded in the
     *                        legacy id set but NOT here — it is the container, not moved
     *                        content; nested folders arrive with a non-empty basePath and are.
     */
    @SuppressWarnings("unchecked")
    public void exportFolderRecursive(String repositoryId, Folder folder, String basePath,
            ZipOutputStream zos, CallContext callContext, Set<String> exportedObjectIds,
            jp.aegif.nemaki.rest.importexport.ImportExportUtils.ExportedObjectCollector exportedObjects)
            throws Exception {

        ContentService cs = getContentService();
        List<Content> children = cs.getChildren(repositoryId, folder.getId());

        if (exportedObjectIds != null) {
            exportedObjectIds.add(folder.getId());
        }
        if (exportedObjects != null && !basePath.isEmpty()) {
            exportedObjects.record(folder.getId(), folder.getName(), true);
        }

        // Export folder metadata
        if (!basePath.isEmpty()) {
            JSONObject folderMeta = buildFolderMetadata(repositoryId, folder, callContext);
            String folderMetaPath = basePath + "/.meta.json";
            zos.putNextEntry(new ZipEntry(folderMetaPath));
            zos.write(folderMeta.toJSONString().getBytes("UTF-8"));
            zos.closeEntry();
        }

        for (Content child : children) {
            if (!hasReadPermission(cs, repositoryId, callContext, child)) {
                log.info("Export: skipping '" + child.getName() + "' (no read permission for user: " + callContext.getUsername() + ")");
                continue;
            }

            // Object names are user-controllable; sanitize each segment so
            // the ZIP cannot contain traversal entries ("../x") or path
            // separators that downstream extractors would mishandle. basePath
            // is already composed of sanitized segments.
            String safeChildName = sanitizeExportName(child.getName());
            String childPath = basePath.isEmpty() ? safeChildName : basePath + "/" + safeChildName;

            if (child instanceof Folder) {
                zos.putNextEntry(new ZipEntry(childPath + "/"));
                zos.closeEntry();

                exportFolderRecursive(repositoryId, (Folder) child, childPath, zos, callContext,
                        exportedObjectIds, exportedObjects);

            } else if (child instanceof Document) {
                Document doc = (Document) child;

                if (exportedObjectIds != null) {
                    exportedObjectIds.add(doc.getId());
                }
                if (exportedObjects != null) {
                    exportedObjects.record(doc.getId(), doc.getName(), false);
                }

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

                JSONObject metadata = buildDocumentMetadata(repositoryId, doc, callContext);
                String metaPath = childPath + META_SUFFIX;
                zos.putNextEntry(new ZipEntry(metaPath));
                zos.write(metadata.toJSONString().getBytes("UTF-8"));
                zos.closeEntry();

                exportVersionHistory(repositoryId, doc, childPath, zos, callContext);
            }
        }
    }

    /**
     * Export a single document (content + metadata + version history) into the ZIP stream.
     */
    public void exportSingleDocument(String repositoryId, Document doc, String path,
            ZipOutputStream zos, CallContext callContext, ContentService cs) throws Exception {
        if (doc.getAttachmentNodeId() != null) {
            try {
                var attachment = cs.getAttachment(repositoryId, doc.getAttachmentNodeId());
                if (attachment != null && attachment.getInputStream() != null) {
                    zos.putNextEntry(new ZipEntry(path));
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
                log.warn("Failed to export content for: " + path, e);
            }
        }

        JSONObject metadata = buildDocumentMetadata(repositoryId, doc, callContext);
        String metaPath = path + META_SUFFIX;
        zos.putNextEntry(new ZipEntry(metaPath));
        zos.write(metadata.toJSONString().getBytes("UTF-8"));
        zos.closeEntry();

        exportVersionHistory(repositoryId, doc, path, zos, callContext);
    }

    // ========== Document/Folder Metadata ==========

    @SuppressWarnings("unchecked")
    public JSONObject buildDocumentMetadata(String repositoryId, Document doc, CallContext callContext) {
        JSONObject metadata = new JSONObject();

        JSONObject properties = new JSONObject();
        properties.put(PropertyIds.OBJECT_ID, doc.getId());
        properties.put(PropertyIds.NAME, doc.getName());
        properties.put(PropertyIds.OBJECT_TYPE_ID, doc.getObjectType());
        if (doc.getDescription() != null) {
            properties.put(PropertyIds.DESCRIPTION, doc.getDescription());
        }
        if (doc.getSubTypeProperties() != null) {
            for (jp.aegif.nemaki.model.Property prop : doc.getSubTypeProperties()) {
                if (prop.getValue() != null) {
                    properties.put(prop.getKey(), serializePropertyValue(prop.getValue()));
                }
            }
        }
        metadata.put("properties", properties);

        ContentService cs = getContentService();
        if (hasAclPermission(cs, repositoryId, callContext, doc)) {
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
        }

        JSONObject versionInfo = new JSONObject();
        versionInfo.put("versionLabel", doc.getVersionLabel());
        versionInfo.put("versionSeriesId", doc.getVersionSeriesId());
        versionInfo.put("isLatestVersion", doc.isLatestVersion());
        versionInfo.put("isMajorVersion", doc.isMajorVersion());
        if (doc.getCheckinComment() != null) {
            versionInfo.put("checkinComment", doc.getCheckinComment());
        }
        metadata.put("versionInfo", versionInfo);

        return metadata;
    }

    @SuppressWarnings("unchecked")
    public JSONObject buildFolderMetadata(String repositoryId, Folder folder, CallContext callContext) {
        JSONObject metadata = new JSONObject();

        JSONObject properties = new JSONObject();
        properties.put(PropertyIds.OBJECT_ID, folder.getId());
        properties.put(PropertyIds.OBJECT_TYPE_ID, folder.getObjectType() != null ? folder.getObjectType() : "cmis:folder");
        properties.put(PropertyIds.NAME, folder.getName());
        metadata.put("properties", properties);

        ContentService cs = getContentService();
        if (hasAclPermission(cs, repositoryId, callContext, folder)) {
            if (folder.getAcl() != null && folder.getAcl().getLocalAces() != null) {
                JSONArray aclArray = new JSONArray();
                for (Ace ace : folder.getAcl().getLocalAces()) {
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
        }

        return metadata;
    }

    // ========== Version History Export ==========

    @SuppressWarnings("unchecked")
    public void exportVersionHistory(String repositoryId, Document doc, String basePath,
            ZipOutputStream zos, CallContext callContext) {

        try {
            ContentService cs = getContentService();
            VersionSeries vs = cs.getVersionSeries(repositoryId, doc);
            if (vs == null) {
                return;
            }

            List<Document> allVersions = cs.getAllVersions(callContext, repositoryId, vs.getId());
            if (allVersions == null || allVersions.size() <= 1) {
                return;
            }

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
                if (version.isLatestVersion()) {
                    continue;
                }

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

    // ========== Relationship Export ==========

    @SuppressWarnings("unchecked")
    /**
     * Collect and export relationships where both endpoints are in exportedObjectIds.
     * @return Set of custom (non-base) relationship type IDs that were exported
     */
    public Set<String> collectAndExportRelationships(String repositoryId, Set<String> exportedObjectIds,
            ZipOutputStream zos, CallContext callContext) throws Exception {

        Set<String> relationshipTypeIds = new HashSet<>();
        if (exportedObjectIds == null || exportedObjectIds.isEmpty()) {
            return relationshipTypeIds;
        }

        ContentService cs = getContentService();
        if (cs == null) {
            return relationshipTypeIds;
        }

        Map<String, Relationship> uniqueRelationships = new HashMap<>();

        for (String objectId : exportedObjectIds) {
            try {
                List<Relationship> rels = cs.getRelationsipsOfObject(repositoryId, objectId,
                        org.apache.chemistry.opencmis.commons.enums.RelationshipDirection.EITHER);
                if (rels != null) {
                    for (Relationship rel : rels) {
                        if (exportedObjectIds.contains(rel.getSourceId()) && exportedObjectIds.contains(rel.getTargetId())) {
                            uniqueRelationships.put(rel.getId(), rel);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to get relationships for object " + objectId + ": " + e.getMessage());
            }
        }

        if (uniqueRelationships.isEmpty()) {
            return relationshipTypeIds;
        }

        zos.putNextEntry(new ZipEntry(RELATIONSHIPS_DIR));
        zos.closeEntry();

        int count = 0;
        int skipped = 0;
        for (Map.Entry<String, Relationship> entry : uniqueRelationships.entrySet()) {
            Relationship rel = entry.getValue();

            if (callContext != null && !hasReadPermission(cs, repositoryId, callContext, rel)) {
                skipped++;
                log.debug("Export: skipping relationship " + rel.getId() + " (no read permission)");
                continue;
            }

            JSONObject relJson = new JSONObject();
            relJson.put("objectType", rel.getObjectType());
            relJson.put("sourceId", rel.getSourceId());
            relJson.put("targetId", rel.getTargetId());
            relJson.put("name", rel.getName());

            if (rel.getSubTypeProperties() != null) {
                JSONObject propsJson = new JSONObject();
                for (jp.aegif.nemaki.model.Property prop : rel.getSubTypeProperties()) {
                    if (prop.getValue() != null) {
                        propsJson.put(prop.getKey(), serializePropertyValue(prop.getValue()));
                    }
                }
                if (!propsJson.isEmpty()) {
                    relJson.put("properties", propsJson);
                }
            }

            if (hasAclPermission(cs, repositoryId, callContext, rel)) {
                if (rel.getAcl() != null && rel.getAcl().getLocalAces() != null) {
                    JSONArray aclArray = new JSONArray();
                    for (Ace ace : rel.getAcl().getLocalAces()) {
                        JSONObject aceJson = new JSONObject();
                        aceJson.put("principalId", ace.getPrincipalId());
                        JSONArray permsArray = new JSONArray();
                        if (ace.getPermissions() != null) {
                            permsArray.addAll(ace.getPermissions());
                        }
                        aceJson.put("permissions", permsArray);
                        aclArray.add(aceJson);
                    }
                    relJson.put("acl", aclArray);
                }
            }

            String entryName = RELATIONSHIPS_DIR + entry.getKey() + RELATIONSHIP_SUFFIX;
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(relJson.toJSONString().getBytes("UTF-8"));
            zos.closeEntry();
            count++;

            // Collect custom relationship type IDs for type definition export
            String relType = rel.getObjectType();
            if (relType != null && !BASE_TYPE_IDS.contains(relType)) {
                relationshipTypeIds.add(relType);
            }
        }

        if (skipped > 0) {
            log.info("Exported " + count + " relationship(s), skipped " + skipped + " (no read permission)");
        } else {
            log.info("Exported " + count + " relationship(s)");
        }
        return relationshipTypeIds;
    }

    /**
     * 多値プロパティ（List）をJSONArrayとして保持し、単値はそのままtoString()する。
     * これによりインポート時に型情報を失わずに復元できる。
     */
    @SuppressWarnings("unchecked")
    private static Object serializePropertyValue(Object value) {
        if (value instanceof java.util.List) {
            JSONArray arr = new JSONArray();
            for (Object item : (java.util.List<?>) value) {
                arr.add(item != null ? item.toString() : null);
            }
            return arr;
        }
        return value.toString();
    }
}
