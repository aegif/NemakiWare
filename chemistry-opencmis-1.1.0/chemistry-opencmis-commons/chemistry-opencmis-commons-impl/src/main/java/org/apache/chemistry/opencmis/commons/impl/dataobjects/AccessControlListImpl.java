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

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.MutableAcl;

/**
 * Access control list data implementation.
 */
public class AccessControlListImpl extends AbstractExtensionData implements MutableAcl, Serializable {

    private static final long serialVersionUID = 1L;

    private List<Ace> aces;
    private Boolean isExact;

    /**
     * Constructor.
     */
    public AccessControlListImpl() {
    }

    /**
     * Constructor.
     */
    public AccessControlListImpl(List<Ace> aces) {
        this.aces = aces;
    }

    @Override
    public List<Ace> getAces() {
        if (aces == null) {
            aces = new ArrayList<Ace>(0);
        }

        return aces;
    }

    @Override
    public void setAces(List<Ace> aces) {
        this.aces = aces;
    }

    @Override
    public Boolean isExact() {
        return isExact;
    }

    @Override
    public void setExact(Boolean isExact) {
        this.isExact = isExact;
    }

    @Override
    public String toString() {
        return "Access Control List [ACEs=" + aces + ", is exact=" + isExact + "]" + super.toString();
    }
}
