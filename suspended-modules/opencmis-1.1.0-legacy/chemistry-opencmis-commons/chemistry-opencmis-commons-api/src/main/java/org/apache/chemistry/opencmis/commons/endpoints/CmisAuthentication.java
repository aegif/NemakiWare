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

import java.util.Map;

/**
 * CMIS endpoint authentication information.
 */
public interface CmisAuthentication extends Map<String, Object> {

    String KEY_TYPE = "type";
    String KEY_DISPLAY_NAME = "displayName";
    String KEY_DOCUMENTATION_URL = "documentationUrl";
    String KEY_PREFERENCE = "preference";

    String AUTH_NONE = "none";
    String AUTH_BASIC = "basic";
    String AUTH_USERNAME_TOKEN = "usernameToken";
    String AUTH_FORM = "form";
    String AUTH_CERT = "certificate";
    String AUTH_SAML = "saml";
    String AUTH_OAUTH = "oauth";
    String AUTH_OIDC = "oidc";
    String AUTH_NTLM = "ntlm";
    String AUTH_KERBEROS = "kerberos";
    String AUTH_LTPA = "ltpa";

    String getType();

    String getDisplayName();

    String getDocumentationUrl();

    Integer getPreference();

    /**
     * Returns the associated CMIS endpoint.
     * 
     * @return the endpoint object, never {@code null}
     */
    CmisEndpoint getEndpoint();

    /**
     * Returns if this authentication method requires cookies.
     * 
     * Implementations should take following into account:
     * <ul>
     * <li>the authentication method (some methods require cookies to work)</li>
     * <li>the binding and its cookie setting</li>
     * <li>the CSRF settings</li>
     * </ul>
     * 
     * 
     * @return {@code true} if cookies are required, {@code false} if cookies
     *         are not required (they may be recommended, though)
     */
    boolean requiresCookies();
}
