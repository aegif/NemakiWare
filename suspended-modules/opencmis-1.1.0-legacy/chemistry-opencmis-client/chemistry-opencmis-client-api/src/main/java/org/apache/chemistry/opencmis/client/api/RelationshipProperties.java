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
package org.apache.chemistry.opencmis.client.api;

/**
 * Accessors to CMIS relationship properties.
 * 
 * @see CmisObjectProperties
 */
public interface RelationshipProperties {

    /**
     * Returns the source ID of this CMIS relationship (CMIS property
     * {@code cmis:sourceId}).
     * 
     * @return the source ID or {@code null} if the property hasn't been
     *         requested, hasn't been provided by the repository, or the
     *         property value isn't set
     * 
     * @cmis 1.0
     */
    ObjectId getSourceId();

    /**
     * Returns the target ID of this CMIS relationship (CMIS property
     * {@code cmis:targetId}).
     * 
     * @return the target ID or {@code null} if the property hasn't been
     *         requested, hasn't been provided by the repository, or the
     *         property value isn't set
     * 
     * @cmis 1.0
     */
    ObjectId getTargetId();
}
