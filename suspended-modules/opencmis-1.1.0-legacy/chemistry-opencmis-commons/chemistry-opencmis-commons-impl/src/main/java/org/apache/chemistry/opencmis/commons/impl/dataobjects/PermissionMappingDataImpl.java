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

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;

/**
 * Permission mapping.
 */
public class PermissionMappingDataImpl extends AbstractExtensionData implements PermissionMapping {

    private static final long serialVersionUID = 1L;

    private String key;
    private List<String> permissions;

    @Override
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    @Override
    public List<String> getPermissions() {
        if (permissions == null) {
            permissions = new ArrayList<String>(0);
        }

        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    @Override
    public String toString() {
        return "Permission Mapping [key=" + key + ", permissions=" + permissions + "]" + super.toString();
    }

}
