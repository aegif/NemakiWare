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

import org.apache.chemistry.opencmis.commons.data.CreatablePropertyTypes;
import org.apache.chemistry.opencmis.commons.data.NewTypeSettableAttributes;
import org.apache.chemistry.opencmis.commons.data.RepositoryCapabilities;
import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.CapabilityJoin;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.CapabilityQuery;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;

/**
 * RepositoryCapabilities Implementation.
 */
public class RepositoryCapabilitiesImpl extends AbstractExtensionData implements RepositoryCapabilities {

    private static final long serialVersionUID = 2L;

    private Boolean allVersionsSearchable;
    private CapabilityAcl capabilityAcl;
    private CapabilityChanges capabilityChanges;
    private CapabilityContentStreamUpdates capabilityContentStreamUpdates;
    private CapabilityJoin capabilityJoin;
    private CapabilityQuery capabilityQuery;
    private CapabilityRenditions capabilityRendition;
    private Boolean isPwcSearchable;
    private Boolean isPwcUpdatable;
    private Boolean supportsGetDescendants;
    private Boolean supportsGetFolderTree;
    private CapabilityOrderBy capabilityOrderBy;
    private Boolean supportsMultifiling;
    private Boolean supportsUnfiling;
    private Boolean supportsVersionSpecificFiling;
    private CreatablePropertyTypes creatablePropertyTypes;
    private NewTypeSettableAttributes newTypeSettableAttributes;

    /**
     * Constructor.
     */
    public RepositoryCapabilitiesImpl() {
    }

    public RepositoryCapabilitiesImpl(RepositoryCapabilities data) {
        allVersionsSearchable = data.isAllVersionsSearchableSupported();
        capabilityAcl = data.getAclCapability();
        capabilityChanges = data.getChangesCapability();
        capabilityContentStreamUpdates = data.getContentStreamUpdatesCapability();
        capabilityJoin = data.getJoinCapability();
        capabilityQuery = data.getQueryCapability();
        capabilityRendition = data.getRenditionsCapability();
        isPwcSearchable = data.isPwcSearchableSupported();
        isPwcUpdatable = data.isPwcUpdatableSupported();
        supportsGetDescendants = data.isGetDescendantsSupported();
        supportsGetFolderTree = data.isGetFolderTreeSupported();
        capabilityOrderBy = data.getOrderByCapability();
        supportsMultifiling = data.isMultifilingSupported();
        supportsUnfiling = data.isUnfilingSupported();
        supportsVersionSpecificFiling = data.isVersionSpecificFilingSupported();
        creatablePropertyTypes = data.getCreatablePropertyTypes();
        newTypeSettableAttributes = data.getNewTypeSettableAttributes();
        setExtensions(data.getExtensions());
    }

    @Override
    public Boolean isAllVersionsSearchableSupported() {
        return allVersionsSearchable;
    }

    public void setAllVersionsSearchable(Boolean allVersionsSearchable) {
        this.allVersionsSearchable = allVersionsSearchable;
    }

    @Override
    public CapabilityAcl getAclCapability() {
        return capabilityAcl;
    }

    public void setCapabilityAcl(CapabilityAcl capabilityAcl) {
        this.capabilityAcl = capabilityAcl;
    }

    @Override
    public CapabilityChanges getChangesCapability() {
        return capabilityChanges;
    }

    public void setCapabilityChanges(CapabilityChanges capabilityChanges) {
        this.capabilityChanges = capabilityChanges;
    }

    @Override
    public CapabilityContentStreamUpdates getContentStreamUpdatesCapability() {
        return capabilityContentStreamUpdates;
    }

    public void setCapabilityContentStreamUpdates(CapabilityContentStreamUpdates capabilityContentStreamUpdates) {
        this.capabilityContentStreamUpdates = capabilityContentStreamUpdates;
    }

    @Override
    public CapabilityJoin getJoinCapability() {
        return capabilityJoin;
    }

    public void setCapabilityJoin(CapabilityJoin capabilityJoin) {
        this.capabilityJoin = capabilityJoin;
    }

