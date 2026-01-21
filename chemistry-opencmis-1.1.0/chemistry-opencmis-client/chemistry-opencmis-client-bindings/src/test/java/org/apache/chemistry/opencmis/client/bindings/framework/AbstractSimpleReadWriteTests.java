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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.spi.Holder;

/**
 * Simple read-write test.
 */
public abstract class AbstractSimpleReadWriteTests extends AbstractCmisTestCase {

    public static final String TEST_CREATE_FOLDER = "createFolder";
    public static final String TEST_CREATE_DOCUMENT = "createDocument";
    public static final String TEST_CREATE_FROM_SOURCE = "createDocumentFromSource";
    public static final String TEST_SET_AND_DELETE_CONTENT = "setAndDeleteContent";
    public static final String TEST_UPDATE_PROPERTIES = "updateProperties";
    public static final String TEST_DELETE_TREE = "deleteTree";
    public static final String TEST_MOVE_OBJECT = "moveObject";
    public static final String TEST_COPY_OBJECT = "copyObject";
    public static final String TEST_VERSIONING = "versioning";

    private static final byte[] CONTENT = "My document test content!".getBytes();
    private static final byte[] CONTENT2 = "Another test content!".getBytes();
    private static final String CONTENT_TYPE = "text/plain";

    /**
     * Tests folder creation.
     */
    public void testCreateFolder() {
        if (!isEnabled(TEST_CREATE_FOLDER)) {
            return;
        }

        // create folder
        List<PropertyData<?>> propList = new ArrayList<PropertyData<?>>();
        propList.add(getObjectFactory().createPropertyStringData(PropertyIds.NAME, "testfolder"));
        propList.add(getObjectFactory().createPropertyIdData(PropertyIds.OBJECT_TYPE_ID, getDefaultFolderType()));

        Properties properties = getObjectFactory().createPropertiesData(propList);

        String folderId = createFolder(properties, getTestRootFolder(), null, null, null);

        // delete folder
        delete(folderId, true);
    }

    /**
     * Tests document creation.
     */
    public void testCreateDocument() throws Exception {
        if (!isEnabled(TEST_CREATE_DOCUMENT)) {
            return;
        }

        VersioningState vs = isVersionable(getDefaultDocumentType()) ? VersioningState.MAJOR : VersioningState.NONE;

        // create document
        List<PropertyData<?>> propList = new ArrayList<PropertyData<?>>();
        propList.add(getObjectFactory().createPropertyStringData(PropertyIds.NAME, "testdoc.txt"));
        propList.add(getObjectFactory().createPropertyIdData(PropertyIds.OBJECT_TYPE_ID, getDefaultDocumentType()));

        Properties properties = getObjectFactory().createPropertiesData(propList);

        ContentStream contentStream = createContentStreamData(CONTENT_TYPE, CONTENT);

        String docId = createDocument(properties, getTestRootFolder(), contentStream, vs, null, null, null);

        // read and assert content
        ContentStream contentStream2 = getContent(docId, null);
        assertMimeType(CONTENT_TYPE, contentStream2.getMimeType());
        if (contentStream2.getBigLength() != null) {
            assertEquals(CONTENT.length, contentStream2.getBigLength().intValue());
        }

        byte[] content = readContent(contentStream2);
        assertContent(CONTENT, content);

        // apply an ACL
        if (supportsManageACLs()) {
            Ace ace = getObjectFactory()
                    .createAccessControlEntry(getUsername(), Collections.singletonList("cmis:read"));
            Acl acl = getObjectFactory().createAccessControlList(Collections.singletonList(ace));

            Acl newAcl = getBinding().getAclService().applyAcl(getTestRepositoryId(), docId, acl, null,
                    getAclPropagation(), null);
            assertNotNull(newAcl);

            Acl readAcl = getBinding().getAclService().getAcl(getTestRepositoryId(), docId, Boolean.FALSE, null);
            assertNotNull(readAcl);

            assertEquals(newAcl, readAcl);
        } else {
            warning("ACLs management not supported!");
        }

        // delete document
        delete(docId, true);
    }

    /**
     * Tests document creation from source.
     */
    public void testCreateDocumentFromSource() throws Exception {
        if (!isEnabled(TEST_CREATE_FROM_SOURCE)) {
            return;
        }

        VersioningState vs = isVersionable(getDefaultDocumentType()) ? VersioningState.MAJOR : VersioningState.NONE;

        String docId = createDefaultDocument(getTestRootFolder(), "testdoc.org.txt", CONTENT_TYPE, CONTENT);

        // create a copy
        List<PropertyData<?>> propList2 = new ArrayList<PropertyData<?>>();
        propList2.add(getObjectFactory().createPropertyStringData(PropertyIds.NAME, "testdoc.copy.txt"));

        Properties properties2 = getObjectFactory().createPropertiesData(propList2);

        String docId2 = createDocumentFromSource(docId, properties2, getTestRootFolder(), vs, null, null, null);

        // get objects
        getObject(docId);
        getObject(docId2);

        // read and assert content
        ContentStream contentStream2 = getContent(docId, null);
        ContentStream contentStream3 = getContent(docId2, null);

        assertEquals(contentStream2.getMimeType(), contentStream3.getMimeType());
        assertEquals(contentStream2.getBigLength(), contentStream3.getBigLength());

        byte[] content2 = readContent(contentStream2);
        byte[] content3 = readContent(contentStream3);
        assertContent(content2, content3);

        // delete documents
        delete(docId, true);
        delete(docId2, true);
    }

