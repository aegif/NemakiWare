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
import java.util.Map;

/**
 * Represents a set of properties.
 */
public interface Properties extends ExtensionsData {

    /**
     * Returns a map of properties (property ID =&gt; property).
     * <p>
     * This method should not be used with queries because some repositories
     * don't set property IDs, and because when dealing with queries the proper
     * key is usually the query name (when using JOINs, several properties with
     * the same ID may be returned).
     * 
     * @return the map of properties, not {@code null}
     */
    Map<String, PropertyData<?>> getProperties();

    /**
     * Returns the list of properties.
     * 
     * @return the list of properties, not {@code null}
     */
    List<PropertyData<?>> getPropertyList();
}
