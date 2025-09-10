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

import java.io.Serializable;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChangeEventInfoDataImpl;

/**
 * Change Event.
 */
public class ChangeEventImpl extends ChangeEventInfoDataImpl implements ChangeEvent, Serializable {

    private static final long serialVersionUID = 1L;

    private String objectId;
    private Map<String, List<?>> properties;
    private List<String> policyIds;
    private Acl acl;

    public ChangeEventImpl() {
    }

    public ChangeEventImpl(ChangeType changeType, GregorianCalendar changeTime, String objectId,
            Map<String, List<?>> properties, List<String> policyIds, Acl acl) {
        super(changeType, changeTime);
        this.objectId = objectId;
        this.properties = properties;
        this.policyIds = policyIds;
        this.acl = acl;
    }

    @Override
    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    @Override
    public Map<String, List<?>> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, List<?>> properties) {
        this.properties = properties;
    }

    @Override
    public List<String> getPolicyIds() {
        return policyIds;
    }

    public void setPolicyIds(List<String> policyIds) {
        this.policyIds = policyIds;
    }

    @Override
    public Acl getAcl() {
        return acl;
    }

    public void setAcl(Acl acl) {
        this.acl = acl;
    }

    @Override
    public String toString() {
        return "Change Event [change type=" + getChangeType() + ", change time=" + getChangeTime() + ", object id="
                + objectId + ", properties=" + properties + ", policy ids=" + policyIds + ", ACL=" + acl + "]"
                + super.toString();
    }
}
