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

import java.util.HashSet;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.enums.Action;

/**
 * JSON object constants.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class JSONConstants {

    public static final String ERROR_EXCEPTION = "exception";
    public static final String ERROR_MESSAGE = "message";
    public static final String ERROR_STACKTRACE = "stacktrace";

    public static final String JSON_REPINFO_ID = "repositoryId";
    public static final String JSON_REPINFO_NAME = "repositoryName";
    public static final String JSON_REPINFO_DESCRIPTION = "repositoryDescription";
    public static final String JSON_REPINFO_VENDOR = "vendorName";
    public static final String JSON_REPINFO_PRODUCT = "productName";
    public static final String JSON_REPINFO_PRODUCT_VERSION = "productVersion";
    public static final String JSON_REPINFO_ROOT_FOLDER_ID = "rootFolderId";
    public static final String JSON_REPINFO_REPOSITORY_URL = "repositoryUrl";
    public static final String JSON_REPINFO_ROOT_FOLDER_URL = "rootFolderUrl";
    public static final String JSON_REPINFO_CAPABILITIES = "capabilities";
    public static final String JSON_REPINFO_ACL_CAPABILITIES = "aclCapabilities";
    public static final String JSON_REPINFO_CHANGE_LOG_TOKEN = "latestChangeLogToken";
    public static final String JSON_REPINFO_CMIS_VERSION_SUPPORTED = "cmisVersionSupported";
    public static final String JSON_REPINFO_THIN_CLIENT_URI = "thinClientURI";
    public static final String JSON_REPINFO_CHANGES_INCOMPLETE = "changesIncomplete";
    public static final String JSON_REPINFO_CHANGES_ON_TYPE = "changesOnType";
    public static final String JSON_REPINFO_PRINCIPAL_ID_ANONYMOUS = "principalIdAnonymous";
    public static final String JSON_REPINFO_PRINCIPAL_ID_ANYONE = "principalIdAnyone";
    public static final String JSON_REPINFO_EXTENDED_FEATURES = "extendedFeatures";

    public static final Set<String> REPINFO_KEYS = new HashSet<String>();
    static {
        REPINFO_KEYS.add(JSON_REPINFO_ID);
        REPINFO_KEYS.add(JSON_REPINFO_NAME);
        REPINFO_KEYS.add(JSON_REPINFO_DESCRIPTION);
        REPINFO_KEYS.add(JSON_REPINFO_VENDOR);
        REPINFO_KEYS.add(JSON_REPINFO_PRODUCT);
        REPINFO_KEYS.add(JSON_REPINFO_PRODUCT_VERSION);
        REPINFO_KEYS.add(JSON_REPINFO_ROOT_FOLDER_ID);
        REPINFO_KEYS.add(JSON_REPINFO_REPOSITORY_URL);
        REPINFO_KEYS.add(JSON_REPINFO_ROOT_FOLDER_URL);
        REPINFO_KEYS.add(JSON_REPINFO_CAPABILITIES);
        REPINFO_KEYS.add(JSON_REPINFO_ACL_CAPABILITIES);
        REPINFO_KEYS.add(JSON_REPINFO_CHANGE_LOG_TOKEN);
        REPINFO_KEYS.add(JSON_REPINFO_CMIS_VERSION_SUPPORTED);
        REPINFO_KEYS.add(JSON_REPINFO_THIN_CLIENT_URI);
        REPINFO_KEYS.add(JSON_REPINFO_CHANGES_INCOMPLETE);
        REPINFO_KEYS.add(JSON_REPINFO_CHANGES_ON_TYPE);
        REPINFO_KEYS.add(JSON_REPINFO_PRINCIPAL_ID_ANONYMOUS);
        REPINFO_KEYS.add(JSON_REPINFO_PRINCIPAL_ID_ANYONE);
        REPINFO_KEYS.add(JSON_REPINFO_EXTENDED_FEATURES);
    }

    public static final String JSON_CAP_CONTENT_STREAM_UPDATABILITY = "capabilityContentStreamUpdatability";
    public static final String JSON_CAP_CHANGES = "capabilityChanges";
    public static final String JSON_CAP_RENDITIONS = "capabilityRenditions";
    public static final String JSON_CAP_GET_DESCENDANTS = "capabilityGetDescendants";
    public static final String JSON_CAP_GET_FOLDER_TREE = "capabilityGetFolderTree";
    public static final String JSON_CAP_MULTIFILING = "capabilityMultifiling";
    public static final String JSON_CAP_UNFILING = "capabilityUnfiling";
    public static final String JSON_CAP_VERSION_SPECIFIC_FILING = "capabilityVersionSpecificFiling";
    public static final String JSON_CAP_PWC_SEARCHABLE = "capabilityPWCSearchable";
    public static final String JSON_CAP_PWC_UPDATABLE = "capabilityPWCUpdatable";
    public static final String JSON_CAP_ALL_VERSIONS_SEARCHABLE = "capabilityAllVersionsSearchable";
    public static final String JSON_CAP_ORDER_BY = "capabilityOrderBy";
    public static final String JSON_CAP_QUERY = "capabilityQuery";
    public static final String JSON_CAP_JOIN = "capabilityJoin";
    public static final String JSON_CAP_ACL = "capabilityACL";
    public static final String JSON_CAP_CREATABLE_PROPERTY_TYPES = "capabilityCreatablePropertyTypes";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES = "capabilityNewTypeSettableAttributes";

    public static final Set<String> CAP_KEYS = new HashSet<String>();
    static {
        CAP_KEYS.add(JSON_CAP_CONTENT_STREAM_UPDATABILITY);
        CAP_KEYS.add(JSON_CAP_CHANGES);
        CAP_KEYS.add(JSON_CAP_RENDITIONS);
        CAP_KEYS.add(JSON_CAP_GET_DESCENDANTS);
        CAP_KEYS.add(JSON_CAP_GET_FOLDER_TREE);
        CAP_KEYS.add(JSON_CAP_MULTIFILING);
        CAP_KEYS.add(JSON_CAP_UNFILING);
        CAP_KEYS.add(JSON_CAP_VERSION_SPECIFIC_FILING);
        CAP_KEYS.add(JSON_CAP_PWC_SEARCHABLE);
        CAP_KEYS.add(JSON_CAP_PWC_UPDATABLE);
        CAP_KEYS.add(JSON_CAP_ALL_VERSIONS_SEARCHABLE);
        CAP_KEYS.add(JSON_CAP_ORDER_BY);
        CAP_KEYS.add(JSON_CAP_QUERY);
        CAP_KEYS.add(JSON_CAP_JOIN);
        CAP_KEYS.add(JSON_CAP_ACL);
        CAP_KEYS.add(JSON_CAP_CREATABLE_PROPERTY_TYPES);
        CAP_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES);
    }

    public static final String JSON_CAP_CREATABLE_PROPERTY_TYPES_CANCREATE = "canCreate";

    public static final Set<String> CAP_CREATABLE_PROPERTY_TYPES_KEYS = new HashSet<String>();
    static {
        CAP_CREATABLE_PROPERTY_TYPES_KEYS.add(JSON_CAP_CREATABLE_PROPERTY_TYPES_CANCREATE);
    }

    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_ID = "id";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAME = "localName";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAMESPACE = "localNamespace";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAYNAME = "displayName";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYNAME = "queryName";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION = "description";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATEABLE = "creatable";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE = "fileable";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE = "queryable";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXTINDEXED = "fulltextIndexed";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDEDINSUPERTYTPEQUERY = "includedInSupertypeQuery";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEPOLICY = "controllablePolicy";
    public static final String JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEACL = "controllableACL";

    public static final Set<String> CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS = new HashSet<String>();
    static {
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_ID);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAME);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAMESPACE);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAYNAME);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYNAME);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATEABLE);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXTINDEXED);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDEDINSUPERTYTPEQUERY);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEPOLICY);
        CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS.add(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEACL);
    }

    public static final String JSON_ACLCAP_SUPPORTED_PERMISSIONS = "supportedPermissions";
    public static final String JSON_ACLCAP_ACL_PROPAGATION = "propagation";
    public static final String JSON_ACLCAP_PERMISSIONS = "permissions";
    public static final String JSON_ACLCAP_PERMISSION_MAPPING = "permissionMapping";

    public static final Set<String> ACLCAP_KEYS = new HashSet<String>();
    static {
        ACLCAP_KEYS.add(JSON_ACLCAP_SUPPORTED_PERMISSIONS);
        ACLCAP_KEYS.add(JSON_ACLCAP_ACL_PROPAGATION);
        ACLCAP_KEYS.add(JSON_ACLCAP_PERMISSIONS);
        ACLCAP_KEYS.add(JSON_ACLCAP_PERMISSION_MAPPING);
    }

    public static final String JSON_ACLCAP_PERMISSION_PERMISSION = "permission";
    public static final String JSON_ACLCAP_PERMISSION_DESCRIPTION = "description";

    public static final Set<String> ACLCAP_PERMISSION_KEYS = new HashSet<String>();
    static {
        ACLCAP_PERMISSION_KEYS.add(JSON_ACLCAP_PERMISSION_PERMISSION);
        ACLCAP_PERMISSION_KEYS.add(JSON_ACLCAP_PERMISSION_DESCRIPTION);
    }

    public static final String JSON_ACLCAP_MAPPING_KEY = "key";
    public static final String JSON_ACLCAP_MAPPING_PERMISSION = "permission";

    public static final Set<String> ACLCAP_MAPPING_KEYS = new HashSet<String>();
    static {
        ACLCAP_MAPPING_KEYS.add(JSON_ACLCAP_MAPPING_KEY);
        ACLCAP_MAPPING_KEYS.add(JSON_ACLCAP_MAPPING_PERMISSION);
    }

    public static final String JSON_FEATURE_ID = "id";
    public static final String JSON_FEATURE_URL = "url";
    public static final String JSON_FEATURE_COMMON_NAME = "commonName";
    public static final String JSON_FEATURE_VERSION_LABEL = "versionLabel";
    public static final String JSON_FEATURE_DESCRIPTION = "description";
    public static final String JSON_FEATURE_DATA = "featureData";

    public static final Set<String> FEATURE_KEYS = new HashSet<String>();
    static {
        FEATURE_KEYS.add(JSON_FEATURE_ID);
        FEATURE_KEYS.add(JSON_FEATURE_URL);
        FEATURE_KEYS.add(JSON_FEATURE_COMMON_NAME);
        FEATURE_KEYS.add(JSON_FEATURE_VERSION_LABEL);
        FEATURE_KEYS.add(JSON_FEATURE_DESCRIPTION);
        FEATURE_KEYS.add(JSON_FEATURE_DATA);
    }

    public static final String JSON_OBJECT_PROPERTIES = "properties";
    public static final String JSON_OBJECT_SUCCINCT_PROPERTIES = "succinctProperties";
    public static final String JSON_OBJECT_PROPERTIES_EXTENSION = "propertiesExtension";
    public static final String JSON_OBJECT_ALLOWABLE_ACTIONS = "allowableActions";
    public static final String JSON_OBJECT_RELATIONSHIPS = "relationships";
    public static final String JSON_OBJECT_CHANGE_EVENT_INFO = "changeEventInfo";
    public static final String JSON_OBJECT_ACL = "acl";
    public static final String JSON_OBJECT_EXACT_ACL = "exactACL";
    public static final String JSON_OBJECT_POLICY_IDS = "policyIds";
    public static final String JSON_OBJECT_POLICY_IDS_IDS = "ids";
    public static final String JSON_OBJECT_RENDITIONS = "renditions";

    public static final Set<String> OBJECT_KEYS = new HashSet<String>();
    static {
        OBJECT_KEYS.add(JSON_OBJECT_PROPERTIES);
        OBJECT_KEYS.add(JSON_OBJECT_SUCCINCT_PROPERTIES);
        OBJECT_KEYS.add(JSON_OBJECT_PROPERTIES_EXTENSION);
        OBJECT_KEYS.add(JSON_OBJECT_ALLOWABLE_ACTIONS);
        OBJECT_KEYS.add(JSON_OBJECT_RELATIONSHIPS);
        OBJECT_KEYS.add(JSON_OBJECT_CHANGE_EVENT_INFO);
        OBJECT_KEYS.add(JSON_OBJECT_ACL);
        OBJECT_KEYS.add(JSON_OBJECT_EXACT_ACL);
        OBJECT_KEYS.add(JSON_OBJECT_POLICY_IDS);
        OBJECT_KEYS.add(JSON_OBJECT_RENDITIONS);
    }

    public static final Set<String> ALLOWABLE_ACTIONS_KEYS = new HashSet<String>();
    static {
        for (Action action : Action.values()) {
            ALLOWABLE_ACTIONS_KEYS.add(action.value());
        }
    }

    public static final Set<String> POLICY_IDS_KEYS = new HashSet<String>();
    static {
        POLICY_IDS_KEYS.add(JSON_OBJECT_POLICY_IDS_IDS);
    }

    public static final String JSON_OBJECTINFOLDER_OBJECT = "object";
    public static final String JSON_OBJECTINFOLDER_PATH_SEGMENT = "pathSegment";

    public static final Set<String> OBJECTINFOLDER_KEYS = new HashSet<String>();
    static {
        OBJECTINFOLDER_KEYS.add(JSON_OBJECTINFOLDER_OBJECT);
        OBJECTINFOLDER_KEYS.add(JSON_OBJECTINFOLDER_PATH_SEGMENT);
    }

    public static final String JSON_OBJECTPARENTS_OBJECT = "object";
    public static final String JSON_OBJECTPARENTS_RELATIVE_PATH_SEGMENT = "relativePathSegment";

    public static final Set<String> OBJECTPARENTS_KEYS = new HashSet<String>();
    static {
        OBJECTPARENTS_KEYS.add(JSON_OBJECTPARENTS_OBJECT);
        OBJECTPARENTS_KEYS.add(JSON_OBJECTPARENTS_RELATIVE_PATH_SEGMENT);
    }

    public static final String JSON_PROPERTY_ID = "id";
    public static final String JSON_PROPERTY_LOCALNAME = "localName";
    public static final String JSON_PROPERTY_DISPLAYNAME = "displayName";
    public static final String JSON_PROPERTY_QUERYNAME = "queryName";
    public static final String JSON_PROPERTY_VALUE = "value";
    public static final String JSON_PROPERTY_DATATYPE = "type";
    public static final String JSON_PROPERTY_CARDINALITY = "cardinality";

    public static final Set<String> PROPERTY_KEYS = new HashSet<String>();
    static {
        PROPERTY_KEYS.add(JSON_PROPERTY_ID);
        PROPERTY_KEYS.add(JSON_PROPERTY_LOCALNAME);
        PROPERTY_KEYS.add(JSON_PROPERTY_DISPLAYNAME);
        PROPERTY_KEYS.add(JSON_PROPERTY_QUERYNAME);
        PROPERTY_KEYS.add(JSON_PROPERTY_VALUE);
        PROPERTY_KEYS.add(JSON_PROPERTY_DATATYPE);
        PROPERTY_KEYS.add(JSON_PROPERTY_CARDINALITY);
    }

    public static final String JSON_CHANGE_EVENT_TYPE = "changeType";
    public static final String JSON_CHANGE_EVENT_TIME = "changeTime";

    public static final Set<String> CHANGE_EVENT_KEYS = new HashSet<String>();
    static {
        CHANGE_EVENT_KEYS.add(JSON_CHANGE_EVENT_TYPE);
        CHANGE_EVENT_KEYS.add(JSON_CHANGE_EVENT_TIME);
    }

    public static final String JSON_ACL_ACES = "aces";
    public static final String JSON_ACL_IS_EXACT = "isExact";

    public static final Set<String> ACL_KEYS = new HashSet<String>();
    static {
        ACL_KEYS.add(JSON_ACL_ACES);
        ACL_KEYS.add(JSON_ACL_IS_EXACT);
    }

    public static final String JSON_ACE_PRINCIPAL = "principal";
    public static final String JSON_ACE_PRINCIPAL_ID = "principalId";
    public static final String JSON_ACE_PERMISSIONS = "permissions";
    public static final String JSON_ACE_IS_DIRECT = "isDirect";

    public static final Set<String> ACE_KEYS = new HashSet<String>();
    static {
        ACE_KEYS.add(JSON_ACE_PRINCIPAL);
        ACE_KEYS.add(JSON_ACE_PRINCIPAL_ID);
        ACE_KEYS.add(JSON_ACE_PERMISSIONS);
        ACE_KEYS.add(JSON_ACE_IS_DIRECT);
    }

    public static final Set<String> PRINCIPAL_KEYS = new HashSet<String>();
    static {
        PRINCIPAL_KEYS.add(JSON_ACE_PRINCIPAL_ID);
    }

    public static final String JSON_RENDITION_STREAM_ID = "streamId";
    public static final String JSON_RENDITION_MIMETYPE = "mimeType";
    public static final String JSON_RENDITION_LENGTH = "length";
    public static final String JSON_RENDITION_KIND = "kind";
    public static final String JSON_RENDITION_TITLE = "title";
    public static final String JSON_RENDITION_HEIGHT = "height";
    public static final String JSON_RENDITION_WIDTH = "width";
    public static final String JSON_RENDITION_DOCUMENT_ID = "renditionDocumentId";

    public static final Set<String> RENDITION_KEYS = new HashSet<String>();
    static {
        RENDITION_KEYS.add(JSON_RENDITION_STREAM_ID);
        RENDITION_KEYS.add(JSON_RENDITION_MIMETYPE);
        RENDITION_KEYS.add(JSON_RENDITION_LENGTH);
        RENDITION_KEYS.add(JSON_RENDITION_KIND);
        RENDITION_KEYS.add(JSON_RENDITION_TITLE);
        RENDITION_KEYS.add(JSON_RENDITION_HEIGHT);
        RENDITION_KEYS.add(JSON_RENDITION_WIDTH);
        RENDITION_KEYS.add(JSON_RENDITION_DOCUMENT_ID);
    }

    public static final String JSON_OBJECTLIST_OBJECTS = "objects";
    public static final String JSON_OBJECTLIST_HAS_MORE_ITEMS = "hasMoreItems";
    public static final String JSON_OBJECTLIST_NUM_ITEMS = "numItems";
    public static final String JSON_OBJECTLIST_CHANGE_LOG_TOKEN = "changeLogToken";

    public static final Set<String> OBJECTLIST_KEYS = new HashSet<String>();
    static {
        OBJECTLIST_KEYS.add(JSON_OBJECTLIST_OBJECTS);
        OBJECTLIST_KEYS.add(JSON_OBJECTLIST_HAS_MORE_ITEMS);
        OBJECTLIST_KEYS.add(JSON_OBJECTLIST_NUM_ITEMS);
        OBJECTLIST_KEYS.add(JSON_OBJECTLIST_CHANGE_LOG_TOKEN);
    }

    public static final String JSON_OBJECTINFOLDERLIST_OBJECTS = "objects";
    public static final String JSON_OBJECTINFOLDERLIST_HAS_MORE_ITEMS = "hasMoreItems";
    public static final String JSON_OBJECTINFOLDERLIST_NUM_ITEMS = "numItems";

    public static final Set<String> OBJECTINFOLDERLIST_KEYS = new HashSet<String>();
    static {
        OBJECTINFOLDERLIST_KEYS.add(JSON_OBJECTINFOLDERLIST_OBJECTS);
        OBJECTINFOLDERLIST_KEYS.add(JSON_OBJECTINFOLDERLIST_HAS_MORE_ITEMS);
        OBJECTINFOLDERLIST_KEYS.add(JSON_OBJECTINFOLDERLIST_NUM_ITEMS);
    }

    public static final String JSON_OBJECTINFOLDERCONTAINER_OBJECT = "object";
    public static final String JSON_OBJECTINFOLDERCONTAINER_CHILDREN = "children";

    public static final Set<String> OBJECTINFOLDERCONTAINER_KEYS = new HashSet<String>();
    static {
        OBJECTINFOLDERCONTAINER_KEYS.add(JSON_OBJECTINFOLDERCONTAINER_OBJECT);
        OBJECTINFOLDERCONTAINER_KEYS.add(JSON_OBJECTINFOLDERCONTAINER_CHILDREN);
    }

    public static final String JSON_QUERYRESULTLIST_RESULTS = "results";
    public static final String JSON_QUERYRESULTLIST_HAS_MORE_ITEMS = "hasMoreItems";
    public static final String JSON_QUERYRESULTLIST_NUM_ITEMS = "numItems";

    public static final Set<String> QUERYRESULTLIST_KEYS = new HashSet<String>();
    static {
        QUERYRESULTLIST_KEYS.add(JSON_QUERYRESULTLIST_RESULTS);
        QUERYRESULTLIST_KEYS.add(JSON_QUERYRESULTLIST_HAS_MORE_ITEMS);
        QUERYRESULTLIST_KEYS.add(JSON_QUERYRESULTLIST_NUM_ITEMS);
    }

    public static final String JSON_TYPE_ID = "id";
    public static final String JSON_TYPE_LOCALNAME = "localName";
    public static final String JSON_TYPE_LOCALNAMESPACE = "localNamespace";
    public static final String JSON_TYPE_DISPLAYNAME = "displayName";
    public static final String JSON_TYPE_QUERYNAME = "queryName";
    public static final String JSON_TYPE_DESCRIPTION = "description";
    public static final String JSON_TYPE_BASE_ID = "baseId";
    public static final String JSON_TYPE_PARENT_ID = "parentId";
    public static final String JSON_TYPE_CREATABLE = "creatable";
    public static final String JSON_TYPE_FILEABLE = "fileable";
    public static final String JSON_TYPE_QUERYABLE = "queryable";
    public static final String JSON_TYPE_FULLTEXT_INDEXED = "fulltextIndexed";
    public static final String JSON_TYPE_INCLUDE_IN_SUPERTYPE_QUERY = "includedInSupertypeQuery";
    public static final String JSON_TYPE_CONTROLABLE_POLICY = "controllablePolicy";
    public static final String JSON_TYPE_CONTROLABLE_ACL = "controllableACL";
    public static final String JSON_TYPE_PROPERTY_DEFINITIONS = "propertyDefinitions";
    public static final String JSON_TYPE_TYPE_MUTABILITY = "typeMutability";

    public static final String JSON_TYPE_VERSIONABLE = "versionable"; // document
    public static final String JSON_TYPE_CONTENTSTREAM_ALLOWED = "contentStreamAllowed"; // document

    public static final String JSON_TYPE_ALLOWED_SOURCE_TYPES = "allowedSourceTypes"; // relationship
    public static final String JSON_TYPE_ALLOWED_TARGET_TYPES = "allowedTargetTypes"; // relationship

    public static final Set<String> TYPE_KEYS = new HashSet<String>();
    static {
        TYPE_KEYS.add(JSON_TYPE_ID);
        TYPE_KEYS.add(JSON_TYPE_LOCALNAME);
        TYPE_KEYS.add(JSON_TYPE_LOCALNAMESPACE);
        TYPE_KEYS.add(JSON_TYPE_DISPLAYNAME);
        TYPE_KEYS.add(JSON_TYPE_QUERYNAME);
        TYPE_KEYS.add(JSON_TYPE_DESCRIPTION);
        TYPE_KEYS.add(JSON_TYPE_BASE_ID);
        TYPE_KEYS.add(JSON_TYPE_PARENT_ID);
        TYPE_KEYS.add(JSON_TYPE_CREATABLE);
        TYPE_KEYS.add(JSON_TYPE_FILEABLE);
        TYPE_KEYS.add(JSON_TYPE_QUERYABLE);
        TYPE_KEYS.add(JSON_TYPE_FULLTEXT_INDEXED);
        TYPE_KEYS.add(JSON_TYPE_INCLUDE_IN_SUPERTYPE_QUERY);
        TYPE_KEYS.add(JSON_TYPE_CONTROLABLE_POLICY);
        TYPE_KEYS.add(JSON_TYPE_CONTROLABLE_ACL);
        TYPE_KEYS.add(JSON_TYPE_PROPERTY_DEFINITIONS);
        TYPE_KEYS.add(JSON_TYPE_VERSIONABLE);
        TYPE_KEYS.add(JSON_TYPE_CONTENTSTREAM_ALLOWED);
        TYPE_KEYS.add(JSON_TYPE_ALLOWED_SOURCE_TYPES);
        TYPE_KEYS.add(JSON_TYPE_ALLOWED_TARGET_TYPES);
        TYPE_KEYS.add(JSON_TYPE_TYPE_MUTABILITY);
    }

    public static final String JSON_PROPERTY_TYPE_ID = "id";
    public static final String JSON_PROPERTY_TYPE_LOCALNAME = "localName";
    public static final String JSON_PROPERTY_TYPE_LOCALNAMESPACE = "localNamespace";
    public static final String JSON_PROPERTY_TYPE_DISPLAYNAME = "displayName";
    public static final String JSON_PROPERTY_TYPE_QUERYNAME = "queryName";
    public static final String JSON_PROPERTY_TYPE_DESCRIPTION = "description";
    public static final String JSON_PROPERTY_TYPE_PROPERTY_TYPE = "propertyType";
    public static final String JSON_PROPERTY_TYPE_CARDINALITY = "cardinality";
    public static final String JSON_PROPERTY_TYPE_UPDATABILITY = "updatability";
    public static final String JSON_PROPERTY_TYPE_INHERITED = "inherited";
    public static final String JSON_PROPERTY_TYPE_REQUIRED = "required";
    public static final String JSON_PROPERTY_TYPE_QUERYABLE = "queryable";
    public static final String JSON_PROPERTY_TYPE_ORDERABLE = "orderable";
    public static final String JSON_PROPERTY_TYPE_OPENCHOICE = "openChoice";

    public static final String JSON_PROPERTY_TYPE_DEAULT_VALUE = "defaultValue";

    public static final String JSON_PROPERTY_TYPE_MAX_LENGTH = "maxLength";
    public static final String JSON_PROPERTY_TYPE_MIN_VALUE = "minValue";
    public static final String JSON_PROPERTY_TYPE_MAX_VALUE = "maxValue";
    public static final String JSON_PROPERTY_TYPE_PRECISION = "precision";
    public static final String JSON_PROPERTY_TYPE_RESOLUTION = "resolution";

    public static final String JSON_PROPERTY_TYPE_CHOICE = "choice";
    public static final String JSON_PROPERTY_TYPE_CHOICE_DISPLAYNAME = "displayName";
    public static final String JSON_PROPERTY_TYPE_CHOICE_VALUE = "value";
    public static final String JSON_PROPERTY_TYPE_CHOICE_CHOICE = "choice";

    public static final Set<String> PROPERTY_TYPE_KEYS = new HashSet<String>();
    static {
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_ID);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_LOCALNAME);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_LOCALNAMESPACE);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_DISPLAYNAME);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_QUERYNAME);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_DESCRIPTION);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_PROPERTY_TYPE);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_CARDINALITY);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_UPDATABILITY);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_INHERITED);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_REQUIRED);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_QUERYABLE);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_ORDERABLE);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_OPENCHOICE);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_DEAULT_VALUE);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_MAX_LENGTH);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_MIN_VALUE);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_MAX_VALUE);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_PRECISION);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_RESOLUTION);
        PROPERTY_TYPE_KEYS.add(JSON_PROPERTY_TYPE_CHOICE);
    }

    public static final String JSON_TYPE_TYPE_MUTABILITY_CREATE = "create";
    public static final String JSON_TYPE_TYPE_MUTABILITY_UPDATE = "update";
    public static final String JSON_TYPE_TYPE_MUTABILITY_DELETE = "delete";

    public static final Set<String> JSON_TYPE_TYPE_MUTABILITY_KEYS = new HashSet<String>();
    static {
        JSON_TYPE_TYPE_MUTABILITY_KEYS.add(JSON_TYPE_TYPE_MUTABILITY_CREATE);
        JSON_TYPE_TYPE_MUTABILITY_KEYS.add(JSON_TYPE_TYPE_MUTABILITY_UPDATE);
        JSON_TYPE_TYPE_MUTABILITY_KEYS.add(JSON_TYPE_TYPE_MUTABILITY_DELETE);
    }

    public static final String JSON_TYPESLIST_TYPES = "types";
    public static final String JSON_TYPESLIST_HAS_MORE_ITEMS = "hasMoreItems";
    public static final String JSON_TYPESLIST_NUM_ITEMS = "numItems";

    public static final Set<String> TYPESLIST_KEYS = new HashSet<String>();
    static {
        TYPESLIST_KEYS.add(JSON_TYPESLIST_TYPES);
        TYPESLIST_KEYS.add(JSON_TYPESLIST_HAS_MORE_ITEMS);
        TYPESLIST_KEYS.add(JSON_TYPESLIST_NUM_ITEMS);
    }

    public static final String JSON_TYPESCONTAINER_TYPE = "type";
    public static final String JSON_TYPESCONTAINER_CHILDREN = "children";

    public static final Set<String> TYPESCONTAINER_KEYS = new HashSet<String>();
    static {
        TYPESCONTAINER_KEYS.add(JSON_TYPESCONTAINER_TYPE);
        TYPESCONTAINER_KEYS.add(JSON_TYPESCONTAINER_CHILDREN);
    }

    public static final String JSON_FAILEDTODELETE_ID = "ids";

    public static final Set<String> FAILEDTODELETE_KEYS = new HashSet<String>();
    static {
        FAILEDTODELETE_KEYS.add(JSON_FAILEDTODELETE_ID);
    }

    public static final String JSON_BULK_UPDATE_ID = "id";
    public static final String JSON_BULK_UPDATE_NEW_ID = "newId";
    public static final String JSON_BULK_UPDATE_CHANGE_TOKEN = "changeToken";

    public static final Set<String> BULK_UPDATE_KEYS = new HashSet<String>();
    static {
        BULK_UPDATE_KEYS.add(JSON_BULK_UPDATE_ID);
        BULK_UPDATE_KEYS.add(JSON_BULK_UPDATE_NEW_ID);
        BULK_UPDATE_KEYS.add(JSON_BULK_UPDATE_CHANGE_TOKEN);
    }

    // Constant utility class.
    private JSONConstants() {
    }

}
