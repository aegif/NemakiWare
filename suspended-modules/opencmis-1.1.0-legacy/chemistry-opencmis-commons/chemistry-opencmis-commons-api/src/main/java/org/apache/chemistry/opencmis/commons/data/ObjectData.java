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
package org.apache.chemistry.opencmis.commons.data;

import java.util.List;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;

/**
 * Base object for CMIS documents, folders, relationships, policies, and items.
 */
public interface ObjectData extends ExtensionsData {

    /**
     * Returns the object ID.
     * 
     * @return the object ID or {@code null} if the object ID is unknown
     */
    String getId();

    /**
     * Returns the base object type.
     * 
     * @return the base object type or {@code null} if the base object type is
     *         unknown
     */
    BaseTypeId getBaseTypeId();

    /**
     * Returns the object properties. The properties can be incomplete if a
     * property filter was used.
     * 
     * @return the properties or {@code null} if no properties are known
     */
    Properties getProperties();

    /**
     * Returns the allowable actions.
     * 
     * @return the allowable actions or {@code null} if the allowable actions
     *         are unknown
     */
    AllowableActions getAllowableActions();

    /**
     * Returns the relationships from and to this object.
     * 
     * @return the list of relationship objects, not {@code null}
     */
    List<ObjectData> getRelationships();

    /**
     * Returns the change event infos.
     * 
     * @return the change event infos or {@code null} if the infos are unknown
     */
    ChangeEventInfo getChangeEventInfo();

    /**
     * Returns the access control list.
     * 
     * @return the access control list or {@code null} if the access control
     *         list is unknown
     */
    Acl getAcl();

    /**
     * Returns if the access control list reflects the exact permission set in
     * the repository.
     * 
     * @return {@code true} - exact; {@code false} - not exact, other permission
     *         constraints exist; {@code null} - unknown
     */
    Boolean isExactAcl();

    /**
     * Returns the IDs of the applied policies.
     * 
     * @return the policy IDs or {@code null} if no policies are applied or the
     *         IDs are unknown
     */
    PolicyIdList getPolicyIds();

    /**
     * Returns the renditions of this object.
     * 
     * @return the list of renditions, not {@code null}
     */
    List<RenditionData> getRenditions();
}