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
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;

public class QueryTypeImpl extends AbstractExtensionData {

    private static final long serialVersionUID = 1L;

    private String statement;
    private Boolean searchAllVersions;
    private Boolean includeAllowableActions;
    private IncludeRelationships includeRelationships;
    private String renditionFilter;
    private BigInteger maxItems;
    private BigInteger skipCount;

    public QueryTypeImpl() {
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public Boolean getSearchAllVersions() {
        return searchAllVersions;
    }

    public void setSearchAllVersions(Boolean searchAllVersions) {
        this.searchAllVersions = searchAllVersions;
    }

    public Boolean getIncludeAllowableActions() {
        return includeAllowableActions;
    }

    public void setIncludeAllowableActions(Boolean includeAllowableActions) {
        this.includeAllowableActions = includeAllowableActions;
    }

    public IncludeRelationships getIncludeRelationships() {
        return includeRelationships;
    }

    public void setIncludeRelationships(IncludeRelationships includeRelationships) {
        this.includeRelationships = includeRelationships;
    }

    public String getRenditionFilter() {
        return renditionFilter;
    }

    public void setRenditionFilter(String renditionFilter) {
        this.renditionFilter = renditionFilter;
    }

    public BigInteger getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(BigInteger maxItems) {
        this.maxItems = maxItems;
    }

    public BigInteger getSkipCount() {
        return skipCount;
    }

    public void setSkipCount(BigInteger skipCount) {
        this.skipCount = skipCount;
    }

    @Override
    public String toString() {
        return "QueryType [statement=" + statement + ", searchAllVersions=" + searchAllVersions
                + ", includeAllowableActions=" + includeAllowableActions + ", includeRelationships="
                + includeRelationships + ", renditionFilter=" + renditionFilter + ", maxItems=" + maxItems
                + ", skipCount=" + skipCount + "]" + super.toString();
    }
}
