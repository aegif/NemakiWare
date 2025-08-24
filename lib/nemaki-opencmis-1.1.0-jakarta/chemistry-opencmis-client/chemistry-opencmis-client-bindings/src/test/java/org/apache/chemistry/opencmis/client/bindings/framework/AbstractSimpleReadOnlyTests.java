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
package org.apache.chemistry.opencmis.client.bindings.framework;

import static org.apache.chemistry.opencmis.commons.impl.CollectionsHelper.isNotEmpty;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderList;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.FolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PolicyTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;

/**
 * Simple read-only tests.
 */
public abstract class AbstractSimpleReadOnlyTests extends AbstractCmisTestCase {

    public static final String TEST_REPOSITORY_INFO = "repositoryInfo";
    public static final String TEST_TYPES = "types";
    public static final String TEST_CONTENT_STREAM = "contentStream";
    public static final String TEST_NAVIGATION = "navigation";
    public static final String TEST_QUERY = "query";
    public static final String TEST_CHECKEDOUT = "checkedout";
    public static final String TEST_CONTENT_CHANGES = "contentChanges";

    /**
     * Tests repository info.
     */
    public void testRepositoryInfo() {
        if (!isEnabled(TEST_REPOSITORY_INFO)) {
            return;
        }

        RepositoryInfo repInfo = getRepositoryInfo();

        Tools.print(repInfo);

        assertNotNull(repInfo.getId());
        assertNotNull(repInfo.getCmisVersionSupported());
        assertNotNull(repInfo.getRootFolderId());
        assertNotNull(repInfo.getCapabilities());
    }

    /**
     * Some type related tests.
     */
    public void testTypes() {
        if (!isEnabled(TEST_TYPES)) {
            return;
        }

        String repId = getTestRepositoryId();

        // get standard type
        TypeDefinition docType = getTypeDefinition("cmis:document");
        assertTrue(docType instanceof DocumentTypeDefinition);
        assertEquals("cmis:document", docType.getId());
        assertEquals(BaseTypeId.CMIS_DOCUMENT, docType.getBaseTypeId());

        TypeDefinition folderType = getTypeDefinition("cmis:folder");
        assertTrue(folderType instanceof FolderTypeDefinition);
        assertEquals("cmis:folder", folderType.getId());
        assertEquals(BaseTypeId.CMIS_FOLDER, folderType.getBaseTypeId());

        try {
            TypeDefinition relationshipType = getTypeDefinition("cmis:relationship");
            assertTrue(relationshipType instanceof RelationshipTypeDefinition);
            assertEquals("cmis:relationship", relationshipType.getId());
            assertEquals(BaseTypeId.CMIS_RELATIONSHIP, relationshipType.getBaseTypeId());
        } catch (Exception e) {
            warning("Relationships type: " + e);
        }

        try {
            TypeDefinition policyType = getTypeDefinition("cmis:policy");
            assertTrue(policyType instanceof PolicyTypeDefinition);
            assertEquals("cmis:policy", policyType.getId());
            assertEquals(BaseTypeId.CMIS_POLICY, policyType.getBaseTypeId());
        } catch (Exception e) {
            warning("Policy type: " + e);
        }

        // getTypeChildren
        TypeDefinitionList types = getBinding().getRepositoryService().getTypeChildren(repId, null, Boolean.TRUE, null,
                null, null);
        assertNotNull(types);
        assertNotNull(types.hasMoreItems());
        assertNotNull(types.getList());
        assertFalse(types.getList().isEmpty());
        assertTrue(types.getList().size() >= 2);
        assertTrue(types.getList().size() <= 4);

        getBinding().clearAllCaches();

        for (TypeDefinition type : types.getList()) {
            TypeDefinition type2 = getTypeDefinition(type.getId());
            assertEquals(type, type2, true);
        }

        // getTypeDescendants
        List<TypeDefinitionContainer> typesContainers = getBinding().getRepositoryService().getTypeDescendants(repId,
                null, null, Boolean.TRUE, null);
        assertNotNull(typesContainers);
        assertFalse(typesContainers.isEmpty());

        for (TypeDefinitionContainer typeContainer : typesContainers) {
            assertNotNull(typeContainer.getTypeDefinition());
            assertNotNull(typeContainer.getTypeDefinition().getId());
            TypeDefinition type2 = getTypeDefinition(typeContainer.getTypeDefinition().getId());
            assertEquals(typeContainer.getTypeDefinition(), type2, true);
        }

        Tools.printTypes("Type Descendants", typesContainers);

        getBinding().clearAllCaches();

        assertTypeContainers(repId, typesContainers);
    }

