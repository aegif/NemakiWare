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
package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

import java.util.Collections;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;

public class InMemoryAce {

    private static final String ANONYMOUS = "anonymous";
    private static final String ANYONE = "anyone";
    private final String principalId;
    private Permission permission;
    private static final InMemoryAce DEFAULT_ACE = new InMemoryAce(InMemoryAce.getAnyoneUser(), Permission.ALL);

    public static final String getAnyoneUser() {
        return ANYONE;
    }

    public static final String getAnonymousUser() {
        return ANONYMOUS;
    }

    public static final InMemoryAce getDefaultAce() {
        return DEFAULT_ACE;
    }

    public InMemoryAce(Ace commonsAce) {
        if (null == commonsAce || null == commonsAce.getPrincipalId() || null == commonsAce.getPermissions()) {
            throw new IllegalArgumentException("Cannot create InMemoryAce with null value");
        }
        List<String> perms = commonsAce.getPermissions();
        if (perms.size() != 1) {
            throw new IllegalArgumentException("InMemory only supports ACEs with a single permission.");
        }
        String perm = perms.get(0);
        this.principalId = commonsAce.getPrincipalId();
        this.permission = Permission.fromCmisString(perm);
    }

    public InMemoryAce(String prinicpalId, Permission permission) {
        if (null == prinicpalId || null == permission) {
            throw new IllegalArgumentException("Cannot create InMemoryAce with null value");
        }

        this.principalId = prinicpalId;
        this.permission = permission;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public Permission getPermission() {
        return permission;
    }

    public void setPermission(Permission newPermission) {
        permission = newPermission;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((permission == null) ? 0 : permission.hashCode());
        result = prime * result + ((principalId == null) ? 0 : principalId.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        InMemoryAce other = (InMemoryAce) obj;
        if (permission != other.permission) {
            return false;
        }
        if (principalId == null) {
            if (other.principalId != null) {
                return false;
            }
        } else if (!principalId.equals(other.principalId)) {
            return false;
        }
        return true;
    }

    public boolean hasPermission(Permission permission2) {
        return this.permission.compareTo(permission2) >= 0;
    }

    @Override
    public String toString() {
        return "InMemoryAce [principalId=" + principalId + ", permission=" + permission + "]";
    }

    public Ace toCommonsAce() {
        return new AccessControlEntryImpl(new AccessControlPrincipalDataImpl(principalId),
                Collections.singletonList(permission.toCmisString()));
    }

}
