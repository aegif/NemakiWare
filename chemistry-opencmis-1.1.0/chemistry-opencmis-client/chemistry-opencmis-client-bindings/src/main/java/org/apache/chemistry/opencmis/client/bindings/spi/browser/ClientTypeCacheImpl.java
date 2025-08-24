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
package org.apache.chemistry.opencmis.client.bindings.spi.browser;

import org.apache.chemistry.opencmis.client.bindings.cache.TypeDefinitionCache;
import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;

public class ClientTypeCacheImpl implements TypeCache {

    private final String repositoryId;
    private final AbstractBrowserBindingService service;

    public ClientTypeCacheImpl(String repositoryId, AbstractBrowserBindingService service) {
        this.repositoryId = repositoryId;
        this.service = service;
    }

    @Override
    public TypeDefinition getTypeDefinition(String typeId) {

        TypeDefinitionCache cache = CmisBindingsHelper.getTypeDefinitionCache(service.getSession());

        TypeDefinition type = cache.get(repositoryId, typeId);
        if (type == null) {
            type = service.getTypeDefinitionInternal(repositoryId, typeId);
            if (type != null) {
                cache.put(repositoryId, type);
            }
        }

        return type;
    }

    @Override
    public TypeDefinition reloadTypeDefinition(String typeId) {

        TypeDefinitionCache cache = CmisBindingsHelper.getTypeDefinitionCache(service.getSession());

        TypeDefinition type = service.getTypeDefinitionInternal(repositoryId, typeId);
        if (type != null) {
            cache.put(repositoryId, type);
        }

        return type;
    }

    @Override
    public TypeDefinition getTypeDefinitionForObject(String objectId) {
        // not used
        assert false;
        return null;
    }

    @Override
    public PropertyDefinition<?> getPropertyDefinition(String propId) {
        assert false;
        return null;
    }
}
