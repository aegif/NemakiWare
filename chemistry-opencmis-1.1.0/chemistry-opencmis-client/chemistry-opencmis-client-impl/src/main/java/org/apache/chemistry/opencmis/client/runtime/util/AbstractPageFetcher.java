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
package org.apache.chemistry.opencmis.client.runtime.util;

import java.math.BigInteger;
import java.util.List;

/**
 * Abstract page fetcher.
 *
 * @param <T> the type of items fetched
 */
public abstract class AbstractPageFetcher<T> {

    protected long maxNumItems;

    protected AbstractPageFetcher(long maxNumItems) {
        this.maxNumItems = maxNumItems;
    }

    /**
     * Fetches the given page from the server.
     *
     * @param skipCount initial offset where to start fetching
     */
    protected abstract Page<T> fetchPage(long skipCount);

    /**
     * A fetched page.
     *
     * @param <T> the type of items fetched
     */
    public static class Page<T> {
        private final List<T> items;
        private final Long totalNumItems;
        private final Boolean hasMoreItems;

        public Page(List<T> items, BigInteger totalNumItems, Boolean hasMoreItems) {
            this.items = items;
            this.totalNumItems = totalNumItems == null ? null
                    : Long.valueOf(totalNumItems.longValue());
            this.hasMoreItems = hasMoreItems;
        }

        public Page(List<T> items, long totalNumItems, boolean hasMoreItems) {
            this.items = items;
            this.totalNumItems = Long.valueOf(totalNumItems);
            this.hasMoreItems = Boolean.valueOf(hasMoreItems);
        }

        public List<T> getItems() {
            return items;
        }

        public Long getTotalNumItems() {
            return totalNumItems;
        }

        public Boolean getHasMoreItems() {
            return hasMoreItems;
        }
    }

    public void setMaxNumItems(int maxNumItems) {
        this.maxNumItems = maxNumItems;
    }

}
