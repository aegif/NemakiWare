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
package org.apache.chemistry.opencmis.commons.spi;

import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;

/**
 * Relationship Service interface.
 * 
 * <p>
 * <em>
 * See the CMIS 1.0 and CMIS 1.1 specifications for details on the operations, parameters,
 * exceptions and the domain model.
 * </em>
 * </p>
 */
public interface RelationshipService {

    /**
     * Gets all or a subset of relationships associated with an independent
     * object.
     * 
     * @param repositoryId
     *            the identifier for the repository
     * @param objectId
     *            the object
     * @param extension
     *            extension data
     * @return the list of relationships
     */
    ObjectList getObjectRelationships(String repositoryId, String objectId, Boolean includeSubRelationshipTypes,
            RelationshipDirection relationshipDirection, String typeId, String filter, Boolean includeAllowableActions,
            BigInteger maxItems, BigInteger skipCount, ExtensionsData extension);
}
