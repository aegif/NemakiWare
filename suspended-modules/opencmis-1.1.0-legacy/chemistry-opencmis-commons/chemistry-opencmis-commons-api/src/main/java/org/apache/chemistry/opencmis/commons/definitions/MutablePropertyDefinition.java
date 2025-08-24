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
package org.apache.chemistry.opencmis.commons.definitions;

import java.util.List;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;

/**
 * Mutable base property definition interface.
 * 
 * @cmis 1.0
 */
public interface MutablePropertyDefinition<T> extends PropertyDefinition<T> {

    void setId(String id);

    void setLocalName(String localName);

    void setLocalNamespace(String localNamespace);

    void setQueryName(String queryName);

    void setDisplayName(String displayName);

    void setDescription(String description);

    void setPropertyType(PropertyType propertyType);

    void setCardinality(Cardinality cardinality);

    void setChoices(List<Choice<T>> choiceList);

    void setDefaultValue(List<T> defaultValue);

    void setUpdatability(Updatability updatability);

    void setIsInherited(Boolean isInherited);

    void setIsQueryable(Boolean isQueryable);

    void setIsOrderable(Boolean isOrderable);

    void setIsRequired(Boolean isRequired);

    void setIsOpenChoice(Boolean isOpenChoice);
}
