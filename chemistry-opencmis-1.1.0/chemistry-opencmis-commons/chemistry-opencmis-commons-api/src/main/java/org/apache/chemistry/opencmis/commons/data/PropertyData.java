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

/**
 * Base property interface.
 */
public interface PropertyData<T> extends ExtensionsData {

    /**
     * Returns the property ID.
     * <p>
     * The property ID may not be set if the object is used in a query result.
     * 
     * 
     * @return the property ID, may be {@code null}
     */
    String getId();

    /**
     * Returns the local name.
     * 
     * @return the local name, may be {@code null}
     */
    String getLocalName();

    /**
     * Returns the display name.
     * 
     * @return the display name, may be {@code null}
     */
    String getDisplayName();

    /**
     * Returns the query name.
     * <p>
     * The property query name must be set if the object is used in a query
     * result.
     * 
     * @return the query name, may be {@code null}
     */
    String getQueryName();

    /**
     * Returns the list of values of this property. For a single value property
     * this is a list with one entry.
     * 
     * @return the list of values, not {@code null}
     */
    List<T> getValues();

    /**
     * Returns the first entry of the list of values.
     * 
     * @return first entry in the list of values or {@code null} if the list of
     *         values is empty
     */
    T getFirstValue();
}
