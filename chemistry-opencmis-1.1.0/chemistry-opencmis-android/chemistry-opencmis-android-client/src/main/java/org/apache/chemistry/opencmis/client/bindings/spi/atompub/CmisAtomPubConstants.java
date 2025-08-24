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
package org.apache.chemistry.opencmis.client.bindings.spi.atompub;

import java.util.HashSet;
import java.util.Set;

/**
 * Constants for AtomPub.
 */
public final class CmisAtomPubConstants {

    // service doc
    public static final String TAG_SERVICE = "service";
    public static final String TAG_WORKSPACE = "workspace";
    public static final String TAG_REPOSITORY_INFO = "repositoryInfo";
    public static final String TAG_COLLECTION = "collection";
    public static final String TAG_COLLECTION_TYPE = "collectionType";
    public static final String TAG_URI_TEMPLATE = "uritemplate";
    public static final String TAG_TEMPLATE_TEMPLATE = "template";
    public static final String TAG_TEMPLATE_TYPE = "type";
    public static final String TAG_LINK = "link";

    // atom
    public static final String TAG_ATOM_ID = "id";
    public static final String TAG_ATOM_TITLE = "title";
    public static final String TAG_ATOM_UPDATED = "updated";

    // feed
    public static final String TAG_FEED = "feed";

    // entry
    public static final String TAG_ENTRY = "entry";
    public static final String TAG_OBJECT = "object";
    public static final String TAG_NUM_ITEMS = "numItems";
    public static final String TAG_PATH_SEGMENT = "pathSegment";
    public static final String TAG_RELATIVE_PATH_SEGMENT = "relativePathSegment";
    public static final String TAG_TYPE = "type";
    public static final String TAG_CHILDREN = "children";
    public static final String TAG_CONTENT = "content";
    public static final String TAG_CONTENT_MEDIATYPE = "mediatype";
    public static final String TAG_CONTENT_BASE64 = "base64";
    public static final String TAG_CONTENT_FILENAME = "filename";

    public static final String ATTR_DOCUMENT_TYPE = "cmisTypeDocumentDefinitionType";
    public static final String ATTR_FOLDER_TYPE = "cmisTypeFolderDefinitionType";
    public static final String ATTR_RELATIONSHIP_TYPE = "cmisTypeRelationshipDefinitionType";
    public static final String ATTR_POLICY_TYPE = "cmisTypePolicyDefinitionType";
    public static final String ATTR_ITEM_TYPE = "cmisTypeItemDefinitionType";
    public static final String ATTR_SECONDARY_TYPE = "cmisTypeSecondaryDefinitionType";

    // allowable actions
    public static final String TAG_ALLOWABLEACTIONS = "allowableActions";

    // ACL
    public static final String TAG_ACL = "acl";

    // HTML
    public static final String TAG_HTML = "html";

    // links
    public static final String LINK_REL = "rel";
    public static final String LINK_HREF = "href";
    public static final String LINK_TYPE = "type";
    public static final String CONTENT_SRC = "src";

    // Android Parser Specific
    public static final String TAG_PROPERTY = "property";
    public static final String ATTR_PROPERTY_ID = "id";
    public static final String ATTR_PROPERTY_LOCALNAME = "localName";
    public static final String ATTR_PROPERTY_DISPLAYNAME = "displayName";
    public static final String ATTR_PROPERTY_QUERYNAME = "queryName";
    public static final String ATTR_PROPERTY_VALUE = "value";
    public static final String ATTR_PROPERTY_DATATYPE = "type";
    public static final String ATTR_PROPERTY_CARDINALITY = "cardinality";

    public static final String TAG_REPINFO_ID = "repositoryId";
    public static final String TAG_REPINFO_NAME = "repositoryName";
    public static final String TAG_REPINFO_DESCRIPTION = "repositoryDescription";
    public static final String TAG_REPINFO_VENDOR = "vendorName";
    public static final String TAG_REPINFO_PRODUCT = "productName";
    public static final String TAG_REPINFO_PRODUCT_VERSION = "productVersion";
    public static final String TAG_REPINFO_ROOT_FOLDER_ID = "rootFolderId";
    public static final String TAG_REPINFO_REPOSITORY_URL = "repositoryUrl";
    public static final String TAG_REPINFO_ROOT_FOLDER_URL = "rootFolderUrl";
    public static final String TAG_REPINFO_CAPABILITIES = "capabilities";
    public static final String TAG_REPINFO_ACL_CAPABILITY = "aclCapability";
    public static final String TAG_REPINFO_CHANGE_LOCK_TOKEN = "latestChangeLogToken";
    public static final String TAG_REPINFO_CMIS_VERSION_SUPPORTED = "cmisVersionSupported";
    public static final String TAG_REPINFO_THIN_CLIENT_URI = "thinClientURI";
    public static final String TAG_REPINFO_CHANGES_INCOMPLETE = "changesIncomplete";
    public static final String TAG_REPINFO_CHANGES_ON_TYPE = "changesOnType";
    public static final String TAG_REPINFO_PRINCIPAL_ANONYMOUS = "principalAnonymous";
    public static final String TAG_REPINFO_PRINCIPAL_ANYONE = "principalAnyone";