    private void assertTypeContainers(String repId, List<TypeDefinitionContainer> typesContainers) {
        if (typesContainers == null) {
            return;
        }

        for (TypeDefinitionContainer container : typesContainers) {
            assertNotNull(container.getTypeDefinition());

            TypeDefinition type = container.getTypeDefinition();
            TypeDefinition type2 = getTypeDefinition(type.getId());

            assertEquals(type, type2, true);

            assertTypeContainers(repId, container.getChildren());
        }
    }

    /**
     * Navigation smoke test.
     */
    public void testNavigation() {
        if (!isEnabled(TEST_NAVIGATION)) {
            return;
        }

        String repId = getTestRepositoryId();
        String rootFolder = getRootFolderId();
        String testRootFolder = getTestRootFolder();

        ObjectData rootFolderObject = getObject(rootFolder);
        String rootPath = getPath(rootFolderObject);
        assertEquals("Root path is not \"/\"!", "/", rootPath);
        assertAllowableAction(rootFolderObject.getAllowableActions(), Action.CAN_GET_OBJECT_PARENTS, false);

        ObjectData folderObject = getObject(testRootFolder);
        String path = getPath(folderObject);

        ObjectInFolderList children = getBinding().getNavigationService().getChildren(repId, testRootFolder, "*", null,
                Boolean.TRUE, IncludeRelationships.BOTH, null, Boolean.TRUE, null, null, null);
        assertNotNull(children);
        assertNotNull(children.hasMoreItems());

        if (supportsDescendants()) {
            List<ObjectInFolderContainer> desc = getBinding().getNavigationService().getDescendants(repId,
                    testRootFolder, BigInteger.valueOf(2), "*", Boolean.TRUE, IncludeRelationships.BOTH, null,
                    Boolean.TRUE, null);
            assertNotNull(desc);
            Tools.print("Descendants", desc);

            assertContainer(desc, 5);
        } else {
            warning("Descendants not supported!");
        }

        if (supportsFolderTree()) {
            List<ObjectInFolderContainer> tree = getBinding().getNavigationService().getFolderTree(repId,
                    testRootFolder, BigInteger.valueOf(2), "*", Boolean.TRUE, IncludeRelationships.BOTH, null,
                    Boolean.TRUE, null);
            assertNotNull(tree);
            Tools.print("Tree", tree);

            assertContainer(tree, 5);
        } else {
            warning("Folder Tree not supported!");
        }

        for (ObjectInFolderData object : children.getObjects()) {
            assertNotNull(object.getObject());
            assertNotNull(object.getObject().getId());
            assertNotNull(object.getObject().getBaseTypeId());

            ObjectData object2 = getObject(object.getObject().getId());
            assertNotNull(object2.getId());
            assertEquals(object.getObject().getId(), object2.getId());
            assertEquals(object.getObject().getProperties(), object2.getProperties());

            ObjectData object3 = getObjectByPath((path.equals("/") ? "/" : path + "/") + object.getPathSegment());
            assertNotNull(object3);
            assertNotNull(object3.getId());
            assertEquals(object.getObject().getId(), object3.getId());
            assertEquals(object.getObject().getProperties(), object3.getProperties());

            checkObject(object.getObject().getId());

            if (object.getObject().getBaseTypeId() == BaseTypeId.CMIS_FOLDER) {
                ObjectInFolderList children2 = getBinding().getNavigationService().getChildren(repId,
                        object.getObject().getId(), null, null, Boolean.TRUE, IncludeRelationships.BOTH, null,
                        Boolean.TRUE, null, null, null);
                assertNotNull(children2);
            } else if (object.getObject().getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT) {
                checkObjectVersions(object.getObject().getId());
            }
        }
    }

    private void assertContainer(List<ObjectInFolderContainer> containers, int maxDepth) {
        if (containers == null) {
            return;
        }

        if (maxDepth < 1) {
            return;
        }

        for (ObjectInFolderContainer container : containers) {
            assertNotNull(container);
            assertNotNull(container.getObject());
            assertNotNull(container.getObject().getObject());
            assertNotNull(container.getObject().getObject().getId());
            assertNotNull(container.getObject().getPathSegment());

            ObjectData object = getObject(container.getObject().getObject().getId());

            assertEquals(container.getObject().getObject().getProperties(), object.getProperties());
            assertEquals(container.getObject().getObject().getAllowableActions(), object.getAllowableActions());

            assertContainer(container.getChildren(), maxDepth - 1);
        }
    }

