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
package org.apache.chemistry.opencmis.server.impl.browser;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.Base64;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.TypeCache;
import org.apache.chemistry.opencmis.commons.impl.UrlBuilder;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDecimalImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyHtmlImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyUriImpl;
import org.apache.chemistry.opencmis.commons.impl.json.JSONObject;
import org.apache.chemistry.opencmis.commons.impl.json.JSONStreamAware;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.shared.AbstractServiceCall;
import org.apache.chemistry.opencmis.server.shared.Dispatcher;

public abstract class AbstractBrowserServiceCall extends AbstractServiceCall {

    public static final String JSON_MIME_TYPE = "application/json";
    public static final String HTML_MIME_TYPE = "text/html";

    public static final String ROOT_PATH_FRAGMENT = "root";

    public static final String REPOSITORY_PLACEHOLDER = "{repositoryId}";

    /**
     * Compiles the base URL for links, collections and templates.
     */
    public UrlBuilder compileBaseUrl(HttpServletRequest request, String repositoryId) {
        String baseUrl = (String) request.getAttribute(Dispatcher.BASE_URL_ATTRIBUTE);
        if (baseUrl != null) {
            int repIdPos = baseUrl.indexOf(REPOSITORY_PLACEHOLDER);
            if (repIdPos < 0) {
                return new UrlBuilder(baseUrl);
            } else {
                return new UrlBuilder(baseUrl.substring(0, repIdPos) + repositoryId
                        + baseUrl.substring(repIdPos + REPOSITORY_PLACEHOLDER.length()));
            }
        }

        UrlBuilder url = new UrlBuilder(request.getScheme(), request.getServerName(), request.getServerPort(), null);

        url.addPath(request.getContextPath());
        url.addPath(request.getServletPath());
        url.addPathSegment(repositoryId);

        return url;
    }

    public UrlBuilder compileRepositoryUrl(HttpServletRequest request, String repositoryId) {
        return compileBaseUrl(request, repositoryId);
    }

    public UrlBuilder compileRootUrl(HttpServletRequest request, String repositoryId) {
        return compileRepositoryUrl(request, repositoryId).addPathSegment(ROOT_PATH_FRAGMENT);
    }

    public String compileObjectLocationUrl(HttpServletRequest request, String repositoryId, String objectId) {
        return compileRootUrl(request, repositoryId).addParameter(Constants.PARAM_OBJECT_ID, objectId).toString();
    }

    public String compileTypeLocationUrl(HttpServletRequest request, String repositoryId, String typeId) {
        return compileRepositoryUrl(request, repositoryId).addParameter(Constants.PARAM_TYPE_ID, typeId).toString();
    }

    /**
     * Writes JSON to the servlet response and adds a callback wrapper if
     * requested.
     */
    public void writeJSON(JSONStreamAware json, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String token = getStringParameter(request, Constants.PARAM_TOKEN);

        if (token != null && "POST".equals(request.getMethod())) {
            response.setContentType(HTML_MIME_TYPE);
            response.setContentLength(0);
        } else {
            response.setContentType(JSON_MIME_TYPE);
            response.setCharacterEncoding(IOUtils.UTF8);

            PrintWriter pw = response.getWriter();

            String callback = getStringParameter(request, Constants.PARAM_CALLBACK);
            if (callback != null) {
                if (!callback.matches("[A-Za-z0-9._\\[\\]]*")) {
                    throw new CmisInvalidArgumentException("Invalid callback name!");
                }
                pw.print(callback + "(");
            }

            json.writeJSONString(pw);

            if (callback != null) {
                pw.print(");");
            }

            pw.flush();
        }
    }

    public void writeEmpty(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentLength(0);
        response.setContentType(HTML_MIME_TYPE);
        response.getWriter().flush();
    }

    public ObjectData getSimpleObject(CmisService service, String repositoryId, String objectId) {
        return service.getObject(repositoryId, objectId, null, false, IncludeRelationships.NONE, "cmis:none", false,
                false, null);
    }

    /**
     * Sets the given HTTP status code if the surpessResponseCodes parameter is
     * not set to true; otherwise sets HTTP status code 200 (OK).
     */
    public void setStatus(HttpServletRequest request, HttpServletResponse response, int statusCode) {
        if (getBooleanParameter(request, Constants.PARAM_SUPPRESS_RESPONSE_CODES, false)) {
            statusCode = HttpServletResponse.SC_OK;
        }

        response.setStatus(statusCode);
    }

    /**
     * Transforms the transaction into a cookie name.
     */
    public String getCookieName(String token) {
        if (token == null || token.length() == 0) {
            return "cmis%";
        }

        return "cmis_" + Base64.encodeBytes(IOUtils.toUTF8Bytes(token)).replace('=', '%');
    }

