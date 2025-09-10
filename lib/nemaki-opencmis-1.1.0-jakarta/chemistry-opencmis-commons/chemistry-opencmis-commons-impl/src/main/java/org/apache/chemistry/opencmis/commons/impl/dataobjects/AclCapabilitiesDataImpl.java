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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.AclCapabilities;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.SupportedPermissions;

/**
 * AclCapabilities Implementation.
 */
public class AclCapabilitiesDataImpl extends AbstractExtensionData implements AclCapabilities {

    private static final long serialVersionUID = 1L;

    private SupportedPermissions supportedPermissions;
    private AclPropagation aclPropagation;
    private Map<String, PermissionMapping> permissionMapping;
    private List<PermissionDefinition> permissionDefinitionList;

    @Override
    public SupportedPermissions getSupportedPermissions() {
        return supportedPermissions;
    }

    public void setSupportedPermissions(SupportedPermissions supportedPermissions) {
        this.supportedPermissions = supportedPermissions;
    }

    @Override
    public AclPropagation getAclPropagation() {
        return aclPropagation;
    }

    public void setAclPropagation(AclPropagation aclPropagation) {
        this.aclPropagation = aclPropagation;
    }

    @Override
    public Map<String, PermissionMapping> getPermissionMapping() {
        if (permissionMapping == null) {
            permissionMapping = new HashMap<String, PermissionMapping>(2);
        }

        return permissionMapping;
    }

    public void setPermissionMappingData(Map<String, PermissionMapping> permissionMapping) {
        this.permissionMapping = permissionMapping;
    }

    @Override
    public List<PermissionDefinition> getPermissions() {
        if (permissionDefinitionList == null) {
            permissionDefinitionList = new ArrayList<PermissionDefinition>(0);
        }

        return permissionDefinitionList;
    }

    public void setPermissionDefinitionData(List<PermissionDefinition> permissionDefinitionList) {
        this.permissionDefinitionList = permissionDefinitionList;
    }

    @Override
    public String toString() {
        return "ACL Capabilities [ACL propagation=" + aclPropagation + ", permission definition list="
                + permissionDefinitionList + ", permission mappings=" + permissionMapping + "]" + super.toString();
    }
}