    public static final Set<String> REPINFO_KEYS = new HashSet<String>();
    static {
        REPINFO_KEYS.add(TAG_REPINFO_ID);
        REPINFO_KEYS.add(TAG_REPINFO_NAME);
        REPINFO_KEYS.add(TAG_REPINFO_DESCRIPTION);
        REPINFO_KEYS.add(TAG_REPINFO_VENDOR);
        REPINFO_KEYS.add(TAG_REPINFO_PRODUCT);
        REPINFO_KEYS.add(TAG_REPINFO_PRODUCT_VERSION);
        REPINFO_KEYS.add(TAG_REPINFO_ROOT_FOLDER_ID);
        REPINFO_KEYS.add(TAG_REPINFO_REPOSITORY_URL);
        REPINFO_KEYS.add(TAG_REPINFO_ROOT_FOLDER_URL);
        REPINFO_KEYS.add(TAG_REPINFO_CAPABILITIES);
        REPINFO_KEYS.add(TAG_REPINFO_ACL_CAPABILITY);
        REPINFO_KEYS.add(TAG_REPINFO_CHANGE_LOCK_TOKEN);
        REPINFO_KEYS.add(TAG_REPINFO_CMIS_VERSION_SUPPORTED);
        REPINFO_KEYS.add(TAG_REPINFO_THIN_CLIENT_URI);
        REPINFO_KEYS.add(TAG_REPINFO_CHANGES_INCOMPLETE);
        REPINFO_KEYS.add(TAG_REPINFO_CHANGES_ON_TYPE);
        REPINFO_KEYS.add(TAG_REPINFO_PRINCIPAL_ANONYMOUS);
        REPINFO_KEYS.add(TAG_REPINFO_PRINCIPAL_ANYONE);
    }

    public static final String TAG_ACLCAP_ACL_PROPAGATION = "propagation";
    public static final String TAG_ACLCAP_SUPPORTED_PERMISSIONS = "supportedPermissions";
    public static final String TAG_ACLCAP_PERMISSIONS = "permissions";
    public static final String TAG_ACLCAP_PERMISSION_PERMISSION = "permission";
    public static final String TAG_ACLCAP_PERMISSION_DESCRIPTION = "description";
    public static final String TAG_ACLCAP_MAPPING = "mapping";
    public static final String TAG_ACLCAP_MAPPING_KEY = "key";
    public static final String TAG_ACLCAP_MAPPING_PERMISSION = "permission";
    public static final String TAG_ACLCAP_DIRECT = "direct";

    public static final String TAG_CAP_CONTENT_STREAM_UPDATES = "capabilityContentStreamUpdatability";
    public static final String TAG_CAP_CHANGES = "capabilityChanges";
    public static final String TAG_CAP_RENDITIONS = "capabilityRenditions";
    public static final String TAG_CAP_GET_DESCENDANTS = "capabilityGetDescendants";
    public static final String TAG_CAP_GET_FOLDER_TREE = "capabilityGetFolderTree";
    public static final String TAG_CAP_MULTIFILING = "capabilityMultifiling";
    public static final String TAG_CAP_UNFILING = "capabilityUnfiling";
    public static final String TAG_CAP_VERSION_SPECIFIC_FILING = "capabilityVersionSpecificFiling";
    public static final String TAG_CAP_PWC_SEARCHABLE = "capabilityPWCSearchable";
    public static final String TAG_CAP_PWC_UPDATABLE = "capabilityPWCUpdatable";
    public static final String TAG_CAP_ALL_VERSIONS_SEARCHABLE = "capabilityAllVersionsSearchable";
    public static final String TAG_CAP_QUERY = "capabilityQuery";
    public static final String TAG_CAP_JOIN = "capabilityJoin";
    public static final String TAG_CAP_ACL = "capabilityACL";

    public static final String ATTR_PROPERTY_TYPE_ID = "id";
    public static final String ATTR_PROPERTY_DEFINITION_ID = "propertyDefinitionId";
    public static final String ATTR_PROPERTY_TYPE_LOCALNAME = "localName";
    public static final String ATTR_PROPERTY_TYPE_LOCALNAMESPACE = "localNamespace";
    public static final String ATTR_PROPERTY_TYPE_DISPLAYNAME = "displayName";
    public static final String ATTR_PROPERTY_TYPE_QUERYNAME = "queryName";
    public static final String ATTR_PROPERTY_TYPE_DESCRIPTION = "description";
    public static final String ATTR_PROPERTY_TYPE_PROPERTY_TYPE = "propertyType";
    public static final String ATTR_PROPERTY_TYPE_CARDINALITY = "cardinality";
    public static final String ATTR_PROPERTY_TYPE_UPDATABILITY = "updatability";
    public static final String ATTR_PROPERTY_TYPE_INHERITED = "inherited";
    public static final String ATTR_PROPERTY_TYPE_REQUIRED = "required";
    public static final String ATTR_PROPERTY_TYPE_QUERYABLE = "queryable";
    public static final String ATTR_PROPERTY_TYPE_ORDERABLE = "orderable";
    public static final String ATTR_PROPERTY_TYPE_OPENCHOICE = "openChoice";

