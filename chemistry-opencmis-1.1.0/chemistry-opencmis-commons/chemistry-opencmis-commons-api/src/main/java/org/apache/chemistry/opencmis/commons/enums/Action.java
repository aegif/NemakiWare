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
package org.apache.chemistry.opencmis.commons.enums;

public enum Action {

    // important: do not change the order of these values!
    /** @cmis 1.0 */
    CAN_DELETE_OBJECT("canDeleteObject"), //
    /** @cmis 1.0 */
    CAN_UPDATE_PROPERTIES("canUpdateProperties"), //
    /** @cmis 1.0 */
    CAN_GET_FOLDER_TREE("canGetFolderTree"), //
    /** @cmis 1.0 */
    CAN_GET_PROPERTIES("canGetProperties"), //
    /** @cmis 1.0 */
    CAN_GET_OBJECT_RELATIONSHIPS("canGetObjectRelationships"), //
    /** @cmis 1.0 */
    CAN_GET_OBJECT_PARENTS("canGetObjectParents"), //
    /** @cmis 1.0 */
    CAN_GET_FOLDER_PARENT("canGetFolderParent"), //
    /** @cmis 1.0 */
    CAN_GET_DESCENDANTS("canGetDescendants"), //
    /** @cmis 1.0 */
    CAN_MOVE_OBJECT("canMoveObject"), //
    /** @cmis 1.0 */
    CAN_DELETE_CONTENT_STREAM("canDeleteContentStream"), //
    /** @cmis 1.0 */
    CAN_CHECK_OUT("canCheckOut"), //
    /** @cmis 1.0 */
    CAN_CANCEL_CHECK_OUT("canCancelCheckOut"), //
    /** @cmis 1.0 */
    CAN_CHECK_IN("canCheckIn"), //
    /** @cmis 1.0 */
    CAN_SET_CONTENT_STREAM("canSetContentStream"), //
    /** @cmis 1.0 */
    CAN_GET_ALL_VERSIONS("canGetAllVersions"), //
    /** @cmis 1.0 */
    CAN_ADD_OBJECT_TO_FOLDER("canAddObjectToFolder"), //
    /** @cmis 1.0 */
    CAN_REMOVE_OBJECT_FROM_FOLDER("canRemoveObjectFromFolder"), //
    /** @cmis 1.0 */
    CAN_GET_CONTENT_STREAM("canGetContentStream"), //
    /** @cmis 1.0 */
    CAN_APPLY_POLICY("canApplyPolicy"), //
    /** @cmis 1.0 */
    CAN_GET_APPLIED_POLICIES("canGetAppliedPolicies"), //
    /** @cmis 1.0 */
    CAN_REMOVE_POLICY("canRemovePolicy"), //
    /** @cmis 1.0 */
    CAN_GET_CHILDREN("canGetChildren"), //
    /** @cmis 1.0 */
    CAN_CREATE_DOCUMENT("canCreateDocument"), //
    /** @cmis 1.0 */
    CAN_CREATE_FOLDER("canCreateFolder"), //
    /** @cmis 1.0 */
    CAN_CREATE_RELATIONSHIP("canCreateRelationship"), //
    /** @cmis 1.1 */
    CAN_CREATE_ITEM("canCreateItem"), //
    /** @cmis 1.0 */
    CAN_DELETE_TREE("canDeleteTree"), //
    /** @cmis 1.0 */
    CAN_GET_RENDITIONS("canGetRenditions"), //
    /** @cmis 1.0 */
    CAN_GET_ACL("canGetACL"), //
    /** @cmis 1.0 */
    CAN_APPLY_ACL("canApplyACL");

    private final String value;

    Action(String v) {
        value = v;
    }

    public String value() {
        return value;
    }

    public static Action fromValue(String v) {
        for (Action c : Action.values()) {
            if (c.value.equals(v)) {
                return c;
            }
        }
        throw new IllegalArgumentException(v);
    }
}
