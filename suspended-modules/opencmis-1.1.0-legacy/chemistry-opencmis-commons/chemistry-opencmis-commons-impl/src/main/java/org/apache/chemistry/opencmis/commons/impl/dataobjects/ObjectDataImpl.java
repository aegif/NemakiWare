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
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ChangeEventInfo;
import org.apache.chemistry.opencmis.commons.data.MutableObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PolicyIdList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

/**
 * ObjectData implementation.
 */
public class ObjectDataImpl extends AbstractExtensionData implements MutableObjectData {

    private static final long serialVersionUID = 1L;

    private Properties properties;
    private ChangeEventInfo changeEventInfo;
    private List<ObjectData> relationships;
    private List<RenditionData> renditions;
    private PolicyIdList policyIds;
    private AllowableActions allowableActions;
    private Acl acl;
    private Boolean isExactAcl;

    @Override
    public String getId() {
        Object value = getFirstValue(PropertyIds.OBJECT_ID);
        if (value instanceof String) {
            return (String) value;
        }

        return null;
    }

    @Override
    public BaseTypeId getBaseTypeId() {
        Object value = getFirstValue(PropertyIds.BASE_TYPE_ID);
        if (value instanceof String) {
            try {
                return BaseTypeId.fromValue((String) value);
            } catch (Exception e) {
                // invalid base type -> return null
            }
        }

        return null;
    }

    @Override
    public Properties getProperties() {
        return properties;
    }

    @Override
    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    @Override
    public ChangeEventInfo getChangeEventInfo() {
        return changeEventInfo;
    }

    @Override
    public void setChangeEventInfo(ChangeEventInfo changeEventInfo) {
        this.changeEventInfo = changeEventInfo;
    }

    @Override
    public List<ObjectData> getRelationships() {
        if (relationships == null) {
            relationships = new ArrayList<ObjectData>(0);
        }

        return relationships;
    }

    @Override
    public void setRelationships(List<ObjectData> relationships) {
        this.relationships = relationships;
    }

    @Override
    public List<RenditionData> getRenditions() {
        if (renditions == null) {
            renditions = new ArrayList<RenditionData>(0);
        }

        return renditions;
    }

    @Override
    public void setRenditions(List<RenditionData> renditions) {
        this.renditions = renditions;
    }

    @Override
    public PolicyIdList getPolicyIds() {
        return policyIds;
    }

    @Override
    public void setPolicyIds(PolicyIdList policyIds) {
        this.policyIds = policyIds;
    }

    @Override
    public AllowableActions getAllowableActions() {
        return allowableActions;
    }

    @Override
    public void setAllowableActions(AllowableActions allowableActions) {
        this.allowableActions = allowableActions;
    }

    @Override
    public Acl getAcl() {
        return acl;
    }

    @Override
    public void setAcl(Acl acl) {
        this.acl = acl;
    }

    @Override
    public Boolean isExactAcl() {
        return isExactAcl;
    }

    @Override
    public void setIsExactAcl(Boolean isExactACL) {
        this.isExactAcl = isExactACL;
    }

    // ---- internal ----

    /**
     * Returns the first value of a property or <code>null</code> if the
     * property is not set.
     */
    private Object getFirstValue(String id) {
        if (properties == null || properties.getProperties() == null) {
            return null;
        }

        PropertyData<?> property = properties.getProperties().get(id);
        if (property == null) {
            return null;
        }

        return property.getFirstValue();
    }

    @Override
    public String toString() {
        return "Object Data [properties=" + properties + ", allowable actions=" + allowableActions
                + ", change event info=" + changeEventInfo + ", ACL=" + acl + ", is exact ACL=" + isExactAcl
                + ", policy ids=" + policyIds + ", relationships=" + relationships + ", renditions=" + renditions + "]"
                + super.toString();
    }
}
