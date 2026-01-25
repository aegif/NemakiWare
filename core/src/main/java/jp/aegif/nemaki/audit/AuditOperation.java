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
package jp.aegif.nemaki.audit;

/**
 * Enumeration of audit operations for CMIS and REST API operations.
 * Used to categorize and filter audit events.
 */
public enum AuditOperation {

    // Document operations
    CREATE_DOCUMENT("createDocument", "Document created"),
    UPDATE_DOCUMENT("updateDocument", "Document updated"),
    DELETE_DOCUMENT("deleteDocument", "Document deleted"),
    GET_DOCUMENT("getDocument", "Document retrieved"),

    // Content stream operations
    SET_CONTENT_STREAM("setContentStream", "Content stream set"),
    DELETE_CONTENT_STREAM("deleteContentStream", "Content stream deleted"),
    APPEND_CONTENT_STREAM("appendContentStream", "Content stream appended"),
    GET_CONTENT_STREAM("getContentStream", "Content stream retrieved"),

    // Versioning operations
    CHECK_OUT("checkOut", "Document checked out"),
    CHECK_IN("checkIn", "Document checked in"),
    CANCEL_CHECK_OUT("cancelCheckOut", "Check out cancelled"),
    GET_ALL_VERSIONS("getAllVersions", "All versions retrieved"),

    // Folder operations
    CREATE_FOLDER("createFolder", "Folder created"),
    DELETE_FOLDER("deleteFolder", "Folder deleted"),
    DELETE_TREE("deleteTree", "Folder tree deleted"),
    GET_CHILDREN("getChildren", "Folder children retrieved"),
    GET_DESCENDANTS("getDescendants", "Folder descendants retrieved"),
    GET_FOLDER_TREE("getFolderTree", "Folder tree retrieved"),

    // Object operations
    GET_OBJECT("getObject", "Object retrieved"),
    GET_OBJECT_BY_PATH("getObjectByPath", "Object retrieved by path"),
    UPDATE_PROPERTIES("updateProperties", "Properties updated"),
    MOVE_OBJECT("moveObject", "Object moved"),
    COPY_OBJECT("copyObject", "Object copied"),
    DELETE_OBJECT("deleteObject", "Object deleted"),

    // ACL operations
    GET_ACL("getAcl", "ACL retrieved"),
    APPLY_ACL("applyAcl", "ACL applied"),

    // Relationship operations
    CREATE_RELATIONSHIP("createRelationship", "Relationship created"),
    DELETE_RELATIONSHIP("deleteRelationship", "Relationship deleted"),
    GET_OBJECT_RELATIONSHIPS("getObjectRelationships", "Object relationships retrieved"),

    // Policy operations
    APPLY_POLICY("applyPolicy", "Policy applied"),
    REMOVE_POLICY("removePolicy", "Policy removed"),
    GET_APPLIED_POLICIES("getAppliedPolicies", "Applied policies retrieved"),

    // Query operations
    QUERY("query", "Query executed"),

    // Navigation operations
    GET_OBJECT_PARENTS("getObjectParents", "Object parents retrieved"),
    GET_FOLDER_PARENT("getFolderParent", "Folder parent retrieved"),
    GET_CHECKED_OUT_DOCS("getCheckedOutDocs", "Checked out documents retrieved"),

    // Discovery operations
    GET_CONTENT_CHANGES("getContentChanges", "Content changes retrieved"),

    // Authentication operations
    LOGIN("login", "User logged in"),
    LOGOUT("logout", "User logged out"),
    TOKEN_CREATE("tokenCreate", "Authentication token created"),
    TOKEN_VALIDATE("tokenValidate", "Authentication token validated"),

    // Type management operations
    CREATE_TYPE("createType", "Type created"),
    UPDATE_TYPE("updateType", "Type updated"),
    DELETE_TYPE("deleteType", "Type deleted"),
    GET_TYPE_DEFINITION("getTypeDefinition", "Type definition retrieved"),
    GET_TYPE_CHILDREN("getTypeChildren", "Type children retrieved"),
    GET_TYPE_DESCENDANTS("getTypeDescendants", "Type descendants retrieved"),

    // User management operations
    CREATE_USER("createUser", "User created"),
    UPDATE_USER("updateUser", "User updated"),
    DELETE_USER("deleteUser", "User deleted"),
    GET_USER("getUser", "User retrieved"),
    CHANGE_PASSWORD("changePassword", "Password changed"),

