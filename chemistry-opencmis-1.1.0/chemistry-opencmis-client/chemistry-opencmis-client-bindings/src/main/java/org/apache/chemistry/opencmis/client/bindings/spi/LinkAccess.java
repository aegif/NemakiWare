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
package org.apache.chemistry.opencmis.client.bindings.spi;

/**
 * Provides access to internal links. It bypasses the CMIS domain model. Use
 * with care!
 */
public interface LinkAccess {

    /**
     * Gets a link from the cache if it is there or loads it into the cache if
     * it is not there.
     */
    String loadLink(String repositoryId, String objectId, String rel, String type);

    /**
     * Gets the content link from the cache if it is there or loads it into the
     * cache if it is not there.
     */
    String loadContentLink(String repositoryId, String documentId);

    /**
     * Gets a rendition content link from the cache if it is there or loads it
     * into the cache if it is not there.
     */
    String loadRenditionContentLink(String repositoryId, String documentId, String streamId);
}
