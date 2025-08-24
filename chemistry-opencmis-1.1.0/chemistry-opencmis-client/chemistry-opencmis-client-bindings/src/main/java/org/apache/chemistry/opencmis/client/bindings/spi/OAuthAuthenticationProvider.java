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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.chemistry.opencmis.client.bindings.impl.ClientVersion;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.MimeHelper;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth 2.0 Authentication Provider.
 * <p>
 * This authentication provider implements OAuth 2.0 (RFC 6749) Bearer Tokens
 * (RFC 6750).
 * <p>
 * The provider can be either configured with an authorization code or with an
 * existing bearer token. Token endpoint and client ID are always required. If a
 * client secret is required depends on the authorization server.
 * <p>
 * Configuration with authorization code:
 * 
 * <pre>
 * {@code
 * SessionFactory factory = ...
 * 
 * Map<String, String> parameter = new HashMap<String, String>();
 * 
 * parameter.put(SessionParameter.ATOMPUB_URL, "http://localhost/cmis/atom");
 * parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
 * parameter.put(SessionParameter.REPOSITORY_ID, "myRepository");
 * 
 * parameter.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, "org.apache.chemistry.opencmis.client.bindings.spi.OAuthAuthenticationProvider");
 * 
 * parameter.put(SessionParameter.OAUTH_TOKEN_ENDPOINT, "https://example.com/auth/oauth/token");
 * parameter.put(SessionParameter.OAUTH_CLIENT_ID, "s6BhdRkqt3");
 * parameter.put(SessionParameter.OAUTH_CLIENT_SECRET, "7Fjfp0ZBr1KtDRbnfVdmIw");
 * 
 * parameter.put(SessionParameter.OAUTH_CODE, "abc");
 * 
 * ...
 * Session session = factory.createSession(parameter);
 * }
 * </pre>
 * 
 * <p>
 * Configuration with existing bearer token:
 * 
 * <pre>
 * {@code
 * SessionFactory factory = ...
 * 
 * Map<String, String> parameter = new HashMap<String, String>();
 * 
 * parameter.put(SessionParameter.ATOMPUB_URL, "http://localhost/cmis/atom");
 * parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
 * parameter.put(SessionParameter.REPOSITORY_ID, "myRepository");
 * 
 * parameter.put(SessionParameter.AUTHENTICATION_PROVIDER_CLASS, "org.apache.chemistry.opencmis.client.bindings.spi.OAuthAuthenticationProvider");
 *  
 * parameter.put(SessionParameter.OAUTH_TOKEN_ENDPOINT, "https://example.com/auth/oauth/token");
 * parameter.put(SessionParameter.OAUTH_CLIENT_ID, "s6BhdRkqt3");
 * parameter.put(SessionParameter.OAUTH_CLIENT_SECRET, "7Fjfp0ZBr1KtDRbnfVdmIw");
 * 
 * parameter.put(SessionParameter.OAUTH_ACCESS_TOKEN, "2YotnFZFEjr1zCsicMWpAA");
 * parameter.put(SessionParameter.OAUTH_REFRESH_TOKEN, "tGzv3JOkF0XG5Qx2TlKWIA");
 * parameter.put(SessionParameter.OAUTH_EXPIRATION_TIMESTAMP, "1388237075127");
 * 
 * ...
 * Session session = factory.createSession(parameter);
 * }
 * </pre>
 * 
 * <p>
 * Getting tokens at runtime:
 * 
 * <pre>
 * {@code
 * OAuthAuthenticationProvider authProvider = (OAuthAuthenticationProvider) session.getBinding().getAuthenticationProvider();
 * 
 * // get the current token
 * Token token = authProvider.getToken();
 * 
 * // listen for token refreshes
 * authProvider.addTokenListener(new OAuthAuthenticationProvider.TokenListener() {
 *     public void tokenRefreshed(Token token) {
 *         // do something with the new token
 *     }
 * });
 * }
 * </pre>
 * 
 * <p>
 * OAuth errors can be handled like this:
 * 
 * <pre>
 * {@code
 * try {
 *     ...
 *     // CMIS calls
 *      ...
 * } catch (CmisConnectionException connEx) {
 *     if (connEx.getCause() instanceof CmisOAuthException) {
 *         CmisOAuthException oauthEx = (CmisOAuthException) connEx.getCause();
 * 
 *         if (CmisOAuthException.ERROR_INVALID_GRANT.equals(oauthEx.getError()) ||
 *             CmisOAuthException.ERROR_INVALID_TOKEN.equals(oauthEx.getError())) {
 *             // ask the user to authenticate again
 *         } else {
 *            // a configuration or server problem
 *         }
 *     }
 * }
 * }
 * </pre>
 */