    /**
     * Content stream smoke test.
     */
    public void testContentStream() throws Exception {
        if (!isEnabled(TEST_CONTENT_STREAM)) {
            return;
        }

        String repId = getTestRepositoryId();
        String rootFolder = getTestRootFolder();

        ObjectInFolderList children = getBinding().getNavigationService().getChildren(repId, rootFolder, null, null,
                Boolean.FALSE, IncludeRelationships.BOTH, null, Boolean.FALSE, null, null, null);
        assertNotNull(children);
        assertNotNull(children.getObjects());

        for (ObjectInFolderData object : children.getObjects()) {
            assertNotNull(object.getObject().getId());
            assertNotNull(object.getObject().getBaseTypeId());

            if (object.getObject().getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT) {
                ContentStream contentStream = getContent(object.getObject().getId(), null);
                readContent(contentStream);

                return;
            }
        }

        fail("No document in test folder!");
    }

    /**
     * Query smoke test.
     */
    public void testQuery() {
        if (!isEnabled(TEST_QUERY)) {
            return;
        }

        if (supportsQuery()) {
            String repId = getTestRepositoryId();

            ObjectList rs = getBinding().getDiscoveryService().query(repId, "SELECT * FROM cmis:document", null, null,
                    null, null, null, null, null);
            assertNotNull(rs);

            if (rs.getObjects() != null) {
                for (ObjectData object : rs.getObjects()) {
                    assertNotNull(object);
                    assertNotNull(object.getProperties());
                    assertNotNull(object.getProperties().getProperties());
                }
            }

        } else {
            warning("Query not supported!");
        }
    }

    /**
     * Checked out smoke test.
     */
    public void testCheckedout() {
        if (!isEnabled(TEST_CHECKEDOUT)) {
            return;
        }

        String repId = getTestRepositoryId();

        ObjectList co = getBinding().getNavigationService().getCheckedOutDocs(repId, getTestRootFolder(), null, null,
                Boolean.TRUE, IncludeRelationships.BOTH, null, BigInteger.valueOf(100), null, null);
        assertNotNull(co);

        if (co.getObjects() != null) {
            assertTrue(co.getObjects().size() <= 100);

            for (ObjectData object : co.getObjects()) {
                assertNotNull(object);
                assertNotNull(object.getId());
                assertEquals(BaseTypeId.CMIS_DOCUMENT, object.getBaseTypeId());
            }
        }
    }

    /**
     * Content changes smoke test.
     */
    public void testContentChanges() {
        if (!isEnabled(TEST_CONTENT_CHANGES)) {
            return;
        }

        if (supportsContentChanges()) {
            String repId = getTestRepositoryId();

            ObjectList cc = getBinding().getDiscoveryService().getContentChanges(repId, null, Boolean.TRUE, "*",
                    Boolean.TRUE, Boolean.TRUE, BigInteger.valueOf(100), null);
            assertNotNull(cc);

            if (cc.getObjects() != null) {
                assertTrue(cc.getObjects().size() <= 100);

                for (ObjectData object : cc.getObjects()) {
                    assertNotNull(object);
                    assertNotNull(object.getId());
                    assertNotNull(object.getChangeEventInfo());
                    assertNotNull(object.getChangeEventInfo().getChangeType());
                    assertNotNull(object.getChangeEventInfo().getChangeTime());
                }
            }
        } else {
            warning("Content changes not supported!");
        }
    }

