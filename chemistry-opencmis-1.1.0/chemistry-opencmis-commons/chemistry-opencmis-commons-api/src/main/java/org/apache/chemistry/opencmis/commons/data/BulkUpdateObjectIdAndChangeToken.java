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

/**
 * Holder for bulkUpdateObject data.
 * 
 * @cmis 1.1
 */
public interface BulkUpdateObjectIdAndChangeToken extends ExtensionsData {

    /**
     * Returns the object ID.
     * 
     * @return the object ID
     */
    String getId();

    /**
     * Returns the new object ID if the repository created a new object during
     * the update.
     * 
     * @return the new object ID or {@code null} if no new object has been
     *         created
     */
    String getNewId();

    /**
     * Returns the change token of the object.
     * 
     * @return the change token or {@code null} if the repository does not
     *         support change tokens
     */
    String getChangeToken();
}