    @Override
    public CapabilityQuery getQueryCapability() {
        return capabilityQuery;
    }

    public void setCapabilityQuery(CapabilityQuery capabilityQuery) {
        this.capabilityQuery = capabilityQuery;
    }

    @Override
    public CapabilityRenditions getRenditionsCapability() {
        return capabilityRendition;
    }

    public void setCapabilityRendition(CapabilityRenditions capabilityRendition) {
        this.capabilityRendition = capabilityRendition;
    }

    @Override
    public Boolean isPwcSearchableSupported() {
        return isPwcSearchable;
    }

    public void setIsPwcSearchable(Boolean isPwcSearchable) {
        this.isPwcSearchable = isPwcSearchable;
    }

    @Override
    public Boolean isPwcUpdatableSupported() {
        return isPwcUpdatable;
    }

    public void setIsPwcUpdatable(Boolean isPwcUpdatable) {
        this.isPwcUpdatable = isPwcUpdatable;
    }

    @Override
    public Boolean isGetDescendantsSupported() {
        return supportsGetDescendants;
    }

    public void setSupportsGetDescendants(Boolean supportsGetDescendants) {
        this.supportsGetDescendants = supportsGetDescendants;
    }

    @Override
    public Boolean isGetFolderTreeSupported() {
        return supportsGetFolderTree;
    }

    public void setSupportsGetFolderTree(Boolean supportsGetFolderTree) {
        this.supportsGetFolderTree = supportsGetFolderTree;
    }

    @Override
    public CapabilityOrderBy getOrderByCapability() {
        return capabilityOrderBy;
    }

    public void setCapabilityOrderBy(CapabilityOrderBy capabilityOrderBy) {
        this.capabilityOrderBy = capabilityOrderBy;
    }

    @Override
    public Boolean isMultifilingSupported() {
        return supportsMultifiling;
    }

    public void setSupportsMultifiling(Boolean supportsMultifiling) {
        this.supportsMultifiling = supportsMultifiling;
    }

    @Override
    public Boolean isUnfilingSupported() {
        return supportsUnfiling;
    }

    public void setSupportsUnfiling(Boolean supportsUnfiling) {
        this.supportsUnfiling = supportsUnfiling;
    }

    @Override
    public Boolean isVersionSpecificFilingSupported() {
        return supportsVersionSpecificFiling;
    }

    public void setSupportsVersionSpecificFiling(Boolean supportsVersionSpecificFiling) {
        this.supportsVersionSpecificFiling = supportsVersionSpecificFiling;
    }

    @Override
    public CreatablePropertyTypes getCreatablePropertyTypes() {
        return creatablePropertyTypes;
    }

    public void setCreatablePropertyTypes(CreatablePropertyTypes creatablePropertyTypes) {
        this.creatablePropertyTypes = creatablePropertyTypes;
    }

    @Override
    public NewTypeSettableAttributes getNewTypeSettableAttributes() {
        return newTypeSettableAttributes;
    }

    public void setNewTypeSettableAttributes(NewTypeSettableAttributes newTypeSettableAttributes) {
        this.newTypeSettableAttributes = newTypeSettableAttributes;
    }

    @Override
    public String toString() {
        return "Repository Capabilities [all versions searchable=" + allVersionsSearchable + ", capability ACL="
                + capabilityAcl + ", capability changes=" + capabilityChanges + ", capability content stream updates="
                + capabilityContentStreamUpdates + ", capability join=" + capabilityJoin + ", capability query="
                + capabilityQuery + ", capability rendition=" + capabilityRendition + ", is PWC searchable="
                + isPwcSearchable + ", is PWC updatable=" + isPwcUpdatable + ", supports GetDescendants="
                + supportsGetDescendants + ", supports GetFolderTree=" + supportsGetFolderTree
                + ", supports multifiling=" + supportsMultifiling + ", supports unfiling=" + supportsUnfiling
                + ", supports version specific filing=" + supportsVersionSpecificFiling + ", creatable property types="
                + creatablePropertyTypes + ", newTypeSettableAttributes=" + newTypeSettableAttributes + "]"
                + super.toString();
    }

}
