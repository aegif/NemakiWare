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
package org.apache.chemistry.opencmis.commons.endpoints;

import java.util.List;
import java.util.Map;

/**
 * CMIS endpoint.
 */
public interface CmisEndpoint extends Map<String, Object> {

    String KEY_DISPLAY_NAME = "displayName";
    String KEY_CMIS_VERSION = "cmisVersion";
    String KEY_BINDING = "binding";
    String KEY_URL = "url";
    String KEY_REPOSITORY_SERVICE_WSDL = "repositoryServiceWdsl";
    String KEY_NAVIGATION_SERVICE_WSDL = "navigationServiceWdsl";
    String KEY_OBJECT_SERVICE_WSDL = "objectServiceWdsl";
    String KEY_MULTIFILING_SERVICE_WSDL = "multifilingServiceWdsl";
    String KEY_DISCOVERY_SERVICE_WSDL = "discoveryServiceWdsl";
    String KEY_VERSIONING_SERVICE_WSDL = "versioningServiceWdsl";
    String KEY_RELATIONSHIP_SERVICE_WSDL = "relationshipServiceWdsl";
    String KEY_POLICY_SERVICE_WSDL = "policyServiceWdsl";
    String KEY_ACL_SERVICE_WSDL = "aclServiceWdsl";
    String KEY_SOAP_VERSION = "soapVersion";
    String KEY_COOKIES = "cookies";
    String KEY_COMPRESSION = "compression";
    String KEY_CSRF_HEADER = "csrfHeader";
    String KEY_CSRF_PARAMETER = "csrfParameter";
    String KEY_AUTHENTICATION = "authentication";

    String VERSION_1_0 = "1.0";
    String VERSION_1_1 = "1.1";

    String BINDING_WEBSERVICES = "webservices";
    String BINDING_ATOMPUB = "atompub";
    String BINDING_BROWSER = "browser";

    String SOAP_VERSION_1_1 = "1.1";
    String SOAP_VERSION_1_2 = "1.2";

    String COOKIES_REQUIRED = "required";
    String COOKIES_RECOMMENDED = "recommended";
    String COOKIES_OPTIONAL = "optional";

    String COMPRESSION_NONE = "none";
    String COMPRESSION_SERVER = "server";
    String COMPRESSION_CLIENT = "client";
    String COMPRESSION_BOTH = "both";

    String getDisplayName();

    String getCmisVersion();

    String getBinding();

    String getUrl();

    String getRepositoryServiceWdsl();

    String getNavigationServiceWdsl();

    String getObjectServiceWdsl();

    String getMultifilingServiceWdsl();

    String getDiscoveryServiceWdsl();

    String getVersioningServiceWdsl();

    String getRelationshipServiceWdsl();

    String getPolicyServiceWdsl();

    String getAclServiceWdsl();

    String getSoapVersion();

    String getCookies();

    String getCompression();

    String getCsrfHeader();

    String getCsrfParameter();

    /**
     * Returns the list of associated authentication methods.
     * 
     * @return list of authentication methods, never {@code null}
     */
    List<CmisAuthentication> getAuthentications();
}