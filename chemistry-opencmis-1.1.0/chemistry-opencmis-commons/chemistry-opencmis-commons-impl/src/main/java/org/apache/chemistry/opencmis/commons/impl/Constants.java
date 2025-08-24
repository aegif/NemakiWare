/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.commons.impl;

/**
 * Constants for CMIS server and client.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class Constants {

    // media types
    public static final String MEDIATYPE_SERVICE = "application/atomsvc+xml";
    public static final String MEDIATYPE_FEED = "application/atom+xml;type=feed";
    public static final String MEDIATYPE_ENTRY = "application/atom+xml;type=entry";
    public static final String MEDIATYPE_CHILDREN = MEDIATYPE_FEED;
    public static final String MEDIATYPE_DESCENDANTS = "application/cmistree+xml";
    public static final String MEDIATYPE_QUERY = "application/cmisquery+xml";
    public static final String MEDIATYPE_ALLOWABLEACTION = "application/cmisallowableactions+xml";
    public static final String MEDIATYPE_ACL = "application/cmisacl+xml";
    public static final String MEDIATYPE_CMISATOM = "application/cmisatom+xml";
    public static final String MEDIATYPE_OCTETSTREAM = "application/octet-stream";

    // collections
    public static final String COLLECTION_ROOT = "root";
    public static final String COLLECTION_TYPES = "types";
    public static final String COLLECTION_QUERY = "query";
    public static final String COLLECTION_CHECKEDOUT = "checkedout";
    public static final String COLLECTION_UNFILED = "unfiled";
    public static final String COLLECTION_BULK_UPDATE = "update";

    // URI templates
    public static final String TEMPLATE_OBJECT_BY_ID = "objectbyid";
    public static final String TEMPLATE_OBJECT_BY_PATH = "objectbypath";
    public static final String TEMPLATE_TYPE_BY_ID = "typebyid";
    public static final String TEMPLATE_QUERY = "query";

    // Link rel
    public static final String REL_SELF = "self";
    public static final String REL_ENCLOSURE = "enclosure";
    public static final String REL_SERVICE = "service";
    public static final String REL_DESCRIBEDBY = "describedby";
    public static final String REL_ALTERNATE = "alternate";
    public static final String REL_DOWN = "down";
    public static final String REL_UP = "up";
    public static final String REL_FIRST = "first";
    public static final String REL_LAST = "last";
    public static final String REL_PREV = "previous";
    public static final String REL_NEXT = "next";
    public static final String REL_VIA = "via";
    public static final String REL_EDIT = "edit";
    public static final String REL_EDITMEDIA = "edit-media";
    public static final String REL_VERSIONHISTORY = "version-history";
    public static final String REL_CURRENTVERSION = "current-version";
    public static final String REL_WORKINGCOPY = "working-copy";
    public static final String REL_FOLDERTREE = "http://docs.oasis-open.org/ns/cmis/link/200908/foldertree";
    public static final String REL_ALLOWABLEACTIONS = "http://docs.oasis-open.org/ns/cmis/link/200908/allowableactions";
    public static final String REL_ACL = "http://docs.oasis-open.org/ns/cmis/link/200908/acl";
    public static final String REL_SOURCE = "http://docs.oasis-open.org/ns/cmis/link/200908/source";
    public static final String REL_TARGET = "http://docs.oasis-open.org/ns/cmis/link/200908/target";

    public static final String REL_RELATIONSHIPS = "http://docs.oasis-open.org/ns/cmis/link/200908/relationships";
    public static final String REL_POLICIES = "http://docs.oasis-open.org/ns/cmis/link/200908/policies";

    public static final String REP_REL_TYPEDESC = "http://docs.oasis-open.org/ns/cmis/link/200908/typedescendants";
    public static final String REP_REL_FOLDERTREE = "http://docs.oasis-open.org/ns/cmis/link/200908/foldertree";
    public static final String REP_REL_ROOTDESC = "http://docs.oasis-open.org/ns/cmis/link/200908/rootdescendants";
    public static final String REP_REL_CHANGES = "http://docs.oasis-open.org/ns/cmis/link/200908/changes";

    // browser binding selectors
    public static final String SELECTOR_LAST_RESULT = "lastResult";
    public static final String SELECTOR_REPOSITORY_INFO = "repositoryInfo";
    public static final String SELECTOR_TYPE_CHILDREN = "typeChildren";
    public static final String SELECTOR_TYPE_DESCENDANTS = "typeDescendants";
    public static final String SELECTOR_TYPE_DEFINITION = "typeDefinition";
    public static final String SELECTOR_CONTENT = "content";
    public static final String SELECTOR_OBJECT = "object";
    public static final String SELECTOR_PROPERTIES = "properties";
    public static final String SELECTOR_ALLOWABLEACTIONS = "allowableActions";
    public static final String SELECTOR_RENDITIONS = "renditions";
    public static final String SELECTOR_CHILDREN = "children";
    public static final String SELECTOR_DESCENDANTS = "descendants";
    public static final String SELECTOR_PARENTS = "parents";
    public static final String SELECTOR_PARENT = "parent";
    public static final String SELECTOR_FOLDER_TREE = "folderTree";
    public static final String SELECTOR_QUERY = "query";
    public static final String SELECTOR_VERSIONS = "versions";
    public static final String SELECTOR_RELATIONSHIPS = "relationships";
    public static final String SELECTOR_CHECKEDOUT = "checkedout";
    public static final String SELECTOR_POLICIES = "policies";
    public static final String SELECTOR_ACL = "acl";
    public static final String SELECTOR_CONTENT_CHANGES = "contentChanges";

    // browser binding actions
    public static final String CMISACTION_CREATE_TYPE = "createType";
    public static final String CMISACTION_UPDATE_TYPE = "updateType";
    public static final String CMISACTION_DELETE_TYPE = "deleteType";
    public static final String CMISACTION_CREATE_DOCUMENT = "createDocument";
    public static final String CMISACTION_CREATE_DOCUMENT_FROM_SOURCE = "createDocumentFromSource";
    public static final String CMISACTION_CREATE_FOLDER = "createFolder";
    public static final String CMISACTION_CREATE_RELATIONSHIP = "createRelationship";
    public static final String CMISACTION_CREATE_POLICY = "createPolicy";
    public static final String CMISACTION_CREATE_ITEM = "createItem";
    public static final String CMISACTION_UPDATE_PROPERTIES = "update";
    public static final String CMISACTION_BULK_UPDATE = "bulkUpdate";
    public static final String CMISACTION_DELETE_CONTENT = "deleteContent";
    public static final String CMISACTION_SET_CONTENT = "setContent";
    public static final String CMISACTION_APPEND_CONTENT = "appendContent";
    public static final String CMISACTION_DELETE = "delete";
    public static final String CMISACTION_DELETE_TREE = "deleteTree";
    public static final String CMISACTION_MOVE = "move";
    public static final String CMISACTION_ADD_OBJECT_TO_FOLDER = "addObjectToFolder";
    public static final String CMISACTION_REMOVE_OBJECT_FROM_FOLDER = "removeObjectFromFolder";
    public static final String CMISACTION_QUERY = "query";
    public static final String CMISACTION_CHECK_OUT = "checkOut";
    public static final String CMISACTION_CANCEL_CHECK_OUT = "cancelCheckOut";
    public static final String CMISACTION_CHECK_IN = "checkIn";
    public static final String CMISACTION_APPLY_POLICY = "applyPolicy";
    public static final String CMISACTION_REMOVE_POLICY = "removePolicy";
    public static final String CMISACTION_APPLY_ACL = "applyACL";

    // browser binding control
    public static final String CONTROL_CMISACTION = "cmisaction";
    public static final String CONTROL_SUCCINCT = "succinct";
    public static final String CONTROL_TOKEN = "token";
    public static final String CONTROL_OBJECT_ID = "objectId";
    public static final String CONTROL_PROP_ID = "propertyId";
    public static final String CONTROL_PROP_VALUE = "propertyValue";
    public static final String CONTROL_POLICY = "policy";
    public static final String CONTROL_POLICY_ID = "policyId";
    public static final String CONTROL_ADD_ACE_PRINCIPAL = "addACEPrincipal";
    public static final String CONTROL_ADD_ACE_PERMISSION = "addACEPermission";
    public static final String CONTROL_REMOVE_ACE_PRINCIPAL = "removeACEPrincipal";
    public static final String CONTROL_REMOVE_ACE_PERMISSION = "removeACEPermission";
    public static final String CONTROL_CONTENT_TYPE = "contenttype";
    public static final String CONTROL_FILENAME = "filename";
    public static final String CONTROL_IS_LAST_CHUNK = "isLastChunk";
    public static final String CONTROL_TYPE = "type";
    public static final String CONTROL_TYPE_ID = "typeId";
    public static final String CONTROL_CHANGE_TOKEN = "changeToken";
    public static final String CONTROL_ADD_SECONDARY_TYPE = "addSecondaryTypeId";
    public static final String CONTROL_REMOVE_SECONDARY_TYPE = "removeSecondaryTypeId";

    // parameter
    public static final String PARAM_ACL = "includeACL";
    public static final String PARAM_ALLOWABLE_ACTIONS = "includeAllowableActions";
    public static final String PARAM_ALL_VERSIONS = "allVersions";
    public static final String PARAM_APPEND = "append";
    public static final String PARAM_CHANGE_LOG_TOKEN = "changeLogToken";
    public static final String PARAM_CHANGE_TOKEN = "changeToken";
    public static final String PARAM_CHECKIN_COMMENT = "checkinComment";
    public static final String PARAM_CHECK_IN = "checkin";
    public static final String PARAM_CHILD_TYPES = "childTypes";
    public static final String PARAM_CONTINUE_ON_FAILURE = "continueOnFailure";
    public static final String PARAM_DEPTH = "depth";
    public static final String PARAM_DOWNLOAD = "download";
    public static final String PARAM_FILTER = "filter";
    public static final String PARAM_SUCCINCT = "succinct";
    public static final String PARAM_DATETIME_FORMAT = "dateTimeFormat";
    public static final String PARAM_FOLDER_ID = "folderId";
    public static final String PARAM_ID = "id";
    public static final String PARAM_IS_LAST_CHUNK = "isLastChunk";
    public static final String PARAM_MAJOR = "major";
    public static final String PARAM_MAX_ITEMS = "maxItems";
    public static final String PARAM_OBJECT_ID = "objectId";
    public static final String PARAM_ONLY_BASIC_PERMISSIONS = "onlyBasicPermissions";
    public static final String PARAM_ORDER_BY = "orderBy";
    public static final String PARAM_OVERWRITE_FLAG = "overwriteFlag";
    public static final String PARAM_PATH = "path";
    public static final String PARAM_PATH_SEGMENT = "includePathSegment";
    public static final String PARAM_POLICY_ID = "policyId";
    public static final String PARAM_POLICY_IDS = "includePolicyIds";
    public static final String PARAM_PROPERTIES = "includeProperties";
    public static final String PARAM_PROPERTY_DEFINITIONS = "includePropertyDefinitions";
    public static final String PARAM_RELATIONSHIPS = "includeRelationships";
    public static final String PARAM_RELATIONSHIP_DIRECTION = "relationshipDirection";
    public static final String PARAM_RELATIVE_PATH_SEGMENT = "includeRelativePathSegment";
    public static final String PARAM_REMOVE_FROM = "removeFrom";
    public static final String PARAM_RENDITION_FILTER = "renditionFilter";
    public static final String PARAM_REPOSITORY_ID = "repositoryId";
    public static final String PARAM_RETURN_VERSION = "returnVersion";
    public static final String PARAM_SKIP_COUNT = "skipCount";
    public static final String PARAM_SOURCE_FOLDER_ID = "sourceFolderId";
    public static final String PARAM_TARGET_FOLDER_ID = "targetFolderId";
    public static final String PARAM_STREAM_ID = "streamId";
    public static final String PARAM_SUB_RELATIONSHIP_TYPES = "includeSubRelationshipTypes";
    public static final String PARAM_TYPE_ID = "typeId";
    public static final String PARAM_UNFILE_OBJECTS = "unfileObjects";
    public static final String PARAM_VERSION_SERIES_ID = "versionSeries";
    public static final String PARAM_VERSIONIG_STATE = "versioningState";
    public static final String PARAM_Q = "q";
    public static final String PARAM_STATEMENT = "statement";
    public static final String PARAM_SEARCH_ALL_VERSIONS = "searchAllVersions";
    public static final String PARAM_ACL_PROPAGATION = "ACLPropagation";
    public static final String PARAM_SOURCE_ID = "sourceId";

    public static final String PARAM_SELECTOR = "cmisselector";
    public static final String PARAM_CALLBACK = "callback";
    public static final String PARAM_SUPPRESS_RESPONSE_CODES = "suppressResponseCodes";
    public static final String PARAM_TOKEN = "token";

    // rendition filter
    public static final String RENDITION_NONE = "cmis:none";

    /**
     * Private constructor.
     */
    private Constants() {
    }
}
