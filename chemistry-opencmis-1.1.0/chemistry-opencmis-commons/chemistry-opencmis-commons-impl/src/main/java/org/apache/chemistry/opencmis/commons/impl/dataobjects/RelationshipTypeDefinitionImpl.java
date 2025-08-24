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

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.definitions.MutableRelationshipTypeDefinition;

/**
 * Relationship type definition.
 */
public class RelationshipTypeDefinitionImpl extends AbstractTypeDefinition implements MutableRelationshipTypeDefinition {

    private static final long serialVersionUID = 1L;

    private List<String> allowedSourceTypes;
    private List<String> allowedTargetTypes;

    @Override
    public List<String> getAllowedSourceTypeIds() {
        if (allowedSourceTypes == null) {
            allowedSourceTypes = new ArrayList<String>(0);
        }

        return allowedSourceTypes;
    }

    @Override
    public void setAllowedSourceTypes(List<String> allowedSourceTypes) {
        this.allowedSourceTypes = allowedSourceTypes;
    }

    @Override
    public List<String> getAllowedTargetTypeIds() {
        if (allowedTargetTypes == null) {
            allowedTargetTypes = new ArrayList<String>(0);
        }

        return allowedTargetTypes;
    }

    @Override
    public void setAllowedTargetTypes(List<String> allowedTargetTypes) {
        this.allowedTargetTypes = allowedTargetTypes;
    }
}
