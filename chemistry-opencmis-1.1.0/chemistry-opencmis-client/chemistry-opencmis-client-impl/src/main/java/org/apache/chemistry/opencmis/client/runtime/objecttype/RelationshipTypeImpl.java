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
package org.apache.chemistry.opencmis.client.runtime.objecttype;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.RelationshipType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.Tree;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;

/**
 * Relationship type.
 */
public class RelationshipTypeImpl extends RelationshipTypeDefinitionImpl implements RelationshipType, Serializable {

    private static final long serialVersionUID = 1L;

    private final ObjectTypeHelper helper;
    private List<ObjectType> allowedSourceTypes;
    private List<ObjectType> allowedTargetTypes;

    public RelationshipTypeImpl(Session session, RelationshipTypeDefinition typeDefinition) {
        assert session != null;
        assert typeDefinition != null;

        initialize(typeDefinition);
        setAllowedSourceTypes(typeDefinition.getAllowedSourceTypeIds());
        setAllowedTargetTypes(typeDefinition.getAllowedTargetTypeIds());
        helper = new ObjectTypeHelper(session, this);
    }

    @Override
    public ObjectType getBaseType() {
        return helper.getBaseType();
    }

    @Override
    public ItemIterable<ObjectType> getChildren() {
        return helper.getChildren();
    }

    @Override
    public List<Tree<ObjectType>> getDescendants(int depth) {
        return helper.getDescendants(depth);
    }

    @Override
    public ObjectType getParentType() {
        return helper.getParentType();
    }

    @Override
    public boolean isBaseType() {
        return helper.isBaseType();
    }

    @Override
    public List<ObjectType> getAllowedSourceTypes() {
        if (allowedSourceTypes == null) {
            List<String> ids = getAllowedSourceTypeIds();
            List<ObjectType> types = new ArrayList<ObjectType>(ids == null ? 0 : ids.size());
            if (ids != null) {
                for (String id : ids) {
                    types.add(helper.getSession().getTypeDefinition(id));
                }
            }
            allowedSourceTypes = types;
        }
        return allowedSourceTypes;
    }

    @Override
    public List<ObjectType> getAllowedTargetTypes() {
        if (allowedTargetTypes == null) {
            List<String> ids = getAllowedTargetTypeIds();
            List<ObjectType> types = new ArrayList<ObjectType>(ids == null ? 0 : ids.size());
            if (ids != null) {
                for (String id : ids) {
                    types.add(helper.getSession().getTypeDefinition(id));
                }
            }
            allowedTargetTypes = types;
        }
        return allowedTargetTypes;
    }

}
