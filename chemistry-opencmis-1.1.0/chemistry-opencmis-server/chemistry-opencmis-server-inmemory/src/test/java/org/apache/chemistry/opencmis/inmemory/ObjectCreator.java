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
package org.apache.chemistry.opencmis.inmemory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;
import org.junit.Assert;

public class ObjectCreator {

    private final BindingsObjectFactory fFactory;
    private final ObjectService fObjSvc;
    private final String fRepositoryId;

    public ObjectCreator(BindingsObjectFactory factory, ObjectService objSvc, String repositoryId) {
        fObjSvc = objSvc;
        fFactory = factory;
        fRepositoryId = repositoryId;
    }

    public String createDocument(String name, String typeId, String folderId, VersioningState versioningState,
            Map<String, String> propsToSet) {
        ContentStream contentStream = null;
        List<String> policies = null;
        Acl addACEs = null;
        Acl removeACEs = null;
        ExtensionsData extension = null;

        Properties props = createStringDocumentProperties(name, typeId, propsToSet);

        contentStream = createContent();

        String id = null;
        id = fObjSvc.createDocument(fRepositoryId, props, folderId, contentStream, versioningState, policies, addACEs,
                removeACEs, extension);
        if (null == id) {
            Assert.fail("createDocument failed.");
        }

        return id;
    }

    public Properties createStringDocumentProperties(String name, String typeId, Map<String, String> propsToSet) {
        List<PropertyData<?>> properties = new ArrayList<PropertyData<?>>();
        properties.add(fFactory.createPropertyIdData(PropertyIds.NAME, name));
        properties.add(fFactory.createPropertyIdData(PropertyIds.OBJECT_TYPE_ID, typeId));
        if (null != propsToSet) {
            for (Entry<String, String> propToSet : propsToSet.entrySet()) {
                properties.add(fFactory.createPropertyStringData(propToSet.getKey(), propToSet.getValue()));
            }
        }
        Properties props = fFactory.createPropertiesData(properties);
        return props;
    }

    public ContentStream createContent() {
        ContentStreamImpl content = new ContentStreamImpl();
        content.setFileName("data.txt");
        content.setMimeType("text/plain");
        int len = 32 * 1024;
        byte[] b = { 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d, 0x6e, 0x0c, 0x0a,
                0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x6b, 0x6c, 0x6d, 0x6e, 0x0c, 0x0a }; // 32
        // Bytes
        ByteArrayOutputStream ba = new ByteArrayOutputStream(len);
        try {
            for (int i = 0; i < 1024; i++) {
                ba.write(b);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to fill content stream with data", e);
        }
        content.setStream(new ByteArrayInputStream(ba.toByteArray()));
        content.setLength(BigInteger.valueOf(len));
        return content;
    }

    public ContentStream createAlternateContent() {
        ContentStreamImpl content = new ContentStreamImpl();
        content.setFileName("data.txt");
        content.setMimeType("text/plain");
        int len = 32 * 1024;
        byte[] b = { 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61,
                0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61 }; // 32
        // Bytes
        ByteArrayOutputStream ba = new ByteArrayOutputStream(len);
        try {
            for (int i = 0; i < 1024; i++) {
                ba.write(b);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to fill content stream with data", e);
        }
        content.setStream(new ByteArrayInputStream(ba.toByteArray()));
        content.setLength(BigInteger.valueOf(len));
        return content;
    }

    /**
     * Compare two streams and return true if they are equal.
     */
    public boolean verifyContent(ContentStream csd1, ContentStream csd2) {
        if (!csd1.getFileName().equals(csd2.getFileName())) {
            return false;
        }
        if (!csd1.getBigLength().equals(csd2.getBigLength())) {
            return false;
        }
        if (!csd1.getMimeType().equals(csd2.getMimeType())) {
            return false;
        }
        long len = csd1.getBigLength().longValue();
        InputStream s1 = csd1.getStream();
        InputStream s2 = csd2.getStream();
        try {
            for (int i = 0; i < len; i++) {
                int val1 = s1.read();
                int val2 = s2.read();
                if (val1 != val2) {
                    return false;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public void updateProperty(String id, String propertyId, String propertyValue) {
        Properties properties = getUpdatePropertyList(propertyId, propertyValue);

        Holder<String> idHolder = new Holder<String>(id);
        Holder<String> changeTokenHolder = new Holder<String>();
        fObjSvc.updateProperties(fRepositoryId, idHolder, changeTokenHolder, properties, null);
    }

    public Properties getUpdatePropertyList(String propertyId, String propertyValue) {
        List<PropertyData<?>> properties = new ArrayList<PropertyData<?>>();
        properties.add(fFactory.createPropertyStringData(propertyId, propertyValue));
        Properties newProps = fFactory.createPropertiesData(properties);
        return newProps;
    }

    public List<PropertyData<?>> getUpdatePropertyDataList(String propertyId, String propertyValue) {
        List<PropertyData<?>> properties = new ArrayList<PropertyData<?>>();
        properties.add(fFactory.createPropertyStringData(propertyId, propertyValue));
        return properties;
    }

    public boolean verifyProperty(String id, String propertyId, String propertyValue) {
        Properties props = fObjSvc.getProperties(fRepositoryId, id, "*", null);
        Map<String, PropertyData<?>> propsMap = props.getProperties();
        PropertyString pd = (PropertyString) propsMap.get(propertyId);
        return propertyValue.equals(pd.getFirstValue());
    }

}
