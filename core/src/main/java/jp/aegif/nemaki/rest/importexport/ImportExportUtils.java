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

    /**
     * Sanitize a filename for use in the legacy {@code filename="..."} parameter
     * of a Content-Disposition header. Strips CR/LF (response-splitting) and the
     * double-quote / backslash that could break out of the quoted-string and
     * inject additional parameters (e.g. a spoofed extension). Returns
     * {@code "download"} for null/empty input.
     */
    public static String sanitizeHeaderFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "download";
        }
        return fileName.replaceAll("[\"\\\\\\r\\n]", "_");
    }

    /**
     * Build a safe {@code Content-Disposition: attachment} header value for a
     * download. Emits both the sanitized legacy {@code filename="..."} (ASCII,
     * injection-safe) and an RFC 6266 / RFC 5987 {@code filename*=UTF-8''...}
     * parameter so non-ASCII names are preserved without allowing header or
     * parameter injection. Use this for every content/rendition/export download
     * instead of concatenating a raw filename.
     */
    public static String contentDispositionAttachment(String fileName) {
        String safeAscii = sanitizeHeaderFileName(fileName);
        String source = (fileName == null || fileName.isEmpty()) ? "download" : fileName;
        return "attachment; filename=\"" + safeAscii + "\"; filename*=UTF-8''"
                + encodeRfc5987(source);
    }

    /**
     * Percent-encode a string per RFC 5987 (for the {@code filename*} parameter).
     * Every byte that is not an RFC 5987 attr-char is percent-encoded, which makes
     * control characters and quotes inherently safe while preserving UTF-8 names.
     */
    private static String encodeRfc5987(String s) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final String attrChars = "!#$&+-.^_`|~";
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || attrChars.indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
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
     * Sanitize a CMIS object name into a single safe path/entry segment
     * for EXPORT. Object names are user-controllable and stored verbatim,
     * so a name like {@code ../../etc/x} or {@code a/b} must not be used
     * directly to build a filesystem path or ZIP entry — that would let an
     * export escape its target directory (path traversal) or emit
     * traversal entries for downstream extractors.
     *
     * <p>The result is always a single non-empty segment with no path
     * separators, no {@code ..}, no leading dots, no drive/colon, and no
     * control characters (incl. NUL). Empty/blank or fully-stripped names
     * fall back to a deterministic placeholder so the export still
     * produces a uniquely addressable entry.
     */
    public static String sanitizeExportName(String name) {
        if (name == null || name.isEmpty()) {
            return "_unnamed";
        }
        // Replace path separators and other unsafe characters with '_'.
        // Covers '/', '\\', ':' (Windows drive / ADS), and any ISO control
        // char (NUL, CR, LF, etc.).
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || Character.isISOControl(c)) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString();
        // Collapse any remaining ".." sequences so no traversal token
        // survives, then strip leading dots so we never produce "." / ".."
        // / hidden-by-accident segments that some extractors mishandle.
        cleaned = cleaned.replace("..", "_");
        int start = 0;
        while (start < cleaned.length() && cleaned.charAt(start) == '.') {
            start++;
        }
        cleaned = cleaned.substring(start);
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return "_unnamed";
        }
        return cleaned;
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

    /**
     * Gate for applying archive-supplied ACLs on import. Archive ACEs are
     * attacker-controlled data: the metadata/ACP inside an uploaded package can
     * name arbitrary principals and permissions. Restore them only for
     * administrators; a non-admin importer's objects keep their default /
     * inherited ACL. Without this gate, an importer holding only create-child
     * permission on the target folder could set arbitrary ACEs on imported
     * objects (the import path persists ACLs via {@code updateInternal}, which
     * bypasses the {@code CAN_APPLY_ACL_OBJECT} check enforced by the normal
     * ACL service). A non-admin who legitimately holds apply-ACL can still set
     * ACLs directly through the permission-checked ACL service after import.
     * Fail-closed: any resolution error returns false so the ACL is skipped
     * rather than applied unchecked.
     */
    public static boolean isAclApplyAllowed(String repositoryId,
            org.apache.chemistry.opencmis.commons.server.CallContext callContext) {
        try {
            if (callContext == null) {
                return false;
            }
            if (callContext instanceof jp.aegif.nemaki.cmis.factory.SystemCallContext) {
                return true;
            }
            ContentService cs = getContentService();
            if (cs == null) {
                return false;
            }
            jp.aegif.nemaki.model.UserItem user = cs.getUserItemById(repositoryId, callContext.getUsername());
            return user != null && Boolean.TRUE.equals(user.isAdmin());
        } catch (Exception e) {
            log.warn("ACL-apply admin check failed, skipping archive ACL: " + e.getMessage());
            return false;
        }
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
     * Fails closed on null/invalid inputs or permission-check errors.
     */
    public static boolean hasReadPermission(ContentService cs, String repositoryId,
                                            org.apache.chemistry.opencmis.commons.server.CallContext callContext,
                                            jp.aegif.nemaki.model.Content content) {
        if (cs == null || callContext == null || content == null) {
            return false;
        }
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
        if (cs == null || callContext == null || folder == null) {
            return false;
        }
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
        if (cs == null || callContext == null || content == null) {
            return false;
        }
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
                return user != null && Boolean.TRUE.equals(user.isAdmin());
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
