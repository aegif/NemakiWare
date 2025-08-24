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

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;
import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;
import static org.apache.chemistry.opencmis.commons.impl.JSONConstants.*;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.ExtensionFeatures;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AclCapabilities;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ChangeEventInfo;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.CreatablePropertyTypes;
import org.apache.chemistry.opencmis.commons.data.ExtensionFeature;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.NewTypeSettableAttributes;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.PolicyIdList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyBoolean;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyDataWithDefinition;
import org.apache.chemistry.opencmis.commons.data.PropertyDateTime;
import org.apache.chemistry.opencmis.commons.data.PropertyDecimal;
import org.apache.chemistry.opencmis.commons.data.PropertyHtml;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyInteger;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.data.PropertyUri;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.data.RepositoryCapabilities;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyBooleanDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyHtmlDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyUriDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.definitions.TypeMutability;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.CapabilityJoin;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.CapabilityQuery;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.enums.DateTimeResolution;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.SupportedPermissions;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AclCapabilitiesDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AllowableActionsImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateObjectIdAndChangeTokenImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChangeEventInfoDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChoiceImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CreatablePropertyTypesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ExtensionFeatureImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FailedToDeleteDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.NewTypeSettableAttributesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectParentDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionDefinitionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionMappingDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyIdListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RenditionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryCapabilitiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryInfoBrowserBindingImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeMutabilityImpl;
import org.apache.chemistry.opencmis.commons.impl.json.JSONArray;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;

/**
 * OpenCMIS objects to JSON converter.
 */
public final class JSONConverter {

    /**
     * Private constructor.
     */
    private JSONConverter() {
    }

    public enum PropertyMode {
        /** object */
        OBJECT,
        /** query result */
        QUERY,
        /** change event */
        CHANGE
    }

