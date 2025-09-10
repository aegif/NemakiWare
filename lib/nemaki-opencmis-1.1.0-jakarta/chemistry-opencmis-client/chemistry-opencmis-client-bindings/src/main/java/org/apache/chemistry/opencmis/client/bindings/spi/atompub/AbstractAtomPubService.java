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
package org.apache.chemistry.opencmis.client.bindings.spi.atompub;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.client.bindings.impl.CmisBindingsHelper;
import org.apache.chemistry.opencmis.client.bindings.impl.RepositoryInfoCache;
import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.client.bindings.spi.LinkAccess;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomAcl;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomBase;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomElement;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomEntry;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomLink;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.RepositoryWorkspace;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.ServiceDoc;
import org.apache.chemistry.opencmis.client.bindings.spi.http.HttpInvoker;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Output;
import org.apache.chemistry.opencmis.client.bindings.spi.http.Response;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
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
import org.apache.chemistry.opencmis.commons.impl.ReturnVersion;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyIdListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;

/**
 * Base class for all AtomPub client services.
 */
public abstract class AbstractAtomPubService implements LinkAccess {

    protected enum IdentifierType {
        ID, PATH
    }

    protected static final String NAME_COLLECTION = "collection";
    protected static final String NAME_URI_TEMPLATE = "uritemplate";
    protected static final String NAME_PATH_SEGMENT = "pathSegment";
    protected static final String NAME_RELATIVE_PATH_SEGMENT = "relativePathSegment";
    protected static final String NAME_NUM_ITEMS = "numItems";

    private static final String EXCEPTION_EXCEPTION_BEGIN = "<!--exception-->";
    private static final String EXCEPTION_EXCEPTION_END = "<!--/exception-->";
    private static final String EXCEPTION_MESSAGE_BEGIN = "<!--message-->";
    private static final String EXCEPTION_MESSAGE_END = "<!--/message-->";
    private static final String EXCEPTION_KEY_BEGIN = "<!--key-->";
    private static final String EXCEPTION_KEY_END = "<!--/key-->";
    private static final String EXCEPTION_VALUE_BEGIN = "<!--value-->";
    private static final String EXCEPTION_VALUE_END = "<!--/value-->";

    private BindingSession session;

