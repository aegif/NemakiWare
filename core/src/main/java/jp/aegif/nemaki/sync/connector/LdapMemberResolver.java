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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.naming.InvalidNameException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

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
     * {@code ambiguousMembers}, {@code ambiguousGroupMembers} and {@code ambiguousGroupIds} from its
     * {@code memberDns}, replacing whatever they held, and marks a group whose id another group entry
     * also has.
     *
     * <p>A value is ambiguous — never written — when its entry is returned by both searches, or
     * its id is the id of more than one entry: whichever were chosen, the membership could go to a
     * different principal than the directory meant. A value naming a group entry whose id another
     * group entry has is kept apart: it is a nested group either way, so it matters only where
     * nested groups are synced (R149 (c)).
     *
     * <p>{@code ambiguousGroupIds} lists the groups a value could name though the sync cannot tell
     * that it does (R151): the group of an entry both searches returned, a group id two group
     * entries share, and a group whose DN a value no search returned as written could still be —
     * {@link LdapName} does not take {@code commonName=} or {@code 2.5.4.3=} for {@code cn=}, nor
     * a value written in hex for the same value written out, though the directory does. Such a
     * value is compared with the groups' DNs AVA by AVA ({@link #couldBeTheSame}), and a value no
     * DN could be parsed from, in a group whose other values are DNs, could be any group
     * ({@code anyGroupCouldBeNamed} — R153 (b)).
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
        // The groups by the shape of their DNs, for the values no search returned as written (R151, R153 (b)):
        // the shapes compared value by value are indexed; the rest are compared one by one.
        Map<String, List<String>> groupsByShape = new HashMap<>();
        List<Map.Entry<List<List<Ava>>, String>> groupShapes = new ArrayList<>();
        List<Map.Entry<List<List<Ava>>, String>> unkeyedShapes = new ArrayList<>();
        for (LdapGroup group : groups) {
            LdapName dn = parse(group.getDn());
            if (dn != null) {
                groupByDn.put(dn, group.getGroupId());
                if (group.getGroupId() != null) {
                    List<List<Ava>> shape = shape(dn);
                    groupShapes.add(Map.entry(shape, group.getGroupId()));
                    String key = key(shape);
                    if (key != null) {
                        groupsByShape.computeIfAbsent(key, k -> new ArrayList<>()).add(group.getGroupId());
                    } else {
                        unkeyedShapes.add(Map.entry(shape, group.getGroupId()));
                    }
                }
            }
            groupIdCount.merge(group.getGroupId(), 1, Integer::sum);
        }

        for (LdapGroup group : groups) {
            List<String> userMembers = new ArrayList<>();
            List<String> groupMembers = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            List<String> ambiguous = new ArrayList<>();
            List<String> ambiguousGroups = new ArrayList<>();
            List<String> ambiguousGroupIds = new ArrayList<>();
            int writtenDifferently = 0;
            boolean unparsedAmongDns = false;
            boolean anyDn = false;
            List<String> values = group.getMemberDns() == null ? List.of() : group.getMemberDns();
            for (String value : values) {
                LdapName dn = parse(value);
                if (dn != null) {
                    anyDn = true;
                } else if (value != null && value.indexOf('=') >= 0) {
                    unparsedAmongDns = true;
                }
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
                    addOnce(ambiguousGroupIds, groupByDn.get(dn));
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
                        addOnce(ambiguousGroupIds, id);
                    } else {
                        addOnce(groupMembers, id);
                    }
                } else {
                    unresolved.add(value);
                    // No search returned this DN as written; the directory may still read it as one of
                    // the groups, written differently. Not named — the group is refused all the same —
                    // but a nesting of that group is not known to be gone.
                    List<String> could = groupsItCouldBe(dn, groupsByShape, groupShapes, unkeyedShapes);
                    if (!could.isEmpty()) {
                        writtenDifferently++;
                        for (String id : could) {
                            addOnce(ambiguousGroupIds, id);
                        }
                    }
                }
            }
            group.setMemberUserIds(userMembers);
            group.setMemberGroupIds(groupMembers);
            group.setUnresolvedMembers(unresolved);
            group.setAmbiguousMembers(ambiguous);
            group.setAmbiguousGroupMembers(ambiguousGroups);
            group.setAmbiguousGroupIds(ambiguousGroupIds);
            group.setIdShared(groupIdCount.get(group.getGroupId()) > 1);
            // A value that looks like a DN but could not be parsed as one, among values that are DNs, is not
            // compared with anything: it could be any group (R153 (b)).
            group.setAnyGroupCouldBeNamed(anyDn && unparsedAmongDns);
            if (!unresolved.isEmpty() || !ambiguous.isEmpty() || !ambiguousGroups.isEmpty()) {
                log.info("Group " + group.getGroupId() + ": " + unresolved.size() + " member value(s) are not a "
                        + "user or group this sync read (" + writtenDifferently + " of them could be a group it "
                        + "read, written differently), " + ambiguous.size() + " name more than one entry, "
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

    /**
     * The attribute types whose values are compared here, by their names and OIDs: the RFC 4519 types a DN
     * is written with, all matched by caseIgnoreMatch (dc by caseIgnoreIA5Match). The value of any other type
     * is not compared — the type may be an alias or OID of one of these, or have a matching rule of its own
     * (generalizedTime, objectIdentifier, integer, a server's own) — so it could be any value (R153 (b)).
     */
    private static final Map<String, String> CASE_IGNORE_TYPES = Map.ofEntries(
            Map.entry("cn", "cn"), Map.entry("commonname", "cn"), Map.entry("2.5.4.3", "cn"),
            Map.entry("uid", "uid"), Map.entry("userid", "uid"), Map.entry("0.9.2342.19200300.100.1.1", "uid"),
            Map.entry("ou", "ou"), Map.entry("organizationalunitname", "ou"), Map.entry("2.5.4.11", "ou"),
            Map.entry("o", "o"), Map.entry("organizationname", "o"), Map.entry("2.5.4.10", "o"),
            Map.entry("dc", "dc"), Map.entry("domaincomponent", "dc"), Map.entry("0.9.2342.19200300.100.1.25", "dc"),
            Map.entry("c", "c"), Map.entry("countryname", "c"), Map.entry("2.5.4.6", "c"),
            Map.entry("l", "l"), Map.entry("localityname", "l"), Map.entry("2.5.4.7", "l"),
            Map.entry("st", "st"), Map.entry("stateorprovincename", "st"), Map.entry("2.5.4.8", "st"),
            Map.entry("sn", "sn"), Map.entry("surname", "sn"), Map.entry("2.5.4.4", "sn"));

    /** One attribute value assertion of an RDN: the type from the table, or null; the folded value, or null (any). */
    private static final class Ava {
        final String type;
        final String value;

        Ava(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    /** The groups whose DNs the directory could take this DN for (see {@link #couldBeTheSame}). */
    private static List<String> groupsItCouldBe(LdapName dn, Map<String, List<String>> groupsByShape,
            List<Map.Entry<List<List<Ava>>, String>> groupShapes, List<Map.Entry<List<List<Ava>>, String>> unkeyedShapes) {
        List<List<Ava>> shape = shape(dn);
        String key = key(shape);
        List<String> could = new ArrayList<>();
        if (key != null) {
            // Two shapes compared value by value meet only on the same string; the others are paired one by one.
            for (String id : groupsByShape.getOrDefault(key, List.of())) {
                addOnce(could, id);
            }
        }
        for (Map.Entry<List<List<Ava>>, String> group : key != null ? unkeyedShapes : groupShapes) {
            if (couldBeTheSame(shape, group.getKey())) {
                addOnce(could, group.getValue());
            }
        }
        return could;
    }

    /**
     * A DN as its RDNs (right to left), each as its AVAs: the type when it is in {@link #CASE_IGNORE_TYPES},
     * by any of its names or OIDs (an {@code OID.} prefix ignored); the value, when the type is one of those
     * and the value is ASCII, with its case folded and everything but letters and digits dropped — what
     * caseIgnoreMatch compares, and more. Any other value is null: it could be any value.
     */
    private static List<List<Ava>> shape(LdapName dn) {
        List<List<Ava>> shape = new ArrayList<>(dn.size());
        for (Rdn rdn : dn.getRdns()) {
            List<Ava> avas = new ArrayList<>();
            try {
                NamingEnumeration<? extends Attribute> attributes = rdn.toAttributes().getAll();
                while (attributes.hasMore()) {
                    Attribute attribute = attributes.next();
                    String type = canonicalType(attribute.getID());
                    NamingEnumeration<?> each = attribute.getAll();
                    while (each.hasMore()) {
                        Object value = each.next();
                        avas.add(new Ava(type, type != null && value instanceof String ? fold((String) value) : null));
                    }
                }
            } catch (NamingException unreadable) {
                avas.clear();
                avas.add(new Ava(null, null));
            }
            shape.add(avas);
        }
        return shape;
    }

    private static String canonicalType(String id) {
        if (id == null) {
            return null;
        }
        String name = id.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("oid.")) {
            name = name.substring(4);
        }
        return CASE_IGNORE_TYPES.get(name);
    }

    /**
     * An ASCII value as caseIgnoreMatch could take it: case folded, and everything but letters and digits
     * dropped (more than the insignificant spaces the rule drops). A value with anything outside ASCII is
     * null — what the directory's string preparation makes of it is not emulated here.
     */
    private static String fold(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7F) {
                return null;
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) (c - 'A' + 'a'));
            }
        }
        return out.toString();
    }

    /**
     * Whether the directory could take the two DNs for one entry: as many RDNs, and at each position an RDN
     * whose AVAs can be paired, each pair of types not two different ones of the table, and each pair of values
     * not two different folded ones. Every pair the directory takes for one entry is paired so; others can be too,
     * which is the side this errs on (R153 (b)).
     */
    private static boolean couldBeTheSame(List<List<Ava>> a, List<List<Ava>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!rdnsCouldBeTheSame(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean rdnsCouldBeTheSame(List<Ava> x, List<Ava> y) {
        if (x.size() != y.size()) {
            return false;
        }
        if (x.size() > 6) {
            return true;
        }
        return pairs(x, y, 0, new boolean[y.size()]);
    }

    /** Whether the AVAs of x from the i-th on can each be paired with a different, unused AVA of y. */
    private static boolean pairs(List<Ava> x, List<Ava> y, int i, boolean[] used) {
        if (i == x.size()) {
            return true;
        }
        for (int j = 0; j < y.size(); j++) {
            if (!used[j] && avasCouldBeTheSame(x.get(i), y.get(j))) {
                used[j] = true;
                if (pairs(x, y, i + 1, used)) {
                    return true;
                }
                used[j] = false;
            }
        }
        return false;
    }

    private static boolean avasCouldBeTheSame(Ava p, Ava q) {
        if (p.type != null && q.type != null && !p.type.equals(q.type)) {
            return false;
        }
        return p.value == null || q.value == null || p.value.equals(q.value);
    }

    /**
     * A shape whose every AVA has a type of the table and a folded value, as one string — two such shapes could
     * be the same entry only when their strings are equal; null for any other shape.
     */
    private static String key(List<List<Ava>> shape) {
        StringBuilder key = new StringBuilder();
        for (List<Ava> rdn : shape) {
            List<String> avas = new ArrayList<>();
            for (Ava ava : rdn) {
                if (ava.type == null || ava.value == null) {
                    return null;
                }
                avas.add(ava.type + "=" + ava.value);
            }
            avas.sort(null);
            key.append(String.join("+", avas)).append(',');
        }
        return key.toString();
    }
}
