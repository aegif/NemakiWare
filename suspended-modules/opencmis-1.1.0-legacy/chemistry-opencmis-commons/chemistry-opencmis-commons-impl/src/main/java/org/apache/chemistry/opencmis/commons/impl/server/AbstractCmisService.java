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
package org.apache.chemistry.opencmis.commons.impl.server;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyBoolean;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyDateTime;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyInteger;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.data.RepositoryCapabilities;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.ObjectInfo;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.commons.server.RenditionInfo;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractCmisService implements CmisService, ObjectInfoHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractCmisService.class);

    private Map<String, ObjectInfo> objectInfoMap;
    private boolean addObjectInfos = true;

    // --- repository service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is required. Convenience implementation is present.</li>
     * </ul>
     */
    @Override
    public RepositoryInfo getRepositoryInfo(String repositoryId, ExtensionsData extension) {
        RepositoryInfo result = null;

        List<RepositoryInfo> repositories = getRepositoryInfos(extension);
        if (repositories != null) {
            for (RepositoryInfo ri : repositories) {
                if (ri.getId().equals(repositoryId)) {
                    result = ri;
                    break;
                }
            }
        }

        if (result == null) {
            throw new CmisObjectNotFoundException("Repository '" + repositoryId + "' does not exist!");
        }

        return result;
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is required.</li>
     * </ul>
     */
    @Override
    public abstract List<RepositoryInfo> getRepositoryInfos(ExtensionsData extension);

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is required.</li>
     * </ul>
     */
    @Override
    public abstract TypeDefinitionList getTypeChildren(String repositoryId, String typeId,
            Boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional. Convenience implementation is present.</li>
     * </ul>
     */
    @Override
    public List<TypeDefinitionContainer> getTypeDescendants(String repositoryId, String typeId, BigInteger depth,
            Boolean includePropertyDefinitions, ExtensionsData extension) {
        // check depth
        int d = (depth == null ? -1 : depth.intValue());
        if (d == 0) {
            throw new CmisInvalidArgumentException("Depth must not be 0!");
        }
        if (typeId == null) {
            d = -1;
        }

        List<TypeDefinitionContainer> result = new ArrayList<TypeDefinitionContainer>();

        TypeDefinitionList children = getTypeChildren(repositoryId, typeId, includePropertyDefinitions,
                BigInteger.valueOf(Integer.MAX_VALUE), BigInteger.ZERO, null);

        if (children != null && isNotEmpty(children.getList())) {
            for (TypeDefinition td : children.getList()) {
                TypeDefinitionContainerImpl tdc = new TypeDefinitionContainerImpl(td);
                addTypeChildren(repositoryId, includePropertyDefinitions, (d > 0 ? d - 1 : -1), tdc);
                result.add(tdc);
            }
        }

        return result;
    }

    /**
     * Helper method for
     * {@link #getTypeDescendants(String, String, BigInteger, Boolean, ExtensionsData)}
     * .
     */
    private void addTypeChildren(String repositoryId, Boolean includePropertyDefinitions, int depth,
            TypeDefinitionContainerImpl container) {

        if (depth == 0) {
            return;
        }

        TypeDefinitionList children = getTypeChildren(repositoryId, container.getTypeDefinition().getId(),
                includePropertyDefinitions, BigInteger.valueOf(Integer.MAX_VALUE), BigInteger.ZERO, null);

        if (children != null && isNotEmpty(children.getList())) {
            List<TypeDefinitionContainer> list = new ArrayList<TypeDefinitionContainer>();
            container.setChildren(list);

            for (TypeDefinition td : children.getList()) {
                TypeDefinitionContainerImpl tdc = new TypeDefinitionContainerImpl(td);
                addTypeChildren(repositoryId, includePropertyDefinitions, (depth > 0 ? depth - 1 : -1), tdc);
                list.add(tdc);
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is required.</li>
     * </ul>
     */
    @Override
    public abstract TypeDefinition getTypeDefinition(String repositoryId, String typeId, ExtensionsData extension);

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Introduced in CMIS 1.1</li>
     * </ul>
     */
    @Override
    public TypeDefinition createType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Introduced in CMIS 1.1</li>
     * </ul>
     */
    @Override
    public TypeDefinition updateType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Introduced in CMIS 1.1</li>
     * </ul>
     */
    @Override
    public void deleteType(String repositoryId, String typeId, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- navigation service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is required.</li>
     * <li>Object infos should contain the folder and all returned children.</li>
     * </ul>
     */
    @Override
    public abstract ObjectInFolderList getChildren(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePathSegment, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the folder and all returned descendants.</li>
     * </ul>
     */
    @Override
    public List<ObjectInFolderContainer> getDescendants(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the folder and all returned descendants.</li>
     * </ul>
     */
    @Override
    public List<ObjectInFolderContainer> getFolderTree(String repositoryId, String folderId, BigInteger depth,
            String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePathSegment, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is required.</li>
     * <li>Object infos should contain the object and all returned parents.</li>
     * </ul>
     */
    @Override
    public abstract List<ObjectParentData> getObjectParents(String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includeRelativePathSegment, ExtensionsData extension);

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the returned parent folder.</li>
     * </ul>
     */
    @Override
    public ObjectData getFolderParent(String repositoryId, String folderId, String filter, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the folder and the returned objects.</li>
     * </ul>
     */
    @Override
    public ObjectList getCheckedOutDocs(String repositoryId, String folderId, String filter, String orderBy,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- object service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub</li>
     * <li>Implementation is optional. Convenience implementation is present.</li>
     * <li>Object infos should contain the newly created object.</li>
     * </ul>
     */
    @Override
    public String create(String repositoryId, Properties properties, String folderId, ContentStream contentStream,
            VersioningState versioningState, List<String> policies, ExtensionsData extension) {
        // check properties
        if (properties == null || properties.getProperties() == null) {
            throw new CmisInvalidArgumentException("Properties must be set!");
        }

        // check object type id
        PropertyData<?> obbjectTypeIdProperty = properties.getProperties().get(PropertyIds.OBJECT_TYPE_ID);
        if (obbjectTypeIdProperty == null || !(obbjectTypeIdProperty.getFirstValue() instanceof String)) {
            throw new CmisInvalidArgumentException("Property '" + PropertyIds.OBJECT_TYPE_ID + "' must be set!");
        }

        // get the type
        String objectTypeId = obbjectTypeIdProperty.getFirstValue().toString();
        TypeDefinition type = getTypeDefinition(repositoryId, objectTypeId, null);

        // create object
        String newId;
        switch (type.getBaseTypeId()) {
        case CMIS_DOCUMENT:
            newId = createDocument(repositoryId, properties, folderId, contentStream, versioningState, policies, null,
                    null, extension);
            break;
        case CMIS_FOLDER:
            newId = createFolder(repositoryId, properties, folderId, policies, null, null, extension);
            break;
        case CMIS_POLICY:
            newId = createPolicy(repositoryId, properties, folderId, policies, null, null, extension);
            break;
        case CMIS_ITEM:
            newId = createItem(repositoryId, properties, folderId, policies, null, null, extension);
            break;
        default:
            newId = null;
        }

        // check new object id
        if (newId == null) {
            throw new CmisRuntimeException("Creation failed!");
        }

        // return the new object id
        return newId;
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public String createDocument(String repositoryId, Properties properties, String folderId,
            ContentStream contentStream, VersioningState versioningState, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public String createDocumentFromSource(String repositoryId, String sourceId, Properties properties,
            String folderId, VersioningState versioningState, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public String createFolder(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the newly created object.</li>
     * </ul>
     */
    @Override
    public String createRelationship(String repositoryId, Properties properties, List<String> policies, Acl addAces,
            Acl removeAces, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public String createPolicy(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public String createItem(String repositoryId, Properties properties, String folderId, List<String> policies,
            Acl addAces, Acl removeAces, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional. Convenience implementation is present.</li>
     * </ul>
     */
    @Override
    public AllowableActions getAllowableActions(String repositoryId, String objectId, ExtensionsData extension) {
        return getObject(repositoryId, objectId, "cmis:objectId", true, IncludeRelationships.NONE, "cmis:none", false,
                false, extension).getAllowableActions();
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is required.</li>
     * <li>Object infos should contain the returned object.</li>
     * </ul>
     */
    @Override
    public abstract ObjectData getObject(String repositoryId, String objectId, String filter,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension);

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional. Convenience implementation is present.</li>
     * </ul>
     */
    @Override
    public Properties getProperties(String repositoryId, String objectId, String filter, ExtensionsData extension) {
        return getObject(repositoryId, objectId, filter, false, IncludeRelationships.NONE, "cmis:none", false, false,
                extension).getProperties();
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public List<RenditionData> getRenditions(String repositoryId, String objectId, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the returned object.</li>
     * </ul>
     */
    @Override
    public ObjectData getObjectByPath(String repositoryId, String path, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public ContentStream getContentStream(String repositoryId, String objectId, String streamId, BigInteger offset,
            BigInteger length, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the updated object.</li>
     * </ul>
     */
    @Override
    public void updateProperties(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            Properties properties, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Introduced in CMIS 1.1</li>
     * </ul>
     */
    @Override
    public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(String repositoryId,
            List<BulkUpdateObjectIdAndChangeToken> objectIdAndChangeToken, Properties properties,
            List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the moved object.</li>
     * </ul>
     */
    @Override
    public void moveObject(String repositoryId, Holder<String> objectId, String targetFolderId, String sourceFolderId,
            ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional. Convenience implementation is present
     * (forwards to
     * {@link #deleteObjectOrCancelCheckOut(String, String, Boolean, ExtensionsData)}
     * ).</li>
     * </ul>
     */
    @Override
    public void deleteObject(String repositoryId, String objectId, Boolean allVersions, ExtensionsData extension) {
        deleteObjectOrCancelCheckOut(repositoryId, objectId, allVersions, extension);
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public void deleteObjectOrCancelCheckOut(String repositoryId, String objectId, Boolean allVersions,
            ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public FailedToDeleteData deleteTree(String repositoryId, String folderId, Boolean allVersions,
            UnfileObject unfileObjects, Boolean continueOnFailure, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public void setContentStream(String repositoryId, Holder<String> objectId, Boolean overwriteFlag,
            Holder<String> changeToken, ContentStream contentStream, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Introduced in CMIS 1.1</li>
     * </ul>
     */
    @Override
    public void appendContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ContentStream contentStream, boolean isLastChunk, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public void deleteContentStream(String repositoryId, Holder<String> objectId, Holder<String> changeToken,
            ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- versioning service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the checked out object.</li>
     * </ul>
     */
    @Override
    public void checkOut(String repositoryId, Holder<String> objectId, ExtensionsData extension,
            Holder<Boolean> contentCopied) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public void cancelCheckOut(String repositoryId, String objectId, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the checked in object.</li>
     * </ul>
     */
    @Override
    public void checkIn(String repositoryId, Holder<String> objectId, Boolean major, Properties properties,
            ContentStream contentStream, String checkinComment, List<String> policies, Acl addAces, Acl removeAces,
            ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the returned object.</li>
     * </ul>
     */
    @Override
    public ObjectData getObjectOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, Boolean includeAllowableActions, IncludeRelationships includeRelationships,
            String renditionFilter, Boolean includePolicyIds, Boolean includeAcl, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Local</li>
     * <li>Implementation is optional. Convenience implementation is present, if
     * {@link #getObjectOfLatestVersion(String, String, String, Boolean, String, Boolean, IncludeRelationships, String, Boolean, Boolean, ExtensionsData)}
     * is implemented.</li>
     * </ul>
     */
    @Override
    public Properties getPropertiesOfLatestVersion(String repositoryId, String objectId, String versionSeriesId,
            Boolean major, String filter, ExtensionsData extension) {
        return getObjectOfLatestVersion(repositoryId, objectId, versionSeriesId, major, filter, false,
                IncludeRelationships.NONE, "cmis:none", false, false, extension).getProperties();
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the returned objects.</li>
     * </ul>
     */
    @Override
    public List<ObjectData> getAllVersions(String repositoryId, String objectId, String versionSeriesId, String filter,
            Boolean includeAllowableActions, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- discovery service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the returned objects.</li>
     * </ul>
     */
    @Override
    public ObjectList getContentChanges(String repositoryId, Holder<String> changeLogToken, Boolean includeProperties,
            String filter, Boolean includePolicyIds, Boolean includeAcl, BigInteger maxItems, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public ObjectList query(String repositoryId, String statement, Boolean searchAllVersions,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- multi filing service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the added object.</li>
     * </ul>
     */
    @Override
    public void addObjectToFolder(String repositoryId, String objectId, String folderId, Boolean allVersions,
            ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the removed object.</li>
     * </ul>
     */
    @Override
    public void removeObjectFromFolder(String repositoryId, String objectId, String folderId, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- relationship service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the object and the returned relationship
     * objects.</li>
     * </ul>
     */
    @Override
    public ObjectList getObjectRelationships(String repositoryId, String objectId, Boolean includeSubRelationshipTypes,
            RelationshipDirection relationshipDirection, String typeId, String filter, Boolean includeAllowableActions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- ACL service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public Acl applyAcl(String repositoryId, String objectId, Acl addAces, Acl removeAces,
            AclPropagation aclPropagation, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public Acl applyAcl(String repositoryId, String objectId, Acl aces, AclPropagation aclPropagation) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public Acl getAcl(String repositoryId, String objectId, Boolean onlyBasicPermissions, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- policy service ---

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the applied policy object.</li>
     * </ul>
     */
    @Override
    public void applyPolicy(String repositoryId, String policyId, String objectId, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * <li>Object infos should contain the returned policy objects.</li>
     * </ul>
     */
    @Override
    public List<ObjectData> getAppliedPolicies(String repositoryId, String objectId, String filter,
            ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub, Web Services, Browser, Local</li>
     * <li>Implementation is optional.</li>
     * </ul>
     */
    @Override
    public void removePolicy(String repositoryId, String policyId, String objectId, ExtensionsData extension) {
        throw new CmisNotSupportedException("Not supported!");
    }

    // --- server specific ---

    /**
     * Returns the object info map.
     */
    private Map<String, ObjectInfo> getObjectInfoMap() {
        if (objectInfoMap == null) {
            objectInfoMap = new HashMap<String, ObjectInfo>(2);
        }

        return objectInfoMap;
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>Implementation Hints:</b>
     * <ul>
     * <li>Bindings: AtomPub</li>
     * <li>If the object info is not found, the object info will be assembled.
     * To do that the repository info, the object, the object parent, the object
     * history and the base type definitions will be fetched. If you want to
     * change this behavior, override this method.</li>
     * </ul>
     */
    @Override
    public ObjectInfo getObjectInfo(String repositoryId, String objectId) {
        Map<String, ObjectInfo> oim = getObjectInfoMap();
        ObjectInfo info = oim.get(objectId);
        if (info == null) {
            // object info has not been found -> create one
            try {
                // switch off object info collection to avoid side effects
                addObjectInfos = false;

                // get the object and its info
                ObjectData object = getObject(repositoryId, objectId, null, Boolean.TRUE, IncludeRelationships.BOTH,
                        "*", Boolean.TRUE, Boolean.FALSE, null);
                info = getObjectInfoIntern(repositoryId, object);

                // switch on object info collection
                addObjectInfos = true;

                // add object info
                addObjectInfo(info);
            } catch (Exception e) {
                info = null;

                if (LOG.isTraceEnabled()) {
                    LOG.trace("Getting the object info for object {} in repository {}  failed: {}", objectId,
                            repositoryId, e.toString(), e);
                }
            } finally {
                addObjectInfos = true;
            }
        }
        return info;
    }

    /**
     * Collects the {@link ObjectInfo} about an object.
     * 
     * @param repositoryId
     *            the repository id
     * @param object
     *            the object
     * @return the collected object info
     */
    protected ObjectInfo getObjectInfoIntern(String repositoryId, ObjectData object) {
        // if the object has no properties, stop here
        if (object.getProperties() == null || object.getProperties().getProperties() == null) {
            throw new CmisRuntimeException("No properties!");
        }

        ObjectInfoImpl info = new ObjectInfoImpl();

        // get the repository info
        RepositoryInfo repositoryInfo = null;
        try {
            repositoryInfo = getRepositoryInfo(repositoryId, null);
        } catch (CmisRuntimeException e) {
            LOG.error("getRepositoryInfo returned an error while compiling object info for object {}.", object.getId(),
                    e);
            throw e;
        }

        // general properties
        info.setObject(object);
        info.setId(object.getId());
        info.setName(getStringProperty(object, PropertyIds.NAME));
        info.setCreatedBy(getStringProperty(object, PropertyIds.CREATED_BY));
        info.setCreationDate(getDateTimeProperty(object, PropertyIds.CREATED_BY));
        info.setLastModificationDate(getDateTimeProperty(object, PropertyIds.LAST_MODIFICATION_DATE));
        info.setTypeId(getIdProperty(object, PropertyIds.OBJECT_TYPE_ID));
        info.setBaseType(object.getBaseTypeId());

        // versioning
        info.setIsCurrentVersion(object.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT);
        info.setWorkingCopyId(null);
        info.setWorkingCopyOriginalId(null);

        info.setVersionSeriesId(getIdProperty(object, PropertyIds.VERSION_SERIES_ID));
        if (info.getVersionSeriesId() != null) {
            Boolean isLatest = getBooleanProperty(object, PropertyIds.IS_LATEST_VERSION);
            info.setIsCurrentVersion(isLatest == null ? true : isLatest.booleanValue());

            Boolean isCheckedOut = getBooleanProperty(object, PropertyIds.IS_VERSION_SERIES_CHECKED_OUT);
            if (isCheckedOut != null && isCheckedOut.booleanValue()) {
                info.setWorkingCopyId(getIdProperty(object, PropertyIds.VERSION_SERIES_CHECKED_OUT_ID));

                // get latest version
                try {
                    List<ObjectData> versions = getAllVersions(repositoryId, object.getId(), info.getVersionSeriesId(),
                            null, Boolean.FALSE, null);
                    if (isNotEmpty(versions)) {
                        info.setWorkingCopyOriginalId(versions.get(0).getId());
                    }
                } catch (CmisNotSupportedException nse) {
                    if (LOG.isWarnEnabled()) {
                        LOG.warn("getAllVersions is not implemented! Object info for object {} might be incorrect.",
                                info.getId(), nse);
                    }
                }
            }
        }

        // content
        String fileName = getStringProperty(object, PropertyIds.CONTENT_STREAM_FILE_NAME);
        String mimeType = getStringProperty(object, PropertyIds.CONTENT_STREAM_MIME_TYPE);
        String streamId = getIdProperty(object, PropertyIds.CONTENT_STREAM_ID);
        BigInteger length = getIntegerProperty(object, PropertyIds.CONTENT_STREAM_LENGTH);
        boolean hasContent = fileName != null || mimeType != null || streamId != null || length != null;
        if (hasContent) {
            info.setHasContent(hasContent);
            info.setContentType(mimeType);
            info.setFileName(fileName);
        } else {
            info.setHasContent(false);
            info.setContentType(null);
            info.setFileName(null);
        }

        // parent
        if (object.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP) {
            info.setHasParent(false);
        } else if (object.getBaseTypeId() == BaseTypeId.CMIS_FOLDER) {
            info.setHasParent(!object.getId().equals(repositoryInfo.getRootFolderId()));
        } else {
            try {
                List<ObjectParentData> parents = getObjectParents(repositoryId, object.getId(), null, Boolean.FALSE,
                        IncludeRelationships.NONE, "cmis:none", Boolean.FALSE, null);
                info.setHasParent(isNotEmpty(parents));
            } catch (CmisInvalidArgumentException e) {
                info.setHasParent(false);
            } catch (CmisNotSupportedException nse) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("getObjectParents is not implemented! Object info for object {} might be incorrect.",
                            info.getId(), nse);
                }
            }
        }

        // policies and relationships
        info.setSupportsRelationships(false);
        info.setSupportsPolicies(false);

        try {
            TypeDefinitionList baseTypesList = getTypeChildren(repositoryId, null, Boolean.FALSE,
                    BigInteger.valueOf(6), BigInteger.ZERO, null);
            for (TypeDefinition type : baseTypesList.getList()) {
                if (BaseTypeId.CMIS_RELATIONSHIP.value().equals(type.getId())) {
                    info.setSupportsRelationships(true);
                } else if (BaseTypeId.CMIS_POLICY.value().equals(type.getId())) {
                    info.setSupportsPolicies(true);
                }
            }
        } catch (CmisNotSupportedException nse) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("getTypeChildren is not implemented! Object info for object {} might be incorrect.",
                        info.getId(), nse);
            }
        }

        // renditions
        info.setRenditionInfos(null);
        List<RenditionData> renditions = object.getRenditions();
        if (isNotEmpty(renditions)) {
            List<RenditionInfo> renditionInfos = new ArrayList<RenditionInfo>();
            for (RenditionData rendition : renditions) {
                RenditionInfoImpl renditionInfo = new RenditionInfoImpl();
                renditionInfo.setId(rendition.getStreamId());
                renditionInfo.setKind(rendition.getKind());
                renditionInfo.setContentType(rendition.getMimeType());
                renditionInfo.setTitle(rendition.getTitle());
                renditionInfo.setLength(rendition.getBigLength());
                renditionInfos.add(renditionInfo);
            }
            info.setRenditionInfos(renditionInfos);
        }

        // relationships
        info.setRelationshipSourceIds(null);
        info.setRelationshipTargetIds(null);
        List<ObjectData> relationships = object.getRelationships();
        if (isNotEmpty(relationships)) {
            List<String> sourceIds = new ArrayList<String>();
            List<String> targetIds = new ArrayList<String>();
            for (ObjectData relationship : relationships) {
                String sourceId = getIdProperty(relationship, PropertyIds.SOURCE_ID);
                String targetId = getIdProperty(relationship, PropertyIds.TARGET_ID);
                if (object.getId().equals(sourceId)) {
                    sourceIds.add(relationship.getId());
                }
                if (object.getId().equals(targetId)) {
                    targetIds.add(relationship.getId());
                }
            }
            if (isNotEmpty(sourceIds)) {
                info.setRelationshipSourceIds(sourceIds);
            }
            if (isNotEmpty(targetIds)) {
                info.setRelationshipTargetIds(targetIds);
            }
        }

        // global settings
        info.setHasAcl(false);
        info.setSupportsDescendants(false);
        info.setSupportsFolderTree(false);

        RepositoryCapabilities capabilities = repositoryInfo.getCapabilities();
        if (capabilities != null) {
            info.setHasAcl(capabilities.getAclCapability() == CapabilityAcl.DISCOVER
                    || capabilities.getAclCapability() == CapabilityAcl.MANAGE);
            if (object.getBaseTypeId() == BaseTypeId.CMIS_FOLDER) {
                info.setSupportsDescendants(Boolean.TRUE.equals(capabilities.isGetDescendantsSupported()));
                info.setSupportsFolderTree(Boolean.TRUE.equals(capabilities.isGetFolderTreeSupported()));
            }
        }

        return info;
    }

    /**
     * Adds an object info.
     */
    @Override
    public void addObjectInfo(ObjectInfo objectInfo) {
        if (!addObjectInfos) {
            return;
        }

        if (objectInfo != null && objectInfo.getId() != null) {
            getObjectInfoMap().put(objectInfo.getId(), objectInfo);
        }
    }

    /**
     * Clears the object info map.
     */
    public void clearObjectInfos() {
        objectInfoMap = null;
    }

    @Override
    public void close() {
        clearObjectInfos();
    }

    // --- helpers ---

    protected String getStringProperty(ObjectData object, String name) {
        PropertyData<?> property = object.getProperties().getProperties().get(name);
        if (property instanceof PropertyString) {
            return ((PropertyString) property).getFirstValue();
        }
        return null;
    }

    protected String getIdProperty(ObjectData object, String name) {
        PropertyData<?> property = object.getProperties().getProperties().get(name);
        if (property instanceof PropertyId) {
            return ((PropertyId) property).getFirstValue();
        }
        return null;
    }

    protected GregorianCalendar getDateTimeProperty(ObjectData object, String name) {
        PropertyData<?> property = object.getProperties().getProperties().get(name);
        if (property instanceof PropertyDateTime) {
            return ((PropertyDateTime) property).getFirstValue();
        }
        return null;
    }

    protected Boolean getBooleanProperty(ObjectData object, String name) {
        PropertyData<?> property = object.getProperties().getProperties().get(name);
        if (property instanceof PropertyBoolean) {
            return ((PropertyBoolean) property).getFirstValue();
        }
        return null;
    }

    protected BigInteger getIntegerProperty(ObjectData object, String name) {
        PropertyData<?> property = object.getProperties().getProperties().get(name);
        if (property instanceof PropertyInteger) {
            return ((PropertyInteger) property).getFirstValue();
        }
        return null;
    }
}
