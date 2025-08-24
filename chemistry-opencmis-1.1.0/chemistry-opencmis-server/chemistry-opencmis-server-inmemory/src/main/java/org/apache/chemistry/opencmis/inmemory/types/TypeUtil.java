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
package org.apache.chemistry.opencmis.inmemory.types;

import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.FolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.ItemTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PolicyTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyBooleanDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyHtmlDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyUriDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.SecondaryTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ItemTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;

public final class TypeUtil {

    private TypeUtil() {
    }

    public static TypeDefinition cloneType(TypeDefinition type) {
        if (type instanceof DocumentTypeDefinition) {
            return cloneTypeDoc((DocumentTypeDefinition) type);
        } else if (type instanceof FolderTypeDefinition) {
            return cloneTypeFolder((FolderTypeDefinition) type);
        } else if (type instanceof PolicyTypeDefinition) {
            return cloneTypePolicy((PolicyTypeDefinition) type);
        } else if (type instanceof ItemTypeDefinition) {
            return cloneTypeItem((ItemTypeDefinition) type);
        } else if (type instanceof RelationshipTypeDefinition) {
            return cloneTypeRelationship((RelationshipTypeDefinition) type);
        } else if (type instanceof SecondaryTypeDefinition) {
            return cloneTypeSecondary((SecondaryTypeDefinition) type);
        } else {
            return null;
        }
    }

    public static AbstractPropertyDefinition<?> clonePropertyDefinition(PropertyDefinition<?> pd) {
        if (pd instanceof PropertyBooleanDefinition) {
            PropertyBooleanDefinitionImpl pdBoolDef = new PropertyBooleanDefinitionImpl();
            PropertyBooleanDefinitionImpl pdSrc = (PropertyBooleanDefinitionImpl) pd;
            initializeAbstractPropertyDefinition(pd, pdBoolDef);
            pdBoolDef.setChoices(pdSrc.getChoices());
            pdBoolDef.setDefaultValue(pdSrc.getDefaultValue());
            return pdBoolDef;
        } else if (pd instanceof PropertyDateTimeDefinition) {
            PropertyDateTimeDefinitionImpl pdDateDef = new PropertyDateTimeDefinitionImpl();
            PropertyDateTimeDefinitionImpl pdSrc = (PropertyDateTimeDefinitionImpl) pd;
            initializeAbstractPropertyDefinition(pd, pdDateDef);
            pdDateDef.setChoices(pdSrc.getChoices());
            pdDateDef.setDefaultValue(pdSrc.getDefaultValue());
            pdDateDef.setDateTimeResolution(pdSrc.getDateTimeResolution());
            return pdDateDef;
        } else if (pd instanceof PropertyDecimalDefinition) {
            PropertyDecimalDefinitionImpl pdDecDef = new PropertyDecimalDefinitionImpl();
            PropertyDecimalDefinitionImpl pdSrc = (PropertyDecimalDefinitionImpl) pd;
            initializeAbstractPropertyDefinition(pd, pdDecDef);
            pdDecDef.setChoices(pdSrc.getChoices());
            pdDecDef.setDefaultValue(pdSrc.getDefaultValue());
            pdDecDef.setMinValue(pdSrc.getMinValue());
            pdDecDef.setMaxValue(pdSrc.getMaxValue());
            pdDecDef.setPrecision(pdSrc.getPrecision());
            return pdDecDef;
        } else if (pd instanceof PropertyHtmlDefinition) {
            PropertyHtmlDefinitionImpl pdHtmlDef = new PropertyHtmlDefinitionImpl();
            PropertyHtmlDefinitionImpl pdSrc = (PropertyHtmlDefinitionImpl) pd;
            initializeAbstractPropertyDefinition(pd, pdHtmlDef);
            pdHtmlDef.setChoices(pdSrc.getChoices());
            pdHtmlDef.setDefaultValue(pdSrc.getDefaultValue());
            return pdHtmlDef;
        } else if (pd instanceof PropertyIdDefinition) {
            PropertyIdDefinitionImpl pdIdDef = new PropertyIdDefinitionImpl();
            PropertyIdDefinitionImpl pdSrc = (PropertyIdDefinitionImpl) pd;
            initializeAbstractPropertyDefinition(pd, pdIdDef);
            pdIdDef.setChoices(pdSrc.getChoices());
            pdIdDef.setDefaultValue(pdSrc.getDefaultValue());
            return pdIdDef;
        } else if (pd instanceof PropertyIntegerDefinition) {
            PropertyIntegerDefinitionImpl pdIntDef = new PropertyIntegerDefinitionImpl();
            PropertyIntegerDefinitionImpl pdSrc = (PropertyIntegerDefinitionImpl) pd;
            initializeAbstractPropertyDefinition(pd, pdIntDef);
            pdIntDef.setChoices(pdSrc.getChoices());
            pdIntDef.setDefaultValue(pdSrc.getDefaultValue());
            pdIntDef.setMinValue(pdSrc.getMinValue());
            pdIntDef.setMaxValue(pdSrc.getMaxValue());
            return pdIntDef;
        } else if (pd instanceof PropertyStringDefinition) {
            PropertyStringDefinitionImpl pdStringDef = new PropertyStringDefinitionImpl();
            PropertyStringDefinitionImpl pdSrc = (PropertyStringDefinitionImpl) pd;
            initializeAbstractPropertyDefinition(pd, pdStringDef);
            pdStringDef.setChoices(pdSrc.getChoices());
            pdStringDef.setDefaultValue(pdSrc.getDefaultValue());
            pdStringDef.setMaxLength(pdSrc.getMaxLength());
            return pdStringDef;
        } else if (pd instanceof PropertyUriDefinition) {
            PropertyUriDefinitionImpl pdUriDef = new PropertyUriDefinitionImpl();
            PropertyUriDefinition pdSrc = (PropertyUriDefinition) pd;
            initializeAbstractPropertyDefinition(pd, pdUriDef);
            pdUriDef.setChoices(pdSrc.getChoices());
            pdUriDef.setDefaultValue(pdSrc.getDefaultValue());
            return pdUriDef;
        } else {
            return null;
        }
    }

