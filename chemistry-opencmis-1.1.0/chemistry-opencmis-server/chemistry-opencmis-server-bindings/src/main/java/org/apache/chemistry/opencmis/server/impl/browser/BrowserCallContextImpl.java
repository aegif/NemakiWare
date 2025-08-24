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

import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.apache.chemistry.opencmis.server.shared.TempStoreOutputStreamFactory;

/**
 * A {@link CallContext} implementation that holds browser binding specific
 * values
 */
public class BrowserCallContextImpl extends CallContextImpl {

    private static final long serialVersionUID = 1L;
    
    private CmisService service;
    private String objectId;
    private String[] pathFragments;
    private String typeId;
    private BaseTypeId baseTypeId;
    private String token;

    public BrowserCallContextImpl(String binding, CmisVersion cmisVersion, String repositoryId,
            ServletContext servletContext, HttpServletRequest request, HttpServletResponse response,
            CmisServiceFactory factory, TempStoreOutputStreamFactory streamFactory) {
        super(binding, cmisVersion, repositoryId, servletContext, request, response, factory, streamFactory);
    }

    /**
     * Sets the necessary details to retrieve the object id, type id, and token
     * if requested.
     */
    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    public void setCallDetails(CmisService service, String objectId, String[] pathFragments, String token) {
        this.service = service;
        this.objectId = objectId;
        this.pathFragments = pathFragments;
        this.token = token;
    }

    /**
     * Returns the token.
     */
    public String getToken() {
        return token;
    }

    /**
     * Returns the object id of the current object. If the object is unknown,
     * that is the object has been addressed by path, the object is loaded to
     * get the object id.
     */
    public String getObjectId() {
        if (objectId != null) {
            return objectId;
        }

        loadObject();

        return objectId;
    }

    /**
     * Returns the type id of the current object.
     */
    public String getTypeId() {
        return typeId;
    }

    /**
     * Returns the base type id of the current object. If the base type is
     * unknown, the object is loaded to get the base type id.
     */
    public BaseTypeId getBaseTypeId() {
        if (baseTypeId != null) {
            return baseTypeId;
        }

        loadObject();

        return baseTypeId;
    }

    /**
     * Loads the object.
     */
    private void loadObject() {
        ObjectData object = null;

        if (objectId != null) {
            object = service.getObject(getRepositoryId(), objectId, "cmis:objectId,cmis:objectTypeId,cmis:baseTypeId",
                    false, IncludeRelationships.NONE, "cmis:none", false, false, null);
        } else if (pathFragments != null) {
            object = service.getObjectByPath(getRepositoryId(), getPath(),
                    "cmis:objectId,cmis:objectTypeId,cmis:baseTypeId", false, IncludeRelationships.NONE, "cmis:none",
                    false, false, null);
        } else {
            // this is a repository URL call without object id
            // -> there is nothing to load
            return;
        }

        objectId = object.getId();
        typeId = getStringPropertyValue(object, PropertyIds.OBJECT_TYPE_ID);
        baseTypeId = BaseTypeId.fromValue(getStringPropertyValue(object, PropertyIds.BASE_TYPE_ID));
    }

    /**
     * Builds the object path.
     */
    private String getPath() {
        if (pathFragments.length < 2) {
            throw new CmisRuntimeException("Internal error!");
        }
        if (pathFragments.length == 2) {
            return "/";
        }

        StringBuilder sb = new StringBuilder(128);
        for (int i = 2; i < pathFragments.length; i++) {
            if (pathFragments[i].length() == 0) {
                continue;
            }

            sb.append('/');
            sb.append(pathFragments[i]);
        }

        return sb.toString();
    }

    /**
     * Extracts a property from an object.
     */
    protected String getStringPropertyValue(ObjectData object, String name) {
        if (object == null) {
            return null;
        }

        Properties propData = object.getProperties();
        if (propData == null) {
            return null;
        }

        Map<String, PropertyData<?>> properties = propData.getProperties();
        if (properties == null) {
            return null;
        }

        PropertyData<?> property = properties.get(name);
        if (property == null) {
            return null;
        }

        Object value = property.getFirstValue();
        if (!(value instanceof String)) {
            return null;
        }

        return (String) value;
    }
}
