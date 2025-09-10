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
package org.apache.chemistry.opencmis.tck.tests.crud;

import static org.apache.chemistry.opencmis.tck.CmisTestResultStatus.FAILURE;
import static org.apache.chemistry.opencmis.tck.CmisTestResultStatus.SKIPPED;
import static org.apache.chemistry.opencmis.tck.CmisTestResultStatus.WARNING;

import java.util.HashMap;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import org.apache.chemistry.opencmis.tck.impl.AbstractSessionTest;

/**
 * Document without content test.
 */
public class CreateDocumentWithoutContent extends AbstractSessionTest {

    @Override
    public void init(Map<String, String> parameters) {
        super.init(parameters);
        setName("Create Document without Content Test");
        setDescription("Creates a document without content and deletes it.");
    }

    @Override
    public void run(Session session) {
        CmisTestResult f;

        String objectTypeId = getDocumentTestTypeId();

        TypeDefinition type = session.getTypeDefinition(objectTypeId);
        if (!(type instanceof DocumentTypeDefinition)) {
            addResult(createResult(FAILURE, "Type is not a document type! Type: " + objectTypeId, true));
            return;
        }

        DocumentTypeDefinition docType = (DocumentTypeDefinition) type;

        if (docType.getContentStreamAllowed() == ContentStreamAllowed.REQUIRED) {
            addResult(createResult(SKIPPED,
                    "The test document type does not support documents without content. Test skipped!"));
            return;
        }

        // create a test folder
        Folder testFolder = createTestFolder(session);

        try {
            String name = "nocontent";

            Map<String, Object> properties = new HashMap<String, Object>();
            properties.put(PropertyIds.NAME, name);
            properties.put(PropertyIds.OBJECT_TYPE_ID, objectTypeId);

            VersioningState versioningState = (Boolean.TRUE.equals(docType.isVersionable()) ? VersioningState.MAJOR
                    : VersioningState.NONE);

            // create and fetch the document
            ObjectId id = session.createDocument(properties, testFolder, null, versioningState);
            Document doc = (Document) session.getObject(id, SELECT_ALL_NO_CACHE_OC);

            // check the new document
            addResult(checkObject(session, doc, getAllProperties(doc), "New document object spec compliance"));

            // check the MIME type
            f = createResult(FAILURE, "The document has no content but a MIME type!", true);
            assertNull(doc.getContentStreamMimeType(), null, f);

            // check the content size
            if (doc.getContentStreamLength() == 0) {
                addResult(createResult(WARNING, "The document has no content but the content length is set to 0! "
                        + "The content length shouldn't be set."));
            } else if (doc.getContentStreamLength() > 0) {
                addResult(createResult(FAILURE, "The document has no content but the content length is set and >0! "
                        + "(content length: " + doc.getContentStreamLength() + ")"));
            }

            // delete it
            doc.delete(true);
        } finally {
            // delete the test folder
            deleteTestFolder();
        }
    }
}