    /**
     * Converts a repository info object.
     */
    public static JSONObject convert(final RepositoryInfo repositoryInfo, final String repositoryUrl,
            final String rootUrl, final boolean addExtendedDatetimeExtensionFeature) {
        if (repositoryInfo == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        result.put(JSON_REPINFO_ID, repositoryInfo.getId());
        result.put(JSON_REPINFO_NAME, repositoryInfo.getName());
        result.put(JSON_REPINFO_DESCRIPTION, repositoryInfo.getDescription());
        result.put(JSON_REPINFO_VENDOR, repositoryInfo.getVendorName());
        result.put(JSON_REPINFO_PRODUCT, repositoryInfo.getProductName());
        result.put(JSON_REPINFO_PRODUCT_VERSION, repositoryInfo.getProductVersion());
        result.put(JSON_REPINFO_ROOT_FOLDER_ID, repositoryInfo.getRootFolderId());
        result.put(JSON_REPINFO_CAPABILITIES, convert(repositoryInfo.getCapabilities()));
        setIfNotNull(JSON_REPINFO_ACL_CAPABILITIES, convert(repositoryInfo.getAclCapabilities()), result);
        result.put(JSON_REPINFO_CHANGE_LOG_TOKEN, repositoryInfo.getLatestChangeLogToken());
        result.put(JSON_REPINFO_CMIS_VERSION_SUPPORTED, repositoryInfo.getCmisVersionSupported());
        setIfNotNull(JSON_REPINFO_THIN_CLIENT_URI, repositoryInfo.getThinClientUri(), result);
        setIfNotNull(JSON_REPINFO_CHANGES_INCOMPLETE, repositoryInfo.getChangesIncomplete(), result);

        JSONArray changesOnType = new JSONArray();
        if (repositoryInfo.getChangesOnType() != null) {
            for (BaseTypeId type : repositoryInfo.getChangesOnType()) {
                if (type != null) {
                    changesOnType.add(getJSONStringValue(type.value()));
                }
            }
        }
        result.put(JSON_REPINFO_CHANGES_ON_TYPE, changesOnType);

        setIfNotNull(JSON_REPINFO_PRINCIPAL_ID_ANONYMOUS, repositoryInfo.getPrincipalIdAnonymous(), result);
        setIfNotNull(JSON_REPINFO_PRINCIPAL_ID_ANYONE, repositoryInfo.getPrincipalIdAnyone(), result);

        if (isNotEmpty(repositoryInfo.getExtensionFeatures())) {
            JSONArray extendedFeatures = new JSONArray();

            for (ExtensionFeature feature : repositoryInfo.getExtensionFeatures()) {
                extendedFeatures.add(convert(feature));
            }

            result.put(JSON_REPINFO_EXTENDED_FEATURES, extendedFeatures);
        }

        if (addExtendedDatetimeExtensionFeature) {
            JSONArray extendedFeatures = (JSONArray) result.get(JSON_REPINFO_EXTENDED_FEATURES);
            boolean addFeature = true;
            if (extendedFeatures == null) {
                extendedFeatures = new JSONArray();
                result.put(JSON_REPINFO_EXTENDED_FEATURES, extendedFeatures);
            } else {
                for (Object ef : extendedFeatures) {
                    if (ef instanceof ExtensionFeature) {
                        if (ExtensionFeatures.EXTENDED_DATETIME_FORMAT.getId()
                                .equals(((ExtensionFeature) ef).getId())) {
                            addFeature = false;
                            break;
                        }
                    }
                }
            }

            if (addFeature) {
                extendedFeatures.add(convert(ExtensionFeatures.EXTENDED_DATETIME_FORMAT));
            }
        }

        result.put(JSON_REPINFO_REPOSITORY_URL, repositoryUrl);
        result.put(JSON_REPINFO_ROOT_FOLDER_URL, rootUrl);

        convertExtension(repositoryInfo, result);

        return result;
    }

    private static JSONObject convert(final ExtensionFeature feature) {
        if (feature == null) {
            return null;
        }

        JSONObject jsonFeature = new JSONObject();

        setIfNotNull(JSON_FEATURE_ID, feature.getId(), jsonFeature);
        setIfNotNull(JSON_FEATURE_URL, feature.getUrl(), jsonFeature);
        setIfNotNull(JSON_FEATURE_COMMON_NAME, feature.getCommonName(), jsonFeature);
        setIfNotNull(JSON_FEATURE_VERSION_LABEL, feature.getVersionLabel(), jsonFeature);
        setIfNotNull(JSON_FEATURE_DESCRIPTION, feature.getDescription(), jsonFeature);

        if (isNotEmpty(feature.getFeatureData())) {
            JSONObject data = new JSONObject();
            data.putAll(feature.getFeatureData());
            jsonFeature.put(JSON_FEATURE_DATA, data);
        }

        convertExtension(feature, jsonFeature);

        return jsonFeature;
    }

    /**
     * Converts a capabilities object.
     */
    public static JSONObject convert(final RepositoryCapabilities capabilities) {
        if (capabilities == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        result.put(JSON_CAP_CONTENT_STREAM_UPDATABILITY,
                getJSONEnumValue(capabilities.getContentStreamUpdatesCapability()));
        result.put(JSON_CAP_CHANGES, getJSONEnumValue(capabilities.getChangesCapability()));
        result.put(JSON_CAP_RENDITIONS, getJSONEnumValue(capabilities.getRenditionsCapability()));
        result.put(JSON_CAP_GET_DESCENDANTS, capabilities.isGetDescendantsSupported());
        result.put(JSON_CAP_GET_FOLDER_TREE, capabilities.isGetFolderTreeSupported());
        result.put(JSON_CAP_MULTIFILING, capabilities.isMultifilingSupported());
        result.put(JSON_CAP_UNFILING, capabilities.isUnfilingSupported());
        result.put(JSON_CAP_VERSION_SPECIFIC_FILING, capabilities.isVersionSpecificFilingSupported());
        result.put(JSON_CAP_PWC_SEARCHABLE, capabilities.isPwcSearchableSupported());
        result.put(JSON_CAP_PWC_UPDATABLE, capabilities.isPwcUpdatableSupported());
        result.put(JSON_CAP_ALL_VERSIONS_SEARCHABLE, capabilities.isAllVersionsSearchableSupported());
        result.put(JSON_CAP_ORDER_BY, getJSONEnumValue(capabilities.getOrderByCapability()));
        result.put(JSON_CAP_QUERY, getJSONEnumValue(capabilities.getQueryCapability()));
        result.put(JSON_CAP_JOIN, getJSONEnumValue(capabilities.getJoinCapability()));
        result.put(JSON_CAP_ACL, getJSONEnumValue(capabilities.getAclCapability()));

        if (capabilities.getCreatablePropertyTypes() != null) {
            CreatablePropertyTypes creatablePropertyTypes = capabilities.getCreatablePropertyTypes();

            JSONObject creatablePropertyTypesJson = new JSONObject();

            if (creatablePropertyTypes.canCreate() != null) {
                JSONArray canCreate = new JSONArray();
                for (PropertyType propType : creatablePropertyTypes.canCreate()) {
                    if (propType != null) {
                        canCreate.add(propType.value());
                    }
                }
                creatablePropertyTypesJson.put(JSON_CAP_CREATABLE_PROPERTY_TYPES_CANCREATE, canCreate);
            }

            convertExtension(creatablePropertyTypes, creatablePropertyTypesJson);

            result.put(JSON_CAP_CREATABLE_PROPERTY_TYPES, creatablePropertyTypesJson);
        }

        if (capabilities.getNewTypeSettableAttributes() != null) {
            NewTypeSettableAttributes newTypeSettableAttributes = capabilities.getNewTypeSettableAttributes();

            JSONObject newTypeSettableAttributesJson = new JSONObject();
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_ID,
                    newTypeSettableAttributes.canSetId());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAME,
                    newTypeSettableAttributes.canSetLocalName());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAMESPACE,
                    newTypeSettableAttributes.canSetLocalNamespace());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAYNAME,
                    newTypeSettableAttributes.canSetDisplayName());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYNAME,
                    newTypeSettableAttributes.canSetQueryName());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION,
                    newTypeSettableAttributes.canSetDescription());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATEABLE,
                    newTypeSettableAttributes.canSetCreatable());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE,
                    newTypeSettableAttributes.canSetFileable());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE,
                    newTypeSettableAttributes.canSetQueryable());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXTINDEXED,
                    newTypeSettableAttributes.canSetFulltextIndexed());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDEDINSUPERTYTPEQUERY,
                    newTypeSettableAttributes.canSetIncludedInSupertypeQuery());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEPOLICY,
                    newTypeSettableAttributes.canSetControllablePolicy());
            newTypeSettableAttributesJson.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEACL,
                    newTypeSettableAttributes.canSetControllableAcl());

            convertExtension(newTypeSettableAttributes, newTypeSettableAttributesJson);

            result.put(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES, newTypeSettableAttributesJson);
        }

        convertExtension(capabilities, result);

        return result;
    }

    /**
     * Converts an ACL capabilities object.
     */
    public static JSONObject convert(final AclCapabilities capabilities) {
        if (capabilities == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        result.put(JSON_ACLCAP_SUPPORTED_PERMISSIONS, getJSONEnumValue(capabilities.getSupportedPermissions()));
        result.put(JSON_ACLCAP_ACL_PROPAGATION, getJSONEnumValue(capabilities.getAclPropagation()));

        // permissions
        if (capabilities.getPermissions() != null) {
            JSONArray permissions = new JSONArray();

            for (PermissionDefinition permDef : capabilities.getPermissions()) {
                JSONObject permission = new JSONObject();
                permission.put(JSON_ACLCAP_PERMISSION_PERMISSION, permDef.getId());
                permission.put(JSON_ACLCAP_PERMISSION_DESCRIPTION, permDef.getDescription());

                permissions.add(permission);
            }

            result.put(JSON_ACLCAP_PERMISSIONS, permissions);
        }

        // permission mapping
        if (capabilities.getPermissionMapping() != null) {
            JSONArray permissionMapping = new JSONArray();

            for (PermissionMapping permMap : capabilities.getPermissionMapping().values()) {
                JSONArray mappingPermissions = new JSONArray();
                if (permMap.getPermissions() != null) {
                    for (String p : permMap.getPermissions()) {
                        mappingPermissions.add(p);
                    }
                }

                JSONObject mapping = new JSONObject();
                mapping.put(JSON_ACLCAP_MAPPING_KEY, permMap.getKey());
                mapping.put(JSON_ACLCAP_MAPPING_PERMISSION, mappingPermissions);

                permissionMapping.add(mapping);
            }

            result.put(JSON_ACLCAP_PERMISSION_MAPPING, permissionMapping);
        }

        convertExtension(capabilities, result);

        return result;
    }

    public static RepositoryInfo convertRepositoryInfo(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        RepositoryInfoBrowserBindingImpl result = new RepositoryInfoBrowserBindingImpl();

        result.setId(getString(json, JSON_REPINFO_ID));
        result.setName(getString(json, JSON_REPINFO_NAME));
        result.setDescription(getString(json, JSON_REPINFO_DESCRIPTION));
        result.setVendorName(getString(json, JSON_REPINFO_VENDOR));
        result.setProductName(getString(json, JSON_REPINFO_PRODUCT));
        result.setProductVersion(getString(json, JSON_REPINFO_PRODUCT_VERSION));
        result.setRootFolder(getString(json, JSON_REPINFO_ROOT_FOLDER_ID));
        result.setRepositoryUrl(getString(json, JSON_REPINFO_REPOSITORY_URL));
        result.setRootUrl(getString(json, JSON_REPINFO_ROOT_FOLDER_URL));
        result.setCapabilities(convertRepositoryCapabilities(getMap(json.get(JSON_REPINFO_CAPABILITIES))));
        result.setAclCapabilities(convertAclCapabilities(getMap(json.get(JSON_REPINFO_ACL_CAPABILITIES))));
        result.setLatestChangeLogToken(getString(json, JSON_REPINFO_CHANGE_LOG_TOKEN));
        result.setCmisVersionSupported(getString(json, JSON_REPINFO_CMIS_VERSION_SUPPORTED));
        result.setThinClientUri(getString(json, JSON_REPINFO_THIN_CLIENT_URI));
        result.setChangesIncomplete(getBoolean(json, JSON_REPINFO_CHANGES_INCOMPLETE));

        List<Object> changesOnType = getList(json.get(JSON_REPINFO_CHANGES_ON_TYPE));
        if (changesOnType != null) {
            List<BaseTypeId> types = new ArrayList<BaseTypeId>();
            for (Object type : changesOnType) {
                if (type != null) {
                    types.add(BaseTypeId.fromValue(type.toString()));
                }
            }
            result.setChangesOnType(types);
        }

        result.setPrincipalAnonymous(getString(json, JSON_REPINFO_PRINCIPAL_ID_ANONYMOUS));
        result.setPrincipalAnyone(getString(json, JSON_REPINFO_PRINCIPAL_ID_ANYONE));

        List<Object> extendedFeatures = getList(json.get(JSON_REPINFO_EXTENDED_FEATURES));
        if (extendedFeatures != null) {
            List<ExtensionFeature> features = new ArrayList<ExtensionFeature>();

            for (Object extendedFeature : extendedFeatures) {
                Map<String, Object> jsonFeature = getMap(extendedFeature);

                ExtensionFeatureImpl feature = new ExtensionFeatureImpl();
                feature.setId(getString(jsonFeature, JSON_FEATURE_ID));
                feature.setUrl(getString(jsonFeature, JSON_FEATURE_URL));
                feature.setCommonName(getString(jsonFeature, JSON_FEATURE_COMMON_NAME));
                feature.setVersionLabel(getString(jsonFeature, JSON_FEATURE_VERSION_LABEL));
                feature.setDescription(getString(jsonFeature, JSON_FEATURE_DESCRIPTION));

                Map<String, Object> data = getMap(jsonFeature.get(JSON_FEATURE_DATA));
                if (data != null) {
                    LinkedHashMap<String, String> dataMap = new LinkedHashMap<String, String>();
                    for (Map.Entry<String, Object> e : data.entrySet()) {
                        dataMap.put(e.getKey(), (e.getValue() == null ? null : e.getValue().toString()));
                    }

                    if (!dataMap.isEmpty()) {
                        feature.setFeatureData(dataMap);
                    }
                }

                convertExtension(jsonFeature, feature, FEATURE_KEYS);

                features.add(feature);
            }

            if (!features.isEmpty()) {
                result.setExtensionFeature(features);
            }
        }

        // handle extensions
        convertExtension(json, result, REPINFO_KEYS);

        return result;
    }

    public static RepositoryCapabilities convertRepositoryCapabilities(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        RepositoryCapabilitiesImpl result = new RepositoryCapabilitiesImpl();

        result.setCapabilityContentStreamUpdates(
                getEnum(json, JSON_CAP_CONTENT_STREAM_UPDATABILITY, CapabilityContentStreamUpdates.class));
        result.setCapabilityChanges(getEnum(json, JSON_CAP_CHANGES, CapabilityChanges.class));
        result.setCapabilityRendition(getEnum(json, JSON_CAP_RENDITIONS, CapabilityRenditions.class));
        result.setSupportsGetDescendants(getBoolean(json, JSON_CAP_GET_DESCENDANTS));
        result.setSupportsGetFolderTree(getBoolean(json, JSON_CAP_GET_FOLDER_TREE));
        result.setSupportsMultifiling(getBoolean(json, JSON_CAP_MULTIFILING));
        result.setSupportsUnfiling(getBoolean(json, JSON_CAP_UNFILING));
        result.setSupportsVersionSpecificFiling(getBoolean(json, JSON_CAP_VERSION_SPECIFIC_FILING));
        result.setIsPwcSearchable(getBoolean(json, JSON_CAP_PWC_SEARCHABLE));
        result.setIsPwcUpdatable(getBoolean(json, JSON_CAP_PWC_UPDATABLE));
        result.setAllVersionsSearchable(getBoolean(json, JSON_CAP_ALL_VERSIONS_SEARCHABLE));
        result.setCapabilityOrderBy(getEnum(json, JSON_CAP_ORDER_BY, CapabilityOrderBy.class));
        result.setCapabilityQuery(getEnum(json, JSON_CAP_QUERY, CapabilityQuery.class));
        result.setCapabilityJoin(getEnum(json, JSON_CAP_JOIN, CapabilityJoin.class));
        result.setCapabilityAcl(getEnum(json, JSON_CAP_ACL, CapabilityAcl.class));

        Map<String, Object> creatablePropertyTypesJson = getMap(json.get(JSON_CAP_CREATABLE_PROPERTY_TYPES));
        if (creatablePropertyTypesJson != null) {
            CreatablePropertyTypesImpl creatablePropertyTypes = new CreatablePropertyTypesImpl();

            List<Object> canCreateJson = getList(
                    creatablePropertyTypesJson.get(JSON_CAP_CREATABLE_PROPERTY_TYPES_CANCREATE));
            if (canCreateJson != null) {
                Set<PropertyType> canCreate = EnumSet.noneOf(PropertyType.class);

                for (Object o : canCreateJson) {
                    try {
                        if (o != null) {
                            canCreate.add(PropertyType.fromValue(o.toString()));
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }

                creatablePropertyTypes.setCanCreate(canCreate);
            }

            convertExtension(creatablePropertyTypesJson, creatablePropertyTypes, CAP_CREATABLE_PROPERTY_TYPES_KEYS);

            result.setCreatablePropertyTypes(creatablePropertyTypes);
        }

        Map<String, Object> newTypeSettableAttributesJson = getMap(json.get(JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES));
        if (newTypeSettableAttributesJson != null) {
            NewTypeSettableAttributesImpl newTypeSettableAttributes = new NewTypeSettableAttributesImpl();

            newTypeSettableAttributes
                    .setCanSetId(getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_ID));
            newTypeSettableAttributes.setCanSetLocalName(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAME));
            newTypeSettableAttributes.setCanSetLocalNamespace(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAMESPACE));
            newTypeSettableAttributes.setCanSetDisplayName(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAYNAME));
            newTypeSettableAttributes.setCanSetQueryName(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYNAME));
            newTypeSettableAttributes.setCanSetDescription(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION));
            newTypeSettableAttributes.setCanSetCreatable(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATEABLE));
            newTypeSettableAttributes.setCanSetFileable(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE));
            newTypeSettableAttributes.setCanSetQueryable(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE));
            newTypeSettableAttributes.setCanSetFulltextIndexed(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXTINDEXED));
            newTypeSettableAttributes.setCanSetIncludedInSupertypeQuery(getBoolean(newTypeSettableAttributesJson,
                    JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDEDINSUPERTYTPEQUERY));
            newTypeSettableAttributes.setCanSetControllablePolicy(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEPOLICY));
            newTypeSettableAttributes.setCanSetControllableAcl(
                    getBoolean(newTypeSettableAttributesJson, JSON_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEACL));

            convertExtension(newTypeSettableAttributesJson, newTypeSettableAttributes,
                    CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_KEYS);

            result.setNewTypeSettableAttributes(newTypeSettableAttributes);
        }

        // handle extensions
        convertExtension(json, result, CAP_KEYS);

        return result;
    }

    @SuppressWarnings("unchecked")
    public static AclCapabilities convertAclCapabilities(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        AclCapabilitiesDataImpl result = new AclCapabilitiesDataImpl();

        result.setSupportedPermissions(getEnum(json, JSON_ACLCAP_SUPPORTED_PERMISSIONS, SupportedPermissions.class));
        result.setAclPropagation(getEnum(json, JSON_ACLCAP_ACL_PROPAGATION, AclPropagation.class));

        List<Object> permissions = getList(json.get(JSON_ACLCAP_PERMISSIONS));
        if (permissions != null) {
            List<PermissionDefinition> permissionDefinitionList = new ArrayList<PermissionDefinition>();

            for (Object permission : permissions) {
                Map<String, Object> permissionMap = getMap(permission);
                if (permissionMap != null) {
                    PermissionDefinitionDataImpl permDef = new PermissionDefinitionDataImpl();

                    permDef.setId(getString(permissionMap, JSON_ACLCAP_PERMISSION_PERMISSION));
                    permDef.setDescription(getString(permissionMap, JSON_ACLCAP_PERMISSION_DESCRIPTION));

                    convertExtension(permissionMap, permDef, ACLCAP_PERMISSION_KEYS);

                    permissionDefinitionList.add(permDef);
                }
            }

            result.setPermissionDefinitionData(permissionDefinitionList);
        }

        List<Object> permissionMapping = getList(json.get(JSON_ACLCAP_PERMISSION_MAPPING));
        if (permissionMapping != null) {
            Map<String, PermissionMapping> permMap = new HashMap<String, PermissionMapping>();

            for (Object permission : permissionMapping) {
                Map<String, Object> permissionMap = getMap(permission);
                if (permissionMap != null) {
                    PermissionMappingDataImpl mapping = new PermissionMappingDataImpl();

                    String key = getString(permissionMap, JSON_ACLCAP_MAPPING_KEY);
                    mapping.setKey(key);

                    Object perms = permissionMap.get(JSON_ACLCAP_MAPPING_PERMISSION);
                    if (perms instanceof List) {
                        List<String> permList = new ArrayList<String>(3);

                        for (Object perm : (List<Object>) perms) {
                            if (perm != null) {
                                permList.add(perm.toString());
                            }
                        }

                        mapping.setPermissions(permList);
                    }

                    convertExtension(permissionMap, mapping, ACLCAP_MAPPING_KEYS);

                    permMap.put(key, mapping);
                }
            }

            result.setPermissionMappingData(permMap);
        }

        // handle extensions
        convertExtension(json, result, ACLCAP_KEYS);

        return result;
    }

    @SuppressWarnings("unchecked")
    public static TypeDefinition convertTypeDefinition(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        AbstractTypeDefinition result = null;

        String id = getString(json, JSON_TYPE_ID);

        // find base type
        BaseTypeId baseType = getEnum(json, JSON_TYPE_BASE_ID, BaseTypeId.class);
        if (baseType == null) {
            throw new CmisInvalidArgumentException("Invalid base type: " + id);
        }

        switch (baseType) {
        case CMIS_FOLDER:
            result = new FolderTypeDefinitionImpl();
            break;
        case CMIS_DOCUMENT:
            result = new DocumentTypeDefinitionImpl();

            ((DocumentTypeDefinitionImpl) result).setContentStreamAllowed(
                    getEnum(json, JSON_TYPE_CONTENTSTREAM_ALLOWED, ContentStreamAllowed.class));
            ((DocumentTypeDefinitionImpl) result).setIsVersionable(getBoolean(json, JSON_TYPE_VERSIONABLE));

            break;
        case CMIS_RELATIONSHIP:
            result = new RelationshipTypeDefinitionImpl();

            Object allowedSourceTypes = json.get(JSON_TYPE_ALLOWED_SOURCE_TYPES);
            if (allowedSourceTypes instanceof List) {
                List<String> types = new ArrayList<String>();
                for (Object type : (List<Object>) allowedSourceTypes) {
                    if (type != null) {
                        types.add(type.toString());
                    }
                }

                ((RelationshipTypeDefinitionImpl) result).setAllowedSourceTypes(types);
            }

            Object allowedTargetTypes = json.get(JSON_TYPE_ALLOWED_TARGET_TYPES);
            if (allowedTargetTypes instanceof List) {
                List<String> types = new ArrayList<String>();
                for (Object type : (List<Object>) allowedTargetTypes) {
                    if (type != null) {
                        types.add(type.toString());
                    }
                }

                ((RelationshipTypeDefinitionImpl) result).setAllowedTargetTypes(types);
            }

            break;
        case CMIS_POLICY:
            result = new PolicyTypeDefinitionImpl();
            break;
        case CMIS_ITEM:
            result = new ItemTypeDefinitionImpl();
            break;
        case CMIS_SECONDARY:
            result = new SecondaryTypeDefinitionImpl();
            break;
        default:
            throw new CmisRuntimeException("Type '" + id + "' does not match a base type!");
        }

        result.setBaseTypeId(baseType);
        result.setDescription(getString(json, JSON_TYPE_DESCRIPTION));
        result.setDisplayName(getString(json, JSON_TYPE_DISPLAYNAME));
        result.setId(id);
        result.setIsControllableAcl(getBoolean(json, JSON_TYPE_CONTROLABLE_ACL));
        result.setIsControllablePolicy(getBoolean(json, JSON_TYPE_CONTROLABLE_POLICY));
        result.setIsCreatable(getBoolean(json, JSON_TYPE_CREATABLE));
        result.setIsFileable(getBoolean(json, JSON_TYPE_FILEABLE));
        result.setIsFulltextIndexed(getBoolean(json, JSON_TYPE_FULLTEXT_INDEXED));
        result.setIsIncludedInSupertypeQuery(getBoolean(json, JSON_TYPE_INCLUDE_IN_SUPERTYPE_QUERY));
        result.setIsQueryable(getBoolean(json, JSON_TYPE_QUERYABLE));
        result.setLocalName(getString(json, JSON_TYPE_LOCALNAME));
        result.setLocalNamespace(getString(json, JSON_TYPE_LOCALNAMESPACE));
        result.setParentTypeId(getString(json, JSON_TYPE_PARENT_ID));
        result.setQueryName(getString(json, JSON_TYPE_QUERYNAME));

        Map<String, Object> typeMutabilityJson = getMap(json.get(JSON_TYPE_TYPE_MUTABILITY));
        if (typeMutabilityJson != null) {
            TypeMutabilityImpl typeMutability = new TypeMutabilityImpl();

            typeMutability.setCanCreate(getBoolean(typeMutabilityJson, JSON_TYPE_TYPE_MUTABILITY_CREATE));
            typeMutability.setCanUpdate(getBoolean(typeMutabilityJson, JSON_TYPE_TYPE_MUTABILITY_UPDATE));
            typeMutability.setCanDelete(getBoolean(typeMutabilityJson, JSON_TYPE_TYPE_MUTABILITY_DELETE));

            convertExtension(typeMutabilityJson, typeMutability, JSON_TYPE_TYPE_MUTABILITY_KEYS);

            result.setTypeMutability(typeMutability);
        }

        Map<String, Object> propertyDefinitions = getMap(json.get(JSON_TYPE_PROPERTY_DEFINITIONS));
        if (propertyDefinitions != null) {
            for (Object propDef : propertyDefinitions.values()) {
                result.addPropertyDefinition(convertPropertyDefinition(getMap(propDef)));
            }
        }

        // handle extensions
        convertExtension(json, result, TYPE_KEYS);

        return result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static PropertyDefinition<?> convertPropertyDefinition(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        AbstractPropertyDefinition<?> result = null;

        String id = getString(json, JSON_PROPERTY_ID);

        // find property type
        PropertyType propertyType = getEnum(json, JSON_PROPERTY_TYPE_PROPERTY_TYPE, PropertyType.class);
        if (propertyType == null) {
            throw new CmisRuntimeException("Invalid property type '" + id + "'! Data type not set!");
        }

        // find
        Cardinality cardinality = getEnum(json, JSON_PROPERTY_TYPE_CARDINALITY, Cardinality.class);
        if (cardinality == null) {
            throw new CmisRuntimeException("Invalid property type '" + id + "'! Cardinality not set!");
        }

        switch (propertyType) {
        case STRING:
            result = new PropertyStringDefinitionImpl();
            ((PropertyStringDefinitionImpl) result).setMaxLength(getInteger(json, JSON_PROPERTY_TYPE_MAX_LENGTH));
            ((PropertyStringDefinitionImpl) result)
                    .setChoices(convertChoicesString(json.get(JSON_PROPERTY_TYPE_CHOICE)));
            break;
        case ID:
            result = new PropertyIdDefinitionImpl();
            ((PropertyIdDefinitionImpl) result).setChoices(convertChoicesString(json.get(JSON_PROPERTY_TYPE_CHOICE)));
            break;
        case BOOLEAN:
            result = new PropertyBooleanDefinitionImpl();
            ((PropertyBooleanDefinitionImpl) result)
                    .setChoices(convertChoicesBoolean(json.get(JSON_PROPERTY_TYPE_CHOICE)));
            break;
        case INTEGER:
            result = new PropertyIntegerDefinitionImpl();
            ((PropertyIntegerDefinitionImpl) result).setMinValue(getInteger(json, JSON_PROPERTY_TYPE_MIN_VALUE));
            ((PropertyIntegerDefinitionImpl) result).setMaxValue(getInteger(json, JSON_PROPERTY_TYPE_MAX_VALUE));
            ((PropertyIntegerDefinitionImpl) result)
                    .setChoices(convertChoicesInteger(json.get(JSON_PROPERTY_TYPE_CHOICE)));
            break;
        case DATETIME:
            result = new PropertyDateTimeDefinitionImpl();
            ((PropertyDateTimeDefinitionImpl) result)
                    .setDateTimeResolution(getEnum(json, JSON_PROPERTY_TYPE_RESOLUTION, DateTimeResolution.class));
            ((PropertyDateTimeDefinitionImpl) result)
                    .setChoices(convertChoicesDateTime(json.get(JSON_PROPERTY_TYPE_CHOICE)));
            break;
        case DECIMAL:
            result = new PropertyDecimalDefinitionImpl();
            ((PropertyDecimalDefinitionImpl) result).setMinValue(getDecimal(json, JSON_PROPERTY_TYPE_MIN_VALUE));
            ((PropertyDecimalDefinitionImpl) result).setMaxValue(getDecimal(json, JSON_PROPERTY_TYPE_MAX_VALUE));

            String precisionStr = getString(json, JSON_PROPERTY_TYPE_PRECISION);
            if (precisionStr != null) {
                if ("32".equals(precisionStr)) {
                    ((PropertyDecimalDefinitionImpl) result).setPrecision(DecimalPrecision.BITS32);
                } else if ("64".equals(precisionStr)) {
                    ((PropertyDecimalDefinitionImpl) result).setPrecision(DecimalPrecision.BITS64);
                }
            }

            ((PropertyDecimalDefinitionImpl) result)
                    .setChoices(convertChoicesDecimal(json.get(JSON_PROPERTY_TYPE_CHOICE)));
            break;
        case HTML:
            result = new PropertyHtmlDefinitionImpl();
            ((PropertyHtmlDefinitionImpl) result).setChoices(convertChoicesString(json.get(JSON_PROPERTY_TYPE_CHOICE)));
            break;
        case URI:
            result = new PropertyUriDefinitionImpl();
            ((PropertyUriDefinitionImpl) result).setChoices(convertChoicesString(json.get(JSON_PROPERTY_TYPE_CHOICE)));
            break;
        default:
            throw new CmisRuntimeException("Property type '" + id + "' does not match a data type!");
        }

        // default value
        Object defaultValue = json.get(JSON_PROPERTY_TYPE_DEAULT_VALUE);
        if (defaultValue != null) {
            if (defaultValue instanceof List) {
                List values = new ArrayList();
                for (Object value : (List) defaultValue) {
                    values.add(getCMISValue(value, propertyType));
                }
                result.setDefaultValue(values);
            } else {
                result.setDefaultValue((List) Collections.singletonList(getCMISValue(defaultValue, propertyType)));
            }
        }

        // generic
        result.setId(id);
        result.setPropertyType(propertyType);
        result.setCardinality(cardinality);
        result.setLocalName(getString(json, JSON_PROPERTY_TYPE_LOCALNAME));
        result.setLocalNamespace(getString(json, JSON_PROPERTY_TYPE_LOCALNAMESPACE));
        result.setQueryName(getString(json, JSON_PROPERTY_TYPE_QUERYNAME));
        result.setDescription(getString(json, JSON_PROPERTY_TYPE_DESCRIPTION));
        result.setDisplayName(getString(json, JSON_PROPERTY_TYPE_DISPLAYNAME));
        result.setIsInherited(getBoolean(json, JSON_PROPERTY_TYPE_INHERITED));
        result.setIsOpenChoice(getBoolean(json, JSON_PROPERTY_TYPE_OPENCHOICE));
        result.setIsOrderable(getBoolean(json, JSON_PROPERTY_TYPE_ORDERABLE));
        result.setIsQueryable(getBoolean(json, JSON_PROPERTY_TYPE_QUERYABLE));
        result.setIsRequired(getBoolean(json, JSON_PROPERTY_TYPE_REQUIRED));
        result.setUpdatability(getEnum(json, JSON_PROPERTY_TYPE_UPDATABILITY, Updatability.class));

        // handle extensions
        convertExtension(json, result, PROPERTY_TYPE_KEYS);

        return result;
    }

    /**
     * Converts choices.
     */
    @SuppressWarnings({ "rawtypes" })
    private static List<Choice<String>> convertChoicesString(final Object choices) {
        if (!(choices instanceof List)) {
            return null;
        }

        List<Choice<String>> result = new ArrayList<Choice<String>>();

        for (Object obj : (List) choices) {
            Map<String, Object> choiceMap = getMap(obj);
            if (choiceMap != null) {
                ChoiceImpl<String> choice = new ChoiceImpl<String>();
                choice.setDisplayName(getString(choiceMap, JSON_PROPERTY_TYPE_CHOICE_DISPLAYNAME));

                Object choiceValue = choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_VALUE);
                List<String> values = new ArrayList<String>();
                if (choiceValue instanceof List) {
                    for (Object value : (List) choiceValue) {
                        values.add((String) getCMISValue(value, PropertyType.STRING));
                    }
                } else {
                    values.add((String) getCMISValue(choiceValue, PropertyType.STRING));
                }
                choice.setValue(values);

                choice.setChoice(convertChoicesString(choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_CHOICE)));

                result.add(choice);
            }
        }

        return result;
    }

    /**
     * Converts choices.
     */
    @SuppressWarnings({ "rawtypes" })
    private static List<Choice<Boolean>> convertChoicesBoolean(final Object choices) {
        if (!(choices instanceof List)) {
            return null;
        }

        List<Choice<Boolean>> result = new ArrayList<Choice<Boolean>>();

        for (Object obj : (List) choices) {
            Map<String, Object> choiceMap = getMap(obj);
            if (choiceMap != null) {
                ChoiceImpl<Boolean> choice = new ChoiceImpl<Boolean>();
                choice.setDisplayName(getString(choiceMap, JSON_PROPERTY_TYPE_CHOICE_DISPLAYNAME));

                Object choiceValue = choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_VALUE);
                List<Boolean> values = new ArrayList<Boolean>();
                if (choiceValue instanceof List) {
                    for (Object value : (List) choiceValue) {
                        values.add((Boolean) getCMISValue(value, PropertyType.BOOLEAN));
                    }
                } else {
                    values.add((Boolean) getCMISValue(choiceValue, PropertyType.BOOLEAN));
                }
                choice.setValue(values);

                choice.setChoice(convertChoicesBoolean(choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_CHOICE)));

                result.add(choice);
            }
        }

        return result;
    }

    /**
     * Converts choices.
     */
    @SuppressWarnings({ "rawtypes" })
    private static List<Choice<BigInteger>> convertChoicesInteger(final Object choices) {
        if (!(choices instanceof List)) {
            return null;
        }

        List<Choice<BigInteger>> result = new ArrayList<Choice<BigInteger>>();

        for (Object obj : (List) choices) {
            Map<String, Object> choiceMap = getMap(obj);
            if (choiceMap != null) {
                ChoiceImpl<BigInteger> choice = new ChoiceImpl<BigInteger>();
                choice.setDisplayName(getString(choiceMap, JSON_PROPERTY_TYPE_CHOICE_DISPLAYNAME));

                Object choiceValue = choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_VALUE);
                List<BigInteger> values = new ArrayList<BigInteger>();
                if (choiceValue instanceof List) {
                    for (Object value : (List) choiceValue) {
                        values.add((BigInteger) getCMISValue(value, PropertyType.INTEGER));
                    }
                } else {
                    values.add((BigInteger) getCMISValue(choiceValue, PropertyType.INTEGER));
                }
                choice.setValue(values);

                choice.setChoice(convertChoicesInteger(choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_CHOICE)));

                result.add(choice);
            }
        }

        return result;
    }

    /**
     * Converts choices.
     */
    @SuppressWarnings({ "rawtypes" })
    private static List<Choice<BigDecimal>> convertChoicesDecimal(final Object choices) {
        if (!(choices instanceof List)) {
            return null;
        }

        List<Choice<BigDecimal>> result = new ArrayList<Choice<BigDecimal>>();

        for (Object obj : (List) choices) {
            Map<String, Object> choiceMap = getMap(obj);
            if (choiceMap != null) {
                ChoiceImpl<BigDecimal> choice = new ChoiceImpl<BigDecimal>();
                choice.setDisplayName(getString(choiceMap, JSON_PROPERTY_TYPE_CHOICE_DISPLAYNAME));

                Object choiceValue = choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_VALUE);
                List<BigDecimal> values = new ArrayList<BigDecimal>();
                if (choiceValue instanceof List) {
                    for (Object value : (List) choiceValue) {
                        values.add((BigDecimal) getCMISValue(value, PropertyType.DECIMAL));
                    }
                } else {
                    values.add((BigDecimal) getCMISValue(choiceValue, PropertyType.DECIMAL));
                }
                choice.setValue(values);

                choice.setChoice(convertChoicesDecimal(choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_CHOICE)));

                result.add(choice);
            }
        }

        return result;
    }

    /**
     * Converts choices.
     */
    @SuppressWarnings({ "rawtypes" })
    private static List<Choice<GregorianCalendar>> convertChoicesDateTime(final Object choices) {
        if (!(choices instanceof List)) {
            return null;
        }

        List<Choice<GregorianCalendar>> result = new ArrayList<Choice<GregorianCalendar>>();

        for (Object obj : (List) choices) {
            Map<String, Object> choiceMap = getMap(obj);
            if (choiceMap != null) {
                ChoiceImpl<GregorianCalendar> choice = new ChoiceImpl<GregorianCalendar>();
                choice.setDisplayName(getString(choiceMap, JSON_PROPERTY_TYPE_CHOICE_DISPLAYNAME));

                Object choiceValue = choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_VALUE);
                List<GregorianCalendar> values = new ArrayList<GregorianCalendar>();
                if (choiceValue instanceof List) {
                    for (Object value : (List) choiceValue) {
                        values.add((GregorianCalendar) getCMISValue(value, PropertyType.DATETIME));
                    }
                } else {
                    values.add((GregorianCalendar) getCMISValue(choiceValue, PropertyType.DATETIME));
                }
                choice.setValue(values);

                choice.setChoice(convertChoicesDateTime(choiceMap.get(JSON_PROPERTY_TYPE_CHOICE_CHOICE)));

                result.add(choice);
            }
        }

        return result;
    }

    /**
     * Converts an object.
     */
    public static JSONObject convert(final ObjectData object, final TypeCache typeCache,
            final PropertyMode propertyMode, final boolean succinct, final DateTimeFormat dateTimeFormat) {
        if (object == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        // properties
        if (object.getProperties() != null) {
            if (succinct) {
                JSONObject properties = convert(object.getProperties(), object.getId(), typeCache, propertyMode, true,
                        dateTimeFormat);
                if (properties != null) {
                    result.put(JSON_OBJECT_SUCCINCT_PROPERTIES, properties);
                }
            } else {
                JSONObject properties = convert(object.getProperties(), object.getId(), typeCache, propertyMode, false,
                        dateTimeFormat);
                if (properties != null) {
                    result.put(JSON_OBJECT_PROPERTIES, properties);
                }
            }

            JSONObject propertiesExtension = new JSONObject();
            convertExtension(object.getProperties(), propertiesExtension);
            if (!propertiesExtension.isEmpty()) {
                result.put(JSON_OBJECT_PROPERTIES_EXTENSION, propertiesExtension);
            }
        }

        // allowable actions
        if (object.getAllowableActions() != null) {
            result.put(JSON_OBJECT_ALLOWABLE_ACTIONS, convert(object.getAllowableActions()));
        }

        // relationships
        if (isNotEmpty(object.getRelationships())) {
            JSONArray relationships = new JSONArray();

            for (ObjectData relationship : object.getRelationships()) {
                relationships.add(convert(relationship, typeCache, propertyMode, succinct, dateTimeFormat));
            }

            result.put(JSON_OBJECT_RELATIONSHIPS, relationships);
        }

        // change event info
        if (object.getChangeEventInfo() != null && propertyMode == PropertyMode.CHANGE) {
            JSONObject changeEventInfo = new JSONObject();

            ChangeEventInfo cei = object.getChangeEventInfo();
            changeEventInfo.put(JSON_CHANGE_EVENT_TYPE, getJSONEnumValue(cei.getChangeType()));
            changeEventInfo.put(JSON_CHANGE_EVENT_TIME, getJSONValue(cei.getChangeTime(), dateTimeFormat));

            convertExtension(object.getChangeEventInfo(), changeEventInfo);

            result.put(JSON_OBJECT_CHANGE_EVENT_INFO, changeEventInfo);
        }

        // ACL
        if (object.getAcl() != null && object.getAcl().getAces() != null && propertyMode != PropertyMode.QUERY) {
            result.put(JSON_OBJECT_ACL, convert(object.getAcl()));
        }
        if (propertyMode != PropertyMode.QUERY) {
            setIfNotNull(JSON_OBJECT_EXACT_ACL, object.isExactAcl(), result);
        }

        // policy ids
        if (object.getPolicyIds() != null && object.getPolicyIds().getPolicyIds() != null
                && propertyMode != PropertyMode.QUERY) {
            JSONObject policyIds = new JSONObject();
            JSONArray ids = new JSONArray();
            policyIds.put(JSON_OBJECT_POLICY_IDS_IDS, ids);

            for (String pi : object.getPolicyIds().getPolicyIds()) {
                ids.add(pi);
            }

            convertExtension(object.getPolicyIds(), policyIds);

            result.put(JSON_OBJECT_POLICY_IDS, policyIds);
        }

        // renditions
        if (isNotEmpty(object.getRenditions())) {
            JSONArray renditions = new JSONArray();

            for (RenditionData rendition : object.getRenditions()) {
                renditions.add(convert(rendition));
            }

            result.put(JSON_OBJECT_RENDITIONS, renditions);
        }

        convertExtension(object, result);

        return result;
    }

    /**
     * Converts a bag of properties.
     */
    public static JSONObject convert(final Properties properties, final String objectId, final TypeCache typeCache,
            final PropertyMode propertyMode, final boolean succinct, final DateTimeFormat dateTimeFormat) {
        if (properties == null) {
            return null;
        }

        // get the type
        TypeDefinition type = null;
        if (typeCache != null) {
            PropertyData<?> typeProp = properties.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
            if (typeProp instanceof PropertyId) {
                String typeId = ((PropertyId) typeProp).getFirstValue();
                if (typeId != null) {
                    type = typeCache.getTypeDefinition(typeId);
                }
            }
        }

        JSONObject result = new JSONObject();

        for (PropertyData<?> property : properties.getPropertyList()) {
            assert property != null;
            assert property.getId() != null;

            PropertyDefinition<?> propDef = null;
            if (property instanceof PropertyDataWithDefinition) {
                propDef = ((PropertyDataWithDefinition<?>) property).getPropertyDefinition();
            }
            if (propDef == null && typeCache != null) {
                propDef = typeCache.getPropertyDefinition(property.getId());
            }
            if (propDef == null && type != null) {
                propDef = type.getPropertyDefinitions().get(property.getId());
            }
            if (propDef == null && typeCache != null && objectId != null && propertyMode != PropertyMode.CHANGE) {
                typeCache.getTypeDefinitionForObject(objectId);
                propDef = typeCache.getPropertyDefinition(property.getId());
            }

            String propId = (propertyMode == PropertyMode.QUERY ? property.getQueryName() : property.getId());
            if (propId == null) {
                throw new CmisRuntimeException("No query name or alias for property '" + property.getId() + "'!");
            }
            result.put(propId, convert(property, propDef, succinct, dateTimeFormat));
        }

        return result;
    }

    /**
     * Converts a property.
     */
    public static Object convert(final PropertyData<?> property, final PropertyDefinition<?> propDef, boolean succinct,
            final DateTimeFormat dateTimeFormat) {
        if (property == null) {
            return null;
        }

        if (succinct) {
            Object result = null;

            if (propDef != null) {
                if (isNullOrEmpty(property.getValues())) {
                    result = null;
                } else if (propDef.getCardinality() == Cardinality.SINGLE) {
                    result = getJSONValue(property.getValues().get(0), dateTimeFormat);
                } else {
                    JSONArray values = new JSONArray();

                    for (Object value : property.getValues()) {
                        values.add(getJSONValue(value, dateTimeFormat));
                    }

                    result = values;
                }
            } else {
                if (isNullOrEmpty(property.getValues())) {
                    result = null;
                } else {
                    JSONArray values = new JSONArray();

                    for (Object value : property.getValues()) {
                        values.add(getJSONValue(value, dateTimeFormat));
                    }

                    result = values;
                }
            }

            return result;
        } else {
            JSONObject result = new JSONObject();

            result.put(JSON_PROPERTY_ID, property.getId());
            setIfNotNull(JSON_PROPERTY_LOCALNAME, property.getLocalName(), result);
            setIfNotNull(JSON_PROPERTY_DISPLAYNAME, property.getDisplayName(), result);
            setIfNotNull(JSON_PROPERTY_QUERYNAME, property.getQueryName(), result);

            if (propDef != null) {
                result.put(JSON_PROPERTY_DATATYPE, getJSONEnumValue(propDef.getPropertyType()));
                result.put(JSON_PROPERTY_CARDINALITY, getJSONEnumValue(propDef.getCardinality()));

                if (isNullOrEmpty(property.getValues())) {
                    result.put(JSON_PROPERTY_VALUE, null);
                } else if (propDef.getCardinality() == Cardinality.SINGLE) {
                    result.put(JSON_PROPERTY_VALUE, getJSONValue(property.getValues().get(0), dateTimeFormat));
                } else {
                    JSONArray values = new JSONArray();

                    for (Object value : property.getValues()) {
                        values.add(getJSONValue(value, dateTimeFormat));
                    }

                    result.put(JSON_PROPERTY_VALUE, values);
                }
            } else {
                result.put(JSON_PROPERTY_DATATYPE, getJSONPropertyDataType(property));

                if (isNullOrEmpty(property.getValues())) {
                    result.put(JSON_PROPERTY_VALUE, null);
                } else {
                    JSONArray values = new JSONArray();

                    for (Object value : property.getValues()) {
                        values.add(getJSONValue(value, dateTimeFormat));
                    }

                    result.put(JSON_PROPERTY_VALUE, values);
                }
            }

            convertExtension(property, result);

            return result;
        }
    }

    /**
     * Converts allowable actions.
     */
    public static JSONObject convert(final AllowableActions allowableActions) {
        if (allowableActions == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        Set<Action> actionSet = allowableActions.getAllowableActions();
        for (Action action : Action.values()) {
            result.put(action.value(), actionSet.contains(action));
        }

        convertExtension(allowableActions, result);

        return result;
    }

    /**
     * Converts an ACL.
     */
    public static JSONObject convert(final Acl acl) {
        if (acl == null || acl.getAces() == null) {
            return null;
        }

        JSONArray aceObjects = new JSONArray();

        for (Ace ace : acl.getAces()) {
            JSONArray permissions = new JSONArray();
            if (ace.getPermissions() != null) {
                for (String p : ace.getPermissions()) {
                    permissions.add(p);
                }
            }

            JSONObject aceObject = new JSONObject();

            JSONObject principalObject = new JSONObject();
            principalObject.put(JSON_ACE_PRINCIPAL_ID, ace.getPrincipalId());
            convertExtension(ace.getPrincipal(), principalObject);
            aceObject.put(JSON_ACE_PRINCIPAL, principalObject);

            aceObject.put(JSON_ACE_PERMISSIONS, permissions);
            aceObject.put(JSON_ACE_IS_DIRECT, ace.isDirect());

            convertExtension(ace, aceObject);

            aceObjects.add(aceObject);
        }

        JSONObject result = new JSONObject();
        result.put(JSON_ACL_ACES, aceObjects);
        setIfNotNull(JSON_ACL_IS_EXACT, acl.isExact(), result);

        convertExtension(acl, result);

        return result;
    }

    /**
     * Converts a rendition.
     */
    public static JSONObject convert(final RenditionData rendition) {
        if (rendition == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        result.put(JSON_RENDITION_STREAM_ID, rendition.getStreamId());
        result.put(JSON_RENDITION_MIMETYPE, rendition.getMimeType());
        result.put(JSON_RENDITION_LENGTH, rendition.getBigLength());
        result.put(JSON_RENDITION_KIND, rendition.getKind());
        setIfNotNull(JSON_RENDITION_TITLE, rendition.getTitle(), result);
        setIfNotNull(JSON_RENDITION_HEIGHT, rendition.getBigHeight(), result);
        setIfNotNull(JSON_RENDITION_WIDTH, rendition.getBigWidth(), result);
        setIfNotNull(JSON_RENDITION_DOCUMENT_ID, rendition.getRenditionDocumentId(), result);

        convertExtension(rendition, result);

        return result;
    }

    /**
     * Converts a query object list.
     */
    public static JSONObject convert(final ObjectList list, final TypeCache typeCache, final PropertyMode propertyMode,
            final boolean succinct, final DateTimeFormat dateTimeFormat) {
        if (list == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        JSONArray objects = new JSONArray();
        if (list.getObjects() != null) {
            for (ObjectData object : list.getObjects()) {
                objects.add(convert(object, typeCache, propertyMode, succinct, dateTimeFormat));
            }
        }

        if (propertyMode == PropertyMode.QUERY) {
            result.put(JSON_QUERYRESULTLIST_RESULTS, objects);

            setIfNotNull(JSON_QUERYRESULTLIST_HAS_MORE_ITEMS, list.hasMoreItems(), result);
            setIfNotNull(JSON_QUERYRESULTLIST_NUM_ITEMS, list.getNumItems(), result);
        } else {
            result.put(JSON_OBJECTLIST_OBJECTS, objects);

            setIfNotNull(JSON_OBJECTLIST_HAS_MORE_ITEMS, list.hasMoreItems(), result);
            setIfNotNull(JSON_OBJECTLIST_NUM_ITEMS, list.getNumItems(), result);
        }

        convertExtension(list, result);

        return result;
    }

    /**
     * Converts an object in a folder list.
     */
    public static JSONObject convert(final ObjectInFolderData objectInFolder, final TypeCache typeCache,
            final boolean succinct, final DateTimeFormat dateTimeFormat) {
        if ((objectInFolder == null) || (objectInFolder.getObject() == null)) {
            return null;
        }

        JSONObject result = new JSONObject();
        result.put(JSON_OBJECTINFOLDER_OBJECT,
                convert(objectInFolder.getObject(), typeCache, PropertyMode.OBJECT, succinct, dateTimeFormat));
        setIfNotNull(JSON_OBJECTINFOLDER_PATH_SEGMENT, objectInFolder.getPathSegment(), result);

        convertExtension(objectInFolder, result);

        return result;
    }

    /**
     * Converts a folder list.
     */
    public static JSONObject convert(final ObjectInFolderList objectInFolderList, final TypeCache typeCache,
            final boolean succinct, final DateTimeFormat dateTimeFormat) {
        if (objectInFolderList == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        if (objectInFolderList.getObjects() != null) {
            JSONArray objects = new JSONArray();

            for (ObjectInFolderData object : objectInFolderList.getObjects()) {
                objects.add(convert(object, typeCache, succinct, dateTimeFormat));
            }

            result.put(JSON_OBJECTINFOLDERLIST_OBJECTS, objects);
        }

        setIfNotNull(JSON_OBJECTINFOLDERLIST_HAS_MORE_ITEMS, objectInFolderList.hasMoreItems(), result);
        setIfNotNull(JSON_OBJECTINFOLDERLIST_NUM_ITEMS, objectInFolderList.getNumItems(), result);

        convertExtension(objectInFolderList, result);

        return result;
    }

    /**
     * Converts a folder container.
     */
    public static JSONObject convert(final ObjectInFolderContainer container, final TypeCache typeCache,
            final boolean succinct, final DateTimeFormat dateTimeFormat) {
        if (container == null) {
            return null;
        }

        JSONObject result = new JSONObject();
        result.put(JSON_OBJECTINFOLDERCONTAINER_OBJECT,
                convert(container.getObject(), typeCache, succinct, dateTimeFormat));

        if (isNotEmpty(container.getChildren())) {
            JSONArray children = new JSONArray();
            for (ObjectInFolderContainer descendant : container.getChildren()) {
                children.add(JSONConverter.convert(descendant, typeCache, succinct, dateTimeFormat));
            }

            result.put(JSON_OBJECTINFOLDERCONTAINER_CHILDREN, children);
        }

        convertExtension(container, result);

        return result;
    }

    /**
     * Converts an object parent.
     */
    public static JSONObject convert(final ObjectParentData parent, final TypeCache typeCache, final boolean succinct,
            final DateTimeFormat dateTimeFormat) {
        if ((parent == null) || (parent.getObject() == null)) {
            return null;
        }

        JSONObject result = new JSONObject();
        result.put(JSON_OBJECTPARENTS_OBJECT,
                convert(parent.getObject(), typeCache, PropertyMode.OBJECT, succinct, dateTimeFormat));
        if (parent.getRelativePathSegment() != null) {
            result.put(JSON_OBJECTPARENTS_RELATIVE_PATH_SEGMENT, parent.getRelativePathSegment());
        }

        convertExtension(parent, result);

        return result;
    }

    /**
     * Converts a type definition.
     */
    public static JSONObject convert(final TypeDefinition type, final DateTimeFormat dateTimeFormat) {
        if (type == null) {
            return null;
        }

        JSONObject result = new JSONObject();
        result.put(JSON_TYPE_ID, type.getId());
        result.put(JSON_TYPE_LOCALNAME, type.getLocalName());
        result.put(JSON_TYPE_LOCALNAMESPACE, type.getLocalNamespace());
        setIfNotNull(JSON_TYPE_DISPLAYNAME, type.getDisplayName(), result);
        setIfNotNull(JSON_TYPE_QUERYNAME, type.getQueryName(), result);
        setIfNotNull(JSON_TYPE_DESCRIPTION, type.getDescription(), result);
        result.put(JSON_TYPE_BASE_ID, getJSONEnumValue(type.getBaseTypeId()));
        setIfNotNull(JSON_TYPE_PARENT_ID, type.getParentTypeId(), result);
        result.put(JSON_TYPE_CREATABLE, type.isCreatable());
        result.put(JSON_TYPE_FILEABLE, type.isFileable());
        result.put(JSON_TYPE_QUERYABLE, type.isQueryable());
        result.put(JSON_TYPE_FULLTEXT_INDEXED, type.isFulltextIndexed());
        result.put(JSON_TYPE_INCLUDE_IN_SUPERTYPE_QUERY, type.isIncludedInSupertypeQuery());
        result.put(JSON_TYPE_CONTROLABLE_POLICY, type.isControllablePolicy());
        result.put(JSON_TYPE_CONTROLABLE_ACL, type.isControllableAcl());

        if (type.getTypeMutability() != null) {
            TypeMutability typeMutability = type.getTypeMutability();
            JSONObject typeMutabilityJson = new JSONObject();

            typeMutabilityJson.put(JSON_TYPE_TYPE_MUTABILITY_CREATE, typeMutability.canCreate());
            typeMutabilityJson.put(JSON_TYPE_TYPE_MUTABILITY_UPDATE, typeMutability.canUpdate());
            typeMutabilityJson.put(JSON_TYPE_TYPE_MUTABILITY_DELETE, typeMutability.canDelete());

            convertExtension(typeMutability, typeMutabilityJson);

            result.put(JSON_TYPE_TYPE_MUTABILITY, typeMutabilityJson);
        }

        if (type instanceof DocumentTypeDefinition) {
            result.put(JSON_TYPE_VERSIONABLE, ((DocumentTypeDefinition) type).isVersionable());
            result.put(JSON_TYPE_CONTENTSTREAM_ALLOWED,
                    getJSONEnumValue(((DocumentTypeDefinition) type).getContentStreamAllowed()));
        }

        if (type instanceof RelationshipTypeDefinition) {
            result.put(JSON_TYPE_ALLOWED_SOURCE_TYPES,
                    getJSONArrayFromList(((RelationshipTypeDefinition) type).getAllowedSourceTypeIds()));
            result.put(JSON_TYPE_ALLOWED_TARGET_TYPES,
                    getJSONArrayFromList(((RelationshipTypeDefinition) type).getAllowedTargetTypeIds()));
        }

        if (isNotEmpty(type.getPropertyDefinitions())) {
            JSONObject propertyDefs = new JSONObject();

            for (PropertyDefinition<?> pd : type.getPropertyDefinitions().values()) {
                propertyDefs.put(pd.getId(), convert(pd, dateTimeFormat));
            }

            result.put(JSON_TYPE_PROPERTY_DEFINITIONS, propertyDefs);
        }

        convertExtension(type, result);

        return result;
    }

    /**
     * Converts a property type definition.
     */
    public static JSONObject convert(final PropertyDefinition<?> propertyDefinition,
            final DateTimeFormat dateTimeFormat) {
        if (propertyDefinition == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        // type specific
        if (propertyDefinition instanceof PropertyStringDefinition) {
            setIfNotNull(JSON_PROPERTY_TYPE_MAX_LENGTH, ((PropertyStringDefinition) propertyDefinition).getMaxLength(),
                    result);
        } else if (propertyDefinition instanceof PropertyIdDefinition) {
            // nothing to do
        } else if (propertyDefinition instanceof PropertyIntegerDefinition) {
            setIfNotNull(JSON_PROPERTY_TYPE_MIN_VALUE, ((PropertyIntegerDefinition) propertyDefinition).getMinValue(),
                    result);
            setIfNotNull(JSON_PROPERTY_TYPE_MAX_VALUE, ((PropertyIntegerDefinition) propertyDefinition).getMaxValue(),
                    result);
        } else if (propertyDefinition instanceof PropertyDecimalDefinition) {
            setIfNotNull(JSON_PROPERTY_TYPE_MIN_VALUE, ((PropertyDecimalDefinition) propertyDefinition).getMinValue(),
                    result);
            setIfNotNull(JSON_PROPERTY_TYPE_MAX_VALUE, ((PropertyDecimalDefinition) propertyDefinition).getMaxValue(),
                    result);
            DecimalPrecision precision = ((PropertyDecimalDefinition) propertyDefinition).getPrecision();
            if (precision != null) {
                result.put(JSON_PROPERTY_TYPE_PRECISION, String.valueOf(precision.value().intValue()));
            }
        } else if (propertyDefinition instanceof PropertyBooleanDefinition) {
            // nothing to do
        } else if (propertyDefinition instanceof PropertyDateTimeDefinition) {
            DateTimeResolution resolution = ((PropertyDateTimeDefinition) propertyDefinition).getDateTimeResolution();
            if (resolution != null) {
                result.put(JSON_PROPERTY_TYPE_RESOLUTION, resolution.value());
            }
        } else if (propertyDefinition instanceof PropertyHtmlDefinition) {
            // nothing to do
        } else if (propertyDefinition instanceof PropertyUriDefinition) {
            // nothing to do
        } else {
            assert false;
        }

        // default value
        if (propertyDefinition.getDefaultValue() != null) {
            if (propertyDefinition.getCardinality() == Cardinality.SINGLE) {
                if (!propertyDefinition.getDefaultValue().isEmpty()) {
                    result.put(JSON_PROPERTY_TYPE_DEAULT_VALUE,
                            getJSONValue(propertyDefinition.getDefaultValue().get(0), dateTimeFormat));
                }
            } else {
                JSONArray values = new JSONArray();
                for (Object value : propertyDefinition.getDefaultValue()) {
                    values.add(getJSONValue(value, dateTimeFormat));
                }
                result.put(JSON_PROPERTY_TYPE_DEAULT_VALUE, values);
            }
        }

        // choices
        if (isNotEmpty(propertyDefinition.getChoices())) {
            result.put(JSON_PROPERTY_TYPE_CHOICE, convertChoices(propertyDefinition.getChoices(),
                    propertyDefinition.getCardinality(), dateTimeFormat));
        }

        // generic
        result.put(JSON_PROPERTY_TYPE_ID, propertyDefinition.getId());
        result.put(JSON_PROPERTY_TYPE_LOCALNAME, propertyDefinition.getLocalName());
        setIfNotNull(JSON_PROPERTY_TYPE_LOCALNAMESPACE, propertyDefinition.getLocalNamespace(), result);
        setIfNotNull(JSON_PROPERTY_TYPE_DISPLAYNAME, propertyDefinition.getDisplayName(), result);
        setIfNotNull(JSON_PROPERTY_TYPE_QUERYNAME, propertyDefinition.getQueryName(), result);
        setIfNotNull(JSON_PROPERTY_TYPE_DESCRIPTION, propertyDefinition.getDescription(), result);
        result.put(JSON_PROPERTY_TYPE_PROPERTY_TYPE, getJSONEnumValue(propertyDefinition.getPropertyType()));
        result.put(JSON_PROPERTY_TYPE_CARDINALITY, getJSONEnumValue(propertyDefinition.getCardinality()));
        result.put(JSON_PROPERTY_TYPE_UPDATABILITY, getJSONEnumValue(propertyDefinition.getUpdatability()));
        setIfNotNull(JSON_PROPERTY_TYPE_INHERITED, propertyDefinition.isInherited(), result);
        result.put(JSON_PROPERTY_TYPE_REQUIRED, propertyDefinition.isRequired());
        result.put(JSON_PROPERTY_TYPE_QUERYABLE, propertyDefinition.isQueryable());
        result.put(JSON_PROPERTY_TYPE_ORDERABLE, propertyDefinition.isOrderable());
        setIfNotNull(JSON_PROPERTY_TYPE_OPENCHOICE, propertyDefinition.isOpenChoice(), result);

        convertExtension(propertyDefinition, result);

        return result;
    }

    /**
     * Converts choices.
     */
    private static <T> JSONArray convertChoices(final List<Choice<T>> choices, final Cardinality cardinality,
            final DateTimeFormat dateTimeFormat) {
        assert cardinality != null;

        if (choices == null) {
            return null;
        }

        JSONArray result = new JSONArray();

        for (Choice<?> choice : choices) {
            JSONObject jsonChoice = new JSONObject();

            jsonChoice.put(JSON_PROPERTY_TYPE_CHOICE_DISPLAYNAME, choice.getDisplayName());

            if (cardinality == Cardinality.SINGLE) {
                if (!choice.getValue().isEmpty()) {
                    jsonChoice.put(JSON_PROPERTY_TYPE_CHOICE_VALUE,
                            getJSONValue(choice.getValue().get(0), dateTimeFormat));
                }
            } else {
                JSONArray values = new JSONArray();
                for (Object value : choice.getValue()) {
                    values.add(getJSONValue(value, dateTimeFormat));
                }
                jsonChoice.put(JSON_PROPERTY_TYPE_CHOICE_VALUE, values);
            }

            if (isNotEmpty(choice.getChoice())) {
                jsonChoice.put(JSON_PROPERTY_TYPE_CHOICE_CHOICE,
                        convertChoices(choice.getChoice(), cardinality, dateTimeFormat));
            }

            result.add(jsonChoice);
        }

        return result;
    }

    /**
     * Converts a type definition list.
     */
    public static JSONObject convert(final TypeDefinitionList list, final DateTimeFormat dateTimeFormat) {
        if (list == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        if (list.getList() != null) {
            JSONArray objects = new JSONArray();

            for (TypeDefinition type : list.getList()) {
                objects.add(convert(type, dateTimeFormat));
            }

            result.put(JSON_TYPESLIST_TYPES, objects);
        }

        setIfNotNull(JSON_TYPESLIST_HAS_MORE_ITEMS, list.hasMoreItems(), result);
        setIfNotNull(JSON_TYPESLIST_NUM_ITEMS, list.getNumItems(), result);

        convertExtension(list, result);

        return result;
    }

    /**
     * Converts a type definition list.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static TypeDefinitionList convertTypeChildren(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        TypeDefinitionListImpl result = new TypeDefinitionListImpl();

        Object typesList = json.get(JSON_TYPESLIST_TYPES);
        List<TypeDefinition> types = new ArrayList<TypeDefinition>();

        if (typesList instanceof List) {
            for (Object type : (List) typesList) {
                if (type instanceof Map) {
                    types.add(convertTypeDefinition((Map<String, Object>) type));
                }
            }
        }

        result.setList(types);
        result.setHasMoreItems(getBoolean(json, JSON_TYPESLIST_HAS_MORE_ITEMS));
        result.setNumItems(getInteger(json, JSON_TYPESLIST_NUM_ITEMS));

        convertExtension(json, result, TYPESLIST_KEYS);

        return result;
    }

    /**
     * Converts a type definition container.
     */
    public static JSONObject convert(final TypeDefinitionContainer container, final DateTimeFormat dateTimeFormat) {
        if (container == null) {
            return null;
        }

        JSONObject result = new JSONObject();
        result.put(JSON_TYPESCONTAINER_TYPE, convert(container.getTypeDefinition(), dateTimeFormat));

        if (isNotEmpty(container.getChildren())) {
            JSONArray children = new JSONArray();
            for (TypeDefinitionContainer child : container.getChildren()) {
                children.add(convert(child, dateTimeFormat));
            }

            result.put(JSON_TYPESCONTAINER_CHILDREN, children);
        }

        convertExtension(container, result);

        return result;
    }

    /**
     * Converts a type definition list.
     */
    @SuppressWarnings({ "unchecked" })
    public static List<TypeDefinitionContainer> convertTypeDescendants(final List<Object> json) {
        if (json == null) {
            return null;
        }

        if (json.isEmpty()) {
            return Collections.emptyList();
        }

        List<TypeDefinitionContainer> result = new ArrayList<TypeDefinitionContainer>();

        for (Object obj : json) {
            if (obj instanceof Map) {
                Map<String, Object> jsonContainer = (Map<String, Object>) obj;
                TypeDefinitionContainerImpl container = new TypeDefinitionContainerImpl();

                container.setTypeDefinition(convertTypeDefinition(getMap(jsonContainer.get(JSON_TYPESCONTAINER_TYPE))));

                Object children = jsonContainer.get(JSON_TYPESCONTAINER_CHILDREN);
                if (children instanceof List) {
                    container.setChildren(convertTypeDescendants((List<Object>) children));
                } else {
                    container.setChildren(Collections.<TypeDefinitionContainer> emptyList());
                }

                convertExtension(jsonContainer, container, TYPESCONTAINER_KEYS);

                result.add(container);
            }
        }

        return result;
    }

    /**
     * Converts an object.
     */
    public static ObjectData convertObject(final Map<String, Object> json, final TypeCache typeCache) {
        if (json == null) {
            return null;
        }

        ObjectDataImpl result = new ObjectDataImpl();

        result.setAcl(convertAcl(getMap(json.get(JSON_OBJECT_ACL))));
        result.setAllowableActions(convertAllowableActions(getMap(json.get(JSON_OBJECT_ALLOWABLE_ACTIONS))));
        Map<String, Object> jsonChangeEventInfo = getMap(json.get(JSON_OBJECT_CHANGE_EVENT_INFO));
        if (jsonChangeEventInfo != null) {
            ChangeEventInfoDataImpl changeEventInfo = new ChangeEventInfoDataImpl();

            changeEventInfo.setChangeTime(getDateTime(jsonChangeEventInfo, JSON_CHANGE_EVENT_TIME));
            changeEventInfo.setChangeType(getEnum(jsonChangeEventInfo, JSON_CHANGE_EVENT_TYPE, ChangeType.class));

            convertExtension(json, result, CHANGE_EVENT_KEYS);

            result.setChangeEventInfo(changeEventInfo);
        }
        result.setIsExactAcl(getBoolean(json, JSON_OBJECT_EXACT_ACL));
        result.setPolicyIds(convertPolicyIds(getMap(json.get(JSON_OBJECT_POLICY_IDS))));

        Map<String, Object> propMap = getMap(json.get(JSON_OBJECT_SUCCINCT_PROPERTIES));
        if (propMap != null) {
            result.setProperties(
                    convertSuccinctProperties(propMap, getMap(json.get(JSON_OBJECT_PROPERTIES_EXTENSION)), typeCache));
        }
        propMap = getMap(json.get(JSON_OBJECT_PROPERTIES));
        if (propMap != null) {
            result.setProperties(convertProperties(propMap, getMap(json.get(JSON_OBJECT_PROPERTIES_EXTENSION))));
        }

        List<Object> jsonRelationships = getList(json.get(JSON_OBJECT_RELATIONSHIPS));
        if (jsonRelationships != null) {
            result.setRelationships(convertObjects(jsonRelationships, typeCache));
        }
        List<Object> jsonRenditions = getList(json.get(JSON_OBJECT_RENDITIONS));
        if (jsonRenditions != null) {
            result.setRenditions(convertRenditions(jsonRenditions));
        }

        convertExtension(json, result, OBJECT_KEYS);

        return result;
    }

    /**
     * Converts an object.
     */
    public static List<ObjectData> convertObjects(final List<Object> json, final TypeCache typeCache) {
        if (json == null) {
            return null;
        }

        List<ObjectData> result = new ArrayList<ObjectData>();
        for (Object obj : json) {
            ObjectData relationship = convertObject(getMap(obj), typeCache);
            if (relationship != null) {
                result.add(relationship);
            }
        }

        return result;
    }

    /**
     * Converts an ACL.
     */
    public static Acl convertAcl(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        AccessControlListImpl result = new AccessControlListImpl();

        List<Ace> aces = new ArrayList<Ace>();

        List<Object> jsonAces = getList(json.get(JSON_ACL_ACES));
        if (jsonAces != null) {
            for (Object obj : jsonAces) {
                Map<String, Object> entry = getMap(obj);
                if (entry != null) {
                    AccessControlEntryImpl ace = new AccessControlEntryImpl();

                    Boolean isDirect = getBoolean(entry, JSON_ACE_IS_DIRECT);
                    ace.setDirect(isDirect != null ? isDirect.booleanValue() : true);

                    List<Object> jsonPermissions = getList(entry.get(JSON_ACE_PERMISSIONS));
                    if (jsonPermissions != null) {
                        List<String> permissions = new ArrayList<String>(5);
                        for (Object perm : jsonPermissions) {
                            if (perm != null) {
                                permissions.add(perm.toString());
                            }
                        }
                        ace.setPermissions(permissions);
                    }

                    Map<String, Object> jsonPrincipal = getMap(entry.get(JSON_ACE_PRINCIPAL));
                    if (jsonPrincipal != null) {
                        AccessControlPrincipalDataImpl principal = new AccessControlPrincipalDataImpl();

                        principal.setId(getString(jsonPrincipal, JSON_ACE_PRINCIPAL_ID));

                        convertExtension(jsonPrincipal, principal, PRINCIPAL_KEYS);

                        ace.setPrincipal(principal);
                    }

                    convertExtension(entry, ace, ACE_KEYS);

                    aces.add(ace);
                }
            }
        }

        result.setAces(aces);

        result.setExact(getBoolean(json, JSON_ACL_IS_EXACT));

        convertExtension(json, result, ACL_KEYS);

        return result;
    }

    /**
     * Converts allowable actions.
     */
    public static AllowableActions convertAllowableActions(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        AllowableActionsImpl result = new AllowableActionsImpl();
        Set<Action> allowableActions = EnumSet.noneOf(Action.class);

        for (Action action : Action.values()) {
            Boolean value = getBoolean(json, action.value());
            if (value != null && value.booleanValue()) {
                allowableActions.add(action);
            }
        }

        result.setAllowableActions(allowableActions);

        convertExtension(json, result, ALLOWABLE_ACTIONS_KEYS);

        return result;
    }

    /**
     * Converts a list of policy ids.
     */
    public static PolicyIdList convertPolicyIds(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        PolicyIdListImpl result = new PolicyIdListImpl();
        List<String> policyIds = new ArrayList<String>();

        List<Object> ids = getList(json.get(JSON_OBJECT_POLICY_IDS_IDS));

        if (ids != null) {
            for (Object obj : ids) {
                if (obj instanceof String) {
                    policyIds.add((String) obj);
                }
            }
        }

        convertExtension(json, result, POLICY_IDS_KEYS);

        result.setPolicyIds(policyIds);

        return result;
    }

    /**
     * Converts properties.
     */
    @SuppressWarnings("unchecked")
    public static Properties convertProperties(final Map<String, Object> json, final Map<String, Object> extJson) {
        if (json == null) {
            return null;
        }

        PropertiesImpl result = new PropertiesImpl();

        for (Object jsonProperty : json.values()) {
            Map<String, Object> jsonPropertyMap = getMap(jsonProperty);
            if (jsonPropertyMap != null) {
                AbstractPropertyData<?> property = null;

                String id = getString(jsonPropertyMap, JSON_PROPERTY_ID);
                String queryName = getString(jsonPropertyMap, JSON_PROPERTY_QUERYNAME);
                if (id == null && queryName == null) {
                    throw new CmisRuntimeException(
                            "Invalid property! Neither a property ID nor a query name is provided!");
                }

                PropertyType propertyType = null;
                try {
                    propertyType = PropertyType.fromValue(getString(jsonPropertyMap, JSON_PROPERTY_DATATYPE));
                } catch (Exception e) {
                    throw new CmisRuntimeException("Invalid property datatype: " + id, e);
                }

                Object value = jsonPropertyMap.get(JSON_PROPERTY_VALUE);
                List<Object> values = null;
                if (value instanceof List) {
                    values = (List<Object>) value;
                } else if (value != null) {
                    values = Collections.singletonList(value);
                }

                switch (propertyType) {
                case STRING:
                    property = new PropertyStringImpl();
                    ((PropertyStringImpl) property).setValues(copyStringValues(values));
                    break;
                case ID:
                    property = new PropertyIdImpl();
                    ((PropertyIdImpl) property).setValues(copyStringValues(values));
                    break;
                case BOOLEAN:
                    property = new PropertyBooleanImpl();
                    ((PropertyBooleanImpl) property).setValues(copyBooleanValues(values));
                    break;
                case INTEGER:
                    property = new PropertyIntegerImpl();
                    ((PropertyIntegerImpl) property).setValues(copyIntegerValues(values));
                    break;
                case DECIMAL:
                    property = new PropertyDecimalImpl();
                    ((PropertyDecimalImpl) property).setValues(copyDecimalValues(values));
                    break;
                case DATETIME:
                    property = new PropertyDateTimeImpl();
                    ((PropertyDateTimeImpl) property).setValues(copyDateTimeValues(values));
                    break;
                case HTML:
                    property = new PropertyHtmlImpl();
                    ((PropertyHtmlImpl) property).setValues(copyStringValues(values));
                    break;
                case URI:
                    property = new PropertyUriImpl();
                    ((PropertyUriImpl) property).setValues(copyStringValues(values));
                    break;
                default:
                    throw new CmisRuntimeException("Unknown property data type!");
                }

                property.setId(id);
                property.setDisplayName(getString(jsonPropertyMap, JSON_PROPERTY_DISPLAYNAME));
                property.setQueryName(queryName);
                property.setLocalName(getString(jsonPropertyMap, JSON_PROPERTY_LOCALNAME));

                convertExtension(jsonPropertyMap, property, PROPERTY_KEYS);

                result.addProperty(property);
            }
        }

        if (extJson != null) {
            convertExtension(extJson, result, Collections.<String> emptySet());
        }

        return result;
    }

    /**
     * Converts properties.
     */
    @SuppressWarnings("unchecked")
    public static Properties convertSuccinctProperties(final Map<String, Object> json,
            final Map<String, Object> extJson, final TypeCache typeCache) {
        if (json == null) {
            return null;
        }

        TypeDefinition typeDef = null;
        if (json.get(PropertyIds.OBJECT_TYPE_ID) instanceof String) {
            typeDef = typeCache.getTypeDefinition((String) json.get(PropertyIds.OBJECT_TYPE_ID));
        }

        List<Object> secTypeIds = getList(json.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS));
        List<TypeDefinition> secTypeDefs = null;
        if (isNotEmpty(secTypeIds)) {
            secTypeDefs = new ArrayList<TypeDefinition>(secTypeIds.size());
            for (Object secTypeId : secTypeIds) {
                if (secTypeId instanceof String) {
                    secTypeDefs.add(typeCache.getTypeDefinition((String) secTypeId));
                }
            }
        }

        PropertiesImpl result = new PropertiesImpl();

        for (Map.Entry<String, Object> entry : json.entrySet()) {
            String id = entry.getKey();

            PropertyDefinition<?> propDef = null;
            if (typeDef != null) {
                propDef = typeDef.getPropertyDefinitions().get(id);
            }

            if (propDef == null && secTypeDefs != null) {
                for (TypeDefinition secTypeDef : secTypeDefs) {
                    if (secTypeDef != null && secTypeDef.getPropertyDefinitions() != null) {
                        propDef = secTypeDef.getPropertyDefinitions().get(id);
                        if (propDef != null) {
                            break;
                        }
                    }
                }
            }

            if (propDef == null) {
                propDef = typeCache.getTypeDefinition(BaseTypeId.CMIS_DOCUMENT.value()).getPropertyDefinitions()
                        .get(id);
            }

            if (propDef == null) {
                propDef = typeCache.getTypeDefinition(BaseTypeId.CMIS_FOLDER.value()).getPropertyDefinitions().get(id);
            }

            if (propDef == null && typeDef != null) {
                TypeDefinition reloadedTypeDef = typeCache.reloadTypeDefinition(typeDef.getId());
                if (reloadedTypeDef != null) {
                    propDef = reloadedTypeDef.getPropertyDefinitions().get(id);
                }
            }

            if (propDef == null && secTypeDefs != null) {
                for (TypeDefinition secTypeDef : secTypeDefs) {
                    TypeDefinition reloadedTypeDef = typeCache.reloadTypeDefinition(secTypeDef.getId());
                    if (reloadedTypeDef != null && reloadedTypeDef.getPropertyDefinitions() != null) {
                        propDef = reloadedTypeDef.getPropertyDefinitions().get(id);
                        if (propDef != null) {
                            break;
                        }
                    }
                }
            }

            List<Object> values = null;
            if (entry.getValue() instanceof List) {
                values = (List<Object>) entry.getValue();
            } else if (entry.getValue() != null) {
                values = Collections.singletonList(entry.getValue());
            }

            AbstractPropertyData<?> property = null;

            if (propDef != null) {
                switch (propDef.getPropertyType()) {
                case STRING:
                    property = new PropertyStringImpl();
                    ((PropertyStringImpl) property).setValues(copyStringValues(values));
                    break;
                case ID:
                    property = new PropertyIdImpl();
                    ((PropertyIdImpl) property).setValues(copyStringValues(values));
                    break;
                case BOOLEAN:
                    property = new PropertyBooleanImpl();
                    ((PropertyBooleanImpl) property).setValues(copyBooleanValues(values));
                    break;
                case INTEGER:
                    property = new PropertyIntegerImpl();
                    ((PropertyIntegerImpl) property).setValues(copyIntegerValues(values));
                    break;
                case DECIMAL:
                    property = new PropertyDecimalImpl();
                    ((PropertyDecimalImpl) property).setValues(copyDecimalValues(values));
                    break;
                case DATETIME:
                    property = new PropertyDateTimeImpl();
                    ((PropertyDateTimeImpl) property).setValues(copyDateTimeValues(values));
                    break;
                case HTML:
                    property = new PropertyHtmlImpl();
                    ((PropertyHtmlImpl) property).setValues(copyStringValues(values));
                    break;
                case URI:
                    property = new PropertyUriImpl();
                    ((PropertyUriImpl) property).setValues(copyStringValues(values));
                    break;
                default:
                    throw new CmisRuntimeException("Unknown property type!");
                }

                property.setId(id);
                property.setDisplayName(propDef.getDisplayName());
                property.setQueryName(propDef.getQueryName());
                property.setLocalName(propDef.getLocalName());
            } else {
                // this else block should only be reached in rare circumstances
                // it may return incorrect types

                if (values == null) {
                    property = new PropertyStringImpl();
                    ((PropertyStringImpl) property).setValues(null);
                } else {
                    Object firstValue = values.get(0);
                    if (firstValue instanceof Boolean) {
                        property = new PropertyBooleanImpl();
                        ((PropertyBooleanImpl) property).setValues(copyBooleanValues(values));
                    } else if (firstValue instanceof BigInteger) {
                        property = new PropertyIntegerImpl();
                        ((PropertyIntegerImpl) property).setValues(copyIntegerValues(values));
                    } else if (firstValue instanceof BigDecimal) {
                        property = new PropertyDecimalImpl();
                        ((PropertyDecimalImpl) property).setValues(copyDecimalValues(values));
                    } else {
                        property = new PropertyStringImpl();
                        ((PropertyStringImpl) property).setValues(copyStringValues(values));
                    }
                }

                property.setId(id);
                property.setDisplayName(id);
                property.setQueryName(null);
                property.setLocalName(null);
            }

            result.addProperty(property);
        }

        if (extJson != null) {
            convertExtension(extJson, result, Collections.<String> emptySet());
        }

        return result;
    }

    private static List<String> copyStringValues(List<Object> source) {
        List<String> result = null;
        if (source != null) {
            result = new ArrayList<String>(source.size());
            for (Object obj : source) {
                if (obj instanceof String) {
                    result.add(obj.toString());
                } else {
                    throw new CmisRuntimeException("Invalid property value: " + obj);
                }
            }
        }

        return result;
    }

    private static List<Boolean> copyBooleanValues(List<Object> source) {
        List<Boolean> result = null;
        if (source != null) {
            result = new ArrayList<Boolean>(source.size());
            for (Object obj : source) {
                if (obj instanceof Boolean) {
                    result.add((Boolean) obj);
                } else {
                    throw new CmisRuntimeException("Invalid property value: " + obj);
                }
            }
        }

        return result;
    }

    private static List<BigInteger> copyIntegerValues(List<Object> source) {
        List<BigInteger> result = null;
        if (source != null) {
            result = new ArrayList<BigInteger>(source.size());
            for (Object obj : source) {
                if (obj instanceof BigInteger) {
                    result.add((BigInteger) obj);
                } else {
                    throw new CmisRuntimeException("Invalid property value: " + obj);
                }
            }
        }

        return result;
    }

    private static List<BigDecimal> copyDecimalValues(List<Object> source) {
        List<BigDecimal> result = null;
        if (source != null) {
            result = new ArrayList<BigDecimal>(source.size());
            for (Object obj : source) {
                if (obj instanceof BigDecimal) {
                    result.add((BigDecimal) obj);
                } else if (obj instanceof BigInteger) {
                    result.add(new BigDecimal((BigInteger) obj));
                } else {
                    throw new CmisRuntimeException("Invalid property value: " + obj);
                }
            }
        }

        return result;
    }

    private static List<GregorianCalendar> copyDateTimeValues(List<Object> source) {
        List<GregorianCalendar> result = null;
        if (source != null) {
            result = new ArrayList<GregorianCalendar>(source.size());
            for (Object obj : source) {
                if (obj instanceof Number) {
                    GregorianCalendar cal = new GregorianCalendar(DateTimeHelper.GMT);
                    cal.setTimeInMillis(((Number) obj).longValue());
                    result.add(cal);
                } else if (obj instanceof String) {
                    GregorianCalendar cal = DateTimeHelper.parseXmlDateTime((String) obj);
                    if (cal == null) {
                        throw new CmisRuntimeException("Invalid property value: " + obj);
                    }
                    result.add(cal);
                } else {
                    throw new CmisRuntimeException("Invalid property value: " + obj);
                }
            }
        }

        return result;
    }

    /**
     * Converts a rendition.
     */
    public static RenditionData convertRendition(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        RenditionDataImpl result = new RenditionDataImpl();

        result.setBigHeight(getInteger(json, JSON_RENDITION_HEIGHT));
        result.setKind(getString(json, JSON_RENDITION_KIND));
        result.setBigLength(getInteger(json, JSON_RENDITION_LENGTH));
        result.setMimeType(getString(json, JSON_RENDITION_MIMETYPE));
        result.setRenditionDocumentId(getString(json, JSON_RENDITION_DOCUMENT_ID));
        result.setStreamId(getString(json, JSON_RENDITION_STREAM_ID));
        result.setTitle(getString(json, JSON_RENDITION_TITLE));
        result.setBigWidth(getInteger(json, JSON_RENDITION_WIDTH));

        convertExtension(json, result, RENDITION_KEYS);

        return result;
    }

    /**
     * Converts a list of renditions.
     */
    public static List<RenditionData> convertRenditions(final List<Object> json) {
        if (json == null) {
            return null;
        }

        List<RenditionData> result = new ArrayList<RenditionData>();

        for (Object obj : json) {
            RenditionData rendition = convertRendition(getMap(obj));
            if (rendition != null) {
                result.add(rendition);
            }
        }

        return result;
    }

    /**
     * Converts a object list.
     */
    public static ObjectInFolderList convertObjectInFolderList(final Map<String, Object> json,
            final TypeCache typeCache) {
        if (json == null) {
            return null;
        }

        ObjectInFolderListImpl result = new ObjectInFolderListImpl();

        List<Object> jsonChildren = getList(json.get(JSON_OBJECTINFOLDERLIST_OBJECTS));
        List<ObjectInFolderData> objects = new ArrayList<ObjectInFolderData>();

        if (jsonChildren != null) {
            for (Object obj : jsonChildren) {
                Map<String, Object> jsonObject = getMap(obj);
                if (jsonObject != null) {
                    objects.add(convertObjectInFolder(jsonObject, typeCache));
                }
            }
        }

        result.setObjects(objects);
        result.setHasMoreItems(getBoolean(json, JSON_OBJECTINFOLDERLIST_HAS_MORE_ITEMS));
        result.setNumItems(getInteger(json, JSON_OBJECTINFOLDERLIST_NUM_ITEMS));

        convertExtension(json, result, OBJECTINFOLDERLIST_KEYS);

        return result;
    }

    /**
     * Converts an object in a folder.
     */
    public static ObjectInFolderData convertObjectInFolder(final Map<String, Object> json, final TypeCache typeCache) {
        if (json == null) {
            return null;
        }

        ObjectInFolderDataImpl result = new ObjectInFolderDataImpl();

        result.setObject(convertObject(getMap(json.get(JSON_OBJECTINFOLDER_OBJECT)), typeCache));
        result.setPathSegment(getString(json, JSON_OBJECTINFOLDER_PATH_SEGMENT));

        convertExtension(json, result, OBJECTINFOLDER_KEYS);

        return result;
    }

    /**
     * Converts a descendants tree.
     */
    public static List<ObjectInFolderContainer> convertDescendants(final List<Object> json, final TypeCache typeCache) {
        if (json == null) {
            return null;
        }

        List<ObjectInFolderContainer> result = new ArrayList<ObjectInFolderContainer>();

        for (Object obj : json) {
            Map<String, Object> desc = getMap(obj);
            if (desc != null) {
                result.add(convertDescendant(desc, typeCache));
            }
        }

        return result;
    }

    /**
     * Converts a descendant.
     */
    public static ObjectInFolderContainer convertDescendant(final Map<String, Object> json, final TypeCache typeCache) {
        if (json == null) {
            return null;
        }

        ObjectInFolderContainerImpl result = new ObjectInFolderContainerImpl();

        result.setObject(convertObjectInFolder(getMap(json.get(JSON_OBJECTINFOLDERCONTAINER_OBJECT)), typeCache));

        List<ObjectInFolderContainer> containerList = new ArrayList<ObjectInFolderContainer>();
        List<Object> jsonContainerList = getList(json.get(JSON_OBJECTINFOLDERCONTAINER_CHILDREN));
        if (jsonContainerList != null) {
            for (Object obj : jsonContainerList) {
                Map<String, Object> containerChild = getMap(obj);
                if (containerChild != null) {
                    containerList.add(convertDescendant(containerChild, typeCache));
                }
            }
        }

        result.setChildren(containerList);

        convertExtension(json, result, OBJECTINFOLDERCONTAINER_KEYS);

        return result;
    }

    /**
     * Converts an object parents list.
     */
    public static List<ObjectParentData> convertObjectParents(final List<Object> json, final TypeCache typeCache) {
        if (json == null) {
            return null;
        }

        List<ObjectParentData> result = new ArrayList<ObjectParentData>();

        for (Object obj : json) {
            Map<String, Object> jsonParent = getMap(obj);
            if (jsonParent != null) {
                ObjectParentDataImpl parent = new ObjectParentDataImpl();

                parent.setObject(convertObject(getMap(jsonParent.get(JSON_OBJECTPARENTS_OBJECT)), typeCache));
                parent.setRelativePathSegment(getString(jsonParent, JSON_OBJECTPARENTS_RELATIVE_PATH_SEGMENT));

                convertExtension(jsonParent, parent, OBJECTPARENTS_KEYS);

                result.add(parent);
            }
        }

        return result;
    }

    /**
     * Converts a object list.
     */
    public static ObjectList convertObjectList(final Map<String, Object> json, final TypeCache typeCache,
            final boolean isQueryResult) {
        if (json == null) {
            return null;
        }

        ObjectListImpl result = new ObjectListImpl();

        List<Object> jsonChildren = getList(
                json.get(isQueryResult ? JSON_QUERYRESULTLIST_RESULTS : JSON_OBJECTLIST_OBJECTS));
        List<ObjectData> objects = new ArrayList<ObjectData>();

        if (jsonChildren != null) {
            for (Object obj : jsonChildren) {
                Map<String, Object> jsonObject = getMap(obj);
                if (jsonObject != null) {
                    objects.add(convertObject(jsonObject, typeCache));
                }
            }
        }

        result.setObjects(objects);

        if (isQueryResult) {
            result.setHasMoreItems(getBoolean(json, JSON_QUERYRESULTLIST_HAS_MORE_ITEMS));
            result.setNumItems(getInteger(json, JSON_QUERYRESULTLIST_NUM_ITEMS));
            convertExtension(json, result, QUERYRESULTLIST_KEYS);
        } else {
            result.setHasMoreItems(getBoolean(json, JSON_OBJECTLIST_HAS_MORE_ITEMS));
            result.setNumItems(getInteger(json, JSON_OBJECTLIST_NUM_ITEMS));
            convertExtension(json, result, OBJECTLIST_KEYS);
        }

        return result;
    }

    // -----------------------------------------------------------------

    /**
     * Converts FailedToDelete ids.
     */
    public static JSONObject convert(final FailedToDeleteData ftd) {
        if (ftd == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        JSONArray ids = new JSONArray();
        if (ftd.getIds() != null) {
            for (String id : ftd.getIds()) {
                ids.add(id);
            }
        }

        result.put(JSON_FAILEDTODELETE_ID, ids);

        convertExtension(ftd, result);

        return result;
    }

    /**
     * Converts FailedToDelete ids.
     */
    public static FailedToDeleteData convertFailedToDelete(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        FailedToDeleteDataImpl result = new FailedToDeleteDataImpl();

        List<String> ids = new ArrayList<String>();
        List<Object> jsonIds = getList(json.get(JSON_FAILEDTODELETE_ID));

        if (jsonIds != null) {
            for (Object obj : jsonIds) {
                if (obj != null) {
                    ids.add(obj.toString());
                }
            }
        }

        result.setIds(ids);

        convertExtension(json, result, FAILEDTODELETE_KEYS);

        return result;
    }

    // -----------------------------------------------------------------

    /**
     * Converts bulk update data.
     */
    public static JSONObject convert(BulkUpdateObjectIdAndChangeToken oc) {
        if (oc == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        setIfNotNull(JSON_BULK_UPDATE_ID, oc.getId(), result);
        setIfNotNull(JSON_BULK_UPDATE_NEW_ID, oc.getNewId(), result);
        setIfNotNull(JSON_BULK_UPDATE_CHANGE_TOKEN, oc.getChangeToken(), result);

        convertExtension(oc, result);

        return result;
    }

    /**
     * Converts bulk update data lists.
     */
    public static List<BulkUpdateObjectIdAndChangeToken> convertBulkUpdate(final List<Object> json) {
        if (json == null) {
            return null;
        }

        List<BulkUpdateObjectIdAndChangeToken> result = new ArrayList<BulkUpdateObjectIdAndChangeToken>();

        for (Object ocJson : json) {
            BulkUpdateObjectIdAndChangeToken oc = convertBulkUpdate(getMap(ocJson));
            if (oc != null) {
                result.add(oc);
            }
        }

        return result;
    }

    /**
     * Converts bulk update data.
     */
    public static BulkUpdateObjectIdAndChangeToken convertBulkUpdate(final Map<String, Object> json) {
        if (json == null) {
            return null;
        }

        String id = getString(json, JSON_BULK_UPDATE_ID);
        String newId = getString(json, JSON_BULK_UPDATE_NEW_ID);
        String changeToken = getString(json, JSON_BULK_UPDATE_CHANGE_TOKEN);

        BulkUpdateObjectIdAndChangeTokenImpl result = new BulkUpdateObjectIdAndChangeTokenImpl(id, newId, changeToken);

        convertExtension(json, result, BULK_UPDATE_KEYS);

        return result;
    }

    // -----------------------------------------------------------------

    public static void convertExtension(final ExtensionsData source, final JSONObject target) {
        if (source == null || source.getExtensions() == null) {
            return;
        }

        for (CmisExtensionElement ext : source.getExtensions()) {
            addExtensionToTarget(ext, target);
        }
    }

    private static JSONObject convertExtensionList(final List<CmisExtensionElement> extensionList) {
        if (extensionList == null) {
            return null;
        }

        JSONObject result = new JSONObject();

        for (CmisExtensionElement ext : extensionList) {
            addExtensionToTarget(ext, result);
        }

        return result;
    }

    private static void addExtensionToTarget(final CmisExtensionElement ext, final JSONObject target) {
        if (ext == null) {
            return;
        }

        Object value = null;

        if (isNotEmpty(ext.getChildren())) {
            value = convertExtensionList(ext.getChildren());
        } else {
            value = ext.getValue();
        }

        if (!target.containsKey(ext.getName())) {
            target.put(ext.getName(), value);
        } else {
            Object extValue = target.get(ext.getName());

            JSONArray array;
            if (extValue instanceof JSONArray) {
                array = (JSONArray) extValue;
            } else {
                array = new JSONArray();
                array.add(extValue);
            }

            array.add(value);

            target.put(ext.getName(), array);
        }
    }

    @SuppressWarnings("unchecked")
    public static void convertExtension(final Map<String, Object> source, final ExtensionsData target,
            final Set<String> cmisKeys) {
        if (source == null) {
            return;
        }

        List<CmisExtensionElement> extensions = null;

        for (Map.Entry<String, Object> element : source.entrySet()) {
            if (cmisKeys.contains(element.getKey())) {
                continue;
            }

            if (extensions == null) {
                extensions = new ArrayList<CmisExtensionElement>();
            }

            if (element.getValue() instanceof Map) {
                extensions.add(new CmisExtensionElementImpl(null, element.getKey(), null,
                        convertExtension((Map<String, Object>) element.getValue())));
            } else if (element.getValue() instanceof List) {
                extensions.addAll(convertExtension(element.getKey(), (List<Object>) element.getValue()));
            } else {
                String value = element.getValue() == null ? null : element.getValue().toString();
                extensions.add(new CmisExtensionElementImpl(null, element.getKey(), null, value));
            }
        }

        target.setExtensions(extensions);
    }

    @SuppressWarnings("unchecked")
    public static List<CmisExtensionElement> convertExtension(final Map<String, Object> map) {
        if (map == null) {
            return null;
        }

        List<CmisExtensionElement> extensions = new ArrayList<CmisExtensionElement>();

        for (Map.Entry<String, Object> element : map.entrySet()) {
            if (element.getValue() instanceof Map) {
                extensions.add(new CmisExtensionElementImpl(null, element.getKey(), null,
                        convertExtension((Map<String, Object>) element.getValue())));
            } else if (element.getValue() instanceof List) {
                extensions.addAll(convertExtension(element.getKey(), (List<Object>) element.getValue()));
            } else {
                String value = element.getValue() == null ? null : element.getValue().toString();
                extensions.add(new CmisExtensionElementImpl(null, element.getKey(), null, value));
            }
        }

        return extensions;
    }

    @SuppressWarnings("unchecked")
    public static List<CmisExtensionElement> convertExtension(final String name, final List<Object> list) {
        if (list == null) {
            return null;
        }

        List<CmisExtensionElement> extensions = new ArrayList<CmisExtensionElement>();

        for (Object element : list) {
            if (element instanceof Map) {
                extensions.add(new CmisExtensionElementImpl(null, name, null,
                        convertExtension((Map<String, Object>) element)));
            } else if (element instanceof List) {
                extensions.addAll(convertExtension(name, (List<Object>) element));
            } else {
                String value = element == null ? null : element.toString();
                extensions.add(new CmisExtensionElementImpl(null, name, null, value));
            }
        }

        return extensions;
    }

    // -----------------------------------------------------------------

    public static String getJSONStringValue(final Object obj) {
        if (obj == null) {
            return null;
        }

        return obj.toString();
    }

    public static Object getJSONValue(final Object value, final DateTimeFormat dateTimeFormat) {
        if (value instanceof GregorianCalendar) {
            if (dateTimeFormat == DateTimeFormat.EXTENDED) {
                return DateTimeHelper.formatXmlDateTime((GregorianCalendar) value);
            } else {
                return ((GregorianCalendar) value).getTimeInMillis();
            }
        }

        return value;
    }

    public static String getJSONEnumValue(final Object obj) {
        if (obj == null) {
            return null;
        }

        try {
            Method m = obj.getClass().getMethod("value", new Class[0]);
            return (String) m.invoke(obj, new Object[0]);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException) {
                return null;
            } else {
                throw new CmisRuntimeException("Could not get enum value!", e);
            }
        }
    }

    public static Object getCMISValue(final Object value, final PropertyType propertyType) {
        if (value == null) {
            return null;
        }

        switch (propertyType) {
        case STRING:
        case ID:
        case HTML:
        case URI:
            if (value instanceof String) {
                return value;
            }
            throw new CmisRuntimeException("Invalid String value!");
        case BOOLEAN:
            if (value instanceof Boolean) {
                return value;
            }
            throw new CmisRuntimeException("Invalid Boolean value!");
        case INTEGER:
            if (value instanceof BigInteger) {
                return value;
            }
            throw new CmisRuntimeException("Invalid Integer value!");
        case DECIMAL:
            if (value instanceof BigDecimal) {
                return value;
            }
            throw new CmisRuntimeException("Invalid Decimal value!");
        case DATETIME:
            if (value instanceof Number) {
                GregorianCalendar cal = new GregorianCalendar(DateTimeHelper.GMT);
                cal.setTimeInMillis(((Number) value).longValue());
                return cal;
            } else if (value instanceof String) {
                GregorianCalendar cal = DateTimeHelper.parseXmlDateTime((String) value);
                if (cal == null) {
                    throw new CmisRuntimeException("Invalid DateTime value!");
                }
                return cal;
            }
            throw new CmisRuntimeException("Invalid DateTime value!");
        default:
        }

        throw new CmisRuntimeException("Unkown property type!");
    }

    public static JSONArray getJSONArrayFromList(final List<?> list) {
        if (list == null) {
            return null;
        }

        JSONArray result = new JSONArray();
        result.addAll(list);

        return result;
    }

    public static String getJSONPropertyDataType(final PropertyData<?> property) {
        if (property instanceof PropertyBoolean) {
            return PropertyType.BOOLEAN.value();
        } else if (property instanceof PropertyId) {
            return PropertyType.ID.value();
        } else if (property instanceof PropertyInteger) {
            return PropertyType.INTEGER.value();
        } else if (property instanceof PropertyDateTime) {
            return PropertyType.DATETIME.value();
        } else if (property instanceof PropertyDecimal) {
            return PropertyType.DECIMAL.value();
        } else if (property instanceof PropertyHtml) {
            return PropertyType.HTML.value();
        } else if (property instanceof PropertyString) {
            return PropertyType.STRING.value();
        } else if (property instanceof PropertyUri) {
            return PropertyType.URI.value();
        }

        return null;
    }

    public static void setIfNotNull(String name, Object obj, JSONObject json) {
        if (obj != null) {
            json.put(name, obj);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(final Object o) {
        if (o == null) {
            return null;
        }

        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }

        throw new CmisRuntimeException("Expected a JSON object but found a "
                + (o instanceof List ? "JSON array" : o.getClass().getSimpleName()) + ": " + o.toString());
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(final Object o) {
        if (o == null) {
            return null;
        }

        if (o instanceof List) {
            return (List<Object>) o;
        }

        throw new CmisRuntimeException("Expected a JSON array but found a "
                + (o instanceof List ? "JSON object" : o.getClass().getSimpleName()) + ": " + o.toString());
    }

    public static String getString(final Map<String, Object> json, final String key) {
        Object obj = json.get(key);
        return obj == null ? null : obj.toString();
    }

    public static Boolean getBoolean(final Map<String, Object> json, final String key) {
        Object obj = json.get(key);

        if (obj instanceof Boolean) {
            return (Boolean) obj;
        }

        return null;
    }

    public static BigInteger getInteger(final Map<String, Object> json, final String key) {
        Object obj = json.get(key);

        if (obj instanceof BigInteger) {
            return (BigInteger) obj;
        }

        return null;
    }

    public static BigDecimal getDecimal(final Map<String, Object> json, final String key) {
        Object obj = json.get(key);

        if (obj instanceof BigDecimal) {
            return (BigDecimal) obj;
        }

        if (obj instanceof BigInteger) {
            return new BigDecimal((BigInteger) obj);
        }

        return null;
    }

    public static GregorianCalendar getDateTime(final Map<String, Object> json, final String key) {
        Object obj = json.get(key);

        if (obj instanceof Number) {
            GregorianCalendar cal = new GregorianCalendar(DateTimeHelper.GMT);
            cal.setTimeInMillis(((Number) obj).longValue());
            return cal;
        } else if (obj instanceof String) {
            return DateTimeHelper.parseXmlDateTime((String) obj);
        }

        return null;
    }

    public static <T extends Enum<T>> T getEnum(final Map<String, Object> json, final String key,
            final Class<T> clazz) {
        return CmisEnumHelper.fromValue(getString(json, key), clazz);
    }

    public static <T extends Enum<T>> T getIntEnum(final Map<String, Object> json, final String key,
            final Class<T> clazz) {
        return CmisEnumHelper.fromValue(getInteger(json, key), clazz);
    }
}
