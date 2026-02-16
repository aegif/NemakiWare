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
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared constants and utility methods for Import/Export operations.
 */
public final class ImportExportUtils {

    private static final Log log = LogFactory.getLog(ImportExportUtils.class);

    // Custom format naming conventions
    public static final String META_SUFFIX = ".meta.json";
    public static final String VERSION_PREFIX = ".v";
    public static final String TYPE_DEFINITIONS_DIR = ".nemaki-types/";
    public static final String TYPE_DEFINITION_SUFFIX = ".type.json";
    public static final String RELATIONSHIPS_DIR = ".nemaki-relationships/";
    public static final String RELATIONSHIP_SUFFIX = ".rel.json";

    // Base type IDs that should not be exported
    public static final Set<String> BASE_TYPE_IDS = new HashSet<>();
    static {
        BASE_TYPE_IDS.add("cmis:document");
        BASE_TYPE_IDS.add("cmis:folder");
        BASE_TYPE_IDS.add("cmis:relationship");
        BASE_TYPE_IDS.add("cmis:policy");
        BASE_TYPE_IDS.add("cmis:item");
        BASE_TYPE_IDS.add("cmis:secondary");
    }

    // Size limits for import (prevent OOM). For ZIPs larger than 2GB, use filesystem import.
    public static final long MAX_UPLOAD_SIZE = 2L * 1024 * 1024 * 1024; // 2GB max upload
    public static final long MAX_SINGLE_FILE_SIZE = 2L * 1024 * 1024 * 1024; // 2GB max per file in ZIP
    public static final long MAX_METADATA_SIZE = 10L * 1024 * 1024; // 10MB max for JSON metadata files

    // Allowed filesystem root paths for import/export (sandbox protection)
    // Configure via system property: nemakiware.filesystem.allowed.roots
    // Default: /tmp/nemakiware-import, /tmp/nemakiware-export
    public static final List<String> ALLOWED_FILESYSTEM_ROOTS;
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

    private ImportExportUtils() {
        // Utility class
    }

