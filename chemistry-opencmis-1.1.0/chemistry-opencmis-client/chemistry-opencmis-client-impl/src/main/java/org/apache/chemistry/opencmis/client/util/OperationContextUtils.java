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
package org.apache.chemistry.opencmis.client.util;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.runtime.OperationContextImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;

/**
 * Constants and methods to create and manipulate {@link OperationContext}
 * objects.
 */
public final class OperationContextUtils {

    private static final char[] INVALID_QUERY_NAME_CHARS = " \t\n\r\f\"',\\.()".toCharArray();

    public static final String PROPERTIES_STAR = "*";
    public static final String RENDITION_NONE = "cmis:none";

    private OperationContextUtils() {
    }

    /**
     * Creates a new OperationContext object.
     */
    public static OperationContext createOperationContext() {
        return new OperationContextImpl();
    }

    /**
     * Copies an OperationContext object.
     */
    public static OperationContext copyOperationContext(OperationContext context) {
        return new OperationContextImpl(context);
    }

    /**
     * Creates a new OperationContext object with the given parameters.
     */
    public static OperationContext createOperationContext(Set<String> filter, boolean includeAcls,
            boolean includeAllowableActions, boolean includePolicies, IncludeRelationships includeRelationships,
            Set<String> renditionFilter, boolean includePathSegments, String orderBy, boolean cacheEnabled,
            int maxItemsPerPage) {
        if (!checkQueryNames(filter)) {
            throw new IllegalArgumentException("Invalid filter: " + filter);
        }

        return new OperationContextImpl(filter, includeAcls, includeAllowableActions, includePolicies,
                includeRelationships, renditionFilter, includePathSegments, orderBy, cacheEnabled, maxItemsPerPage);
    }

    /**
     * Creates a new OperationContext object that only selects the bare minimum.
     * Caching is enabled.
     */
    public static OperationContext createMinimumOperationContext() {
        return createMinimumOperationContext((String[]) null);
    }

    /**
     * Creates a new OperationContext object that only selects the bare minimum
     * plus the provided properties. Caching is enabled.
     */
    public static OperationContext createMinimumOperationContext(String... propertyQueryNames) {
        Set<String> filter = new HashSet<String>();
        filter.add(PropertyIds.OBJECT_ID);
        filter.add(PropertyIds.OBJECT_TYPE_ID);
        filter.add(PropertyIds.BASE_TYPE_ID);

        if (propertyQueryNames != null) {
            for (String prop : propertyQueryNames) {
                if (!checkQueryName(prop)) {
                    throw new IllegalArgumentException("Not a valid property query name: " + prop);
                }
                filter.add(prop);
            }
        }

        return new OperationContextImpl(filter, false, false, false, IncludeRelationships.NONE,
                Collections.singleton(RENDITION_NONE), false, null, true, 100);
    }

    /**
     * Creates a new OperationContext object that selects everything.
     */
    public static OperationContext createMaximumOperationContext() {
        return new OperationContextImpl(Collections.singleton(PROPERTIES_STAR), true, true, true,
                IncludeRelationships.BOTH, Collections.singleton("*"), false, null, true, 100);
    }

    /**
     * Returns an unmodifiable view of the specified OperationContext.
     * 
     * Attempts to modify the returned OperationContext object result in an
     * {@code UnsupportedOperationException}.
     */
    public static OperationContext unmodifiableOperationContext(final OperationContext context) {
        return new OperationContext() {

            private static final long serialVersionUID = 1L;

            @Override
            public Set<String> getFilter() {
                return Collections.unmodifiableSet(context.getFilter());
            }

            @Override
            public void setFilter(Set<String> propertyFilter) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setFilterString(String propertyFilter) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getFilterString() {
                return context.getFilterString();
            }

            @Override
            public void setLoadSecondaryTypeProperties(boolean load) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean loadSecondaryTypeProperties() {
                return context.loadSecondaryTypeProperties();
            }

            @Override
            public boolean isIncludeAllowableActions() {
                return context.isIncludeAllowableActions();
            }

            @Override
            public void setIncludeAllowableActions(boolean include) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isIncludeAcls() {
                return context.isIncludeAcls();
            }

            @Override
            public void setIncludeAcls(boolean include) {
                throw new UnsupportedOperationException();
            }

            @Override
            public IncludeRelationships getIncludeRelationships() {
                return context.getIncludeRelationships();
            }

            @Override
            public void setIncludeRelationships(IncludeRelationships include) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isIncludePolicies() {
                return context.isIncludePolicies();
            }

            @Override
            public void setIncludePolicies(boolean include) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Set<String> getRenditionFilter() {
                return Collections.unmodifiableSet(context.getRenditionFilter());
            }

            @Override
            public void setRenditionFilter(Set<String> renditionFilter) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setRenditionFilterString(String renditionFilter) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getRenditionFilterString() {
                return context.getRenditionFilterString();
            }

            @Override
            public boolean isIncludePathSegments() {
                return context.isIncludePathSegments();
            }

            @Override
            public void setIncludePathSegments(boolean include) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getOrderBy() {
                return context.getOrderBy();
            }

            @Override
            public void setOrderBy(String orderBy) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isCacheEnabled() {
                return context.isCacheEnabled();
            }

            @Override
            public void setCacheEnabled(boolean cacheEnabled) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getCacheKey() {
                return context.getCacheKey();
            }

            @Override
            public void setMaxItemsPerPage(int maxItemsPerPage) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getMaxItemsPerPage() {
                return context.getMaxItemsPerPage();
            }

            @Override
            public String toString() {
                return context.toString();
            }
        };
    }

    // --- helpers ---

    /**
     * Checks if a property query name is valid.
     * 
     * @param queryName
     *            the query name
     * @return {@code true} if the query name is valid, {@code false} otherwise
     */
    private static boolean checkQueryName(String queryName) {
        if (queryName == null || queryName.length() == 0) {
            return false;
        }

        int n = queryName.length();
        for (int i = 0; i < n; i++) {
            char c = queryName.charAt(i);
            for (char ic : INVALID_QUERY_NAME_CHARS) {
                if (c == ic) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Checks if a set of property query name is valid.
     * 
     * @param queryNames
     *            the query names
     * @return {@code true} if all query names are valid, {@code false}
     *         otherwise
     */
    private static boolean checkQueryNames(Collection<String> queryNames) {
        if (queryNames == null || queryNames.isEmpty()) {
            return true;
        }

        for (String qn : queryNames) {
            if (!checkQueryName(qn)) {
                return false;
            }
        }

        return true;
    }
}
