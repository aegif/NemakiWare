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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.definitions.MutableTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeMutability;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

/**
 * Abstract type definition data implementation.
 */
public abstract class AbstractTypeDefinition extends AbstractExtensionData implements MutableTypeDefinition {

    private static final long serialVersionUID = 2L;

    private String id;
    private String localName;
    private String localNamespace;
    private String queryName;
    private String displayName;
    private String description;
    private BaseTypeId baseId;
    private String parentId;
    private Boolean isCreatable;
    private Boolean isFileable;
    private Boolean isQueryable;
    private Boolean isIncludedInSupertypeQuery;
    private Boolean isFulltextIndexed;
    private Boolean isControllableACL;
    private Boolean isControllablePolicy;
    private LinkedHashMap<String, PropertyDefinition<?>> propertyDefinitions = new LinkedHashMap<String, PropertyDefinition<?>>();
    private TypeMutability typeMutability;

    public void initialize(TypeDefinition typeDefinition) {
        assert typeDefinition != null;

        setId(typeDefinition.getId());
        setLocalName(typeDefinition.getLocalName());
        setLocalNamespace(typeDefinition.getLocalNamespace());
        setQueryName(typeDefinition.getQueryName());
        setDisplayName(typeDefinition.getDisplayName());
        setDescription(typeDefinition.getDescription());
        setBaseTypeId(typeDefinition.getBaseTypeId());
        setParentTypeId(typeDefinition.getParentTypeId());
        setIsCreatable(typeDefinition.isCreatable());
        setIsFileable(typeDefinition.isFileable());
        setIsQueryable(typeDefinition.isQueryable());
        setIsIncludedInSupertypeQuery(typeDefinition.isIncludedInSupertypeQuery());
        setIsFulltextIndexed(typeDefinition.isFulltextIndexed());
        setIsControllableAcl(typeDefinition.isControllableAcl());
        setIsControllablePolicy(typeDefinition.isControllablePolicy());
        setPropertyDefinitions(typeDefinition.getPropertyDefinitions());
        setTypeMutability(typeDefinition.getTypeMutability());
        setExtensions(typeDefinition.getExtensions());
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getLocalName() {
        return localName;
    }

    @Override
    public void setLocalName(String localName) {
        this.localName = localName;
    }

    @Override
    public String getLocalNamespace() {
        return localNamespace;
    }

    @Override
    public void setLocalNamespace(String localNamespace) {
        this.localNamespace = localNamespace;
    }

    @Override
    public String getQueryName() {
        return queryName;
    }

    @Override
    public void setQueryName(String queryName) {
        this.queryName = queryName;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public BaseTypeId getBaseTypeId() {
        return baseId;
    }

    @Override
    public void setBaseTypeId(BaseTypeId baseId) {
        this.baseId = baseId;
    }

    @Override
    public String getParentTypeId() {
        return parentId;
    }

    @Override
    public void setParentTypeId(String parentId) {
        if (parentId == null || parentId.length() == 0) {
            this.parentId = null;
        } else {
            this.parentId = parentId;
        }
    }

    @Override
    public Boolean isCreatable() {
        return isCreatable;
    }

    @Override
    public void setIsCreatable(Boolean isCreatable) {
        this.isCreatable = isCreatable;
    }

    @Override
    public Boolean isFileable() {
        return isFileable;
    }

    @Override
    public void setIsFileable(Boolean isFileable) {
        this.isFileable = isFileable;
    }

    @Override
    public Boolean isQueryable() {
        return isQueryable;
    }

    @Override
    public void setIsQueryable(Boolean isQueryable) {
        this.isQueryable = isQueryable;
    }

    @Override
    public Boolean isIncludedInSupertypeQuery() {
        return isIncludedInSupertypeQuery;
    }

    @Override
    public void setIsIncludedInSupertypeQuery(Boolean isIncludedInSupertypeQuery) {
        this.isIncludedInSupertypeQuery = isIncludedInSupertypeQuery;
    }

    @Override
    public Boolean isFulltextIndexed() {
        return isFulltextIndexed;
    }

    @Override
    public void setIsFulltextIndexed(Boolean isFulltextIndexed) {
        this.isFulltextIndexed = isFulltextIndexed;
    }

    @Override
    public Boolean isControllableAcl() {
        return isControllableACL;
    }

    @Override
    public void setIsControllableAcl(Boolean isControllableACL) {
        this.isControllableACL = isControllableACL;
    }

    @Override
    public Boolean isControllablePolicy() {
        return isControllablePolicy;
    }

    @Override
    public void setIsControllablePolicy(Boolean isControllablePolicy) {
        this.isControllablePolicy = isControllablePolicy;
    }

    @Override
    public Map<String, PropertyDefinition<?>> getPropertyDefinitions() {
        return propertyDefinitions;
    }

    public void setPropertyDefinitions(Map<String, PropertyDefinition<?>> newPropertyDefinitions) {
        if (newPropertyDefinitions == null) {
            propertyDefinitions = new LinkedHashMap<String, PropertyDefinition<?>>();
        } else if (newPropertyDefinitions instanceof LinkedHashMap) {
            propertyDefinitions = (LinkedHashMap<String, PropertyDefinition<?>>) newPropertyDefinitions;
        } else {
            propertyDefinitions = new LinkedHashMap<String, PropertyDefinition<?>>(newPropertyDefinitions);
        }
    }

    @Override
    public void addPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
        if (propertyDefinition == null) {
            return;
        }

        propertyDefinitions.put(propertyDefinition.getId(), propertyDefinition);
    }

    @Override
    public void removePropertyDefinition(String propertyId) {
        if (propertyId == null) {
            return;
        }

        propertyDefinitions.remove(propertyId);
    }

    @Override
    public void removeAllPropertyDefinitions() {
        propertyDefinitions = new LinkedHashMap<String, PropertyDefinition<?>>();
    }

    @Override
    public TypeMutability getTypeMutability() {
        return typeMutability;
    }

    @Override
    public void setTypeMutability(TypeMutability typeMutability) {
        this.typeMutability = typeMutability;
    }

    @Override
    public String toString() {
        return "Type Definition [base id=" + baseId + ", id=" + id + ", display Name=" + displayName + ", description="
                + description + ", local name=" + localName + ", local namespace=" + localNamespace + ", query name="
                + queryName + ", parent id=" + parentId + ", is controllable ACL=" + isControllableACL
                + ", is controllable policy=" + isControllablePolicy + ", is creatable=" + isCreatable
                + ", is fileable=" + isFileable + ", is fulltext indexed=" + isFulltextIndexed
                + ", is included in supertype query=" + isIncludedInSupertypeQuery + ", is queryable=" + isQueryable
                + ", property definitions=" + propertyDefinitions + ", typeMutability=" + typeMutability + "]"
                + super.toString();
    }
}