public class OAuthAuthenticationProvider extends StandardAuthenticationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthAuthenticationProvider.class);
    private static final String TOKEN_TYPE_BEARER = "bearer";

    private static final long serialVersionUID = 1L;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private Token token = null;
    private long defaultTokenLifetime = 3600;
    private List<TokenListener> tokenListeners;

    @Override
    public void setSession(BindingSession session) {
        super.setSession(session);

        if (token == null) {
            // get predefined access token
            String accessToken = null;
            if (session.get(SessionParameter.OAUTH_ACCESS_TOKEN) instanceof String) {
                accessToken = (String) session.get(SessionParameter.OAUTH_ACCESS_TOKEN);
            }

            // get predefined refresh token
            String refreshToken = null;
            if (session.get(SessionParameter.OAUTH_REFRESH_TOKEN) instanceof String) {
                refreshToken = (String) session.get(SessionParameter.OAUTH_REFRESH_TOKEN);
            }

            // get predefined expiration timestamp
            long expirationTimestamp = 0;
            if (session.get(SessionParameter.OAUTH_EXPIRATION_TIMESTAMP) instanceof String) {
                try {
                    expirationTimestamp = Long.parseLong((String) session
                            .get(SessionParameter.OAUTH_EXPIRATION_TIMESTAMP));
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            } else if (session.get(SessionParameter.OAUTH_EXPIRATION_TIMESTAMP) instanceof Number) {
                expirationTimestamp = ((Number) session.get(SessionParameter.OAUTH_EXPIRATION_TIMESTAMP)).longValue();
            }

            // get default token lifetime
            if (session.get(SessionParameter.OAUTH_DEFAULT_TOKEN_LIFETIME) instanceof String) {
                try {
                    defaultTokenLifetime = Long.parseLong((String) session
                            .get(SessionParameter.OAUTH_DEFAULT_TOKEN_LIFETIME));
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            } else if (session.get(SessionParameter.OAUTH_DEFAULT_TOKEN_LIFETIME) instanceof Number) {
                defaultTokenLifetime = ((Number) session.get(SessionParameter.OAUTH_DEFAULT_TOKEN_LIFETIME))
                        .longValue();
            }

            token = new Token(accessToken, refreshToken, expirationTimestamp);
            fireTokenListner(token);
        }
    }

    @Override
    public Map<String, List<String>> getHTTPHeaders(String url) {
        Map<String, List<String>> headers = super.getHTTPHeaders(url);
        if (headers == null) {
            headers = new HashMap<String, List<String>>();
        }

        headers.put("Authorization", Collections.singletonList("Bearer " + getAccessToken()));

        return headers;
    }

    /**
     * Returns the current token.
     * 
     * @return the current token
     */
    public Token getToken() {
        lock.readLock().lock();
        try {
            return token;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Adds a token listener.
     * 
     * @param listner
     *            the listener object
     */
    public void addTokenListener(TokenListener listner) {
        if (listner == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            if (tokenListeners == null) {
                tokenListeners = new ArrayList<OAuthAuthenticationProvider.TokenListener>();
            }

            tokenListeners.add(listner);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a token listener.
     * 
     * @param listner
     *            the listener object
     */
    public void removeTokenListener(TokenListener listner) {
        if (listner == null) {
            return;
        }

        lock.writeLock().lock();
        try {
            if (tokenListeners != null) {
                tokenListeners.remove(listner);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Lets all token listeners know that there is a new token.
     */
    protected void fireTokenListner(Token token) {
        if (tokenListeners == null) {
            return;
        }

        for (TokenListener listner : tokenListeners) {
            listner.tokenRefreshed(token);
        }
    }

    @Override
    protected boolean getSendBearerToken() {
        // the super class should not handle bearer tokens
        return false;
    }

    /**
     * Gets the access token. If no access token is present or the access token
     * is expired, a new token is requested.
     * 
     * @return the access token
     */
    protected String getAccessToken() {
        lock.writeLock().lock();
        try {
            if (token.getAccessToken() == null) {
                if (token.getRefreshToken() == null) {
                    requestToken();
                } else {
                    refreshToken();
                }
            } else if (token.isExpired()) {
                refreshToken();
            }

            return token.getAccessToken();
        } catch (CmisConnectionException ce) {
            throw ce;
        } catch (Exception e) {
            throw new CmisConnectionException("Cannot get OAuth access token: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void requestToken() throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Requesting new OAuth access token.");
        }

        makeRequest(false);

        if (LOG.isTraceEnabled()) {
            LOG.trace(token.toString());
        }
    }

    private void refreshToken() throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Refreshing OAuth access token.");
        }

        makeRequest(true);

        if (LOG.isTraceEnabled()) {
            LOG.trace(token.toString());
        }
    }

    private void makeRequest(boolean isRefresh) throws IOException {
        Object tokenEndpoint = getSession().get(SessionParameter.OAUTH_TOKEN_ENDPOINT);
        if (!(tokenEndpoint instanceof String)) {
            throw new CmisConnectionException("Token endpoint not set!");
        }

        if (isRefresh && token.getRefreshToken() == null) {
            throw new CmisConnectionException("No refresh token!");
        }

        // request token
        HttpURLConnection conn = (HttpURLConnection) (new URL(tokenEndpoint.toString())).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setAllowUserInteraction(false);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent",
                (String) getSession().get(SessionParameter.USER_AGENT, ClientVersion.OPENCMIS_USER_AGENT));
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        // compile request
        Writer writer = new OutputStreamWriter(conn.getOutputStream(), IOUtils.UTF8);

        if (isRefresh) {
            writer.write("grant_type=refresh_token");

            writer.write("&refresh_token=");
            writer.write(IOUtils.encodeURL(token.getRefreshToken()));
        } else {
            writer.write("grant_type=authorization_code");

            Object code = getSession().get(SessionParameter.OAUTH_CODE);
            if (code != null) {
                writer.write("&code=");
                writer.write(IOUtils.encodeURL(code.toString()));
            }

            Object redirectUri = getSession().get(SessionParameter.OAUTH_REDIRECT_URI);
            if (redirectUri != null) {
                writer.write("&redirect_uri=");
                writer.write(IOUtils.encodeURL(redirectUri.toString()));
            }
        }

        Object clientId = getSession().get(SessionParameter.OAUTH_CLIENT_ID);
        if (clientId != null) {
            writer.write("&client_id=");
            writer.write(IOUtils.encodeURL(clientId.toString()));
        }

        Object clientSecret = getSession().get(SessionParameter.OAUTH_CLIENT_SECRET);
        if (clientSecret != null) {
            writer.write("&client_secret=");
            writer.write(IOUtils.encodeURL(clientSecret.toString()));
        }

        writer.close();

        // connect
        conn.connect();

        // check success
        if (conn.getResponseCode() != 200) {
            JSONObject jsonResponse = parseResponse(conn);

            Object error = jsonResponse.get("error");
            String errorStr = error == null ? null : error.toString();

            Object description = jsonResponse.get("error_description");
            String descriptionStr = description == null ? null : description.toString();

            Object uri = jsonResponse.get("error_uri");
            String uriStr = uri == null ? null : uri.toString();

            if (LOG.isDebugEnabled()) {
                LOG.debug("OAuth token request failed: {}", jsonResponse.toJSONString());
            }

            throw new CmisOAuthException("OAuth token request failed" + (errorStr == null ? "" : ": " + errorStr)
                    + (descriptionStr == null ? "" : ": " + descriptionStr), errorStr, descriptionStr, uriStr);
        }

        // parse response
        JSONObject jsonResponse = parseResponse(conn);

        Object tokenType = jsonResponse.get("token_type");
        if (!(tokenType instanceof String) || !TOKEN_TYPE_BEARER.equalsIgnoreCase((String) tokenType)) {
            throw new CmisOAuthException("Unsupported OAuth token type: " + tokenType);
        }

        Object jsonAccessToken = jsonResponse.get("access_token");
        if (!(jsonAccessToken instanceof String)) {
            throw new CmisOAuthException("Invalid OAuth access_token!");
        }

        Object jsonRefreshToken = jsonResponse.get("refresh_token");
        if (jsonRefreshToken != null && !(jsonRefreshToken instanceof String)) {
            throw new CmisOAuthException("Invalid OAuth refresh_token!");
        }

        long expiresIn = defaultTokenLifetime;
        Object jsonExpiresIn = jsonResponse.get("expires_in");
        if (jsonExpiresIn != null) {
            if (jsonExpiresIn instanceof Number) {
                expiresIn = ((Number) jsonExpiresIn).longValue();
            } else if (jsonExpiresIn instanceof String) {
                try {
                    expiresIn = Long.parseLong((String) jsonExpiresIn);
                } catch (NumberFormatException nfe) {
                    throw new CmisOAuthException("Invalid OAuth expires_in value!");
                }
            } else {
                throw new CmisOAuthException("Invalid OAuth expires_in value!");
            }

            if (expiresIn <= 0) {
                expiresIn = defaultTokenLifetime;
            }
        }

        token = new Token(jsonAccessToken.toString(), (jsonRefreshToken == null ? null : jsonRefreshToken.toString()),
                expiresIn * 1000 + System.currentTimeMillis());
        fireTokenListner(token);
    }

    private JSONObject parseResponse(HttpURLConnection conn) {
        Reader reader = null;
        InputStream stream = null;
        try {
            int respCode = conn.getResponseCode();
            if (respCode == 401) {
                Map<String, Map<String, String>> challenges = MimeHelper.getChallengesFromAuthenticateHeader(conn
                        .getHeaderField("WWW-Authenticate"));

                if (challenges != null && challenges.containsKey(TOKEN_TYPE_BEARER)) {
                    Map<String, String> params = challenges.get(TOKEN_TYPE_BEARER);

                    String errorStr = params.get("error");
                    String descriptionStr = params.get("error_description");
                    String uriStr = params.get("error_uri");

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Invalid OAuth token: {}", params.toString());
                    }

                    throw new CmisOAuthException("Unauthorized" + (errorStr == null ? "" : ": " + errorStr)
                            + (descriptionStr == null ? "" : ": " + descriptionStr), errorStr, descriptionStr, uriStr);
                }

                throw new CmisOAuthException("Unauthorized!");
            }

            if (respCode >= 200 && respCode < 300) {
                stream = conn.getInputStream();
            } else {
                stream = conn.getErrorStream();
            }
            if (stream == null) {
                throw new CmisOAuthException("Invalid OAuth token response!");
            }

            reader = new InputStreamReader(stream, extractCharset(conn));
            JSONParser parser = new JSONParser();
            Object response = parser.parse(reader);

            if (!(response instanceof JSONObject)) {
                throw new CmisOAuthException("Invalid OAuth token response!");
            }

            return (JSONObject) response;
        } catch (CmisConnectionException ce) {
            throw ce;
        } catch (Exception pe) {
            throw new CmisOAuthException("Parsing the OAuth token response failed: " + pe.getMessage(), pe);
        } finally {
            IOUtils.consumeAndClose(reader);
            if (reader == null) {
                IOUtils.closeQuietly(stream);
            }
        }
    }

    private String extractCharset(HttpURLConnection conn) {
        String charset = IOUtils.UTF8;

        String contentType = conn.getContentType();
        if (contentType != null) {
            String[] parts = contentType.split(";");
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim().toLowerCase(Locale.ENGLISH);
                if (part.startsWith("charset")) {
                    int x = part.indexOf('=');
                    charset = part.substring(x + 1).trim();
                    break;
                }
            }
        }

        return charset;
    }

    /**
     * Token holder class.
     */
    public static class Token implements Serializable {

        private static final long serialVersionUID = 1L;

        private String accessToken;
        private String refreshToken;
        private long expirationTimestamp;

        public Token(String accessToken, String refreshToken, long expirationTimestamp) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expirationTimestamp = expirationTimestamp;
        }

        /**
         * Returns the access token.
         * 
         * @return the access token
         */
        public String getAccessToken() {
            return accessToken;
        }

        /**
         * Returns the refresh token.
         * 
         * @return the refresh token
         */
        public String getRefreshToken() {
            return refreshToken;
        }

        /**
         * Returns the timestamp when the access expires.
         * 
         * @return the timestamp in milliseconds since midnight, January 1, 1970
         *         UTC.
         */
        public long getExpirationTimestamp() {
            return expirationTimestamp;
        }

        /**
         * Returns whether the access token is expired or not.
         * 
         * @return {@code true} if the access token is expired, {@code false}
         *         otherwise
         */
        public boolean isExpired() {
            return System.currentTimeMillis() >= expirationTimestamp;
        }

        @Override
        public String toString() {
            return "Access token: " + accessToken + " / Refresh token: " + refreshToken + " / Expires : "
                    + expirationTimestamp;
        }
    }

    /**
     * Listener for OAuth token events.
     */
    public interface TokenListener {

        /**
         * Called when a token is requested of refreshed.
         * 
         * @param token
         *            the new token
         */
        void tokenRefreshed(Token token);
    }

    /**
     * Exception for OAuth errors.
     */
    public static class CmisOAuthException extends CmisConnectionException {

        private static final long serialVersionUID = 1L;

        // general OAuth errors
        public static final String ERROR_INVALID_REQUEST = "invalid_request";
        public static final String ERROR_INVALID_CLIENT = "invalid_client";
        public static final String ERROR_INVALID_GRANT = "invalid_grant";
        public static final String ERROR_UNAUTHORIZED_CLIENT = "unauthorized_client";
        public static final String ERROR_UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
        public static final String ERROR_INVALID_SCOPE = "invalid_scope";

        // bearer specific
        public static final String ERROR_INVALID_TOKEN = "invalid_token";

        private String error;
        private String errorDescription;
        private String errorUri;

        public CmisOAuthException() {
            super();
        }

        public CmisOAuthException(String message) {
            super(message);
        }

        public CmisOAuthException(String message, Throwable cause) {
            super(message, cause);
        }

        public CmisOAuthException(String message, String error, String errorDescription, String errorUri) {
            super(message);
            this.error = error;
            this.errorDescription = errorDescription;
            this.errorUri = errorUri;
        }

        public String getError() {
            return error;
        }

        public String getErrorDescription() {
            return errorDescription;
        }

        public String getErrorUri() {
            return errorUri;
        }
    }
}
