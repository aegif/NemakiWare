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

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Base class for all filable persistent session object impl classes.
 */
public abstract class AbstractFilableCmisObject extends AbstractCmisObject implements FileableCmisObject {

    private static final long serialVersionUID = 1L;

    @Override
    public List<Folder> getParents() {
        return getParents(getSession().getDefaultContext());
    }

    @Override
    public List<Folder> getParents(OperationContext context) {
        String objectId = getObjectId();

        // get object ids of the parent folders
        List<ObjectParentData> bindingParents = getBinding().getNavigationService().getObjectParents(getRepositoryId(),
                objectId, getPropertyQueryName(PropertyIds.OBJECT_ID), false, IncludeRelationships.NONE, null, false,
                null);

        List<Folder> parents = new ArrayList<Folder>();

        for (ObjectParentData p : bindingParents) {
            if (p == null || p.getObject() == null || p.getObject().getProperties() == null) {
                // should not happen...
                throw new CmisRuntimeException("Repository sent invalid data!");
            }

            // get id property
            PropertyData<?> idProperty = p.getObject().getProperties().getProperties().get(PropertyIds.OBJECT_ID);
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

            parents.add((Folder) parentFolder);
        }

        return parents;
    }

    @Override
    public List<String> getPaths() {
        String objectId = getObjectId();

        // determine filter
        String filter;

        ObjectType folderType = getSession().getTypeDefinition(BaseTypeId.CMIS_FOLDER.value());
        PropertyDefinition<?> idPropDef = folderType.getPropertyDefinitions().get(PropertyIds.OBJECT_ID);
        PropertyDefinition<?> pathPropDef = folderType.getPropertyDefinitions().get(PropertyIds.PATH);
        if (idPropDef != null && pathPropDef != null) {
            filter = idPropDef.getQueryName() + "," + pathPropDef.getQueryName();
        } else {
            filter = "*";
        }

        // get object paths of the parent folders
        List<ObjectParentData> bindingParents = getBinding().getNavigationService().getObjectParents(getRepositoryId(),
                objectId, filter, false, IncludeRelationships.NONE, null, true, null);

        List<String> paths = new ArrayList<String>();

        for (ObjectParentData p : bindingParents) {
            if (p == null || p.getObject() == null || p.getObject().getProperties() == null) {
                // should not happen...
                throw new CmisRuntimeException("Repository sent invalid data!");
            }

            // get path property
            PropertyData<?> pathProperty = p.getObject().getProperties().getProperties().get(PropertyIds.PATH);
            if (!(pathProperty instanceof PropertyString)) {
                // the repository sent a folder without a valid path...
                throw new CmisRuntimeException("Repository sent invalid data! No path property!");
            }

            if (p.getRelativePathSegment() == null) {
                // the repository didn't send a relative path segment
                throw new CmisRuntimeException("Repository sent invalid data! No relative path segement!");
            }

            String folderPath = ((String) pathProperty.getFirstValue());
            if (folderPath == null) {
                // the repository sent a folder without a valid path...
                throw new CmisRuntimeException("Repository sent invalid data! No path property value!");
            }
            paths.add(folderPath + (folderPath.endsWith("/") ? "" : "/") + p.getRelativePathSegment());
        }

        return paths;
    }

    @Override
    public FileableCmisObject move(ObjectId sourceFolderId, ObjectId targetFolderId) {
        return move(sourceFolderId, targetFolderId, getSession().getDefaultContext());
    }

    @Override
    public FileableCmisObject move(ObjectId sourceFolderId, ObjectId targetFolderId, OperationContext context) {
        String objectId = getObjectId();
        Holder<String> objectIdHolder = new Holder<String>(objectId);

        if (sourceFolderId == null || sourceFolderId.getId() == null) {
            throw new IllegalArgumentException("Source folder id must be set!");
        }

        if (targetFolderId == null || targetFolderId.getId() == null) {
            throw new IllegalArgumentException("Target folder id must be set!");
        }

        getBinding().getObjectService().moveObject(getRepositoryId(), objectIdHolder, targetFolderId.getId(),
                sourceFolderId.getId(), null);

        // invalidate path cache
        getSession().removeObjectFromCache(objectId);

        if (objectIdHolder.getValue() == null) {
            return null;
        }

        CmisObject movedObject = getSession().getObject(objectIdHolder.getValue(), context);
        if (!(movedObject instanceof FileableCmisObject)) {
            throw new CmisRuntimeException("Moved object is invalid!");
        }

        return (FileableCmisObject) movedObject;
    }

    @Override
    public void addToFolder(ObjectId folderId, boolean allVersions) {
        String objectId = getObjectId();

        if (folderId == null || folderId.getId() == null) {
            throw new IllegalArgumentException("Folder Id must be set!");
        }

        getBinding().getMultiFilingService().addObjectToFolder(getRepositoryId(), objectId, folderId.getId(),
                allVersions, null);

        // invalidate path cache
        getSession().removeObjectFromCache(objectId);
    }

    @Override
    public void removeFromFolder(ObjectId folderId) {
        String objectId = getObjectId();

        getBinding().getMultiFilingService().removeObjectFromFolder(getRepositoryId(), objectId,
                (folderId != null ? folderId.getId() : null), null);

        // invalidate path cache
        getSession().removeObjectFromCache(objectId);
    }
}
