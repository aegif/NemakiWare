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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.LinkAccess;
import org.apache.chemistry.opencmis.client.bindings.spi.http.HttpInvoker;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisFilterNotValidException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNameConstraintViolationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisProxyAuthenticationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisServiceUnavailableException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStreamNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisTooManyRequestsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.JSONConstants;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryInfoBrowserBindingImpl;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.impl.json.parser.ContainerFactory;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Base class for all Browser Binding client services.
 */
public abstract class AbstractBrowserBindingService implements LinkAccess {

    protected static final ContainerFactory SIMPLE_CONTAINER_FACTORY = new ContainerFactory() {
        @Override
        public Map<String, Object> createObjectContainer() {
            return new LinkedHashMap<String, Object>();
        }

        @Override
        public List<Object> creatArrayContainer() {
            return new ArrayList<Object>();
        }
    };

    private BindingSession session;
    private boolean succint;
    private DateTimeFormat dateTimeFormat;

    /**
     * Sets the current session.
     */
    protected void setSession(BindingSession session) {
        this.session = session;

        Object succintObj = session.get(SessionParameter.BROWSER_SUCCINCT);
        this.succint = succintObj == null ? true : Boolean.parseBoolean(succintObj.toString());

        Object dateTimeFormatObj = session.get(SessionParameter.BROWSER_DATETIME_FORMAT);
        this.dateTimeFormat = dateTimeFormatObj == null ? DateTimeFormat.SIMPLE : DateTimeFormat
                .fromValue(dateTimeFormatObj.toString().toLowerCase(Locale.ENGLISH));
    }

    /**
     * Gets the current session.
     */
    protected BindingSession getSession() {
        return session;
    }

    /**
     * Gets the HTTP Invoker object.
     */
    protected HttpInvoker getHttpInvoker() {
        return CmisBindingsHelper.getHttpInvoker(session);
    }

    /**
     * Returns the service URL of this session.
     */
    protected String getServiceUrl() {
        Object url = session.get(SessionParameter.BROWSER_URL);
        if (url instanceof String) {
            return (String) url;
        }

        return null;
    }

    protected UrlBuilder getRepositoryUrl(String repositoryId, String selector) {
        UrlBuilder result = getRepositoryUrlCache().getRepositoryUrl(repositoryId, selector);

        if (result == null) {
            getRepositoriesInternal(repositoryId);
            result = getRepositoryUrlCache().getRepositoryUrl(repositoryId, selector);
        }

        if (result == null) {
            throw new CmisObjectNotFoundException("Unknown repository!");
        }

        return result;
    }

    protected UrlBuilder getRepositoryUrl(String repositoryId) {
        UrlBuilder result = getRepositoryUrlCache().getRepositoryUrl(repositoryId);

        if (result == null) {
            getRepositoriesInternal(repositoryId);
            result = getRepositoryUrlCache().getRepositoryUrl(repositoryId);
        }

        if (result == null) {
            throw new CmisObjectNotFoundException("Unknown repository!");
        }

        return result;
    }

    protected UrlBuilder getObjectUrl(String repositoryId, String objectId, String selector) {
        UrlBuilder result = getRepositoryUrlCache().getObjectUrl(repositoryId, objectId, selector);

        if (result == null) {
            getRepositoriesInternal(repositoryId);
            result = getRepositoryUrlCache().getObjectUrl(repositoryId, objectId, selector);
        }

        if (result == null) {
            throw new CmisObjectNotFoundException("Unknown repository!");
        }

        return result;
    }

    protected UrlBuilder getObjectUrl(String repositoryId, String objectId) {
        UrlBuilder result = getRepositoryUrlCache().getObjectUrl(repositoryId, objectId);

        if (result == null) {
            getRepositoriesInternal(repositoryId);
            result = getRepositoryUrlCache().getObjectUrl(repositoryId, objectId);
        }

        if (result == null) {
            throw new CmisObjectNotFoundException("Unknown repository!");
        }

        return result;
    }

    protected UrlBuilder getPathUrl(String repositoryId, String path, String selector) {
        UrlBuilder result = getRepositoryUrlCache().getPathUrl(repositoryId, path, selector);

        if (result == null) {
            getRepositoriesInternal(repositoryId);
            result = getRepositoryUrlCache().getPathUrl(repositoryId, path, selector);
        }

        if (result == null) {
            throw new CmisObjectNotFoundException("Unknown repository!");
        }

        return result;
    }

    protected boolean getSuccinct() {
        return succint;
    }

    protected String getSuccinctParameter() {
        return succint ? "true" : null;
    }

    protected DateTimeFormat getDateTimeFormat() {
        return dateTimeFormat;
    }

    protected String getDateTimeFormatParameter() {
        return dateTimeFormat == null || dateTimeFormat == DateTimeFormat.SIMPLE ? null : dateTimeFormat.value();
    }

    protected void setChangeToken(Holder<String> changeToken, ObjectData obj) {
        if (changeToken == null) {
            return;
        }

        changeToken.setValue(null);

        if (obj == null || obj.getProperties() == null || obj.getProperties().getProperties() == null) {
            return;
        }

        PropertyData<?> ct = obj.getProperties().getProperties().get(PropertyIds.CHANGE_TOKEN);
        if (ct instanceof PropertyString) {
            changeToken.setValue(((PropertyString) ct).getFirstValue());
        }
    }

