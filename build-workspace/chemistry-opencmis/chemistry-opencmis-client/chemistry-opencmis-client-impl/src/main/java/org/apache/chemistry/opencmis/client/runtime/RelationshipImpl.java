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
package org.apache.chemistry.opencmis.client.runtime;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Relationship;
import org.apache.chemistry.opencmis.client.api.RelationshipType;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;

public class RelationshipImpl extends AbstractCmisObject implements Relationship {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     */
    public RelationshipImpl(SessionImpl session, ObjectType objectType, ObjectData objectData, OperationContext context) {
        initialize(session, objectType, objectData, context);
    }

    @Override
    public RelationshipType getRelationshipType() {
        ObjectType objectType = super.getType();
        if (objectType instanceof RelationshipType) {
            return (RelationshipType) objectType;
        } else {
            throw new ClassCastException("Object type is not a relationship type.");
        }
    }

    @Override
    public CmisObject getSource() {
        return getSource(getSession().getDefaultContext());
    }

    @Override
    public CmisObject getSource(OperationContext context) {
        readLock();
        try {
            ObjectId sourceId = getSourceId();
            if (sourceId == null) {
                return null;
            }

            return getSession().getObject(sourceId, context);
        } finally {
            readUnlock();
        }
    }

    @Override
    public ObjectId getSourceId() {
        String sourceId = getPropertyValue(PropertyIds.SOURCE_ID);
        if (sourceId == null || sourceId.length() == 0) {
            return null;
        }

        return getSession().createObjectId(sourceId);
    }

    @Override
    public CmisObject getTarget() {
        return getTarget(getSession().getDefaultContext());
    }

    @Override
    public CmisObject getTarget(OperationContext context) {
        readLock();
        try {
            ObjectId targetId = getTargetId();
            if (targetId == null) {
                return null;
            }

            return getSession().getObject(targetId, context);
        } finally {
            readUnlock();
        }
    }

    @Override
    public ObjectId getTargetId() {
        String targetId = getPropertyValue(PropertyIds.TARGET_ID);
        if (targetId == null || targetId.length() == 0) {
            return null;
        }

        return getSession().createObjectId(targetId);
    }
}
