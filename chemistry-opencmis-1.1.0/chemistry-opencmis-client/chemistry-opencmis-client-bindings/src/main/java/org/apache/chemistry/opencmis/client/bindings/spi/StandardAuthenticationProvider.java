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
package org.apache.chemistry.opencmis.client.bindings.spi;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.client.bindings.spi.cookies.CmisCookieManager;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.Base64;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Standard authentication provider class.
 * 
 * Adds a basic authentication HTTP header and a WS-Security UsernameToken SOAP
 * header.
 */
public class StandardAuthenticationProvider extends AbstractAuthenticationProvider {

    private static final long serialVersionUID = 1L;

    protected static final String WSSE_NAMESPACE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    protected static final String WSU_NAMESPACE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private CmisCookieManager cookieManager;
    private Map<String, List<String>> fixedHeaders = new HashMap<String, List<String>>();
    private String csrfHeader;
    private String csrfValue = "fetch";

    @Override
    public void setSession(BindingSession session) {
        super.setSession(session);

        if (getHandleCookies() && cookieManager == null) {
            cookieManager = new CmisCookieManager(session.getSessionId());
        }

        boolean sendBasicAuth = getSendBasicAuth();

        // basic authentication
        if (sendBasicAuth) {
            // get user and password
            String user = getUser();
            String password = getPassword();

            // if no user is set, don't set basic auth header
            if (user != null) {
                fixedHeaders.put("Authorization", createBasicAuthHeaderValue(user, password));
            }
        }

        boolean sendBearerToken = getSendBearerToken();

        // send bearer token
        if (sendBearerToken) {
            String token = getBearerToken();

            // if no token is set, don't set bearer header
            if (token != null) {
                fixedHeaders.put("Authorization", Collections.singletonList("Bearer " + token));
            }
        }

        // proxy authentication
        if (getProxyUser() != null) {
            // get proxy user and password
            String proxyUser = getProxyUser();
            String proxyPassword = getProxyPassword();

            fixedHeaders.put("Proxy-Authorization", createBasicAuthHeaderValue(proxyUser, proxyPassword));
        }

        // CSRF header
        csrfHeader = getCsrfHeader();

        // other headers
        addSessionParameterHeadersToFixedHeaders();
    }

    @Override
    public Map<String, List<String>> getHTTPHeaders(String url) {
        Map<String, List<String>> result = new HashMap<String, List<String>>(fixedHeaders);

        lock.readLock().lock();
        try {
            // cookies
            if (cookieManager != null) {
                Map<String, List<String>> cookies = cookieManager.get(url, result);
                if (!cookies.isEmpty()) {
                    result.putAll(cookies);
                }
            }

            // CSRF header
            if (csrfHeader != null && csrfValue != null) {
                result.put(csrfHeader, Collections.singletonList(csrfValue));
            }
        } finally {
            lock.readLock().unlock();
        }

        return result.isEmpty() ? null : result;
    }