    // ---- exceptions ----

    /**
     * Converts an error message or a HTTP status code into an Exception.
     */
    protected CmisBaseException convertStatusCode(int code, String message, String errorContent, Throwable t) {
        Object obj = null;
        try {
            if (errorContent != null) {
                JSONParser parser = new JSONParser();
                obj = parser.parse(errorContent);
            }
        } catch (JSONParseException pe) {
            // error content is not valid JSON -> ignore
        }

        if (obj instanceof JSONObject) {
            JSONObject json = (JSONObject) obj;
            Object jsonError = json.get(JSONConstants.ERROR_EXCEPTION);
            if (jsonError instanceof String) {
                Object jsonMessage = json.get(JSONConstants.ERROR_MESSAGE);
                if (jsonMessage != null) {
                    message = jsonMessage.toString();
                }

                Map<String, String> additionalData = null;
                for (Map.Entry<String, Object> e : json.entrySet()) {
                    if (JSONConstants.ERROR_EXCEPTION.equalsIgnoreCase(e.getKey())
                            || JSONConstants.ERROR_MESSAGE.equalsIgnoreCase(e.getKey())) {
                        continue;
                    }

                    if (additionalData == null) {
                        additionalData = new HashMap<String, String>();
                    }

                    additionalData.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
                }

                if (CmisConstraintException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisConstraintException(message, errorContent, additionalData, t);
                } else if (CmisContentAlreadyExistsException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisContentAlreadyExistsException(message, errorContent, additionalData, t);
                } else if (CmisFilterNotValidException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisFilterNotValidException(message, errorContent, additionalData, t);
                } else if (CmisInvalidArgumentException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisInvalidArgumentException(message, errorContent, additionalData, t);
                } else if (CmisNameConstraintViolationException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisNameConstraintViolationException(message, errorContent, additionalData, t);
                } else if (CmisNotSupportedException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisNotSupportedException(message, errorContent, additionalData, t);
                } else if (CmisObjectNotFoundException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisObjectNotFoundException(message, errorContent, additionalData, t);
                } else if (CmisPermissionDeniedException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisPermissionDeniedException(message, errorContent, additionalData, t);
                } else if (CmisStorageException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisStorageException(message, errorContent, additionalData, t);
                } else if (CmisStreamNotSupportedException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisStreamNotSupportedException(message, errorContent, additionalData, t);
                } else if (CmisUpdateConflictException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisUpdateConflictException(message, errorContent, additionalData, t);
                } else if (CmisVersioningException.EXCEPTION_NAME.equalsIgnoreCase((String) jsonError)) {
                    return new CmisVersioningException(message, errorContent, additionalData, t);
                } else if (code == 503) {
                    return new CmisServiceUnavailableException(message, errorContent, additionalData, t);
                }
            }
        }

        // fall back to status code
        switch (code) {
        case 301:
        case 302:
        case 303:
        case 307:
            return new CmisConnectionException("Redirects are not supported (HTTP status code " + code + "): "
                    + message, errorContent, t);
        case 400:
            return new CmisInvalidArgumentException(message, errorContent, t);
        case 401:
            return new CmisUnauthorizedException(message, errorContent, t);
        case 403:
            return new CmisPermissionDeniedException(message, errorContent, t);
        case 404:
            return new CmisObjectNotFoundException(message, errorContent, t);
        case 405:
            return new CmisNotSupportedException(message, errorContent, t);
        case 407:
            return new CmisProxyAuthenticationException(message, errorContent, t);
        case 409:
            return new CmisConstraintException(message, errorContent, t);
        case 429:
            return new CmisTooManyRequestsException(message, errorContent, t);
        case 503:
            return new CmisServiceUnavailableException(message, errorContent, t);
        default:
            return new CmisRuntimeException(message, errorContent, t);
        }
    }

    // ---- helpers ----

    /**
     * Parses an object from an input stream.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseObject(InputStream stream, String charset) {
        Object obj = parse(stream, charset, SIMPLE_CONTAINER_FACTORY);

        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }

        throw new CmisConnectionException("Unexpected object!");
    }

    /**
     * Parses an array from an input stream.
     */
    @SuppressWarnings("unchecked")
    protected List<Object> parseArray(InputStream stream, String charset) {
        Object obj = parse(stream, charset, SIMPLE_CONTAINER_FACTORY);

        if (obj instanceof List) {
            return (List<Object>) obj;
        }

        throw new CmisConnectionException("Unexpected object!");
    }

