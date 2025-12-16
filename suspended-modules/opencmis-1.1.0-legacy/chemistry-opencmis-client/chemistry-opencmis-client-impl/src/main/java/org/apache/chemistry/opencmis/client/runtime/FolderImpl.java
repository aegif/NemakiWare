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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.FolderType;
import org.apache.chemistry.opencmis.client.api.Item;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectFactory;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.client.runtime.util.AbstractPageFetcher;
import org.apache.chemistry.opencmis.client.runtime.util.CollectionIterable;
import org.apache.chemistry.opencmis.client.runtime.util.TreeImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.spi.NavigationService;

public class FolderImpl extends AbstractFilableCmisObject implements Folder {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     */
    public FolderImpl(SessionImpl session, ObjectType objectType, ObjectData objectData, OperationContext context) {
        initialize(session, objectType, objectData, context);
    }

    @Override
    public FolderType getFolderType() {
        ObjectType objectType = super.getType();
        if (objectType instanceof FolderType) {
            return (FolderType) objectType;
        } else {
            throw new ClassCastException("Object type is not a folder type.");
        }
    }

    @Override
    public Document createDocument(Map<String, ?> properties, ContentStream contentStream,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces,
            OperationContext context) {

        ObjectId newId = getSession().createDocument(properties, this, contentStream, versioningState, policies,
                addAces, removeAces);

        // if no context is provided the object will not be fetched
        if (context == null || newId == null) {
            return null;
        }

        // get the new object
        CmisObject object = getSession().getObject(newId, context);
        if (!(object instanceof Document)) {
            throw new CmisRuntimeException("Newly created object is not a document! New id: " + newId);
        }

        return (Document) object;
    }

    @Override
    public Document createDocumentFromSource(ObjectId source, Map<String, ?> properties,
            VersioningState versioningState, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces,
            OperationContext context) {

        ObjectId newId = getSession().createDocumentFromSource(source, properties, this, versioningState, policies,
                addAces, removeAces);

        // if no context is provided the object will not be fetched
        if (context == null || newId == null) {
            return null;
        }

        // get the new object
        CmisObject object = getSession().getObject(newId, context);
        if (!(object instanceof Document)) {
            throw new CmisRuntimeException("Newly created object is not a document! New id: " + newId);
        }

        return (Document) object;
    }

    @Override
    public Folder createFolder(Map<String, ?> properties, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces, OperationContext context) {

        ObjectId newId = getSession().createFolder(properties, this, policies, addAces, removeAces);

        // if no context is provided the object will not be fetched
        if (context == null || newId == null) {
            return null;
        }

        // get the new object
        CmisObject object = getSession().getObject(newId, context);
        if (!(object instanceof Folder)) {
            throw new CmisRuntimeException("Newly created object is not a folder! New id: " + newId);
        }

        return (Folder) object;
    }

    @Override
    public Policy createPolicy(Map<String, ?> properties, List<Policy> policies, List<Ace> addAces,
            List<Ace> removeAces, OperationContext context) {

        ObjectId newId = getSession().createPolicy(properties, this, policies, addAces, removeAces);

        // if no context is provided the object will not be fetched
        if (context == null || newId == null) {
            return null;
        }

        // get the new object
        CmisObject object = getSession().getObject(newId, context);
        if (!(object instanceof Policy)) {
            throw new CmisRuntimeException("Newly created object is not a policy! New id: " + newId);
        }

        return (Policy) object;
    }

    @Override
    public Item createItem(Map<String, ?> properties, List<Policy> policies, List<Ace> addAces, List<Ace> removeAces,
            OperationContext context) {

        ObjectId newId = getSession().createItem(properties, this, policies, addAces, removeAces);

        // if no context is provided the object will not be fetched
        if (context == null || newId == null) {
            return null;
        }

        // get the new object
        CmisObject object = getSession().getObject(newId, context);
        if (!(object instanceof Item)) {
            throw new CmisRuntimeException("Newly created object is not an item! New id: " + newId);
        }

        return (Item) object;
    }

    @Override
    public List<String> deleteTree(boolean allVersions, UnfileObject unfile, boolean continueOnFailure) {
        return getSession().deleteTree(this, allVersions, unfile, continueOnFailure);
    }

    @Override
    public String getParentId() {
        return getPropertyValue(PropertyIds.PARENT_ID);
    }

    @Override
    public List<ObjectType> getAllowedChildObjectTypes() {
        List<ObjectType> result = new ArrayList<ObjectType>();

        readLock();
        try {
            List<String> otids = getPropertyValue(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS);
            if (otids == null) {
                return result;
            }

            for (String otid : otids) {
                result.add(getSession().getTypeDefinition(otid));
            }
        } finally {
            readUnlock();
        }

        return result;
    }

