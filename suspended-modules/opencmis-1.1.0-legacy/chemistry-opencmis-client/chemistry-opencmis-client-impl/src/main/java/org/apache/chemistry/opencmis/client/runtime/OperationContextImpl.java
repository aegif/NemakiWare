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
package org.apache.chemistry.opencmis.client.runtime;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;

/**
 * {@link OperationContext} implementation.
 */
public class OperationContextImpl implements OperationContext, Serializable {

    public static final String PROPERTIES_STAR = "*";
    public static final String RENDITION_NONE = "cmis:none";

    private static final long serialVersionUID = 1L;

    private TreeSet<String> filter;
    private boolean loadSecondaryTypeProperties;
    private boolean includeAcls;
    private boolean includeAllowableActions;
    private boolean includePolicies;
    private IncludeRelationships includeRelationships;
    private TreeSet<String> renditionFilter;
    private boolean includePathSegments;
    private String orderBy;
    private boolean cacheEnabled;
    private String cacheKey;
    private int maxItemsPerPage;

    /**
     * Default constructor.
     */
    public OperationContextImpl() {
        setFilter(null);
        setLoadSecondaryTypeProperties(false);
        setIncludeAcls(false);
        setIncludeAllowableActions(true);
        setIncludePolicies(false);
        setIncludeRelationships(IncludeRelationships.NONE);
        setRenditionFilter(null);
        setIncludePathSegments(true);
        setOrderBy(null);
        setCacheEnabled(false);
        generateCacheKey();

        // default page size is 100
        setMaxItemsPerPage(100);
    }

    /**
     * Copy constructor.
     */
    public OperationContextImpl(OperationContext source) {
        setFilter(source.getFilter());
        setLoadSecondaryTypeProperties(source.loadSecondaryTypeProperties());
        setIncludeAcls(source.isIncludeAcls());
        setIncludeAllowableActions(source.isIncludeAllowableActions());
        setIncludePolicies(source.isIncludePolicies());
        setIncludeRelationships(source.getIncludeRelationships());
        setRenditionFilter(source.getRenditionFilter());
        setIncludePathSegments(source.isIncludePathSegments());
        setOrderBy(source.getOrderBy());
        setCacheEnabled(source.isCacheEnabled());
        generateCacheKey();

        setMaxItemsPerPage(source.getMaxItemsPerPage());
    }

    /**
     * Constructor with parameters.
     */
    public OperationContextImpl(Set<String> propertyFilter, boolean includeAcls, boolean includeAllowableActions,
            boolean includePolicies, IncludeRelationships includeRelationships, Set<String> renditionFilter,
            boolean includePathSegments, String orderBy, boolean cacheEnabled, int maxItemsPerPage) {
        setFilter(propertyFilter);
        setIncludeAcls(includeAcls);
        setIncludeAllowableActions(includeAllowableActions);
        setIncludePolicies(includePolicies);
        setIncludeRelationships(includeRelationships);
        setRenditionFilter(renditionFilter);
        setIncludePathSegments(includePathSegments);
        setOrderBy(orderBy);
        setCacheEnabled(cacheEnabled);
        generateCacheKey();

        setMaxItemsPerPage(maxItemsPerPage);
    }

    @Override
    public final Set<String> getFilter() {
        if (filter == null) {
            return null;
        }

        return Collections.unmodifiableSet(filter);
    }

    @Override
    public final void setFilter(Set<String> propertyFilter) {
        if (propertyFilter != null) {
            TreeSet<String> tempSet = new TreeSet<String>();

            for (String oid : propertyFilter) {
                if (oid == null) {
                    continue;
                }

                String toid = oid.trim();
                if (toid.length() == 0) {
                    continue;
                }
                if (toid.equals(PROPERTIES_STAR)) {
                    tempSet = new TreeSet<String>();
                    tempSet.add(PROPERTIES_STAR);
                    break;
                }
                if (toid.indexOf(',') > -1) {
                    throw new IllegalArgumentException("Query id must not contain a comma!");
                }

                tempSet.add(toid);
            }

            if (tempSet.isEmpty()) {
                filter = null;
            } else {
                filter = tempSet;
            }
        } else {
            filter = null;
        }

        generateCacheKey();
    }

    @Override
    public final void setFilterString(String propertyFilter) {
        if (propertyFilter == null || propertyFilter.trim().length() == 0) {
            setFilter(null);
            return;
        }

        String[] propertyIds = propertyFilter.split(",");
        TreeSet<String> tempSet = new TreeSet<String>();
        for (String pid : propertyIds) {
            tempSet.add(pid);
        }

        setFilter(tempSet);
    }

