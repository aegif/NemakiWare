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

import java.util.Iterator;
import java.util.List;

import org.apache.chemistry.opencmis.client.runtime.util.AbstractPageFetcher.Page;

/**
 * Abstract {@code Iterator} implementation.
 *
 * @param <T>
 *            the type returned by the iterator
 */
public abstract class AbstractIterator<T> implements Iterator<T> {

    private long skipCount;
    private int skipOffset;
    private final AbstractPageFetcher<T> pageFetcher;

    private Page<T> page;
    private Long totalNumItems;
    private Boolean hasMoreItems;

    /**
     * Constructor.
     */
    protected AbstractIterator(long skipCount, AbstractPageFetcher<T> pageFetcher) {
        this.skipCount = skipCount;
        this.pageFetcher = pageFetcher;
    }

    public long getPosition() {
        return skipCount + skipOffset;
    }

    public long getPageNumItems() {
        Page<T> currentPage = getCurrentPage();
        if (currentPage != null) {
            List<T> items = currentPage.getItems();
            if (items != null) {
                return items.size();
            }
        }
        return 0L;
    }

    public long getTotalNumItems() {
        if (totalNumItems == null) {
            totalNumItems = Long.valueOf(-1);
            Page<T> currentPage = getCurrentPage();
            if (currentPage != null) {
                // set number of items
                if (currentPage.getTotalNumItems() != null) {
                    totalNumItems = currentPage.getTotalNumItems();
                }
            }
        }
        return totalNumItems.longValue();
    }

    public boolean getHasMoreItems() {
        if (hasMoreItems == null) {
            hasMoreItems = Boolean.FALSE;
            Page<T> currentPage = getCurrentPage();
            if (currentPage != null) {
                if (currentPage.getHasMoreItems() != null) {
                    hasMoreItems = currentPage.getHasMoreItems();
                }
            }
        }
        return hasMoreItems.booleanValue();
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets current skip count
     *
     * @return skip count
     */
    protected long getSkipCount() {
        return skipCount;
    }

    /**
     * Gets current skip offset (from skip count)
     *
     * @return skip offset
     */
    protected int getSkipOffset() {
        return skipOffset;
    }

    /**
     * Increment the skip offset by one
     *
     * @return incremented skip offset
     */
    protected int incrementSkipOffset() {
        return skipOffset++;
    }

    /**
     * Gets the current page of items within collection
     *
     * @return current page
     */
    protected Page<T> getCurrentPage() {
        if (page == null) {
            page = pageFetcher.fetchPage(skipCount);
        }
        return page;
    }

    /**
     * Skip to the next page of items within collection
     *
     * @return next page
     */
    protected Page<T> incrementPage() {
        skipCount += skipOffset;
        skipOffset = 0;
        totalNumItems = null;
        hasMoreItems = null;
        page = pageFetcher.fetchPage(skipCount);
        return page;
    }

}
