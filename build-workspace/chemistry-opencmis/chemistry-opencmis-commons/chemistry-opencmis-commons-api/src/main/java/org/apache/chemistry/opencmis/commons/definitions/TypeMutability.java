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
package org.apache.chemistry.opencmis.commons.definitions;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;

/**
 * Type mutability flags.
 * 
 * @cmis 1.1
 */
public interface TypeMutability extends ExtensionsData {

    /**
     * Indicates if a sub type of this type can be created.
     * 
     * @return <code>true</code> if a sub type can be created,
     *         <code>false</code> otherwise
     * 
     * @cmis 1.1
     */
    Boolean canCreate();

    /**
     * Indicates if this type can be updated.
     * 
     * @return <code>true</code> if this type can be updated, <code>false</code>
     *         otherwise
     * 
     * @cmis 1.1
     */
    Boolean canUpdate();

    /**
     * Indicates if this type can be deleted.
     * 
     * @return <code>true</code> if this type can be deleted, <code>false</code>
     *         otherwise
     * 
     * @cmis 1.1
     */
    Boolean canDelete();
}
