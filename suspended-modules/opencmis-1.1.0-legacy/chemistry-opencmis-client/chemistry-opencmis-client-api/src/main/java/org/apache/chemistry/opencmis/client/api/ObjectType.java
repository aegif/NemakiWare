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

import java.util.List;

import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;

/**
 * Object Type.
 * 
 * @cmis 1.0
 */
public interface ObjectType extends TypeDefinition {

    /**
     * Indicates whether this is base object type or not.
     * 
     * @return {@code true} if this type is a base type, {@code false} if this
     *         type is a derived type
     * 
     * @cmis 1.0
     */
    boolean isBaseType();

    /**
     * Gets the types base type, if the type is a derived (non-base) type.
     * 
     * @return the base type this type is derived from, or {@code null} if it is
     *         a base type
     * 
     * @cmis 1.0
     */
    ObjectType getBaseType();

    /**
     * Gets the types parent type, if the type is a derived (non-base) type.
     * 
     * @return the parent type from which this type is derived, or {@code null}
     *         if it is a base type
     * 
     * @cmis 1.0
     */
    ObjectType getParentType();

    /**
     * Gets the list of types directly derived from this type (which will return
     * this type on {@code getParent()}).
     * 
     * @return list of types which are directly derived from this type
     * 
     * @cmis 1.0
     */
    ItemIterable<ObjectType> getChildren();

    /**
     * Gets the list of all types somehow derived from this type.
     * 
     * @param depth
     *            the tree depth, must be greater than 0 or -1 for infinite
     *            depth
     * 
     * @return a list of trees of types which are derived from this type (direct
     *         and via their parents)
     * 
     * @cmis 1.0
     */
    List<Tree<ObjectType>> getDescendants(int depth);

}
