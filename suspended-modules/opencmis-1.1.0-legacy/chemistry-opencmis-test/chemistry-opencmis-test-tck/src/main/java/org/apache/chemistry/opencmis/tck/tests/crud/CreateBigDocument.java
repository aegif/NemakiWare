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

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.tck.CmisTestResult;
import static org.apache.chemistry.opencmis.tck.CmisTestResultStatus.FAILURE;
import org.apache.chemistry.opencmis.tck.impl.AbstractSessionTest;

/**
 * Big document test.
 */
public class CreateBigDocument extends AbstractSessionTest {

    @Override
    public void init(Map<String, String> parameters) {
        super.init(parameters);
        setName("Create Big Document Test");
        setDescription("Creates a 10 MiB document and deletes it.");
    }

    @Override
    public void run(Session session) {
        CmisTestResult f;

        // create a test folder
        Folder testFolder = createTestFolder(session);

        try {
            String name = "bigdoc";
            String objectTypeId = getDocumentTestTypeId();
            String mimetype = "application/octet-stream";

            final long size = 10 * 1024 * 1024; // 10 MiB
            InputStream in = new InputStream() {
                private int counter = -1;

                @Override
                public int read() throws IOException {
                    counter++;
                    if (counter >= size) {
                        return -1;
                    }

                    return '0' + (counter / 10);
                }
            };

            // create stream and properties
            ContentStream contentStream = session.getObjectFactory().createContentStream(name, size, mimetype, in);

            Map<String, Object> properties = new HashMap<String, Object>();
            properties.put(PropertyIds.NAME, name);
            properties.put(PropertyIds.OBJECT_TYPE_ID, objectTypeId);

            // check type
            TypeDefinition type = session.getTypeDefinition(objectTypeId);
            if (!(type instanceof DocumentTypeDefinition)) {
                addResult(createResult(FAILURE, "Type is not a document type! Type: " + objectTypeId, true));
                return;
            }

            DocumentTypeDefinition docType = (DocumentTypeDefinition) type;
            VersioningState versioningState = (Boolean.TRUE.equals(docType.isVersionable()) ? VersioningState.MAJOR
                    : VersioningState.NONE);

            // create and fetch the document
            ObjectId id = session.createDocument(properties, testFolder, contentStream, versioningState);
            Document doc = (Document) session.getObject(id, SELECT_ALL_NO_CACHE_OC);

            // check the new document
            addResult(checkObject(session, doc, getAllProperties(doc), "New document object spec compliance"));

            // check the size
            f = createResult(FAILURE, "Content stream length doesn't match the uploaded content!", true);
            assertEquals(size, doc.getContentStreamLength(), null, f);

            // delete it by path
            List<String> paths = doc.getPaths();

            f = createResult(FAILURE,
                    "The document must have at least one path because it was created in a folder! Id: " + doc.getId());
            addResult(assertIsTrue(paths != null && paths.size() > 0, null, f));

            session.deleteByPath(paths.get(0));
        } finally {
            // delete the test folder
            deleteTestFolder();
        }
    }
}
