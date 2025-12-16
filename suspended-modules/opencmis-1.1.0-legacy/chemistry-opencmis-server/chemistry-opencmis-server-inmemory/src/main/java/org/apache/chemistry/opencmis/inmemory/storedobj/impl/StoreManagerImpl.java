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
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.MutableTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.CapabilityJoin;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.CapabilityQuery;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.SupportedPermissions;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AclCapabilitiesDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BindingsObjectFactoryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CreatablePropertyTypesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.NewTypeSettableAttributesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionDefinitionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionMappingDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryCapabilitiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryInfoImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.TypeCreator;
import org.apache.chemistry.opencmis.inmemory.TypeManagerImpl;
import org.apache.chemistry.opencmis.inmemory.query.InMemoryQueryProcessor;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.CmisServiceValidator;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.server.support.TypeDefinitionFactory;
import org.apache.chemistry.opencmis.server.support.TypeManager;

/**
 * Factory to create objects that are stored in the InMemory store.
 * 
 */
public class StoreManagerImpl implements StoreManager {

    private static final String UNKNOWN_REPOSITORY = "Unknown repository ";
    private static final String CMIS_READ = "cmis:read";
    private static final String CMIS_WRITE = "cmis:write";
    private static final String CMIS_ALL = "cmis:all";

    private final BindingsObjectFactory fObjectFactory;
    private final TypeDefinitionFactory typeFactory = TypeDefinitionFactory.newInstance();
    private static final String OPENCMIS_VERSION;
    private static final String OPENCMIS_SERVER;

    static {
        Package p = Package.getPackage("org.apache.chemistry.opencmis.inmemory");
        if (p == null) {
            OPENCMIS_VERSION = "?";
            OPENCMIS_SERVER = "Apache-Chemistry-OpenCMIS-InMemory";
        } else {
            String ver = p.getImplementationVersion();
            OPENCMIS_VERSION = (null == ver ? "?" : ver);
            OPENCMIS_SERVER = "Apache-Chemistry-OpenCMIS-InMemory/" + OPENCMIS_VERSION;
        }
    }

    /**
     * Map from repository id to a type manager.
     */
    private final Map<String, TypeManagerImpl> fMapRepositoryToTypeManager = new HashMap<String, TypeManagerImpl>();

    /**
     * Map from repository id to a object store.
     */
    private final Map<String, ObjectStore> fMapRepositoryToObjectStore = new HashMap<String, ObjectStore>();
    
    private boolean relaxedParserMode = false;
    

    public ObjectStoreImpl getStore(String repositoryId) {
        return (ObjectStoreImpl) fMapRepositoryToObjectStore.get(repositoryId);
    }

    public StoreManagerImpl() {
        fObjectFactory = new BindingsObjectFactoryImpl();
    }

    @Override
    public List<String> getAllRepositoryIds() {
        Set<String> repIds = fMapRepositoryToObjectStore.keySet();
        List<String> result = new ArrayList<String>();
        result.addAll(repIds);
        return result;
    }

    @Override
    public void initRepository(String repositoryId) {
        fMapRepositoryToObjectStore.put(repositoryId, new ObjectStoreImpl(repositoryId));
        fMapRepositoryToTypeManager.put(repositoryId, new TypeManagerImpl());
    }

    @Override
    public void createAndInitRepository(String repositoryId, String typeCreatorClassName) {
        if (fMapRepositoryToObjectStore.containsKey(repositoryId)
                || fMapRepositoryToTypeManager.containsKey(repositoryId)) {
            throw new CmisInvalidArgumentException("Cannot add repository, repository " + repositoryId
                    + " already exists.");
        }

        fMapRepositoryToObjectStore.put(repositoryId, new ObjectStoreImpl(repositoryId));
        fMapRepositoryToTypeManager.put(repositoryId, new TypeManagerImpl());

        // initialize the type system:
        initTypeSystem(repositoryId, typeCreatorClassName);
    }

    @Override
    public void addFlag(String flag) {
    	if (flag.trim().equalsIgnoreCase("ParserModeRelaxed")) {
    		relaxedParserMode = true;
    	}
    }
    
    @Override
    public ObjectStore getObjectStore(String repositoryId) {
        return fMapRepositoryToObjectStore.get(repositoryId);
    }

    @Override
    public CmisServiceValidator getServiceValidator() {
        return new InMemoryServiceValidatorImpl(this);
    }

    @Override
    public BindingsObjectFactory getObjectFactory() {
        return fObjectFactory;
    }