    /**
     * Sets the current session.
     */
    protected void setSession(BindingSession session) {
        this.session = session;
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
     * Returns the service document URL of this session.
     */
    protected String getServiceDocURL() {
        Object url = session.get(SessionParameter.ATOMPUB_URL);
        if (url instanceof String) {
            return (String) url;
        }

        return null;
    }

    /**
     * Return the CMIS version of the given repository.
     */
    protected CmisVersion getCmisVersion(String repositoryId) {
        if (CmisBindingsHelper.getForcedCmisVersion(session) != null) {
            return CmisBindingsHelper.getForcedCmisVersion(session);
        }

        RepositoryInfoCache cache = CmisBindingsHelper.getRepositoryInfoCache(session);
        RepositoryInfo info = cache.get(repositoryId);

        if (info == null) {
            List<RepositoryInfo> infoList = getRepositoriesInternal(repositoryId);
            if (isNotEmpty(infoList)) {
                info = infoList.get(0);
                cache.put(info);
            }
        }

        return info == null ? CmisVersion.CMIS_1_0 : info.getCmisVersion();
    }

    // ---- link cache ----

    /**
     * Returns the link cache or creates a new cache if it doesn't exist.
     */
    protected LinkCache getLinkCache() {
        LinkCache linkCache = (LinkCache) getSession().get(SpiSessionParameter.LINK_CACHE);
        if (linkCache == null) {
            linkCache = new LinkCache(getSession());
            getSession().put(SpiSessionParameter.LINK_CACHE, linkCache);
        }

        return linkCache;
    }

    /**
     * Gets a link from the cache.
     */
    protected String getLink(String repositoryId, String id, String rel, String type) {
        if (repositoryId == null) {
            throw new CmisInvalidArgumentException("Repository ID must be set!");
        }

        if (id == null) {
            throw new CmisInvalidArgumentException("Object ID must be set!");
        }

        return getLinkCache().getLink(repositoryId, id, rel, type);
    }

    /**
     * Gets a link from the cache.
     */
    protected String getLink(String repositoryId, String id, String rel) {
        return getLink(repositoryId, id, rel, null);
    }

    /**
     * Gets a link from the cache if it is there or loads it into the cache if
     * it is not there.
     */
    @Override
    public String loadLink(String repositoryId, String id, String rel, String type) {
        String link = getLink(repositoryId, id, rel, type);
        if (link == null) {
            getObjectInternal(repositoryId, IdentifierType.ID, id, ReturnVersion.THIS, "cmis:objectId", Boolean.FALSE,
                    IncludeRelationships.NONE, "cmis:none", Boolean.FALSE, Boolean.FALSE, null);
            link = getLink(repositoryId, id, rel, type);
        }

        return link;
    }

    /**
     * Gets the content link from the cache if it is there or loads it into the
     * cache if it is not there.
     */
    @Override
    public String loadContentLink(String repositoryId, String id) {
        return loadLink(repositoryId, id, AtomPubParser.LINK_REL_CONTENT, null);
    }

    /**
     * Gets a rendition content link from the cache if it is there or loads it
     * into the cache if it is not there.
     */
    @Override
    public String loadRenditionContentLink(String repositoryId, String id, String streamId) {
        return loadLink(repositoryId, id, Constants.REL_ALTERNATE, streamId);
    }

    /**
     * Adds a link to the cache.
     */
    protected void addLink(String repositoryId, String id, String rel, String type, String link) {
        getLinkCache().addLink(repositoryId, id, rel, type, link);
    }

    /**
     * Adds a link to the cache.
     */
    protected void addLink(String repositoryId, String id, AtomLink link) {
        getLinkCache().addLink(repositoryId, id, link.getRel(), link.getType(), link.getHref());
    }

    /**
     * Removes all links of an object.
     */
    protected void removeLinks(String repositoryId, String id) {
        getLinkCache().removeLinks(repositoryId, id);
    }

    /**
     * Locks the link cache.
     */
    protected void lockLinks() {
        getLinkCache().lockLinks();
    }

    /**
     * Unlocks the link cache.
     */
    protected void unlockLinks() {
        getLinkCache().unlockLinks();
    }

    /**
     * Checks a link throw an appropriate exception.
     */
    protected void throwLinkException(String repositoryId, String id, String rel, String type) {
        int index = getLinkCache().checkLink(repositoryId, id, rel, type);

        switch (index) {
        case 0:
            throw new CmisObjectNotFoundException("Unknown repository!");
        case 1:
            throw new CmisObjectNotFoundException("Unknown object!");
        case 2:
            throw new CmisNotSupportedException("Operation not supported by the repository for this object!");
        case 3:
            throw new CmisNotSupportedException("No link with matching media type!");
        case 4:
            throw new CmisRuntimeException("Nothing wrong! Either this is a bug or a threading issue.");
        default:
            throw new CmisRuntimeException("Unknown error!");
        }
    }

    /**
     * Gets a type link from the cache.
     */
    protected String getTypeLink(String repositoryId, String typeId, String rel, String type) {
        if (repositoryId == null) {
            throw new CmisInvalidArgumentException("Repository ID must be set!");
        }

        if (typeId == null) {
            throw new CmisInvalidArgumentException("Type ID must be set!");
        }

        return getLinkCache().getTypeLink(repositoryId, typeId, rel, type);
    }

    /**
     * Gets a type link from the cache.
     */
    protected String getTypeLink(String repositoryId, String typeId, String rel) {
        return getTypeLink(repositoryId, typeId, rel, null);
    }

    /**
     * Gets a link from the cache if it is there or loads it into the cache if
     * it is not there.
     */
    protected String loadTypeLink(String repositoryId, String typeId, String rel, String type) {
        String link = getTypeLink(repositoryId, typeId, rel, type);
        if (link == null) {
            getTypeDefinitionInternal(repositoryId, typeId);
            link = getTypeLink(repositoryId, typeId, rel, type);
        }

        return link;
    }

    /**
     * Adds a type link to the cache.
     */
    protected void addTypeLink(String repositoryId, String typeId, String rel, String type, String link) {
        getLinkCache().addTypeLink(repositoryId, typeId, rel, type, link);
    }

    /**
     * Adds a type link to the cache.
     */
    protected void addTypeLink(String repositoryId, String typeId, AtomLink link) {
        getLinkCache().addTypeLink(repositoryId, typeId, link.getRel(), link.getType(), link.getHref());
    }

    /**
     * Removes all links of a type.
     */
    protected void removeTypeLinks(String repositoryId, String id) {
        getLinkCache().removeTypeLinks(repositoryId, id);
    }

    /**
     * Locks the type link cache.
     */
    protected void lockTypeLinks() {
        getLinkCache().lockTypeLinks();
    }

    /**
     * Unlocks the type link cache.
     */
    protected void unlockTypeLinks() {
        getLinkCache().unlockTypeLinks();
    }

    /**
     * Gets a collection from the cache.
     */
    protected String getCollection(String repositoryId, String collection) {
        return getLinkCache().getCollection(repositoryId, collection);
    }

    /**
     * Gets a collection from the cache if it is there or loads it into the
     * cache if it is not there.
     */
    protected String loadCollection(String repositoryId, String collection) {
        String link = getCollection(repositoryId, collection);
        if (link == null) {
            // cache repository info
            getRepositoriesInternal(repositoryId);
            link = getCollection(repositoryId, collection);
        }

        return link;
    }

    /**
     * Adds a collection to the cache.
     */
    protected void addCollection(String repositoryId, String collection, String link) {
        getLinkCache().addCollection(repositoryId, collection, link);
    }

    /**
     * Gets a repository link from the cache.
     */
    protected String getRepositoryLink(String repositoryId, String rel) {
        return getLinkCache().getRepositoryLink(repositoryId, rel);
    }

    /**
     * Gets a repository link from the cache if it is there or loads it into the
     * cache if it is not there.
     */
    protected String loadRepositoryLink(String repositoryId, String rel) {
        String link = getRepositoryLink(repositoryId, rel);
        if (link == null) {
            // cache repository info
            getRepositoriesInternal(repositoryId);
            link = getRepositoryLink(repositoryId, rel);
        }

        return link;
    }

    /**
     * Adds a repository link to the cache.
     */
    protected void addRepositoryLink(String repositoryId, String rel, String link) {
        getLinkCache().addRepositoryLink(repositoryId, rel, link);
    }

    /**
     * Adds a repository link to the cache.
     */
    protected void addRepositoryLink(String repositoryId, AtomLink link) {
        addRepositoryLink(repositoryId, link.getRel(), link.getHref());
    }

    /**
     * Gets an URI template from the cache.
     */
    protected String getTemplateLink(String repositoryId, String type, Map<String, Object> parameters) {
        return getLinkCache().getTemplateLink(repositoryId, type, parameters);
    }

    /**
     * Gets a template link from the cache if it is there or loads it into the
     * cache if it is not there.
     */
    protected String loadTemplateLink(String repositoryId, String type, Map<String, Object> parameters) {
        String link = getTemplateLink(repositoryId, type, parameters);
        if (link == null) {
            // cache repository info
            getRepositoriesInternal(repositoryId);
            link = getTemplateLink(repositoryId, type, parameters);
        }

        return link;
    }

    /**
     * Adds an URI template to the cache.
     */
    protected void addTemplate(String repositoryId, String type, String link) {
        getLinkCache().addTemplate(repositoryId, type, link);
    }

    // ---- exceptions ----

    /**
     * Converts a HTTP status code into an Exception.
     */
    protected CmisBaseException convertStatusCode(int code, String message, String errorContent, Throwable t) {
        String exception = extractException(errorContent);
        message = extractErrorMessage(message, errorContent);
        Map<String, String> additionalData = extractAddtionalData(errorContent);

        switch (code) {
        case 301:
        case 302:
        case 303:
        case 307:
            return new CmisConnectionException("Redirects are not supported (HTTP status code " + code + "): "
                    + message, errorContent, t);
        case 400:
            if (CmisFilterNotValidException.EXCEPTION_NAME.equals(exception)) {
                return new CmisFilterNotValidException(message, errorContent, additionalData, t);
            }
            return new CmisInvalidArgumentException(message, errorContent, additionalData, t);
        case 401:
            return new CmisUnauthorizedException(message, errorContent, additionalData, t);
        case 403:
            if (CmisStreamNotSupportedException.EXCEPTION_NAME.equals(exception)) {
                return new CmisStreamNotSupportedException(message, errorContent, additionalData, t);
            }
            return new CmisPermissionDeniedException(message, errorContent, additionalData, t);
        case 404:
            return new CmisObjectNotFoundException(message, errorContent, additionalData, t);
        case 405:
            return new CmisNotSupportedException(message, errorContent, additionalData, t);
        case 407:
            return new CmisProxyAuthenticationException(message, errorContent, additionalData, t);
        case 409:
            if (CmisContentAlreadyExistsException.EXCEPTION_NAME.equals(exception)) {
                return new CmisContentAlreadyExistsException(message, errorContent, additionalData, t);
            } else if (CmisVersioningException.EXCEPTION_NAME.equals(exception)) {
                return new CmisVersioningException(message, errorContent, additionalData, t);
            } else if (CmisUpdateConflictException.EXCEPTION_NAME.equals(exception)) {
                return new CmisUpdateConflictException(message, errorContent, additionalData, t);
            } else if (CmisNameConstraintViolationException.EXCEPTION_NAME.equals(exception)) {
                return new CmisNameConstraintViolationException(message, errorContent, additionalData, t);
            }
            return new CmisConstraintException(message, errorContent, additionalData, t);
        case 429:
            return new CmisTooManyRequestsException(message, errorContent, additionalData, t);
        case 503:
            return new CmisServiceUnavailableException(message, errorContent, additionalData, t);
        default:
            if (CmisStorageException.EXCEPTION_NAME.equals(exception)) {
                return new CmisStorageException(message, errorContent, additionalData, t);
            }
            return new CmisRuntimeException(message, errorContent, additionalData, t);
        }
    }

    protected String extractException(String errorContent) {
        if (errorContent == null) {
            return null;
        }

        int begin = errorContent.indexOf(EXCEPTION_EXCEPTION_BEGIN);
        int end = errorContent.indexOf(EXCEPTION_EXCEPTION_END);

        if (begin == -1 || end == -1 || begin > end) {
            return null;
        }

        return errorContent.substring(begin + EXCEPTION_EXCEPTION_BEGIN.length(), end);
    }

    protected String extractErrorMessage(String message, String errorContent) {
        if (errorContent == null) {
            return message;
        }

        int begin = errorContent.indexOf(EXCEPTION_MESSAGE_BEGIN);
        int end = errorContent.indexOf(EXCEPTION_MESSAGE_END);

        if (begin == -1 || end == -1 || begin > end) {
            return message;
        }

        return errorContent.substring(begin + EXCEPTION_MESSAGE_BEGIN.length(), end);
    }

    protected Map<String, String> extractAddtionalData(String errorContent) {
        if (errorContent == null) {
            return null;
        }

        Map<String, String> result = null;

        int pos = 0;

        while (true) {
            int keyBegin = errorContent.indexOf(EXCEPTION_KEY_BEGIN, pos);
            int keyEnd = errorContent.indexOf(EXCEPTION_KEY_END, pos);

            if (keyBegin == -1 || keyEnd == -1 || keyBegin > keyEnd) {
                break;
            }

            pos = keyEnd + EXCEPTION_KEY_END.length();

            int valueBegin = errorContent.indexOf(EXCEPTION_VALUE_BEGIN, pos);
            int valueEnd = errorContent.indexOf(EXCEPTION_VALUE_END, pos);

            if (valueBegin == -1 || valueEnd == -1 || valueBegin > valueEnd) {
                break;
            }

            pos = valueEnd + EXCEPTION_VALUE_END.length();

            if (result == null) {
                result = new HashMap<String, String>();
            }

            result.put(errorContent.substring(keyBegin + EXCEPTION_KEY_BEGIN.length(), keyEnd),
                    errorContent.substring(valueBegin + EXCEPTION_VALUE_BEGIN.length(), valueEnd));
        }

        return result;
    }

    // ---- helpers ----

    protected boolean is(String name, AtomElement element) {
        return name.equals(element.getName().getLocalPart());
    }

    protected boolean isStr(String name, AtomElement element) {
        return is(name, element) && (element.getObject() instanceof String);
    }

    protected boolean isInt(String name, AtomElement element) {
        return is(name, element) && (element.getObject() instanceof BigInteger);
    }

    protected boolean isNextLink(AtomElement element) {
        return Constants.REL_NEXT.equals(((AtomLink) element.getObject()).getRel());
    }

    /**
     * Creates a CMIS object with properties and policy IDs.
     */
    protected ObjectDataImpl createObject(Properties properties, String changeToken, List<String> policies) {
        ObjectDataImpl object = new ObjectDataImpl();

        boolean omitChangeToken = getSession().get(SessionParameter.OMIT_CHANGE_TOKENS, false);

        if (properties == null) {
            properties = new PropertiesImpl();
            if (changeToken != null && !omitChangeToken) {
                ((PropertiesImpl) properties)
                        .addProperty(new PropertyStringImpl(PropertyIds.CHANGE_TOKEN, changeToken));
            }
        } else {
            if (omitChangeToken) {
                if (properties.getProperties().containsKey(PropertyIds.CHANGE_TOKEN)) {
                    properties = new PropertiesImpl(properties);
                    ((PropertiesImpl) properties).removeProperty(PropertyIds.CHANGE_TOKEN);
                }
            } else {
                if (changeToken != null && !properties.getProperties().containsKey(PropertyIds.CHANGE_TOKEN)) {
                    properties = new PropertiesImpl(properties);
                    ((PropertiesImpl) properties).addProperty(new PropertyStringImpl(PropertyIds.CHANGE_TOKEN,
                            changeToken));
                }
            }
        }

        object.setProperties(properties);

        if (isNotEmpty(policies)) {
            PolicyIdListImpl policyIdList = new PolicyIdListImpl();
            policyIdList.setPolicyIds(policies);
            object.setPolicyIds(policyIdList);
        }

        return object;
    }

    /**
     * Creates a CMIS object that only contains an ID in the property list.
     */
    protected ObjectData createIdObject(String objectId) {
        ObjectDataImpl object = new ObjectDataImpl();

        PropertiesImpl properties = new PropertiesImpl();
        object.setProperties(properties);

        properties.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, objectId));

