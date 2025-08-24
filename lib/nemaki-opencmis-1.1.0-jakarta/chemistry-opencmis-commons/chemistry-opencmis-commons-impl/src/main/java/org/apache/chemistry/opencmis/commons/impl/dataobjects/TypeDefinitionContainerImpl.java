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

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;

/**
 * TypeDefinitionContainer implementation.
 */
public class TypeDefinitionContainerImpl extends AbstractExtensionData implements TypeDefinitionContainer {

    private static final long serialVersionUID = 1L;

    private TypeDefinition type;
    private List<TypeDefinitionContainer> children;

    public TypeDefinitionContainerImpl() {
    }

    public TypeDefinitionContainerImpl(TypeDefinition typeDef) {
        type = typeDef;
    }

    @Override
    public TypeDefinition getTypeDefinition() {
        return type;
    }

    public void setTypeDefinition(TypeDefinition type) {
        this.type = type;
    }

    @Override
    public List<TypeDefinitionContainer> getChildren() {
        if (children == null) {
            children = new ArrayList<TypeDefinitionContainer>(0);
        }

        return children;
    }

    public void setChildren(List<TypeDefinitionContainer> children) {
        this.children = children;
    }

    @Override
    public String toString() {
        return "Type Definition Container [type=" + type + " ,children=" + children + "]" + super.toString();
    }
}
