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
package org.apache.chemistry.opencmis.inmemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ChangeEventInfo;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PolicyIdList;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AllowableActionsImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChangeEventInfoDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyIdListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Content;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Filing;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Folder;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Item;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Policy;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Relationship;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoredObject;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.Version;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.VersionedDocument;
import org.apache.chemistry.opencmis.inmemory.types.PropertyCreationHelper;
import org.apache.chemistry.opencmis.server.support.TypeManager;

/**
 * A collection of utility functions to fill the data objects used
 *         as return values for the service object calls
 */
public final class DataObjectCreator {

    // Utility class
    private DataObjectCreator() {
    }

    public static AllowableActions fillAllowableActions(CallContext callContext, StoredObject so, String user) {

        boolean isFolder = so instanceof Folder;
        boolean isDocument = so instanceof Content;
        boolean isItem = so instanceof Item;
        boolean isRelationship = so instanceof Relationship;
        boolean isFileable = isFolder || isDocument || isItem;
        boolean isPolicy = so instanceof Policy;
        boolean isCheckedOut = false;
        boolean canCheckOut = false;
        boolean canCheckIn = false;
        boolean isVersioned = so instanceof Version || so instanceof VersionedDocument;
        boolean hasContent = so instanceof Content && ((Content) so).hasContent();
        boolean isRootFolder = isFolder && ((Folder) so).getParentId() == null;
        boolean hasRendition = so.hasRendition(user);
        boolean canGetAcl = user != null && (isDocument || isFolder || isItem);
        boolean canSetAcl = canGetAcl;
        boolean cmis11 = callContext.getCmisVersion() != CmisVersion.CMIS_1_0;

        if (so instanceof Version) {
            isCheckedOut = ((Version) so).isPwc();
            canCheckIn = isCheckedOut && ((Version) so).getParentDocument().getCheckedOutBy().equals(user);
            canCheckOut = !((Version) so).getParentDocument().isCheckedOut();
        } else if (so instanceof VersionedDocument) {
            isCheckedOut = ((VersionedDocument) so).isCheckedOut();
            canCheckOut = !((VersionedDocument) so).isCheckedOut();
            canCheckIn = isCheckedOut && ((VersionedDocument) so).getCheckedOutBy().equals(user);
        }

        AllowableActionsImpl allowableActions = new AllowableActionsImpl();
        Set<Action> set = allowableActions.getAllowableActions();

        if (!isRootFolder) {
            set.add(Action.CAN_DELETE_OBJECT);
            set.add(Action.CAN_UPDATE_PROPERTIES);
            set.add(Action.CAN_APPLY_POLICY);
            set.add(Action.CAN_GET_APPLIED_POLICIES);
        }

        if (isFolder || isDocument || isItem || isRelationship || isPolicy) {
            set.add(Action.CAN_GET_PROPERTIES);
            if (!isRootFolder && isFileable) {
                set.add(Action.CAN_GET_OBJECT_PARENTS);
                set.add(Action.CAN_MOVE_OBJECT);
            }
        }

        if (isFolder) {
            if (!isRootFolder) {
                set.add(Action.CAN_GET_FOLDER_PARENT);
                set.add(Action.CAN_DELETE_TREE);
            }
            set.add(Action.CAN_GET_FOLDER_TREE);
            set.add(Action.CAN_GET_DESCENDANTS);

            set.add(Action.CAN_CREATE_DOCUMENT);
            set.add(Action.CAN_CREATE_FOLDER);
            if (cmis11) {
                set.add(Action.CAN_CREATE_ITEM);
            }
            set.add(Action.CAN_GET_CHILDREN);
        }

        if (hasContent) {
            set.add(Action.CAN_DELETE_CONTENT_STREAM);
            set.add(Action.CAN_GET_CONTENT_STREAM);
        }

        if (isVersioned) {
            if (canCheckOut) {
                set.add(Action.CAN_CHECK_OUT);
            }
            if (canCheckIn) {
                set.add(Action.CAN_CANCEL_CHECK_OUT);
                set.add(Action.CAN_CHECK_IN);
            }
            set.add(Action.CAN_GET_ALL_VERSIONS);
        }

        if (isDocument || isItem) {
            if (so instanceof Filing && ((Filing) so).hasParent()) {
                set.add(Action.CAN_ADD_OBJECT_TO_FOLDER);
                set.add(Action.CAN_REMOVE_OBJECT_FROM_FOLDER);
            }
            if (isDocument) {
                if (isVersioned) {
                    if (canCheckIn) {
                        set.add(Action.CAN_SET_CONTENT_STREAM);
                    }
                } else {
                    set.add(Action.CAN_SET_CONTENT_STREAM);
                }
            }
        }

        if (hasRendition) {
            set.add(Action.CAN_GET_RENDITIONS);
        }

        if (canSetAcl) {
            set.add(Action.CAN_APPLY_ACL);
        }
        if (canGetAcl) {
            set.add(Action.CAN_GET_ACL);
        }

        allowableActions.setAllowableActions(set);
        return allowableActions;
    }

    public static Acl fillACL(StoredObject so) {
        AccessControlListImpl acl = new AccessControlListImpl();
        List<Ace> aces = new ArrayList<Ace>();
        acl.setAces(aces);
        return acl;
    }

    public static PolicyIdList fillPolicyIds(StoredObject so) {
        PolicyIdListImpl polIds = new PolicyIdListImpl();
        List<String> pols = so.getAppliedPolicies();
        polIds.setPolicyIds(pols);
        return polIds;
    }

    public static List<ObjectData> fillRelationships(CallContext context, TypeManager tm, ObjectStore objStore,
            IncludeRelationships includeRelationships, StoredObject so, String user) {
        return getRelationships(context, tm, objStore, includeRelationships, so, user);
    }

    public static ChangeEventInfo fillChangeEventInfo(StoredObject so) {
        // TODO: to be completed if change information is implemented
        ChangeEventInfo changeEventInfo = new ChangeEventInfoDataImpl();
        return changeEventInfo;
    }

    public static List<ObjectData> getRelationships(CallContext context, TypeManager tm, ObjectStore objStore,
            IncludeRelationships includeRelationships, StoredObject spo, String user) {
        if (includeRelationships != IncludeRelationships.NONE) {
            RelationshipDirection relationshipDirection = RelationshipDirection.SOURCE;
            // source is default
            if (includeRelationships == IncludeRelationships.TARGET) {
                relationshipDirection = RelationshipDirection.TARGET;
            } else if (includeRelationships == IncludeRelationships.BOTH) {
                relationshipDirection = RelationshipDirection.EITHER;
            }

            List<StoredObject> relationships = objStore.getRelationships(spo.getId(), null, relationshipDirection);
            List<ObjectData> res = new ArrayList<ObjectData>(relationships.size());
            for (StoredObject so : relationships) {
                ObjectData od = PropertyCreationHelper.getObjectData(context, tm, objStore, so, null, user, false,
                        IncludeRelationships.NONE, null, false, false, null);
                res.add(od);
            }
            return res;
        }
        return null;
    }
}
