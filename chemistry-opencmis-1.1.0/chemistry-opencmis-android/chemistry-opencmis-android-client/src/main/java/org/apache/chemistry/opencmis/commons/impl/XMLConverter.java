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

import static org.apache.chemistry.opencmis.commons.impl.XMLConstants.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.namespace.QName;

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
import org.apache.chemistry.opencmis.commons.data.NewTypeSettableAttributes;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.PolicyIdList;
import org.apache.chemistry.opencmis.commons.data.Principal;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyBoolean;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
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
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.DateTimeResolution;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
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
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateObjectIdAndChangeTokenImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChangeEventInfoDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChoiceImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CreatablePropertyTypesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ExtensionFeatureImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.NewTypeSettableAttributesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
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
import org.apache.chemistry.opencmis.commons.impl.dataobjects.QueryTypeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RenditionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryCapabilitiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryInfoImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeMutabilityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

public final class XMLConverter {

    private static final Logger LOG = LoggerFactory.getLogger(XMLConverter.class);

    private XMLConverter() {
    }

    // ---------------
    // --- writers ---
    // ---------------

    public static void writeRepositoryInfo(XmlSerializer writer, CmisVersion cmisVersion, String namespace,
            RepositoryInfo source) throws IOException {
        if (source == null) {
            return;
        }

        writer.startTag(namespace, TAG_REPOSITORY_INFO);

        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_ID, source.getId());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_NAME, source.getName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_DESCRIPTION, source.getDescription());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_VENDOR, source.getVendorName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_PRODUCT, source.getProductName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_PRODUCT_VERSION, source.getProductVersion());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_ROOT_FOLDER_ID, source.getRootFolderId());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_CHANGE_LOG_TOKEN,
                source.getLatestChangeLogToken());
        writeRepositoryCapabilities(writer, cmisVersion, source.getCapabilities());
        writeAclCapabilities(writer, cmisVersion, source.getAclCapabilities());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_CMIS_VERSION_SUPPORTED,
                source.getCmisVersionSupported());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_THIN_CLIENT_URI, source.getThinClientUri());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_CHANGES_INCOMPLETE,
                source.getChangesIncomplete());
        if (source.getChangesOnType() != null) {
            for (BaseTypeId baseType : source.getChangesOnType()) {
                if (cmisVersion == CmisVersion.CMIS_1_0 && baseType == BaseTypeId.CMIS_ITEM) {
                    LOG.warn("Receiver only understands CMIS 1.0 but the Changes On Type list in the Repository info contains the base type Item. "
                            + "The Item base type has been removed from the list.");
                    continue;
                }
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_CHANGES_ON_TYPE, baseType);
            }
        }
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_PRINCIPAL_ID_ANONYMOUS,
                source.getPrincipalIdAnonymous());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_REPINFO_PRINCIPAL_ID_ANYONE,
                source.getPrincipalIdAnyone());
        if (cmisVersion != CmisVersion.CMIS_1_0 && source.getExtensionFeatures() != null) {
            for (ExtensionFeature feature : source.getExtensionFeatures()) {
                writeExtendedFeatures(writer, cmisVersion, feature);
            }
        }

        writeExtensions(writer, source);
        writer.endTag(namespace, TAG_REPOSITORY_INFO);
    }

    public static void writeRepositoryCapabilities(XmlSerializer writer, CmisVersion cmisVersion,
            RepositoryCapabilities source) throws IOException {
        if (source == null) {
            return;
        }

        writer.startTag(NAMESPACE_CMIS, TAG_REPINFO_CAPABILITIES);

        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_ACL, source.getAclCapability());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_ALL_VERSIONS_SEARCHABLE,
                source.isAllVersionsSearchableSupported());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_CHANGES, source.getChangesCapability());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_CONTENT_STREAM_UPDATABILITY,
                source.getContentStreamUpdatesCapability());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_GET_DESCENDANTS, source.isGetDescendantsSupported());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_GET_FOLDER_TREE, source.isGetFolderTreeSupported());
        if (cmisVersion != CmisVersion.CMIS_1_0) {
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_ORDER_BY, source.getOrderByCapability());
        }
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_MULTIFILING, source.isMultifilingSupported());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_PWC_SEARCHABLE, source.isPwcSearchableSupported());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_PWC_UPDATABLE, source.isPwcUpdatableSupported());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_QUERY, source.getQueryCapability());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_RENDITIONS, source.getRenditionsCapability());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_UNFILING, source.isUnfilingSupported());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_VERSION_SPECIFIC_FILING,
                source.isVersionSpecificFilingSupported());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_JOIN, source.getJoinCapability());
        if (cmisVersion != CmisVersion.CMIS_1_0) {
            if (source.getCreatablePropertyTypes() != null) {
                CreatablePropertyTypes creatablePropertyTypes = source.getCreatablePropertyTypes();

                writer.startTag(NAMESPACE_CMIS, TAG_CAP_CREATABLE_PROPERTY_TYPES);

                if (creatablePropertyTypes.canCreate() != null) {
                    for (PropertyType pt : creatablePropertyTypes.canCreate()) {
                        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_CREATABLE_PROPERTY_TYPES_CANCREATE,
                                pt);
                    }
                }

                writeExtensions(writer, creatablePropertyTypes);
                writer.endTag(NAMESPACE_CMIS, TAG_CAP_CREATABLE_PROPERTY_TYPES);
            }
            if (source.getNewTypeSettableAttributes() != null) {
                NewTypeSettableAttributes newTypeSettableAttributes = source.getNewTypeSettableAttributes();

                writer.startTag(NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES);

                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_ID,
                        newTypeSettableAttributes.canSetId());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAME,
                        newTypeSettableAttributes.canSetLocalName());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS,
                        TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAMESPACE,
                        newTypeSettableAttributes.canSetLocalNamespace());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAYNAME,
                        newTypeSettableAttributes.canSetDisplayName());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYNAME,
                        newTypeSettableAttributes.canSetQueryName());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION,
                        newTypeSettableAttributes.canSetDescription());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATEABLE,
                        newTypeSettableAttributes.canSetCreatable());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE,
                        newTypeSettableAttributes.canSetFileable());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE,
                        newTypeSettableAttributes.canSetQueryable());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS,
                        TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXTINDEXED,
                        newTypeSettableAttributes.canSetFulltextIndexed());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS,
                        TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDEDINSUPERTYTPEQUERY,
                        newTypeSettableAttributes.canSetIncludedInSupertypeQuery());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS,
                        TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEPOLICY,
                        newTypeSettableAttributes.canSetControllablePolicy());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS,
                        TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEACL,
                        newTypeSettableAttributes.canSetControllableAcl());

                writeExtensions(writer, newTypeSettableAttributes);
                writer.endTag(NAMESPACE_CMIS, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES);
            }
        }

        writeExtensions(writer, source);
        writer.endTag(NAMESPACE_CMIS, TAG_REPINFO_CAPABILITIES);
    }

    public static void writeAclCapabilities(XmlSerializer writer, CmisVersion cmisVersion, AclCapabilities source)
            throws IOException {
        if (source == null) {
            return;
        }

        writer.startTag(NAMESPACE_CMIS, TAG_REPINFO_ACL_CAPABILITIES);

        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACLCAP_SUPPORTED_PERMISSIONS,
                source.getSupportedPermissions());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACLCAP_ACL_PROPAGATION, source.getAclPropagation());
        if (source.getPermissions() != null) {
            for (PermissionDefinition pd : source.getPermissions()) {
                writer.startTag(NAMESPACE_CMIS, TAG_ACLCAP_PERMISSIONS);

                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACLCAP_PERMISSION_PERMISSION, pd.getId());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACLCAP_PERMISSION_DESCRIPTION,
                        pd.getDescription());

                writeExtensions(writer, pd);
                writer.endTag(NAMESPACE_CMIS, TAG_ACLCAP_PERMISSIONS);
            }
        }
        if (source.getPermissionMapping() != null) {
            for (PermissionMapping pm : source.getPermissionMapping().values()) {
                writer.startTag(NAMESPACE_CMIS, TAG_ACLCAP_PERMISSION_MAPPING);

                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACLCAP_MAPPING_KEY, pm.getKey());
                if (pm.getPermissions() != null) {
                    for (String perm : pm.getPermissions()) {
                        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACLCAP_MAPPING_PERMISSION, perm);
                    }
                }

                writeExtensions(writer, pm);
                writer.endTag(NAMESPACE_CMIS, TAG_ACLCAP_PERMISSION_MAPPING);
            }
        }

        writeExtensions(writer, source);
        writer.endTag(NAMESPACE_CMIS, TAG_REPINFO_ACL_CAPABILITIES);
    }

    public static void writeExtendedFeatures(XmlSerializer writer, CmisVersion cmisVersion, ExtensionFeature source)
            throws IOException {
        if (source == null) {
            return;
        }

        writer.startTag(NAMESPACE_CMIS, TAG_REPINFO_EXTENDED_FEATURES);

        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_FEATURE_ID, source.getId());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_FEATURE_URL, source.getUrl());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_FEATURE_COMMON_NAME, source.getCommonName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_FEATURE_VERSION_LABEL, source.getVersionLabel());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_FEATURE_DESCRIPTION, source.getDescription());
        if (source.getFeatureData() != null) {
            for (Map.Entry<String, String> data : source.getFeatureData().entrySet()) {
                writer.startTag(NAMESPACE_CMIS, TAG_FEATURE_DATA);

                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_FEATURE_DATA_KEY, data.getKey());
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_FEATURE_DATA_VALUE, data.getValue());

                writer.endTag(NAMESPACE_CMIS, TAG_FEATURE_DATA);
            }
        }

        writeExtensions(writer, source);
        writer.endTag(NAMESPACE_CMIS, TAG_REPINFO_EXTENDED_FEATURES);
    }

    // --------------------------
    // --- definition writers ---
    // --------------------------

    public static void writeTypeDefinition(XmlSerializer writer, CmisVersion cmisVersion, String namespace,
            TypeDefinition source) throws IOException {
        if (source == null) {
            return;
        }

        if (cmisVersion == CmisVersion.CMIS_1_0) {
            if (source.getBaseTypeId() == BaseTypeId.CMIS_ITEM) {
                LOG.warn("Receiver only understands CMIS 1.0. It may not able to handle an Item type definition.");
            } else if (source.getBaseTypeId() == BaseTypeId.CMIS_SECONDARY) {
                LOG.warn("Receiver only understands CMIS 1.0. It may not able to handle a Secondary type definition.");
            }
        }

        writer.setPrefix(PREFIX_XSI, NAMESPACE_XSI);
        writer.startTag(namespace, TAG_TYPE);
        writer.attribute("", XMLConstants.PREFIX_XSI, XMLConstants.NAMESPACE_XSI);

        if (source.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT) {
            writer.attribute(NAMESPACE_XSI, "type", PREFIX_CMIS + ":" + ATTR_DOCUMENT_TYPE);
        } else if (source.getBaseTypeId() == BaseTypeId.CMIS_FOLDER) {
            writer.attribute(NAMESPACE_XSI, "type", PREFIX_CMIS + ":" + ATTR_FOLDER_TYPE);
        } else if (source.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP) {
            writer.attribute(NAMESPACE_XSI, "type", PREFIX_CMIS + ":" + ATTR_RELATIONSHIP_TYPE);
        } else if (source.getBaseTypeId() == BaseTypeId.CMIS_POLICY) {
            writer.attribute(NAMESPACE_XSI, "type", PREFIX_CMIS + ":" + ATTR_POLICY_TYPE);
        } else if (source.getBaseTypeId() == BaseTypeId.CMIS_ITEM) {
            writer.attribute(NAMESPACE_XSI, "type", PREFIX_CMIS + ":" + ATTR_ITEM_TYPE);
        } else if (source.getBaseTypeId() == BaseTypeId.CMIS_SECONDARY) {
            writer.attribute(NAMESPACE_XSI, "type", PREFIX_CMIS + ":" + ATTR_SECONDARY_TYPE);
        } else {
            throw new CmisRuntimeException("Type definition has no base type id!");
        }

        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_ID, source.getId());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_LOCALNAME, source.getLocalName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_LOCALNAMESPACE, source.getLocalNamespace());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_DISPLAYNAME, source.getDisplayName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_QUERYNAME, source.getQueryName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_DESCRIPTION, source.getDescription());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_BASE_ID, source.getBaseTypeId());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_PARENT_ID, source.getParentTypeId());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_CREATABLE, source.isCreatable());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_FILEABLE, source.isFileable());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_QUERYABLE, source.isQueryable());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_FULLTEXT_INDEXED, source.isFulltextIndexed());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_INCLUDE_IN_SUPERTYPE_QUERY,
                source.isIncludedInSupertypeQuery());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_CONTROLABLE_POLICY, source.isControllablePolicy());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_CONTROLABLE_ACL, source.isControllableAcl());
        if (cmisVersion != CmisVersion.CMIS_1_0 && source.getTypeMutability() != null) {
            TypeMutability tm = source.getTypeMutability();

            writer.startTag(NAMESPACE_CMIS, TAG_TYPE_TYPE_MUTABILITY);

            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_TYPE_MUTABILITY_CREATE, tm.canCreate());
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_TYPE_MUTABILITY_UPDATE, tm.canUpdate());
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_TYPE_MUTABILITY_DELETE, tm.canDelete());

            writeExtensions(writer, tm);
            writer.endTag(NAMESPACE_CMIS, TAG_TYPE_TYPE_MUTABILITY);
        }
        if (source.getPropertyDefinitions() != null) {
            for (PropertyDefinition<?> pd : source.getPropertyDefinitions().values()) {
                writePropertyDefinition(writer, cmisVersion, pd);
            }
        }

        if (source instanceof DocumentTypeDefinition) {
            DocumentTypeDefinition docDef = (DocumentTypeDefinition) source;
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_VERSIONABLE, docDef.isVersionable());
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_CONTENTSTREAM_ALLOWED,
                    docDef.getContentStreamAllowed());
        }

        if (source instanceof RelationshipTypeDefinition) {
            RelationshipTypeDefinition relDef = (RelationshipTypeDefinition) source;
            if (relDef.getAllowedSourceTypeIds() != null) {
                for (String id : relDef.getAllowedSourceTypeIds()) {
                    if (id != null) {
                        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_ALLOWED_SOURCE_TYPES, id);
                    }
                }
            }
            if (relDef.getAllowedTargetTypeIds() != null) {
                for (String id : relDef.getAllowedTargetTypeIds()) {
                    if (id != null) {
                        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_TYPE_ALLOWED_TARGET_TYPES, id);
                    }
                }
            }
        }

        writeExtensions(writer, source);
        writer.endTag(namespace, TAG_TYPE);
    }

    public static void writePropertyDefinition(XmlSerializer writer, CmisVersion cmisVersion,
            PropertyDefinition<?> source) throws IOException {
        if (source == null) {
            return;
        }

        if (source.getPropertyType() == null) {
            throw new CmisRuntimeException("Property type for property definition '" + source.getId() + "' is not set!");
        }

        String tagName = "";
        switch (source.getPropertyType()) {
        case STRING:
            tagName = TAG_TYPE_PROP_DEF_STRING;
            break;
        case ID:
            tagName = TAG_TYPE_PROP_DEF_ID;
            break;
        case INTEGER:
            tagName = TAG_TYPE_PROP_DEF_INTEGER;
            break;
        case BOOLEAN:
            tagName = TAG_TYPE_PROP_DEF_BOOLEAN;
            break;
        case DATETIME:
            tagName = TAG_TYPE_PROP_DEF_DATETIME;
            break;
        case DECIMAL:
            tagName = TAG_TYPE_PROP_DEF_DECIMAL;
            break;
        case HTML:
            tagName = TAG_TYPE_PROP_DEF_HTML;
            break;
        case URI:
            tagName = TAG_TYPE_PROP_DEF_URI;
            break;
        default:
            throw new CmisRuntimeException("Property defintion has no property type!");
        }
        writer.startTag(NAMESPACE_CMIS, tagName);

        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_ID, source.getId());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_LOCALNAME, source.getLocalName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_LOCALNAMESPACE,
                source.getLocalNamespace());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_DISPLAYNAME, source.getDisplayName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_QUERYNAME, source.getQueryName());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_DESCRIPTION, source.getDescription());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_PROPERTY_TYPE, source.getPropertyType());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_CARDINALITY, source.getCardinality());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_UPDATABILITY, source.getUpdatability());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_INHERITED, source.isInherited());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_REQUIRED, source.isRequired());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_QUERYABLE, source.isQueryable());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_ORDERABLE, source.isOrderable());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_OPENCHOICE, source.isOpenChoice());

        if (source instanceof PropertyStringDefinition) {
            PropertyStringDefinition def = (PropertyStringDefinition) source;

            if (def.getDefaultValue() != null) {
                writeProperty(writer, new PropertyStringImpl((String) null, def.getDefaultValue()), true);
            }

            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_MAX_LENGTH, def.getMaxLength());
        } else if (source instanceof PropertyIdDefinition) {
            PropertyIdDefinition def = (PropertyIdDefinition) source;

            if (def.getDefaultValue() != null) {
                writeProperty(writer, new PropertyIdImpl((String) null, def.getDefaultValue()), true);
            }
        } else if (source instanceof PropertyIntegerDefinition) {
            PropertyIntegerDefinition def = (PropertyIntegerDefinition) source;

            if (def.getDefaultValue() != null) {
                writeProperty(writer, new PropertyIntegerImpl((String) null, def.getDefaultValue()), true);
            }

            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_MAX_VALUE, def.getMaxValue());
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_MIN_VALUE, def.getMinValue());
        } else if (source instanceof PropertyBooleanDefinition) {
            PropertyBooleanDefinition def = (PropertyBooleanDefinition) source;

            if (def.getDefaultValue() != null) {
                writeProperty(writer, new PropertyBooleanImpl((String) null, def.getDefaultValue()), true);
            }
        } else if (source instanceof PropertyDateTimeDefinition) {
            PropertyDateTimeDefinition def = (PropertyDateTimeDefinition) source;

            if (def.getDefaultValue() != null) {
                writeProperty(writer, new PropertyDateTimeImpl((String) null, def.getDefaultValue()), true);
            }

            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_RESOLUTION,
                    def.getDateTimeResolution());
        } else if (source instanceof PropertyDecimalDefinition) {
            PropertyDecimalDefinition def = (PropertyDecimalDefinition) source;

            if (def.getDefaultValue() != null) {
                writeProperty(writer, new PropertyDecimalImpl((String) null, def.getDefaultValue()), true);
            }

            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_MAX_VALUE, def.getMaxValue());
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_MIN_VALUE, def.getMinValue());
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_PRECISION, def.getPrecision());
        } else if (source instanceof PropertyHtmlDefinition) {
            PropertyHtmlDefinition def = (PropertyHtmlDefinition) source;

            if (def.getDefaultValue() != null) {
                writeProperty(writer, new PropertyHtmlImpl((String) null, def.getDefaultValue()), true);
            }
        } else if (source instanceof PropertyUriDefinition) {
            PropertyUriDefinition def = (PropertyUriDefinition) source;

            if (def.getDefaultValue() != null) {
                writeProperty(writer, new PropertyUriImpl((String) null, def.getDefaultValue()), true);
            }
        }

        if (source.getChoices() != null) {
            for (Choice<?> c : source.getChoices()) {
                if (c != null) {
                    writeChoice(writer, source.getPropertyType(), c);
                }
            }
        }

        writeExtensions(writer, source);
        writer.endTag(NAMESPACE_CMIS, tagName);
    }

    @SuppressWarnings("unchecked")
    public static void writeChoice(XmlSerializer writer, PropertyType propType, Choice<?> source) throws IOException {
        if (source == null) {
            return;
        }

        writer.startTag(NAMESPACE_CMIS, TAG_PROPERTY_TYPE_CHOICE);

        if (source.getDisplayName() != null) {
            writer.attribute("", ATTR_PROPERTY_TYPE_CHOICE_DISPLAYNAME, source.getDisplayName());
        }

        if (source.getValue() != null) {
            switch (propType) {
            case STRING:
            case ID:
            case HTML:
            case URI:
                for (String value : (List<String>) source.getValue()) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_CHOICE_VALUE, value);
                }
                break;
            case INTEGER:
                for (BigInteger value : (List<BigInteger>) source.getValue()) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_CHOICE_VALUE, value);
                }
                break;
            case BOOLEAN:
                for (Boolean value : (List<Boolean>) source.getValue()) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_CHOICE_VALUE, value);
                }
                break;
            case DATETIME:
                for (GregorianCalendar value : (List<GregorianCalendar>) source.getValue()) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_CHOICE_VALUE, value);
                }
                break;
            case DECIMAL:
                for (BigDecimal value : (List<BigDecimal>) source.getValue()) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_TYPE_CHOICE_VALUE, value);
                }
                break;
            default:
            }
        }

        if (source.getChoice() != null) {
            for (Choice<?> c : source.getChoice()) {
                if (c != null) {
                    writeChoice(writer, propType, c);
                }
            }
        }

        writer.endTag(NAMESPACE_CMIS, TAG_PROPERTY_TYPE_CHOICE);
    }

    // -----------------------
    // --- object writers ---
    // -----------------------

    public static void writeObject(XmlSerializer writer, CmisVersion cmisVersion, String namespace, ObjectData source)
            throws IOException {
        writeObject(writer, cmisVersion, false, TAG_OBJECT, namespace, source);
    }

    public static void writeObject(XmlSerializer writer, CmisVersion cmisVersion, boolean root, String name,
            String namespace, ObjectData source) throws IOException {

        if (source == null) {
            return;
        }

        if (cmisVersion == CmisVersion.CMIS_1_0) {
            if (source.getBaseTypeId() == BaseTypeId.CMIS_ITEM) {
                LOG.warn("Receiver only understands CMIS 1.0. It may not be able to handle an Item object.");
            }
        }

        if (root) {
            writer.startTag(NAMESPACE_CMIS, name);
            writer.attribute("", PREFIX_CMIS, NAMESPACE_CMIS);
            namespace = NAMESPACE_CMIS;
        } else {
            writer.startTag(namespace, name);
        }

        if (source.getProperties() != null) {
            Properties properties = source.getProperties();

            writer.startTag(NAMESPACE_CMIS, TAG_OBJECT_PROPERTIES);

            if (properties.getPropertyList() != null) {
                for (PropertyData<?> property : properties.getPropertyList()) {
                    writeProperty(writer, property, false);
                }
            }

            writeExtensions(writer, properties);
            writer.endTag(NAMESPACE_CMIS, TAG_OBJECT_PROPERTIES);
        }
        if (source.getAllowableActions() != null) {
            writeAllowableActions(writer, cmisVersion, false, source.getAllowableActions());
        }
        if (source.getRelationships() != null) {
            for (ObjectData rel : source.getRelationships()) {
                if (rel != null) {
                    writeObject(writer, cmisVersion, false, TAG_OBJECT_RELATIONSHIP, NAMESPACE_CMIS, rel);
                }
            }
        }
        if (source.getChangeEventInfo() != null) {
            ChangeEventInfo info = source.getChangeEventInfo();

            writer.startTag(NAMESPACE_CMIS, TAG_OBJECT_CHANGE_EVENT_INFO);

            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CHANGE_EVENT_TYPE, info.getChangeType());
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_CHANGE_EVENT_TIME, info.getChangeTime());

            writeExtensions(writer, info);
            writer.endTag(NAMESPACE_CMIS, TAG_OBJECT_CHANGE_EVENT_INFO);
        }
        if (source.getAcl() != null) {
            writeAcl(writer, cmisVersion, false, source.getAcl());
        }
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_OBJECT_EXACT_ACL, source.isExactAcl());
        if (source.getPolicyIds() != null) {
            PolicyIdList pids = source.getPolicyIds();

            writer.startTag(NAMESPACE_CMIS, TAG_OBJECT_POLICY_IDS);

            if (pids.getPolicyIds() != null) {
                for (String id : pids.getPolicyIds()) {
                    if (id != null) {
                        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_POLICY_ID, id);
                    }
                }
            }

            writeExtensions(writer, pids);
            writer.endTag(NAMESPACE_CMIS, TAG_OBJECT_POLICY_IDS);
        }
        if (source.getRenditions() != null) {
            for (RenditionData rend : source.getRenditions()) {
                if (rend != null) {
                    writer.startTag(NAMESPACE_CMIS, TAG_OBJECT_RENDITION);

                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_RENDITION_STREAM_ID, rend.getStreamId());
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_RENDITION_MIMETYPE, rend.getMimeType());
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_RENDITION_LENGTH, rend.getBigLength());
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_RENDITION_KIND, rend.getKind());
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_RENDITION_TITLE, rend.getTitle());
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_RENDITION_HEIGHT, rend.getBigHeight());
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_RENDITION_WIDTH, rend.getBigWidth());
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_RENDITION_DOCUMENT_ID,
                            rend.getRenditionDocumentId());

                    writeExtensions(writer, rend);
                    writer.endTag(NAMESPACE_CMIS, TAG_OBJECT_RENDITION);
                }
            }
        }

        writeExtensions(writer, source);
        writer.endTag(namespace, name);
    }

    @SuppressWarnings("unchecked")
    public static void writeProperty(XmlSerializer writer, PropertyData<?> source, boolean isDefaultValue)
            throws IOException {
        if (source == null) {
            return;
        }

        String tagName = TAG_PROPERTY_TYPE_DEAULT_VALUE;
        if (isDefaultValue) {
            tagName = TAG_PROPERTY_TYPE_DEAULT_VALUE;
        } else {
            if (source instanceof PropertyString) {
                tagName = TAG_PROP_STRING;
            } else if (source instanceof PropertyId) {
                tagName = TAG_PROP_ID;
            } else if (source instanceof PropertyInteger) {
                tagName = TAG_PROP_INTEGER;
            } else if (source instanceof PropertyBoolean) {
                tagName = TAG_PROP_BOOLEAN;
            } else if (source instanceof PropertyDateTime) {
                tagName = TAG_PROP_DATETIME;
            } else if (source instanceof PropertyDecimal) {
                tagName = TAG_PROP_DECIMAL;
            } else if (source instanceof PropertyHtml) {
                tagName = TAG_PROP_HTML;
            } else if (source instanceof PropertyUri) {
                tagName = TAG_PROP_URI;
            } else {
                throw new CmisRuntimeException("Invalid property!");
            }
        }

        writer.startTag(NAMESPACE_CMIS, tagName);

        if (source.getId() != null) {
            writer.attribute(null, ATTR_PROPERTY_ID, source.getId());
        }
        if (source.getDisplayName() != null) {
            writer.attribute(null, ATTR_PROPERTY_DISPLAYNAME, source.getDisplayName());
        }
        if (source.getLocalName() != null) {
            writer.attribute(null, ATTR_PROPERTY_LOCALNAME, source.getLocalName());
        }
        if (source.getQueryName() != null) {
            writer.attribute(null, ATTR_PROPERTY_QUERYNAME, source.getQueryName());
        }

        if ((source instanceof PropertyString) || (source instanceof PropertyId) || (source instanceof PropertyHtml)
                || (source instanceof PropertyUri)) {
            List<String> values = (List<String>) source.getValues();
            if (values != null) {
                for (String value : values) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_VALUE, value);
                }
            }
        } else if (source instanceof PropertyInteger) {
            List<BigInteger> values = ((PropertyInteger) source).getValues();
            if (values != null) {
                for (BigInteger value : values) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_VALUE, value);
                }
            }
        } else if (source instanceof PropertyBoolean) {
            List<Boolean> values = ((PropertyBoolean) source).getValues();
            if (values != null) {
                for (Boolean value : values) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_VALUE, value);
                }
            }
        } else if (source instanceof PropertyDateTime) {
            List<GregorianCalendar> values = ((PropertyDateTime) source).getValues();
            if (values != null) {
                for (GregorianCalendar value : values) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_VALUE, value);
                }
            }
        } else if (source instanceof PropertyDecimal) {
            List<BigDecimal> values = ((PropertyDecimal) source).getValues();
            if (values != null) {
                for (BigDecimal value : values) {
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_PROPERTY_VALUE, value);
                }
            }
        }

        writeExtensions(writer, source);
        writer.endTag(NAMESPACE_CMIS, tagName);
    }

    public static void writeAllowableActions(XmlSerializer writer, CmisVersion cmisVersion, boolean root,
            AllowableActions source) throws IOException {
        if (source == null) {
            return;
        }

        if (root) {
            writer.startTag(NAMESPACE_CMIS, "allowableActions");
            writer.attribute("", PREFIX_CMIS, NAMESPACE_CMIS);
        } else {
            writer.startTag(NAMESPACE_CMIS, TAG_OBJECT_ALLOWABLE_ACTIONS);
        }

        if (source.getAllowableActions() != null) {
            for (Action action : Action.values()) {
                if (source.getAllowableActions().contains(action)) {
                    if (action == Action.CAN_CREATE_ITEM && cmisVersion == CmisVersion.CMIS_1_0) {
                        LOG.warn("Receiver only understands CMIS 1.0 but the Allowable Actions contain the canCreateItem action. "
                                + "The canCreateItem action has been removed from the Allowable Actions.");
                        continue;
                    }
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, action.value(), Boolean.TRUE);
                }
            }
        }

        writeExtensions(writer, source);
        writer.endTag(NAMESPACE_CMIS, TAG_OBJECT_ALLOWABLE_ACTIONS);
    }

    public static void writeAcl(XmlSerializer writer, CmisVersion cmisVersion, boolean root, Acl source)
            throws IOException {
        if (source == null) {
            return;
        }

        if (root) {
            writer.startTag(NAMESPACE_CMIS, "acl");
            writer.attribute("", PREFIX_CMIS, NAMESPACE_CMIS);
        } else {
            writer.startTag(NAMESPACE_CMIS, TAG_OBJECT_ACL);
        }

        if (source.getAces() != null) {
            for (Ace ace : source.getAces()) {
                if (ace != null) {
                    writer.startTag(NAMESPACE_CMIS, TAG_ACL_PERMISSISONS);

                    if (ace.getPrincipal() != null) {
                        Principal principal = ace.getPrincipal();

                        writer.startTag(NAMESPACE_CMIS, TAG_ACE_PRINCIPAL);

                        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACE_PRINCIPAL_ID, principal.getId());

                        writeExtensions(writer, principal);
                        writer.endTag(NAMESPACE_CMIS, TAG_ACE_PRINCIPAL);
                    }
                    if (ace.getPermissions() != null) {
                        for (String perm : ace.getPermissions()) {
                            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACE_PERMISSIONS, perm);
                        }
                    }
                    XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_ACE_IS_DIRECT, ace.isDirect());

                    writeExtensions(writer, ace);
                    writer.endTag(NAMESPACE_CMIS, TAG_ACL_PERMISSISONS);
                }
            }
        }

        writeExtensions(writer, source);
        writer.endTag(NAMESPACE_CMIS, TAG_OBJECT_ACL);
    }

    // -------------
    // --- query ---
    // -------------

    public static void writeQuery(XmlSerializer writer, CmisVersion cmisVersion, QueryTypeImpl source)
            throws IOException {
        if (source == null) {
            return;
        }

        writer.startTag(NAMESPACE_CMIS, TAG_QUERY);
        writer.attribute(null, XMLConstants.PREFIX_CMIS, XMLConstants.NAMESPACE_CMIS);

        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_QUERY_STATEMENT, source.getStatement());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_QUERY_SEARCHALLVERSIONS, source.getSearchAllVersions());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_QUERY_INCLUDEALLOWABLEACTIONS,
                source.getIncludeAllowableActions());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_QUERY_INCLUDERELATIONSHIPS,
                source.getIncludeRelationships());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_QUERY_RENDITIONFILTER, source.getRenditionFilter());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_QUERY_MAXITEMS, source.getMaxItems());
        XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_QUERY_SKIPCOUNT, source.getSkipCount());

        writeExtensions(writer, source);
        writer.endTag(NAMESPACE_CMIS, TAG_QUERY);
    }

    // -------------------
    // --- bulk update ---
    // -------------------

    public static void writeBulkUpdate(XmlSerializer writer, String namespace, BulkUpdateImpl bulkUpdate)
            throws IOException {
        if (bulkUpdate == null || bulkUpdate.getObjectIdAndChangeToken() == null) {
            return;
        }

        writer.startTag(namespace, TAG_BULK_UPDATE);

        for (BulkUpdateObjectIdAndChangeToken idAndToken : bulkUpdate.getObjectIdAndChangeToken()) {
            if (idAndToken == null) {
                continue;
            }

            writer.startTag(NAMESPACE_CMIS, TAG_BULK_UPDATE_ID_AND_TOKEN);

            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_IDANDTOKEN_ID, idAndToken.getId());
            XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_IDANDTOKEN_CHANGETOKEN, idAndToken.getChangeToken());

            writeExtensions(writer, idAndToken);
            writer.endTag(NAMESPACE_CMIS, TAG_BULK_UPDATE_ID_AND_TOKEN);
        }

        if (bulkUpdate.getProperties() != null) {
            Properties properties = bulkUpdate.getProperties();
            writer.startTag(NAMESPACE_CMIS, TAG_BULK_UPDATE_PROPERTIES);

            if (properties.getPropertyList() != null) {
                for (PropertyData<?> property : properties.getPropertyList()) {
                    writeProperty(writer, property, false);
                }
            }

            writeExtensions(writer, properties);
            writer.endTag(NAMESPACE_CMIS, TAG_BULK_UPDATE_PROPERTIES);
        }

        if (bulkUpdate.getAddSecondaryTypeIds() != null) {
            for (String id : bulkUpdate.getAddSecondaryTypeIds()) {
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_BULK_UPDATE_ADD_SECONDARY_TYPES, id);
            }
        }

        if (bulkUpdate.getRemoveSecondaryTypeIds() != null) {
            for (String id : bulkUpdate.getRemoveSecondaryTypeIds()) {
                XMLUtils.write(writer, PREFIX_CMIS, NAMESPACE_CMIS, TAG_BULK_UPDATE_REMOVE_SECONDARY_TYPES, id);
            }
        }

        writer.endTag(namespace, TAG_BULK_UPDATE);
    }

    // -------------------------
    // --- extension writers ---
    // -------------------------

    public static void writeExtensions(XmlSerializer writer, ExtensionsData source) throws IOException {
        if (source == null) {
            return;
        }

        LinkedList<String> ns = new LinkedList<String>();

        if (source.getExtensions() != null) {
            for (CmisExtensionElement element : source.getExtensions()) {
                if (element == null) {
                    continue;
                }

                writeExtensionElement(writer, element, ns);
            }
        }
    }

    private static void writeExtensionElement(XmlSerializer writer, CmisExtensionElement source, LinkedList<String> ns)
            throws IOException {
        if (source == null || source.getName() == null) {
            return;
        }

        String namespace = null;
        // boolean addedNamespace = false;

        if (source.getNamespace() != null) {
            /*
             * String prefix = writer.getPrefix(source.getNamespace(), false);
             * if (prefix == null) { int p = ns.indexOf(source.getNamespace());
             * 
             * if (p == -1) { prefix = "e" + (ns.size() + 1);
             * ns.add(source.getNamespace()); addedNamespace = true; } else {
             * prefix = "e" + (p + 1); } }
             */

            namespace = source.getNamespace();
            writer.startTag(source.getNamespace(), source.getName());

            /*
             * if (addedNamespace) { writer.attribute("", prefix,
             * source.getNamespace()); }
             */
        } else {
            writer.startTag(null, source.getName());
        }

        if (source.getAttributes() != null) {
            for (Map.Entry<String, String> attr : source.getAttributes().entrySet()) {
                writer.attribute(null, attr.getKey(), attr.getValue());
            }
        }

        if (source.getValue() != null) {
            writer.text(source.getValue());
        } else {
            if (source.getChildren() != null) {
                for (CmisExtensionElement child : source.getChildren()) {
                    writeExtensionElement(writer, child, ns);
                }
            }
        }

        writer.endTag(namespace, source.getName());

        /*
         * if (addedNamespace) { ns.removeLast(); }
         */
    }

    // ---------------
    // --- parsers ---
    // ---------------

    public static RepositoryInfo convertRepositoryInfo(XmlPullParser parser) throws XmlPullParserException {
        return REPOSITORY_INFO_PARSER.walk(parser);
    }

    public static TypeDefinition convertTypeDefinition(XmlPullParser parser) throws XmlPullParserException {
        return TYPE_DEF_PARSER.walk(parser);
    }

    public static ObjectData convertObject(XmlPullParser parser) throws XmlPullParserException {
        return OBJECT_PARSER.walk(parser);
    }

    public static QueryTypeImpl convertQuery(XmlPullParser parser) throws XmlPullParserException {
        return QUERY_PARSER.walk(parser);
    }

    public static AllowableActions convertAllowableActions(XmlPullParser parser) throws XmlPullParserException {
        return ALLOWABLE_ACTIONS_PARSER.walk(parser);
    }

    public static Acl convertAcl(XmlPullParser parser) throws XmlPullParserException {
        return ACL_PARSER.walk(parser);
    }

    public static BulkUpdateImpl convertBulkUpdate(XmlPullParser parser) throws XmlPullParserException {
        return BULK_UPDATE_PARSER.walk(parser);
    }

    // ------------------------------
    // --- repository info parser ---
    // ------------------------------

    private static final XMLWalker<RepositoryInfoImpl> REPOSITORY_INFO_PARSER = new XMLWalker<RepositoryInfoImpl>() {
        @Override
        protected RepositoryInfoImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new RepositoryInfoImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, RepositoryInfoImpl target)
                throws XmlPullParserException {

            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_REPINFO_ID)) {
                    target.setId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_NAME)) {
                    target.setName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_DESCRIPTION)) {
                    target.setDescription(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_VENDOR)) {
                    target.setVendorName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_PRODUCT)) {
                    target.setProductName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_PRODUCT_VERSION)) {
                    target.setProductVersion(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_ROOT_FOLDER_ID)) {
                    target.setRootFolder(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_CHANGE_LOG_TOKEN)) {
                    target.setLatestChangeLogToken(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_CAPABILITIES)) {
                    target.setCapabilities(CAPABILITIES_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_ACL_CAPABILITIES)) {
                    target.setAclCapabilities(ACL_CAPABILITIES_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_CMIS_VERSION_SUPPORTED)) {
                    target.setCmisVersionSupported(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_THIN_CLIENT_URI)) {
                    target.setThinClientUri(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_CHANGES_INCOMPLETE)) {
                    target.setChangesIncomplete(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_CHANGES_ON_TYPE)) {
                    target.setChangesOnType(addToList(target.getChangesOnType(), readEnum(parser, BaseTypeId.class)));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_PRINCIPAL_ID_ANONYMOUS)) {
                    target.setPrincipalAnonymous(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_PRINCIPAL_ID_ANYONE)) {
                    target.setPrincipalAnyone(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_REPINFO_EXTENDED_FEATURES)) {
                    target.setExtensionFeature(addToList(target.getExtensionFeatures(),
                            EXTENDED_FEATURES_PARSER.walk(parser)));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<RepositoryCapabilitiesImpl> CAPABILITIES_PARSER = new XMLWalker<RepositoryCapabilitiesImpl>() {
        @Override
        protected RepositoryCapabilitiesImpl prepareTarget(XmlPullParser parser, QName name)
                throws XmlPullParserException {
            return new RepositoryCapabilitiesImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, RepositoryCapabilitiesImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_CAP_ACL)) {
                    target.setCapabilityAcl(readEnum(parser, CapabilityAcl.class));
                    return true;
                }

                if (isTag(name, TAG_CAP_ALL_VERSIONS_SEARCHABLE)) {
                    target.setAllVersionsSearchable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_CHANGES)) {
                    target.setCapabilityChanges(readEnum(parser, CapabilityChanges.class));
                    return true;
                }

                if (isTag(name, TAG_CAP_CONTENT_STREAM_UPDATABILITY)) {
                    target.setCapabilityContentStreamUpdates(readEnum(parser, CapabilityContentStreamUpdates.class));
                    return true;
                }

                if (isTag(name, TAG_CAP_GET_DESCENDANTS)) {
                    target.setSupportsGetDescendants(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_GET_FOLDER_TREE)) {
                    target.setSupportsGetFolderTree(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_ORDER_BY)) {
                    target.setCapabilityOrderBy(readEnum(parser, CapabilityOrderBy.class));
                    return true;
                }

                if (isTag(name, TAG_CAP_MULTIFILING)) {
                    target.setSupportsMultifiling(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_PWC_SEARCHABLE)) {
                    target.setIsPwcSearchable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_PWC_UPDATABLE)) {
                    target.setIsPwcUpdatable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_QUERY)) {
                    target.setCapabilityQuery(readEnum(parser, CapabilityQuery.class));
                    return true;
                }

                if (isTag(name, TAG_CAP_RENDITIONS)) {
                    target.setCapabilityRendition(readEnum(parser, CapabilityRenditions.class));
                    return true;
                }

                if (isTag(name, TAG_CAP_UNFILING)) {
                    target.setSupportsUnfiling(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_VERSION_SPECIFIC_FILING)) {
                    target.setSupportsVersionSpecificFiling(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_JOIN)) {
                    target.setCapabilityJoin(readEnum(parser, CapabilityJoin.class));
                    return true;
                }

                if (isTag(name, TAG_CAP_CREATABLE_PROPERTY_TYPES)) {
                    target.setCreatablePropertyTypes(CREATABLE_PROPERTY_TYPES_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES)) {
                    target.setNewTypeSettableAttributes(NEW_TYPES_SETTABLE_ATTRIBUTES_PARSER.walk(parser));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<CreatablePropertyTypesImpl> CREATABLE_PROPERTY_TYPES_PARSER = new XMLWalker<CreatablePropertyTypesImpl>() {
        @Override
        protected CreatablePropertyTypesImpl prepareTarget(XmlPullParser parser, QName name)
                throws XmlPullParserException {
            return new CreatablePropertyTypesImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, CreatablePropertyTypesImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_CAP_CREATABLE_PROPERTY_TYPES_CANCREATE)) {
                    target.canCreate().add(readEnum(parser, PropertyType.class));
                    return true;
                }
            }
            return false;
        }
    };

    private static final XMLWalker<NewTypeSettableAttributesImpl> NEW_TYPES_SETTABLE_ATTRIBUTES_PARSER = new XMLWalker<NewTypeSettableAttributesImpl>() {
        @Override
        protected NewTypeSettableAttributesImpl prepareTarget(XmlPullParser parser, QName name)
                throws XmlPullParserException {
            return new NewTypeSettableAttributesImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, NewTypeSettableAttributesImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_ID)) {
                    target.setCanSetId(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAME)) {
                    target.setCanSetLocalName(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCALNAMESPACE)) {
                    target.setCanSetLocalNamespace(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAYNAME)) {
                    target.setCanSetDisplayName(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYNAME)) {
                    target.setCanSetQueryName(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION)) {
                    target.setCanSetDescription(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATEABLE)) {
                    target.setCanSetCreatable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE)) {
                    target.setCanSetFileable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE)) {
                    target.setCanSetQueryable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXTINDEXED)) {
                    target.setCanSetFulltextIndexed(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDEDINSUPERTYTPEQUERY)) {
                    target.setCanSetIncludedInSupertypeQuery(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEPOLICY)) {
                    target.setCanSetControllablePolicy(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_CAP_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLABLEACL)) {
                    target.setCanSetControllableAcl(readBoolean(parser));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<AclCapabilitiesDataImpl> ACL_CAPABILITIES_PARSER = new XMLWalker<AclCapabilitiesDataImpl>() {
        @Override
        protected AclCapabilitiesDataImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new AclCapabilitiesDataImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, AclCapabilitiesDataImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_ACLCAP_SUPPORTED_PERMISSIONS)) {
                    target.setSupportedPermissions(readEnum(parser, SupportedPermissions.class));
                    return true;
                }

                if (isTag(name, TAG_ACLCAP_ACL_PROPAGATION)) {
                    target.setAclPropagation(readEnum(parser, AclPropagation.class));
                    return true;
                }

                if (isTag(name, TAG_ACLCAP_PERMISSIONS)) {
                    target.setPermissionDefinitionData(addToList(target.getPermissions(),
                            PERMISSION_DEFINITION_PARSER.walk(parser)));
                    return true;
                }

                if (isTag(name, TAG_ACLCAP_PERMISSION_MAPPING)) {
                    PermissionMapping pm = PERMISSION_MAPPING_PARSER.walk(parser);

                    Map<String, PermissionMapping> mapping = target.getPermissionMapping();
                    mapping.put(pm.getKey(), pm);

                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<PermissionDefinitionDataImpl> PERMISSION_DEFINITION_PARSER = new XMLWalker<PermissionDefinitionDataImpl>() {
        @Override
        protected PermissionDefinitionDataImpl prepareTarget(XmlPullParser parser, QName name)
                throws XmlPullParserException {
            return new PermissionDefinitionDataImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, PermissionDefinitionDataImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_ACLCAP_PERMISSION_PERMISSION)) {
                    target.setId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_ACLCAP_PERMISSION_DESCRIPTION)) {
                    target.setDescription(readText(parser));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<PermissionMappingDataImpl> PERMISSION_MAPPING_PARSER = new XMLWalker<PermissionMappingDataImpl>() {
        @Override
        protected PermissionMappingDataImpl prepareTarget(XmlPullParser parser, QName name)
                throws XmlPullParserException {
            return new PermissionMappingDataImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, PermissionMappingDataImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_ACLCAP_MAPPING_KEY)) {
                    target.setKey(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_ACLCAP_MAPPING_PERMISSION)) {
                    target.setPermissions(addToList(target.getPermissions(), readText(parser)));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<ExtensionFeatureImpl> EXTENDED_FEATURES_PARSER = new XMLWalker<ExtensionFeatureImpl>() {
        @Override
        protected ExtensionFeatureImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new ExtensionFeatureImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, ExtensionFeatureImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_FEATURE_ID)) {
                    target.setId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_FEATURE_URL)) {
                    target.setUrl(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_FEATURE_COMMON_NAME)) {
                    target.setCommonName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_FEATURE_VERSION_LABEL)) {
                    target.setVersionLabel(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_FEATURE_DESCRIPTION)) {
                    target.setDescription(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_FEATURE_DATA)) {
                    String[] data = FEATURE_DATA_PARSER.walk(parser);

                    Map<String, String> featureData = target.getFeatureData();
                    featureData.put(data[0], data[1]);

                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<String[]> FEATURE_DATA_PARSER = new XMLWalker<String[]>() {
        @Override
        protected String[] prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new String[2];
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, String[] target) throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_FEATURE_DATA_KEY)) {
                    target[0] = readText(parser);
                    return true;
                }

                if (isTag(name, TAG_FEATURE_DATA_VALUE)) {
                    target[1] = readText(parser);
                    return true;
                }
            }

            return false;
        }
    };

    // --------------------------
    // --- definition parsers ---
    // --------------------------

    private static final XMLWalker<AbstractTypeDefinition> TYPE_DEF_PARSER = new XMLWalker<AbstractTypeDefinition>() {
        @Override
        protected AbstractTypeDefinition prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {

            AbstractTypeDefinition result = null;

            String typeAttr = parser.getAttributeValue(NAMESPACE_XSI, "type");
            if (typeAttr != null) {
                if (typeAttr.endsWith(ATTR_DOCUMENT_TYPE)) {
                    result = new DocumentTypeDefinitionImpl();
                } else if (typeAttr.endsWith(ATTR_FOLDER_TYPE)) {
                    result = new FolderTypeDefinitionImpl();
                } else if (typeAttr.endsWith(ATTR_RELATIONSHIP_TYPE)) {
                    result = new RelationshipTypeDefinitionImpl();
                    ((RelationshipTypeDefinitionImpl) result).setAllowedSourceTypes(new ArrayList<String>());
                    ((RelationshipTypeDefinitionImpl) result).setAllowedTargetTypes(new ArrayList<String>());
                } else if (typeAttr.endsWith(ATTR_POLICY_TYPE)) {
                    result = new PolicyTypeDefinitionImpl();
                } else if (typeAttr.endsWith(ATTR_ITEM_TYPE)) {
                    result = new ItemTypeDefinitionImpl();
                } else if (typeAttr.endsWith(ATTR_SECONDARY_TYPE)) {
                    result = new SecondaryTypeDefinitionImpl();
                }
            }

            if (result == null) {
                throw new CmisInvalidArgumentException("Cannot read type definition!");
            }

            return result;
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, AbstractTypeDefinition target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_TYPE_ID)) {
                    target.setId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_LOCALNAME)) {
                    target.setLocalName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_LOCALNAMESPACE)) {
                    target.setLocalNamespace(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_DISPLAYNAME)) {
                    target.setDisplayName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_QUERYNAME)) {
                    target.setQueryName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_DESCRIPTION)) {
                    target.setDescription(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_BASE_ID)) {
                    BaseTypeId baseType = readEnum(parser, BaseTypeId.class);
                    if (baseType == null) {
                        throw new CmisInvalidArgumentException("Invalid base type!");
                    }

                    target.setBaseTypeId(baseType);
                    return true;
                }

                if (isTag(name, TAG_TYPE_PARENT_ID)) {
                    target.setParentTypeId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_CREATABLE)) {
                    target.setIsCreatable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_FILEABLE)) {
                    target.setIsFileable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_QUERYABLE)) {
                    target.setIsQueryable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_FULLTEXT_INDEXED)) {
                    target.setIsFulltextIndexed(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_INCLUDE_IN_SUPERTYPE_QUERY)) {
                    target.setIsIncludedInSupertypeQuery(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_CONTROLABLE_POLICY)) {
                    target.setIsControllablePolicy(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_CONTROLABLE_ACL)) {
                    target.setIsControllableAcl(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_TYPE_MUTABILITY)) {
                    target.setTypeMutability(TYPE_MUTABILITY_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_PROP_DEF_STRING) || isTag(name, TAG_TYPE_PROP_DEF_ID)
                        || isTag(name, TAG_TYPE_PROP_DEF_BOOLEAN) || isTag(name, TAG_TYPE_PROP_DEF_INTEGER)
                        || isTag(name, TAG_TYPE_PROP_DEF_DATETIME) || isTag(name, TAG_TYPE_PROP_DEF_DECIMAL)
                        || isTag(name, TAG_TYPE_PROP_DEF_HTML) || isTag(name, TAG_TYPE_PROP_DEF_URI)) {
                    target.addPropertyDefinition(PROPERTY_TYPE_PARSER.walk(parser));
                    return true;
                }

                if (target instanceof DocumentTypeDefinitionImpl) {
                    if (isTag(name, TAG_TYPE_VERSIONABLE)) {
                        ((DocumentTypeDefinitionImpl) target).setIsVersionable(readBoolean(parser));
                        return true;
                    }

                    if (isTag(name, TAG_TYPE_CONTENTSTREAM_ALLOWED)) {
                        ((DocumentTypeDefinitionImpl) target).setContentStreamAllowed(readEnum(parser,
                                ContentStreamAllowed.class));
                        return true;
                    }
                }

                if (target instanceof RelationshipTypeDefinitionImpl) {
                    if (isTag(name, TAG_TYPE_ALLOWED_SOURCE_TYPES)) {
                        RelationshipTypeDefinitionImpl relTarget = (RelationshipTypeDefinitionImpl) target;
                        relTarget
                                .setAllowedSourceTypes(addToList(relTarget.getAllowedSourceTypeIds(), readText(parser)));
                        return true;
                    }

                    if (isTag(name, TAG_TYPE_ALLOWED_TARGET_TYPES)) {
                        RelationshipTypeDefinitionImpl relTarget = (RelationshipTypeDefinitionImpl) target;
                        relTarget
                                .setAllowedTargetTypes(addToList(relTarget.getAllowedTargetTypeIds(), readText(parser)));
                        return true;
                    }
                }
            }

            return false;
        }
    };

    private static final XMLWalker<TypeMutabilityImpl> TYPE_MUTABILITY_PARSER = new XMLWalker<TypeMutabilityImpl>() {
        @Override
        protected TypeMutabilityImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new TypeMutabilityImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, TypeMutabilityImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_TYPE_TYPE_MUTABILITY_CREATE)) {
                    target.setCanCreate(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_TYPE_MUTABILITY_UPDATE)) {
                    target.setCanUpdate(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_TYPE_TYPE_MUTABILITY_DELETE)) {
                    target.setCanDelete(readBoolean(parser));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<AbstractPropertyDefinition<?>> PROPERTY_TYPE_PARSER = new XMLWalker<AbstractPropertyDefinition<?>>() {
        @Override
        protected AbstractPropertyDefinition<?> prepareTarget(XmlPullParser parser, QName name)
                throws XmlPullParserException {
            AbstractPropertyDefinition<?> result = null;

            if (isTag(name, TAG_TYPE_PROP_DEF_STRING)) {
                result = new PropertyStringDefinitionImpl();
            } else if (isTag(name, TAG_TYPE_PROP_DEF_ID)) {
                result = new PropertyIdDefinitionImpl();
            } else if (isTag(name, TAG_TYPE_PROP_DEF_BOOLEAN)) {
                result = new PropertyBooleanDefinitionImpl();
            } else if (isTag(name, TAG_TYPE_PROP_DEF_INTEGER)) {
                result = new PropertyIntegerDefinitionImpl();
            } else if (isTag(name, TAG_TYPE_PROP_DEF_DATETIME)) {
                result = new PropertyDateTimeDefinitionImpl();
            } else if (isTag(name, TAG_TYPE_PROP_DEF_DECIMAL)) {
                result = new PropertyDecimalDefinitionImpl();
            } else if (isTag(name, TAG_TYPE_PROP_DEF_HTML)) {
                result = new PropertyHtmlDefinitionImpl();
            } else if (isTag(name, TAG_TYPE_PROP_DEF_URI)) {
                result = new PropertyUriDefinitionImpl();
            }

            if (result == null) {
                throw new CmisInvalidArgumentException("Cannot read property type definition!");
            }

            return result;
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, AbstractPropertyDefinition<?> target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_PROPERTY_TYPE_ID)) {
                    target.setId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_LOCALNAME)) {
                    target.setLocalName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_LOCALNAMESPACE)) {
                    target.setLocalNamespace(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_DISPLAYNAME)) {
                    target.setDisplayName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_QUERYNAME)) {
                    target.setQueryName(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_DESCRIPTION)) {
                    target.setDescription(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_PROPERTY_TYPE)) {
                    PropertyType propType = readEnum(parser, PropertyType.class);
                    if (propType == null) {
                        throw new CmisInvalidArgumentException("Invalid property type!");
                    }

                    target.setPropertyType(propType);
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_CARDINALITY)) {
                    Cardinality cardinality = readEnum(parser, Cardinality.class);
                    if (cardinality == null) {
                        throw new CmisInvalidArgumentException("Invalid cardinality!");
                    }

                    target.setCardinality(cardinality);
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_UPDATABILITY)) {
                    Updatability updatability = readEnum(parser, Updatability.class);
                    if (updatability == null) {
                        throw new CmisInvalidArgumentException("Invalid updatability!");
                    }

                    target.setUpdatability(updatability);
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_INHERITED)) {
                    target.setIsInherited(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_REQUIRED)) {
                    target.setIsRequired(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_QUERYABLE)) {
                    target.setIsQueryable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_ORDERABLE)) {
                    target.setIsOrderable(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_OPENCHOICE)) {
                    target.setIsOpenChoice(readBoolean(parser));
                    return true;
                }

                if (target instanceof PropertyStringDefinitionImpl) {
                    if (isTag(name, TAG_PROPERTY_TYPE_DEAULT_VALUE)) {
                        PropertyString prop = PROPERTY_STRING_PARSER.walk(parser);
                        ((PropertyStringDefinitionImpl) target).setDefaultValue(prop.getValues());
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_CHOICE)) {
                        CHOICE_STRING_PARSER.addToChoiceList(parser, (PropertyStringDefinitionImpl) target);
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_MAX_LENGTH)) {
                        ((PropertyStringDefinitionImpl) target).setMaxLength(readInteger(parser));
                        return true;
                    }
                } else if (target instanceof PropertyIdDefinitionImpl) {
                    if (isTag(name, TAG_PROPERTY_TYPE_DEAULT_VALUE)) {
                        PropertyId prop = PROPERTY_ID_PARSER.walk(parser);
                        ((PropertyIdDefinitionImpl) target).setDefaultValue(prop.getValues());
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_CHOICE)) {
                        CHOICE_STRING_PARSER.addToChoiceList(parser, (PropertyIdDefinitionImpl) target);
                        return true;
                    }
                } else if (target instanceof PropertyBooleanDefinitionImpl) {
                    if (isTag(name, TAG_PROPERTY_TYPE_DEAULT_VALUE)) {
                        PropertyBoolean prop = PROPERTY_BOOLEAN_PARSER.walk(parser);
                        ((PropertyBooleanDefinitionImpl) target).setDefaultValue(prop.getValues());
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_CHOICE)) {
                        CHOICE_BOOLEAN_PARSER.addToChoiceList(parser, (PropertyBooleanDefinitionImpl) target);
                        return true;
                    }
                } else if (target instanceof PropertyIntegerDefinitionImpl) {
                    if (isTag(name, TAG_PROPERTY_TYPE_DEAULT_VALUE)) {
                        PropertyInteger prop = PROPERTY_INTEGER_PARSER.walk(parser);
                        ((PropertyIntegerDefinitionImpl) target).setDefaultValue(prop.getValues());
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_CHOICE)) {
                        CHOICE_INTEGER_PARSER.addToChoiceList(parser, (PropertyIntegerDefinitionImpl) target);
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_MAX_VALUE)) {
                        ((PropertyIntegerDefinitionImpl) target).setMaxValue(readInteger(parser));
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_MIN_VALUE)) {
                        ((PropertyIntegerDefinitionImpl) target).setMinValue(readInteger(parser));
                        return true;
                    }
                } else if (target instanceof PropertyDateTimeDefinitionImpl) {
                    if (isTag(name, TAG_PROPERTY_TYPE_DEAULT_VALUE)) {
                        PropertyDateTime prop = PROPERTY_DATETIME_PARSER.walk(parser);
                        ((PropertyDateTimeDefinitionImpl) target).setDefaultValue(prop.getValues());
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_CHOICE)) {
                        CHOICE_DATETIME_PARSER.addToChoiceList(parser, (PropertyDateTimeDefinitionImpl) target);
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_RESOLUTION)) {
                        ((PropertyDateTimeDefinitionImpl) target).setDateTimeResolution(readEnum(parser,
                                DateTimeResolution.class));
                        return true;
                    }
                } else if (target instanceof PropertyDecimalDefinitionImpl) {
                    if (isTag(name, TAG_PROPERTY_TYPE_DEAULT_VALUE)) {
                        PropertyDecimal prop = PROPERTY_DECIMAL_PARSER.walk(parser);
                        ((PropertyDecimalDefinitionImpl) target).setDefaultValue(prop.getValues());
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_CHOICE)) {
                        CHOICE_DECIMAL_PARSER.addToChoiceList(parser, (PropertyDecimalDefinitionImpl) target);
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_MAX_VALUE)) {
                        ((PropertyDecimalDefinitionImpl) target).setMaxValue(readDecimal(parser));
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_MIN_VALUE)) {
                        ((PropertyDecimalDefinitionImpl) target).setMinValue(readDecimal(parser));
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_PRECISION)) {
                        try {
                            ((PropertyDecimalDefinitionImpl) target).setPrecision(DecimalPrecision
                                    .fromValue(readInteger(parser)));
                        } catch (IllegalArgumentException e) {
                            // invalid enum value - ignore
                        }
                        return true;
                    }
                } else if (target instanceof PropertyHtmlDefinitionImpl) {
                    if (isTag(name, TAG_PROPERTY_TYPE_DEAULT_VALUE)) {
                        PropertyHtml prop = PROPERTY_HTML_PARSER.walk(parser);
                        ((PropertyHtmlDefinitionImpl) target).setDefaultValue(prop.getValues());
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_CHOICE)) {
                        CHOICE_STRING_PARSER.addToChoiceList(parser, (PropertyHtmlDefinitionImpl) target);
                        return true;
                    }
                } else if (target instanceof PropertyUriDefinitionImpl) {
                    if (isTag(name, TAG_PROPERTY_TYPE_DEAULT_VALUE)) {
                        PropertyUri prop = PROPERTY_URI_PARSER.walk(parser);
                        ((PropertyUriDefinitionImpl) target).setDefaultValue(prop.getValues());
                        return true;
                    }

                    if (isTag(name, TAG_PROPERTY_TYPE_CHOICE)) {
                        CHOICE_STRING_PARSER.addToChoiceList(parser, (PropertyUriDefinitionImpl) target);
                        return true;
                    }
                }
            }

            return false;
        }
    };

    private static final ChoiceAtomPubXMLWalker<String> CHOICE_STRING_PARSER = new ChoiceAtomPubXMLWalker<String>() {
        @Override
        protected ChoiceImpl<String> createTarget(XmlPullParser parser, QName name) {
            return new ChoiceImpl<String>();
        }

        @Override
        protected void addValue(XmlPullParser parser, ChoiceImpl<String> target) throws XmlPullParserException {
            target.setValue(addToList(target.getValue(), readText(parser)));
        }

        @Override
        protected void addChoice(XmlPullParser parser, ChoiceImpl<String> target) throws XmlPullParserException {
            target.setChoice(addToList(target.getChoice(), CHOICE_STRING_PARSER.walk(parser)));
        }
    };

    private static final ChoiceAtomPubXMLWalker<Boolean> CHOICE_BOOLEAN_PARSER = new ChoiceAtomPubXMLWalker<Boolean>() {
        @Override
        protected ChoiceImpl<Boolean> createTarget(XmlPullParser parser, QName name) {
            return new ChoiceImpl<Boolean>();
        }

        @Override
        protected void addValue(XmlPullParser parser, ChoiceImpl<Boolean> target) throws XmlPullParserException {
            target.setValue(addToList(target.getValue(), readBoolean(parser)));
        }

        @Override
        protected void addChoice(XmlPullParser parser, ChoiceImpl<Boolean> target) throws XmlPullParserException {
            target.setChoice(addToList(target.getChoice(), CHOICE_BOOLEAN_PARSER.walk(parser)));
        }
    };

    private static final ChoiceAtomPubXMLWalker<BigInteger> CHOICE_INTEGER_PARSER = new ChoiceAtomPubXMLWalker<BigInteger>() {
        @Override
        protected ChoiceImpl<BigInteger> createTarget(XmlPullParser parser, QName name) {
            return new ChoiceImpl<BigInteger>();
        }

        @Override
        protected void addValue(XmlPullParser parser, ChoiceImpl<BigInteger> target) throws XmlPullParserException {
            target.setValue(addToList(target.getValue(), readInteger(parser)));
        }

        @Override
        protected void addChoice(XmlPullParser parser, ChoiceImpl<BigInteger> target) throws XmlPullParserException {
            target.setChoice(addToList(target.getChoice(), CHOICE_INTEGER_PARSER.walk(parser)));
        }
    };

    private static final ChoiceAtomPubXMLWalker<GregorianCalendar> CHOICE_DATETIME_PARSER = new ChoiceAtomPubXMLWalker<GregorianCalendar>() {
        @Override
        protected ChoiceImpl<GregorianCalendar> createTarget(XmlPullParser parser, QName name) {
            return new ChoiceImpl<GregorianCalendar>();
        }

        @Override
        protected void addValue(XmlPullParser parser, ChoiceImpl<GregorianCalendar> target)
                throws XmlPullParserException {
            target.setValue(addToList(target.getValue(), readDateTime(parser)));
        }

        @Override
        protected void addChoice(XmlPullParser parser, ChoiceImpl<GregorianCalendar> target)
                throws XmlPullParserException {
            target.setChoice(addToList(target.getChoice(), CHOICE_DATETIME_PARSER.walk(parser)));
        }
    };

    private static final ChoiceAtomPubXMLWalker<BigDecimal> CHOICE_DECIMAL_PARSER = new ChoiceAtomPubXMLWalker<BigDecimal>() {
        @Override
        protected ChoiceImpl<BigDecimal> createTarget(XmlPullParser parser, QName name) {
            return new ChoiceImpl<BigDecimal>();
        }

        @Override
        protected void addValue(XmlPullParser parser, ChoiceImpl<BigDecimal> target) throws XmlPullParserException {
            target.setValue(addToList(target.getValue(), readDecimal(parser)));
        }

        @Override
        protected void addChoice(XmlPullParser parser, ChoiceImpl<BigDecimal> target) throws XmlPullParserException {
            target.setChoice(addToList(target.getChoice(), CHOICE_DECIMAL_PARSER.walk(parser)));
        }
    };

    private abstract static class ChoiceAtomPubXMLWalker<T> extends XMLWalker<ChoiceImpl<T>> {

        public void addToChoiceList(XmlPullParser parser, AbstractPropertyDefinition<T> propDef)
                throws XmlPullParserException {
            propDef.setChoices(addToList(propDef.getChoices(), walk(parser)));
        }

        protected abstract ChoiceImpl<T> createTarget(XmlPullParser parser, QName name);

        @Override
        protected ChoiceImpl<T> prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            ChoiceImpl<T> result = createTarget(parser, name);

            if (parser.getAttributeCount() > 0) {
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    String attr = parser.getAttributeName(i);
                    if (ATTR_PROPERTY_TYPE_CHOICE_DISPLAYNAME.equals(attr)) {
                        result.setDisplayName(parser.getAttributeValue(i));
                    }
                }
            }

            return result;
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, ChoiceImpl<T> target) throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_PROPERTY_TYPE_CHOICE_VALUE)) {
                    addValue(parser, target);
                    return true;
                }

                if (isTag(name, TAG_PROPERTY_TYPE_CHOICE_CHOICE)) {
                    addChoice(parser, target);
                    return true;
                }
            }

            return false;
        }

        protected abstract void addValue(XmlPullParser parser, ChoiceImpl<T> target) throws XmlPullParserException;

        protected abstract void addChoice(XmlPullParser parser, ChoiceImpl<T> target) throws XmlPullParserException;
    }

    // ---------------------------------
    // --- objects and lists parsers ---
    // ---------------------------------

    private static final XMLWalker<ObjectDataImpl> OBJECT_PARSER = new XMLWalker<ObjectDataImpl>() {
        @Override
        protected ObjectDataImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new ObjectDataImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, ObjectDataImpl target) throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_OBJECT_PROPERTIES)) {
                    target.setProperties(PROPERTIES_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_OBJECT_ALLOWABLE_ACTIONS)) {
                    target.setAllowableActions(ALLOWABLE_ACTIONS_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_OBJECT_RELATIONSHIP)) {
                    target.setRelationships(addToList(target.getRelationships(), OBJECT_PARSER.walk(parser)));
                    return true;
                }

                if (isTag(name, TAG_OBJECT_CHANGE_EVENT_INFO)) {
                    target.setChangeEventInfo(CHANGE_EVENT_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_OBJECT_ACL)) {
                    target.setAcl(ACL_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_OBJECT_EXACT_ACL)) {
                    target.setIsExactAcl(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_OBJECT_POLICY_IDS)) {
                    target.setPolicyIds(POLICY_IDS_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_OBJECT_RENDITION)) {
                    target.setRenditions(addToList(target.getRenditions(), RENDITION_PARSER.walk(parser)));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<PropertiesImpl> PROPERTIES_PARSER = new XMLWalker<PropertiesImpl>() {
        @Override
        protected PropertiesImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new PropertiesImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, PropertiesImpl target) throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_PROP_STRING)) {
                    target.addProperty(PROPERTY_STRING_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_PROP_ID)) {
                    target.addProperty(PROPERTY_ID_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_PROP_BOOLEAN)) {
                    target.addProperty(PROPERTY_BOOLEAN_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_PROP_INTEGER)) {
                    target.addProperty(PROPERTY_INTEGER_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_PROP_DATETIME)) {
                    target.addProperty(PROPERTY_DATETIME_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_PROP_DECIMAL)) {
                    target.addProperty(PROPERTY_DECIMAL_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_PROP_HTML)) {
                    target.addProperty(PROPERTY_HTML_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_PROP_URI)) {
                    target.addProperty(PROPERTY_URI_PARSER.walk(parser));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<AllowableActionsImpl> ALLOWABLE_ACTIONS_PARSER = new XMLWalker<AllowableActionsImpl>() {
        @Override
        protected AllowableActionsImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new AllowableActionsImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, AllowableActionsImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                try {
                    Action action = Action.fromValue(name.getLocalPart());

                    Set<Action> actions = target.getAllowableActions();

                    if (Boolean.TRUE.equals(readBoolean(parser))) {
                        actions.add(action);
                    }

                    return true;
                } catch (IllegalArgumentException e) {
                    // extension tag -> ignore
                }
            }

            return false;
        }
    };

    private static final XMLWalker<ChangeEventInfoDataImpl> CHANGE_EVENT_PARSER = new XMLWalker<ChangeEventInfoDataImpl>() {
        @Override
        protected ChangeEventInfoDataImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new ChangeEventInfoDataImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, ChangeEventInfoDataImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_CHANGE_EVENT_TYPE)) {
                    target.setChangeType(readEnum(parser, ChangeType.class));
                    return true;
                }

                if (isTag(name, TAG_CHANGE_EVENT_TIME)) {
                    target.setChangeTime(readDateTime(parser));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<AccessControlListImpl> ACL_PARSER = new XMLWalker<AccessControlListImpl>() {
        @Override
        protected AccessControlListImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new AccessControlListImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, AccessControlListImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_ACL_PERMISSISONS)) {
                    target.setAces(addToList(target.getAces(), ACE_PARSER.walk(parser)));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<AccessControlEntryImpl> ACE_PARSER = new XMLWalker<AccessControlEntryImpl>() {
        @Override
        protected AccessControlEntryImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new AccessControlEntryImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, AccessControlEntryImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_ACE_PRINCIPAL)) {
                    target.setPrincipal(PRINCIPAL_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_ACE_PERMISSIONS)) {
                    target.setPermissions(addToList(target.getPermissions(), readText(parser)));
                    return true;
                }

                if (isTag(name, TAG_ACE_IS_DIRECT)) {
                    target.setDirect(readBoolean(parser));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<AccessControlPrincipalDataImpl> PRINCIPAL_PARSER = new XMLWalker<AccessControlPrincipalDataImpl>() {
        @Override
        protected AccessControlPrincipalDataImpl prepareTarget(XmlPullParser parser, QName name)
                throws XmlPullParserException {
            return new AccessControlPrincipalDataImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, AccessControlPrincipalDataImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_ACE_PRINCIPAL_ID)) {
                    target.setId(readText(parser));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<PolicyIdListImpl> POLICY_IDS_PARSER = new XMLWalker<PolicyIdListImpl>() {
        @Override
        protected PolicyIdListImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new PolicyIdListImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, PolicyIdListImpl target) throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_POLICY_ID)) {
                    target.setPolicyIds(addToList(target.getPolicyIds(), readText(parser)));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<RenditionDataImpl> RENDITION_PARSER = new XMLWalker<RenditionDataImpl>() {
        @Override
        protected RenditionDataImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new RenditionDataImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, RenditionDataImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_RENDITION_STREAM_ID)) {
                    target.setStreamId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_RENDITION_MIMETYPE)) {
                    target.setMimeType(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_RENDITION_LENGTH)) {
                    target.setBigLength(readInteger(parser));
                    return true;
                }

                if (isTag(name, TAG_RENDITION_KIND)) {
                    target.setKind(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_RENDITION_TITLE)) {
                    target.setTitle(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_RENDITION_HEIGHT)) {
                    target.setBigHeight(readInteger(parser));
                    return true;
                }

                if (isTag(name, TAG_RENDITION_WIDTH)) {
                    target.setBigWidth(readInteger(parser));
                    return true;
                }

                if (isTag(name, TAG_RENDITION_DOCUMENT_ID)) {
                    target.setRenditionDocumentId(readText(parser));
                    return true;
                }
            }

            return false;
        }
    };

    // ------------------------
    // --- property parsers ---
    // ------------------------

    private static final PropertyAtomPubXMLWalker<PropertyStringImpl> PROPERTY_STRING_PARSER = new PropertyStringAtomPubXMLWalker<PropertyStringImpl>() {
        @Override
        protected PropertyStringImpl createTarget(XmlPullParser parser, QName name) {
            return new PropertyStringImpl();
        }
    };

    private static final PropertyAtomPubXMLWalker<PropertyIdImpl> PROPERTY_ID_PARSER = new PropertyStringAtomPubXMLWalker<PropertyIdImpl>() {
        @Override
        protected PropertyIdImpl createTarget(XmlPullParser parser, QName name) {
            return new PropertyIdImpl();
        }
    };

    private static final PropertyAtomPubXMLWalker<PropertyHtmlImpl> PROPERTY_HTML_PARSER = new PropertyStringAtomPubXMLWalker<PropertyHtmlImpl>() {
        @Override
        protected PropertyHtmlImpl createTarget(XmlPullParser parser, QName name) {
            return new PropertyHtmlImpl();
        }
    };

    private static final PropertyAtomPubXMLWalker<PropertyUriImpl> PROPERTY_URI_PARSER = new PropertyStringAtomPubXMLWalker<PropertyUriImpl>() {
        @Override
        protected PropertyUriImpl createTarget(XmlPullParser parser, QName name) {
            return new PropertyUriImpl();
        }
    };

    private static final PropertyAtomPubXMLWalker<PropertyBooleanImpl> PROPERTY_BOOLEAN_PARSER = new PropertyAtomPubXMLWalker<PropertyBooleanImpl>() {
        @Override
        protected PropertyBooleanImpl createTarget(XmlPullParser parser, QName name) {
            return new PropertyBooleanImpl();
        }

        @Override
        protected void addValue(XmlPullParser parser, PropertyBooleanImpl target) throws XmlPullParserException {
            target.setValues(addToList(target.getValues(), readBoolean(parser)));
        }
    };

    private static final PropertyAtomPubXMLWalker<PropertyIntegerImpl> PROPERTY_INTEGER_PARSER = new PropertyAtomPubXMLWalker<PropertyIntegerImpl>() {
        @Override
        protected PropertyIntegerImpl createTarget(XmlPullParser parser, QName name) {
            return new PropertyIntegerImpl();
        }

        @Override
        protected void addValue(XmlPullParser parser, PropertyIntegerImpl target) throws XmlPullParserException {
            target.setValues(addToList(target.getValues(), readInteger(parser)));
        }
    };

    private static final PropertyAtomPubXMLWalker<PropertyDecimalImpl> PROPERTY_DECIMAL_PARSER = new PropertyAtomPubXMLWalker<PropertyDecimalImpl>() {
        @Override
        protected PropertyDecimalImpl createTarget(XmlPullParser parser, QName name) {
            return new PropertyDecimalImpl();
        }

        @Override
        protected void addValue(XmlPullParser parser, PropertyDecimalImpl target) throws XmlPullParserException {
            target.setValues(addToList(target.getValues(), readDecimal(parser)));
        }
    };

    private static final PropertyAtomPubXMLWalker<PropertyDateTimeImpl> PROPERTY_DATETIME_PARSER = new PropertyAtomPubXMLWalker<PropertyDateTimeImpl>() {
        @Override
        protected PropertyDateTimeImpl createTarget(XmlPullParser parser, QName name) {
            return new PropertyDateTimeImpl();
        }

        @Override
        protected void addValue(XmlPullParser parser, PropertyDateTimeImpl target) throws XmlPullParserException {
            target.setValues(addToList(target.getValues(), readDateTime(parser)));
        }
    };

    private abstract static class PropertyAtomPubXMLWalker<T extends AbstractPropertyData<?>> extends XMLWalker<T> {

        protected abstract T createTarget(XmlPullParser parser, QName name);

        @Override
        protected T prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            T result = createTarget(parser, name);

            if (parser.getAttributeCount() > 0) {
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    String attr = parser.getAttributeName(i);
                    if (ATTR_PROPERTY_ID.equals(attr)) {
                        result.setId(parser.getAttributeValue(i));
                    } else if (ATTR_PROPERTY_LOCALNAME.equals(attr)) {
                        result.setLocalName(parser.getAttributeValue(i));
                    } else if (ATTR_PROPERTY_DISPLAYNAME.equals(attr)) {
                        result.setDisplayName(parser.getAttributeValue(i));
                    } else if (ATTR_PROPERTY_QUERYNAME.equals(attr)) {
                        result.setQueryName(parser.getAttributeValue(i));
                    }
                }
            }

            return result;
        }

        protected abstract void addValue(XmlPullParser parser, T target) throws XmlPullParserException;

        @Override
        protected boolean read(XmlPullParser parser, QName name, T target) throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_PROPERTY_VALUE)) {
                    addValue(parser, target);
                    return true;
                }
            }

            return false;
        }

    }

    private abstract static class PropertyStringAtomPubXMLWalker<T extends AbstractPropertyData<String>> extends
            PropertyAtomPubXMLWalker<T> {
        @Override
        protected void addValue(XmlPullParser parser, T target) throws XmlPullParserException {
            target.setValues(addToList(target.getValues(), readText(parser)));
        }
    }

    // --------------------
    // --- query parser ---
    // --------------------

    private static final XMLWalker<QueryTypeImpl> QUERY_PARSER = new XMLWalker<QueryTypeImpl>() {
        @Override
        protected QueryTypeImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new QueryTypeImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, QueryTypeImpl target) throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_QUERY_STATEMENT)) {
                    target.setStatement(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_QUERY_SEARCHALLVERSIONS)) {
                    target.setSearchAllVersions(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_QUERY_INCLUDEALLOWABLEACTIONS)) {
                    target.setIncludeAllowableActions(readBoolean(parser));
                    return true;
                }

                if (isTag(name, TAG_QUERY_INCLUDERELATIONSHIPS)) {
                    target.setIncludeRelationships(readEnum(parser, IncludeRelationships.class));
                    return true;
                }

                if (isTag(name, TAG_QUERY_RENDITIONFILTER)) {
                    target.setRenditionFilter(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_QUERY_MAXITEMS)) {
                    target.setMaxItems(readInteger(parser));
                    return true;
                }

                if (isTag(name, TAG_QUERY_SKIPCOUNT)) {
                    target.setSkipCount(readInteger(parser));
                    return true;
                }
            }

            return false;
        }
    };

    // --------------------------
    // --- bulk update parser ---
    // --------------------------

    private static final XMLWalker<BulkUpdateImpl> BULK_UPDATE_PARSER = new XMLWalker<BulkUpdateImpl>() {
        @Override
        protected BulkUpdateImpl prepareTarget(XmlPullParser parser, QName name) throws XmlPullParserException {
            return new BulkUpdateImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, BulkUpdateImpl target) throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_BULK_UPDATE_ID_AND_TOKEN)) {
                    target.setObjectIdAndChangeToken(addToList(target.getObjectIdAndChangeToken(),
                            ID_AND_TOKEN_PARSER.walk(parser)));
                    return true;
                }

                if (isTag(name, TAG_BULK_UPDATE_PROPERTIES)) {
                    target.setProperties(PROPERTIES_PARSER.walk(parser));
                    return true;
                }

                if (isTag(name, TAG_BULK_UPDATE_ADD_SECONDARY_TYPES)) {
                    target.setAddSecondaryTypeIds(addToList(target.getAddSecondaryTypeIds(), readText(parser)));
                    return true;
                }

                if (isTag(name, TAG_BULK_UPDATE_REMOVE_SECONDARY_TYPES)) {
                    target.setRemoveSecondaryTypeIds(addToList(target.getRemoveSecondaryTypeIds(), readText(parser)));
                    return true;
                }
            }

            return false;
        }
    };

    private static final XMLWalker<BulkUpdateObjectIdAndChangeTokenImpl> ID_AND_TOKEN_PARSER = new XMLWalker<BulkUpdateObjectIdAndChangeTokenImpl>() {
        @Override
        protected BulkUpdateObjectIdAndChangeTokenImpl prepareTarget(XmlPullParser parser, QName name)
                throws XmlPullParserException {
            return new BulkUpdateObjectIdAndChangeTokenImpl();
        }

        @Override
        protected boolean read(XmlPullParser parser, QName name, BulkUpdateObjectIdAndChangeTokenImpl target)
                throws XmlPullParserException {
            if (isCmisNamespace(name)) {
                if (isTag(name, TAG_IDANDTOKEN_ID)) {
                    target.setId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_IDANDTOKEN_NEWID)) {
                    target.setNewId(readText(parser));
                    return true;
                }

                if (isTag(name, TAG_IDANDTOKEN_CHANGETOKEN)) {
                    target.setChangeToken(readText(parser));
                    return true;
                }
            }

            return false;
        }
    };
}