    @Override
    public ItemIterable<Document> getCheckedOutDocs() {
        return getCheckedOutDocs(getSession().getDefaultContext());
    }

    @Override
    public ItemIterable<Document> getCheckedOutDocs(OperationContext context) {
        final String objectId = getObjectId();
        final NavigationService navigationService = getBinding().getNavigationService();
        final ObjectFactory objectFactory = getSession().getObjectFactory();
        final OperationContext ctxt = new OperationContextImpl(context);

        return new CollectionIterable<Document>(new AbstractPageFetcher<Document>(ctxt.getMaxItemsPerPage()) {

            @Override
            protected AbstractPageFetcher.Page<Document> fetchPage(long skipCount) {

                // get checked out documents for this folder
                ObjectList checkedOutDocs = navigationService.getCheckedOutDocs(getRepositoryId(), objectId,
                        ctxt.getFilterString(), ctxt.getOrderBy(), ctxt.isIncludeAllowableActions(),
                        ctxt.getIncludeRelationships(), ctxt.getRenditionFilterString(),
                        BigInteger.valueOf(this.maxNumItems), BigInteger.valueOf(skipCount), null);

                // convert objects
                List<Document> page = new ArrayList<Document>();
                if (checkedOutDocs.getObjects() != null) {
                    for (ObjectData objectData : checkedOutDocs.getObjects()) {
                        CmisObject doc = objectFactory.convertObject(objectData, ctxt);
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
    public ItemIterable<CmisObject> getChildren() {
        return getChildren(getSession().getDefaultContext());
    }

    @Override
    public ItemIterable<CmisObject> getChildren(OperationContext context) {
        final String objectId = getObjectId();
        final NavigationService navigationService = getBinding().getNavigationService();
        final ObjectFactory objectFactory = getSession().getObjectFactory();
        final OperationContext ctxt = new OperationContextImpl(context);

        return new CollectionIterable<CmisObject>(new AbstractPageFetcher<CmisObject>(ctxt.getMaxItemsPerPage()) {

            @Override
            protected AbstractPageFetcher.Page<CmisObject> fetchPage(long skipCount) {

                // get the children
                ObjectInFolderList children = navigationService.getChildren(getRepositoryId(), objectId,
                        ctxt.getFilterString(), ctxt.getOrderBy(), ctxt.isIncludeAllowableActions(),
                        ctxt.getIncludeRelationships(), ctxt.getRenditionFilterString(), ctxt.isIncludePathSegments(),
                        BigInteger.valueOf(this.maxNumItems), BigInteger.valueOf(skipCount), null);

                // convert objects
                List<CmisObject> page = new ArrayList<CmisObject>();
                List<ObjectInFolderData> childObjects = children.getObjects();
                if (childObjects != null) {
                    for (ObjectInFolderData objectData : childObjects) {
                        if (objectData.getObject() != null) {
                            page.add(objectFactory.convertObject(objectData.getObject(), ctxt));
                        }
                    }
                }

                return new AbstractPageFetcher.Page<CmisObject>(page, children.getNumItems(), children.hasMoreItems());
            }
        });
    }

    @Override
    public List<Tree<FileableCmisObject>> getDescendants(int depth) {
        return getDescendants(depth, getSession().getDefaultContext());
    }

    @Override
    public List<Tree<FileableCmisObject>> getDescendants(int depth, OperationContext context) {
        String objectId = getObjectId();

        // get the descendants
        List<ObjectInFolderContainer> providerContainerList = getBinding().getNavigationService().getDescendants(
                getRepositoryId(), objectId, BigInteger.valueOf(depth), context.getFilterString(),
                context.isIncludeAllowableActions(), context.getIncludeRelationships(),
                context.getRenditionFilterString(), context.isIncludePathSegments(), null);

        return convertBindingContainer(providerContainerList, context);
    }

    @Override
    public List<Tree<FileableCmisObject>> getFolderTree(int depth) {
        return getFolderTree(depth, getSession().getDefaultContext());
    }

    @Override
    public List<Tree<FileableCmisObject>> getFolderTree(int depth, OperationContext context) {
        String objectId = getObjectId();

        // get the folder tree
        List<ObjectInFolderContainer> providerContainerList = getBinding().getNavigationService().getFolderTree(
                getRepositoryId(), objectId, BigInteger.valueOf(depth), context.getFilterString(),
                context.isIncludeAllowableActions(), context.getIncludeRelationships(),
                context.getRenditionFilterString(), context.isIncludePathSegments(), null);

        return convertBindingContainer(providerContainerList, context);
    }

    /**
     * Converts a binding container into an API container.
     */
    private List<Tree<FileableCmisObject>> convertBindingContainer(List<ObjectInFolderContainer> bindingContainerList,
            OperationContext context) {
        if (bindingContainerList == null) {
            return null;
        }

        ObjectFactory of = getSession().getObjectFactory();

        List<Tree<FileableCmisObject>> result = new ArrayList<Tree<FileableCmisObject>>();
        for (ObjectInFolderContainer oifc : bindingContainerList) {
            if (oifc.getObject() == null || oifc.getObject().getObject() == null) {
                // shouldn't happen ...
                continue;
            }

            // convert the object
            CmisObject object = of.convertObject(oifc.getObject().getObject(), context);
            if (!(object instanceof FileableCmisObject)) {
                // the repository must not return objects that are not fileable,
                // but you never know...
                continue;
            }

            // convert the children
            List<Tree<FileableCmisObject>> children = convertBindingContainer(oifc.getChildren(), context);

            // add both to current container
            result.add(new TreeImpl<FileableCmisObject>((FileableCmisObject) object, children));
        }

        return result;
    }

    @Override
    public boolean isRootFolder() {
        String objectId = getObjectId();
        String rootFolderId = getSession().getRepositoryInfo().getRootFolderId();

        return objectId.equals(rootFolderId);
    }

    @Override
    public Folder getFolderParent() {
        if (isRootFolder()) {
            return null;
        }

        List<Folder> parents = getParents(getSession().getDefaultContext());
        if (isNullOrEmpty(parents)) {
            return null;
        }

        return parents.get(0);
    }

    @Override
    public String getPath() {
        String path;

        readLock();
        try {
            // get the path property
            path = getPropertyValue(PropertyIds.PATH);

            // if the path property isn't set, get it
            if (path == null) {
                String objectId = getObjectId();
                ObjectData objectData = getBinding().getObjectService().getObject(getRepositoryId(), objectId,
                        getPropertyQueryName(PropertyIds.PATH), false, IncludeRelationships.NONE, "cmis:none", false,
                        false, null);

                if (objectData.getProperties() != null && objectData.getProperties().getProperties() != null) {
                    PropertyData<?> pathProperty = objectData.getProperties().getProperties().get(PropertyIds.PATH);

                    if (pathProperty instanceof PropertyString) {
                        path = ((PropertyString) pathProperty).getFirstValue();
                    }
                }
            }
        } finally {
            readUnlock();
        }

        // we still don't know the path ... it's not a CMIS compliant repository
        if (path == null) {
            throw new CmisRuntimeException("Repository didn't return " + PropertyIds.PATH + "!");
        }

        return path;
    }

    @Override
    public List<Folder> getParents(OperationContext context) {
        if (isRootFolder()) {
            return Collections.emptyList();
        }

        String objectId = getObjectId();

        ObjectData bindingParent = getBinding().getNavigationService().getFolderParent(getRepositoryId(), objectId,
                getPropertyQueryName(PropertyIds.OBJECT_ID), null);

        if (bindingParent.getProperties() == null) {
            // should not happen...
            throw new CmisRuntimeException("Repository sent invalid data!");
        }

        // get id property
        PropertyData<?> idProperty = bindingParent.getProperties().getProperties().get(PropertyIds.OBJECT_ID);
        if (!(idProperty instanceof PropertyId) && !(idProperty instanceof PropertyString)) {
            // the repository sent an object without a valid object id...
            throw new CmisRuntimeException("Repository sent invalid data! No object id!");
        }

        // fetch the object and make sure it is a folder
        CmisObject parentFolder = getSession().getObject((String) idProperty.getFirstValue(), context);
        if (!(parentFolder instanceof Folder)) {
            // the repository sent an object that is not a folder...
            throw new CmisRuntimeException("Repository sent invalid data! Object is not a folder!");
        }

        return Collections.singletonList((Folder) parentFolder);
    }

    @Override
    public List<String> getPaths() {
        return Collections.singletonList(getPath());
    }

    @Override
    public Document createDocument(Map<String, ?> properties, ContentStream contentStream,
            VersioningState versioningState) {
        return this.createDocument(properties, contentStream, versioningState, null, null, null, getSession()
                .getDefaultContext());
    }

    @Override
    public Document createDocumentFromSource(ObjectId source, Map<String, ?> properties, VersioningState versioningState) {
        return this.createDocumentFromSource(source, properties, versioningState, null, null, null, getSession()
                .getDefaultContext());
    }

    @Override
    public Folder createFolder(Map<String, ?> properties) {
        return this.createFolder(properties, null, null, null, getSession().getDefaultContext());
    }

    @Override
    public Policy createPolicy(Map<String, ?> properties) {
        return this.createPolicy(properties, null, null, null, getSession().getDefaultContext());
    }

    @Override
    public Item createItem(Map<String, ?> properties) {
        return this.createItem(properties, null, null, null, getSession().getDefaultContext());
    }
}
