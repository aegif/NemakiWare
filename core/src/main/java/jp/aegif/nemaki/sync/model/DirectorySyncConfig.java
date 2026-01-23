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

import java.util.GregorianCalendar;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DirectorySyncConfig {

    private String repositoryId;
    private boolean enabled;

    private String ldapUrl;
    private String ldapBaseDn;
    private String ldapBindDn;
    private String ldapBindPassword;
    private boolean useTls;
    private boolean useStartTls;
    private int connectionTimeout = 5000;
    private int readTimeout = 30000;

    private String groupSearchBase;
    private String groupSearchFilter;
    private String userSearchBase;
    private String userSearchFilter;

    private String groupIdAttribute;
    private String groupNameAttribute;
    private String groupMemberAttribute;
    private String userIdAttribute;

    private boolean syncNestedGroups;
    private boolean createMissingUsers;
    private boolean updateExistingUsers;
    private boolean deleteOrphanGroups;
    private boolean deleteOrphanUsers;
    private String groupPrefix;
    private String userPrefix;

    private String cronExpression;
    private boolean scheduleEnabled;

    private GregorianCalendar lastSyncTime;
    private String lastSyncStatus;

    public DirectorySyncConfig() {
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLdapUrl() {
        return ldapUrl;
    }

    public void setLdapUrl(String ldapUrl) {
        this.ldapUrl = ldapUrl;
    }

    public String getLdapBaseDn() {
        return ldapBaseDn;
    }

    public void setLdapBaseDn(String ldapBaseDn) {
        this.ldapBaseDn = ldapBaseDn;
    }

    public String getLdapBindDn() {
        return ldapBindDn;
    }

    public void setLdapBindDn(String ldapBindDn) {
        this.ldapBindDn = ldapBindDn;
    }

    public String getLdapBindPassword() {
        return ldapBindPassword;
    }

    public void setLdapBindPassword(String ldapBindPassword) {
        this.ldapBindPassword = ldapBindPassword;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public boolean isUseStartTls() {
        return useStartTls;
    }

    public void setUseStartTls(boolean useStartTls) {
        this.useStartTls = useStartTls;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getGroupSearchBase() {
        return groupSearchBase;
    }

    public void setGroupSearchBase(String groupSearchBase) {
        this.groupSearchBase = groupSearchBase;
    }

    public String getGroupSearchFilter() {
        return groupSearchFilter;
    }

    public void setGroupSearchFilter(String groupSearchFilter) {
        this.groupSearchFilter = groupSearchFilter;
    }

    public String getUserSearchBase() {
        return userSearchBase;
    }

    public void setUserSearchBase(String userSearchBase) {
        this.userSearchBase = userSearchBase;
    }

    public String getUserSearchFilter() {
        return userSearchFilter;
    }

    public void setUserSearchFilter(String userSearchFilter) {
        this.userSearchFilter = userSearchFilter;
    }

    public String getGroupIdAttribute() {
        return groupIdAttribute;
    }

    public void setGroupIdAttribute(String groupIdAttribute) {
        this.groupIdAttribute = groupIdAttribute;
    }

    public String getGroupNameAttribute() {
        return groupNameAttribute;
    }

    public void setGroupNameAttribute(String groupNameAttribute) {
        this.groupNameAttribute = groupNameAttribute;
    }

    public String getGroupMemberAttribute() {
        return groupMemberAttribute;
    }

    public void setGroupMemberAttribute(String groupMemberAttribute) {
        this.groupMemberAttribute = groupMemberAttribute;
    }

    public String getUserIdAttribute() {
        return userIdAttribute;
    }

    public void setUserIdAttribute(String userIdAttribute) {
        this.userIdAttribute = userIdAttribute;
    }

    public boolean isSyncNestedGroups() {
        return syncNestedGroups;
    }

    public void setSyncNestedGroups(boolean syncNestedGroups) {
        this.syncNestedGroups = syncNestedGroups;
    }

    public boolean isCreateMissingUsers() {
        return createMissingUsers;
    }

    public void setCreateMissingUsers(boolean createMissingUsers) {
        this.createMissingUsers = createMissingUsers;
    }

    public boolean isUpdateExistingUsers() {
        return updateExistingUsers;
    }

    public void setUpdateExistingUsers(boolean updateExistingUsers) {
        this.updateExistingUsers = updateExistingUsers;
    }

    public boolean isDeleteOrphanGroups() {
        return deleteOrphanGroups;
    }

    public void setDeleteOrphanGroups(boolean deleteOrphanGroups) {
        this.deleteOrphanGroups = deleteOrphanGroups;
    }

    public boolean isDeleteOrphanUsers() {
        return deleteOrphanUsers;
    }

    public void setDeleteOrphanUsers(boolean deleteOrphanUsers) {
        this.deleteOrphanUsers = deleteOrphanUsers;
    }

    public String getGroupPrefix() {
        return groupPrefix;
    }

    public void setGroupPrefix(String groupPrefix) {
        this.groupPrefix = groupPrefix;
    }

    public String getUserPrefix() {
        return userPrefix;
    }

    public void setUserPrefix(String userPrefix) {
        this.userPrefix = userPrefix;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    public void setScheduleEnabled(boolean scheduleEnabled) {
        this.scheduleEnabled = scheduleEnabled;
    }

    public GregorianCalendar getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(GregorianCalendar lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public String getLastSyncStatus() {
        return lastSyncStatus;
    }

    public void setLastSyncStatus(String lastSyncStatus) {
        this.lastSyncStatus = lastSyncStatus;
    }
}
