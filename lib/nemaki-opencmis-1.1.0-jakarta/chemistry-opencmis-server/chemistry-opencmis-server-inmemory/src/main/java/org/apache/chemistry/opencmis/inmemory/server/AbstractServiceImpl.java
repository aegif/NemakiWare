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

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;

/**
 * Common functionality for all service implementations.
 */
public class AbstractServiceImpl {

    protected final StoreManager fStoreManager;

    protected AbstractServiceImpl(StoreManager storeManager) {
        fStoreManager = storeManager;
    }

    /**
     * check if repository is known and that object exists. To avoid later calls
     * to again retrieve the object from the id return the retrieved object for
     * later use.
     * 
     * @param repositoryId
     *            repository id
     * @param objectId
     *            object id
     * @return object for objectId
     */
    protected StoredObject checkStandardParameters(String repositoryId, String objectId) {

        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        if (objStore == null) {
            throw new CmisObjectNotFoundException("Unknown repository id: " + repositoryId);
        }

        StoredObject so = objStore.getObjectById(objectId);

        if (so == null) {
            throw new CmisObjectNotFoundException("Unknown object id: " + objectId);
        }

        return so;
    }

    protected StoredObject checkExistingObjectId(ObjectStore objStore, String objectId) {
        StoredObject so = objStore.getObjectById(objectId);

        if (so == null) {
            throw new CmisObjectNotFoundException("Unknown object id: " + objectId);
        }

        return so;
    }

    protected void checkRepositoryId(String repositoryId) {
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        if (objStore == null) {
            throw new CmisObjectNotFoundException("Unknown repository id: " + repositoryId);
        }
    }

    protected TypeDefinition getTypeDefinition(String repositoryId, Properties properties, boolean cmis11) {
        String typeId = (String) properties.getProperties().get(PropertyIds.OBJECT_TYPE_ID).getFirstValue();
        TypeDefinitionContainer typeDefC = fStoreManager.getTypeById(repositoryId, typeId, cmis11);
        if (typeDefC == null) {
            throw new CmisInvalidArgumentException("Cannot create object, a type with id " + typeId + " is unknown");
        }

        return typeDefC.getTypeDefinition();
    }

    protected TypeDefinition getTypeDefinition(String repositoryId, StoredObject obj, boolean cmis11) {

        TypeDefinitionContainer typeDefC = fStoreManager.getTypeById(repositoryId, obj.getTypeId(), cmis11);
        return typeDefC.getTypeDefinition();
    }

    /**
     * We allow checkin, cancel, checkout operations on a single version as well
     * as on a version series This method returns the versioned document
     * (version series) in each case.
     * 
     * @param so
     *            version or versioned document
     * @return versioned document
     */
    protected VersionedDocument getVersionedDocumentOfObjectId(StoredObject so) {
        VersionedDocument verDoc;
        if (so instanceof DocumentVersion) {
            // get document the version is contained in to c
            verDoc = ((DocumentVersion) so).getParentDocument();
        } else {
            verDoc = (VersionedDocument) so;
        }

        return verDoc;
    }

    protected VersionedDocument testIsNotCheckedOutBySomeoneElse(StoredObject so, String user) {
        checkIsVersionableObject(so);
        VersionedDocument verDoc = getVersionedDocumentOfObjectId(so);
        if (verDoc.isCheckedOut()) {
            testCheckedOutByCurrentUser(user, verDoc);
        }

        return verDoc;
    }

    protected VersionedDocument testHasProperCheckedOutStatus(StoredObject so, String user) {
        checkIsVersionableObject(so);
        VersionedDocument verDoc = getVersionedDocumentOfObjectId(so);

        checkHasUser(user);

        testIsCheckedOut(verDoc);
        testCheckedOutByCurrentUser(user, verDoc);

        return verDoc;
    }

    protected void checkIsVersionableObject(StoredObject so) {
        if (!(so instanceof VersionedDocument || so instanceof DocumentVersion)) {
            throw new CmisInvalidArgumentException("Object " + so.getId() + " must of a versionable type.");
        }
    }

    protected void checkHasUser(String user) {
        if (null == user || user.length() == 0) {
            throw new CmisUpdateConflictException("Object can't be checked-in, no user is given.");
        }
    }

    protected void testCheckedOutByCurrentUser(String user, VersionedDocument verDoc) {
        if (!user.equals(verDoc.getCheckedOutBy())) {
            throw new CmisUpdateConflictException("Object can't be checked-in, user " + verDoc.getCheckedOutBy()
                    + " has checked out the document.");
        }
    }

    protected void testIsCheckedOut(VersionedDocument verDoc) {
        if (!verDoc.isCheckedOut()) {
            throw new CmisUpdateConflictException("Canot check-in: Document " + verDoc.getId()
                    + " is not checked out.");
        }
    }

}
