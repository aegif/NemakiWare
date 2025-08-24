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
package org.apache.chemistry.opencmis.inmemory.query;

import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.COMPLEX_TYPE;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.FOLDER_TYPE;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.PROP_ID_BOOLEAN;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.PROP_ID_DATETIME;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.PROP_ID_DECIMAL;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.PROP_ID_INT;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.PROP_ID_STRING;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.PROP_ID_STRING_MULTI_VALUE;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.SECONDARY_INTEGER_PROP;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.SECONDARY_STRING_PROP;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.SECONDARY_TYPE;
import static org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator.VERSION_PROPERTY_ID;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BindingsObjectFactoryImpl;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.chemistry.opencmis.commons.spi.ObjectService;
import org.apache.chemistry.opencmis.commons.spi.VersioningService;
import org.apache.chemistry.opencmis.inmemory.UnitTestTypeSystemCreator;
import org.apache.chemistry.opencmis.inmemory.storedobj.impl.ContentStreamDataImpl;

/**
 * Utility class that fills the in-memory repository with some test objects that
 * can be used for query
 * 
 *         This class uses the following data for query testing. We have one
 *         document type "ComplexType" and one folder type "FolderType" The
 *         document type has one property of each of the types boolean, integer,
 *         decimal, string and datetime. id, uri and html are treated like a
 *         string and do not make a difference.
 * 
 *         String Int Double DateTime Boolean
 *         ------------------------------------------------ Alpha -100 -1.6E-5
 *         23.05.1618 true Beta -50 -4.0E24 08.05.1945 false Gamma 0 3.141592
 *         (now) true Delta 50 1.23456E-6 20.01.2038 true Epsilon 100 1.2345E12
 *         14.07.2345 false
 * 
 *         For folder and tree tests this series is put in each of the three
 *         test folders
 */
public class QueryTestDataCreator {

    private final BindingsObjectFactory fFactory = new BindingsObjectFactoryImpl();
    private final String rootFolderId;
    private final String repositoryId;
    private final ObjectService fObjSvc;
    private final VersioningService fVerSvc;
    private String doc1, doc2, doc3, doc4, doc5;
    private String folder1;
    private String folder2;
    private String folder11;
    private static final TimeZone TZ = TimeZone.getTimeZone("Zulu");

    public QueryTestDataCreator(String repositoryId, String rootFolderId, ObjectService objSvc, VersioningService verSvc) {
        this.rootFolderId = rootFolderId;
        this.repositoryId = repositoryId;
        fObjSvc = objSvc;
        fVerSvc = verSvc;
    }

    public String getFolder1() {
        return folder1;
    }

    public String getFolder2() {
        return folder2;
    }

    public String getFolder11() {
        return folder11;
    }

    public void createBasicTestData() {
        createTestFolders();
        createBasicTestDocuments();
    }

