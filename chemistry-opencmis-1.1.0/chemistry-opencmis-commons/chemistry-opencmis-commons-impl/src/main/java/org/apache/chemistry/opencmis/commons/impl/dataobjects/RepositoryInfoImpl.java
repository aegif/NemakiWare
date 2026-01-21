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

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.AclCapabilities;
import org.apache.chemistry.opencmis.commons.data.ExtensionFeature;
import org.apache.chemistry.opencmis.commons.data.RepositoryCapabilities;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;

/**
 * Repository info data implementation.
 */
public class RepositoryInfoImpl extends AbstractExtensionData implements RepositoryInfo {

    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String description;
    private String versionSupported;
    private RepositoryCapabilities capabilities;
    private String rootFolderId;
    private AclCapabilities aclCapabilities;
    private String principalAnonymous;
    private String principalAnyone;
    private String thinClientUri;
    private Boolean changesIncomplete;
    private List<BaseTypeId> changesOnType;
    private String latestChangeLogToken;
    private String vendorName;
    private String productName;
    private String productVersion;
    private List<ExtensionFeature> extensionFeatures;

    /**
     * Constructor.
     */
    public RepositoryInfoImpl() {
    }

    public RepositoryInfoImpl(RepositoryInfo data) {
        id = data.getId();
        name = data.getName();
        description = data.getDescription();
        versionSupported = data.getCmisVersionSupported();
        capabilities = data.getCapabilities();
        rootFolderId = data.getRootFolderId();
        aclCapabilities = data.getAclCapabilities();
        principalAnonymous = data.getPrincipalIdAnonymous();
        principalAnyone = data.getPrincipalIdAnyone();
        thinClientUri = data.getThinClientUri();
        changesIncomplete = data.getChangesIncomplete();
        changesOnType = data.getChangesOnType();
        latestChangeLogToken = data.getLatestChangeLogToken();
        vendorName = data.getVendorName();
        productName = data.getProductName();
        productVersion = data.getProductVersion();
        extensionFeatures = data.getExtensionFeatures();
        setExtensions(data.getExtensions());
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String getCmisVersionSupported() {
        if (versionSupported == null) {
            return "1.0";
        }

        return versionSupported;
    }

    public void setCmisVersionSupported(String versionSupported) {
        this.versionSupported = versionSupported;
    }

    @Override
    public CmisVersion getCmisVersion() {
        if (versionSupported == null) {
            return CmisVersion.CMIS_1_0;
        }

        try {
            return CmisVersion.fromValue(versionSupported);
        } catch (IllegalArgumentException e) {
            return CmisVersion.CMIS_1_0;
        }
    }

    public void setCmisVersion(CmisVersion cmisVersion) {
        if (cmisVersion == null) {
            versionSupported = CmisVersion.CMIS_1_0.value();
        } else {
            versionSupported = cmisVersion.value();
        }
    }

    @Override
    public RepositoryCapabilities getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(RepositoryCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public String getRootFolderId() {
        return rootFolderId;
    }

    public void setRootFolder(String rootFolderId) {
        this.rootFolderId = rootFolderId;
    }

    @Override
    public AclCapabilities getAclCapabilities() {
        return aclCapabilities;
    }

    public void setAclCapabilities(AclCapabilities aclCapabilities) {
        this.aclCapabilities = aclCapabilities;
    }

    @Override
    public String getPrincipalIdAnonymous() {
        return principalAnonymous;
    }

    public void setPrincipalAnonymous(String principalAnonymous) {
        this.principalAnonymous = principalAnonymous;
    }

    @Override
    public String getPrincipalIdAnyone() {
        return principalAnyone;
    }

    public void setPrincipalAnyone(String principalAnyone) {
        this.principalAnyone = principalAnyone;
    }

    @Override
    public String getThinClientUri() {
        return thinClientUri;
    }

    public void setThinClientUri(String thinClientUri) {
        this.thinClientUri = thinClientUri;
    }

    @Override
    public Boolean getChangesIncomplete() {
        return changesIncomplete;
    }

    public void setChangesIncomplete(Boolean changesIncomplete) {
        this.changesIncomplete = changesIncomplete;
    }

    @Override
    public List<BaseTypeId> getChangesOnType() {
        if (changesOnType == null) {
            changesOnType = new ArrayList<BaseTypeId>(0);
        }

        return changesOnType;
    }

    public void setChangesOnType(List<BaseTypeId> changesOnType) {
        this.changesOnType = changesOnType;
    }

    @Override
    public String getLatestChangeLogToken() {
        return latestChangeLogToken;
    }

    public void setLatestChangeLogToken(String latestChangeLogToken) {
        this.latestChangeLogToken = latestChangeLogToken;
    }

    @Override
    public String getVendorName() {
        return vendorName;
    }

    public void setVendorName(String vendorName) {
        this.vendorName = vendorName;
    }

    @Override
    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    @Override
    public String getProductVersion() {
        return productVersion;
    }

    public void setProductVersion(String productVersion) {
        this.productVersion = productVersion;
    }

    @Override
    public List<ExtensionFeature> getExtensionFeatures() {
        return extensionFeatures;
    }

    public void setExtensionFeature(List<ExtensionFeature> extensionFeatures) {
        this.extensionFeatures = extensionFeatures;
    }

    @Override
    public String toString() {
        return "Repository Info [id=" + id + ", name=" + name + ", description=" + description + ", capabilities="
                + capabilities + ", ACL capabilities=" + aclCapabilities + ", changes incomplete=" + changesIncomplete
                + ", changes on type=" + changesOnType + ", latest change log token=" + latestChangeLogToken
                + ", principal anonymous=" + principalAnonymous + ", principal anyone=" + principalAnyone
                + ", vendor name=" + vendorName + ", product name=" + productName + ", product version="
                + productVersion + ", root folder id=" + rootFolderId + ", thin client URI=" + thinClientUri
                + ", version supported=" + versionSupported + ", extension features=" + extensionFeatures + "]"
                + super.toString();
    }

}
