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
package org.apache.chemistry.opencmis.workbench.checks;

import java.util.Map;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.tck.CmisTestResultStatus;
import org.apache.chemistry.opencmis.tck.impl.AbstractSessionTest;

/**
 * This class checks an object for CMIS specification compliance.
 */
public class ObjectComplianceCheck extends AbstractSessionTest {

    private String objectId;

    public ObjectComplianceCheck(String objectId) {
        this.objectId = objectId;
    }

    @Override
    public final void init(Map<String, String> parameters) {
        super.init(parameters);
        setName("Object Compliance Check");
    }

    @Override
    public final void run(Session session) {
        CmisObject object = session.getObject(objectId, SELECT_ALL_NO_CACHE_OC);
        String[] propertiesToCheck = getAllProperties(object);

        addResult(checkObject(session, object, propertiesToCheck, "Object check: " + object.getId()));

        if (object instanceof Document) {
            addResult(checkVersionHistory(session, object, propertiesToCheck,
                    "Version history check: " + object.getId()));
        } else if (object instanceof Folder) {
            addResult(checkChildren(session, (Folder) object, "Folder children check: " + object.getId()));
        }

        if (getResults().isEmpty()) {
            addResult(createResult(CmisTestResultStatus.OK, "Object seems to be compliant! ID: " + object.getId()));
        }
    }
}
