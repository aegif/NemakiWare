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

import org.apache.chemistry.opencmis.client.api.ItemIterable;

/**
 * Abstract <code>ItemIterable</code> implementation.
 *
 * @param <T> the type returned by the iterable's iterator
 */
public abstract class AbstractIterable<T> implements ItemIterable<T> {

    private final AbstractPageFetcher<T> pageFetcher;
    private final long skipCount;
    private AbstractIterator<T> iterator;

    protected AbstractIterable(AbstractPageFetcher<T> pageFetcher) {
        this(0, pageFetcher);
    }

    protected AbstractIterable(long position, AbstractPageFetcher<T> pageFetcher) {
        this.pageFetcher = pageFetcher;
        this.skipCount = position;
    }

    /**
     * Gets the skip count
     *
     * @return  skip count
     */
    protected long getSkipCount() {
        return skipCount;
    }

    /**
     * Gets the page fetcher
     *
     * @return  page fetcher
     */
    protected AbstractPageFetcher<T> getPageFetcher() {
        return pageFetcher;
    }

    /**
     * Construct the iterator
     *
     * @return  iterator
     */
    protected abstract AbstractIterator<T> createIterator();

    @Override
    public AbstractIterator<T> iterator() {
        return getIterator();
    }

    @Override
    public ItemIterable<T> skipTo(long position) {
        return new CollectionIterable<T>(position, pageFetcher);
    }

    @Override
    public ItemIterable<T> getPage() {
        return new CollectionPageIterable<T>(skipCount, pageFetcher);
    }

    @Override
    public ItemIterable<T> getPage(int maxNumItems) {
        this.pageFetcher.setMaxNumItems(maxNumItems);
        return new CollectionPageIterable<T>(skipCount, pageFetcher);
    }

    @Override
    public long getPageNumItems() {
        return getIterator().getPageNumItems();
    }

    @Override
    public boolean getHasMoreItems() {
        return getIterator().getHasMoreItems();
    }

    @Override
    public long getTotalNumItems() {
        return getIterator().getTotalNumItems();
    }

    private AbstractIterator<T> getIterator() {
        if (this.iterator == null) {
            this.iterator = createIterator();
        }
        return this.iterator;
    }
}

