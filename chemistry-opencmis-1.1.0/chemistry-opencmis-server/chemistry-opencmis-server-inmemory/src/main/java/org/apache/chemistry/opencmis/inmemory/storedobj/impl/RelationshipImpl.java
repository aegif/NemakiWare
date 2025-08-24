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

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.FilterParser;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Relationship;

public class RelationshipImpl extends StoredObjectImpl implements Relationship {

    private String sourceObjId;
    private String targetObjId;

    public RelationshipImpl() {
        super();
    }

    @Override
    public String getSourceObjectId() {
        return sourceObjId;
    }

    public void setSource(String id) {
        this.sourceObjId = id;
    }

    @Override
    public String getTargetObjectId() {
        return targetObjId;
    }

    public void setTarget(String id) {
        targetObjId = id;
    }

    @Override
    public void fillProperties(Map<String, PropertyData<?>> properties, BindingsObjectFactory objFactory,
            List<String> requestedIds) {

        super.fillProperties(properties, objFactory, requestedIds);

        if (FilterParser.isContainedInFilter(PropertyIds.SOURCE_ID, requestedIds)) {
            properties.put(PropertyIds.SOURCE_ID,
                    objFactory.createPropertyStringData(PropertyIds.SOURCE_ID, sourceObjId));
        }

        if (FilterParser.isContainedInFilter(PropertyIds.TARGET_ID, requestedIds)) {
            properties.put(PropertyIds.TARGET_ID,
                    objFactory.createPropertyStringData(PropertyIds.TARGET_ID, targetObjId));
        }

    }

}