    /**
     * Parses an input stream.
     */
    protected Object parse(InputStream stream, String charset, ContainerFactory containerFactory) {

        InputStreamReader reader = null;

        Object obj = null;
        try {
            reader = new InputStreamReader(stream, charset);
            JSONParser parser = new JSONParser();
            obj = parser.parse(reader, containerFactory);
        } catch (JSONParseException e) {
            throw new CmisConnectionException("Parsing exception: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new CmisConnectionException("Parsing exception!", e);
        } finally {
            IOUtils.consumeAndClose(reader);
            if (reader == null) {
                IOUtils.closeQuietly(stream);
            }
        }

        return obj;
    }

    /**
     * Performs a GET on an URL, checks the response code and returns the
     * result.
     */
    protected Response read(UrlBuilder url) {
        // make the call
        Response resp = getHttpInvoker().invokeGET(url, session);

        // check response code
        if (resp.getResponseCode() != 200) {
            throw convertStatusCode(resp.getResponseCode(), resp.getResponseMessage(), resp.getErrorContent(), null);
        }

        return resp;
    }

    /**
     * Performs a POST on an URL, checks the response code and returns the
     * result.
     */
    protected Response post(UrlBuilder url, String contentType, Output writer) {
        // make the call
        Response resp = getHttpInvoker().invokePOST(url, contentType, writer, session);

        // check response code
        if (resp.getResponseCode() != 200 && resp.getResponseCode() != 201) {
            throw convertStatusCode(resp.getResponseCode(), resp.getResponseMessage(), resp.getErrorContent(), null);
        }

        return resp;
    }

    /**
     * Performs a POST on an URL, checks the response code and returns the
     * result.
     */
    protected void postAndConsume(UrlBuilder url, String contentType, Output writer) {
        Response resp = post(url, contentType, writer);
        IOUtils.consumeAndClose(resp.getStream());
    }

    // ---- URL ----

    /**
     * Returns the repository URL cache or creates a new cache if it doesn't
     * exist.
     */
    protected RepositoryUrlCache getRepositoryUrlCache() {
        RepositoryUrlCache repositoryUrlCache = (RepositoryUrlCache) getSession().get(
                SpiSessionParameter.REPOSITORY_URL_CACHE);
        if (repositoryUrlCache == null) {
            repositoryUrlCache = new RepositoryUrlCache();
            getSession().put(SpiSessionParameter.REPOSITORY_URL_CACHE, repositoryUrlCache);
        }

        return repositoryUrlCache;
    }

    /**
     * Retrieves the the repository info objects.
     */
    protected List<RepositoryInfo> getRepositoriesInternal(String repositoryId) {

        UrlBuilder url = null;

        if (repositoryId == null) {
            // no repository id provided -> get all
            url = new UrlBuilder(getServiceUrl());
        } else {
            // use URL of the specified repository
            url = getRepositoryUrlCache().getRepositoryUrl(repositoryId, Constants.SELECTOR_REPOSITORY_INFO);
            if (url == null) {
                // repository infos haven't been fetched yet -> get them all
                url = new UrlBuilder(getServiceUrl());
            }
        }

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        List<RepositoryInfo> repInfos = new ArrayList<RepositoryInfo>();

        for (Object jri : json.values()) {
            if (jri instanceof Map) {
                @SuppressWarnings("unchecked")
                RepositoryInfo ri = JSONConverter.convertRepositoryInfo((Map<String, Object>) jri);
                String id = ri.getId();

                if (ri instanceof RepositoryInfoBrowserBindingImpl) {
                    String repositoryUrl = ((RepositoryInfoBrowserBindingImpl) ri).getRepositoryUrl();
                    String rootUrl = ((RepositoryInfoBrowserBindingImpl) ri).getRootUrl();

                    if (id == null || repositoryUrl == null || rootUrl == null) {
                        throw new CmisConnectionException("Found invalid Repository Info! (id: " + id + ")");
                    }

                    getRepositoryUrlCache().addRepository(id, repositoryUrl, rootUrl);
                }

                repInfos.add(ri);
            } else {
                throw new CmisConnectionException("Found invalid Repository Info!");
            }
        }

        return repInfos;
    }

    /**
     * Retrieves a type definition.
     */
    protected TypeDefinition getTypeDefinitionInternal(String repositoryId, String typeId) {
        // build URL
        UrlBuilder url = getRepositoryUrl(repositoryId, Constants.SELECTOR_TYPE_DEFINITION);
        url.addParameter(Constants.PARAM_TYPE_ID, typeId);

        // read and parse
        Response resp = read(url);
        Map<String, Object> json = parseObject(resp.getStream(), resp.getCharset());

        return JSONConverter.convertTypeDefinition(json);
    }

    // ---- LinkAccess interface ----

    @Override
    public String loadLink(String repositoryId, String objectId, String rel, String type) {
        // AtomPub specific -> return null
        return null;
    }

    @Override
    public String loadContentLink(String repositoryId, String documentId) {
        UrlBuilder result = getRepositoryUrlCache().getObjectUrl(repositoryId, documentId, Constants.SELECTOR_CONTENT);
        return result == null ? null : result.toString();
    }

    @Override
    public String loadRenditionContentLink(String repositoryId, String documentId, String streamId) {
        UrlBuilder result = getRepositoryUrlCache().getObjectUrl(repositoryId, documentId, Constants.SELECTOR_CONTENT);
        if (result != null) {
            result.addParameter(Constants.PARAM_STREAM_ID, streamId);
            return result.toString();
        }

        return null;
    }
}
