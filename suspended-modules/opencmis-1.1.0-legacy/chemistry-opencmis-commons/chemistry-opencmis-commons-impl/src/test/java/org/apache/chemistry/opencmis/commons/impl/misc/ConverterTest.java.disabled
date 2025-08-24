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
package org.apache.chemistry.opencmis.commons.impl.misc;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.CapabilityJoin;
import org.apache.chemistry.opencmis.commons.enums.CapabilityQuery;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.impl.WSConverter;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AclCapabilitiesDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionDefinitionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionMappingDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryCapabilitiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryInfoImpl;
import org.junit.Test;

/**
 * Tests converter methods.
 */
public class ConverterTest extends TestCase {

    @Test
    public void testRepositoryInfo() throws Exception {
        // dry run
        RepositoryInfoImpl obj1 = new RepositoryInfoImpl();
        RepositoryInfo obj2 = WSConverter.convert(WSConverter.convert(obj1, CmisVersion.CMIS_1_1));

        assertDataObjectsEquals("RepositoryInfo", obj1, obj2);

        // values
        obj1 = new RepositoryInfoImpl();
        obj1.setChangesIncomplete(Boolean.TRUE);
        obj1.setChangesOnType(Collections.singletonList(BaseTypeId.CMIS_DOCUMENT));
        obj1.setCmisVersionSupported("1.0");
        obj1.setLatestChangeLogToken("changeLogToken");
        obj1.setPrincipalAnonymous("principalAnonymous");
        obj1.setPrincipalAnyone("principalAnyone");
        obj1.setProductName("productName");
        obj1.setProductVersion("productVersion");
        obj1.setDescription("description");
        obj1.setId("id");
        obj1.setName("name");
        obj1.setRootFolder("rootFolderId");
        obj1.setThinClientUri("thinClientUri");
        obj1.setVendorName("vendorName");

        RepositoryCapabilitiesImpl cap1 = new RepositoryCapabilitiesImpl();
        cap1.setAllVersionsSearchable(Boolean.TRUE);
        cap1.setCapabilityAcl(CapabilityAcl.DISCOVER);
        cap1.setCapabilityChanges(CapabilityChanges.ALL);
        cap1.setCapabilityContentStreamUpdates(CapabilityContentStreamUpdates.ANYTIME);
        cap1.setCapabilityJoin(CapabilityJoin.INNERANDOUTER);
        cap1.setCapabilityQuery(CapabilityQuery.BOTHCOMBINED);
        cap1.setCapabilityRendition(CapabilityRenditions.READ);
        cap1.setIsPwcSearchable(Boolean.TRUE);
        cap1.setIsPwcUpdatable(Boolean.TRUE);
        cap1.setSupportsGetDescendants(Boolean.TRUE);
        cap1.setSupportsGetFolderTree(Boolean.TRUE);
        cap1.setSupportsMultifiling(Boolean.TRUE);
        cap1.setSupportsUnfiling(Boolean.TRUE);
        cap1.setSupportsVersionSpecificFiling(Boolean.TRUE);
        obj1.setCapabilities(cap1);

        AclCapabilitiesDataImpl acl1 = new AclCapabilitiesDataImpl();
        acl1.setAclPropagation(AclPropagation.PROPAGATE);
        List<PermissionDefinition> pddList = new ArrayList<PermissionDefinition>();
        PermissionDefinitionDataImpl pdd1 = new PermissionDefinitionDataImpl();
        pdd1.setId("test:perm1");
        pdd1.setDescription("Permission1");
        pddList.add(pdd1);
        PermissionDefinitionDataImpl pdd2 = new PermissionDefinitionDataImpl();
        pdd2.setId("test:perm2");
        pdd2.setDescription("Permission2");
        pddList.add(pdd2);
        acl1.setPermissionDefinitionData(pddList);
        Map<String, PermissionMapping> pmd = new LinkedHashMap<String, PermissionMapping>();
        PermissionMappingDataImpl pmd1 = new PermissionMappingDataImpl();
        pmd1.setKey(PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER);
        pmd1.setPermissions(Arrays.asList(new String[] { "p1", "p2" }));
        pmd.put(pmd1.getKey(), pmd1);
        PermissionMappingDataImpl pmd2 = new PermissionMappingDataImpl();
        pmd2.setKey(PermissionMapping.CAN_DELETE_OBJECT);
        pmd2.setPermissions(Arrays.asList(new String[] { "p3", "p4" }));
        pmd.put(pmd2.getKey(), pmd2);
        acl1.setPermissionMappingData(pmd);
        obj1.setAclCapabilities(acl1);

        obj2 = WSConverter.convert(WSConverter.convert(obj1, CmisVersion.CMIS_1_1));

        assertDataObjectsEquals("RepositoryInfo", obj1, obj2);
    }

