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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.MutableAce;
import org.apache.chemistry.opencmis.commons.data.Principal;

/**
 * Access Control Entry data implementation.
 */
public class AccessControlEntryImpl extends AbstractExtensionData implements MutableAce, Serializable {

    private static final long serialVersionUID = 1L;

    private List<String> permissions;
    private Principal principal;
    private boolean isDirect = true;

    /**
     * Constructor.
     */
    public AccessControlEntryImpl() {
    }

    /**
     * Constructor.
     */
    public AccessControlEntryImpl(Principal principal, List<String> permissions) {
        this.principal = principal;
        this.permissions = permissions;
    }

    @Override
    public Principal getPrincipal() {
        return principal;
    }

    @Override
    public String getPrincipalId() {
        return principal == null ? null : principal.getId();
    }

    @Override
    public void setPrincipal(Principal principal) {
        this.principal = principal;
    }

    @Override
    public List<String> getPermissions() {
        if (permissions == null) {
            permissions = new ArrayList<String>(0);
        }

        return permissions;
    }

    @Override
    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    @Override
    public boolean isDirect() {
        return isDirect;
    }

    @Override
    public void setDirect(boolean direct) {
        this.isDirect = direct;
    }

    @Override
    public String toString() {
        return "Access Control Entry [principal=" + principal + ", permissions=" + permissions + ", is direct="
                + isDirect + "]" + super.toString();
    }
}
