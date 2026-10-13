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
 * principal this sync knows, and a value that could name more than one entry is not known either:
 * the sync does not write a group that has either (it cannot tell whom it would leave out).
 */
public final class LdapMemberResolver {

    private static final Log log = LogFactory.getLog(LdapMemberResolver.class);

    private LdapMemberResolver() {
    }

    /**
     * Fills each group's {@code memberUserIds}, {@code memberGroupIds}, {@code unresolvedMembers},
     * {@code ambiguousMembers} and {@code ambiguousGroupMembers} from its {@code memberDns},
     * replacing whatever they held, and marks a group whose id another group entry also has.
     *
     * <p>A value is ambiguous — never written — when its entry is returned by both searches, or
     * its id is the id of more than one entry: whichever were chosen, the membership could go to a
     * different principal than the directory meant. A value naming a group entry whose id another
     * group entry has is kept apart: it is a nested group either way, so it matters only where
     * nested groups are synced (R149 (c)).
     */
    public static void resolve(List<LdapUser> users, List<LdapGroup> groups) {
        Map<LdapName, String> userByDn = new HashMap<>();
        Map<String, Integer> userIdCount = new HashMap<>();
        for (LdapUser user : users) {
            LdapName dn = parse(user.getDn());
            if (dn != null) {
                userByDn.put(dn, user.getUserId());
            }
            userIdCount.merge(user.getUserId(), 1, Integer::sum);
        }
        Map<LdapName, String> groupByDn = new HashMap<>();
        Map<String, Integer> groupIdCount = new HashMap<>();
        for (LdapGroup group : groups) {
            LdapName dn = parse(group.getDn());
            if (dn != null) {
                groupByDn.put(dn, group.getGroupId());
            }
            groupIdCount.merge(group.getGroupId(), 1, Integer::sum);
        }

        for (LdapGroup group : groups) {
            List<String> userMembers = new ArrayList<>();
            List<String> groupMembers = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            List<String> ambiguous = new ArrayList<>();
            List<String> ambiguousGroups = new ArrayList<>();
            List<String> values = group.getMemberDns() == null ? List.of() : group.getMemberDns();
            for (String value : values) {
                LdapName dn = parse(value);
                if (dn == null) {
                    // Not a DN: the member attribute holds ids (memberUid). It names a member only
                    // if this sync read a user with that id — and only one.
                    if (value == null || !userIdCount.containsKey(value)) {
                        unresolved.add(value);
                    } else if (userIdCount.get(value) > 1) {
                        ambiguous.add(value);
                    } else {
                        addOnce(userMembers, value);
                    }
                } else if (userByDn.containsKey(dn) && groupByDn.containsKey(dn)) {
                    ambiguous.add(value);
                } else if (userByDn.containsKey(dn)) {
                    String id = userByDn.get(dn);
                    if (userIdCount.get(id) > 1) {
                        ambiguous.add(value);
                    } else {
                        addOnce(userMembers, id);
                    }
                } else if (groupByDn.containsKey(dn)) {
                    String id = groupByDn.get(dn);
                    if (groupIdCount.get(id) > 1) {
                        // Only the group search returned this entry, so it is a nested group whichever
                        // entry the id means: ambiguous only where nested groups are synced (R149 (c)).
                        ambiguousGroups.add(value);
                    } else {
                        addOnce(groupMembers, id);
                    }
                } else {
                    unresolved.add(value);
                }
            }
            group.setMemberUserIds(userMembers);
            group.setMemberGroupIds(groupMembers);
            group.setUnresolvedMembers(unresolved);
            group.setAmbiguousMembers(ambiguous);
            group.setAmbiguousGroupMembers(ambiguousGroups);
            group.setIdShared(groupIdCount.get(group.getGroupId()) > 1);
            if (!unresolved.isEmpty() || !ambiguous.isEmpty() || !ambiguousGroups.isEmpty()) {
                log.info("Group " + group.getGroupId() + ": " + unresolved.size() + " member value(s) are not a "
                        + "user or group this sync read, " + ambiguous.size() + " name more than one entry, "
                        + ambiguousGroups.size() + " name a group whose id more than one group entry has");
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
