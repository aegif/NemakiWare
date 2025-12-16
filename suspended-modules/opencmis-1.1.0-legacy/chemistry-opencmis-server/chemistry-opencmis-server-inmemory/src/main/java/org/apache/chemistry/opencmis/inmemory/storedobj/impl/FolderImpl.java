package org.apache.chemistry.opencmis.inmemory.storedobj.impl;

/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.NameValidator;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Folder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FolderImpl extends StoredObjectImpl implements Folder {
    private static final Logger LOG = LoggerFactory.getLogger(FilingImpl.class.getName());
    private String parentId;

    public FolderImpl() {
        super();
    }

    public FolderImpl(String name, String parentId) {
        super();
        init(name, parentId);
    }

    @Override
    public void fillProperties(Map<String, PropertyData<?>> properties, BindingsObjectFactory objFactory,
            List<String> requestedIds) {

        super.fillProperties(properties, objFactory, requestedIds);

        // add folder specific properties

        if (FilterParser.isContainedInFilter(PropertyIds.PARENT_ID, requestedIds)) {
            properties.put(PropertyIds.PARENT_ID, objFactory.createPropertyIdData(PropertyIds.PARENT_ID, parentId));
        }

        if (FilterParser.isContainedInFilter(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS, requestedIds)) {
            String allowedChildObjects = null; // TODO: not yet supported
            properties.put(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS,
                    objFactory.createPropertyIdData(PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS, allowedChildObjects));
        }

    }

    @Override
    public List<String> getAllowedChildObjectTypeIds() {
        // TODO implement this.
        return null;
    }

    @Override
    public boolean hasRendition(String user) {
        return true;
    }

    @Override
    public List<String> getParentIds() {
        if (parentId == null) {
            return Collections.emptyList();
        } else {
            return Collections.singletonList(parentId);
        }
    }

    @Override
    public boolean hasParent() {
        return null != parentId;
    }

    @Override
    public String getParentId() {
        return parentId;
    }

    @Override
    public String getPathSegment() {
        return getName();
    }

    @Override
    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    // Helper functions
    private void init(String name, String parentId) {
        if (!NameValidator.isValidName(name)) {
            throw new CmisInvalidArgumentException(NameValidator.ERROR_ILLEGAL_NAME);
        }
        setName(name);
        this.parentId = parentId;
    }

}
