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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.ExtensionLevel;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;

/**
 * Base interface for all CMIS objects.
 */
public interface CmisObject extends ObjectId, CmisObjectProperties {

    // object

    /**
     * Returns the allowable actions if they have been fetched for this object.
     * 
     * @return the allowable actions or {@code null} if the allowable actions
     *         have not been requested or no allowable actions are returned by
     *         the repository
     * 
     * @cmis 1.0
     */
    AllowableActions getAllowableActions();

    /**
     * Returns if a given action in the Allowable Actions.
     * 
     * @param action
     *            the action to test, must not be {@code null}
     * @return {@code true} if the action was found in the Allowable Actions,
     *         {@code false} if the action was not found in the Allowable
     *         Actions
     * @throws IllegalStateException
     *             if the Allowable Actions haven't been fetched or provided by
     *             the repository
     * 
     * @cmis 1.0
     */
    boolean hasAllowableAction(Action action);

    /**
     * Returns the relationships if they have been fetched for this object.
     * 
     * @return the relationships to or from this object or {@code null} if the
     *         relationships have not been requested or no relationships are
     *         returned by the repository
     * 
     * @cmis 1.0
     */
    List<Relationship> getRelationships();

    /**
     * Returns the ACL if it has been fetched for this object.
     * 
     * @cmis 1.0
     */
    Acl getAcl();

    /**
     * Returns all permissions for the given principal from the ACL.
     * 
     * @param principalId
     *            the principal ID, must not be {@code null}
     * @return the set of permissions for this user, or an empty set if
     *         principal is not in the ACL
     * @throws IllegalStateException
     *             if the ACL hasn't been fetched or provided by the repository
     * 
     * @cmis 1.0
     */
    Set<String> getPermissionsForPrincipal(String principalId);

    // object service

    /**
     * Deletes this object. If this object is a document, the whole version
     * series is deleted.
     * 
     * @cmis 1.0
     */
    void delete();

    /**
     * Deletes this object.
     * 
     * @param allVersions
     *            if this object is a document this parameter defines whether
     *            only this version ({@code false}) or all versions
     *            ({@code true} ) should be deleted, the parameter is ignored
     *            for all other object types
     * 
     * @cmis 1.0
     */
    void delete(boolean allVersions);

    /**
     * Updates the provided properties and refreshes this object afterwards. If
     * the repository created a new object, for example a new version, this new
     * object is returned. Otherwise the current object is returned.
     * 
     * @param properties
     *            the properties to update
     * 
     * @return the updated object
     * 
     * @cmis 1.0
     */
    CmisObject updateProperties(Map<String, ?> properties);

    /**
     * Updates the provided properties. If the repository created a new object,
     * for example a new version, the object ID of the new object is returned.
     * Otherwise the object ID of the current object is returned.
     * 
     * @param properties
     *            the properties to update
     * @param refresh
     *            {@code true} if this object should be refreshed after the
     *            update, {@code false} if not
     * 
     * @return the object ID of the updated object
     * 
     * @cmis 1.0
     */
    ObjectId updateProperties(Map<String, ?> properties, boolean refresh);

    /**
     * Updates the provided properties and refreshes this object afterwards. If
     * the repository created a new object, for example a new version, this new
     * object is returned. Otherwise the current object is returned.
     * 
     * Secondary types must be supported by the repository and must have been
     * retrieved for this object.
     * 
     * @param properties
     *            the properties to update
     * @param addSecondaryTypeIds
     *            list of secondary type IDs that should be added, may be
     *            {@code null}
     * @param removeSecondaryTypeIds
     *            list of secondary type IDs that should be removed, may be
     *            {@code null}
     * 
     * @return the updated object
     * 
     * @cmis 1.1
     */
    CmisObject updateProperties(Map<String, ?> properties, List<String> addSecondaryTypeIds,
            List<String> removeSecondaryTypeIds);

    /**
     * Updates the provided properties. If the repository created a new object,
     * for example a new version, the object ID of the new object is returned.
     * Otherwise the object ID of the current object is returned.
     * 
     * Secondary types must be supported by the repository and must have been
     * retrieved for this object.
     * 
     * @param properties
     *            the properties to update
     * @param addSecondaryTypeIds
     *            list of secondary type IDs that should be added, may be
     *            {@code null}
     * @param removeSecondaryTypeIds
     *            list of secondary type IDs that should be removed, may be
     *            {@code null}
     * @param refresh
     *            {@code true} if this object should be refreshed after the
     *            update, {@code false} if not
     * 
     * @return the object ID of the updated object
     * 
     * @cmis 1.1
     */
    ObjectId updateProperties(Map<String, ?> properties, List<String> addSecondaryTypeIds,
            List<String> removeSecondaryTypeIds, boolean refresh);

    /**
     * Renames this object (changes the value of {@code cmis:name}). If the
     * repository created a new object, for example a new version, this new
     * object is returned. Otherwise the current object is returned.
     * 
     * @param newName
     *            the new name, not {@code null} or empty
     * 
     * @return the updated object
     * 
     * @cmis 1.0
     */
    CmisObject rename(String newName);

