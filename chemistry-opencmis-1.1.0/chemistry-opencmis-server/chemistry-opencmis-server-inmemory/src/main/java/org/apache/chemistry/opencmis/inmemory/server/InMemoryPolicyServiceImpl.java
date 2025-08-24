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

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.types.PropertyCreationHelper;
import org.apache.chemistry.opencmis.server.support.TypeManager;

public class InMemoryPolicyServiceImpl extends InMemoryAbstractServiceImpl {

    protected InMemoryPolicyServiceImpl(StoreManager storeManager) {
        super(storeManager);
    }

    public List<ObjectData> getAppliedPolicies(CallContext context, String repositoryId, String objectId,
            String filter, ExtensionsData extension) {

        StoredObject so = validator.getAppliedPolicies(context, repositoryId, objectId, extension);
        ObjectStore objStore = fStoreManager.getObjectStore(repositoryId);

        if (so == null) {
            throw new CmisObjectNotFoundException("Unknown object id: " + objectId);
        }

        String user = context.getUsername();
        TypeManager tm = fStoreManager.getTypeManager(repositoryId);

        List<String> polIds = so.getAppliedPolicies();
        List<ObjectData> res = new ArrayList<ObjectData>();
        if (null != polIds && polIds.size() > 0) {
            ObjectStore os = super.fStoreManager.getObjectStore(repositoryId);
            for (String polId : polIds) {
                StoredObject pol = os.getObjectById(polId);
                ObjectData od = PropertyCreationHelper.getObjectData(context, tm, objStore, pol, filter, user, false,
                        IncludeRelationships.NONE, null, false, false, null);
                res.add(od);
            }
        }
        return res;
    }

    public void removePolicy(CallContext context, String repositoryId, String policyId, String objectId,
            ExtensionsData extension) {

        StoredObject so = validator.getAppliedPolicies(context, repositoryId, objectId, extension);

        List<String> polIds = so.getAppliedPolicies();
        if (null == polIds || !(polIds.contains(policyId))) {
            throw new CmisInvalidArgumentException("Policy id " + policyId
                    + "cannot be removed, because it is not applied to object " + objectId);
        }
        so.removePolicy(policyId);
    }

    public void applyPolicy(CallContext context, String repositoryId, String policyId, String objectId,
            ExtensionsData extension) {
        StoredObject[] sos = validator.applyPolicy(context, repositoryId, policyId, objectId, extension);
        StoredObject so = sos[1];

        List<String> polIds = so.getAppliedPolicies();
        if (null != polIds && polIds.contains(policyId)) {
            throw new CmisInvalidArgumentException("Policy id " + policyId
                    + "cannot be added, because it is already applied to object " + objectId);
        }
        so.addAppliedPolicy(policyId);
    }

}
