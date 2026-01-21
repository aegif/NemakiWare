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

import org.apache.chemistry.opencmis.commons.definitions.TypeMutability;

public class TypeMutabilityImpl extends ExtensionDataImpl implements TypeMutability {

    private static final long serialVersionUID = 1L;

    private Boolean canCreate;
    private Boolean canUpdate;
    private Boolean canDelete;

    @Override
    public Boolean canCreate() {
        return canCreate;
    }

    public void setCanCreate(Boolean canCreate) {
        this.canCreate = canCreate;
    }

    @Override
    public Boolean canUpdate() {
        return canUpdate;
    }

    public void setCanUpdate(Boolean canUpdate) {
        this.canUpdate = canUpdate;
    }

    @Override
    public Boolean canDelete() {
        return canDelete;
    }

    public void setCanDelete(Boolean canDelete) {
        this.canDelete = canDelete;
    }

    @Override
    public String toString() {
        return "TypeMutability [canCreate=" + canCreate + ", canUpdate=" + canUpdate + ", canDelete=" + canDelete + "]"
                + super.toString();
    }
}
