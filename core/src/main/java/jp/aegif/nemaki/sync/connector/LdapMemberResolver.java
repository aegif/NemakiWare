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
 ******************************************************************************/
package jp.aegif.nemaki.sync.connector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.sync.model.LdapGroup;
import jp.aegif.nemaki.sync.model.LdapUser;

/**
 * Turns each group's member values into the ids this sync writes, by looking every value up
 * among the entries the same sync read (R148, 3.4.1).
 *
 * <p>The directory sync used to read the id out of the member value itself: the value of the
 * DN's first RDN, and "a group" when the DN contained the group search base's string. Neither is
 * what the entry is. Where the RDN attribute is not the id attribute — Active Directory's
 * {@code CN=<name>,…} with {@code sAMAccountName} as the id, or {@code cn} as the RDN with
 * {@code uid} as the id — the membership named no user, or a different user whose id happened to
 * equal the RDN value; and a DN such as {@code cn=ops,ou=users-archive,ou=groups,…} contains the
 * user search base {@code ou=users}. Until 3.4.1 the sync could not update an existing group, so
 * those memberships stayed as an administrator had corrected them; the first 3.4.1 sync would have
 * overwritten them.
 *
 * <p>Here a DN is matched against the DNs the user and group searches returned (compared as
 * {@link LdapName}s, so case, spacing after commas and escaping do not matter), and the id is the
 * configured id attribute's value on that entry. A value that is not a DN — {@code memberUid}
 * holds ids — is a member only if it is the id of a user this sync read. Anything else is not a
 * principal this sync knows: it is reported, never written under a guessed id.
 */
public final class LdapMemberResolver {

    private static final Log log = LogFactory.getLog(LdapMemberResolver.class);

    private LdapMemberResolver() {
    }

    /**
     * Fills each group's {@code memberUserIds}, {@code memberGroupIds} and
     * {@code unresolvedMembers} from its {@code memberDns}, replacing whatever they held.
     */
    public static void resolve(List<LdapUser> users, List<LdapGroup> groups) {
        Map<LdapName, String> userByDn = new HashMap<>();
        Set<String> userIds = new HashSet<>();
        for (LdapUser user : users) {
            LdapName dn = parse(user.getDn());
            if (dn != null && !dn.isEmpty()) {
                userByDn.put(dn, user.getUserId());
            }
            userIds.add(user.getUserId());
        }
        Map<LdapName, String> groupByDn = new HashMap<>();
        for (LdapGroup group : groups) {
            LdapName dn = parse(group.getDn());
            if (dn != null && !dn.isEmpty()) {
                groupByDn.put(dn, group.getGroupId());
            }
        }

        for (LdapGroup group : groups) {
            List<String> userMembers = new ArrayList<>();
            List<String> groupMembers = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            List<String> values = group.getMemberDns() == null ? List.of() : group.getMemberDns();
            for (String value : values) {
                LdapName dn = parse(value);
                if (dn == null) {
                    // Not a DN: the member attribute holds ids (memberUid). It names a member only
                    // if this sync read a user with that id.
                    if (value != null && userIds.contains(value)) {
                        addOnce(userMembers, value);
                    } else {
                        unresolved.add(value);
                    }
                } else if (userByDn.containsKey(dn)) {
                    addOnce(userMembers, userByDn.get(dn));
                } else if (groupByDn.containsKey(dn)) {
                    addOnce(groupMembers, groupByDn.get(dn));
                } else {
                    unresolved.add(value);
                }
            }
            group.setMemberUserIds(userMembers);
            group.setMemberGroupIds(groupMembers);
            group.setUnresolvedMembers(unresolved);
            if (!unresolved.isEmpty()) {
                log.info("Group " + group.getGroupId() + ": " + unresolved.size() + " member value(s) are "
                        + "not a user or group this sync read and are not written");
            }
        }
    }

    /** The value as a DN, or null when it is not one; the empty DN is not one either. */
    private static LdapName parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            LdapName name = new LdapName(value);
            return name.isEmpty() ? null : name;
        } catch (InvalidNameException notADn) {
            return null;
        }
    }

    private static void addOnce(List<String> ids, String id) {
        if (id != null && !ids.contains(id)) {
            ids.add(id);
        }
    }
}
