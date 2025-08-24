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
package org.apache.chemistry.opencmis.client.runtime;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNullOrEmpty;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.ChangeEvents;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.DocumentType;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectFactory;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.QueryStatement;
import org.apache.chemistry.opencmis.client.api.Relationship;
import org.apache.chemistry.opencmis.client.api.SecondaryType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.client.bindings.cache.TypeDefinitionCache;
import org.apache.chemistry.opencmis.client.runtime.cache.Cache;
import org.apache.chemistry.opencmis.client.runtime.cache.CacheImpl;
import org.apache.chemistry.opencmis.client.runtime.repository.ObjectFactoryImpl;
import org.apache.chemistry.opencmis.client.runtime.util.AbstractPageFetcher;
import org.apache.chemistry.opencmis.client.runtime.util.CollectionIterable;
import org.apache.chemistry.opencmis.client.runtime.util.TreeImpl;
import org.apache.chemistry.opencmis.client.util.OperationContextUtils;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.SessionParameterDefaults;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.FailedToDeleteData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateObjectIdAndChangeTokenImpl;
import org.apache.chemistry.opencmis.commons.spi.AclService;
import org.apache.chemistry.opencmis.commons.spi.AuthenticationProvider;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;
import org.apache.chemistry.opencmis.commons.spi.DiscoveryService;
import org.apache.chemistry.opencmis.commons.spi.ExtendedAclService;
import org.apache.chemistry.opencmis.commons.spi.ExtendedHolder;
import org.apache.chemistry.opencmis.commons.spi.ExtendedRepositoryService;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;
import org.apache.chemistry.opencmis.commons.spi.RelationshipService;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;

/**
 * Persistent model session.
 */
public class SessionImpl implements Session {

    private static final OperationContext DEFAULT_CONTEXT = new OperationContextImpl(null, false, true, false,
            IncludeRelationships.NONE, null, true, null, true, 100);

    private static final Set<Updatability> CREATE_UPDATABILITY = EnumSet.noneOf(Updatability.class);
    private static final Set<Updatability> CREATE_AND_CHECKOUT_UPDATABILITY = EnumSet.noneOf(Updatability.class);

    static {
        CREATE_UPDATABILITY.add(Updatability.ONCREATE);
        CREATE_UPDATABILITY.add(Updatability.READWRITE);
        CREATE_AND_CHECKOUT_UPDATABILITY.add(Updatability.ONCREATE);
        CREATE_AND_CHECKOUT_UPDATABILITY.add(Updatability.READWRITE);
        CREATE_AND_CHECKOUT_UPDATABILITY.add(Updatability.WHENCHECKEDOUT);
    }

    // private static Logger log = LoggerFactory.getLogger(SessionImpl.class);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private transient LinkedHashMap<String, ObjectType> objectTypeCache;

    /*
     * default session context (serializable)
     */
    private OperationContext defaultContext = DEFAULT_CONTEXT;

    /*
     * session parameter (serializable)
     */
    private Map<String, String> parameters;

    /*
     * CMIS binding (serializable)
     */
    private CmisBinding binding;

    /*
     * Session Locale, determined from session parameter (serializable)
     */
    private Locale locale;

    /*
     * Object factory (serializable)
     */
    private final ObjectFactory objectFactory;

    /*
     * Authentication provider (serializable)
     */
    private final AuthenticationProvider authenticationProvider;

    /*
     * Object cache (serializable)
     */
    private Cache cache;
    private final boolean cachePathOmit;

    /*
     * Type cache.
     */
    private TypeDefinitionCache typeDefCache;

    /*
     * Repository info (serializable)
     */
    private RepositoryInfo repositoryInfo;

    /**
     * required for serialization
     */
    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     */
    public SessionImpl(Map<String, String> parameters, ObjectFactory objectFactory,
            AuthenticationProvider authenticationProvider, Cache cache, TypeDefinitionCache typeDefCache) {
        if (parameters == null) {
            throw new IllegalArgumentException("No parameters provided!");
        }

        this.parameters = parameters;
        this.locale = determineLocale(parameters);

        this.objectFactory = objectFactory == null ? createObjectFactory() : objectFactory;
        this.authenticationProvider = authenticationProvider;
        this.cache = cache == null ? createCache() : cache;
        this.typeDefCache = typeDefCache;

        cachePathOmit = Boolean.parseBoolean(parameters.get(SessionParameter.CACHE_PATH_OMIT));
    }

    private Locale determineLocale(Map<String, String> parameters) {
        Locale result;

        String language = parameters.get(SessionParameter.LOCALE_ISO639_LANGUAGE);
        String country = parameters.get(SessionParameter.LOCALE_ISO3166_COUNTRY);
        String variant = parameters.get(SessionParameter.LOCALE_VARIANT);

        if (variant != null) {
            // all 3 parameter must not be null and valid
            result = new Locale(language, country, variant);
        } else if (country != null) {
            // 2 parameter must not be null and valid
            result = new Locale(language, country);
        } else if (language != null) {
            // 1 parameter must not be null and valid
            result = new Locale(language);
        } else {
            result = Locale.getDefault();
        }

        return result;
    }

    private ObjectFactory createObjectFactory() {
        try {
            String classname = parameters.get(SessionParameter.OBJECT_FACTORY_CLASS);

            Class<?> objectFactoryClass;
            if (classname == null) {
                objectFactoryClass = ObjectFactoryImpl.class;
            } else {
                objectFactoryClass = ClassLoaderUtil.loadClass(classname);
            }

            Object of = objectFactoryClass.newInstance();
            if (!(of instanceof ObjectFactory)) {
                throw new InstantiationException("Class does not implement ObjectFactory!");
            }

            ((ObjectFactory) of).initialize(this, parameters);

            return (ObjectFactory) of;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to create object factory: " + e, e);
        }
    }

    private Cache createCache() {
        try {
            String classname = parameters.get(SessionParameter.CACHE_CLASS);

            Class<?> cacheClass;
            if (classname == null) {
                cacheClass = CacheImpl.class;
            } else {
                cacheClass = ClassLoaderUtil.loadClass(classname);
            }

            Object of = cacheClass.newInstance();
            if (!(of instanceof Cache)) {
                throw new InstantiationException("Class does not implement Cache!");
            }

            ((Cache) of).initialize(this, parameters);

            return (Cache) of;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to create cache: " + e, e);
        }
    }

    @Override
    public Map<String, String> getSessionParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            // create new object cache
            cache = createCache();

            // clear object type cache
            objectTypeCache = null;

            // clear provider cache
            getBinding().clearAllCaches();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public ObjectFactory getObjectFactory() {
        assert objectFactory != null;
        return objectFactory;
    }

    @Override
    public ItemIterable<Document> getCheckedOutDocs() {
        return getCheckedOutDocs(getDefaultContext());
    }

