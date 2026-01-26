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
    private List<String> memberDns = new ArrayList<>();
    private List<String> memberUserIds = new ArrayList<>();
    private List<String> memberGroupIds = new ArrayList<>();
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
