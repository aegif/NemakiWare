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

/**
 * Mutable ObjectData.
 */
public interface MutableObjectData extends ObjectData {

    /**
     * Sets the properties.
     * 
     * @param properties
     *            the properties of {@code null} to remove all properties
     */
    void setProperties(Properties properties);

    /**
     * Sets the change event info.
     * 
     * @param changeEventInfo
     *            the change event info of {@code null} to remove the change
     *            event info
     */
    void setChangeEventInfo(ChangeEventInfo changeEventInfo);

    /**
     * Sets the relationships.
     * 
     * @param relationships
     *            the list of relationships of {@code null} to remove all
     *            relationships
     */
    void setRelationships(List<ObjectData> relationships);

    /**
     * Sets the renditions.
     * 
     * @param renditions
     *            the list of renditions of {@code null} to remove all
     *            renditions
     */
    void setRenditions(List<RenditionData> renditions);

    /**
     * Sets the policy IDs.
     * 
     * @param policyIds
     *            the policy ID of {@code null} to remove all policy IDs
     */
    void setPolicyIds(PolicyIdList policyIds);

    /**
     * Sets the Allowable Actions.
     * 
     * @param allowableActions
     *            the Allowable Actions of {@code null} to remove the Allowable
     *            Actions
     */
    void setAllowableActions(AllowableActions allowableActions);

    /**
     * Sets the access control list.
     * 
     * @param acl
     *            the access control list of {@code null} to remove the access
     *            control list
     */
    void setAcl(Acl acl);

    /**
     * Sets if the access control list reflects the exact permission set in the
     * repository.
     * <p>
     * Should be set to {@code true} or {@code false} if an access control list
     * exists. Should be set to {@code null} if no access control list exists.
     * 
     * @param isExactACL
     *            the exact flag.
     */
    void setIsExactAcl(Boolean isExactACL);
}
