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
 * A folder is a StoredObject that that has a path and children. Children can be
 * folder or documents
 */
public interface Children {

    /**
     * Class to represent a result of get children calls
     *
     */
    public final class ChildrenResult {
        private int noItems;
        private List<? extends StoredObject> children;

        private ChildrenResult(List<? extends StoredObject> children, int noItems) {
            this.children = children;
            this.noItems = noItems;
        }

        /**
         * Get number of items in this result.
         * @return
         *  number of items
         */
        public int getNoItems() {
            return noItems;
        }

        /**
         * Get the children objects.
         * @return
         *      list of children
         */
        public List<? extends StoredObject> getChildren() {
            return children;
        }
    }

    /**
     * get all the children of this folder. To support paging an initial offset
     * and a maximum number of children to retrieve can be passed.
     * 
     * @param maxItems
     *            max. number of items to return
     * @param skipCount
     *            initial offset where to start fetching
     * @param user
     *            user to determine visible children
     * @param usePwc
     *            if true return private working copy otherwise return latest
     *            version;
     * 
     * @return list of children objects
     */
    ChildrenResult getChildren(int maxItems, int skipCount, String user, boolean usePwc);

    /**
     * get all the children of this folder which are folders. To support paging
     * an initial offset and a maximum number of children to retrieve can be
     * passed.
     * 
     * @param maxItems
     *            max. number of items to return
     * @param skipCount
     *            initial offset where to start fetching
     * @param user
     *          user to determine visible children
     * @return list of children folders
     */
    ChildrenResult getFolderChildren(int maxItems, int skipCount, String user);

    /**
     * indicate if a child with the given name exists in this folder.
     * 
     * @param name
     *            name to check
     * @return true if the name exists in the folderas child, false otherwise
     */
    boolean hasChild(String name);

}