    @Override
    public final String getFilterString() {
        if (filter == null) {
            return null;
        }

        if (filter.contains(PROPERTIES_STAR)) {
            return PROPERTIES_STAR;
        }

        filter.add(PropertyIds.OBJECT_ID);
        filter.add(PropertyIds.BASE_TYPE_ID);
        filter.add(PropertyIds.OBJECT_TYPE_ID);
        if (loadSecondaryTypeProperties) {
            filter.add(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
        }

        StringBuilder sb = new StringBuilder(128);

        for (String oid : filter) {
            if (sb.length() > 0) {
                sb.append(',');
            }

            sb.append(oid);
        }

        return sb.toString();
    }

    @Override
    public final void setLoadSecondaryTypeProperties(boolean load) {
        loadSecondaryTypeProperties = load;
    }

    @Override
    public final boolean loadSecondaryTypeProperties() {
        return loadSecondaryTypeProperties;
    }

    @Override
    public final boolean isIncludeAcls() {
        return includeAcls;
    }

    @Override
    public final void setIncludeAcls(boolean include) {
        includeAcls = include;
        generateCacheKey();
    }

    @Override
    public final boolean isIncludeAllowableActions() {
        return includeAllowableActions;
    }

    @Override
    public final void setIncludeAllowableActions(boolean include) {
        includeAllowableActions = include;
        generateCacheKey();
    }

    @Override
    public final boolean isIncludePolicies() {
        return includePolicies;
    }

    @Override
    public final void setIncludePolicies(boolean include) {
        includePolicies = include;
        generateCacheKey();
    }

    @Override
    public final IncludeRelationships getIncludeRelationships() {
        return includeRelationships;
    }

    @Override
    public final void setIncludeRelationships(IncludeRelationships include) {
        includeRelationships = include;
        generateCacheKey();
    }

    @Override
    public final Set<String> getRenditionFilter() {
        if (renditionFilter == null) {
            return null;
        }

        return Collections.unmodifiableSet(renditionFilter);
    }

    @Override
    public final void setRenditionFilter(Set<String> renditionFilter) {
        TreeSet<String> tempSet = new TreeSet<String>();

        if (renditionFilter != null) {
            for (String rf : renditionFilter) {
                if (rf == null) {
                    continue;
                }

                String trf = rf.trim();
                if (trf.length() == 0) {
                    continue;
                }
                if (trf.indexOf(',') > -1) {
                    throw new IllegalArgumentException("Rendition must not contain a comma!");
                }

                tempSet.add(trf);
            }

            if (tempSet.isEmpty()) {
                tempSet.add(RENDITION_NONE);
            }
        } else {
            tempSet.add(RENDITION_NONE);
        }

        this.renditionFilter = tempSet;
        generateCacheKey();
    }

    @Override
    public final void setRenditionFilterString(String renditionFilter) {
        if (renditionFilter == null || renditionFilter.trim().length() == 0) {
            setRenditionFilter(null);
            return;
        }

        String[] renditions = renditionFilter.split(",");
        TreeSet<String> tempSet = new TreeSet<String>();
        for (String rend : renditions) {
            tempSet.add(rend);
        }

        setRenditionFilter(tempSet);
    }

    @Override
    public final String getRenditionFilterString() {
        if (renditionFilter == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(128);

        for (String rf : renditionFilter) {
            if (sb.length() > 0) {
                sb.append(',');
            }

            sb.append(rf);
        }

        return sb.toString();
    }

    @Override
    public final boolean isIncludePathSegments() {
        return includePathSegments;
    }

    @Override
    public final void setIncludePathSegments(boolean include) {
        includePathSegments = include;
    }

    @Override
    public final String getOrderBy() {
        return orderBy;
    }

    @Override
    public final void setOrderBy(String orderBy) {
        this.orderBy = orderBy;
    }

    @Override
    public final boolean isCacheEnabled() {
        return cacheEnabled;
    }

    @Override
    public final void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    @Override
    public final String getCacheKey() {
        return cacheKey;
    }

    /**
     * Generates a new cache key from all parameters that are relevant for
     * caching.
     */
    protected final void generateCacheKey() {
        if (!cacheEnabled) {
            cacheKey = null;
        }

        StringBuilder sb = new StringBuilder(128);

        int bits = 0;
        if (includeAcls) {
            bits += 1;
        }
        if (includeAllowableActions) {
            bits += 2;
        }
        if (includePolicies) {
            bits += 4;
        }

        sb.append((char) ('0' + bits));
        sb.append(includeRelationships == null ? '-' : (char) ('a' + includeRelationships.ordinal()));
        sb.append(filter == null ? "" : getFilterString());
        if (renditionFilter != null && renditionFilter.size() > 0) {
            sb.append('\\');
            sb.append(getRenditionFilterString());
        }

        cacheKey = sb.toString();
    }

    @Override
    public final int getMaxItemsPerPage() {
        return maxItemsPerPage;
    }

    @Override
    public final void setMaxItemsPerPage(int maxItemsPerPage) {
        if (maxItemsPerPage < 1) {
            throw new IllegalArgumentException("itemsPerPage must be > 0!");
        }

        this.maxItemsPerPage = maxItemsPerPage;
    }
}
