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

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.CmisServiceValidator;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.DocumentVersion;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;

/**
 * Common functionality for all service implementations
 * 
 */
public class InMemoryAbstractServiceImpl {

    protected final StoreManager fStoreManager;
    protected final CmisServiceValidator validator;
    protected final AtomLinkInfoProvider fAtomLinkProvider;


    protected InMemoryAbstractServiceImpl(StoreManager storeManager, CmisServiceValidator validator) {
        this.fStoreManager = storeManager;
        this.validator = validator;
        this.fAtomLinkProvider = new AtomLinkInfoProvider(fStoreManager);
    }

    protected InMemoryAbstractServiceImpl(StoreManager storeManager) {
        this.fStoreManager = storeManager;
        this.validator = storeManager.getServiceValidator();
        this.fAtomLinkProvider = new AtomLinkInfoProvider(fStoreManager);
    }

    protected TypeDefinition getTypeDefinition(String repositoryId, Properties properties, boolean cmis11) {
        if (null == properties) {
            return null;
        }

        String typeId = (String) properties.getProperties().get(PropertyIds.OBJECT_TYPE_ID).getFirstValue();
        
        TypeDefinitionContainer typeDefC = fStoreManager.getTypeById(repositoryId, typeId, cmis11);
        if (typeDefC == null) {
            throw new CmisInvalidArgumentException("Cannot create object, a type with id " + typeId + " is unknown");
        }

        return typeDefC.getTypeDefinition();
    }

    protected List<TypeDefinition> getTypeDefinition(String repositoryId, List<String> typeIds, boolean cmis11) {
        if (null == typeIds || typeIds.isEmpty()) {
            return null;
        }

        List<TypeDefinition> result = new ArrayList<TypeDefinition>(typeIds.size());
        for (String typeId : typeIds) {
            TypeDefinitionContainer typeDefC = fStoreManager.getTypeById(repositoryId, typeId, cmis11);
            if (typeDefC == null) {
                throw new CmisInvalidArgumentException("Cannot create object, a type with id " + typeId
                        + " is unknown");
            }
            result.add(typeDefC.getTypeDefinition());
        }

        return result;
    }

    protected TypeDefinition getTypeDefinition(String repositoryId, StoredObject obj, boolean cmis11) {

        TypeDefinitionContainer typeDefC = fStoreManager.getTypeById(repositoryId, obj.getTypeId(), cmis11);
        return typeDefC == null ? null : typeDefC.getTypeDefinition();
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
            throw new CmisInvalidArgumentException(
                    "Object is of a versionable type but not instance of VersionedDocument or DocumentVersion.");
        }
    }

    protected void checkHasUser(String user) {
        if (null == user || user.length() == 0) {
            throw new CmisPermissionDeniedException("Object can't be checked-in, no user is given.");
        }
    }

    protected void testCheckedOutByCurrentUser(String user, VersionedDocument verDoc) {
        if (!user.equals(verDoc.getCheckedOutBy())) {
            throw new CmisUpdateConflictException("User " + verDoc.getCheckedOutBy()
                    + " has checked out the document.");
        }
    }

    protected void testIsCheckedOut(VersionedDocument verDoc) {
        if (!verDoc.isCheckedOut()) {
            throw new CmisUpdateConflictException("Document " + verDoc.getId() + " is not checked out.");
        }
    }

    protected boolean isCheckedOut(StoredObject so, String user) {
        if (so instanceof VersionedDocument || so instanceof DocumentVersion) {
            VersionedDocument verDoc = getVersionedDocumentOfObjectId(so);
            return verDoc.isCheckedOut() && user.equals(verDoc.getCheckedOutBy());
        } else {
            return false;
        }

    }

}