    // Group management operations
    CREATE_GROUP("createGroup", "Group created"),
    UPDATE_GROUP("updateGroup", "Group updated"),
    DELETE_GROUP("deleteGroup", "Group deleted"),
    GET_GROUP("getGroup", "Group retrieved"),
    ADD_GROUP_MEMBER("addGroupMember", "Member added to group"),
    REMOVE_GROUP_MEMBER("removeGroupMember", "Member removed from group"),

    // Repository operations
    GET_REPOSITORY_INFO("getRepositoryInfo", "Repository info retrieved"),

    // Archive operations
    ARCHIVE_RESTORE("archiveRestore", "Archive restored"),
    ARCHIVE_DELETE("archiveDelete", "Archive deleted"),

    // Rendition operations
    GET_RENDITIONS("getRenditions", "Renditions retrieved"),

    // Secondary type operations
    ADD_SECONDARY_TYPE("addSecondaryType", "Secondary type added"),
    REMOVE_SECONDARY_TYPE("removeSecondaryType", "Secondary type removed"),

    // Bulk operations
    BULK_UPDATE_PROPERTIES("bulkUpdateProperties", "Bulk properties updated"),

    // Search engine operations
    SOLR_REINDEX("solrReindex", "Solr reindex initiated"),
    SOLR_CLEAR("solrClear", "Solr index cleared"),

    // Unknown operation (fallback)
    UNKNOWN("unknown", "Unknown operation");

    private final String code;
    private final String description;

