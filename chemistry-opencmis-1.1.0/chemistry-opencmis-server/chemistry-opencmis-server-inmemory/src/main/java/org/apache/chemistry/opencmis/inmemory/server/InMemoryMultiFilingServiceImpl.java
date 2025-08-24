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
package org.apache.chemistry.opencmis.inmemory.server;

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Folder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryMultiFilingServiceImpl extends InMemoryAbstractServiceImpl {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMultiFilingServiceImpl.class.getName());

    public InMemoryMultiFilingServiceImpl(StoreManager storeMgr) {
        super(storeMgr);
    }

    public void addObjectToFolder(CallContext context, String repositoryId, String objectId, String folderId,
            Boolean allVersions, ExtensionsData extension, ObjectInfoHandler objectInfos) {

        LOG.debug("Begin addObjectToFolder()");

        if (allVersions != null && !allVersions.booleanValue()) {
            throw new CmisNotSupportedException(
                    "Cannot add object to folder, version specific filing is not supported.");
        }
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        StoredObject[] sos = validator.addObjectToFolder(context, repositoryId, objectId, folderId, allVersions,
                extension);

        StoredObject so = sos[0];
        StoredObject folder = sos[1];
        checkObjects(repositoryId, so, folder);

        Folder newParent = (Folder) folder;
        objStore.addParent(so, newParent);
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, folder, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        LOG.debug("End addObjectToFolder()");
    }

    public void removeObjectFromFolder(CallContext context, String repositoryId, String objectId, String folderId,
            ExtensionsData extension, ObjectInfoHandler objectInfos) {

        LOG.debug("Begin removeObjectFromFolder()");

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);
        StoredObject[] sos = validator.removeObjectFromFolder(context, repositoryId, objectId, folderId, extension);
        StoredObject so = sos[0];
        if (null != folderId) {
            StoredObject folder = sos[1];
            checkObjects(repositoryId, so, folder);
            Folder parent = (Folder) folder;

            objStore.removeParent(so, parent);

            // To be able to provide all Atom links in the response we need
            // additional information:
            if (context.isObjectInfoRequired()) {
                ObjectInfoImpl objectInfo = new ObjectInfoImpl();
                fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
                fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, folder, objectInfo);
                objectInfos.addObjectInfo(objectInfo);
            }
        } else {
            checkObjects(repositoryId, so, null);
            List<String> parentIds = objStore.getParentIds(so, context.getUsername());
            for (String parentId : parentIds) {
                Folder parent = (Folder) objStore.getObjectById(parentId);
                objStore.removeParent(so, parent);
            }

            // To be able to provide all Atom links in the response we need
            // additional information:
            if (context.isObjectInfoRequired()) {
                ObjectInfoImpl objectInfo = new ObjectInfoImpl();
                fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
                objectInfos.addObjectInfo(objectInfo);
            }
        }

        LOG.debug("End removeObjectFromFolder()");
    }

    private void checkObjects(String repositoryId, StoredObject so, StoredObject folder) {
        if ((so instanceof Folder)) {
            throw new CmisConstraintException("Cannot add object to folder, object id " + folder.getId()
                    + " is a folder and folders are not multi-filed.");
        }

        if (folder != null && !(folder instanceof Folder)) {
            throw new CmisConstraintException("Cannot add object to folder, folder id " + folder.getId()
                    + " does not refer to a folder.");
        }
    }

}