    @Override
    public ItemIterable<Document> getCheckedOutDocs(OperationContext context) {
        checkContext(context);

        final NavigationService navigationService = getBinding().getNavigationService();
        final ObjectFactory of = getObjectFactory();
        final OperationContext ctxt = new OperationContextImpl(context);

        return new CollectionIterable<Document>(new AbstractPageFetcher<Document>(ctxt.getMaxItemsPerPage()) {

            @Override
            protected AbstractPageFetcher.Page<Document> fetchPage(long skipCount) {

                // get all checked out documents
                ObjectList checkedOutDocs = navigationService.getCheckedOutDocs(getRepositoryId(), null,
                        ctxt.getFilterString(), ctxt.getOrderBy(), ctxt.isIncludeAllowableActions(),
                        ctxt.getIncludeRelationships(), ctxt.getRenditionFilterString(),
                        BigInteger.valueOf(this.maxNumItems), BigInteger.valueOf(skipCount), null);

                // convert objects
                List<Document> page = new ArrayList<Document>();
                if (checkedOutDocs.getObjects() != null) {
                    for (ObjectData objectData : checkedOutDocs.getObjects()) {
                        CmisObject doc = of.convertObject(objectData, ctxt);
                        if (!(doc instanceof Document)) {
                            // should not happen...
                            continue;
                        }

                        page.add((Document) doc);
                    }
                }

                return new AbstractPageFetcher.Page<Document>(page, checkedOutDocs.getNumItems(),
                        checkedOutDocs.hasMoreItems());
            }
        });
    }

    @Override
    public ChangeEvents getContentChanges(String changeLogToken, boolean includeProperties, long maxNumItems) {
        return getContentChanges(changeLogToken, includeProperties, maxNumItems, getDefaultContext());
    }

    @Override
    public ChangeEvents getContentChanges(String changeLogToken, boolean includeProperties, long maxNumItems,
            OperationContext context) {
        checkContext(context);

        Holder<String> changeLogTokenHolder = new Holder<String>(changeLogToken);
        ObjectList objectList = null;

        lock.readLock().lock();
        try {
            objectList = getBinding().getDiscoveryService().getContentChanges(getRepositoryInfo().getId(),
                    changeLogTokenHolder, includeProperties, context.getFilterString(), context.isIncludePolicies(),
                    context.isIncludeAcls(), BigInteger.valueOf(maxNumItems), null);
        } finally {
            lock.readLock().unlock();
        }

        return objectFactory.convertChangeEvents(changeLogTokenHolder.getValue(), objectList);
    }

    @Override
    public ItemIterable<ChangeEvent> getContentChanges(String changeLogToken, final boolean includeProperties) {
        return getContentChanges(changeLogToken, includeProperties, getDefaultContext());
    }

    @Override
    public ItemIterable<ChangeEvent> getContentChanges(final String changeLogToken, final boolean includeProperties,
            OperationContext context) {
        checkContext(context);

        final DiscoveryService discoveryService = getBinding().getDiscoveryService();
        final ObjectFactory of = getObjectFactory();
        final OperationContext ctxt = new OperationContextImpl(context);

        return new CollectionIterable<ChangeEvent>(new AbstractPageFetcher<ChangeEvent>(Integer.MAX_VALUE) {

            private String token = changeLogToken;
            private String nextLink = null;
            private boolean firstPage = true;

            @Override
            protected AbstractPageFetcher.Page<ChangeEvent> fetchPage(long skipCount) {
                assert firstPage || token != null ? (nextLink == null) : true;

                // fetch the data
                ExtendedHolder<String> changeLogTokenHolder = new ExtendedHolder<String>(token);
                if (nextLink != null) {
                    changeLogTokenHolder.setExtraValue(Constants.REP_REL_CHANGES, nextLink);
                }

                ObjectList objectList = discoveryService.getContentChanges(getRepositoryInfo().getId(),
                        changeLogTokenHolder, includeProperties, ctxt.getFilterString(), ctxt.isIncludePolicies(),
                        ctxt.isIncludeAcls(), BigInteger.valueOf(this.maxNumItems), null);

                // convert type definitions
                List<ChangeEvent> page = new ArrayList<ChangeEvent>();
                for (ObjectData objectData : objectList.getObjects()) {
                    page.add(of.convertChangeEvent(objectData));
                }

                if (!firstPage) {
                    // the last entry of the previous page is repeated
                    // -> remove the first entry
                    page.remove(0);
                }
                firstPage = false;

                if (changeLogTokenHolder.getValue() != null) {
                    // the web services and the browser binding
                    // return a new token
                    token = changeLogTokenHolder.getValue();
                } else {
                    // the atompub binding does not return a new token,
                    // but might return a link to the next Atom feed
                    token = null;
                    nextLink = (String) changeLogTokenHolder.getExtraValue(Constants.REP_REL_CHANGES);
                }

                return new AbstractPageFetcher.Page<ChangeEvent>(page, objectList.getNumItems(),
                        objectList.hasMoreItems()) {
                };
            }
        }) {

            @Override
            public ItemIterable<ChangeEvent> skipTo(long position) {
                throw new CmisNotSupportedException("Skipping not supported!");
            }

            @Override
            public ItemIterable<ChangeEvent> getPage() {
                throw new CmisNotSupportedException("Paging not supported!");
            }

            @Override
            public ItemIterable<ChangeEvent> getPage(int maxNumItems) {
                throw new CmisNotSupportedException("Paging not supported!");
            }

        };
    }

    @Override
    public String getLatestChangeLogToken() {
        return getBinding().getRepositoryService().getRepositoryInfo(getRepositoryId(), null).getLatestChangeLogToken();
    }

