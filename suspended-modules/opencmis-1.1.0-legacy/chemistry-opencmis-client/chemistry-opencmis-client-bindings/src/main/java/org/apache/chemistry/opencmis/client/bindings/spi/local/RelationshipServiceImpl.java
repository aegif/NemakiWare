/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.chemistry.opencmis.client.bindings.spi.local;

import java.math.BigInteger;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.spi.RelationshipService;

public class RelationshipServiceImpl extends AbstractLocalService implements RelationshipService {

    /**
     * Constructor.
     */
    public RelationshipServiceImpl(BindingSession session, CmisServiceFactory factory) {
        setSession(session);
        setServiceFactory(factory);
    }

    @Override
    public ObjectList getObjectRelationships(String repositoryId, String objectId, Boolean includeSubRelationshipTypes,
            RelationshipDirection relationshipDirection, String typeId, String filter, Boolean includeAllowableActions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
        CmisService service = getService(repositoryId);

        try {
            if (stopBeforeService(service)) {
                return null;
            }

            ObjectList serviceResult = service.getObjectRelationships(repositoryId, objectId,
                    includeSubRelationshipTypes, relationshipDirection, typeId, filter, includeAllowableActions,
                    maxItems, skipCount, extension);

            if (stopAfterService(service)) {
                return null;
            }

            return serviceResult;
        } finally {
            service.close();
        }
    }
}
