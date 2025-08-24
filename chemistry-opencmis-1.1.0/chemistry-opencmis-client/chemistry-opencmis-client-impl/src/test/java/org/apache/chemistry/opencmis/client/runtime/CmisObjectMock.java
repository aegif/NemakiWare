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
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.Relationship;
import org.apache.chemistry.opencmis.client.api.Rendition;
import org.apache.chemistry.opencmis.client.api.SecondaryType;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ExtensionLevel;

public class CmisObjectMock implements CmisObject, Serializable {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    private final String id;

    public CmisObjectMock(String id) {
        this.id = id;
    }

    @Override
    public Acl addAcl(List<Ace> addAces, AclPropagation aclPropagation) {
        return null;
    }

    @Override
    public Acl applyAcl(List<Ace> addAces, List<Ace> removeAces, AclPropagation aclPropagation) {
        return null;
    }

    @Override
    public Acl setAcl(List<Ace> aces) {
        return null;
    }

    @Override
    public void delete() {
    }

    @Override
    public void delete(boolean allVersions) {
    }

    @Override
    public Acl getAcl() {
        return null;
    }

    @Override
    public Set<String> getPermissionsForPrincipal(String principalId) {
        return null;
    }

    @Override
    public AllowableActions getAllowableActions() {
        return null;
    }

    @Override
    public boolean hasAllowableAction(Action action) {
        return false;
    }

    @Override
    public ObjectType getBaseType() {
        return null;
    }

    @Override
    public BaseTypeId getBaseTypeId() {
        return null;
    }

    @Override
    public String getChangeToken() {
        return null;
    }

    @Override
    public String getCreatedBy() {
        return null;
    }

    @Override
    public GregorianCalendar getCreationDate() {
        return null;
    }

    @Override
    public GregorianCalendar getLastModificationDate() {
        return null;
    }

    @Override
    public String getLastModifiedBy() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public List<Policy> getPolicies() {
        return null;
    }

    @Override
    public List<ObjectId> getPolicyIds() {
        return null;
    }

    @Override
    public List<Property<?>> getProperties() {
        return null;
    }

    @Override
    public <T> Property<T> getProperty(String id) {
        return null;
    }

    @Override
    public <T> T getPropertyValue(String id) {
        return null;
    }

    @Override
    public long getRefreshTimestamp() {
        return 0;
    }

    @Override
    public List<Relationship> getRelationships() {
        return null;
    }

    @Override
    public List<Rendition> getRenditions() {
        return null;
    }

    @Override
    public ObjectType getType() {
        return null;
    }

    @Override
    public List<SecondaryType> getSecondaryTypes() {
        return null;
    }

    @Override
    public List<ObjectType> findObjectType(String id) {
        return null;
    }

    @Override
    public List<CmisExtensionElement> getExtensions(ExtensionLevel level) {
        return null;
    }

    @Override
    public void refresh() {

    }

    @Override
    public void refreshIfOld(long durationInMillis) {

    }

    @Override
    public Acl removeAcl(List<Ace> removeAces, AclPropagation aclPropagation) {
        return null;
    }

    @Override
    public CmisObject updateProperties(Map<String, ?> properties) {
        return null;
    }

    @Override
    public ObjectId updateProperties(Map<String, ?> properties, boolean refresh) {
        return null;
    }

    @Override
    public CmisObject updateProperties(Map<String, ?> properties, List<String> addSecondaryTypeIds,
            List<String> removeSecondaryTypeIds) {
        return null;
    }

    @Override
    public ObjectId updateProperties(Map<String, ?> properties, List<String> addSecondaryTypeIds,
            List<String> removeSecondaryTypeIds, boolean refresh) {
        return null;
    }

    @Override
    public CmisObject rename(String newName) {
        return null;
    }

    @Override
    public ObjectId rename(String newName, boolean refresh) {
        return null;
    }

    @Override
    public void applyPolicy(ObjectId... policyIds) {
    }

    @Override
    public void applyPolicy(ObjectId policyId, boolean refresh) {
    }

    @Override
    public void removePolicy(ObjectId... policyIds) {
    }

    @Override
    public void removePolicy(ObjectId policyId, boolean refresh) {
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public <T> T getAdapter(Class<T> adapterInterface) {
        return null;
    }
}