    @Override
    public OperationContext getDefaultContext() {
        lock.readLock().lock();
        try {
            return defaultContext;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void setDefaultContext(OperationContext context) {
        lock.writeLock().lock();
        try {
            this.defaultContext = context == null ? DEFAULT_CONTEXT : context;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public OperationContext createOperationContext(Set<String> filter, boolean includeAcls,
            boolean includeAllowableActions, boolean includePolicies, IncludeRelationships includeRelationships,
            Set<String> renditionFilter, boolean includePathSegments, String orderBy, boolean cacheEnabled,
            int maxItemsPerPage) {
        return OperationContextUtils.createOperationContext(filter, includeAcls, includeAllowableActions,
                includePolicies, includeRelationships, renditionFilter, includePathSegments, orderBy, cacheEnabled,
                maxItemsPerPage);
    }

    @Override
    public OperationContext createOperationContext() {
        return OperationContextUtils.createOperationContext();
    }

    @Override
    public ObjectId createObjectId(String id) {
        return new ObjectIdImpl(id);
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @Override
    public CmisObject getObject(ObjectId objectId) {
        return getObject(objectId, getDefaultContext());
    }

    @Override
    public CmisObject getObject(ObjectId objectId, OperationContext context) {
        checkObjectId(objectId);
        return getObject(objectId.getId(), context);
    }

    @Override
    public CmisObject getObject(String objectId) {
        return getObject(objectId, getDefaultContext());
    }

    @Override
    public CmisObject getObject(String objectId, OperationContext context) {
        checkObjectId(objectId);
        checkContext(context);

        CmisObject result = null;

        // ask the cache first
        if (context.isCacheEnabled()) {
            result = cache.getById(objectId, context.getCacheKey());
            if (result != null) {
                return result;
            }
        }

        // get the object
        ObjectData objectData = binding.getObjectService().getObject(getRepositoryId(), objectId,
                context.getFilterString(), context.isIncludeAllowableActions(), context.getIncludeRelationships(),
                context.getRenditionFilterString(), context.isIncludePolicies(), context.isIncludeAcls(), null);

        result = getObjectFactory().convertObject(objectData, context);

        // put into cache
        if (context.isCacheEnabled()) {
            cache.put(result, context.getCacheKey());
        }

        return result;
    }

    @Override
    public CmisObject getObjectByPath(String path) {
        return getObjectByPath(path, getDefaultContext());
    }

    @Override
    public CmisObject getObjectByPath(String path, OperationContext context) {
        checkPath(path);
        checkContext(context);

        CmisObject result = null;

        // ask the cache first
        if (context.isCacheEnabled() && !cachePathOmit) {
            result = cache.getByPath(path, context.getCacheKey());
            if (result != null) {
                return result;
            }
        }

        // get the object
        ObjectData objectData = binding.getObjectService().getObjectByPath(getRepositoryId(), path,
                context.getFilterString(), context.isIncludeAllowableActions(), context.getIncludeRelationships(),
                context.getRenditionFilterString(), context.isIncludePolicies(), context.isIncludeAcls(), null);

        result = getObjectFactory().convertObject(objectData, context);

        // put into cache
        if (context.isCacheEnabled()) {
            cache.putPath(path, result, context.getCacheKey());
        }

        return result;
    }

    @Override
    public CmisObject getObjectByPath(String parentPath, String name) {
        return getObjectByPath(parentPath, name, getDefaultContext());
    }

    @Override
    public CmisObject getObjectByPath(String parentPath, String name, OperationContext context) {
        return getObjectByPath(buildPath(parentPath, name), context);
    }

    @Override
    public Document getLatestDocumentVersion(ObjectId objectId) {
        return getLatestDocumentVersion(objectId, false, getDefaultContext());
    }

    @Override
    public Document getLatestDocumentVersion(String objectId, OperationContext context) {
        checkDocumentId(objectId);
        return getLatestDocumentVersion(createObjectId(objectId), false, context);
    }

    @Override
    public Document getLatestDocumentVersion(String objectId, boolean major, OperationContext context) {
        checkDocumentId(objectId);
        return getLatestDocumentVersion(createObjectId(objectId), major, context);
    }

    @Override
    public Document getLatestDocumentVersion(String objectId) {
        checkDocumentId(objectId);
        return getLatestDocumentVersion(createObjectId(objectId), false, getDefaultContext());
    }

    @Override
    public Document getLatestDocumentVersion(ObjectId objectId, OperationContext context) {
        return getLatestDocumentVersion(objectId, false, context);
    }

    @Override
    public Document getLatestDocumentVersion(ObjectId objectId, boolean major, OperationContext context) {
        checkDocumentId(objectId);
        checkContext(context);

        CmisObject result = null;

        String versionSeriesId = null;

        // first attempt: if we got a Document object, try getting the version
        // series ID from it
        if (objectId instanceof Document) {
            Document sourceDoc = (Document) objectId;

            if (!sourceDoc.isVersionable()) {
                // if it is not versionable, a getObject() is sufficient
                return (Document) getObject(sourceDoc, context);
            }

            versionSeriesId = sourceDoc.getVersionSeriesId();
        }

        // second attempt: if we have a Document object in the cache, retrieve
        // the version series ID form there
        if (versionSeriesId == null) {
            if (context.isCacheEnabled()) {
                CmisObject sourceObj = cache.getById(objectId.getId(), context.getCacheKey());
                if (sourceObj instanceof Document) {
                    Document sourceDoc = (Document) sourceObj;

                    if (!sourceDoc.isVersionable()) {
                        // if it is not versionable, a getObject() is sufficient
                        return (Document) getObject(sourceDoc, context);
                    }

                    versionSeriesId = sourceDoc.getVersionSeriesId();
                }
            }
        }

        // third attempt (Web Services only): get the version series ID from the
        // repository
        // (the AtomPub and Browser binding don't need the version series ID ->
        // avoid roundtrip)
        if (versionSeriesId == null) {
            BindingType bindingType = getBinding().getBindingType();
            if (bindingType == BindingType.WEBSERVICES || bindingType == BindingType.CUSTOM) {

                // get the document to find the version series ID
                ObjectData sourceObjectData = binding.getObjectService().getObject(getRepositoryId(), objectId.getId(),
                        PropertyIds.OBJECT_ID + "," + PropertyIds.OBJECT_TYPE_ID + "," + PropertyIds.VERSION_SERIES_ID,
                        false, IncludeRelationships.NONE, "cmis:none", false, false, null);

                String objectTypeId = null;

                if (sourceObjectData.getProperties() != null
                        && sourceObjectData.getProperties().getProperties() != null) {

                    PropertyData<?> objectTypeIdProp = sourceObjectData.getProperties().getProperties()
                            .get(PropertyIds.OBJECT_TYPE_ID);
                    if (objectTypeIdProp != null && objectTypeIdProp.getFirstValue() instanceof String) {
                        objectTypeId = (String) objectTypeIdProp.getFirstValue();
                    }

                    PropertyData<?> verionsSeriesIdProp = sourceObjectData.getProperties().getProperties()
                            .get(PropertyIds.VERSION_SERIES_ID);
                    if (verionsSeriesIdProp != null && verionsSeriesIdProp.getFirstValue() instanceof String) {
                        versionSeriesId = (String) verionsSeriesIdProp.getFirstValue();
                    }
                }

                // the Web Services binding needs the version series ID
                if (versionSeriesId == null) {

                    ObjectType type = getTypeDefinition(objectTypeId);
                    if (type instanceof DocumentType && Boolean.FALSE.equals(((DocumentType) type).isVersionable())) {
                        // if the document is not versionable, we don't need a
                        // version series ID
                        return (Document) getObject(objectId, context);
                    }

                    throw new IllegalArgumentException("Object is not a document or not versionable!");
                }
            }
        }

        // get the object
        ObjectData objectData = binding.getVersioningService().getObjectOfLatestVersion(getRepositoryId(),
                objectId.getId(), versionSeriesId, major, context.getFilterString(),
                context.isIncludeAllowableActions(), context.getIncludeRelationships(),
                context.getRenditionFilterString(), context.isIncludePolicies(), context.isIncludeAcls(), null);

        result = getObjectFactory().convertObject(objectData, context);

        // put into cache
        if (context.isCacheEnabled()) {
            cache.put(result, context.getCacheKey());
        }

        // check result
        if (!(result instanceof Document)) {
            throw new IllegalArgumentException("Latest version is not a document!");
        }

        return (Document) result;
    }

    @Override
    public boolean exists(ObjectId objectId) {
        checkObjectId(objectId);
        return exists(objectId.getId());
    }

    @Override
    public boolean exists(String objectId) {
        checkObjectId(objectId);

        try {
            binding.getObjectService().getObject(getRepositoryId(), objectId, "cmis:objectId", Boolean.FALSE,
                    IncludeRelationships.NONE, "cmis:none", Boolean.FALSE, Boolean.FALSE, null);
            return true;
        } catch (CmisObjectNotFoundException onf) {
            removeObjectFromCache(objectId);
            return false;
        }
    }

    @Override
    public boolean existsPath(String path) {
        checkPath(path);

        try {
            String objectId = getObjectIdByPath(path);
            String cacheObjectId = cache.getObjectIdByPath(path);

            if (cacheObjectId != null && !cacheObjectId.equals(objectId)) {
                cache.removePath(path);
            }

            return true;
        } catch (CmisObjectNotFoundException onf) {
            return false;
        }
    }

    @Override
    public boolean existsPath(String parentPath, String name) {
        return existsPath(buildPath(parentPath, name));
    }

    @Override
    public void removeObjectFromCache(ObjectId objectId) {
        checkObjectId(objectId);
        removeObjectFromCache(objectId.getId());
    }

    @Override
    public void removeObjectFromCache(String objectId) {
        cache.remove(objectId);
    }

    @Override
    public RepositoryInfo getRepositoryInfo() {
        lock.readLock().lock();
        try {
            return repositoryInfo;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Folder getRootFolder() {
        return getRootFolder(getDefaultContext());
    }

    @Override
    public Folder getRootFolder(OperationContext context) {
        String rootFolderId = getRepositoryInfo().getRootFolderId();

        CmisObject rootFolder = getObject(rootFolderId, context);
        if (!(rootFolder instanceof Folder)) {
            throw new CmisRuntimeException("Root folder object is not a folder!");
        }

        return (Folder) rootFolder;
    }

    @Override
    public ItemIterable<ObjectType> getTypeChildren(final String typeId, final boolean includePropertyDefinitions) {
        final RepositoryService repositoryService = getBinding().getRepositoryService();

        return new CollectionIterable<ObjectType>(
                new AbstractPageFetcher<ObjectType>(getDefaultContext().getMaxItemsPerPage()) {

                    @Override
                    protected AbstractPageFetcher.Page<ObjectType> fetchPage(long skipCount) {

                        // fetch the data
                        TypeDefinitionList tdl = repositoryService.getTypeChildren(SessionImpl.this.getRepositoryId(),
                                typeId, includePropertyDefinitions, BigInteger.valueOf(this.maxNumItems),
                                BigInteger.valueOf(skipCount), null);

                        // convert type definitions
                        List<ObjectType> page = new ArrayList<ObjectType>(tdl.getList().size());
                        for (TypeDefinition typeDefinition : tdl.getList()) {
                            page.add(convertTypeDefinition(typeDefinition));
                        }

                        return new AbstractPageFetcher.Page<ObjectType>(page, tdl.getNumItems(), tdl.hasMoreItems()) {
                        };
                    }
                });
    }

    @Override
    public ObjectType getTypeDefinition(String typeId) {
        TypeDefinition typeDefinition = getBinding().getRepositoryService().getTypeDefinition(getRepositoryId(), typeId,
                null);

        return convertAndCacheTypeDefinition(typeDefinition, true);
    }

    @Override
    public ObjectType getTypeDefinition(String typeId, boolean useCache) {
        RepositoryService service = getBinding().getRepositoryService();
        if (!(service instanceof ExtendedRepositoryService)) {
            throw new CmisRuntimeException(
                    "Internal error: Repository Service does not implement ExtendedRepositoryService!");
        }

        ExtendedRepositoryService extRepSrv = (ExtendedRepositoryService) service;
        TypeDefinition typeDefinition = extRepSrv.getTypeDefinition(getRepositoryId(), typeId, null, useCache);

        return convertAndCacheTypeDefinition(typeDefinition, useCache);
    }

    @Override
    public List<Tree<ObjectType>> getTypeDescendants(String typeId, int depth, boolean includePropertyDefinitions) {
        List<TypeDefinitionContainer> descendants = getBinding().getRepositoryService().getTypeDescendants(
                getRepositoryId(), typeId, BigInteger.valueOf(depth), includePropertyDefinitions, null);

        return convertTypeDescendants(descendants);
    }

    /**
     * Converts binding <code>TypeDefinitionContainer</code> to API
     * <code>Container</code>.
     */
    private List<Tree<ObjectType>> convertTypeDescendants(List<TypeDefinitionContainer> descendantsList) {
        List<Tree<ObjectType>> result = new ArrayList<Tree<ObjectType>>();

        for (TypeDefinitionContainer container : descendantsList) {
            ObjectType objectType = convertTypeDefinition(container.getTypeDefinition());
            List<Tree<ObjectType>> children = convertTypeDescendants(container.getChildren());

            result.add(new TreeImpl<ObjectType>(objectType, children));
        }

        return result;
    }

    private ObjectType convertTypeDefinition(TypeDefinition typeDefinition) {
        return objectFactory.convertTypeDefinition(typeDefinition);
    }

    /**
     * Converts a type definition into an object type and caches the result.
     *
     * The cache should only be used for type definitions that have been fetched
     * with getTypeDefinition() because the high level cache should roughly
     * correspond to the low level type cache. The type definitions returned by
     * getTypeChildren() and getTypeDescendants() are not cached in the low
     * level cache and therefore shouldn't be cached here.
     */
    private ObjectType convertAndCacheTypeDefinition(TypeDefinition typeDefinition, boolean useCache) {
        ObjectType result = null;

        lock.writeLock().lock();
        try {
            if (objectTypeCache == null) {
                int cacheSize;
                try {
                    cacheSize = Integer.valueOf(parameters.get(SessionParameter.CACHE_SIZE_TYPES));
                    if (cacheSize < 0) {
                        cacheSize = SessionParameterDefaults.CACHE_SIZE_TYPES;
                    }
                } catch (NumberFormatException nfe) {
                    cacheSize = SessionParameterDefaults.CACHE_SIZE_TYPES;
                }

                final int maxEntries = cacheSize;

                objectTypeCache = new LinkedHashMap<String, ObjectType>(maxEntries + 1, 0.70f, true) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public boolean removeEldestEntry(Map.Entry<String, ObjectType> eldest) {
                        return size() > maxEntries;
                    }
                };
            }

            if (!useCache) {
                result = objectFactory.convertTypeDefinition(typeDefinition);
                objectTypeCache.put(result.getId(), result);
            } else {
                result = objectTypeCache.get(typeDefinition.getId());
                if (result == null) {
                    result = objectFactory.convertTypeDefinition(typeDefinition);
                    objectTypeCache.put(result.getId(), result);
                }
            }

            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes the object type object with the given type ID from the cache.
     */
    private void removeFromObjectTypeCache(String typeId) {
        lock.writeLock().lock();
        try {
            if (objectTypeCache != null) {
                objectTypeCache.remove(typeId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public ObjectType createType(TypeDefinition type) {
        checkCmisVersion();

        TypeDefinition newType = getBinding().getRepositoryService().createType(getRepositoryId(), type, null);
        return convertTypeDefinition(newType);
    }

    @Override
    public ObjectType updateType(TypeDefinition type) {
        checkCmisVersion();

        TypeDefinition updatedType = getBinding().getRepositoryService().updateType(getRepositoryId(), type, null);

        removeFromObjectTypeCache(updatedType.getId());

        return convertTypeDefinition(updatedType);
    }

    @Override
    public void deleteType(String typeId) {
        checkCmisVersion();

        getBinding().getRepositoryService().deleteType(getRepositoryId(), typeId, null);
        removeFromObjectTypeCache(typeId);
    }

    @Override
    public ItemIterable<QueryResult> query(final String statement, final boolean searchAllVersions) {
        return query(statement, searchAllVersions, getDefaultContext());
    }

    @Override
    public ItemIterable<QueryResult> query(final String statement, final boolean searchAllVersions,
            OperationContext context) {
        checkContext(context);

        final DiscoveryService discoveryService = getBinding().getDiscoveryService();
        final ObjectFactory of = getObjectFactory();
        final OperationContext ctxt = new OperationContextImpl(context);

        return new CollectionIterable<QueryResult>(new AbstractPageFetcher<QueryResult>(ctxt.getMaxItemsPerPage()) {

            @Override
            protected AbstractPageFetcher.Page<QueryResult> fetchPage(long skipCount) {

                // fetch the data
                ObjectList resultList = discoveryService.query(getRepositoryId(), statement, searchAllVersions,
                        ctxt.isIncludeAllowableActions(), ctxt.getIncludeRelationships(),
                        ctxt.getRenditionFilterString(), BigInteger.valueOf(this.maxNumItems),
                        BigInteger.valueOf(skipCount), null);

                // convert query results
                List<QueryResult> page = new ArrayList<QueryResult>();
                if (resultList.getObjects() != null) {
                    for (ObjectData objectData : resultList.getObjects()) {
                        if (objectData == null) {
                            continue;
                        }

                        page.add(of.convertQueryResult(objectData));
                    }
                }

                return new AbstractPageFetcher.Page<QueryResult>(page, resultList.getNumItems(),
                        resultList.hasMoreItems());
            }
        });
    }

    @Override
    public ItemIterable<CmisObject> queryObjects(String typeId, String where, final boolean searchAllVersions,
            OperationContext context) {
        if (typeId == null || typeId.trim().length() == 0) {
            throw new IllegalArgumentException("Type ID must be set!");
        }

        checkContext(context);

        final DiscoveryService discoveryService = getBinding().getDiscoveryService();
        final ObjectFactory of = getObjectFactory();
        final OperationContext ctxt = new OperationContextImpl(context);
        final StringBuilder statement = new StringBuilder(1024);

        statement.append("SELECT ");

        String select = ctxt.getFilterString();
        if (select == null) {
            statement.append('*');
        } else {
            statement.append(select);
        }

        final ObjectType type = getTypeDefinition(typeId);
        statement.append(" FROM ");
        statement.append(type.getQueryName());

        if (where != null && where.trim().length() > 0) {
            statement.append(" WHERE ");
            statement.append(where);
        }

        String orderBy = ctxt.getOrderBy();
        if (orderBy != null && orderBy.trim().length() > 0) {
            statement.append(" ORDER BY ");
            statement.append(orderBy);
        }

        return new CollectionIterable<CmisObject>(new AbstractPageFetcher<CmisObject>(ctxt.getMaxItemsPerPage()) {

            @Override
            protected AbstractPageFetcher.Page<CmisObject> fetchPage(long skipCount) {

                // fetch the data
                ObjectList resultList = discoveryService.query(getRepositoryId(), statement.toString(),
                        searchAllVersions, ctxt.isIncludeAllowableActions(), ctxt.getIncludeRelationships(),
                        ctxt.getRenditionFilterString(), BigInteger.valueOf(this.maxNumItems),
                        BigInteger.valueOf(skipCount), null);

                // convert query results
                List<CmisObject> page = new ArrayList<CmisObject>();
                if (resultList.getObjects() != null) {
                    for (ObjectData objectData : resultList.getObjects()) {
                        if (objectData == null) {
                            continue;
                        }

                        page.add(of.convertObject(objectData, ctxt));
                    }
                }

                return new AbstractPageFetcher.Page<CmisObject>(page, resultList.getNumItems(),
                        resultList.hasMoreItems());
            }
        });
    }

    @Override
    public QueryStatement createQueryStatement(final String statement) {
        return new QueryStatementImpl(this, statement);
    }

    @Override
    public QueryStatement createQueryStatement(final Collection<String> selectPropertyIds,
            final Map<String, String> fromTypes, final String whereClause, final List<String> orderByPropertyIds) {
        return new QueryStatementImpl(this, selectPropertyIds, fromTypes, whereClause, orderByPropertyIds);
    }

    /**
     * Connect session object to the provider. This is the very first call after
     * a session is created.
     * <p>
     * In dependency of the parameter set an {@code AtomPub}, a
     * {@code WebService} or an {@code InMemory} provider is selected.
     */
    public void connect() {
        lock.writeLock().lock();
        try {
            binding = CmisBindingHelper.createBinding(parameters, authenticationProvider, typeDefCache);

            /* get initial repository ID from session parameter */
            String repositoryId = parameters.get(SessionParameter.REPOSITORY_ID);
            if (repositoryId == null) {
                throw new IllegalStateException("Repository ID is not set!");
            }

            repositoryInfo = objectFactory
                    .convertRepositoryInfo(getBinding().getRepositoryService().getRepositoryInfo(repositoryId, null));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CmisBinding getBinding() {
        lock.readLock().lock();
        try {
            return binding;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Cache getCache() {
        lock.readLock().lock();
        try {
            return cache;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the repository id.
     */
    public String getRepositoryId() {
        return getRepositoryInfo().getId();
    }

    // --- creates ---
    @Override
    public ObjectId createDocument(Map<String, ?> properties, ObjectId folderId, ContentStream contentStream,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces) {
        checkProperties(properties);

        String newId = getBinding().getObjectService().createDocument(getRepositoryId(),
                objectFactory.convertProperties(properties, null, null, CREATE_AND_CHECKOUT_UPDATABILITY),
                (folderId == null ? null : folderId.getId()), objectFactory.convertContentStream(contentStream),
                versioningState, objectFactory.convertPolicies(policies), objectFactory.convertAces(addAces),
                objectFactory.convertAces(removeAces), null);

        if (newId == null) {
            return null;
        }

        return createObjectId(newId);
    }

    @Override
    public ObjectId createDocumentFromSource(ObjectId source, Map<String, ?> properties, ObjectId folderId,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces) {
        if (source == null || source.getId() == null) {
            throw new IllegalArgumentException("Source must be set!");
        }

        // get the type of the source document
        ObjectType type = null;
        List<SecondaryType> secondaryTypes = null;
        if (source instanceof CmisObject) {
            type = ((CmisObject) source).getType();
            secondaryTypes = ((CmisObject) source).getSecondaryTypes();
        } else {
            CmisObject sourceObj = getObject(source);
            type = sourceObj.getType();
            secondaryTypes = sourceObj.getSecondaryTypes();
        }

        if (type.getBaseTypeId() != BaseTypeId.CMIS_DOCUMENT) {
            throw new IllegalArgumentException("Source object must be a document!");
        }

        String newId = getBinding().getObjectService().createDocumentFromSource(getRepositoryId(), source.getId(),
                objectFactory.convertProperties(properties, type, secondaryTypes, CREATE_AND_CHECKOUT_UPDATABILITY),
                (folderId == null ? null : folderId.getId()), versioningState, objectFactory.convertPolicies(policies),
                objectFactory.convertAces(addAces), objectFactory.convertAces(removeAces), null);

        if (newId == null) {
            return null;
        }

        return createObjectId(newId);
    }

    @Override
    public ObjectId createFolder(Map<String, ?> properties, ObjectId folderId, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces) {
        checkFolderId(folderId);
        checkProperties(properties);

        String newId = getBinding().getObjectService().createFolder(getRepositoryId(),
                objectFactory.convertProperties(properties, null, null, CREATE_UPDATABILITY), folderId.getId(),
                objectFactory.convertPolicies(policies), objectFactory.convertAces(addAces),
                objectFactory.convertAces(removeAces), null);

        if (newId == null) {
            return null;
        }

        return createObjectId(newId);
    }

    @Override
    public ObjectId createPath(String newPath, String typeId) {
        return createPath(null, newPath, typeId);
    }

    @Override
    public ObjectId createPath(ObjectId startFolderId, String newPath, String typeId) {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, typeId);

        return createPath(startFolderId, newPath, properties, null, null, null);
    }

    @Override
    public ObjectId createPath(String newPath, Map<String, ?> properties) {
        return createPath(null, newPath, properties);
    }

    @Override
    public ObjectId createPath(ObjectId startFolderId, String newPath, Map<String, ?> properties) {
        return createPath(startFolderId, newPath, properties, null, null, null);
    }

    @Override
    public ObjectId createPath(ObjectId startFolderId, String newPath, Map<String, ?> properties, List<Policy> policies,
            List<Ace> addAces, List<Ace> removeAces) {
        checkPath(newPath);
        if (newPath.length() == 1) {
            throw new IllegalArgumentException("Cannot create root folder!");
        }
        if (newPath.charAt(newPath.length() - 1) == '/') {
            throw new IllegalArgumentException("Path cannot end with a '/'!");
        }

        checkProperties(properties);
        if (!(properties.get(PropertyIds.OBJECT_TYPE_ID) instanceof String)) {
            throw new IllegalArgumentException("Property '" + PropertyIds.OBJECT_TYPE_ID + "' not set or invalid!");
        }

        StringBuilder nextPath = new StringBuilder(newPath.length());
        String[] segements;
        ObjectId lastFolderId = null;
        boolean create = false;

        // check start folder
        if (startFolderId != null && startFolderId.getId() != null) {
            if (startFolderId instanceof Folder) {
                Folder startFolder = (Folder) startFolderId;
                if (!startFolder.isRootFolder()) {
                    nextPath.append(startFolder.getPath());
                    lastFolderId = startFolder;
                }
            } else {
                ObjectData startFolderData = null;
                try {
                    startFolderData = getBinding().getObjectService().getObject(getRepositoryId(),
                            startFolderId.getId(), "cmis:objectId,cmis:baseTypeId,cmis:name,cmis:path", false,
                            IncludeRelationships.NONE, "cmis:none", false, false, null);
                } catch (CmisBaseException cbe) {
                    throw new IllegalArgumentException("Start folder does not exist or is not accessible!", cbe);
                }

                if (startFolderData.getBaseTypeId() != BaseTypeId.CMIS_FOLDER) {
                    throw new IllegalArgumentException("Start folder is not a folder!");
                }

                if (startFolderData.getProperties() == null || startFolderData.getProperties().getProperties() == null
                        || startFolderData.getProperties().getProperties().get(PropertyIds.PATH) == null) {
                    throw new IllegalArgumentException("Start folder has no path property?!");
                }

                Object startPath = startFolderData.getProperties().getProperties().get(PropertyIds.PATH)
                        .getFirstValue();
                if (!(startPath instanceof String)) {
                    throw new IllegalArgumentException("Start folder has an invalid path property?!");
                }

                if (!repositoryInfo.getRootFolderId().equals(startFolderData.getId())) {
                    nextPath.append(startPath);
                    lastFolderId = startFolderId;
                }
            }

            if (!newPath.startsWith(nextPath.toString())) {
                throw new IllegalArgumentException("Start folder in not in the path!");
            }

            segements = newPath.substring(nextPath.length()).split("/");
        } else {
            segements = newPath.split("/");
        }

        // create folders
        for (int i = 1; i < segements.length; i++) {
            if (create) {
                lastFolderId = createFolder(buildCreatePathProperties(properties, segements[i]), lastFolderId, policies,
                        addAces, removeAces);
            } else {
                try {
                    nextPath.append('/');
                    nextPath.append(segements[i]);

                    ObjectData folderData = getBinding().getObjectService().getObjectByPath(getRepositoryId(),
                            nextPath.toString(), "cmis:objectId,cmis:baseTypeId,cmis:name", false,
                            IncludeRelationships.NONE, "cmis:none", false, false, null);
                    if (folderData.getBaseTypeId() != BaseTypeId.CMIS_FOLDER) {
                        throw new CmisConstraintException("Cannot create a folder '" + segements[i]
                                + "' because there is already an object with this name, which is not a folder!");
                    }

                    lastFolderId = new ObjectIdImpl(folderData.getId());
                } catch (CmisObjectNotFoundException onfe) {
                    if (lastFolderId == null) {
                        lastFolderId = new ObjectIdImpl(repositoryInfo.getRootFolderId());
                    }

                    lastFolderId = createFolder(buildCreatePathProperties(properties, segements[i]), lastFolderId,
                            policies, addAces, removeAces);
                    create = true;
                }
            }
        }

        return lastFolderId;
    }

    private Map<String, ?> buildCreatePathProperties(Map<String, ?> properties, String name) {
        Map<String, Object> newProperties = new HashMap<String, Object>(properties);
        newProperties.put(PropertyIds.NAME, name);

        return newProperties;
    }

    @Override
    public ObjectId createPolicy(Map<String, ?> properties, ObjectId folderId, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces) {
        checkProperties(properties);

        String newId = getBinding().getObjectService().createPolicy(getRepositoryId(),
                objectFactory.convertProperties(properties, null, null, CREATE_UPDATABILITY),
                (folderId == null ? null : folderId.getId()), objectFactory.convertPolicies(policies),
                objectFactory.convertAces(addAces), objectFactory.convertAces(removeAces), null);

        if (newId == null) {
            return null;
        }

        return createObjectId(newId);
    }

    @Override
    public ObjectId createItem(Map<String, ?> properties, ObjectId folderId, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces) {
        checkProperties(properties);

        String newId = getBinding().getObjectService().createItem(getRepositoryId(),
                objectFactory.convertProperties(properties, null, null, CREATE_UPDATABILITY),
                (folderId == null ? null : folderId.getId()), objectFactory.convertPolicies(policies),
                objectFactory.convertAces(addAces), objectFactory.convertAces(removeAces), null);

        if (newId == null) {
            return null;
        }

        return createObjectId(newId);
    }

    @Override
    public ObjectId createRelationship(Map<String, ?> properties, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces) {
        checkProperties(properties);

        String newId = getBinding().getObjectService().createRelationship(getRepositoryId(),
                objectFactory.convertProperties(properties, null, null, CREATE_UPDATABILITY),
                objectFactory.convertPolicies(policies), objectFactory.convertAces(addAces),
                objectFactory.convertAces(removeAces), null);

        if (newId == null) {
            return null;
        }

        return createObjectId(newId);
    }

    @Override
    public ObjectId createDocument(Map<String, ?> properties, ObjectId folderId, ContentStream contentStream,
            VersioningState versioningState) {
        return createDocument(properties, folderId, contentStream, versioningState, null, null, null);
    }

    @Override
    public ObjectId createDocumentFromSource(ObjectId source, Map<String, ?> properties, ObjectId folderId,
            VersioningState versioningState) {
        return createDocumentFromSource(source, properties, folderId, versioningState, null, null, null);
    }

    @Override
    public ObjectId createFolder(Map<String, ?> properties, ObjectId folderId) {
        return createFolder(properties, folderId, null, null, null);
    }

    @Override
    public ObjectId createPolicy(Map<String, ?> properties, ObjectId folderId) {
        return createPolicy(properties, folderId, null, null, null);
    }

    @Override
    public ObjectId createItem(Map<String, ?> properties, ObjectId folderId) {
        return createItem(properties, folderId, null, null, null);
    }

    // --- relationships ---
    @Override
    public ObjectId createRelationship(Map<String, ?> properties) {
        return createRelationship(properties, null, null, null);
    }

    @Override
    public ItemIterable<Relationship> getRelationships(ObjectId objectId, final boolean includeSubRelationshipTypes,
            final RelationshipDirection relationshipDirection, ObjectType type, OperationContext context) {
        checkObjectId(objectId);
        checkContext(context);

        final String id = objectId.getId();
        final String typeId = type == null ? null : type.getId();
        final RelationshipService relationshipService = getBinding().getRelationshipService();
        final OperationContext ctxt = new OperationContextImpl(context);

        return new CollectionIterable<Relationship>(new AbstractPageFetcher<Relationship>(ctxt.getMaxItemsPerPage()) {

            @Override
            protected AbstractPageFetcher.Page<Relationship> fetchPage(long skipCount) {

                // fetch the relationships
                ObjectList relList = relationshipService.getObjectRelationships(getRepositoryId(), id,
                        includeSubRelationshipTypes, relationshipDirection, typeId, ctxt.getFilterString(),
                        ctxt.isIncludeAllowableActions(), BigInteger.valueOf(this.maxNumItems),
                        BigInteger.valueOf(skipCount), null);

                // convert relationship objects
                List<Relationship> page = new ArrayList<Relationship>();
                if (relList.getObjects() != null) {
                    for (ObjectData rod : relList.getObjects()) {
                        CmisObject relationship = getObject(rod.getId(), ctxt);
                        if (!(relationship instanceof Relationship)) {
                            throw new CmisRuntimeException("Repository returned an object that is not a relationship!");
                        }

                        page.add((Relationship) relationship);
                    }
                }

                return new AbstractPageFetcher.Page<Relationship>(page, relList.getNumItems(), relList.hasMoreItems());
            }
        });
    }

    // --- bulk update ---
    @Override
    public List<BulkUpdateObjectIdAndChangeToken> bulkUpdateProperties(List<CmisObject> objects,
            Map<String, ?> properties, List<String> addSecondaryTypeIds, List<String> removeSecondaryTypeIds) {
        checkCmisVersion();
        checkProperties(properties);

        ObjectType objectType = null;
        Map<String, SecondaryType> secondaryTypes = new HashMap<String, SecondaryType>();

        // gather secondary types
        if (addSecondaryTypeIds != null) {
            for (String stid : addSecondaryTypeIds) {
                ObjectType secondaryType = getTypeDefinition(stid);

                if (!(secondaryType instanceof SecondaryType)) {
                    throw new IllegalArgumentException(
                            "Secondary types contains a type that is not a secondary type: " + secondaryType.getId());
                }

                secondaryTypes.put(secondaryType.getId(), (SecondaryType) secondaryType);
            }
        }

        // gather IDs and change tokens
        List<BulkUpdateObjectIdAndChangeToken> objectIdsAndChangeTokens = new ArrayList<BulkUpdateObjectIdAndChangeToken>();
        for (CmisObject object : objects) {
            if (object == null) {
                continue;
            }

            objectIdsAndChangeTokens
                    .add(new BulkUpdateObjectIdAndChangeTokenImpl(object.getId(), object.getChangeToken()));

            if (objectType == null) {
                objectType = object.getType();
            }

            if (object.getSecondaryTypes() != null) {
                for (SecondaryType secondaryType : object.getSecondaryTypes()) {
                    secondaryTypes.put(secondaryType.getId(), secondaryType);
                }
            }
        }

        Set<Updatability> updatebility = EnumSet.noneOf(Updatability.class);
        updatebility.add(Updatability.READWRITE);

        return getBinding().getObjectService().bulkUpdateProperties(getRepositoryId(), objectIdsAndChangeTokens,
                objectFactory.convertProperties(properties, objectType, secondaryTypes.values(), updatebility),
                addSecondaryTypeIds, removeSecondaryTypeIds, null);
    }

    // --- delete ---
    @Override
    public void delete(ObjectId objectId) {
        delete(objectId, true);
    }

    @Override
    public void delete(ObjectId objectId, boolean allVersions) {
        checkObjectId(objectId);

        getBinding().getObjectService().deleteObject(getRepositoryId(), objectId.getId(), allVersions, null);
        removeObjectFromCache(objectId);
    }

    @Override
    public void deleteByPath(String path) {
        deleteByPath(path, true);
    }

    @Override
    public void deleteByPath(String parentPath, String name) {
        deleteByPath(buildPath(parentPath, name), true);
    }

    @Override
    public void deleteByPath(String path, boolean allVersions) {
        checkPath(path);

        delete(new ObjectIdImpl(getObjectIdByPath(path)), allVersions);
    }

    @Override
    public List<String> deleteTree(ObjectId folderId, boolean allVersions, UnfileObject unfile,
            boolean continueOnFailure) {
        checkFolderId(folderId);

        FailedToDeleteData failed = getBinding().getObjectService().deleteTree(getRepositoryId(), folderId.getId(),
                allVersions, unfile, continueOnFailure, null);

        if (failed == null || isNullOrEmpty(failed.getIds())) {
            removeObjectFromCache(folderId);
        }

        return (failed != null ? failed.getIds() : null);
    }

    @Override
    public List<String> deleteTreebyPath(String parentPath, String name, boolean allVersions, UnfileObject unfile,
            boolean continueOnFailure) {
        return deleteTreebyPath(buildPath(parentPath, name), allVersions, unfile, continueOnFailure);
    }

    @Override
    public List<String> deleteTreebyPath(String path, boolean allVersions, UnfileObject unfile,
            boolean continueOnFailure) {
        checkPath(path);

        return deleteTree(new ObjectIdImpl(getObjectIdByPath(path)), allVersions, unfile, continueOnFailure);
    }

    // --- content stream ---
    @Override
    public ContentStream getContentStream(ObjectId docId) {
        return getContentStream(docId, null, null, null);
    }

    @Override
    public ContentStream getContentStream(ObjectId docId, String streamId, BigInteger offset, BigInteger length) {
        checkDocumentId(docId);

        // get the stream
        ContentStream contentStream = null;
        try {
            contentStream = getBinding().getObjectService().getContentStream(getRepositoryId(), docId.getId(), streamId,
                    offset, length, null);
        } catch (CmisConstraintException e) {
            // no content stream
            return null;
        } catch (CmisObjectNotFoundException onfe) {
            removeObjectFromCache(docId.getId());
            throw onfe;
        }

        return contentStream;
    }

    @Override
    public ContentStream getContentStreamByPath(String path) {
        return getContentStreamByPath(path, null, null, null);
    }

    @Override
    public ContentStream getContentStreamByPath(String path, String streamId, BigInteger offset, BigInteger length) {
        checkPath(path);

        // check the cache
        boolean fromCache = true;
        String objectId = cache.getObjectIdByPath(path);

        // not in cache -> get the object
        if (objectId == null) {
            fromCache = false;
            objectId = getObjectIdByPath(path);

            // don't check if the object is a document
            // the path could belong to a folder or an item and the stream ID
            // could point to a rendition
        }

        // get the stream
        ContentStream contentStream = null;
        try {
            contentStream = getBinding().getObjectService().getContentStream(getRepositoryId(), objectId, streamId,
                    offset, length, null);
        } catch (CmisConstraintException ce) {
            // no content stream
            return null;
        } catch (CmisObjectNotFoundException onfe) {
            if (fromCache) {
                removeObjectFromCache(objectId);
                cache.removePath(path);
            } else {
                throw onfe;
            }
        }

        if (contentStream == null) {
            // we are here because we got the object ID from the cache but the
            // object couldn't be found anymore
            // there maybe now a new object at this path -> let's try again

            contentStream = getBinding().getObjectService().getContentStream(getRepositoryId(), getObjectIdByPath(path),
                    streamId, offset, length, null);
        }

        return contentStream;
    }

    // --- ACL ---
    @Override
    public Acl getAcl(ObjectId objectId, boolean onlyBasicPermissions) {
        checkObjectId(objectId);
        return getBinding().getAclService().getAcl(getRepositoryId(), objectId.getId(), onlyBasicPermissions, null);
    }

    @Override
    public Acl applyAcl(ObjectId objectId, List<Ace> addAces, List<Ace> removeAces, AclPropagation aclPropagation) {
        checkObjectId(objectId);

        ObjectFactory of = getObjectFactory();

        return getBinding().getAclService().applyAcl(getRepositoryId(), objectId.getId(), of.convertAces(addAces),
                of.convertAces(removeAces), aclPropagation, null);
    }

    @Override
    public Acl setAcl(ObjectId objectId, List<Ace> aces) {
        checkObjectId(objectId);

        if (aces == null) {
            aces = Collections.emptyList();
        }

        AclService aclService = getBinding().getAclService();
        if (!(aclService instanceof ExtendedAclService)) {
            throw new CmisNotSupportedException("setAcl() is not supported by the binding implementation.");
        }

        ObjectFactory of = getObjectFactory();

        return ((ExtendedAclService) aclService).setAcl(getRepositoryId(), objectId.getId(), of.convertAces(aces));
    }

    // --- Policies ---
    @Override
    public void applyPolicy(ObjectId objectId, ObjectId... policyIds) {
        checkObjectId(objectId);

        if (policyIds == null || policyIds.length == 0) {
            throw new IllegalArgumentException("No Policies provided!");
        }

        String[] ids = new String[policyIds.length];
        for (int i = 0; i < policyIds.length; i++) {
            if (policyIds[i] == null || policyIds[i].getId() == null) {
                throw new IllegalArgumentException("A Policy ID is not set!");
            }

            ids[i] = policyIds[i].getId();
        }

        for (String id : ids) {
            getBinding().getPolicyService().applyPolicy(getRepositoryId(), id, objectId.getId(), null);
        }
    }

    @Override
    public void removePolicy(ObjectId objectId, ObjectId... policyIds) {
        checkObjectId(objectId);

        if (policyIds == null || policyIds.length == 0) {
            throw new IllegalArgumentException("No Policies provided!");
        }

        String[] ids = new String[policyIds.length];
        for (int i = 0; i < policyIds.length; i++) {
            if (policyIds[i] == null || policyIds[i].getId() == null) {
                throw new IllegalArgumentException("A Policy ID is not set!");
            }

            ids[i] = policyIds[i].getId();
        }

        for (String id : ids) {
            getBinding().getPolicyService().removePolicy(getRepositoryId(), id, objectId.getId(), null);
        }
    }

    // ----
    protected String buildPath(String parentPath, String name) {
        if (parentPath == null || parentPath.length() < 1) {
            throw new IllegalArgumentException("Parent path must be set!");
        }
        if (parentPath.charAt(0) != '/') {
            throw new IllegalArgumentException("Parent path must start with a '/'!");
        }
        if (name == null || name.length() < 1) {
            throw new IllegalArgumentException("Name must be set!");
        }

        StringBuilder path = new StringBuilder(parentPath.length() + name.length() + 2);
        path.append(parentPath);
        if (!parentPath.endsWith("/")) {
            path.append('/');
        }
        path.append(name);

        return path.toString();
    }

    protected String getObjectIdByPath(String path) {
        try {
            return getBinding().getObjectService().getObjectByPath(getRepositoryId(), path,
                    "cmis:objectId,cmis:baseTypeId", false, IncludeRelationships.NONE, "cmis:none", false, false, null)
                    .getId();
        } catch (CmisObjectNotFoundException onfe) {
            cache.removePath(path);
            throw onfe;
        }
    }

    protected final void checkObjectId(ObjectId objectId) {
        if (objectId == null || objectId.getId() == null) {
            throw new IllegalArgumentException("Invalid object ID!");
        }
    }

    protected final void checkObjectId(String objectId) {
        if (objectId == null) {
            throw new IllegalArgumentException("Invalid object ID!");
        }
    }

    protected final void checkDocumentId(ObjectId docId) {
        if (docId == null || docId.getId() == null) {
            throw new IllegalArgumentException("Invalid document ID!");
        }
    }

    protected final void checkDocumentId(String docId) {
        if (docId == null) {
            throw new IllegalArgumentException("Invalid document ID!");
        }
    }

    protected final void checkFolderId(ObjectId folderId) {
        if (folderId == null || folderId.getId() == null) {
            throw new IllegalArgumentException("Invalid folder ID!");
        }
    }

    protected final void checkPath(String path) {
        if (path == null || path.length() < 1) {
            throw new IllegalArgumentException("Invalid path!");
        }
        if (path.charAt(0) != '/') {
            throw new IllegalArgumentException("Path must start with a '/'!");
        }
    }

    protected final void checkContext(OperationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Invalid Operation Context!");
        }
    }

    protected final void checkProperties(Map<String, ?> properties) {
        if (isNullOrEmpty(properties)) {
            throw new IllegalArgumentException("Properties must not be empty!");
        }
    }

    protected final void checkCmisVersion() {
        if (repositoryInfo.getCmisVersion() == CmisVersion.CMIS_1_0) {
            throw new CmisNotSupportedException("This method is not supported for CMIS 1.0 repositories.");
        }
    }

    // ----
    @Override
    public String toString() {
        return "Session " + getBinding().getSessionId();
    }
}
