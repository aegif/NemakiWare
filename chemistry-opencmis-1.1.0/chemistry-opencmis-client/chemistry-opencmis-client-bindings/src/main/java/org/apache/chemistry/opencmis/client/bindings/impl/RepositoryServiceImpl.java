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
package org.apache.chemistry.opencmis.client.bindings.impl;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.client.bindings.cache.TypeDefinitionCache;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.CmisSpi;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.spi.ExtendedRepositoryService;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;

/**
 * Repository Service implementation.
 * 
 * Passes requests to the SPI and handles caching.
 */
public class RepositoryServiceImpl implements RepositoryService, ExtendedRepositoryService, Serializable {

    private static final long serialVersionUID = 1L;

    private final BindingSession session;

    /**
     * Constructor.
     */
    public RepositoryServiceImpl(BindingSession session) {
        assert session != null;

        this.session = session;
    }

    @Override
    public RepositoryInfo getRepositoryInfo(String repositoryId, ExtensionsData extension) {
        RepositoryInfo result = null;
        boolean hasExtension = (extension != null) && isNotEmpty(extension.getExtensions());

        RepositoryInfoCache cache = CmisBindingsHelper.getRepositoryInfoCache(session);

        // if extension is not set, check the cache first
        if (!hasExtension) {
            result = cache.get(repositoryId);
            if (result != null) {
                return result;
            }
        }

        // it was not in the cache -> get the SPI and fetch the repository info
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        result = spi.getRepositoryService().getRepositoryInfo(repositoryId, extension);

        // put it into the cache
        if (!hasExtension) {
            cache.put(result);
        }

        return result;
    }

    @Override
    public List<RepositoryInfo> getRepositoryInfos(ExtensionsData extension) {
        List<RepositoryInfo> result = null;
        boolean hasExtension = (extension != null) && isNotEmpty(extension.getExtensions());

        // get the SPI and fetch the repository infos
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        result = spi.getRepositoryService().getRepositoryInfos(extension);

        // put it into the cache
        if (!hasExtension && (result != null)) {
            RepositoryInfoCache cache = CmisBindingsHelper.getRepositoryInfoCache(session);
            for (RepositoryInfo rid : result) {
                cache.put(rid);
            }
        }

        return result;
    }

    @Override
    public TypeDefinitionList getTypeChildren(String repositoryId, String typeId, Boolean includePropertyDefinitions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        TypeDefinitionList result = null;
        boolean hasExtension = (extension != null) && isNotEmpty(extension.getExtensions());
        boolean propDefs = (includePropertyDefinitions == null ? false : includePropertyDefinitions.booleanValue());

        // get the SPI and fetch the type definitions
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        result = spi.getRepositoryService().getTypeChildren(repositoryId, typeId, includePropertyDefinitions, maxItems,
                skipCount, extension);

        // put it into the cache
        if (!hasExtension && propDefs && (result != null)) {
            TypeDefinitionCache cache = CmisBindingsHelper.getTypeDefinitionCache(session);

            for (TypeDefinition tdd : result.getList()) {
                cache.put(repositoryId, tdd);
            }
        }

        return result;
    }

    @Override
    public TypeDefinition getTypeDefinition(String repositoryId, String typeId, ExtensionsData extension) {
        return getTypeDefinition(repositoryId, typeId, extension, true);
    }

    @Override
    public TypeDefinition getTypeDefinition(String repositoryId, String typeId, ExtensionsData extension,
            boolean useCache) {
        TypeDefinition result = null;
        boolean hasExtension = (extension != null) && isNotEmpty(extension.getExtensions());

        TypeDefinitionCache cache = CmisBindingsHelper.getTypeDefinitionCache(session);

        // if the cache should be used and the extension is not set,
        // check the cache first
        if (useCache && !hasExtension) {
            result = cache.get(repositoryId, typeId);
            if (result != null) {
                return result;
            }
        }

        // it was not in the cache -> get the SPI and fetch the type definition
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        result = spi.getRepositoryService().getTypeDefinition(repositoryId, typeId, extension);

        // put it into the cache
        if (!hasExtension && (result != null)) {
            cache.put(repositoryId, result);
        }

        return result;
    }

    @Override
    public List<TypeDefinitionContainer> getTypeDescendants(String repositoryId, String typeId, BigInteger depth,
            Boolean includePropertyDefinitions, ExtensionsData extension) {
        List<TypeDefinitionContainer> result = null;
        boolean hasExtension = (extension != null) && isNotEmpty(extension.getExtensions());
        boolean propDefs = includePropertyDefinitions == null ? false : includePropertyDefinitions.booleanValue();

        // get the SPI and fetch the type definitions
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        result = spi.getRepositoryService().getTypeDescendants(repositoryId, typeId, depth, includePropertyDefinitions,
                extension);

        // put it into the cache
        if (!hasExtension && propDefs && (result != null)) {
            TypeDefinitionCache cache = CmisBindingsHelper.getTypeDefinitionCache(session);
            addToTypeCache(cache, repositoryId, result);
        }

        return result;
    }

    private void addToTypeCache(TypeDefinitionCache cache, String repositoryId, List<TypeDefinitionContainer> containers) {
        if (containers == null) {
            return;
        }

        for (TypeDefinitionContainer container : containers) {
            cache.put(repositoryId, container.getTypeDefinition());
            addToTypeCache(cache, repositoryId, container.getChildren());
        }
    }

    @Override
    public TypeDefinition createType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        // get the SPI and create the type definitions
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        TypeDefinition result = spi.getRepositoryService().createType(repositoryId, type, extension);

        // add the type to cache
        TypeDefinitionCache cache = CmisBindingsHelper.getTypeDefinitionCache(session);
        cache.put(repositoryId, result);

        return result;
    }

    @Override
    public TypeDefinition updateType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        // get the SPI and update the type definitions
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        TypeDefinition result = spi.getRepositoryService().updateType(repositoryId, type, extension);

        // update the type in cache
        TypeDefinitionCache cache = CmisBindingsHelper.getTypeDefinitionCache(session);
        cache.put(repositoryId, result);

        return result;
    }

    @Override
    public void deleteType(String repositoryId, String typeId, ExtensionsData extension) {
        // get the SPI and delete the type definitions
        CmisSpi spi = CmisBindingsHelper.getSPI(session);
        spi.getRepositoryService().deleteType(repositoryId, typeId, extension);

        // remove the type from cache
        TypeDefinitionCache cache = CmisBindingsHelper.getTypeDefinitionCache(session);
        cache.remove(repositoryId, typeId);
    }

}
