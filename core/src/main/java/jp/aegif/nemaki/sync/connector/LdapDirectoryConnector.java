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
package jp.aegif.nemaki.sync.connector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.sync.model.LdapGroup;
import jp.aegif.nemaki.sync.model.LdapUser;

public class LdapDirectoryConnector {

    private static final Log log = LogFactory.getLog(LdapDirectoryConnector.class);

    private LdapContext context;
    private StartTlsResponse tlsResponse;
    private DirectorySyncConfig config;

    public LdapDirectoryConnector(DirectorySyncConfig config) {
        this.config = config;
    }

    public void connect() throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.getLdapUrl());

        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(config.getConnectionTimeout()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(config.getReadTimeout()));

        if (config.isUseTls()) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
        }

        log.info("Connecting to LDAP server: " + config.getLdapUrl());
        this.context = new InitialLdapContext(env, null);

        if (config.isUseStartTls()) {
            try {
                tlsResponse = (StartTlsResponse) context.extendedOperation(new StartTlsRequest());
                tlsResponse.negotiate();
                log.info("STARTTLS negotiation successful");
            } catch (IOException e) {
                throw new NamingException("STARTTLS negotiation failed: " + e.getMessage());
            }
        }

        context.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
        context.addToEnvironment(Context.SECURITY_PRINCIPAL, config.getLdapBindDn());
        context.addToEnvironment(Context.SECURITY_CREDENTIALS, config.getLdapBindPassword());

        context.reconnect(null);
        log.info("Successfully connected to LDAP server");
    }

    public void disconnect() {
        if (tlsResponse != null) {
            try {
                tlsResponse.close();
            } catch (IOException e) {
                log.warn("Error closing TLS connection: " + e.getMessage());
            }
        }
        if (context != null) {
            try {
                context.close();
                log.info("Disconnected from LDAP server");
            } catch (NamingException e) {
                log.warn("Error closing LDAP connection: " + e.getMessage());
            }
        }
    }

    public boolean testConnection() {
        try {
            connect();
            disconnect();
            return true;
        } catch (NamingException e) {
            log.error("LDAP connection test failed: " + e.getMessage());
            return false;
        }
    }

    public List<LdapGroup> searchGroups() throws NamingException {
        List<LdapGroup> groups = new ArrayList<>();

        String searchBase = config.getGroupSearchBase();
        if (searchBase == null || searchBase.isEmpty()) {
            searchBase = config.getLdapBaseDn();
        } else if (!searchBase.contains(",")) {
            searchBase = searchBase + "," + config.getLdapBaseDn();
        }

        String filter = config.getGroupSearchFilter();
        if (filter == null || filter.isEmpty()) {
            filter = "(objectClass=groupOfNames)";
        }

        log.info("Searching groups in: " + searchBase + " with filter: " + filter);

        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{
            config.getGroupIdAttribute(),
            config.getGroupNameAttribute(),
            config.getGroupMemberAttribute()
        });

        NamingEnumeration<SearchResult> results = null;
        try {
            results = context.search(searchBase, filter, controls);

            while (results.hasMore()) {
                SearchResult result = results.next();
                LdapGroup group = mapToLdapGroup(result);
                if (group != null) {
                    groups.add(group);
                    log.debug("Found group: " + group.getGroupId());
                }
            }
        } finally {
            closeQuietly(results);
        }

        log.info("Found " + groups.size() + " groups in LDAP");
        return groups;
    }

    private LdapGroup mapToLdapGroup(SearchResult result) throws NamingException {
        Attributes attrs = result.getAttributes();
        
        String groupId = getAttributeValue(attrs, config.getGroupIdAttribute());
        if (groupId == null) {
            log.warn("Group without ID attribute found, skipping: " + result.getNameInNamespace());
            return null;
        }

        String groupName = getAttributeValue(attrs, config.getGroupNameAttribute());
        if (groupName == null) {
            groupName = groupId;
        }

        LdapGroup group = new LdapGroup(result.getNameInNamespace(), groupId, groupName);

        Attribute memberAttr = attrs.get(config.getGroupMemberAttribute());
        if (memberAttr != null) {
            NamingEnumeration<?> members = null;
            try {
                members = memberAttr.getAll();
                while (members.hasMore()) {
                    String memberDn = (String) members.next();
                    group.addMemberDn(memberDn);
                    
                    String memberId = extractIdFromDn(memberDn);
                    if (memberId != null) {
                        if (isGroupDn(memberDn)) {
                            group.addMemberGroupId(memberId);
                        } else {
                            group.addMemberUserId(memberId);
                        }
                    }
                }
            } finally {
                closeQuietly(members);
            }
        }

        return group;
    }

    private String getAttributeValue(Attributes attrs, String attrName) throws NamingException {
        if (attrName == null) {
            return null;
        }
        Attribute attr = attrs.get(attrName);
        if (attr != null && attr.size() > 0) {
            Object value = attr.get();
            return value != null ? value.toString() : null;
        }
        return null;
    }

    private String extractIdFromDn(String dn) {
        if (dn == null || dn.isEmpty()) {
            return null;
        }
        
        String[] parts = dn.split(",");
        if (parts.length > 0) {
            String firstPart = parts[0];
            int eqIndex = firstPart.indexOf('=');
            if (eqIndex > 0 && eqIndex < firstPart.length() - 1) {
                return firstPart.substring(eqIndex + 1).trim();
            }
        }
        return null;
    }

    private boolean isGroupDn(String dn) {
        if (dn == null) {
            return false;
        }
        String lowerDn = dn.toLowerCase();
        
        String userSearchBase = config.getUserSearchBase();
        if (userSearchBase != null && !userSearchBase.isEmpty()) {
            if (lowerDn.contains(userSearchBase.toLowerCase())) {
                return false;
            }
        }
        
        String groupSearchBase = config.getGroupSearchBase();
        if (groupSearchBase != null && !groupSearchBase.isEmpty()) {
            return lowerDn.contains(groupSearchBase.toLowerCase());
        }
        
        if (lowerDn.contains("ou=users") || lowerDn.contains("cn=users")) {
            return false;
        }
        
        return lowerDn.contains("ou=groups") || lowerDn.contains("cn=groups");
    }

    public String resolveUserIdFromDn(String userDn) throws NamingException {
        if (userDn == null || userDn.isEmpty()) {
            return null;
        }

        try {
            Attributes attrs = context.getAttributes(userDn, new String[]{config.getUserIdAttribute()});
            return getAttributeValue(attrs, config.getUserIdAttribute());
        } catch (NamingException e) {
            log.debug("Could not resolve user DN: " + userDn + " - " + e.getMessage());
            return extractIdFromDn(userDn);
        }
    }

    public DirectorySyncConfig getConfig() {
        return config;
    }

    public List<LdapUser> searchUsers() throws NamingException {
        List<LdapUser> users = new ArrayList<>();

        String searchBase = config.getUserSearchBase();
        if (searchBase == null || searchBase.isEmpty()) {
            searchBase = config.getLdapBaseDn();
        } else if (!searchBase.contains(",")) {
            searchBase = searchBase + "," + config.getLdapBaseDn();
        }

        String filter = config.getUserSearchFilter();
        if (filter == null || filter.isEmpty()) {
            filter = "(objectClass=inetOrgPerson)";
        }

        log.info("Searching users in: " + searchBase + " with filter: " + filter);

        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{
            config.getUserIdAttribute(),
            "cn",
            "sn",
            "givenName",
            "mail",
            "displayName"
        });

        NamingEnumeration<SearchResult> results = null;
        try {
            results = context.search(searchBase, filter, controls);

            while (results.hasMore()) {
                SearchResult result = results.next();
                LdapUser user = mapToLdapUser(result);
                if (user != null) {
                    users.add(user);
                    log.debug("Found user: " + user.getUserId());
                }
            }
        } finally {
            closeQuietly(results);
        }

        log.info("Found " + users.size() + " users in LDAP");
        return users;
    }

    private void closeQuietly(NamingEnumeration<?> enumeration) {
        if (enumeration != null) {
            try {
                enumeration.close();
            } catch (NamingException e) {
                log.debug("Error closing NamingEnumeration: " + e.getMessage());
            }
        }
    }

    private LdapUser mapToLdapUser(SearchResult result) throws NamingException {
        Attributes attrs = result.getAttributes();
        
        String userId = getAttributeValue(attrs, config.getUserIdAttribute());
        if (userId == null) {
            log.warn("User without ID attribute found, skipping: " + result.getNameInNamespace());
            return null;
        }

        LdapUser user = new LdapUser(result.getNameInNamespace(), userId, userId);

        String cn = getAttributeValue(attrs, "cn");
        if (cn != null) {
            user.setUserName(cn);
        }

        String displayName = getAttributeValue(attrs, "displayName");
        if (displayName != null) {
            user.setUserName(displayName);
        }

        String mail = getAttributeValue(attrs, "mail");
        if (mail != null) {
            user.setEmail(mail);
        }

        String givenName = getAttributeValue(attrs, "givenName");
        if (givenName != null) {
            user.setFirstName(givenName);
        }

        String sn = getAttributeValue(attrs, "sn");
        if (sn != null) {
            user.setLastName(sn);
        }

        return user;
    }
}
