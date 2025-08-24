/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * ;License;); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * ;AS IS; BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.commons.impl.dataobjects;

import java.io.Serializable;

import org.apache.chemistry.opencmis.commons.data.NewTypeSettableAttributes;

public class NewTypeSettableAttributesImpl extends ExtensionDataImpl implements NewTypeSettableAttributes, Serializable {

    private static final long serialVersionUID = 1L;

    private Boolean id;
    private Boolean localName;
    private Boolean localNamespace;
    private Boolean displayName;
    private Boolean queryName;
    private Boolean description;
    private Boolean creatable;
    private Boolean fileable;
    private Boolean queryable;
    private Boolean fulltextIndexed;
    private Boolean includedInSupertypeQuery;
    private Boolean controllablePolicy;
    private Boolean controllableACL;

    @Override
    public Boolean canSetId() {
        return id;
    }

    public void setCanSetId(Boolean id) {
        this.id = id;
    }

    @Override
    public Boolean canSetLocalName() {
        return localName;
    }

    public void setCanSetLocalName(Boolean localName) {
        this.localName = localName;
    }

    @Override
    public Boolean canSetLocalNamespace() {
        return localNamespace;
    }

    public void setCanSetLocalNamespace(Boolean localNamespace) {
        this.localNamespace = localNamespace;
    }

    @Override
    public Boolean canSetDisplayName() {
        return displayName;
    }

    public void setCanSetDisplayName(Boolean displayName) {
        this.displayName = displayName;
    }

    @Override
    public Boolean canSetQueryName() {
        return queryName;
    }

    public void setCanSetQueryName(Boolean queryName) {
        this.queryName = queryName;
    }

    @Override
    public Boolean canSetDescription() {
        return description;
    }

    public void setCanSetDescription(Boolean description) {
        this.description = description;
    }

    @Override
    public Boolean canSetCreatable() {
        return creatable;
    }

    public void setCanSetCreatable(Boolean creatable) {
        this.creatable = creatable;
    }

    @Override
    public Boolean canSetFileable() {
        return fileable;
    }

    public void setCanSetFileable(Boolean fileable) {
        this.fileable = fileable;
    }

    @Override
    public Boolean canSetQueryable() {
        return queryable;
    }

    public void setCanSetQueryable(Boolean queryable) {
        this.queryable = queryable;
    }

    @Override
    public Boolean canSetFulltextIndexed() {
        return fulltextIndexed;
    }

    public void setCanSetFulltextIndexed(Boolean fulltextIndexed) {
        this.fulltextIndexed = fulltextIndexed;
    }

    @Override
    public Boolean canSetIncludedInSupertypeQuery() {
        return includedInSupertypeQuery;
    }

    public void setCanSetIncludedInSupertypeQuery(Boolean includedInSupertypeQuery) {
        this.includedInSupertypeQuery = includedInSupertypeQuery;
    }

    @Override
    public Boolean canSetControllablePolicy() {
        return controllablePolicy;
    }

    public void setCanSetControllablePolicy(Boolean controllablePolicy) {
        this.controllablePolicy = controllablePolicy;
    }

    @Override
    public Boolean canSetControllableAcl() {
        return controllableACL;
    }

    public void setCanSetControllableAcl(Boolean controllableACL) {
        this.controllableACL = controllableACL;
    }

    @Override
    public String toString() {
        return "NewTypeSettableAttributes [id=" + id + ", localName=" + localName + ", localNamespace="
                + localNamespace + ", displayName=" + displayName + ", queryName=" + queryName + ", description="
                + description + ", creatable=" + creatable + ", fileable=" + fileable + ", queryable=" + queryable
                + ", fulltextIndexed=" + fulltextIndexed + ", includedInSupertypeQuery=" + includedInSupertypeQuery
                + ", controllablePolicy=" + controllablePolicy + ", controllableACL=" + controllableACL + "]"
                + super.toString();
    }
}