    @SuppressWarnings("serial")
    public void createBasicTestDocuments() {

        final GregorianCalendar gc1 = new GregorianCalendar(TZ);
        gc1.clear();
        gc1.set(1945, 4, 8);

        final Map<String, Object> propertyMap1 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "Alpha");
                put(PROP_ID_INT, Integer.valueOf(-100));
                put(PROP_ID_DECIMAL, Double.valueOf(-4.0E24d));
                put(PROP_ID_DATETIME, gc1);
                put(PROP_ID_BOOLEAN, true);
            }
        };
        ContentStream content1 = createContent("I have a cat.");
        doc1 = createDocument("alpha", rootFolderId, COMPLEX_TYPE, propertyMap1, content1);
        assertNotNull(doc1);

        final GregorianCalendar gc2 = new GregorianCalendar(TZ);
        gc2.clear();
        gc2.set(1618, 4, 23);

        final Map<String, Object> propertyMap2 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "Beta");
                put(PROP_ID_INT, Integer.valueOf(-50));
                put(PROP_ID_DECIMAL, Double.valueOf(-1.6E-5d));
                put(PROP_ID_DATETIME, gc2);
                put(PROP_ID_BOOLEAN, false);
            }
        };
        ContentStream content2 = createContent("I have a cat named Kitty Katty.");
        doc2 = createDocument("beta", rootFolderId, COMPLEX_TYPE, propertyMap2, content2);
        assertNotNull(doc2);

        final Map<String, Object> propertyMap3 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "Gamma");
                put(PROP_ID_INT, Integer.valueOf(0));
                put(PROP_ID_DECIMAL, Double.valueOf(Math.PI));
                put(PROP_ID_DATETIME, new GregorianCalendar(TZ));
                put(PROP_ID_BOOLEAN, true);
            }
        };

        ContentStream content3 = createContent("I have a dog.");
        doc3 = createDocument("gamma", rootFolderId, COMPLEX_TYPE, propertyMap3, content3);
        assertNotNull(doc3);

        final GregorianCalendar gc4 = new GregorianCalendar(TZ);
        gc4.clear();
        gc4.set(2038, 0, 20);

        final Map<String, Object> propertyMap4 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "Delta");
                put(PROP_ID_INT, Integer.valueOf(50));
                put(PROP_ID_DECIMAL, Double.valueOf(1.23456E-6));
                put(PROP_ID_DATETIME, gc4);
                put(PROP_ID_BOOLEAN, true);
            }
        };
        ContentStream content4 = createContent("I have a cat and a dog.");
        doc4 = createDocument("delta", rootFolderId, COMPLEX_TYPE, propertyMap4, content4);
        assertNotNull(doc4);

        final GregorianCalendar gc5 = new GregorianCalendar(TZ);
        gc5.clear();
        gc5.set(2345, 6, 14);

        final Map<String, Object> propertyMap5 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "Epsilon");
                put(PROP_ID_INT, Integer.valueOf(100));
                put(PROP_ID_DECIMAL, Double.valueOf(1.2345E12));
                put(PROP_ID_DATETIME, gc5);
                put(PROP_ID_BOOLEAN, false);
            }
        };
        ContentStream content5 = createContent("I hate having pets.");
        doc5 = createDocument("epsilon", rootFolderId, COMPLEX_TYPE, propertyMap5, content5);
        assertNotNull(doc5);
        
        final Map<String, Object> propertyMap6 = new HashMap<String, Object>();
        doc4 = createDocument("John's Document", rootFolderId, BaseTypeId.CMIS_DOCUMENT.value(), propertyMap6, null);
        assertNotNull(doc4);

    }

    @SuppressWarnings("serial")
    public void createMultiValueDocuments() {
        final List<String> mvProps1 = new ArrayList<String>() {
            {
                add("red");
                add("green");
                add("blue");
            }
        };

        final Map<String, Object> propertyMap1 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING_MULTI_VALUE, mvProps1);
                put(PROP_ID_INT, Integer.valueOf(100));
            }
        };
        createDocument("mv-alpha", rootFolderId, COMPLEX_TYPE, propertyMap1);

        final List<String> mvProps2 = new ArrayList<String>() {
            {
                add("red");
                add("pink");
                add("violet");
            }
        };

        final Map<String, Object> propertyMap2 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING_MULTI_VALUE, mvProps2);
                put(PROP_ID_INT, Integer.valueOf(200));
            }
        };
        createDocument("mv-beta", rootFolderId, COMPLEX_TYPE, propertyMap2);
    }

    @SuppressWarnings("serial")
    public void createTestFolders() {
        final Map<String, Object> propertyMap1 = new HashMap<String, Object>() {
            {
                put(PROP_ID_INT, Integer.valueOf(1234));
                put(PROP_ID_STRING, "ABCD");
            }
        };
        folder1 = createFolder("Folder 1", rootFolderId, FOLDER_TYPE, propertyMap1);

        final Map<String, Object> propertyMap2 = new HashMap<String, Object>() {
            {
                put(PROP_ID_INT, Integer.valueOf(-2345));
                put(PROP_ID_STRING, "defg");
            }
        };
        folder2 = createFolder("Folder 2", rootFolderId, FOLDER_TYPE, propertyMap2);

        final Map<String, Object> propertyMap3 = new HashMap<String, Object>() {
            {
                put(PROP_ID_INT, Integer.valueOf(123));
                put(PROP_ID_STRING, "ZZZZ");
            }
        };
        folder11 = createFolder("Folder 11", folder1, FOLDER_TYPE, propertyMap3);
    }

    @SuppressWarnings("serial")
    public void createNullTestDocument() {

        final Map<String, Object> propertyMap1 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "DocumentWithNulls");
            }
        };
        createDocument("nulldoc", rootFolderId, COMPLEX_TYPE, propertyMap1);
    }

    @SuppressWarnings("serial")
    public void createLikeTestDocuments(String folderId) {

        final Map<String, Object> propertyMap1 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "ABCDEF");
            }
        };
        createDocument("likedoc1", folderId, COMPLEX_TYPE, propertyMap1);

        final Map<String, Object> propertyMap2 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "ABC123");
            }
        };
        createDocument("likedoc2", folderId, COMPLEX_TYPE, propertyMap2);

        final Map<String, Object> propertyMap3 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "123ABC");
            }
        };
        createDocument("likedoc3", folderId, COMPLEX_TYPE, propertyMap3);
    }

    @SuppressWarnings("serial")
    public String createVersionedDocument() {
        final Map<String, Object> propertyMap1 = new HashMap<String, Object>() {
            {
                put(VERSION_PROPERTY_ID, "ver123");
            }
        };

        String verIdV1 = createDocument("verdoc1", rootFolderId, UnitTestTypeSystemCreator.VERSIONED_TYPE,
                propertyMap1, VersioningState.MAJOR, null);
        ObjectData version = fObjSvc.getObject(repositoryId, verIdV1, "*", false, IncludeRelationships.NONE, null,
                false, false, null);

        // get version series id
        String verIdSer = (String) version.getProperties().getProperties().get(PropertyIds.VERSION_SERIES_ID)
                .getFirstValue();

        // create second version
        final Map<String, Object> propertyMap2 = new HashMap<String, Object>() {
            {
                put(VERSION_PROPERTY_ID, "ver456");
            }
        };
        Properties propsV2 = createDocumentProperties(null, null, propertyMap2);

        Holder<String> idHolder = new Holder<String>(verIdV1);
        Holder<Boolean> contentCopied = new Holder<Boolean>(false);
        fVerSvc.checkOut(repositoryId, idHolder, null, contentCopied);

        // Test check-in and pass content and properties
        String checkinComment = "Here comes next version.";
        fVerSvc.checkIn(repositoryId, idHolder, true, propsV2, null, checkinComment, null, null, null, null);
        // String verIdV2 = idHolder.getValue();
        return verIdSer;
    }

    @SuppressWarnings("serial")
    public void createSecondaryTestDocuments() {

        final int intPropVal1 = 100;
        final String stringPropVal1 = "Secondary Property Value";
        final int intPropVal2 = 200;
        final String stringPropVal2 = "Secondary String";

        final GregorianCalendar gc1 = new GregorianCalendar(TZ);
        gc1.clear();
        gc1.set(1945, 4, 8);

        final Map<String, Object> propertyMap1 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "Secondary");
                put(PROP_ID_INT, Integer.valueOf(-100));
                put(PROP_ID_DECIMAL, Double.valueOf(-4.0E24d));
                put(PROP_ID_DATETIME, gc1);
                put(PROP_ID_BOOLEAN, true);
                put(SECONDARY_STRING_PROP, stringPropVal1);
                put(SECONDARY_INTEGER_PROP, intPropVal1);
                put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, SECONDARY_TYPE);
            }
        };
        ContentStream content1 = createContent("Some more content.");
        doc1 = createDocument("docwithsecondary", rootFolderId, COMPLEX_TYPE, propertyMap1, content1);
        assertNotNull(doc1);

        final Map<String, Object> propertyMap2 = new HashMap<String, Object>() {
            {
                put(PROP_ID_STRING, "Secondary 2");
                put(PROP_ID_INT, Integer.valueOf(123));
                put(PROP_ID_DECIMAL, Double.valueOf(1.23E24d));
                put(PROP_ID_BOOLEAN, false);
                put(SECONDARY_STRING_PROP, stringPropVal2);
                put(SECONDARY_INTEGER_PROP, intPropVal2);
                put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, SECONDARY_TYPE);
            }
        };
        ContentStream content2 = createContent("Even still some more content.");
        doc1 = createDocument("docwithsecondary2", rootFolderId, COMPLEX_TYPE, propertyMap2, content2);
        assertNotNull(doc1);
    }

    private String createFolder(String folderName, String parentFolderId, String typeId, Map<String, Object> properties) {
        Properties props = createFolderProperties(folderName, typeId, properties);
        String id = null;
        try {
            id = fObjSvc.createFolder(repositoryId, props, parentFolderId, null, null, null, null);
            if (null == id) {
                fail("createFolder failed.");
            }
        } catch (Exception e) {
            fail("createFolder() failed with exception: " + e);
        }
        return id;
    }

    private String createDocument(String name, String folderId, String typeId, Map<String, Object> properties) {
        return createDocument(name, folderId, typeId, properties, VersioningState.NONE, null);
    }

    private String createDocument(String name, String folderId, String typeId, Map<String, Object> properties,
            ContentStream contentStream) {
        return createDocument(name, folderId, typeId, properties, VersioningState.NONE, contentStream);
    }

    private String createDocument(String name, String folderId, String typeId, Map<String, Object> properties,
            VersioningState verState, ContentStream contentStream) {
        List<String> policies = null;
        Acl addACEs = null;
        Acl removeACEs = null;
        ExtensionsData extension = null;

        Properties props = createDocumentProperties(name, typeId, properties);

        String id = null;
        try {
            id = fObjSvc.createDocument(repositoryId, props, folderId, contentStream, verState, policies, addACEs,
                    removeACEs, extension);
            if (null == id) {
                fail("createDocument failed.");
            }
        } catch (Exception e) {
            fail("createDocument() failed with exception: " + e);
        }
        return id;

    }

    private Properties createDocumentProperties(String name, String typeId, Map<String, Object> propertyMap) {
        List<PropertyData<?>> properties = new ArrayList<PropertyData<?>>();
        if (name != null) {
            properties.add(fFactory.createPropertyIdData(PropertyIds.NAME, name));
        }
        if (typeId != null) {
            properties.add(fFactory.createPropertyIdData(PropertyIds.OBJECT_TYPE_ID, typeId));
        }
        for (Map.Entry<String, Object> propEntry : propertyMap.entrySet()) {
            PropertyData<?> pd = createPropertyData(propEntry.getKey(), propEntry.getValue());
            properties.add(pd);
        }
        Properties props = fFactory.createPropertiesData(properties);
        return props;
    }

    private Properties createFolderProperties(String folderName, String typeId, Map<String, Object> propertyMap) {
        List<PropertyData<?>> properties = new ArrayList<PropertyData<?>>();
        properties.add(fFactory.createPropertyIdData(PropertyIds.NAME, folderName));
        properties.add(fFactory.createPropertyIdData(PropertyIds.OBJECT_TYPE_ID, typeId));
        for (Map.Entry<String, Object> propEntry : propertyMap.entrySet()) {
            PropertyData<?> pd = createPropertyData(propEntry.getKey(), propEntry.getValue());
            properties.add(pd);
        }
        Properties props = fFactory.createPropertiesData(properties);
        return props;
    }

    /**
     * Simplified property creation method, create Property of Boolean, String,
     * Integer, Decimal, or DataTime depending on class of value (Boolean,
     * String, Integer, Double, or GregorianCalendar. Id, Html and URI are not
     * supported
     * 
     * @param propId
     * @param value
     * @return
     */
    @SuppressWarnings("unchecked")
    private PropertyData<?> createPropertyData(String propId, Object value) {
        Class<?> clazz = value.getClass();
        if (clazz.equals(Boolean.class)) {
            return fFactory.createPropertyBooleanData(propId, (Boolean) value);
        } else if (clazz.equals(Double.class)) {
            return fFactory.createPropertyDecimalData(propId, BigDecimal.valueOf((Double) value));
        } else if (clazz.equals(Integer.class)) {
            return fFactory.createPropertyIntegerData(propId, BigInteger.valueOf((Integer) value));
        } else if (clazz.equals(String.class)) {
            return fFactory.createPropertyStringData(propId, (String) value);
        } else if (clazz.equals(GregorianCalendar.class)) {
            return fFactory.createPropertyDateTimeData(propId, (GregorianCalendar) value);
        } else if (value instanceof List) {
            clazz = ((List<?>) value).get(0).getClass();
            if (clazz.equals(Boolean.class)) {
                return fFactory.createPropertyBooleanData(propId, (List<Boolean>) value);
            } else if (clazz.equals(Double.class)) {
                return fFactory.createPropertyDecimalData(propId, (List<BigDecimal>) value);
            } else if (clazz.equals(Integer.class)) {
                return fFactory.createPropertyIntegerData(propId, (List<BigInteger>) value);
            } else if (clazz.equals(String.class)) {
                return fFactory.createPropertyStringData(propId, (List<String>) value);
            } else if (clazz.equals(GregorianCalendar.class)) {
                return fFactory.createPropertyDateTimeData(propId, (List<GregorianCalendar>) value);
            } else {
                fail("unsupported type in propery value: " + clazz);
            }
        } else {
            fail("unsupported type in propery value: " + clazz);
        }
        return null;
    }

    private ContentStream createContent(String text) {
        ContentStreamDataImpl content = new ContentStreamDataImpl(-1);
        content.setFileName("data.txt");
        content.setMimeType("text/plain");

        try {
            content.setContent(new ByteArrayInputStream(text.getBytes()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to fill content stream with data", e);
        }
        return content;
    }

}