    public static final String ATTR_PROPERTY_TYPE_MAX_LENGTH = "maxLength";
    public static final String ATTR_PROPERTY_TYPE_MIN_VALUE = "minValue";
    public static final String ATTR_PROPERTY_TYPE_MAX_VALUE = "maxValue";
    public static final String ATTR_PROPERTY_TYPE_PRECISION = "precision";
    public static final String ATTR_PROPERTY_TYPE_RESOLUTION = "resolution";

    public static final String TAG_RENDITION = "rendition";
    public static final String TAG_RENDITION_STREAM_ID = "streamId";
    public static final String TAG_RENDITION_MIMETYPE = "mimeType";
    public static final String TAG_RENDITION_LENGTH = "length";
    public static final String TAG_RENDITION_KIND = "kind";
    public static final String TAG_RENDITION_TITLE = "title";
    public static final String TAG_RENDITION_HEIGHT = "height";
    public static final String TAG_RENDITION_WIDTH = "width";
    public static final String TAG_RENDITION_DOCUMENT_ID = "renditionDocumentId";

    public static final String TAG_ACE_PRINCIPAL = "principal";
    public static final String TAG_ACE_PRINCIPAL_ID = "principalId";
    public static final String TAG_ACE_DIRECT = "direct";

    public static final String TYPE_ID = "id";
    public static final String TYPE_LOCALNAME = "localName";
    public static final String TYPE_LOCALNAMESPACE = "localNamespace";
    public static final String TYPE_DISPLAYNAME = "displayName";
    public static final String TYPE_QUERYNAME = "queryName";
    public static final String TYPE_DESCRIPTION = "description";
    public static final String TYPE_BASE_ID = "baseId";
    public static final String TYPE_PARENT_ID = "parentId";
    public static final String TYPE_CREATABLE = "creatable";
    public static final String TYPE_FILEABLE = "fileable";
    public static final String TYPE_QUERYABLE = "queryable";
    public static final String TYPE_FULLTEXT_INDEXED = "fulltextIndexed";
    public static final String TYPE_INCLUDE_IN_SUPERTYPE_QUERY = "includedInSupertypeQuery";
    public static final String TYPE_CONTROLABLE_POLICY = "controllablePolicy";
    public static final String TYPE_CONTROLABLE_ACL = "controllableACL";
    public static final String TYPE_PROPERTY_DEFINITIONS = "propertyDefinitions";
    public static final String TYPE_CONTENTSTREAM_ALLOWED = "contentStreamAllowed"; // document
    public static final String TYPE_VERSIONABLE = "versionable"; // document

    public static final String TAG_VALUE = "value";
    public static final String TAG_QUERY = "query";
    public static final String TAG_QUERY_STATEMENT = "statement";

    public static final String TAG_OBJECT_PROPERTIES = "properties";
    public static final String TAG_OBJECT_ALLOWABLE_ACTIONS = "allowableActions";
    public static final String TAG_OBJECT_RELATIONSHIPS = "relationships";
    public static final String TAG_OBJECT_CHANGE_EVENT_INFO = "changeEventInfo";
    public static final String TAG_OBJECT_ACL = "acl";
    public static final String TAG_OBJECT_EXACT_ACL = "exactACL";
    public static final String TAG_OBJECT_POLICY_IDS = "policyIds";
    public static final String TAG_OBJECT_RENDITION = "rendition";

    public static final Set<String> OBJECT_KEYS = new HashSet<String>();
    static {
        OBJECT_KEYS.add(TAG_OBJECT_PROPERTIES);
        OBJECT_KEYS.add(TAG_OBJECT_ALLOWABLE_ACTIONS);
        OBJECT_KEYS.add(TAG_OBJECT_RELATIONSHIPS);
        OBJECT_KEYS.add(TAG_OBJECT_CHANGE_EVENT_INFO);
        OBJECT_KEYS.add(TAG_OBJECT_ACL);
        OBJECT_KEYS.add(TAG_OBJECT_EXACT_ACL);
        OBJECT_KEYS.add(TAG_OBJECT_POLICY_IDS);
        OBJECT_KEYS.add(TAG_OBJECT_RENDITION);
    }

    private CmisAtomPubConstants() {
    }
}
