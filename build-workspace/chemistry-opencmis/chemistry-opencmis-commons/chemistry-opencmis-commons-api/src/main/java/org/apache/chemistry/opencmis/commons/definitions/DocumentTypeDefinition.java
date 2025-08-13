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

import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;

/**
 * Document Type Definition.
 * 
 * @cmis 1.0
 */
public interface DocumentTypeDefinition extends TypeDefinition {

    /**
     * Returns whether objects of this type are versionable or not.
     * 
     * @return {code true} if the document type is versionable, {code false} if
     *         is is not versionable, or {code null} if an incompliant
     *         repository does not provide this value
     * 
     * @cmis 1.0
     */
    Boolean isVersionable();

    /**
     * Returns if a content stream must be set.
     * 
     * @return if a content stream must be set or {code null} if an incompliant
     *         repository does not provide this value
     * 
     * @cmis 1.0
     */
    ContentStreamAllowed getContentStreamAllowed();
}
