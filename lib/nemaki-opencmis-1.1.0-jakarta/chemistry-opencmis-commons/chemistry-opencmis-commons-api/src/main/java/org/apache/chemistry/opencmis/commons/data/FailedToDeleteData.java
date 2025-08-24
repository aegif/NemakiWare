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

import org.apache.chemistry.opencmis.commons.spi.ObjectService;

/**
 * Holder for object IDs of objects that could not be deleted.
 * 
 * @see ObjectService#deleteTree(String, String, Boolean,
 *      org.apache.chemistry.opencmis.commons.enums.UnfileObject, Boolean,
 *      ExtensionsData)
 */
public interface FailedToDeleteData extends ExtensionsData {

    /**
     * Returns the list of object IDs of the objects that haven't been deleted.
     * 
     * @return the list of IDs, not {@code null}
     */
    List<String> getIds();
}