    @Override
    public TypeDefinitionContainer getTypeById(String repositoryId, String typeId, boolean cmis11) {
        TypeManager typeManager = fMapRepositoryToTypeManager.get(repositoryId);
        if (null == typeManager) {
            throw new CmisObjectNotFoundException(UNKNOWN_REPOSITORY + repositoryId);
        }

        TypeDefinitionContainer tdc = typeManager.getTypeById(typeId);
        if (null != tdc && !cmis11) {
            TypeDefinition td = tdc.getTypeDefinition();
            if (td.getBaseTypeId() == BaseTypeId.CMIS_ITEM || td.getBaseTypeId() == BaseTypeId.CMIS_SECONDARY
                    || td.getId().equals(BaseTypeId.CMIS_ITEM.value())
                    || td.getId().equals(BaseTypeId.CMIS_SECONDARY.value())) {
                tdc = null; // filter new types for CMIS 1.0
            } else {
            	// remove type mutability information:
                MutableTypeDefinition tdm = typeFactory.copy(td, true);
                tdm.setTypeMutability(null);
                tdc = new TypeDefinitionContainerImpl(tdm);
            }
        }
        return tdc;
    }

    @Override
    public TypeDefinitionContainer getTypeById(String repositoryId, String typeId, boolean includePropertyDefinitions,
            int depthParam, boolean cmis11) {
        int depth = depthParam;
        TypeManager typeManager = fMapRepositoryToTypeManager.get(repositoryId);
        if (null == typeManager) {
            throw new CmisInvalidArgumentException(UNKNOWN_REPOSITORY + repositoryId);
        }

        TypeDefinitionContainer tc = typeManager.getTypeById(typeId);

        if (tc != null) {
            if (depth == -1) {
                if (cmis11 && includePropertyDefinitions) {
                    return tc;
                } else {
                    depth = Integer.MAX_VALUE;
                }
            } else if (depth == 0 || depth < -1) {
                throw new CmisInvalidArgumentException("illegal depth value: " + depth);
            }

            return cloneTypeList(depth, includePropertyDefinitions, tc, null, cmis11);
        } else {
            return null;
        }
    }

    @Override
    public Collection<TypeDefinitionContainer> getTypeDefinitionList(String repositoryId,
            boolean includePropertyDefinitions, boolean cmis11) {
        TypeManager typeManager = fMapRepositoryToTypeManager.get(repositoryId);
        if (null == typeManager) {
            throw new CmisInvalidArgumentException(UNKNOWN_REPOSITORY + repositoryId);
        }
        Collection<TypeDefinitionContainer> typeColl = getRootTypes(repositoryId, includePropertyDefinitions, cmis11);
        return typeColl;
    }

    @Override
    public List<TypeDefinitionContainer> getRootTypes(String repositoryId, boolean includePropertyDefinitions, boolean cmis11) {
        List<TypeDefinitionContainer> result;
        TypeManager typeManager = fMapRepositoryToTypeManager.get(repositoryId);
        if (null == typeManager) {
            throw new CmisInvalidArgumentException(UNKNOWN_REPOSITORY + repositoryId);
        }
        List<TypeDefinitionContainer> rootTypes = typeManager.getRootTypes();

        // remove cmis:item and cmis:secondary for CMIS 1.0
        if (!cmis11) {
            rootTypes = new ArrayList<TypeDefinitionContainer>(rootTypes);
            TypeDefinitionContainer tcItem = null, tcSecondary = null;
            for (TypeDefinitionContainer tc : rootTypes) {
                if (tc.getTypeDefinition().getId().equals(BaseTypeId.CMIS_ITEM.value())) {
                    tcItem = tc;
                }
                if (tc.getTypeDefinition().getId().equals(BaseTypeId.CMIS_SECONDARY.value())) {
                    tcSecondary = tc;
                }
            }
            if (tcItem != null) {
                rootTypes.remove(tcItem);
            }
            if (tcSecondary != null) {
                rootTypes.remove(tcSecondary);
            }
        }

        if (cmis11 && includePropertyDefinitions) {
            result = rootTypes;
        } else {
            result = cloneTypeDefinitionTree(rootTypes, includePropertyDefinitions, cmis11);
        }
        return result;
    }
    