    @Test
    public void testTypeDefinition() throws Exception {
        // dry run
        DocumentTypeDefinitionImpl obj1 = new DocumentTypeDefinitionImpl();
        TypeDefinition obj2 = WSConverter.convert(WSConverter.convert(obj1));

        assertDataObjectsEquals("TypeDefinition", obj1, obj2);

        // simple values
        obj1 = new DocumentTypeDefinitionImpl();
        obj1.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
        obj1.setContentStreamAllowed(ContentStreamAllowed.ALLOWED);
        obj1.setDescription("description");
        obj1.setDisplayName("displayName");
        obj1.setId("id");
        obj1.setIsControllableAcl(Boolean.TRUE);
        obj1.setIsControllablePolicy(Boolean.TRUE);
        obj1.setIsCreatable(Boolean.TRUE);
        obj1.setIsFileable(Boolean.TRUE);
        obj1.setIsIncludedInSupertypeQuery(Boolean.TRUE);
        obj1.setIsQueryable(Boolean.TRUE);
        obj1.setIsVersionable(Boolean.TRUE);
        obj1.setLocalName("localName");
        obj1.setLocalNamespace("localNamespace");
        obj1.setParentTypeId("parentId");
        obj1.setQueryName("queryName");

        obj2 = WSConverter.convert(WSConverter.convert(obj1));

        assertDataObjectsEquals("TypeDefinition", obj1, obj2);
    }

    @Test
    public void testObject() throws Exception {
        // dry run
        ObjectDataImpl obj1 = new ObjectDataImpl();
        ObjectData obj2 = WSConverter.convert(WSConverter.convert(obj1, CmisVersion.CMIS_1_1));

        assertDataObjectsEquals("Object", obj1, obj2);
    }

    /**
     * Asserts OpenCMIS data objects.
     */
    protected void assertDataObjectsEquals(String name, Object expected, Object actual) throws Exception {

        if ((expected == null) && (actual == null)) {
            return;
        }

        if ((expected == null) && (actual instanceof Collection<?>)) {
            assertTrue(((Collection<?>) actual).isEmpty());
            return;
        } else if ((expected instanceof Collection<?>) && (actual == null)) {
            assertTrue(((Collection<?>) expected).isEmpty());
            return;
        } else if ((expected == null) || (actual == null)) {
            fail("Data object is null! name: " + name + " / expected: " + expected + " / actual: " + actual);
        }

        // handle simple types
        if ((expected instanceof String) || (expected instanceof Boolean) || (expected instanceof BigInteger)
                || (expected instanceof BigDecimal) || (expected instanceof Enum<?>)) {
            assertEquals(expected, actual);

            return;
        } else if (expected instanceof List<?>) {
            List<?> expectedList = (List<?>) expected;
            List<?> actualList = (List<?>) actual;

            assertEquals(expectedList.size(), actualList.size());

            for (int i = 0; i < expectedList.size(); i++) {
                assertDataObjectsEquals(name + "[" + i + "]", expectedList.get(i), actualList.get(i));
            }

            return;
        } else if (expected instanceof Map<?, ?>) {
            Map<?, ?> expectedMap = (Map<?, ?>) expected;
            Map<?, ?> actualMap = (Map<?, ?>) actual;

            assertEquals(expectedMap.size(), actualMap.size());

            for (Map.Entry<?, ?> entry : expectedMap.entrySet()) {
                assertTrue(actualMap.containsKey(entry.getKey()));
                assertDataObjectsEquals(name + "[" + entry.getKey() + "]", entry.getValue(),
                        actualMap.get(entry.getKey()));
            }

            return;
        }

        for (Method m : expected.getClass().getMethods()) {
            if (!m.getName().startsWith("get") && !m.getName().startsWith("supports")) {
                continue;
            }

            if (m.getName().equals("getClass")) {
                continue;
            }

            if (m.getParameterTypes().length != 0) {
                continue;
            }

            Object expectedValue = m.invoke(expected, new Object[0]);
            Object actualValue = m.invoke(actual, new Object[0]);

            assertDataObjectsEquals(name + "." + m.getName(), expectedValue, actualValue);
        }
    }
}