    /**
     * Sets a transaction cookie.
     */
    public void setCookie(HttpServletRequest request, HttpServletResponse response, String repositoryId, String token,
            String value) {
        setCookie(request, response, repositoryId, token, value, 3600);
    }

    /**
     * Deletes a transaction cookie.
     */
    public void deleteCookie(HttpServletRequest request, HttpServletResponse response, String repositoryId, String token) {
        setCookie(request, response, repositoryId, token, "", 0);
    }

    /**
     * Sets a transaction cookie.
     */
    public void setCookie(HttpServletRequest request, HttpServletResponse response, String repositoryId, String token,
            String value, int expiry) {
        if (token != null && token.length() > 0) {
            String cookieValue = IOUtils.encodeURL(value);

            Cookie transactionCookie = new Cookie(getCookieName(token), cookieValue);
            transactionCookie.setMaxAge(expiry);
            transactionCookie.setPath(request.getContextPath() + request.getServletPath() + "/" + repositoryId);
            transactionCookie.setSecure(request.isSecure());

            response.addCookie(transactionCookie);
        }
    }

    public String createCookieValue(int code, String objectId, String ex, String message) {
        JSONObject result = new JSONObject();

        result.put("code", code);
        result.put("objectId", objectId == null ? "" : objectId);
        result.put("exception", ex == null ? "" : ex);
        result.put("message", message == null ? "" : message);

        return result.toJSONString();
    }

    public Properties createNewProperties(ControlParser controlParser, TypeCache typeCache) {
        Map<String, List<String>> properties = controlParser.getProperties();
        if (properties == null) {
            return null;
        }

        // load primary type
        List<String> objectTypeIdsValues = properties.get(PropertyIds.OBJECT_TYPE_ID);
        if (isNotEmpty(objectTypeIdsValues)) {
            TypeDefinition typeDef = typeCache.getTypeDefinition(objectTypeIdsValues.get(0));
            if (typeDef == null) {
                throw new CmisInvalidArgumentException("Invalid type: " + objectTypeIdsValues.get(0));
            }
        }

        // load secondary types
        List<String> secondaryObjectTypeIdsValues = properties.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
        if (isNotEmpty(secondaryObjectTypeIdsValues)) {
            for (String secTypeId : secondaryObjectTypeIdsValues) {
                TypeDefinition typeDef = typeCache.getTypeDefinition(secTypeId);
                if (typeDef == null) {
                    throw new CmisInvalidArgumentException("Invalid type: " + secTypeId);
                }
            }
        }

        // create properties
        PropertiesImpl result = new PropertiesImpl();
        for (Map.Entry<String, List<String>> property : properties.entrySet()) {
            PropertyDefinition<?> propDef = typeCache.getPropertyDefinition(property.getKey());
            if (propDef == null) {
                throw new CmisInvalidArgumentException(property.getKey() + " is unknown!");
            }

            result.addProperty(createPropertyData(propDef, property.getValue()));
        }

        return result;
    }