    /**
     * Tests setting and deleting content stream.
     */
    public void testSetAndDeleteContent() throws Exception {
        if (!isEnabled(TEST_SET_AND_DELETE_CONTENT)) {
            return;
        }

        boolean requiresCheckOut = getRepositoryInfo().getCapabilities().getContentStreamUpdatesCapability() == CapabilityContentStreamUpdates.PWCONLY;

        boolean isVersionable = isVersionable(getDefaultDocumentType());

        String docId = createDefaultDocument(getTestRootFolder(), "testcontent.txt", CONTENT_TYPE, CONTENT);

        // if a check out is required, do it
        Holder<String> docIdHolder = new Holder<String>(docId);
        if (requiresCheckOut) {
            if (isVersionable) {
                getBinding().getVersioningService().checkOut(getTestRepositoryId(), docIdHolder, null, null);
            } else {
                warning("Default document type is not versionable!");
                delete(docId, true);
                return;
            }
        }

        String docIdWorkingCopy = docIdHolder.getValue();

        // delete content
        try {
            getBinding().getObjectService().deleteContentStream(getTestRepositoryId(), docIdHolder, null, null);
        } catch (CmisNotSupportedException e) {
            warning("deleteContentStream not supported!");
        }

        // set content
        ContentStream contentStream2 = createContentStreamData(CONTENT_TYPE, CONTENT2);

        docIdHolder = new Holder<String>(docIdWorkingCopy);
        getBinding().getObjectService().setContentStream(getTestRepositoryId(), docIdHolder, true, null,
                contentStream2, null);

        // read and assert content
        String newVersionDocId = (docIdHolder.getValue() == null ? docIdWorkingCopy : docIdHolder.getValue());
        ContentStream contentStream3 = getContent(newVersionDocId, null);
        assertMimeType(CONTENT_TYPE, contentStream3.getMimeType());
        if (contentStream3.getBigLength() != null) {
            assertEquals(CONTENT2.length, contentStream3.getBigLength().intValue());
        }

        byte[] content = readContent(contentStream3);
        assertContent(CONTENT2, content);

        // if it has been checked out, cancel that
        if (requiresCheckOut) {
            getBinding().getVersioningService().cancelCheckOut(getTestRepositoryId(), docIdWorkingCopy, null);
        }

        // delete document
        delete(docId, true);
    }

    /**
     * Tests property updates.
     */
    public void testUpdateProperties() {
        if (!isEnabled(TEST_UPDATE_PROPERTIES)) {
            return;
        }

        String name1 = "updateTest1.txt";
        String name2 = "updateTest2.txt";

        // create document
        String docId = createDefaultDocument(getTestRootFolder(), name1, CONTENT_TYPE, CONTENT);

        // update
        List<PropertyData<?>> updatePropList = new ArrayList<PropertyData<?>>();
        updatePropList.add(getObjectFactory().createPropertyStringData(PropertyIds.NAME, name2));

        Properties updateProperties = getObjectFactory().createPropertiesData(updatePropList);

        Holder<String> docIdHolder = new Holder<String>(docId);
        getBinding().getObjectService().updateProperties(getTestRepositoryId(), docIdHolder, null, updateProperties,
                null);

        // get new id and check name property
        docId = docIdHolder.getValue();

        ObjectData updatedObject = getObject(docId);
        String updatedName = (String) updatedObject.getProperties().getProperties().get(PropertyIds.NAME)
                .getFirstValue();
        assertNotNull(updatedName);
        assertEquals(name2, updatedName);

        // delete document
        delete(docId, true);
    }

    /**
     * Tests delete tree.
     */
    public void testDeleteTree() {
        if (!isEnabled(TEST_DELETE_TREE)) {
            return;
        }

        // create a folder tree
        String folder1 = createDefaultFolder(getTestRootFolder(), "folder1");
        String folder11 = createDefaultFolder(folder1, "folder11");
        String folder12 = createDefaultFolder(folder1, "folder12");
        String folder121 = createDefaultFolder(folder12, "folder121");
        String folder122 = createDefaultFolder(folder12, "folder122");

        // create a few documents
        String doc111 = createDefaultDocument(folder11, "doc111.txt", CONTENT_TYPE, CONTENT);
        String doc1221 = createDefaultDocument(folder122, "doc1221.txt", CONTENT_TYPE, CONTENT2);

        // delete the tree
        getBinding().getObjectService().deleteTree(getTestRepositoryId(), folder1, Boolean.TRUE, UnfileObject.DELETE,
                Boolean.TRUE, null);

        assertFalse(existsObject(folder1));
        assertFalse(existsObject(folder11));
        assertFalse(existsObject(folder12));
        assertFalse(existsObject(folder121));
        assertFalse(existsObject(folder122));
        assertFalse(existsObject(doc111));
        assertFalse(existsObject(doc1221));
    }

