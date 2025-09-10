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

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class XMLConstants {

    // namespaces
    public static final String NAMESPACE_CMIS = "http://docs.oasis-open.org/ns/cmis/core/200908/";
    public static final String NAMESPACE_ATOM = "http://www.w3.org/2005/Atom";
    public static final String NAMESPACE_APP = "http://www.w3.org/2007/app";
    public static final String NAMESPACE_RESTATOM = "http://docs.oasis-open.org/ns/cmis/restatom/200908/";
    public static final String NAMESPACE_XSI = "http://www.w3.org/2001/XMLSchema-instance";
    public static final String NAMESPACE_APACHE_CHEMISTRY = "http://chemistry.apache.org/";

    // prefixes
    public static final String PREFIX_XSI = "xsi";
    public static final String PREFIX_ATOM = "atom";
    public static final String PREFIX_APP = "app";
    public static final String PREFIX_CMIS = "cmis";
    public static final String PREFIX_RESTATOM = "cmisra";
    public static final String PREFIX_APACHE_CHEMISTY = "chemistry";

    // tags
    public static final String TAG_REPOSITORY_INFO = "repositoryInfo";

    public static final String TAG_REPINFO_ID = "repositoryId";
    public static final String TAG_REPINFO_NAME = "repositoryName";
    public static final String TAG_REPINFO_DESCRIPTION = "repositoryDescription";
    public static final String TAG_REPINFO_VENDOR = "vendorName";
    public static final String TAG_REPINFO_PRODUCT = "productName";
    public static final String TAG_REPINFO_PRODUCT_VERSION = "productVersion";
    public static final String TAG_REPINFO_ROOT_FOLDER_ID = "rootFolderId";
    public static final String TAG_REPINFO_CAPABILITIES = "capabilities";
    public static final String TAG_REPINFO_ACL_CAPABILITIES = "aclCapability";
    public static final String TAG_REPINFO_CHANGE_LOG_TOKEN = "latestChangeLogToken";
    public static final String TAG_REPINFO_CMIS_VERSION_SUPPORTED = "cmisVersionSupported";
    public static final String TAG_REPINFO_THIN_CLIENT_URI = "thinClientURI";
    public static final String TAG_REPINFO_CHANGES_INCOMPLETE = "changesIncomplete";
    public static final String TAG_REPINFO_CHANGES_ON_TYPE = "changesOnType";
    public static final String TAG_REPINFO_PRINCIPAL_ID_ANONYMOUS = "principalAnonymous";
    public static final String TAG_REPINFO_PRINCIPAL_ID_ANYONE = "principalAnyone";
    public static final String TAG_REPINFO_EXTENDED_FEATURES = "extendedFeatures";

    public static final String TAG_CAP_CONTENT_STREAM_UPDATABILITY = "capabilityContentStreamUpdatability";
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
    public static final String TAG_CAP_ORDER_BY = "capabilityOrderBy";
    public static final String TAG_CAP_QUERY = "capabilityQuery";
    public static final String TAG_CAP_JOIN = "capabilityJoin";
    public static final String TAG_CAP_ACL = "capabilityACL";
    public static final String TAG_CAP_CREATABLE_PROPERTY_TYPES = "capabilityCreatablePropertyTypes";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES = "capabilityNewTypeSettableAttributes";

    public static final String TAG_CAP_CREATABLE_PROPERTY_TYPES_CANCREATE = "canCreate";

    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_ID = "id";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAME = "localName";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAMESPACE = "localNamespace";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAYNAME = "displayName";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYNAME = "queryName";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION = "description";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATEABLE = "creatable";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE = "fileable";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE = "queryable";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXTINDEXED = "fulltextIndexed";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDEDINSUPERTYTPEQUERY = "includedInSupertypeQuery";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEPOLICY = "controllablePolicy";
    public static final String TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEACL = "controllableACL";

    public static final String TAG_ACLCAP_SUPPORTED_PERMISSIONS = "supportedPermissions";
    public static final String TAG_ACLCAP_ACL_PROPAGATION = "propagation";
    public static final String TAG_ACLCAP_PERMISSIONS = "permissions";
    public static final String TAG_ACLCAP_PERMISSION_MAPPING = "mapping";

    public static final String TAG_ACLCAP_PERMISSION_PERMISSION = "permission";
    public static final String TAG_ACLCAP_PERMISSION_DESCRIPTION = "description";

    public static final String TAG_ACLCAP_MAPPING_KEY = "key";
    public static final String TAG_ACLCAP_MAPPING_PERMISSION = "permission";

    public static final String TAG_FEATURE_ID = "id";
    public static final String TAG_FEATURE_URL = "url";
    public static final String TAG_FEATURE_COMMON_NAME = "commonName";
    public static final String TAG_FEATURE_VERSION_LABEL = "versionLabel";
    public static final String TAG_FEATURE_DESCRIPTION = "description";
    public static final String TAG_FEATURE_DATA = "featureData";

    public static final String TAG_FEATURE_DATA_KEY = "key";
    public static final String TAG_FEATURE_DATA_VALUE = "value";

    public static final String TAG_OBJECT = "object";

    public static final String TAG_OBJECT_PROPERTIES = "properties";
    public static final String TAG_OBJECT_ALLOWABLE_ACTIONS = "allowableActions";
    public static final String TAG_OBJECT_RELATIONSHIP = "relationship";
    public static final String TAG_OBJECT_CHANGE_EVENT_INFO = "changeEventInfo";
    public static final String TAG_OBJECT_ACL = "acl";
    public static final String TAG_OBJECT_EXACT_ACL = "exactACL";
    public static final String TAG_OBJECT_POLICY_IDS = "policyIds";
    public static final String TAG_OBJECT_RENDITION = "rendition";

    public static final String TAG_PROP_BOOLEAN = "propertyBoolean";
    public static final String TAG_PROP_ID = "propertyId";
    public static final String TAG_PROP_INTEGER = "propertyInteger";
    public static final String TAG_PROP_DATETIME = "propertyDateTime";
    public static final String TAG_PROP_DECIMAL = "propertyDecimal";
    public static final String TAG_PROP_HTML = "propertyHtml";
    public static final String TAG_PROP_STRING = "propertyString";
    public static final String TAG_PROP_URI = "propertyUri";

    public static final String TAG_CHANGE_EVENT_TYPE = "changeType";
    public static final String TAG_CHANGE_EVENT_TIME = "changeTime";

    public static final String TAG_ACL_PERMISSISONS = "permission";
    public static final String TAG_ACL_IS_EXACT = "permission";
    public static final String TAG_ACE_PRINCIPAL = "principal";
    public static final String TAG_ACE_PRINCIPAL_ID = "principalId";
    public static final String TAG_ACE_PERMISSIONS = "permission";
    public static final String TAG_ACE_IS_DIRECT = "direct";

    public static final String TAG_POLICY_ID = "id";

    public static final String TAG_RENDITION_STREAM_ID = "streamId";
    public static final String TAG_RENDITION_MIMETYPE = "mimetype";
    public static final String TAG_RENDITION_LENGTH = "length";
    public static final String TAG_RENDITION_KIND = "kind";
    public static final String TAG_RENDITION_TITLE = "title";
    public static final String TAG_RENDITION_HEIGHT = "height";
    public static final String TAG_RENDITION_WIDTH = "width";
    public static final String TAG_RENDITION_DOCUMENT_ID = "renditionDocumentId";

    public static final String ATTR_PROPERTY_ID = "propertyDefinitionId";
    public static final String ATTR_PROPERTY_LOCALNAME = "localName";
    public static final String ATTR_PROPERTY_DISPLAYNAME = "displayName";
    public static final String ATTR_PROPERTY_QUERYNAME = "queryName";
    public static final String TAG_PROPERTY_VALUE = "value";

    public static final String TAG_TYPE = "type";

    public static final String ATTR_DOCUMENT_TYPE = "cmisTypeDocumentDefinitionType";
    public static final String ATTR_FOLDER_TYPE = "cmisTypeFolderDefinitionType";
    public static final String ATTR_RELATIONSHIP_TYPE = "cmisTypeRelationshipDefinitionType";
    public static final String ATTR_POLICY_TYPE = "cmisTypePolicyDefinitionType";
    public static final String ATTR_ITEM_TYPE = "cmisTypeItemDefinitionType";
    public static final String ATTR_SECONDARY_TYPE = "cmisTypeSecondaryDefinitionType";

    public static final String TAG_TYPE_ID = "id";
    public static final String TAG_TYPE_LOCALNAME = "localName";
    public static final String TAG_TYPE_LOCALNAMESPACE = "localNamespace";
    public static final String TAG_TYPE_DISPLAYNAME = "displayName";
    public static final String TAG_TYPE_QUERYNAME = "queryName";
    public static final String TAG_TYPE_DESCRIPTION = "description";
    public static final String TAG_TYPE_BASE_ID = "baseId";
    public static final String TAG_TYPE_PARENT_ID = "parentId";
    public static final String TAG_TYPE_CREATABLE = "creatable";
    public static final String TAG_TYPE_FILEABLE = "fileable";
    public static final String TAG_TYPE_QUERYABLE = "queryable";
    public static final String TAG_TYPE_FULLTEXT_INDEXED = "fulltextIndexed";
    public static final String TAG_TYPE_INCLUDE_IN_SUPERTYPE_QUERY = "includedInSupertypeQuery";
    public static final String TAG_TYPE_CONTROLABLE_POLICY = "controllablePolicy";
    public static final String TAG_TYPE_CONTROLABLE_ACL = "controllableACL";
    public static final String TAG_TYPE_TYPE_MUTABILITY = "typeMutability";
    public static final String TAG_TYPE_VERSIONABLE = "versionable"; // document
    public static final String TAG_TYPE_CONTENTSTREAM_ALLOWED = "contentStreamAllowed"; // document
    public static final String TAG_TYPE_ALLOWED_SOURCE_TYPES = "allowedSourceTypes"; // relationship
    public static final String TAG_TYPE_ALLOWED_TARGET_TYPES = "allowedTargetTypes"; // relationship

    public static final String TAG_TYPE_PROP_DEF_BOOLEAN = "propertyBooleanDefinition";
    public static final String TAG_TYPE_PROP_DEF_DATETIME = "propertyDateTimeDefinition";
    public static final String TAG_TYPE_PROP_DEF_DECIMAL = "propertyDecimalDefinition";
    public static final String TAG_TYPE_PROP_DEF_ID = "propertyIdDefinition";
    public static final String TAG_TYPE_PROP_DEF_INTEGER = "propertyIntegerDefinition";
    public static final String TAG_TYPE_PROP_DEF_HTML = "propertyHtmlDefinition";
    public static final String TAG_TYPE_PROP_DEF_STRING = "propertyStringDefinition";
    public static final String TAG_TYPE_PROP_DEF_URI = "propertyUriDefinition";

    public static final String TAG_PROPERTY_TYPE_ID = "id";
    public static final String TAG_PROPERTY_TYPE_LOCALNAME = "localName";
    public static final String TAG_PROPERTY_TYPE_LOCALNAMESPACE = "localNamespace";
    public static final String TAG_PROPERTY_TYPE_DISPLAYNAME = "displayName";
    public static final String TAG_PROPERTY_TYPE_QUERYNAME = "queryName";
    public static final String TAG_PROPERTY_TYPE_DESCRIPTION = "description";
    public static final String TAG_PROPERTY_TYPE_PROPERTY_TYPE = "propertyType";
    public static final String TAG_PROPERTY_TYPE_CARDINALITY = "cardinality";
    public static final String TAG_PROPERTY_TYPE_UPDATABILITY = "updatability";
    public static final String TAG_PROPERTY_TYPE_INHERITED = "inherited";
    public static final String TAG_PROPERTY_TYPE_REQUIRED = "required";
    public static final String TAG_PROPERTY_TYPE_QUERYABLE = "queryable";
    public static final String TAG_PROPERTY_TYPE_ORDERABLE = "orderable";
    public static final String TAG_PROPERTY_TYPE_OPENCHOICE = "openChoice";

    public static final String TAG_PROPERTY_TYPE_DEAULT_VALUE = "defaultValue";

    public static final String TAG_PROPERTY_TYPE_MAX_LENGTH = "maxLength";
    public static final String TAG_PROPERTY_TYPE_MIN_VALUE = "minValue";
    public static final String TAG_PROPERTY_TYPE_MAX_VALUE = "maxValue";
    public static final String TAG_PROPERTY_TYPE_PRECISION = "precision";
    public static final String TAG_PROPERTY_TYPE_RESOLUTION = "resolution";

    public static final String TAG_PROPERTY_TYPE_CHOICE = "choice";
    public static final String ATTR_PROPERTY_TYPE_CHOICE_DISPLAYNAME = "displayName";
    public static final String TAG_PROPERTY_TYPE_CHOICE_VALUE = "value";
    public static final String TAG_PROPERTY_TYPE_CHOICE_CHOICE = "choice";

    public static final String TAG_TYPE_TYPE_MUTABILITY_CREATE = "create";
    public static final String TAG_TYPE_TYPE_MUTABILITY_UPDATE = "update";
    public static final String TAG_TYPE_TYPE_MUTABILITY_DELETE = "delete";

    public static final String TAG_QUERY = "query";
    public static final String TAG_QUERY_STATEMENT = "statement";
    public static final String TAG_QUERY_SEARCHALLVERSIONS = "searchAllVersions";
    public static final String TAG_QUERY_INCLUDEALLOWABLEACTIONS = "includeAllowableActions";
    public static final String TAG_QUERY_INCLUDERELATIONSHIPS = "includeRelationships";
    public static final String TAG_QUERY_RENDITIONFILTER = "renditionFilter";
    public static final String TAG_QUERY_MAXITEMS = "maxItems";
    public static final String TAG_QUERY_SKIPCOUNT = "skipCount";

    public static final String TAG_BULK_UPDATE = "bulkUpdate";
    public static final String TAG_BULK_UPDATE_ID_AND_TOKEN = "objectIdAndChangeToken";
    public static final String TAG_BULK_UPDATE_PROPERTIES = "properties";
    public static final String TAG_BULK_UPDATE_ADD_SECONDARY_TYPES = "addSecondaryTypeIds";
    public static final String TAG_BULK_UPDATE_REMOVE_SECONDARY_TYPES = "removeSecondaryTypeIds";

    public static final String TAG_IDANDTOKEN_ID = "id";
    public static final String TAG_IDANDTOKEN_NEWID = "newId";
    public static final String TAG_IDANDTOKEN_CHANGETOKEN = "changeToken";

    private XMLConstants() {
    }
}