    @Override
    public void putResponseHeaders(String url, int statusCode, Map<String, List<String>> headers) {
        lock.writeLock().lock();
        try {
            // cookies
            if (cookieManager != null) {
                if (headers != null && headers.size() > 0) {
                    cookieManager.put(url, headers);
                }
            }

            // CSRF header
            if (csrfHeader != null) {
                for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                    if (csrfHeader.equalsIgnoreCase(header.getKey())) {
                        List<String> values = header.getValue();
                        if (values != null && values.size() > 0) {
                            csrfValue = values.get(0);
                        }
                        break;
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Element getSOAPHeaders(Object portObject) {
        // only send SOAP header if configured
        if (!getSendUsernameToken()) {
            return null;
        }

        // get user and password
        String user = getUser();
        String password = getPassword();

        // if no user is set, don't create SOAP header
        if (user == null) {
            return null;
        }

        if (password == null) {
            password = "";
        }

        // set time
        long created = System.currentTimeMillis();
        long expires = created + 24 * 60 * 60 * 1000; // 24 hours

        // create the SOAP header
        try {
            Document document = XMLUtils.newDomDocument();

            Element wsseSecurityElement = document.createElementNS(WSSE_NAMESPACE, "Security");

            Element wsuTimestampElement = document.createElementNS(WSU_NAMESPACE, "Timestamp");
            wsseSecurityElement.appendChild(wsuTimestampElement);

            Element tsCreatedElement = document.createElementNS(WSU_NAMESPACE, "Created");
            tsCreatedElement.appendChild(document.createTextNode(DateTimeHelper.formatXmlDateTime(created)));
            wsuTimestampElement.appendChild(tsCreatedElement);

            Element tsExpiresElement = document.createElementNS(WSU_NAMESPACE, "Expires");
            tsExpiresElement.appendChild(document.createTextNode(DateTimeHelper.formatXmlDateTime(expires)));
            wsuTimestampElement.appendChild(tsExpiresElement);

            Element usernameTokenElement = document.createElementNS(WSSE_NAMESPACE, "UsernameToken");
            wsseSecurityElement.appendChild(usernameTokenElement);

            Element usernameElement = document.createElementNS(WSSE_NAMESPACE, "Username");
            usernameElement.appendChild(document.createTextNode(user));
            usernameTokenElement.appendChild(usernameElement);

            Element passwordElement = document.createElementNS(WSSE_NAMESPACE, "Password");
            passwordElement.appendChild(document.createTextNode(password));
            passwordElement.setAttribute("Type",
                    "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText");
            usernameTokenElement.appendChild(passwordElement);

            Element createdElement = document.createElementNS(WSU_NAMESPACE, "Created");
            createdElement.appendChild(document.createTextNode(DateTimeHelper.formatXmlDateTime(created)));
            usernameTokenElement.appendChild(createdElement);

            return wsseSecurityElement;
        } catch (Exception e) {
            // shouldn't happen...
            throw new CmisRuntimeException("Could not build SOAP header: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the HTTP headers that are sent with all requests. The returned
     * map is mutable but not synchronized!
     */
    protected Map<String, List<String>> getFixedHeaders() {
        return fixedHeaders;
    }

    /**
     * Adds the {@link SessionParameter#HEADER} to the fixed headers. This
     * method should only be called from the {@link #setSession(BindingSession)}
     * method to avoid threading issues.
     */
    protected void addSessionParameterHeadersToFixedHeaders() {
        int x = 0;
        Object headerParam;
        while ((headerParam = getSession().get(SessionParameter.HEADER + "." + x)) != null) {
            String header = headerParam.toString();
            int colon = header.indexOf(':');
            if (colon > -1) {
                String key = header.substring(0, colon).trim();
                if (key.length() > 0) {
                    String value = header.substring(colon + 1).trim();
                    List<String> values = fixedHeaders.get(key);
                    if (values == null) {
                        fixedHeaders.put(key, Collections.singletonList(value));
                    } else {
                        List<String> newValues = new ArrayList<String>(values);
                        newValues.add(value);
                        fixedHeaders.put(key, newValues);
                    }
                }
            }
            x++;
        }
    }

    /**
     * Creates a basic authentication header value from a username and a
     * password.
     */
    protected List<String> createBasicAuthHeaderValue(String username, String password) {
        if (password == null) {
            password = "";
        }

        Object charset = getSession().get(SessionParameter.AUTH_HTTP_BASIC_CHARSET);
        if (charset instanceof String) {
            charset = ((String) charset).trim();
        } else {
            charset = IOUtils.UTF8;
        }

        byte[] usernamePassword;
        try {
            usernamePassword = (username + ":" + password).getBytes((String) charset);
        } catch (UnsupportedEncodingException e) {
            throw new CmisRuntimeException("Unsupported encoding '" + charset + "'!", e);
        }

        return Collections.singletonList("Basic " + Base64.encodeBytes(usernamePassword));
    }

    /**
     * Returns if a HTTP Basic Authentication header should be sent. (All
     * bindings.)
     */
    protected boolean getSendBasicAuth() {
        return getSession().get(SessionParameter.AUTH_HTTP_BASIC, false);
    }

    /**
     * Returns if an OAuth Bearer token header should be sent. (All bindings.)
     */
    protected boolean getSendBearerToken() {
        return getSession().get(SessionParameter.AUTH_OAUTH_BEARER, false);
    }

    /**
     * Returns if a UsernameToken should be sent. (Web Services binding only.)
     */
    protected boolean getSendUsernameToken() {
        return getSession().get(SessionParameter.AUTH_SOAP_USERNAMETOKEN, false);
    }

    /**
     * Returns if the authentication provider should handle cookies.
     */
    protected boolean getHandleCookies() {
        Object value = getSession().get(SessionParameter.COOKIES);

        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        } else if (value == null) {
            return getCsrfHeader() != null;
        }

        return false;
    }
}
