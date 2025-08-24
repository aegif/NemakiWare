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
package org.apache.chemistry.opencmis.commons.impl.server;

import java.io.Serializable;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.server.LinkInfo;
import org.apache.chemistry.opencmis.commons.server.ObjectInfo;
import org.apache.chemistry.opencmis.commons.server.RenditionInfo;

/**
 * Implementation of the {@link ObjectInfo} interface.
 */
public class ObjectInfoImpl implements ObjectInfo, Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String atomId;
    private String name;
    private String createdBy;
    private GregorianCalendar creationDate;
    private GregorianCalendar lastModificationDate;
    private String typeId;
    private BaseTypeId baseTypeId;
    private boolean isCurrentVersion = true;
    private String versionSeriesId = null;
    private String workingCopyId = null;
    private String workingCopyOriginalId = null;
    private boolean hasContent = true;
    private String contentType = null;
    private String fileName = null;
    private List<RenditionInfo> renditionInfos = null;
    private boolean supportsRelationships = false;
    private boolean supportsPolicies = false;
    private boolean hasAcl = false;
    private boolean hasParent = true;
    private boolean supportsDescendants = false;
    private boolean supportsFolderTree = false;
    private List<String> relationshipSourceIds = null;
    private List<String> relationshipTargetIds = null;
    private List<LinkInfo> additionalLinks = null;
    private ObjectData object = null;

    public ObjectInfoImpl() {
    }

    public ObjectInfoImpl(String id, BaseTypeId baseObjectTypeId) {
        this.id = id;
        baseTypeId = baseObjectTypeId;
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String getAtomId() {
        return atomId;
    }

    public void setAtomId(String atomId) {
        this.atomId = atomId;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public GregorianCalendar getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(GregorianCalendar creationDate) {
        this.creationDate = creationDate;
    }

    @Override
    public GregorianCalendar getLastModificationDate() {
        return lastModificationDate;
    }

    public void setLastModificationDate(GregorianCalendar lastModificationDate) {
        this.lastModificationDate = lastModificationDate;
    }

    @Override
    public String getTypeId() {
        return typeId;
    }

    public void setTypeId(String typeId) {
        this.typeId = typeId;
    }

    @Override
    public BaseTypeId getBaseType() {
        return baseTypeId;
    }

    public void setBaseType(BaseTypeId baseObjectTypeId) {
        this.baseTypeId = baseObjectTypeId;
    }

    @Override
    public boolean isCurrentVersion() {
        return isCurrentVersion;
    }

    public void setIsCurrentVersion(boolean currentVersion) {
        this.isCurrentVersion = currentVersion;
    }

    @Override
    public String getVersionSeriesId() {
        return versionSeriesId;
    }

    public void setVersionSeriesId(String versionSeriesId) {
        this.versionSeriesId = versionSeriesId;
    }

    @Override
    public String getWorkingCopyId() {
        return workingCopyId;
    }

    public void setWorkingCopyId(String workingCopyId) {
        this.workingCopyId = workingCopyId;
    }

    @Override
    public String getWorkingCopyOriginalId() {
        return workingCopyOriginalId;
    }

    public void setWorkingCopyOriginalId(String workingCopyOriginalId) {
        this.workingCopyOriginalId = workingCopyOriginalId;
    }

    @Override
    public boolean hasContent() {
        return hasContent;
    }

    public void setHasContent(boolean hasContent) {
        this.hasContent = hasContent;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public List<RenditionInfo> getRenditionInfos() {
        return renditionInfos;
    }

    public void setRenditionInfos(List<RenditionInfo> renditions) {
        this.renditionInfos = renditions;
    }

    @Override
    public boolean supportsRelationships() {
        return supportsRelationships;
    }

    public void setSupportsRelationships(boolean supportsRelationships) {
        this.supportsRelationships = supportsRelationships;
    }

    @Override
    public boolean supportsPolicies() {
        return supportsPolicies;
    }

    public void setSupportsPolicies(boolean supportsPolicies) {
        this.supportsPolicies = supportsPolicies;
    }

    @Override
    public boolean hasAcl() {
        return hasAcl;
    }

    public void setHasAcl(boolean hasAcl) {
        this.hasAcl = hasAcl;
    }

    @Override
    public boolean hasParent() {
        return hasParent;
    }

    public void setHasParent(boolean hasParent) {
        this.hasParent = hasParent;
    }

    @Override
    public boolean supportsDescendants() {
        return supportsDescendants;
    }

    public void setSupportsDescendants(boolean supportsDescendants) {
        this.supportsDescendants = supportsDescendants;
    }

    @Override
    public boolean supportsFolderTree() {
        return supportsFolderTree;
    }

    public void setSupportsFolderTree(boolean supportsFolderTree) {
        this.supportsFolderTree = supportsFolderTree;
    }

    @Override
    public List<String> getRelationshipSourceIds() {
        return relationshipSourceIds;
    }

    public void setRelationshipSourceIds(List<String> relationshipSourceIds) {
        this.relationshipSourceIds = relationshipSourceIds;
    }

    @Override
    public List<String> getRelationshipTargetIds() {
        return relationshipTargetIds;
    }

    public void setRelationshipTargetIds(List<String> relationshipTargetIds) {
        this.relationshipTargetIds = relationshipTargetIds;
    }

    @Override
    public List<LinkInfo> getAdditionalLinks() {
        return additionalLinks;
    }

    public void setAdditionalLinks(List<LinkInfo> additionalLinks) {
        this.additionalLinks = additionalLinks;
    }

    @Override
    public ObjectData getObject() {
        return object;
    }

    public void setObject(ObjectData object) {
        this.object = object;
    }
}
