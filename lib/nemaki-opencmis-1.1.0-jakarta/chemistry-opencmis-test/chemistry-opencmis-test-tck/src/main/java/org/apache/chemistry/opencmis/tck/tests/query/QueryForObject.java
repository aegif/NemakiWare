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
package org.apache.chemistry.opencmis.tck.tests.query;

import static org.apache.chemistry.opencmis.tck.CmisTestResultStatus.FAILURE;
import static org.apache.chemistry.opencmis.tck.CmisTestResultStatus.SKIPPED;

import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.QueryStatement;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.tck.CmisTestResult;

public class QueryForObject extends AbstractQueryTest {

    private static final String CONTENT = "TCK test content.";

    @Override
    public void init(Map<String, String> parameters) {
        super.init(parameters);
        setName("Query For Object Test");
        setDescription("Creates a folder and a document, queries them by object id, and deletes both.");
    }

    @Override
    public void run(Session session) {
        if (supportsQuery(session) && !isFulltextOnly(session)) {
            // create a test folder
            Folder testFolder = createTestFolder(session);

            try {
                // create a test document
                Document document = createDocument(session, testFolder, "testdoc.txt", CONTENT);

                // find the folder
                runFolderQuery(session, testFolder);

                // find the document
                runDocumentQuery(session, document);

                // clean up
                document.delete(true);
            } finally {
                // delete the test folder
                deleteTestFolder();
            }

        } else {
            addResult(createResult(SKIPPED, "Metadata query not supported. Test Skipped!"));
        }
    }

    public void runFolderQuery(Session session, Folder testFolder) {
        if (!Boolean.TRUE.equals(testFolder.getType().isQueryable())) {
            addResult(createResult(SKIPPED, "Folder type '" + testFolder.getType().getId()
                    + "' is not queryable. Folder query test skipped!"));
            return;
        }

        CmisTestResult f;

        QueryStatement[] statements = new QueryStatement[] {
                session.createQueryStatement("SELECT * FROM ? WHERE ? = ?"),
                session.createQueryStatement("SELECT * FROM ? WHERE ? IN (?)") };

        for (QueryStatement statement : statements) {
            statement.setType(1, testFolder.getType());
            statement.setProperty(2, testFolder.getType().getPropertyDefinitions().get(PropertyIds.OBJECT_ID));
            statement.setString(3, testFolder.getId());

            addResult(createInfoResult("Folder query: " + statement.toQueryString()));

            int count = 0;
            for (QueryResult qr : statement.query(false)) {
                count++;

                String objectId = qr.getPropertyValueByQueryName("cmis:objectId");

                f = createResult(FAILURE, "Folder query returned unexpected object. Id: " + objectId);
                addResult(assertEquals(testFolder.getId(), objectId, null, f));
            }

            f = createResult(FAILURE, "Folder query should return exactly one hit, but returned " + count + ".");
            addResult(assertEquals(1, count, null, f));
        }
    }

    public void runDocumentQuery(Session session, Document testDocument) {
        if (!Boolean.TRUE.equals(testDocument.getType().isQueryable())) {
            addResult(createResult(SKIPPED, "Document type '" + testDocument.getType().getId()
                    + "' is not queryable. Document query test skipped!"));
            return;
        }

        CmisTestResult f;

        QueryStatement[] statements = new QueryStatement[] {
                session.createQueryStatement("SELECT * FROM ? WHERE ? = ?"),
                session.createQueryStatement("SELECT * FROM ? WHERE ? IN (?)") };

        for (QueryStatement statement : statements) {
            statement.setType(1, testDocument.getType());
            statement.setProperty(2, testDocument.getType().getPropertyDefinitions().get(PropertyIds.OBJECT_ID));
            statement.setString(3, testDocument.getId());

            addResult(createInfoResult("Document query: " + statement.toQueryString()));

            int count = 0;
            for (QueryResult qr : statement.query(false)) {
                count++;

                String objectId = qr.getPropertyValueByQueryName("cmis:objectId");

                f = createResult(FAILURE, "Document query returned unexpected object. Id: " + objectId);
                addResult(assertEquals(testDocument.getId(), objectId, null, f));
            }

            f = createResult(FAILURE, "Document query should return exactly one hit, but returned " + count + ".");
            addResult(assertEquals(1, count, null, f));
        }
    }
}