    public Properties createUpdateProperties(ControlParser controlParser, String typeId, List<String> secondaryTypeIds,
            List<String> objectIds, TypeCache typeCache) {
        Map<String, List<String>> properties = controlParser.getProperties();
        if (properties == null) {
            return null;
        }

        // load primary type
        if (typeId != null) {
            TypeDefinition typeDef = typeCache.getTypeDefinition(typeId);
            if (typeDef == null) {
                throw new CmisInvalidArgumentException("Invalid type: " + typeId);
            }
        }

        // load secondary types
        List<String> secondaryObjectTypeIdsValues = properties.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
        if (isNotEmpty(secondaryObjectTypeIdsValues)) {
            for (String secTypeId : secondaryObjectTypeIdsValues) {
                TypeDefinition typeDef = typeCache.getTypeDefinition(secTypeId);
                if (typeDef == null) {
                    throw new CmisInvalidArgumentException("Invalid type: " + secTypeId);
                }
            }
        }

        if (secondaryTypeIds != null) {
            for (String secTypeId : secondaryTypeIds) {
                TypeDefinition typeDef = typeCache.getTypeDefinition(secTypeId);
                if (typeDef == null) {
                    throw new CmisInvalidArgumentException("Invalid secondary type: " + secTypeId);
                }
            }
        }

        // create properties
        PropertiesImpl result = new PropertiesImpl();
        for (Map.Entry<String, List<String>> property : properties.entrySet()) {
            PropertyDefinition<?> propDef = typeCache.getPropertyDefinition(property.getKey());
            if (propDef == null && objectIds != null) {
                for (String objectId : objectIds) {
                    typeCache.getTypeDefinitionForObject(objectId);
                    propDef = typeCache.getPropertyDefinition(property.getKey());
                    if (propDef != null) {
                        break;
                    }
                }
            }
            if (propDef == null) {
                throw new CmisInvalidArgumentException(property.getKey() + " is unknown!");
            }

            result.addProperty(createPropertyData(propDef, property.getValue()));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private PropertyData<?> createPropertyData(PropertyDefinition<?> propDef, Object value) {

        List<String> strValues;
        if (value == null) {
            strValues = Collections.emptyList();
        } else if (value instanceof String) {
            strValues = new ArrayList<String>();
            strValues.add((String) value);
        } else {
            strValues = (List<String>) value;
        }

        PropertyData<?> propertyData = null;
        switch (propDef.getPropertyType()) {
        case STRING:
            propertyData = new PropertyStringImpl(propDef.getId(), strValues);
            break;
        case ID:
            propertyData = new PropertyIdImpl(propDef.getId(), strValues);
            break;
        case BOOLEAN:
            List<Boolean> boolValues = new ArrayList<Boolean>(strValues.size());
            for (String s : strValues) {
                boolValues.add(Boolean.valueOf(s));
            }
            propertyData = new PropertyBooleanImpl(propDef.getId(), boolValues);
            break;
        case INTEGER:
            List<BigInteger> intValues = new ArrayList<BigInteger>(strValues.size());
            try {
                for (String s : strValues) {
                    intValues.add(new BigInteger(s));
                }
            } catch (NumberFormatException e) {
                throw new CmisInvalidArgumentException(propDef.getId() + " value is not an integer value!", e);
            }
            propertyData = new PropertyIntegerImpl(propDef.getId(), intValues);
            break;
        case DECIMAL:
            List<BigDecimal> decValues = new ArrayList<BigDecimal>(strValues.size());
            try {
                for (String s : strValues) {
                    decValues.add(new BigDecimal(s));
                }
            } catch (NumberFormatException e) {
                throw new CmisInvalidArgumentException(propDef.getId() + " value is not an integer value!", e);
            }
            propertyData = new PropertyDecimalImpl(propDef.getId(), decValues);
            break;
        case DATETIME:
            List<GregorianCalendar> calValues = new ArrayList<GregorianCalendar>(strValues.size());
            for (String s : strValues) {
                GregorianCalendar cal;
                try {
                    long timestamp = Long.parseLong(s);
                    cal = new GregorianCalendar(DateTimeHelper.GMT);
                    cal.setTimeInMillis(timestamp);
                } catch (NumberFormatException e) {
                    cal = DateTimeHelper.parseXmlDateTime(s);
                }

                if (cal == null) {
                    throw new CmisInvalidArgumentException(propDef.getId() + " value is not an datetime value!");
                }

                calValues.add(cal);
            }

            propertyData = new PropertyDateTimeImpl(propDef.getId(), calValues);
            break;
        case HTML:
            propertyData = new PropertyHtmlImpl(propDef.getId(), strValues);
            break;
        case URI:
            propertyData = new PropertyUriImpl(propDef.getId(), strValues);
            break;
        default:
            assert false;
        }

        return propertyData;
    }

    public List<String> createPolicies(ControlParser controlParser) {
        return controlParser.getValues(Constants.CONTROL_POLICY);
    }

    public Acl createAddAcl(ControlParser controlParser) {
        List<String> principals = controlParser.getValues(Constants.CONTROL_ADD_ACE_PRINCIPAL);
        if (principals == null) {
            return null;
        }

        List<Ace> aces = new ArrayList<Ace>();

        int i = 0;
        for (String principalId : principals) {
            aces.add(new AccessControlEntryImpl(new AccessControlPrincipalDataImpl(principalId), controlParser
                    .getValues(Constants.CONTROL_ADD_ACE_PERMISSION, i)));
            i++;
        }

        return new AccessControlListImpl(aces);
    }

    public Acl createRemoveAcl(ControlParser controlParser) {
        List<String> principals = controlParser.getValues(Constants.CONTROL_REMOVE_ACE_PRINCIPAL);
        if (principals == null) {
            return null;
        }

        List<Ace> aces = new ArrayList<Ace>();

        int i = 0;
        for (String principalId : principals) {
            aces.add(new AccessControlEntryImpl(new AccessControlPrincipalDataImpl(principalId), controlParser
                    .getValues(Constants.CONTROL_REMOVE_ACE_PERMISSION, i)));
            i++;
        }

        return new AccessControlListImpl(aces);
    }

    public ContentStream createContentStream(HttpServletRequest request) {
        ContentStreamImpl result = null;

        if (request instanceof POSTHttpServletRequestWrapper) {
            POSTHttpServletRequestWrapper post = (POSTHttpServletRequestWrapper) request;
            if (post.getStream() != null) {
                result = new ContentStreamImpl(post.getFilename(), post.getSize(), post.getContentType(),
                        post.getStream());
            }
        }

        return result;
    }

    public String getPolicyId(ControlParser controlParser) {
        return controlParser.getValue(Constants.CONTROL_POLICY_ID);
    }
}