    public static DocumentTypeDefinitionImpl cloneTypeDoc(DocumentTypeDefinition type) {
        DocumentTypeDefinitionImpl td = new DocumentTypeDefinitionImpl();
        td.initialize(type);
        td.setIsVersionable(type.isVersionable());
        td.setContentStreamAllowed(type.getContentStreamAllowed());
        return td;
    }

    public static FolderTypeDefinitionImpl cloneTypeFolder(FolderTypeDefinition type) {
        FolderTypeDefinitionImpl td = new FolderTypeDefinitionImpl();
        td.initialize(type);
        return td;
    }

    public static RelationshipTypeDefinitionImpl cloneTypeRelationship(RelationshipTypeDefinition type) {
        RelationshipTypeDefinitionImpl td = new RelationshipTypeDefinitionImpl();
        td.initialize(type);
        td.setAllowedSourceTypes(type.getAllowedSourceTypeIds());
        td.setAllowedTargetTypes(type.getAllowedTargetTypeIds());
        return td;
    }

    public static ItemTypeDefinitionImpl cloneTypeItem(ItemTypeDefinition type) {
        ItemTypeDefinitionImpl td = new ItemTypeDefinitionImpl();
        td.initialize(type);
        return td;
    }

    public static SecondaryTypeDefinitionImpl cloneTypeSecondary(SecondaryTypeDefinition type) {
        SecondaryTypeDefinitionImpl td = new SecondaryTypeDefinitionImpl();
        td.initialize(type);
        return td;
    }

    public static PolicyTypeDefinitionImpl cloneTypePolicy(PolicyTypeDefinition type) {
        PolicyTypeDefinitionImpl td = new PolicyTypeDefinitionImpl();
        td.initialize(td);
        return null;
    }

    public static String getQueryNameFromId(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '.' || c == ' ' || c == ',' || c == '\'' || c == '"' || c == '\\' || c == '(' || c == ')') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void initializeAbstractPropertyDefinition(PropertyDefinition<?> pdSrc,
            AbstractPropertyDefinition<?> pdTarget) {
        pdTarget.setCardinality(pdSrc.getCardinality());
        pdTarget.setDescription(pdSrc.getDescription());
        pdTarget.setDisplayName(pdSrc.getDisplayName());
        pdTarget.setExtensions(pdSrc.getExtensions());
        pdTarget.setId(pdSrc.getId());
        pdTarget.setIsInherited(false);
        pdTarget.setIsOpenChoice(pdSrc.isOpenChoice());
        pdTarget.setIsOrderable(pdSrc.isOrderable());
        pdTarget.setIsQueryable(pdSrc.isQueryable());
        pdTarget.setIsRequired(pdSrc.isRequired());
        pdTarget.setLocalName(pdSrc.getLocalName());
        pdTarget.setLocalNamespace(pdSrc.getLocalNamespace());
        pdTarget.setPropertyType(pdSrc.getPropertyType());
        pdTarget.setQueryName(pdSrc.getQueryName());
        pdTarget.setUpdatability(pdSrc.getUpdatability());
    }

}