    public static String guessMimeType(String fileName) {
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

    /**
     * Validate ZIP entry name to prevent path traversal attacks.
     * Enhanced validation for Windows paths and normalized path checking.
     */
    public static boolean isValidZipEntryName(String name) {
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
        // Reject Windows-style paths with backslash or drive letters
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

    /**
     * Check if a path is within one of the allowed filesystem roots (sandbox protection).
     * This prevents access to arbitrary filesystem locations.
     */
    public static boolean isPathWithinAllowedRoots(java.nio.file.Path path) {
        String pathStr = path.toAbsolutePath().normalize().toString();
        for (String allowedRoot : ALLOWED_FILESYSTEM_ROOTS) {
            java.nio.file.Path rootPath = Paths.get(allowedRoot).toAbsolutePath().normalize();
            String rootStr = rootPath.toString();
            // Check if path starts with the allowed root
            if (pathStr.startsWith(rootStr)) {
                // Make sure it's actually within the directory, not just a prefix match
                if (pathStr.length() == rootStr.length() ||
                    pathStr.charAt(rootStr.length()) == File.separatorChar) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String getParentPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash > 0 ? path.substring(0, lastSlash) : "";
    }

    public static String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    public static boolean isVersionFile(String path) {
        String fileName = getFileName(path);
        // Match version files like "file.txt.v1" but not "file.txt.v1.meta.json"
        return fileName.matches(".*\\.v\\d+$");
    }

    public static boolean isVersionFileFor(String path, String baseFileName) {
        String fileName = getFileName(path);
        // Only match actual version content files like "file.txt.v1", not "file.txt.v1.meta.json"
        return fileName.matches(baseFileName + "\\.v\\d+$");
    }

    public static int extractVersionNumber(String path) {
        String fileName = getFileName(path);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\.v(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(fileName);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    /**
     * Add a property to PropertiesImpl with the correct CMIS type based on the type definition.
     * Handles both single-value and multi-value (List/JSONArray) properties.
     * Falls back to PropertyStringImpl if the type definition is not available or the property is unknown.
     */
    @SuppressWarnings("unchecked")
    public static void addTypedProperty(PropertiesImpl props, String propName, Object propValue,
                                        String typeId, String repositoryId) {
        if (propValue == null) {
            return;
        }

        // Resolve property definition for type and cardinality info
        PropertyDefinition<?> propDef = null;
        PropertyType propertyType = null;
        Cardinality cardinality = Cardinality.SINGLE;
        try {
            TypeManager tm = getTypeManager();
            if (tm != null && typeId != null) {
                TypeDefinition typeDef = tm.getTypeDefinition(repositoryId, typeId);
                if (typeDef != null && typeDef.getPropertyDefinitions() != null) {
                    propDef = typeDef.getPropertyDefinitions().get(propName);
                    if (propDef != null) {
                        propertyType = propDef.getPropertyType();
                        if (propDef.getCardinality() != null) {
                            cardinality = propDef.getCardinality();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve property type for " + propName + " on type " + typeId + ": " + e.getMessage());
        }

        // Determine if value is multi-valued (JSONArray or List)
        boolean isMultiValue = (cardinality == Cardinality.MULTI)
                || (propValue instanceof java.util.List)
                || (propValue instanceof org.json.simple.JSONArray);

        if (isMultiValue) {
            // Convert to list of string values first
            java.util.List<String> strValues = new java.util.ArrayList<>();
            if (propValue instanceof java.util.List) {
                for (Object item : (java.util.List<?>) propValue) {
                    strValues.add(item != null ? item.toString() : null);
                }
            } else {
                // Single value but cardinality is MULTI
                strValues.add(propValue.toString());
            }

            if (propertyType == null) {
                props.addProperty(new PropertyStringImpl(propName, strValues));
                return;
            }

            try {
                switch (propertyType) {
                    case BOOLEAN:
                        java.util.List<Boolean> boolValues = new java.util.ArrayList<>();
                        for (String s : strValues) { boolValues.add(s != null ? Boolean.valueOf(s) : null); }
                        props.addProperty(new PropertyBooleanImpl(propName, boolValues));
                        break;
                    case INTEGER:
                        java.util.List<java.math.BigInteger> intValues = new java.util.ArrayList<>();
                        for (String s : strValues) { intValues.add(s != null ? new java.math.BigInteger(s) : null); }
                        props.addProperty(new PropertyIntegerImpl(propName, intValues));
                        break;
                    case DECIMAL:
                        java.util.List<java.math.BigDecimal> decValues = new java.util.ArrayList<>();
                        for (String s : strValues) { decValues.add(s != null ? new java.math.BigDecimal(s) : null); }
                        props.addProperty(new PropertyDecimalImpl(propName, decValues));
                        break;
                    case DATETIME:
                        java.util.List<java.util.GregorianCalendar> dateValues = new java.util.ArrayList<>();
                        for (String s : strValues) {
                            if (s != null) {
                                java.util.GregorianCalendar cal = new java.util.GregorianCalendar();
                                cal.setTimeInMillis(Long.parseLong(s));
                                dateValues.add(cal);
                            } else {
                                dateValues.add(null);
                            }
                        }
                        props.addProperty(new PropertyDateTimeImpl(propName, dateValues));
                        break;
                    case ID:
                        props.addProperty(new PropertyIdImpl(propName, strValues));
                        break;
                    default:
                        props.addProperty(new PropertyStringImpl(propName, strValues));
                        break;
                }
            } catch (Exception e) {
                log.debug("Type conversion failed for multi-value property " + propName + " (type=" + propertyType + "), falling back to string list: " + e.getMessage());
                props.addProperty(new PropertyStringImpl(propName, strValues));
            }
        } else {
            // Single value
            String strValue = propValue.toString();

            if (propertyType == null) {
                props.addProperty(new PropertyStringImpl(propName, strValue));
                return;
            }

            try {
                switch (propertyType) {
                    case BOOLEAN:
                        props.addProperty(new PropertyBooleanImpl(propName, Boolean.valueOf(strValue)));
                        break;
                    case INTEGER:
                        props.addProperty(new PropertyIntegerImpl(propName, new java.math.BigInteger(strValue)));
                        break;
                    case DECIMAL:
                        props.addProperty(new PropertyDecimalImpl(propName, new java.math.BigDecimal(strValue)));
                        break;
                    case DATETIME:
                        java.util.GregorianCalendar cal = new java.util.GregorianCalendar();
                        cal.setTimeInMillis(Long.parseLong(strValue));
                        props.addProperty(new PropertyDateTimeImpl(propName, cal));
                        break;
                    case ID:
                        props.addProperty(new PropertyIdImpl(propName, strValue));
                        break;
                    default:
                        props.addProperty(new PropertyStringImpl(propName, strValue));
                        break;
                }
            } catch (Exception e) {
                log.debug("Type conversion failed for property " + propName + " (type=" + propertyType + "), falling back to string: " + e.getMessage());
                props.addProperty(new PropertyStringImpl(propName, strValue));
            }
        }
    }

    /**
     * Recursively collect custom (non-base) type IDs from documents in a folder tree.
     */
    public static void collectCustomTypeIds(String repositoryId, jp.aegif.nemaki.model.Folder folder,
                                            Set<String> customTypeIds) {
        ContentService cs = getContentService();
        if (cs == null) return;

        List<jp.aegif.nemaki.model.Content> children = cs.getChildren(repositoryId, folder.getId());
        for (jp.aegif.nemaki.model.Content child : children) {
            if (child instanceof jp.aegif.nemaki.model.Folder) {
                collectCustomTypeIds(repositoryId, (jp.aegif.nemaki.model.Folder) child, customTypeIds);
            } else if (child instanceof jp.aegif.nemaki.model.Document) {
                String objectType = ((jp.aegif.nemaki.model.Document) child).getObjectType();
                if (objectType != null && !BASE_TYPE_IDS.contains(objectType)) {
                    customTypeIds.add(objectType);
                }
            }
        }
    }

    // ========== Spring Bean Access ==========

    public static ContentService getContentService() {
        try {
            ContentService service = SpringContext.getApplicationContext()
                    .getBean("ContentService", ContentService.class);
            if (service != null) {
                return service;
            }
        } catch (Exception e) {
            log.debug("Failed to get ContentService: " + e.getMessage());
        }
        try {
            ContentService service = SpringContext.getApplicationContext()
                    .getBean("contentService", ContentService.class);
            if (service != null) {
                return service;
            }
        } catch (Exception e) {
            log.debug("Could not find contentService with lowercase name: " + e.getMessage());
        }
        log.error("ContentService is null and SpringContext fallback failed");
        return null;
    }

    public static TypeService getTypeService() {
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

    public static TypeManager getTypeManager() {
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

    public static jp.aegif.nemaki.cmis.aspect.PermissionService getPermissionService() {
        try {
            return SpringContext.getApplicationContext()
                    .getBean("PermissionService", jp.aegif.nemaki.cmis.aspect.PermissionService.class);
        } catch (Exception e) {
            log.debug("Failed to get PermissionService: " + e.getMessage());
            return null;
        }
    }

    /**
     * Check whether the current user (from CallContext) has read permission on the given content.
     * Returns true if permission check is unavailable (fail-open for robustness).
     */
    public static boolean hasReadPermission(ContentService cs, String repositoryId,
                                            org.apache.chemistry.opencmis.commons.server.CallContext callContext,
                                            jp.aegif.nemaki.model.Content content) {
        try {
            jp.aegif.nemaki.cmis.aspect.PermissionService permService = getPermissionService();
            if (permService == null) {
                // PermissionService unavailable - fall back to admin-only
                return isAdminUser(callContext.getUsername(), repositoryId);
            }
            jp.aegif.nemaki.model.Acl acl = cs.calculateAcl(repositoryId, content);
            Boolean result = permService.checkPermission(
                callContext, repositoryId,
                org.apache.chemistry.opencmis.commons.data.PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
                acl, content.getType(), content);
            return result != null && result;
        } catch (Exception e) {
            log.debug("Permission check failed for " + content.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Check whether the current user (from CallContext) has permission to create children
     * in the given folder. Used for import permission checks.
     * Returns true if the user can create documents in the folder.
     */
    public static boolean hasCreateChildrenPermission(ContentService cs, String repositoryId,
                                            org.apache.chemistry.opencmis.commons.server.CallContext callContext,
                                            jp.aegif.nemaki.model.Content folder) {
        try {
            jp.aegif.nemaki.cmis.aspect.PermissionService permService = getPermissionService();
            if (permService == null) {
                return isAdminUser(callContext.getUsername(), repositoryId);
            }
            jp.aegif.nemaki.model.Acl acl = cs.calculateAcl(repositoryId, folder);
            Boolean result = permService.checkPermission(
                callContext, repositoryId,
                org.apache.chemistry.opencmis.commons.data.PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER,
                acl, folder.getType(), folder);
            return result != null && result;
        } catch (Exception e) {
            log.debug("Permission check failed for " + folder.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Check whether the current user has ACL read permission (CAN_GET_ACL) on the given content.
     * Returns false if permission check fails or is unavailable (fail-closed for security).
     */
    public static boolean hasAclPermission(ContentService cs, String repositoryId,
                                           org.apache.chemistry.opencmis.commons.server.CallContext callContext,
                                           jp.aegif.nemaki.model.Content content) {
        try {
            jp.aegif.nemaki.cmis.aspect.PermissionService permService = getPermissionService();
            if (permService == null) {
                return isAdminUser(callContext.getUsername(), repositoryId);
            }
            jp.aegif.nemaki.model.Acl acl = cs.calculateAcl(repositoryId, content);
            Boolean result = permService.checkPermission(
                callContext, repositoryId,
                org.apache.chemistry.opencmis.commons.data.PermissionMapping.CAN_GET_ACL_OBJECT,
                acl, content.getType(), content);
            return result != null && result;
        } catch (Exception e) {
            log.debug("ACL permission check failed for " + content.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if user is admin (fallback for when PermissionService is unavailable).
     */
    public static boolean isAdminUser(String username, String repositoryId) {
        try {
            ContentService cs = getContentService();
            if (cs != null) {
                jp.aegif.nemaki.model.UserItem user = cs.getUserItemById(repositoryId, username);
                return user != null && user.isAdmin();
            }
        } catch (Exception e) {
            log.debug("Admin check failed: " + e.getMessage());
        }
        return false;
    }

    // ========== Result Classes ==========

    public static class ImportResult {
        public int foldersCreated = 0;
        public int documentsCreated = 0;
        public int relationshipsCreated = 0;
        public List<String> errors = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    public static class ExportResult {
        public int foldersExported = 0;
        public int documentsExported = 0;
        public List<String> errors = new ArrayList<>();
    }
}
