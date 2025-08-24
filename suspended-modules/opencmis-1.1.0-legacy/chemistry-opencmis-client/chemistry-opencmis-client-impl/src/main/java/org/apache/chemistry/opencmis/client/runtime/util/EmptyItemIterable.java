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
import java.util.NoSuchElementException;

import org.apache.chemistry.opencmis.client.api.ItemIterable;

/**
 * An empty item iterable.
 */
public class EmptyItemIterable<T> implements ItemIterable<T> {

    public static final EmptyItemIterable<?> INSTANCE = new EmptyItemIterable<Object>();

    @SuppressWarnings("unchecked")
    public static <U> EmptyItemIterable<U> instance() {
        return (EmptyItemIterable<U>) INSTANCE;
    }

    @Override
    public ItemIterable<T> skipTo(long position) {
        if (position != 0) {
            throw new IllegalArgumentException(String.valueOf(position));
        }
        return this;
    }

    @Override
    public ItemIterable<T> getPage() {
        return this;
    }

    @Override
    public ItemIterable<T> getPage(int maxNumItems) {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<T> iterator() {
        return (Iterator<T>) EmptyIterator.INSTANCE;
    }

    @Override
    public long getPageNumItems() {
        return 0;
    }

    @Override
    public boolean getHasMoreItems() {
        return false;
    }

    @Override
    public long getTotalNumItems() {
        return 0;
    }

    /**
     * An empty iterator.
     */
    public static class EmptyIterator<V> implements Iterator<V> {
        public static final Iterator<?> INSTANCE = new EmptyIterator<Object>();

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public V next() {
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

}