    /**
     * Renames this object (changes the value of {@code cmis:name}). If the
     * repository created a new object, for example a new version, the object id
     * of the new object is returned. Otherwise the object id of the current
     * object is returned.
     * 
     * @param newName
     *            the new name, not {@code null} or empty
     * @param refresh
     *            {@code true} if this object should be refreshed after the
     *            update, {@code false} if not
     * 
     * @return the object ID of the updated object
     * 
     * @cmis 1.0
     */
    ObjectId rename(String newName, boolean refresh);

    // renditions

    /**
     * Returns the renditions if they have been fetched for this object.
     * 
     * @return the renditions of this object or {@code null} if the renditions
     *         have not been requested or no renditions exist for this object
     * 
     * @cmis 1.0
     */
    List<Rendition> getRenditions();

    // policy service

    /**
     * Applies the provided policies and refreshes this object afterwards.
     * 
     * @param policyIds
     *            the IDs of the policies to be applied
     * 
     * @cmis 1.0
     */
    void applyPolicy(ObjectId... policyIds);

    /**
     * Applies the provided policy.
     * 
     * @param policyId
     *            the ID of the policy to be applied
     * @param refresh
     *            {@code true} if this object should be refreshed after the
     *            update, {@code false} if not
     * 
     * @cmis 1.0
     */
    void applyPolicy(ObjectId policyId, boolean refresh);

    /**
     * Removes the provided policies and refreshes this object afterwards.
     * 
     * @param policyIds
     *            the IDs of the policies to be removed
     * 
     * @cmis 1.0
     */
    void removePolicy(ObjectId... policyIds);

    /**
     * Removes the provided policy.
     * 
     * @param policyId
     *            the ID of the policy to be removed
     * @param refresh
     *            {@code true} if this object should be refreshed after the
     *            update, {@code false} if not
     * 
     * @cmis 1.0
     */
    void removePolicy(ObjectId policyId, boolean refresh);

    /**
     * Returns the applied policies if they have been fetched for this object.
     * This method fetches the policy objects from the repository when this
     * method is called for the first time. Policy objects that don't exist are
     * ignored.
     * 
     * @return the list of policies applied to this object or {@code null} if
     *         the policies have not been requested or no policies are applied
     *         to this object
     * 
     * @see #getPolicyIds()
     * 
     * @cmis 1.0
     */
    List<Policy> getPolicies();

    /**
     * Returns the applied policy IDs if they have been fetched for this object.
     * All applied policy IDs are returned, even IDs of policies that don't
     * exist.
     * 
     * @return the list of IDs of applied policies or {@code null} if the
     *         policies have not been requested or no policies are applied to
     *         this object
     * 
     * @see #getPolicies()
     * 
     * @cmis 1.0
     */
    List<ObjectId> getPolicyIds();

    // ACL service

    /**
     * Adds and removes ACEs to the object and refreshes this object afterwards.
     * 
     * @return the new ACL of this object
     * 
     * @cmis 1.0
     */
    Acl applyAcl(List<Ace> addAces, List<Ace> removeAces, AclPropagation aclPropagation);

    /**
     * Adds ACEs to the object and refreshes this object afterwards.
     * 
     * @return the new ACL of this object
     * 
     * @cmis 1.0
     */
    Acl addAcl(List<Ace> addAces, AclPropagation aclPropagation);

    /**
     * Removes ACEs to the object and refreshes this object afterwards.
     * 
     * @return the new ACL of this object
     * 
     * @cmis 1.0
     */
    Acl removeAcl(List<Ace> removeAces, AclPropagation aclPropagation);

    /**
     * Removes the direct ACE of this object, sets the provided ACEs to the
     * object and refreshes this object afterwards.
     * 
     * @return the new ACL of this object
     * 
     * @cmis 1.0
     */
    Acl setAcl(List<Ace> aces);

    // extensions

    /**
     * Returns the extensions for the given level.
     * 
     * @param level
     *            the level
     * 
     * @return the extensions at that level or {@code null} if there no
     *         extensions
     * 
     * @cmis 1.0
     */
    List<CmisExtensionElement> getExtensions(ExtensionLevel level);

    // adapters

    /**
     * Returns an adapter based on the given interface.
     * 
     * @return an adapter object or {@code null} if no adapter object could be
     *         created
     */
    <T> T getAdapter(Class<T> adapterInterface);

    // session handling

    /**
     * Returns the timestamp of the last refresh.
     * 
     * @return the difference, measured in milliseconds, between the last
     *         refresh time and midnight, January 1, 1970 UTC.
     */
    long getRefreshTimestamp();

    /**
     * Reloads this object from the repository.
     * 
     * @throws CmisObjectNotFoundException
     *             if the object doesn't exist anymore in the repository
     */
    void refresh();

    /**
     * Reloads the data from the repository if the last refresh did not occur
     * within {@code durationInMillis}.
     * 
     * @throws CmisObjectNotFoundException
     *             if the object doesn't exist anymore in the repository
     */
    void refreshIfOld(long durationInMillis);
}
