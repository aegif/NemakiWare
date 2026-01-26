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

/**
 * Configuration for LDAP/Active Directory synchronization.
 *
 * <h2>Authentication Architecture</h2>
 * <p>
 * This feature synchronizes user and group information from LDAP/AD directories.
 * <strong>Authentication (login) is NOT handled by this feature.</strong>
 * </p>
 *
 * <h3>Recommended Authentication Flow</h3>
 * <p>
 * LDAP-synced users should authenticate via SAML/OIDC through Keycloak or other IdPs:
 * </p>
 * <pre>
 * LDAP/AD → Keycloak (LDAP User Federation) → OIDC/SAML → NemakiWare
 * </pre>
 *
 * <h3>Initial Password (Fallback Only)</h3>
 * <p>
 * The {@code initialPassword} field is provided as a fallback for environments
 * where SAML/OIDC is not available. When set, newly created users receive this
 * password. However, the recommended approach is to leave this unset and use
 * Keycloak for authentication.
 * </p>
 *
 * @see <a href="docs/ldap-sync-keycloak-authentication.md">LDAP Sync + Keycloak Authentication Guide</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DirectorySyncConfig {

    public static final String DEFAULT_GROUP_PREFIX = "ldap_";
    public static final String DEFAULT_USER_PREFIX = "";
    public static final int DEFAULT_CONNECTION_TIMEOUT = 5000;
    public static final int DEFAULT_READ_TIMEOUT = 30000;
    public static final String DEFAULT_GROUP_SEARCH_FILTER = "(objectClass=groupOfNames)";
    public static final String DEFAULT_USER_SEARCH_FILTER = "(objectClass=inetOrgPerson)";
    public static final String DEFAULT_GROUP_ID_ATTRIBUTE = "cn";
    public static final String DEFAULT_GROUP_NAME_ATTRIBUTE = "cn";
    public static final String DEFAULT_GROUP_MEMBER_ATTRIBUTE = "member";
    public static final String DEFAULT_USER_ID_ATTRIBUTE = "uid";

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

    /**
     * Initial password for newly created LDAP-synced users.
     * <p>
     * <strong>NOT RECOMMENDED:</strong> This is a fallback option for environments
     * without SAML/OIDC. The preferred approach is to authenticate LDAP users via
     * Keycloak OIDC/SAML, where they use their LDAP credentials directly.
     * </p>
     * <p>
     * If left null/empty, users created via LDAP sync will have a random password
     * and can ONLY authenticate via SAML/OIDC.
     * </p>
     */
    private String initialPassword;

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

    public String getInitialPassword() {
        return initialPassword;
    }

    public void setInitialPassword(String initialPassword) {
        this.initialPassword = initialPassword;
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
