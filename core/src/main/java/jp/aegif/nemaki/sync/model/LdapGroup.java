/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - Directory Sync feature implementation
 ******************************************************************************/
package jp.aegif.nemaki.sync.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LdapGroup {

    private String dn;
    private String groupId;
    private String groupName;
    /**
     * The member values as the directory returned them — DNs, or ids where the member attribute
     * holds ids ({@code memberUid}). Not interpreted here: {@code LdapMemberResolver} looks each
     * one up among the entries the same sync read, and fills the two lists below (R148).
     */
    private List<String> memberDns = new ArrayList<>();
    private List<String> memberUserIds = new ArrayList<>();
    private List<String> memberGroupIds = new ArrayList<>();
    /** Member values that are neither a user nor a group this sync read. */
    private List<String> unresolvedMembers = new ArrayList<>();
    /**
     * Member values that could name more than one entry, a user among them: an entry both searches
     * returned, or the id of more than one user entry. Whatever the group writes, such a value could
     * be one of its users.
     */
    private List<String> ambiguousMembers = new ArrayList<>();
    /**
     * Member values that name a group entry whose id another group entry also has (R149 (c)). Only the
     * group search returned the entry, so the value is a nested group whichever entry it is: it matters
     * only where nested groups are synced.
     */
    private List<String> ambiguousGroupMembers = new ArrayList<>();
    /**
     * The ids of the group entries an ambiguous member value could name: the group entry of a DN both
     * searches returned, and the id two group entries share (R151). A nesting of one of these is not known
     * to be gone from LDAP, though it is not among the nested groups the sync could name.
     */
    private List<String> ambiguousGroupIds = new ArrayList<>();
    /** Whether another group entry has this group's id, so which entry the group is is not known. */
    private boolean idShared;
    /**
     * Whether the entry carried the member attribute at all. Absent is what an empty group looks
     * like — and what a group whose members the bind account may not read looks like too.
     */
    private boolean memberAttributePresent;
    /**
     * Whether the directory returned the member attribute in ranges ({@code member;range=0-1499},
     * Active Directory's ranged retrieval). The values of such an attribute are not read, so the
     * membership is not known.
     */
    private boolean membersRanged;
    private Map<String, Object> attributes = new HashMap<>();

    public LdapGroup() {}

    public LdapGroup(String dn, String groupId, String groupName) {
        this.dn = dn;
        this.groupId = groupId;
        this.groupName = groupName;
    }

    public String getDn() {
        return dn;
    }

    public void setDn(String dn) {
        this.dn = dn;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public List<String> getMemberDns() {
        return memberDns;
    }

    public void setMemberDns(List<String> memberDns) {
        this.memberDns = memberDns;
    }

    public List<String> getMemberUserIds() {
        return memberUserIds;
    }

    public void setMemberUserIds(List<String> memberUserIds) {
        this.memberUserIds = memberUserIds;
    }

    public List<String> getMemberGroupIds() {
        return memberGroupIds;
    }

    public void setMemberGroupIds(List<String> memberGroupIds) {
        this.memberGroupIds = memberGroupIds;
    }

    public List<String> getUnresolvedMembers() {
        return unresolvedMembers;
    }

    public void setUnresolvedMembers(List<String> unresolvedMembers) {
        this.unresolvedMembers = unresolvedMembers;
    }

    public List<String> getAmbiguousMembers() {
        return ambiguousMembers;
    }

    public void setAmbiguousMembers(List<String> ambiguousMembers) {
        this.ambiguousMembers = ambiguousMembers;
    }

    public List<String> getAmbiguousGroupMembers() {
        return ambiguousGroupMembers;
    }

    public void setAmbiguousGroupMembers(List<String> ambiguousGroupMembers) {
        this.ambiguousGroupMembers = ambiguousGroupMembers;
    }

    public List<String> getAmbiguousGroupIds() {
        return ambiguousGroupIds;
    }

    public void setAmbiguousGroupIds(List<String> ambiguousGroupIds) {
        this.ambiguousGroupIds = ambiguousGroupIds;
    }

    public boolean isIdShared() {
        return idShared;
    }

    public void setIdShared(boolean idShared) {
        this.idShared = idShared;
    }

    public boolean isMemberAttributePresent() {
        return memberAttributePresent;
    }

    public void setMemberAttributePresent(boolean memberAttributePresent) {
        this.memberAttributePresent = memberAttributePresent;
    }

    public boolean isMembersRanged() {
        return membersRanged;
    }

    public void setMembersRanged(boolean membersRanged) {
        this.membersRanged = membersRanged;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public void addMemberDn(String memberDn) {
        if (this.memberDns == null) {
            this.memberDns = new ArrayList<>();
        }
        this.memberDns.add(memberDn);
    }

    public void addMemberUserId(String userId) {
        if (this.memberUserIds == null) {
            this.memberUserIds = new ArrayList<>();
        }
        this.memberUserIds.add(userId);
    }

    public void addMemberGroupId(String groupId) {
        if (this.memberGroupIds == null) {
            this.memberGroupIds = new ArrayList<>();
        }
        this.memberGroupIds.add(groupId);
    }

    @Override
    public String toString() {
        return "LdapGroup{" +
                "dn='" + dn + '\'' +
                ", groupId='" + groupId + '\'' +
                ", groupName='" + groupName + '\'' +
                ", memberUserIds=" + memberUserIds +
                ", memberGroupIds=" + memberGroupIds +
                '}';
    }
}