    /**
     * Tests some of the read-only methods of the Object Service.
     */
    private void checkObject(String objectId) {
        System.out.println("Checking object " + objectId + "...");

        ObjectData object = getObject(objectId);

        // check properties
        Properties properties = getBinding().getObjectService().getProperties(getTestRepositoryId(), objectId, "*",
                null);

        assertEquals(object.getProperties(), properties);

        // check allowable actions
        AllowableActions allowableActions = getBinding().getObjectService().getAllowableActions(getTestRepositoryId(),
                objectId, null);

        assertEquals(object.getAllowableActions(), allowableActions);

        // check ACLS
        if (supportsDiscoverACLs()) {
            Acl acl = getBinding().getAclService().getAcl(getTestRepositoryId(), objectId, Boolean.FALSE, null);

            assertEquals(object.getAcl(), acl);
        } else {
            warning("ACLs not supported!");
        }

        // check policies
        if (supportsPolicies()) {
            List<ObjectData> policies = getBinding().getPolicyService().getAppliedPolicies(getTestRepositoryId(),
                    objectId, null, null);

            if (policies == null) {
                assertNull(object.getPolicyIds().getPolicyIds());
            } else {
                assertNotNull(object.getPolicyIds().getPolicyIds());

                List<String> policyIds = new ArrayList<String>();

                for (ObjectData policy : policies) {
                    assertNotNull(policy);
                    assertNotNull(policy.getId());

                    policyIds.add(policy.getId());
                }

                assertEqualLists(object.getPolicyIds().getPolicyIds(), policyIds);
            }
        } else {
            warning("Policies not supported!");
        }

        // check renditions
        if (supportsRenditions()) {
            List<RenditionData> renditions = getBinding().getObjectService().getRenditions(getTestRepositoryId(),
                    objectId, null, null, null, null);

            assertEqualLists(object.getRenditions(), renditions);
        } else {
            warning("Renditions not supported!");
        }

        // check relationships
        if (supportsRelationships()) {
            ObjectList relationships = getBinding().getRelationshipService().getObjectRelationships(
                    getTestRepositoryId(), objectId, Boolean.TRUE, RelationshipDirection.EITHER, null, "*",
                    Boolean.TRUE, null, null, null);
            assertNotNull(relationships);

            if ((object.getRelationships() != null) && (relationships.getObjects() != null)) {
                assertEquals(object.getRelationships().size(), relationships.getObjects().size());
                for (ObjectData rel1 : relationships.getObjects()) {
                    assertBasicProperties(rel1.getProperties());
                    boolean found = false;

                    for (ObjectData rel2 : object.getRelationships()) {
                        if (rel2.getId().equals(rel1.getId())) {
                            found = true;
                            assertEquals(rel2.getProperties(), rel1.getProperties());
                            break;
                        }
                    }

                    assertTrue(found);
                }
            }
        } else {
            warning("Relationships not supported!");
        }
    }

    /**
     * Tests some of the read-only methods of the Versioning Service.
     */
    private void checkObjectVersions(String objectId) {
        System.out.println("Checking versions of object " + objectId + "...");

        String versionSeriesId = getVersionSeriesId(objectId);
        assertNotNull(versionSeriesId);

        // check latest version
        ObjectData latestVersionObject = getBinding().getVersioningService().getObjectOfLatestVersion(
                getTestRepositoryId(), objectId, versionSeriesId, Boolean.FALSE, "*", Boolean.TRUE,
                IncludeRelationships.BOTH, null, Boolean.TRUE, Boolean.TRUE, null);
        assertNotNull(latestVersionObject);

        Properties latestVersionProperties = getBinding().getVersioningService().getPropertiesOfLatestVersion(
                getTestRepositoryId(), objectId, versionSeriesId, Boolean.FALSE, "*", null);
        assertNotNull(latestVersionProperties);

        assertEquals(latestVersionObject.getProperties(), latestVersionProperties);

        String typeName = (String) latestVersionObject.getProperties().getProperties().get(PropertyIds.BASE_TYPE_ID)
                .getFirstValue();
        if (isVersionable(typeName)) {
            List<ObjectData> allVersions = getBinding().getVersioningService().getAllVersions(getTestRepositoryId(),
                    objectId, versionSeriesId, "*", Boolean.FALSE, null);
            assertNotNull(allVersions);
            assertTrue(isNotEmpty(allVersions));

            boolean foundObject = false;
            boolean foundLatestObject = false;
            for (ObjectData object : allVersions) {
                assertNotNull(object);
                assertNotNull(object.getId());

                if (objectId.equals(object.getId())) {
                    foundObject = true;
                }

                if (latestVersionObject.getId().equals(object.getId())) {
                    foundLatestObject = true;
                    assertEquals(latestVersionObject.getProperties(), object.getProperties());
                }
            }

            if (!foundObject) {
                fail("Object " + objectId + " not found in it's version history!");
            }

            if (!foundLatestObject) {
                fail("Object " + latestVersionObject.getId() + " not found in it's version history!");
            }
        }
    }
}
