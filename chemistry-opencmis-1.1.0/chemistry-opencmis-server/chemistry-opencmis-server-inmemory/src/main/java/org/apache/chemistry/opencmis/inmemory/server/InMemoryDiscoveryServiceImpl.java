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
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PolicyIdList;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChangeEventInfoDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyIdListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.ObjectInfoHandler;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryDiscoveryServiceImpl extends InMemoryAbstractServiceImpl {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryDiscoveryServiceImpl.class);

    public InMemoryDiscoveryServiceImpl(StoreManager storeManager) {
        super(storeManager);
    }

    public ObjectList getContentChanges(CallContext context, String repositoryId, Holder<String> changeLogToken,
            Boolean includeProperties, String filter, Boolean includePolicyIds, Boolean includeAcl,
            BigInteger maxItems, ExtensionsData extension, ObjectInfoHandler objectInfos) {
        // dummy implementation using hard coded values
        final int ITEMS_AVAILABLE = 25;

        int token = 0;
        if (changeLogToken != null && changeLogToken.getValue() != null) {
            if (!changeLogToken.getValue().startsWith("token-")) {
                throw new CmisInvalidArgumentException("Unknown change log token!");
            }

            try {
                token = Integer.parseInt(changeLogToken.getValue().substring(6));
            } catch (NumberFormatException nfe) {
                throw new CmisInvalidArgumentException("Unknown change log token!", nfe);
            }

            if (token < 0 || token > ITEMS_AVAILABLE) {
                throw new CmisInvalidArgumentException("Unknown change log token!");
            }
        }

        ObjectListImpl objList = new ObjectListImpl();
        long timestamp = System.currentTimeMillis() - 60 * 1000;
        // convert ObjectInFolderContainerList to objectList
        List<ObjectData> lod = new ArrayList<ObjectData>();
        if (null == maxItems) {
            maxItems = BigInteger.valueOf(ITEMS_AVAILABLE);
        }
        int last = Math.min(ITEMS_AVAILABLE, token + maxItems.intValue());

        for (int i = token; i < last; i++) {
            // add a dummy delete event
            ObjectDataImpl odImpl = new ObjectDataImpl();
            PropertiesImpl props = new PropertiesImpl();
            props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, "cl-" + i));
            props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, BaseTypeId.CMIS_DOCUMENT.value()));
            props.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, BaseTypeId.CMIS_DOCUMENT.value()));
            odImpl.setProperties(props);
            ChangeEventInfoDataImpl changeEventInfo = new ChangeEventInfoDataImpl();
            changeEventInfo.setChangeType(ChangeType.DELETED);
            GregorianCalendar eventTimestamp = new GregorianCalendar();
            eventTimestamp.setTimeInMillis(timestamp + i * 1000);
            changeEventInfo.setChangeTime(eventTimestamp);
            odImpl.setChangeEventInfo(changeEventInfo);
            if (includePolicyIds != null && includePolicyIds) {
                PolicyIdList policies = new PolicyIdListImpl();
                odImpl.setPolicyIds(policies);
            }
            lod.add(odImpl);
        }

        objList.setObjects(lod);
        objList.setNumItems(BigInteger.valueOf(ITEMS_AVAILABLE - token));
        objList.setHasMoreItems(false);

        String changeToken = "token-" + (token + lod.size() - 1);
        if(changeLogToken != null) {
            changeLogToken.setValue(changeToken);
        }
        
        // To be able to provide all Atom links in the response we need
        // additional information:
        if (objectInfos != null) {
            fAtomLinkProvider.fillInformationForAtomLinks(context, repositoryId, null, objectInfos, objList);
        }
        return objList;
    }

    private void convertList(List<ObjectData> lod, ObjectInFolderContainer obj) {
        lod.add(obj.getObject().getObject());
        // add dummy event info
        ObjectData oif = obj.getObject().getObject();
        ObjectDataImpl oifImpl = (ObjectDataImpl) oif;
        ChangeEventInfoDataImpl changeEventInfo = new ChangeEventInfoDataImpl();
        changeEventInfo.setChangeType(ChangeType.UPDATED);
        changeEventInfo.setChangeTime(new GregorianCalendar());
        oifImpl.setChangeEventInfo(changeEventInfo);
        if (null != obj.getChildren()) {
            for (ObjectInFolderContainer oifc : obj.getChildren()) {
                convertList(lod, oifc);
            }
        }
    }

    public ObjectList query(CallContext context, String repositoryId, String statement, Boolean searchAllVersions,
            Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

        LOG.debug("start query()");
        validator.query(context, repositoryId, extension);

        String user = context.getUsername();
        ObjectList res;

        res = fStoreManager.query(context, user, repositoryId, statement, searchAllVersions, includeAllowableActions,
                includeRelationships, renditionFilter, maxItems, skipCount);
        LOG.debug("stop query()");
        return res;
    }

}
