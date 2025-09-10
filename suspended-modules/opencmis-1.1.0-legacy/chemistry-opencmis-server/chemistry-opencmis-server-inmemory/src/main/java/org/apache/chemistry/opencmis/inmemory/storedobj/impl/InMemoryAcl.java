/*
x * Licensed to the Apache Software Foundation (ASF) under one
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;

public class InMemoryAcl implements Cloneable {

    private List<InMemoryAce> acl;
    private int id;

    @SuppressWarnings("serial")
    private static class AceComparator<T extends InMemoryAce> implements Comparator<T> {

        @Override
        public int compare(T o1, T o2) {
            if (null == o1 || null == o2) {
                if (o1 == o2) { // NOSONAR
                    return 0;
                } else if (o1 == null) {
                    return 1;
                } else {
                    return -1;
                }
            }
            int res = o1.getPrincipalId().compareTo(o2.getPrincipalId());
            return res;
        }
    }

    private static final Comparator<? super InMemoryAce> COMP = new AceComparator<InMemoryAce>();
    private static final InMemoryAcl DEFAULT_ACL = new InMemoryAcl(new ArrayList<InMemoryAce>() {
        {
            add(InMemoryAce.getDefaultAce());
        }
    });

    public static InMemoryAcl createFromCommonsAcl(Acl commonsAcl) {
        InMemoryAcl acl = new InMemoryAcl();
        for (Ace cace : commonsAcl.getAces()) {
            if (acl.hasPrincipal(cace.getPrincipalId())) {
                Permission perm = acl.getPermission(cace.getPrincipalId());
                Permission newPerm = Permission.fromCmisString(cace.getPermissions().get(0));
                if (perm.ordinal() > newPerm.ordinal()) {
                    acl.setPermission(cace.getPrincipalId(), newPerm);
                }
            } else {
                acl.addAce(new InMemoryAce(cace));
            }

        }
        return acl;
    }

    public static InMemoryAcl getDefaultAcl() {
        return DEFAULT_ACL;
    }

    public InMemoryAcl() {
        acl = new ArrayList<InMemoryAce>(3);
    }

    public InMemoryAcl(final List<InMemoryAce> arg) {
        this.acl = new ArrayList<InMemoryAce>(arg);
        Collections.sort(this.acl, COMP);
        for (int i = 0; i < acl.size(); i++) {
            InMemoryAce ace = acl.get(i);
            if (ace == null) {
                throw new IllegalArgumentException("Cannot create ACLs with a null principal id or permission.");
            }
        }
        for (int i = 0; i < acl.size() - 1; i++) {
            if (acl.get(i).equals(acl.get(i + 1))) {
                throw new IllegalArgumentException("Cannot create ACLs with same principal id in more than one ACE.");
            }
        }
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public final List<InMemoryAce> getAces() {
        return acl;
    }

    public boolean addAce(InMemoryAce ace) {
        if (ace == null) {
            return false;
        }
        for (InMemoryAce ace2 : acl) {
            if (ace2.getPrincipalId().equals(ace.getPrincipalId())) {
                return false;
            }
        }
        acl.add(ace);
        Collections.sort(acl, COMP);
        return true;
    }

    public boolean removeAce(InMemoryAce ace) {
        return acl.remove(ace);
    }

    public void mergeAcl(InMemoryAcl acl2) {
        if (acl2 == null) {
            return;
        }
        for (InMemoryAce ace : acl2.getAces()) {
            InMemoryAce existingAce = getAce(ace.getPrincipalId());
            if (existingAce == null) {
                acl.add(ace);
            } else if (existingAce.getPermission().ordinal() < ace.getPermission().ordinal()) {
                existingAce.setPermission(ace.getPermission());
            }
        }
        Collections.sort(this.acl, COMP);
    }

    public Permission getPermission(String principalId) {
        InMemoryAce ace = getAce(principalId);
        return ace == null ? Permission.NONE : ace.getPermission();
    }

    private InMemoryAce getAce(String principalId) {
        if (null == principalId) {
            return null;
        }

        for (InMemoryAce ace : acl) {
            if (ace.getPrincipalId().equals(principalId)) {
                return ace;
            }
        }
        return null;
    }

    public boolean hasPermission(String principalId, Permission permission) {
        if (null == permission) {
            return false;
        }

        if (null == principalId) {
            for (InMemoryAce ace : acl) {
                if (ace.getPrincipalId().equals(InMemoryAce.getAnonymousUser())) {
                    return ace.hasPermission(permission);
                }
            }
        }

        for (InMemoryAce ace : acl) {
            if (ace.getPrincipalId().equals(principalId) || ace.getPrincipalId().equals(InMemoryAce.getAnyoneUser())
                    || ace.getPrincipalId().equals(InMemoryAce.getAnonymousUser())) {
                return ace.hasPermission(permission);
            }
        }
        return false;
    }

    public void setPermission(String principalId, Permission permission) {
        for (InMemoryAce ace : acl) {
            if (ace.getPrincipalId().equals(principalId)) {
                ace.setPermission(permission);
            }
        }
        throw new IllegalArgumentException("Unknown principalId in setPermission: " + principalId);
    }

    public int size() {
        return acl.size();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((acl == null) ? 0 : acl.hashCode());
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
        InMemoryAcl other = (InMemoryAcl) obj;
        if (acl == null) {
            if (other.acl != null) {
                return false;
            }
        } else if (!acl.equals(other.acl)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "InMemoryAcl [acl=" + acl + "]";
    }

    private boolean hasPrincipal(String principalId) {
        for (InMemoryAce ace : acl) {
            if (ace.getPrincipalId().equals(principalId)) {
                return true;
            }
        }
        return false;
    }

    public Acl toCommonsAcl() {
        List<Ace> commonsAcl = new ArrayList<Ace>();
        for (InMemoryAce memAce : acl) {
            commonsAcl.add(memAce.toCommonsAce());
        }

        return new AccessControlListImpl(commonsAcl);
    }

    @Override
    public InMemoryAcl clone() throws CloneNotSupportedException {
        InMemoryAcl newAcl = new InMemoryAcl(acl);
        return newAcl;
    }
}
