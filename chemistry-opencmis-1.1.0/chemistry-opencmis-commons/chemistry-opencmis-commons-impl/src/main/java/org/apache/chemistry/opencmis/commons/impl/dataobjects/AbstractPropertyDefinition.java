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

import org.apache.chemistry.opencmis.commons.definitions.Choice;
import org.apache.chemistry.opencmis.commons.definitions.MutablePropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

/**
 * Abstract property definition data implementation.
 */
public abstract class AbstractPropertyDefinition<T> extends AbstractExtensionData implements
        MutablePropertyDefinition<T> {

    private static final long serialVersionUID = 1L;

    private String id;
    private String localName;
    private String localNamespace;
    private String queryName;
    private String displayName;
    private String description;
    private PropertyType propertyType;
    private Cardinality cardinality;
    private List<Choice<T>> choiceList;
    private List<T> defaultValue;
    private Updatability updatability;
    private Boolean isInherited;
    private Boolean isQueryable;
    private Boolean isOrderable;
    private Boolean isRequired;
    private Boolean isOpenChoice;

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
    public PropertyType getPropertyType() {
        return propertyType;
    }

    @Override
    public void setPropertyType(PropertyType propertyType) {
        this.propertyType = propertyType;
    }

    @Override
    public Cardinality getCardinality() {
        return cardinality;
    }

    @Override
    public void setCardinality(Cardinality cardinality) {
        this.cardinality = cardinality;
    }

    @Override
    public List<Choice<T>> getChoices() {
        if (choiceList == null) {
            choiceList = new ArrayList<Choice<T>>(0);
        }

        return choiceList;
    }

    @Override
    public void setChoices(List<Choice<T>> choiceList) {
        this.choiceList = choiceList;
    }

    @Override
    public List<T> getDefaultValue() {
        if (defaultValue == null) {
            defaultValue = new ArrayList<T>(0);
        }

        return defaultValue;
    }

    @Override
    public void setDefaultValue(List<T> defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Override
    public Updatability getUpdatability() {
        return updatability;
    }

    @Override
    public void setUpdatability(Updatability updatability) {
        this.updatability = updatability;
    }

    @Override
    public Boolean isInherited() {
        return isInherited;
    }

    @Override
    public void setIsInherited(Boolean isInherited) {
        this.isInherited = isInherited;
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
    public Boolean isOrderable() {
        return isOrderable;
    }

    @Override
    public void setIsOrderable(Boolean isOrderable) {
        this.isOrderable = isOrderable;
    }

    @Override
    public Boolean isRequired() {
        return isRequired;
    }

    @Override
    public void setIsRequired(Boolean isRequired) {
        this.isRequired = isRequired;
    }

    @Override
    public Boolean isOpenChoice() {
        return isOpenChoice;
    }

    @Override
    public void setIsOpenChoice(Boolean isOpenChoice) {
        this.isOpenChoice = isOpenChoice;
    }

    @Override
    public String toString() {
        return "Property Definition [id=" + id + ", display name=" + displayName + ", description=" + description
                + ", local name=" + localName + ", local namespace=" + localNamespace + ", query name=" + queryName
                + ", property type=" + propertyType + ", cardinality=" + cardinality + ", choice list=" + choiceList
                + ", default value=" + defaultValue + ", is inherited=" + isInherited + ", is open choice="
                + isOpenChoice + ", is queryable=" + isQueryable + ", is required=" + isRequired + ", updatability="
                + updatability + "]" + super.toString();
    }
}