        return object;
    }

    /**
     * Parses an input stream.
     */
    @SuppressWarnings("unchecked")
    protected <T extends AtomBase> T parse(InputStream stream, Class<T> clazz) {
        AtomPubParser parser = new AtomPubParser(stream);

        try {
            parser.parse();
        } catch (Exception e) {
            throw new CmisConnectionException("Parsing exception!", e);
        }

        AtomBase parseResult = parser.getResults();

        if (!clazz.isInstance(parseResult)) {
            throw new CmisConnectionException("Unexpected document! Received: "
                    + (parseResult == null ? "something unknown" : parseResult.getType()));
        }

        return (T) parseResult;
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
        if (resp.getResponseCode() != 201) {
            throw convertStatusCode(resp.getResponseCode(), resp.getResponseMessage(), resp.getErrorContent(), null);
        }

        return resp;
    }

    /**
     * Performs a POST on an URL, checks the response code and consumes the
     * response.
     */
    protected void postAndConsume(UrlBuilder url, String contentType, Output writer) {
        Response resp = post(url, contentType, writer);
        IOUtils.consumeAndClose(resp.getStream());
    }

    /**
     * Performs a PUT on an URL, checks the response code and returns the
     * result.
     */
    protected Response put(UrlBuilder url, String contentType, Output writer) {
        return put(url, contentType, null, writer);
    }

    /**
     * Performs a PUT on an URL, checks the response code and returns the
     * result.
     */
    protected Response put(UrlBuilder url, String contentType, Map<String, String> headers, Output writer) {
        // make the call
        Response resp = getHttpInvoker().invokePUT(url, contentType, headers, writer, session);

        // check response code
        if ((resp.getResponseCode() < 200) || (resp.getResponseCode() > 299)) {
            throw convertStatusCode(resp.getResponseCode(), resp.getResponseMessage(), resp.getErrorContent(), null);
        }

        return resp;
    }

    /**
     * Performs a DELETE on an URL, checks the response code and returns the
     * result.
     */
    protected void delete(UrlBuilder url) {
        // make the call
        Response resp = getHttpInvoker().invokeDELETE(url, session);

        // check response code
        if (resp.getResponseCode() != 204) {
            throw convertStatusCode(resp.getResponseCode(), resp.getResponseMessage(), resp.getErrorContent(), null);
        }
    }

    // ---- common operations ----

    /**
     * Checks if at least one ACE list is not empty.
     */
    protected boolean isAclMergeRequired(Acl addAces, Acl removeAces) {
        return (addAces != null && isNotEmpty(addAces.getAces()))
                || (removeAces != null && isNotEmpty(removeAces.getAces()));
    }

    /**
     * Merges the new ACL from original, add and remove ACEs lists.
     */
    protected Acl mergeAcls(Acl originalAces, Acl addAces, Acl removeAces) {
        Map<String, Set<String>> originals = convertAclToMap(originalAces);
        Map<String, Set<String>> adds = convertAclToMap(addAces);
        Map<String, Set<String>> removes = convertAclToMap(removeAces);
        List<Ace> newAces = new ArrayList<Ace>();

        // iterate through the original ACEs
        for (Map.Entry<String, Set<String>> ace : originals.entrySet()) {

            // add permissions
            Set<String> addPermissions = adds.get(ace.getKey());
            if (addPermissions != null) {
                ace.getValue().addAll(addPermissions);
            }

            // remove permissions
            Set<String> removePermissions = removes.get(ace.getKey());
            if (removePermissions != null) {
                ace.getValue().removeAll(removePermissions);
            }

            // create new ACE
            if (!ace.getValue().isEmpty()) {
                newAces.add(new AccessControlEntryImpl(new AccessControlPrincipalDataImpl(ace.getKey()),
                        new ArrayList<String>(ace.getValue())));
            }
        }

        // find all ACEs that should be added but are not in the original ACE
        // list
        for (Map.Entry<String, Set<String>> ace : adds.entrySet()) {
            if (!originals.containsKey(ace.getKey()) && !ace.getValue().isEmpty()) {
                newAces.add(new AccessControlEntryImpl(new AccessControlPrincipalDataImpl(ace.getKey()),
                        new ArrayList<String>(ace.getValue())));
            }
        }

        return new AccessControlListImpl(newAces);
    }

    /**
     * Converts a list of ACEs into Map for better handling.
     */
    private static Map<String, Set<String>> convertAclToMap(Acl acl) {
        Map<String, Set<String>> result = new HashMap<String, Set<String>>();

        if (acl == null || acl.getAces() == null) {
            return result;
        }

        for (Ace ace : acl.getAces()) {
            // don't consider indirect ACEs - we can't change them
            if (!ace.isDirect()) {
                // ignore
                continue;
            }

            // although a principal must not be null, check it
            if (ace.getPrincipal() == null || ace.getPrincipal().getId() == null) {
                // ignore
                continue;
            }

            Set<String> permissions = result.get(ace.getPrincipal().getId());
            if (permissions == null) {
                permissions = new HashSet<String>();
                result.put(ace.getPrincipal().getId(), permissions);
            }

            if (ace.getPermissions() != null) {
                permissions.addAll(ace.getPermissions());
            }
        }

        return result;
    }

    /**
     * Retrieves the Service Document from the server and caches the repository
     * info objects, collections, links, URI templates, etc.
     */
    @SuppressWarnings("unchecked")
    protected List<RepositoryInfo> getRepositoriesInternal(String repositoryId) {
        List<RepositoryInfo> repInfos = new ArrayList<RepositoryInfo>();

        // retrieve service doc
        UrlBuilder url = new UrlBuilder(getServiceDocURL());
        url.addParameter(Constants.PARAM_REPOSITORY_ID, repositoryId);

        // read and parse
        Response resp = read(url);
        ServiceDoc serviceDoc = parse(resp.getStream(), ServiceDoc.class);

        // walk through the workspaces
        for (RepositoryWorkspace ws : serviceDoc.getWorkspaces()) {
            if (ws.getId() == null) {
                // found a non-CMIS workspace
                continue;
            }

            for (AtomElement element : ws.getElements()) {
                if (is(NAME_COLLECTION, element)) {
                    Map<String, String> colMap = (Map<String, String>) element.getObject();
                    addCollection(ws.getId(), colMap.get("collectionType"), colMap.get("href"));
                } else if (element.getObject() instanceof AtomLink) {
                    addRepositoryLink(ws.getId(), (AtomLink) element.getObject());
                } else if (is(NAME_URI_TEMPLATE, element)) {
                    Map<String, String> tempMap = (Map<String, String>) element.getObject();
                    addTemplate(ws.getId(), tempMap.get("type"), tempMap.get("template"));
                } else if (element.getObject() instanceof RepositoryInfo) {
                    repInfos.add((RepositoryInfo) element.getObject());
                }
            }
        }

        return repInfos;
    }

    /**
     * Retrieves an object from the server and caches the links.
     */
    protected ObjectData getObjectInternal(String repositoryId, IdentifierType idOrPath, String objectIdOrPath,
            ReturnVersion returnVersion, String filter, Boolean includeAllowableActions,
            IncludeRelationships includeRelationships, String renditionFilter, Boolean includePolicyIds,
            Boolean includeAcl, ExtensionsData extension) {

        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put(Constants.PARAM_ID, objectIdOrPath);
        parameters.put(Constants.PARAM_PATH, objectIdOrPath);
        parameters.put(Constants.PARAM_RETURN_VERSION, returnVersion);
        parameters.put(Constants.PARAM_FILTER, filter);
        parameters.put(Constants.PARAM_ALLOWABLE_ACTIONS, includeAllowableActions);
        parameters.put(Constants.PARAM_ACL, includeAcl);
        parameters.put(Constants.PARAM_POLICY_IDS, includePolicyIds);
        parameters.put(Constants.PARAM_RELATIONSHIPS, includeRelationships);
        parameters.put(Constants.PARAM_RENDITION_FILTER, renditionFilter);

        String link = loadTemplateLink(repositoryId, (idOrPath == IdentifierType.ID ? Constants.TEMPLATE_OBJECT_BY_ID
                : Constants.TEMPLATE_OBJECT_BY_PATH), parameters);
        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository!");
        }

        UrlBuilder url = new UrlBuilder(link);
        // workaround for missing template parameter in the CMIS spec
        if (returnVersion != null && returnVersion != ReturnVersion.THIS) {
            url.addParameter(Constants.PARAM_RETURN_VERSION, returnVersion);
        }

        // read and parse
        Response resp = read(url);
        AtomEntry entry = parse(resp.getStream(), AtomEntry.class);

        // we expect a CMIS entry
        if (entry.getId() == null) {
            throw new CmisConnectionException("Received Atom entry is not a CMIS entry!");
        }

        lockLinks();
        ObjectData result = null;
        try {
            // clean up cache
            removeLinks(repositoryId, entry.getId());

            // walk through the entry
            for (AtomElement element : entry.getElements()) {
                if (element.getObject() instanceof AtomLink) {
                    addLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                } else if (element.getObject() instanceof ObjectData) {
                    result = (ObjectData) element.getObject();
                }
            }
        } finally {
            unlockLinks();
        }

        return result;
    }

    /**
     * Retrieves a type definition.
     */
    protected TypeDefinition getTypeDefinitionInternal(String repositoryId, String typeId) {

        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put(Constants.PARAM_ID, typeId);

        String link = loadTemplateLink(repositoryId, Constants.TEMPLATE_TYPE_BY_ID, parameters);
        if (link == null) {
            throw new CmisObjectNotFoundException("Unknown repository!");
        }

        // read and parse
        Response resp = read(new UrlBuilder(link));
        AtomEntry entry = parse(resp.getStream(), AtomEntry.class);

        // we expect a CMIS entry
        if (entry.getId() == null) {
            throw new CmisConnectionException("Received Atom entry is not a CMIS entry!");
        }

        lockTypeLinks();
        TypeDefinition result = null;
        try {
            // clean up cache
            removeTypeLinks(repositoryId, entry.getId());

            // walk through the entry
            for (AtomElement element : entry.getElements()) {
                if (element.getObject() instanceof AtomLink) {
                    addTypeLink(repositoryId, entry.getId(), (AtomLink) element.getObject());
                } else if (element.getObject() instanceof TypeDefinition) {
                    result = (TypeDefinition) element.getObject();
                }
            }
        } finally {
            unlockTypeLinks();
        }

        return result;
    }

    /**
     * Retrieves the ACL of an object.
     */
    public Acl getAclInternal(String repositoryId, String objectId, Boolean onlyBasicPermissions,
            ExtensionsData extension) {

        // find the link
        String link = loadLink(repositoryId, objectId, Constants.REL_ACL, Constants.MEDIATYPE_ACL);

        if (link == null) {
            throwLinkException(repositoryId, objectId, Constants.REL_ACL, Constants.MEDIATYPE_ACL);
        }

        UrlBuilder url = new UrlBuilder(link);
        url.addParameter(Constants.PARAM_ONLY_BASIC_PERMISSIONS, onlyBasicPermissions);

        // read and parse
        Response resp = read(url);
        AtomAcl acl = parse(resp.getStream(), AtomAcl.class);

        return acl.getACL();
    }

    /**
     * Updates the ACL of an object.
     */
    protected AtomAcl updateAcl(String repositoryId, String objectId, final Acl acl, AclPropagation aclPropagation) {

        // find the link
        String link = loadLink(repositoryId, objectId, Constants.REL_ACL, Constants.MEDIATYPE_ACL);

        if (link == null) {
            throwLinkException(repositoryId, objectId, Constants.REL_ACL, Constants.MEDIATYPE_ACL);
        }

        UrlBuilder aclUrl = new UrlBuilder(link);
        aclUrl.addParameter(Constants.PARAM_ACL_PROPAGATION, aclPropagation);

        final CmisVersion cmisVersion = getCmisVersion(repositoryId);

        // update
        Response resp = put(aclUrl, Constants.MEDIATYPE_ACL, new Output() {
            @Override
            public void write(OutputStream out) throws Exception {
                XMLStreamWriter writer = XMLUtils.createWriter(out);
                XMLUtils.startXmlDocument(writer);
                XMLConverter.writeAcl(writer, cmisVersion, true, acl);
                XMLUtils.endXmlDocument(writer);
            }
        });

        // parse new entry
        return parse(resp.getStream(), AtomAcl.class);
    }

}
