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

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.spi.RepositoryService;

/**
 * Repository Service Browser Binding client.
 */
public class RepositoryServiceImpl extends AbstractBrowserBindingService implements RepositoryService {

    /**
     * Constructor.
     */
    public RepositoryServiceImpl(BindingSession session) {
        setSession(session);
    }

    @Override
    public List<RepositoryInfo> getRepositoryInfos(ExtensionsData extension) {
        return getRepositoriesInternal(null);
    }

    @Override
    public RepositoryInfo getRepositoryInfo(String repositoryId, ExtensionsData extension) {
        List<RepositoryInfo> repositoryInfos = getRepositoriesInternal(repositoryId);

        if (repositoryInfos.isEmpty()) {
            throw new CmisObjectNotFoundException("Repository '" + repositoryId + "' not found!");
        }

        // find the repository
        for (RepositoryInfo info : repositoryInfos) {
            if (info.getId() == null) {
                continue;
            }

            if (info.getId().equals(repositoryId)) {
                return info;
            }
        }

        throw new CmisObjectNotFoundException("Repository '" + repositoryId + "' not found!");
    }

    @Override
    public TypeDefinition getTypeDefinition(String repositoryId, String typeId, ExtensionsData extension) {
        return getTypeDefinitionInternal(repositoryId, typeId);
    }

    @Override
    public TypeDefinitionList getTypeChildren(String repositoryId, String typeId, Boolean includePropertyDefinitions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId, Constants.SELECTOR_TYPE_CHILDREN);
        url.addParameter(Constants.PARAM_TYPE_ID, typeId);
        url.addParameter(Constants.PARAM_PROPERTY_DEFINITIONS, includePropertyDefinitions);
        url.addParameter(Constants.PARAM_MAX_ITEMS, maxItems);
        url.addParameter(Constants.PARAM_SKIP_COUNT, skipCount);
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        return JSONConverter.convertTypeChildren(json);
    }

    @Override
    public List<TypeDefinitionContainer> getTypeDescendants(String repositoryId, String typeId, BigInteger depth,
            Boolean includePropertyDefinitions, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId, Constants.SELECTOR_TYPE_DESCENDANTS);
        url.addParameter(Constants.PARAM_TYPE_ID, typeId);
        url.addParameter(Constants.PARAM_DEPTH, depth);
        url.addParameter(Constants.PARAM_PROPERTY_DEFINITIONS, includePropertyDefinitions);
        url.addParameter(Constants.PARAM_DATETIME_FORMAT, getDateTimeFormatParameter());

        // read and parse
        Response resp = read(url);
        List<Object> json = parseArray(resp.getStream(), resp.getCharset());

        return JSONConverter.convertTypeDescendants(json);
    }

    @Override
    public TypeDefinition createType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_CREATE_TYPE);
        if (type != null) {
            formData.addParameter(Constants.CONTROL_TYPE, JSONConverter.convert(type, getDateTimeFormat())
                    .toJSONString());
        }

        // send
        Response resp = post(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });

        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        return JSONConverter.convertTypeDefinition(json);
    }

    @Override
    public TypeDefinition updateType(String repositoryId, TypeDefinition type, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_UPDATE_TYPE);
        if (type != null) {
            formData.addParameter(Constants.CONTROL_TYPE, JSONConverter.convert(type, getDateTimeFormat())
                    .toJSONString());
        }

        // send
        Response resp = post(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });

        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        return JSONConverter.convertTypeDefinition(json);
    }

    @Override
    public void deleteType(String repositoryId, String typeId, ExtensionsData extension) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId);

        // prepare form data
        final FormDataWriter formData = new FormDataWriter(Constants.CMISACTION_DELETE_TYPE);
        formData.addParameter(Constants.CONTROL_TYPE_ID, typeId);

        // send
        postAndConsume(url, formData.getContentType(), new Output() {
            @Override
            public void write(OutputStream out) throws IOException {
                formData.write(out);
            }
        });
    }
}
