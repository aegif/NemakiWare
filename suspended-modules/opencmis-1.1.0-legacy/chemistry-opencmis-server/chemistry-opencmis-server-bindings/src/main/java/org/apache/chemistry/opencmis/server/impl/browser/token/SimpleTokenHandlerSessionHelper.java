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
package org.apache.chemistry.opencmis.server.impl.browser.token;

import java.net.URL;
import java.security.SecureRandom;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public class SimpleTokenHandlerSessionHelper {

    public static final String ATTR_CMIS_USER = "cmis-token.user";
    public static final String ATTR_CMIS_AUTH_TIMESTAMP = "cmis-token.timestamp";
    public static final String ATTR_CMIS_TOKEN = "cmis-token.token";
    public static final String ATTR_CMIS_LOGIN_KEY = "cmis-token.token.loginkey";
    public static final String ATTR_CMIS_FORM_KEY = "cmis-token.formkey";
    public static final String ATTR_CMIS_APP_URL = "cmis-token.appurl";
    public static final String ATTR_CMIS_APP_KEY = "cmis-token.appkey";

    public static final String ATTR_SEPARATOR = "\n";

    public static final String PARAM_KEY = "key";
    public static final String PARAM_TOKEN = "token";
    public static final String PARAM_URL = "url";
    public static final String PARAM_USER = "user";
    public static final String PARAM_PASSWORD = "password";
    public static final String PARAM_TRUSTAPP = "trustapp";

    public static final int APP_ID_BYTES = 10;
    public static final int APP_ID_LENGTH = APP_ID_BYTES * 2;
    public static final int KEY_BYTES = 20;
    public static final int KEY_LENGTH = KEY_BYTES * 2;

    public static String getApplicationIdFromKey(String key) {
        if (key == null || key.length() != APP_ID_LENGTH + KEY_LENGTH) {
            return null;
        }

        return key.substring(0, APP_ID_LENGTH);
    }

    public static String getLoginKey(HttpServletRequest request, String appId) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return null;
        }

        return (String) hs.getAttribute(ATTR_CMIS_LOGIN_KEY + appId);
    }

    public static void setLoginKey(HttpServletRequest request, String loginKey, String formKey, URL appURL) {
        HttpSession hs = request.getSession();

        String appId = getApplicationIdFromKey(loginKey);

        hs.setAttribute(ATTR_CMIS_LOGIN_KEY + appId, loginKey);
        hs.setAttribute(ATTR_CMIS_FORM_KEY + appId, formKey);
        hs.setAttribute(ATTR_CMIS_APP_URL + appId, appURL);
    }

    public static boolean checkLoginKey(HttpServletRequest request) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return false;
        }

        String key = getKey(request);

        if (key == null) {
            return false;
        }

        String appId = getApplicationIdFromKey(key);
        if (appId == null) {
            return false;
        }

        return key.equals(hs.getAttribute(ATTR_CMIS_LOGIN_KEY + appId));
    }

    public static void removeLoginKey(HttpServletRequest request, String appId) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return;
        }

        hs.removeAttribute(ATTR_CMIS_LOGIN_KEY + appId);
    }

    public static boolean checkFormKey(HttpServletRequest request) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return false;
        }

        String formKey = getKey(request);

        if (formKey == null) {
            return false;
        }

        String appId = getApplicationIdFromKey(formKey);
        if (appId == null) {
            return false;
        }

        return formKey.equals(hs.getAttribute(ATTR_CMIS_FORM_KEY + appId));
    }

    public static void removeFormKey(HttpServletRequest request, String appId) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return;
        }

        hs.removeAttribute(ATTR_CMIS_FORM_KEY + appId);
    }

    public static String getUser(HttpServletRequest request, String appId) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return null;
        }

        return (String) hs.getAttribute(ATTR_CMIS_USER + appId);
    }

    public static void setUser(HttpServletRequest request, String appId, String user) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return;
        }

        hs.setAttribute(ATTR_CMIS_USER + appId, user);
        hs.setAttribute(ATTR_CMIS_AUTH_TIMESTAMP + appId, System.currentTimeMillis());
    }

    public static String getApplicationKey(HttpServletRequest request, String appId) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return null;
        }

        return (String) hs.getAttribute(ATTR_CMIS_APP_KEY + appId);
    }

    public static void setApplicationKey(HttpServletRequest request, String key) {
        HttpSession hs = request.getSession();

        String appId = getApplicationIdFromKey(key);

        hs.setAttribute(ATTR_CMIS_APP_KEY + appId, key);
    }

    public static boolean checkApplicationKey(HttpServletRequest request) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return false;
        }

        String key = getKey(request);

        if (key == null) {
            return false;
        }

        String appId = getApplicationIdFromKey(key);
        if (appId == null) {
            return false;
        }

        return key.equals(hs.getAttribute(ATTR_CMIS_APP_KEY + appId));
    }

    public static void removeApplicationKey(HttpServletRequest request, String appId) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return;
        }

        hs.removeAttribute(ATTR_CMIS_APP_KEY + appId);
        hs.removeAttribute(ATTR_CMIS_APP_URL + appId);
        hs.removeAttribute(ATTR_CMIS_USER + appId);
        hs.removeAttribute(ATTR_CMIS_AUTH_TIMESTAMP + appId);
    }

    public static URL getApplicationURL(HttpServletRequest request, String appId) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return null;
        }

        return (URL) hs.getAttribute(ATTR_CMIS_APP_URL + appId);
    }

    /**
     * Retrieves the token from the requests and tests if this token belongs to
     * the current session. If the token is associated with the session, the
     * token will be removed from the session and cannot be reused again.
     * 
     * @return <code>true</code> if the request contains a token and this token
     *         belongs to the session, <code>false</code> otherwise
     */
    public static boolean testAndInvalidateToken(HttpServletRequest request) {
        HttpSession hs = request.getSession(false);
        if (hs == null) {
            return false;
        }

        String token = getToken(request);

        if (token == null) {
            return false;
        }

        String tokenKey = ATTR_CMIS_TOKEN + token;

        Long tokenCreationTimestamp = (Long) hs.getAttribute(tokenKey);
        if (tokenCreationTimestamp != null) {
            // if the token exists, remove it
            hs.removeAttribute(tokenKey);

            // PARANOIA: don't accept tokens that are older than 8 hours
            return System.currentTimeMillis() - tokenCreationTimestamp < 8 * 60 * 60 * 1000;
        }

        return false;
    }

    /**
     * Adds a token to the session.
     */
    public static void addToken(HttpServletRequest request, String token) {
        HttpSession hs = request.getSession();

        String tokenKey = ATTR_CMIS_TOKEN + token;

        // set the token and retain creation timestamp
        hs.setAttribute(tokenKey, System.currentTimeMillis());
    }

    /**
     * Gets the key parameter from the request and checks its validity.
     */
    public static String getKey(HttpServletRequest request) {
        String key = request.getParameter(PARAM_KEY);
        return normalizeKey(key);
    }

    /**
     * Gets the domain parameter from the request and checks its validity.
     */
    public static String getToken(HttpServletRequest request) {
        String token = request.getParameter(PARAM_TOKEN);
        return normalizeKey(token);
    }

    public static String normalizeKey(String key) {
        if (key == null) {
            return null;
        }

        key = key.trim();

        if (key.length() != APP_ID_LENGTH + KEY_LENGTH || !key.matches("^[0-9a-f]+$")) {
            return null;
        }

        return key;
    }

    public static String generateAppId() {
        SecureRandom random = new SecureRandom();

        byte[] bytes = new byte[APP_ID_BYTES];
        random.nextBytes(bytes);

        StringBuilder sb = new StringBuilder(APP_ID_BYTES * 2);

        for (byte b : bytes) {
            String s = Integer.toHexString(b & 0xff);
            if (s.length() < 2) {
                sb.append('0');
            }
            sb.append(s);
        }

        return sb.toString();
    }

    public static String generateKey(String appId) {
        SecureRandom random = new SecureRandom();

        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);

        StringBuilder sb = new StringBuilder(appId + KEY_BYTES * 2);

        for (byte b : bytes) {
            String s = Integer.toHexString(b & 0xff);
            if (s.length() < 2) {
                sb.append('0');
            }
            sb.append(s);
        }

        return sb.toString();
    }
}
