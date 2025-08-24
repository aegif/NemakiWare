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
package org.apache.chemistry.opencmis.client.api;

import java.io.Serializable;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;

/**
 * An <code>OperationContext</code> object defines the filtering, paging and
 * caching of an operation.
 */
public interface OperationContext extends Serializable {

    /**
     * Returns the current filter.
     * 
     * @return a set of <em>query names</em>
     */
    Set<String> getFilter();

    /**
     * Sets the current filter.
     * 
     * @param propertyFilter
     *            a set of <em>query names</em>
     */
    void setFilter(Set<String> propertyFilter);

    /**
     * Sets the current filter.
     * 
     * @param propertyFilter
     *            a comma separated string of <em>query names</em> or "*" for
     *            all properties or {@code null} to let the repository determine
     *            a set of properties
     */
    void setFilterString(String propertyFilter);

    /**
     * Returns the filter extended by cmis:objectId, cmis:objectTypeId and
     * cmis:baseTypeId.
     */
    String getFilterString();

    /**
     * Sets if secondary type properties should be loaded.
     * 
     * @cmis 1.1
     */
    void setLoadSecondaryTypeProperties(boolean load);

    /**
     * Returns is secondary type properties should be loaded.
     * 
     * @cmis 1.1
     */
    boolean loadSecondaryTypeProperties();

    /**
     * Returns if allowable actions should returned.
     */
    boolean isIncludeAllowableActions();

    /**
     * Sets if allowable actions should returned.
     */
    void setIncludeAllowableActions(boolean include);

    /**
     * Returns if ACLs should returned.
     */
    boolean isIncludeAcls();

    /**
     * Sets if ACLs should returned.
     */
    void setIncludeAcls(boolean include);

    /**
     * Returns which relationships should be returned.
     */
    IncludeRelationships getIncludeRelationships();

    /**
     * Sets which relationships should be returned.
     */
    void setIncludeRelationships(IncludeRelationships include);

    /**
     * Returns if policies should returned.
     */
    boolean isIncludePolicies();

    /**
     * Sets if policies should returned.
     */
    void setIncludePolicies(boolean include);

    /**
     * Returns the current rendition filter. (See CMIS spec
     * "2.2.1.2.4.1 Rendition Filter Grammar")
     * 
     * @return a set of rendition filter terms
     */
    Set<String> getRenditionFilter();

    /**
     * Sets the current rendition filter. (See CMIS spec
     * "2.2.1.2.4.1 Rendition Filter Grammar")
     * 
     * @param renditionFilter
     *            a set of rendition filter terms
     */
    void setRenditionFilter(Set<String> renditionFilter);

    /**
     * Sets the current rendition filter. (See CMIS spec
     * "2.2.1.2.4.1 Rendition Filter Grammar")
     * 
     * @param renditionFilter
     *            a comma separated list of rendition filter terms
     */
    void setRenditionFilterString(String renditionFilter);

    /**
     * Returns the current rendition filter. (See CMIS spec
     * "2.2.1.2.4.1 Rendition Filter Grammar")
     * 
     * @return a comma separated list of rendition filter terms
     */
    String getRenditionFilterString();

    /**
     * Returns if path segments should returned.
     */
    boolean isIncludePathSegments();

    /**
     * Sets if path segments should returned.
     */
    void setIncludePathSegments(boolean include);

    /**
     * Returns the order by rule for operations that return lists.
     * 
     * @return a comma-separated list of <em>query names</em> and the ascending
     *         modifier "ASC" or the descending modifier "DESC" for each query
     *         name
     */
    String getOrderBy();

    /**
     * Sets the order by rule for operations that return lists.
     * 
     * @param orderBy
     *            a comma-separated list of <em>query names</em> and the
     *            ascending modifier "ASC" or the descending modifier "DESC" for
     *            each query name
     */
    void setOrderBy(String orderBy);

    /**
     * Return if caching is enabled.
     */
    boolean isCacheEnabled();

    /**
     * Enables or disables the cache.
     */
    void setCacheEnabled(boolean cacheEnabled);

    /**
     * Returns a key for this OperationContext object that is used for caching.
     */
    String getCacheKey();

    /**
     * Set the max number of items per batch for operations that return lists.
     * 
     * This option does not restrict the number of returned items. To retrieve
     * an excerpt (page) of a list, see {@link ItemIterable#getPage(int)}.
     * 
     * @param maxItemsPerPage
     *            max number of items (must be positive)
     */
    void setMaxItemsPerPage(int maxItemsPerPage);

    /**
     * Returns the current max number of items per batch.
     */
    int getMaxItemsPerPage();
}