    AuditOperation(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Maps a method name to an AuditOperation.
     * @param methodName The method name from the service layer
     * @return The corresponding AuditOperation, or UNKNOWN if not mapped
     */
    public static AuditOperation fromMethodName(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return UNKNOWN;
        }

        // Direct method name mapping
        for (AuditOperation op : values()) {
            if (op.code.equalsIgnoreCase(methodName)) {
                return op;
            }
        }

        // Pattern-based mapping
        // IMPORTANT: More specific patterns MUST come before more general patterns
        // e.g., "getobjectbypath" before "getobject", "cancelcheckout" before "checkout"
        String lowerMethod = methodName.toLowerCase();

        // Versioning operations (more specific first)
        if (lowerMethod.contains("cancelcheckout")) return CANCEL_CHECK_OUT;
        if (lowerMethod.contains("checkout")) return CHECK_OUT;
        if (lowerMethod.contains("checkin")) return CHECK_IN;
        if (lowerMethod.contains("getallversions")) return GET_ALL_VERSIONS;

        // Content stream operations
        if (lowerMethod.contains("setcontentstream")) return SET_CONTENT_STREAM;
        if (lowerMethod.contains("deletecontentstream")) return DELETE_CONTENT_STREAM;
        if (lowerMethod.contains("appendcontentstream")) return APPEND_CONTENT_STREAM;
        if (lowerMethod.contains("getcontentstream")) return GET_CONTENT_STREAM;

        // Object operations (more specific first)
        if (lowerMethod.contains("getobjectbypath")) return GET_OBJECT_BY_PATH;
        if (lowerMethod.contains("getobjectparents")) return GET_OBJECT_PARENTS;
        if (lowerMethod.contains("getobjectrelationships")) return GET_OBJECT_RELATIONSHIPS;
        if (lowerMethod.contains("getobject")) return GET_OBJECT;
        if (lowerMethod.contains("moveobject")) return MOVE_OBJECT;
        if (lowerMethod.contains("copyobject")) return COPY_OBJECT;
        if (lowerMethod.contains("deleteobject")) return DELETE_OBJECT;
        if (lowerMethod.contains("updateproperties")) return UPDATE_PROPERTIES;
        if (lowerMethod.contains("bulkupdateproperties")) return BULK_UPDATE_PROPERTIES;

        // Folder operations (more specific first)
        if (lowerMethod.contains("getfoldertree")) return GET_FOLDER_TREE;
        if (lowerMethod.contains("getfolderparent")) return GET_FOLDER_PARENT;
        if (lowerMethod.contains("createfolder")) return CREATE_FOLDER;
        if (lowerMethod.contains("deletefolder")) return DELETE_FOLDER;
        if (lowerMethod.contains("deletetree")) return DELETE_TREE;
        if (lowerMethod.contains("getchildren")) return GET_CHILDREN;
        if (lowerMethod.contains("getdescendants")) return GET_DESCENDANTS;
        if (lowerMethod.contains("getcheckedoutdocs")) return GET_CHECKED_OUT_DOCS;

        // Document operations
        if (lowerMethod.contains("createdocument")) return CREATE_DOCUMENT;
        if (lowerMethod.contains("deletedocument")) return DELETE_DOCUMENT;

        // ACL operations
        if (lowerMethod.contains("applyacl")) return APPLY_ACL;
        if (lowerMethod.contains("getacl")) return GET_ACL;

        // Query operations
        if (lowerMethod.contains("query")) return QUERY;

        // Type operations (more specific first)
        if (lowerMethod.contains("gettypechildren")) return GET_TYPE_CHILDREN;
        if (lowerMethod.contains("gettypedescendants")) return GET_TYPE_DESCENDANTS;
        if (lowerMethod.contains("gettypedefinition")) return GET_TYPE_DEFINITION;
        if (lowerMethod.contains("createtype")) return CREATE_TYPE;
        if (lowerMethod.contains("updatetype")) return UPDATE_TYPE;
        if (lowerMethod.contains("deletetype")) return DELETE_TYPE;

        // Policy operations
        if (lowerMethod.contains("applypolicy")) return APPLY_POLICY;
        if (lowerMethod.contains("removepolicy")) return REMOVE_POLICY;
        if (lowerMethod.contains("getappliedpolicies")) return GET_APPLIED_POLICIES;

        // Relationship operations
        if (lowerMethod.contains("createrelationship")) return CREATE_RELATIONSHIP;
        if (lowerMethod.contains("deleterelationship")) return DELETE_RELATIONSHIP;

        // Rendition operations
        if (lowerMethod.contains("getrenditions")) return GET_RENDITIONS;

        // Discovery operations
        if (lowerMethod.contains("getcontentchanges")) return GET_CONTENT_CHANGES;

        // Secondary type operations
        if (lowerMethod.contains("addsecondarytype")) return ADD_SECONDARY_TYPE;
        if (lowerMethod.contains("removesecondarytype")) return REMOVE_SECONDARY_TYPE;

        // Repository operations
        if (lowerMethod.contains("getrepositoryinfo")) return GET_REPOSITORY_INFO;

        // Authentication operations
        if (lowerMethod.contains("login")) return LOGIN;
        if (lowerMethod.contains("logout")) return LOGOUT;

        // User operations (more specific first)
        if (lowerMethod.contains("changepassword")) return CHANGE_PASSWORD;
        if (lowerMethod.contains("createuser")) return CREATE_USER;
        if (lowerMethod.contains("updateuser")) return UPDATE_USER;
        if (lowerMethod.contains("deleteuser")) return DELETE_USER;
        if (lowerMethod.contains("getuser")) return GET_USER;

        // Group operations (more specific first)
        if (lowerMethod.contains("addgroupmember")) return ADD_GROUP_MEMBER;
        if (lowerMethod.contains("removegroupmember")) return REMOVE_GROUP_MEMBER;
        if (lowerMethod.contains("creategroup")) return CREATE_GROUP;
        if (lowerMethod.contains("updategroup")) return UPDATE_GROUP;
        if (lowerMethod.contains("deletegroup")) return DELETE_GROUP;
        if (lowerMethod.contains("getgroup")) return GET_GROUP;

        // Archive operations
        if (lowerMethod.contains("archiverestore")) return ARCHIVE_RESTORE;
        if (lowerMethod.contains("archivedelete")) return ARCHIVE_DELETE;

        // Solr operations
        if (lowerMethod.contains("reindex")) return SOLR_REINDEX;
        if (lowerMethod.contains("solrclear")) return SOLR_CLEAR;

        return UNKNOWN;
    }

    /**
     * Checks if this operation is a read-only operation.
     * @return true if the operation only reads data without modifying it
     */
    public boolean isReadOnly() {
        switch (this) {
            case GET_DOCUMENT:
            case GET_CONTENT_STREAM:
            case GET_ALL_VERSIONS:
            case GET_CHILDREN:
            case GET_DESCENDANTS:
            case GET_FOLDER_TREE:
            case GET_OBJECT:
            case GET_OBJECT_BY_PATH:
            case GET_ACL:
            case GET_OBJECT_RELATIONSHIPS:
            case GET_APPLIED_POLICIES:
            case QUERY:
            case GET_OBJECT_PARENTS:
            case GET_FOLDER_PARENT:
            case GET_CHECKED_OUT_DOCS:
            case GET_CONTENT_CHANGES:
            case GET_TYPE_DEFINITION:
            case GET_TYPE_CHILDREN:
            case GET_TYPE_DESCENDANTS:
            case GET_USER:
            case GET_GROUP:
            case GET_REPOSITORY_INFO:
            case GET_RENDITIONS:
            case TOKEN_VALIDATE:
                return true;
            default:
                return false;
        }
    }
}
