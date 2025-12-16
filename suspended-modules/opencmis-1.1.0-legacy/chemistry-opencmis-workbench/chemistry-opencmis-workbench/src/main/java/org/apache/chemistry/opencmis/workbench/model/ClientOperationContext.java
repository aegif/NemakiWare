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
package org.apache.chemistry.opencmis.workbench.model;

import java.util.Map;

import org.apache.chemistry.opencmis.client.runtime.OperationContextImpl;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;

public final class ClientOperationContext extends OperationContextImpl {

    private static final long serialVersionUID = 1L;

    public static final String FILTER = "filter";
    public static final String INCLUDE_ACLS = "includeAcls";
    public static final String INCLUDE_ALLOWABLE_ACTIONS = "includeAllowableActions";
    public static final String INCLUDE_POLICIES = "includePolicies";
    public static final String INCLUDE_RELATIONSHIPS = "includeRelationships";
    public static final String RENDITION_FILTER = "renditionFilter";
    public static final String ORDER_BY = "orderBy";
    public static final String MAX_ITEMS_PER_PAGE = "maxItemsPerPage";

    public ClientOperationContext(String prefix, Map<String, String> map) {
        loadContext(prefix, map);
        setIncludePathSegments(false);
        setCacheEnabled(true);
    }

    public void loadContext(String prefix, Map<String, String> map) {
        setFilterString(map.get(prefix + FILTER));
        setIncludeAcls(parseBoolean(map.get(prefix + INCLUDE_ACLS), false));
        setIncludeAllowableActions(parseBoolean(map.get(prefix + INCLUDE_ALLOWABLE_ACTIONS), false));
        setIncludePolicies(parseBoolean(map.get(prefix + INCLUDE_POLICIES), false));
        setIncludeRelationships(parseIncludeRelationships(map.get(prefix + INCLUDE_RELATIONSHIPS),
                IncludeRelationships.NONE));
        setRenditionFilterString(map.get(prefix + RENDITION_FILTER));
        setOrderBy(map.get(prefix + ORDER_BY));
        setMaxItemsPerPage(parseInteger(map.get(prefix + MAX_ITEMS_PER_PAGE), 1000));
    }

    private boolean parseBoolean(String s, boolean defaultValue) {
        return s == null ? defaultValue : Boolean.parseBoolean(s);
    }

    private IncludeRelationships parseIncludeRelationships(String s, IncludeRelationships defaultValue) {
        if (s == null) {
            return defaultValue;
        }

        try {
            return IncludeRelationships.fromValue(s);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int parseInteger(String s, int defaultValue) {
        if (s == null) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
