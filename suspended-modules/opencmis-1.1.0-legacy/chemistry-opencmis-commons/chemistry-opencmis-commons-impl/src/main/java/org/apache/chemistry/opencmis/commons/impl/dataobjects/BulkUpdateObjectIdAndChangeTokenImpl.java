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

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;

public class BulkUpdateObjectIdAndChangeTokenImpl extends AbstractExtensionData implements
        BulkUpdateObjectIdAndChangeToken {

    private static final long serialVersionUID = 1L;

    private String id;
    private String newId;
    private String changeToken = null;

    public BulkUpdateObjectIdAndChangeTokenImpl() {
    }

    public BulkUpdateObjectIdAndChangeTokenImpl(String id, String changeToken) {
        this.id = id;
        this.newId = null;
        this.changeToken = changeToken;
    }

    public BulkUpdateObjectIdAndChangeTokenImpl(String id, String newId, String changeToken) {
        this.id = id;
        this.newId = newId;
        this.changeToken = changeToken;
    }

    public BulkUpdateObjectIdAndChangeTokenImpl(String id, String newId, String changeToken,
            List<CmisExtensionElement> extensions) {
        this.id = id;
        this.newId = newId;
        this.changeToken = changeToken;
        setExtensions(extensions);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getNewId() {
        return newId;
    }

    @Override
    public String getChangeToken() {
        return changeToken;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setNewId(String newId) {
        this.newId = newId;
    }

    public void setChangeToken(String changeToken) {
        this.changeToken = changeToken;
    }

    @Override
    public String toString() {
        return "BulkUpdateObjectIdAndChangeToken [id=" + id + ", newId=" + newId + ", changeToken=" + changeToken + "]"
                + super.toString();
    }
}