    private List<TypeDefinitionContainer> cloneTypeDefinitionTree (List<TypeDefinitionContainer> tdcList, boolean includePropertyDefinitions, boolean cmis11) {
    	List<TypeDefinitionContainer> result = new ArrayList<TypeDefinitionContainer>(tdcList.size());
		for (TypeDefinitionContainer c : tdcList) {
			MutableTypeDefinition td = typeFactory.copy(c.getTypeDefinition(), includePropertyDefinitions);
			if (!cmis11) {
				td.setTypeMutability(null);
			}
			TypeDefinitionContainerImpl tdc = new TypeDefinitionContainerImpl(td);
			tdc.setChildren(cloneTypeDefinitionTree(c.getChildren(), includePropertyDefinitions, cmis11));
			result.add(tdc);
		}
		return result;
	}
    
    @Override
    public RepositoryInfo getRepositoryInfo(CallContext context, String repositoryId) {
        ObjectStore sm = fMapRepositoryToObjectStore.get(repositoryId);
        if (null == sm) {
            return null;
        }
        boolean cmis11 = context.getCmisVersion().equals(CmisVersion.CMIS_1_1);
        RepositoryInfo repoInfo = createRepositoryInfo(repositoryId, cmis11);
        return repoInfo;
    }

    public void clearTypeSystem(String repositoryId) {
        TypeManagerImpl typeManager = fMapRepositoryToTypeManager.get(repositoryId);
        if (null == typeManager) {
            throw new CmisInvalidArgumentException(UNKNOWN_REPOSITORY + repositoryId);
        }

        typeManager.clearTypeSystem();
    }

    public static List<TypeDefinition> initTypeSystem(String typeCreatorClassName) {

        List<TypeDefinition> typesList = null;

        if (typeCreatorClassName != null) {
            Object obj = null;
            TypeCreator typeCreator = null;

            final String message = "Illegal class to create type system, must implement TypeCreator interface.";
            try {
                obj = Class.forName(typeCreatorClassName).newInstance();
            } catch (InstantiationException e) {
                throw new CmisRuntimeException(message, e);
            } catch (IllegalAccessException e) {
                throw new CmisRuntimeException(message, e);
            } catch (ClassNotFoundException e) {
                throw new CmisRuntimeException(message, e);
            }

            if (obj instanceof TypeCreator) {
                typeCreator = (TypeCreator) obj;
            } else {
                throw new CmisRuntimeException(message);
            }

            // retrieve the list of available types from the configured class.
            // test
            typesList = typeCreator.createTypesList();
        }

        return typesList;
    }

    private void initTypeSystem(String repositoryId, String typeCreatorClassName) {

        List<TypeDefinition> typeDefs = null;
        TypeManagerImpl typeManager = fMapRepositoryToTypeManager.get(repositoryId);
        if (null == typeManager) {
            throw new CmisObjectNotFoundException(UNKNOWN_REPOSITORY + repositoryId);
        }

        if (null != typeCreatorClassName) {
            typeDefs = initTypeSystem(typeCreatorClassName);
        }

        typeManager.initTypeSystem(typeDefs, true);
    }

