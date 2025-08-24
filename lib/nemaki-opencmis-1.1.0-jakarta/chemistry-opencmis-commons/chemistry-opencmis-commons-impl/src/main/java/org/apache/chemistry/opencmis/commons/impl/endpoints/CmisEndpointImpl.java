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
package org.apache.chemistry.opencmis.commons.impl.endpoints;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.chemistry.opencmis.commons.endpoints.CmisAuthentication;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpoint;

public class CmisEndpointImpl extends LinkedHashMap<String, Object> implements CmisEndpoint {

    private static final long serialVersionUID = 1L;

    public CmisEndpointImpl() {
    }

    public CmisEndpointImpl(String cmisVersion, String binding) {
        put(KEY_CMIS_VERSION, cmisVersion);
        put(KEY_BINDING, binding);
    }

    @Override
    public String getDisplayName() {
        return getString(KEY_DISPLAY_NAME);
    }

    @Override
    public String getCmisVersion() {
        return getString(KEY_CMIS_VERSION);
    }

    @Override
    public String getBinding() {
        return getString(KEY_BINDING);
    }

    @Override
    public String getUrl() {
        return getString(KEY_URL);
    }

    @Override
    public String getRepositoryServiceWdsl() {
        return getString(KEY_REPOSITORY_SERVICE_WSDL);
    }

    @Override
    public String getNavigationServiceWdsl() {
        return getString(KEY_NAVIGATION_SERVICE_WSDL);
    }

    @Override
    public String getObjectServiceWdsl() {
        return getString(KEY_OBJECT_SERVICE_WSDL);
    }

    @Override
    public String getMultifilingServiceWdsl() {
        return getString(KEY_MULTIFILING_SERVICE_WSDL);
    }

    @Override
    public String getDiscoveryServiceWdsl() {
        return getString(KEY_DISCOVERY_SERVICE_WSDL);
    }

    @Override
    public String getVersioningServiceWdsl() {
        return getString(KEY_VERSIONING_SERVICE_WSDL);
    }

    @Override
    public String getRelationshipServiceWdsl() {
        return getString(KEY_RELATIONSHIP_SERVICE_WSDL);
    }

    @Override
    public String getPolicyServiceWdsl() {
        return getString(KEY_POLICY_SERVICE_WSDL);
    }

    @Override
    public String getAclServiceWdsl() {
        return getString(KEY_ACL_SERVICE_WSDL);
    }

    @Override
    public String getSoapVersion() {
        return getString(KEY_SOAP_VERSION);
    }

    @Override
    public String getCookies() {
        return getString(KEY_COOKIES);
    }

    @Override
    public String getCompression() {
        return getString(KEY_COMPRESSION);
    }

    @Override
    public String getCsrfHeader() {
        return getString(KEY_CSRF_HEADER);
    }

    @Override
    public String getCsrfParameter() {
        return getString(KEY_CSRF_PARAMETER);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<CmisAuthentication> getAuthentications() {
        Object authentications = get(KEY_AUTHENTICATION);

        if (authentications instanceof List) {
            return Collections.unmodifiableList((List<CmisAuthentication>) authentications);
        }

        return Collections.emptyList();
    }

    protected String getString(String key) {
        Object value = get(key);
        if (value instanceof String) {
            return (String) value;
        }

        return null;
    }
}
