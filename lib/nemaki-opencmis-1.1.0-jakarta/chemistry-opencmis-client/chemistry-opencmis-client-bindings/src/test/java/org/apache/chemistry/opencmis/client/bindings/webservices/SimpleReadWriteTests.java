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
package org.apache.chemistry.opencmis.client.bindings.webservices;

import java.util.HashSet;
import java.util.Set;

import org.apache.chemistry.opencmis.client.bindings.framework.AbstractSimpleReadWriteTests;
import org.apache.chemistry.opencmis.commons.spi.CmisBinding;

public class SimpleReadWriteTests extends AbstractSimpleReadWriteTests {

    private final Set<String> fTests;

    public SimpleReadWriteTests() {
        fTests = new HashSet<String>();
        fTests.add(TEST_CREATE_FOLDER);
        fTests.add(TEST_CREATE_DOCUMENT);
        fTests.add(TEST_CREATE_FROM_SOURCE);
        fTests.add(TEST_SET_AND_DELETE_CONTENT);
        fTests.add(TEST_UPDATE_PROPERTIES);
        fTests.add(TEST_DELETE_TREE);
        fTests.add(TEST_MOVE_OBJECT);
        fTests.add(TEST_COPY_OBJECT);
        fTests.add(TEST_VERSIONING);
    }

    @Override
    protected CmisBinding createBinding() {
        return WebServicesTestBindingFactory.createBinding(getWebServicesURL(), getUsername(), getPassword());
    }

    @Override
    protected Set<String> getEnabledTests() {
        return fTests;
    }
}
