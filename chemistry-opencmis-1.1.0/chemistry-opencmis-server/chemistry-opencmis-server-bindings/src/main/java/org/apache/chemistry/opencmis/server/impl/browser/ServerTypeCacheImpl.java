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
package org.apache.chemistry.opencmis.server.impl.browser;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyId;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.server.CmisService;

/**
 * Temporary type cache used for one call.
 */
public class ServerTypeCacheImpl implements TypeCache {

    private final String repositoryId;
    private final CmisService service;
    private final Map<String, TypeDefinition> typeDefinitions;
    private final Map<String, TypeDefinition> objectToTypeDefinitions;

    public ServerTypeCacheImpl(String repositoryId, CmisService service) {
        this.repositoryId = repositoryId;
        this.service = service;
        typeDefinitions = new HashMap<String, TypeDefinition>();
        objectToTypeDefinitions = new HashMap<String, TypeDefinition>();
    }

    @Override
    public TypeDefinition getTypeDefinition(String typeId) {
        TypeDefinition type = typeDefinitions.get(typeId);
        if (type == null) {
            type = service.getTypeDefinition(repositoryId, typeId, null);
            if (type != null) {
                typeDefinitions.put(type.getId(), type);
            }
        }

        return type;
    }

    @Override
    public TypeDefinition reloadTypeDefinition(String typeId) {
        TypeDefinition type = service.getTypeDefinition(repositoryId, typeId, null);
        if (type != null) {
            typeDefinitions.put(type.getId(), type);
        }

        return type;
    }

    @Override
    public TypeDefinition getTypeDefinitionForObject(String objectId) {
        TypeDefinition type = objectToTypeDefinitions.get(objectId);
        if (type == null) {
            ObjectData obj = service.getObject(repositoryId, objectId,
                    "cmis:objectId,cmis:objectTypeId,cmis:baseTypeId,cmis:secondaryObjectTypeIds", false,
                    IncludeRelationships.NONE, "cmis:none", false, false, null);

            if (obj != null && obj.getProperties() != null) {
                PropertyData<?> typeProp = obj.getProperties().getProperties().get(PropertyIds.OBJECT_TYPE_ID);
                if (typeProp instanceof PropertyId) {
                    String typeId = ((PropertyId) typeProp).getFirstValue();
                    if (typeId != null) {
                        type = getTypeDefinition(typeId);
                    }
                }

                PropertyData<?> secTypeProp = obj.getProperties().getProperties()
                        .get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
                if ((secTypeProp instanceof PropertyId) && (secTypeProp.getValues() != null)) {
                    for (String secTypeId : ((PropertyId) secTypeProp).getValues()) {
                        if (secTypeId != null) {
                            getTypeDefinition(secTypeId);
                        }
                    }
                }
            }

            objectToTypeDefinitions.put(objectId, type);
        }

        return type;
    }

    @Override
    public PropertyDefinition<?> getPropertyDefinition(String propId) {
        for (TypeDefinition typeDef : typeDefinitions.values()) {
            PropertyDefinition<?> propDef = typeDef.getPropertyDefinitions().get(propId);
            if (propDef != null) {
                return propDef;
            }
        }

        return null;
    }
}
