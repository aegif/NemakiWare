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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.types.PropertyCreationHelper;
import org.apache.chemistry.opencmis.server.support.TypeManager;

public class InMemoryRelationshipServiceImpl extends InMemoryAbstractServiceImpl {

    private InMemoryRepositoryServiceImpl repSvc;

    protected InMemoryRelationshipServiceImpl(StoreManager storeManager, InMemoryRepositoryServiceImpl repSvc) {
        super(storeManager);
        this.repSvc = repSvc;
    }

    public ObjectList getObjectRelationships(CallContext context, String repositoryId, String objectId,
            Boolean includeSubRelationshipTypes, RelationshipDirection relationshipDirection, String typeId,
            String filter, Boolean includeAllowableActions, BigInteger maxItems, BigInteger skipCount,
            ExtensionsData extension, ObjectInfoHandler objectInfos) {

        int skip = null == skipCount ? 0 : skipCount.intValue();
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        StoredObject so = validator.getObjectRelationships(context, repositoryId, objectId, relationshipDirection,
                typeId, extension);

        if (so == null) {
            throw new CmisObjectNotFoundException("Unknown object id: " + objectId);
        }

        String user = context.getUsername();
        TypeManager tm = fStoreManager.getTypeManager(repositoryId);
        List<String> typeIds = null;
        if (null != typeId) {
            typeIds = new ArrayList<String>();
            typeIds.add(typeId);
            if (includeSubRelationshipTypes) {
                List<TypeDefinitionContainer> typeDescs = repSvc.getTypeDescendants(context, repositoryId, typeId,
                        null, true, null);
                if (null != typeDescs) {
                    for (TypeDefinitionContainer t : typeDescs) {
                        typeIds.add(t.getTypeDefinition().getId());
                    }
                }
            }
        }
        List<StoredObject> rels = objStore.getRelationships(objectId, typeIds, relationshipDirection);
        ObjectListImpl result = new ObjectListImpl();
        List<ObjectData> odList = new ArrayList<ObjectData>();

        for (StoredObject rel : rels) {
            ObjectData od = PropertyCreationHelper.getObjectData(context, tm, objStore, rel, filter, user,
                    includeAllowableActions, IncludeRelationships.NONE, null, false, false, extension);
            odList.add(od);
        }
        if (context.isObjectInfoRequired()) {
            ObjectInfoImpl objectInfo = new ObjectInfoImpl();
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, so, objectInfo);
            objectInfos.addObjectInfo(objectInfo);
        }

        result.setObjects(odList);
        result.setNumItems(BigInteger.valueOf(rels.size()));
        result.setHasMoreItems(rels.size() > skip + rels.size());

        return result;
    }

}