    @SuppressWarnings("serial")
    private RepositoryInfo createRepositoryInfo(String repositoryId, boolean cmis11) {
        ObjectStore objStore = getObjectStore(repositoryId);
        String rootFolderId = objStore.getRootFolder().getId();
        // repository info
        RepositoryInfoImpl repoInfo;
        repoInfo = new RepositoryInfoImpl();
        repoInfo.setId(repositoryId == null ? "inMem" : repositoryId);
        repoInfo.setName("Apache Chemistry OpenCMIS InMemory Repository");
        repoInfo.setDescription("Apache Chemistry OpenCMIS InMemory Repository (Version: " + OPENCMIS_VERSION + ")");
        repoInfo.setRootFolder(rootFolderId);
        repoInfo.setPrincipalAnonymous(InMemoryAce.getAnonymousUser());
        repoInfo.setPrincipalAnyone(InMemoryAce.getAnyoneUser());
        repoInfo.setThinClientUri("");
        repoInfo.setChangesIncomplete(Boolean.TRUE);
        repoInfo.setLatestChangeLogToken("token-24");
        repoInfo.setVendorName("Apache Chemistry");
        repoInfo.setProductName(OPENCMIS_SERVER);
        repoInfo.setProductVersion(OPENCMIS_VERSION);

        // set capabilities
        RepositoryCapabilitiesImpl caps = new RepositoryCapabilitiesImpl();
        caps.setAllVersionsSearchable(false);
        caps.setCapabilityAcl(CapabilityAcl.MANAGE);
        caps.setCapabilityChanges(CapabilityChanges.OBJECTIDSONLY);
        caps.setCapabilityContentStreamUpdates(CapabilityContentStreamUpdates.ANYTIME);
        caps.setCapabilityJoin(CapabilityJoin.NONE);
        caps.setCapabilityQuery(CapabilityQuery.BOTHCOMBINED);
        caps.setCapabilityRendition(CapabilityRenditions.READ);
        caps.setIsPwcSearchable(false);
        caps.setIsPwcUpdatable(true);
        caps.setSupportsGetDescendants(true);
        caps.setSupportsGetFolderTree(true);
        caps.setSupportsMultifiling(true);
        caps.setSupportsUnfiling(true);
        caps.setSupportsVersionSpecificFiling(false);
        caps.setCapabilityAcl(CapabilityAcl.MANAGE);

        AclCapabilitiesDataImpl aclCaps = new AclCapabilitiesDataImpl();
        aclCaps.setAclPropagation(AclPropagation.OBJECTONLY);
        aclCaps.setSupportedPermissions(SupportedPermissions.BASIC);

        // permissions
        List<PermissionDefinition> permissions = new ArrayList<PermissionDefinition>();
        permissions.add(createPermission(CMIS_READ, "Read"));
        permissions.add(createPermission(CMIS_WRITE, "Write"));
        permissions.add(createPermission(CMIS_ALL, "All"));
        if (cmis11) {
            NewTypeSettableAttributesImpl typeAttrs = new NewTypeSettableAttributesImpl();
            typeAttrs.setCanSetControllableAcl(false);
            typeAttrs.setCanSetControllablePolicy(false);
            typeAttrs.setCanSetCreatable(true);
            typeAttrs.setCanSetDescription(true);
            typeAttrs.setCanSetDisplayName(true);
            typeAttrs.setCanSetFileable(false);
            typeAttrs.setCanSetFulltextIndexed(false);
            typeAttrs.setCanSetId(true);
            typeAttrs.setCanSetIncludedInSupertypeQuery(false);
            typeAttrs.setCanSetLocalName(true);
            typeAttrs.setCanSetLocalNamespace(true);
            typeAttrs.setCanSetQueryable(false);
            typeAttrs.setCanSetQueryName(true);
            caps.setNewTypeSettableAttributes(typeAttrs);
        }
        aclCaps.setPermissionDefinitionData(permissions);

        // mapping
        List<PermissionMapping> list = new ArrayList<PermissionMapping>();
        list.add(createMapping(PermissionMapping.CAN_GET_DESCENDENTS_FOLDER, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_GET_CHILDREN_FOLDER, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_GET_PARENTS_FOLDER, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_CREATE_FOLDER_FOLDER, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_CREATE_RELATIONSHIP_SOURCE, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_CREATE_RELATIONSHIP_TARGET, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_GET_PROPERTIES_OBJECT, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_VIEW_CONTENT_OBJECT, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_MOVE_OBJECT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_MOVE_TARGET, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_MOVE_SOURCE, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_DELETE_OBJECT, CMIS_WRITE));

        list.add(createMapping(PermissionMapping.CAN_DELETE_TREE_FOLDER, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_SET_CONTENT_DOCUMENT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_DELETE_CONTENT_DOCUMENT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_ADD_TO_FOLDER_OBJECT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_REMOVE_FROM_FOLDER_OBJECT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_CHECKOUT_DOCUMENT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT, CMIS_WRITE));

        list.add(createMapping(PermissionMapping.CAN_CHECKIN_DOCUMENT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_GET_ALL_VERSIONS_VERSION_SERIES, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_ADD_POLICY_OBJECT, CMIS_WRITE));
        list.add(createMapping(PermissionMapping.CAN_REMOVE_POLICY_OBJECT, CMIS_WRITE));

        list.add(createMapping(PermissionMapping.CAN_GET_APPLIED_POLICIES_OBJECT, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_GET_ACL_OBJECT, CMIS_READ));
        list.add(createMapping(PermissionMapping.CAN_APPLY_ACL_OBJECT, CMIS_ALL));

        Map<String, PermissionMapping> map = new LinkedHashMap<String, PermissionMapping>();
        for (PermissionMapping pm : list) {
            map.put(pm.getKey(), pm);
        }

        List<BaseTypeId> changesOnType;
        // CMIS 1.1 extensions
        if (cmis11) {
            repoInfo.setCmisVersionSupported(CmisVersion.CMIS_1_1.value());
            repoInfo.setCmisVersion(CmisVersion.CMIS_1_1);
            changesOnType = new ArrayList<BaseTypeId>() {
                {
                    add(BaseTypeId.CMIS_DOCUMENT);
                    add(BaseTypeId.CMIS_FOLDER);
                    add(BaseTypeId.CMIS_ITEM);
                }
            };

            Set<PropertyType> propertyTypeSet = new HashSet<PropertyType>() {
                {
                    add(PropertyType.BOOLEAN);
                    add(PropertyType.DATETIME);
                    add(PropertyType.DECIMAL);
                    add(PropertyType.HTML);
                    add(PropertyType.ID);
                    add(PropertyType.INTEGER);
                    add(PropertyType.STRING);
                    add(PropertyType.URI);
                }
            };
            CreatablePropertyTypesImpl creatablePropertyTypes = new CreatablePropertyTypesImpl();
            creatablePropertyTypes.setCanCreate(propertyTypeSet);
            caps.setCreatablePropertyTypes(creatablePropertyTypes);
            caps.setCapabilityOrderBy(CapabilityOrderBy.COMMON);
        } else {
            repoInfo.setCmisVersionSupported(CmisVersion.CMIS_1_0.value());
            repoInfo.setCmisVersion(CmisVersion.CMIS_1_0);
            changesOnType = new ArrayList<BaseTypeId>() {
                {
                    add(BaseTypeId.CMIS_DOCUMENT);
                    add(BaseTypeId.CMIS_FOLDER);
                }
            };
        }
        repoInfo.setChangesOnType(changesOnType);

        aclCaps.setPermissionMappingData(map);

        repoInfo.setAclCapabilities(aclCaps);

        repoInfo.setCapabilities(caps);

        return repoInfo;
    }