    /**
     * Tests move object.
     */
    public void testMoveObject() {
        if (!isEnabled(TEST_MOVE_OBJECT)) {
            return;
        }

        // create folders
        String folder1 = createDefaultFolder(getTestRootFolder(), "folder1");
        String folder2 = createDefaultFolder(getTestRootFolder(), "folder2");

        // create document
        String docId = createDefaultDocument(folder1, "testdoc.txt", CONTENT_TYPE, CONTENT);

        // move it
        Holder<String> docIdHolder = new Holder<String>(docId);
        getBinding().getObjectService().moveObject(getTestRepositoryId(), docIdHolder, folder2, folder1, null);
        assertNotNull(docIdHolder.getValue());

        assertTrue(existsObject(docIdHolder.getValue()));
        getChild(folder2, docIdHolder.getValue());

        deleteTree(folder1);
        deleteTree(folder2);
    }

    /**
     * Tests copy object.
     */
    public void testCopyObject() {
        if (!isEnabled(TEST_COPY_OBJECT)) {
            return;
        }

        // create folders
        String folder1 = createDefaultFolder(getTestRootFolder(), "folder1");
        String folder2 = createDefaultFolder(getTestRootFolder(), "folder2");

        // create document
        String docId = createDefaultDocument(folder1, "testdoc.txt", CONTENT_TYPE, CONTENT);

        // copy it with new properties
        List<PropertyData<?>> updatePropList = new ArrayList<PropertyData<?>>();
        updatePropList.add(getObjectFactory().createPropertyStringData(PropertyIds.NAME, "newdocname"));
        Properties updateProperties = getObjectFactory().createPropertiesData(updatePropList);

        String copyId = getBinding().getObjectService().createDocumentFromSource(getTestRepositoryId(), docId,
                updateProperties, folder2, null, null, null, null, null);
        assertNotNull(copyId);

        assertTrue(existsObject(copyId));
        ObjectInFolderData copy = getChild(folder2, copyId);
        String updatedName = (String) copy.getObject().getProperties().getProperties().get(PropertyIds.NAME)
                .getFirstValue();
        assertEquals("newdocname", updatedName);

        deleteTree(folder1);
        deleteTree(folder2);
    }

    /**
     * Test check-in/check-out.
     */
    public void testVersioning() {
        if (!isEnabled(TEST_VERSIONING)) {
            return;
        }

        if (!isVersionable(getDefaultDocumentType())) {
            warning("Default document type is not versionable!");
            return;
        }

        // create document
        String docId = createDefaultDocument(getTestRootFolder(), "versionTest.txt", CONTENT_TYPE, CONTENT);

        // there must be only one version in the version series
        List<ObjectData> allVersions = getBinding().getVersioningService().getAllVersions(getTestRepositoryId(), docId,
                getVersionSeriesId(docId), "*", Boolean.FALSE, null);
        assertNotNull(allVersions);
        assertEquals(1, allVersions.size());

        assertEquals(docId, allVersions.get(0).getId());

        // check out
        Holder<String> versionIdHolder = new Holder<String>(docId);
        getBinding().getVersioningService().checkOut(getTestRepositoryId(), versionIdHolder, null, null);
        String versionId = versionIdHolder.getValue();

        // object must be marked as checked out
        assertTrue(isCheckedOut(docId));

        // cancel check out
        getBinding().getVersioningService().cancelCheckOut(getTestRepositoryId(), versionId, null);

        // object must NOT be marked as checked out
        assertFalse(isCheckedOut(docId));

        // check out again
        versionIdHolder.setValue(docId);
        getBinding().getVersioningService().checkOut(getTestRepositoryId(), versionIdHolder, null, null);
        versionId = versionIdHolder.getValue();

        // object must be marked as checked out
        assertTrue(isCheckedOut(docId));

        versionIdHolder.setValue(versionId);
        getBinding().getVersioningService().checkIn(getTestRepositoryId(), versionIdHolder, Boolean.TRUE, null, null,
                "Test Version 2", null, null, null, null);
        docId = versionIdHolder.getValue();

        // object must NOT be marked as checked out
        assertFalse(isCheckedOut(docId));

        // there must be exactly two versions in the version series
        allVersions = getBinding().getVersioningService().getAllVersions(getTestRepositoryId(), docId,
                getVersionSeriesId(docId), "*", Boolean.FALSE, null);
        assertNotNull(allVersions);
        assertEquals(2, allVersions.size());

        // delete document
        delete(docId, true);
    }

    private boolean isCheckedOut(String docId) {
        ObjectData object = getObject(docId);
        PropertyData<?> isCheckedOut = object.getProperties().getProperties()
                .get(PropertyIds.IS_VERSION_SERIES_CHECKED_OUT);
        assertNotNull(isCheckedOut);
        assertTrue(isCheckedOut.getFirstValue() instanceof Boolean);

        return ((Boolean) isCheckedOut.getFirstValue()).booleanValue();
    }
}
