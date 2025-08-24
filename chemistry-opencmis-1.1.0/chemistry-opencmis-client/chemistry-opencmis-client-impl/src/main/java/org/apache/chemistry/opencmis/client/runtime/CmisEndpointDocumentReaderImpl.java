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
package org.apache.chemistry.opencmis.client.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import java.util.Map;

import org.apache.chemistry.opencmis.client.SessionParameterMap;
import org.apache.chemistry.opencmis.client.api.CmisEndpointDocumentReader;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.endpoints.CmisAuthentication;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpoint;
import org.apache.chemistry.opencmis.commons.endpoints.CmisEndpointsDocument;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.impl.endpoints.CmisEndpointsDocumentHelper;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;

public class CmisEndpointDocumentReaderImpl implements CmisEndpointDocumentReader {

    @Override
    public CmisEndpointsDocument read(URL url) throws IOException {
        try {
            return CmisEndpointsDocumentHelper.read(url);
        } catch (JSONParseException e) {
            throw new IllegalArgumentException("Not a CMIS Endpoint Document!", e);
        }
    }

    @Override
    public CmisEndpointsDocument read(File file) throws IOException {
        try {
            return CmisEndpointsDocumentHelper.read(file);
        } catch (JSONParseException e) {
            throw new IllegalArgumentException("Not a CMIS Endpoint Document!", e);
        }
    }

    @Override
    public CmisEndpointsDocument read(InputStream in) throws IOException {
        try {
            return CmisEndpointsDocumentHelper.read(in);
        } catch (JSONParseException e) {
            throw new IllegalArgumentException("Not a CMIS Endpoint Document!", e);
        }
    }

    @Override
    public CmisEndpointsDocument read(Reader in) throws IOException {
        try {
            return CmisEndpointsDocumentHelper.read(in);
        } catch (JSONParseException e) {
            throw new IllegalArgumentException("Not a CMIS Endpoint Document!", e);
        }
    }

    @Override
    public CmisEndpointsDocument read(String in) {
        try {
            return CmisEndpointsDocumentHelper.read(in);
        } catch (JSONParseException e) {
            throw new IllegalArgumentException("Not a CMIS Endpoint Document!", e);
        }
    }

    @Override
    public Map<String, String> pepareSessionParameters(CmisAuthentication authentication) {
        if (authentication == null) {
            throw new IllegalArgumentException("Authentication object must be provided!");
        }
        if (authentication.getEndpoint() == null) {
            throw new IllegalArgumentException("Authentication object has no endpoint information!");
        }

        SessionParameterMap result = new SessionParameterMap();

        CmisEndpoint endpoint = authentication.getEndpoint();

        // -- binding --
        String binding = endpoint.getBinding();

        if (CmisEndpoint.BINDING_ATOMPUB.equals(binding)) {
            result.setAtomPubBindingUrl(endpoint.getUrl());
        } else if (CmisEndpoint.BINDING_BROWSER.equals(binding)) {
            result.setBrowserBindingUrl(endpoint.getUrl());
        } else if (CmisEndpoint.BINDING_WEBSERVICES.equals(binding)) {
            if (endpoint.getUrl() != null) {
                result.setWebServicesBindingUrl(endpoint.getUrl());
            } else {
                result.put(SessionParameter.BINDING_TYPE, BindingType.WEBSERVICES.value());
                result.put(SessionParameter.WEBSERVICES_REPOSITORY_SERVICE, endpoint.getRepositoryServiceWdsl());
                result.put(SessionParameter.WEBSERVICES_NAVIGATION_SERVICE, endpoint.getNavigationServiceWdsl());
                result.put(SessionParameter.WEBSERVICES_OBJECT_SERVICE, endpoint.getObjectServiceWdsl());
                result.put(SessionParameter.WEBSERVICES_VERSIONING_SERVICE, endpoint.getVersioningServiceWdsl());
                result.put(SessionParameter.WEBSERVICES_DISCOVERY_SERVICE, endpoint.getDiscoveryServiceWdsl());
                result.put(SessionParameter.WEBSERVICES_MULTIFILING_SERVICE, endpoint.getMultifilingServiceWdsl());
                result.put(SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE, endpoint.getRelationshipServiceWdsl());
                result.put(SessionParameter.WEBSERVICES_ACL_SERVICE, endpoint.getAclServiceWdsl());
                result.put(SessionParameter.WEBSERVICES_POLICY_SERVICE, endpoint.getPolicyServiceWdsl());
            }
        }

        // -- authentication --
        if (CmisAuthentication.AUTH_NONE.equals(authentication.getType())) {
            result.setNoAuthentication();
        } else if (CmisAuthentication.AUTH_BASIC.equals(authentication.getType())) {
            result.setBasicAuthentication();
        } else if (CmisAuthentication.AUTH_USERNAME_TOKEN.equals(authentication.getType())) {
            result.setUsernameTokenAuthentication(false);
        } else if (CmisAuthentication.AUTH_OAUTH.equals(authentication.getType())) {
            result.setOAuthAuthentication();
        } else if (CmisAuthentication.AUTH_NTLM.equals(authentication.getType())) {
            result.setNtlmAuthentication();
        }

        // -- details --
        result.setCookies(authentication.requiresCookies()
                || !CmisEndpoint.COOKIES_OPTIONAL.equals(endpoint.getCookies()));
        result.setCompression(CmisEndpoint.COMPRESSION_SERVER.equals(endpoint.getCompression())
                || CmisEndpoint.COMPRESSION_BOTH.equals(endpoint.getCompression()));
        result.setClientCompression(CmisEndpoint.COMPRESSION_CLIENT.equals(endpoint.getCompression())
                || CmisEndpoint.COMPRESSION_BOTH.equals(endpoint.getCompression()));
        result.setCsrfHeader(endpoint.getCsrfHeader());

        return result;
    }
}