    private static PermissionDefinition createPermission(String permission, String description) {
        PermissionDefinitionDataImpl pd = new PermissionDefinitionDataImpl();
        pd.setId(permission);
        pd.setDescription(description);

        return pd;
    }

    private static PermissionMapping createMapping(String key, String permission) {
        PermissionMappingDataImpl pm = new PermissionMappingDataImpl();
        pm.setKey(key);
        pm.setPermissions(Collections.singletonList(permission));

        return pm;
    }

    /**
     * traverse tree and replace each need node with a clone. remove properties
     * on clone if requested, cut children of clone if depth is exceeded.
     * 
     * @param depth
     *            levels of children to copy
     * @param includePropertyDefinitions
     *            indicates with or without property definitions
     * @param tdc
     *            type definition to clone
     * @param parent
     *            parent container where to add clone as child
     * @return cloned type definition
     */
    public static TypeDefinitionContainer cloneTypeList(int depth, boolean includePropertyDefinitions,
            TypeDefinitionContainer tdc, TypeDefinitionContainer parent, boolean cmis11) {

        final TypeDefinitionFactory typeFactory = TypeDefinitionFactory.newInstance();
        MutableTypeDefinition tdClone = typeFactory.copy(tdc.getTypeDefinition(), includePropertyDefinitions);
        if (!cmis11) {
        	tdClone.setTypeMutability(null);
        }
        TypeDefinitionContainerImpl tdcClone = new TypeDefinitionContainerImpl(tdClone);
        if (null != parent) {
            parent.getChildren().add(tdcClone);
        }

        if (depth > 0) {
            List<TypeDefinitionContainer> children = tdc.getChildren();
            for (TypeDefinitionContainer child : children) {
                cloneTypeList(depth - 1, includePropertyDefinitions, child, tdcClone, cmis11);
            }
        }
        return tdcClone;
    }

    @Override
    public TypeManager getTypeManager(String repositoryId) {
        TypeManager typeManager = fMapRepositoryToTypeManager.get(repositoryId);
        return typeManager;
    }

    @Override
    public boolean supportsSingleFiling(String repositoryId) {
        return false;
    }

    @Override
    public boolean supportsMultiFilings(String repositoryId) {
        return true;
    }

    @Override
    public ObjectList query(CallContext callContext, String user, String repositoryId, String statement, Boolean searchAllVersions,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount) {
        TypeManager tm = getTypeManager(repositoryId);
        ObjectStore objectStore = getObjectStore(repositoryId);

        InMemoryQueryProcessor queryProcessor = new InMemoryQueryProcessor(getStore(repositoryId), callContext, relaxedParserMode);
        ObjectList objList = queryProcessor.query(tm, objectStore, user, repositoryId, statement, searchAllVersions,
                includeAllowableActions, includeRelationships, renditionFilter, maxItems, skipCount);

        return objList;
    }
}
