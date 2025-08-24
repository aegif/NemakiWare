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

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;

/**
 * List of type definitions.
 */
public interface TypeDefinitionList extends ExtensionsData {

    /**
     * Returns the list of type definitions.
     */
    List<TypeDefinition> getList();

    /**
     * Returns whether there more type definitions or not.
     * 
     * @return {@code true} if there are more type definitions, {@code false} if
     *         there are no more type definitions, {@code null} if it's unknown
     */
    Boolean hasMoreItems();

    /**
     * Returns the total number of type definitions.
     * 
     * @return total number of type definitions or {@code null} if the total
     *         number is unknown
     */
    BigInteger getNumItems();
}
