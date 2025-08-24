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
package org.apache.chemistry.opencmis.inmemory.storedobj.api;

import java.util.List;

/**
 * Path is the capability of an object to get accessed by a path in addition to
 * the identifier. Paths are hierarchical, each object with a path has a parent
 * where the parent is always a folder. Paths do not exist on its own but are
 * part of other objects (documents and folders). Most of the functionality is
 * defined in interfaces that are subclasses.
 */
public interface Filing {

    /**
     * character indicating how folders are separated within a path string. This
     * char must not be a valid character of an object name.
     */
    String PATH_SEPARATOR = "/";

    /**
     * return a list of parents the principal has access to. for single parent
     * object this list must contain only one element. returns an empty list if
     * this is an unfiled document.
     * 
     * @return list of parent folders
     */
    List<String> getParentIds();

    /**
     * usually true except for the root folder, optimized call that just tests
     * existence to provide information for AtomPub links (much cheaper than
     * calling getParents() and test for empty result.
     * 
     * @return true if object has a parent, false if it is a root object
     */
    boolean hasParent();

    /**
     * get the path segment of this object.
     * 
     * @return
     *      String with path segment
     */
    String getPathSegment();
}