/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.sync.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.sync.model.LdapGroup;
import jp.aegif.nemaki.sync.model.LdapUser;

/**
 * A member is the entry its value names, looked up among the entries the sync read (R148).
 *
 * <p>Each example is one the RDN reading answered wrongly: the id is not the DN's first RDN
 * value, a DN whose RDN value is another user's id is not that user, and a DN that contains the
 * string of the user search base is not thereby a user.
 */
class LdapMemberResolverTest {

    private static LdapUser user(String dn, String id) {
        return new LdapUser(dn, id, id);
    }

    private static LdapGroup group(String dn, String id, String... members) {
        LdapGroup group = new LdapGroup(dn, id, id);
        group.setMemberAttributePresent(true);
        group.setMemberDns(new ArrayList<>(List.of(members)));
        return group;
    }

    @Test
    @DisplayName("a member's id is the id attribute on its own entry, not its DN's first RDN value")
    void aMemberIsTheIdOnItsOwnEntry() {
        // Active Directory: the DN carries the name, the id is sAMAccountName.
        LdapGroup eng = group("CN=Engineering,OU=Groups,DC=example,DC=com", "eng",
                "CN=Alice Smith,OU=Users,DC=example,DC=com");
        LdapMemberResolver.resolve(List.of(user("CN=Alice Smith,OU=Users,DC=example,DC=com", "asmith")),
                List.of(eng));

        assertEquals(List.of("asmith"), eng.getMemberUserIds(), "the RDN value is not the id");
        assertEquals(List.of(), eng.getUnresolvedMembers());
    }

    @Test
    @DisplayName("a member DN matches its entry whatever its case, spacing after commas and escaping")
    void aMemberDnMatchesItsEntryAsADn() {
        LdapGroup eng = group("cn=eng,ou=groups,dc=x", "eng",
                "cn=alice smith, ou=users,DC=X",
                "CN=smith\\2C john,OU=Users,DC=X");
        LdapMemberResolver.resolve(List.of(user("CN=Alice Smith,OU=Users,DC=x", "asmith"),
                user("cn=Smith\\, John,ou=users,dc=x", "jsmith")), List.of(eng));

        assertEquals(List.of("asmith", "jsmith"), eng.getMemberUserIds());
        assertEquals(List.of(), eng.getUnresolvedMembers());
    }

    @Test
    @DisplayName("a DN whose RDN value is another user's id names no one this sync read — it is not that user")
    void aDnOutsideTheSearchIsNotTheUserItsRdnSpells() {
        // bob in ou=contractors is not the bob the user search found. Read by its RDN value, this
        // member gave the synced bob the group's permissions.
        LdapGroup eng = group("cn=eng,ou=groups,dc=x", "eng", "uid=bob,ou=contractors,dc=x");
        LdapMemberResolver.resolve(List.of(user("uid=bob,ou=users,dc=x", "bob")), List.of(eng));

        assertEquals(List.of(), eng.getMemberUserIds(), "a member outside the search was written as bob");
        assertEquals(List.of("uid=bob,ou=contractors,dc=x"), eng.getUnresolvedMembers());
    }

    @Test
    @DisplayName("a nested group is the group its entry is, even when its DN contains the user search base's string")
    void aNestedGroupIsFoundByItsEntry() {
        // "ou=users-archive" contains "ou=users": the string test read this group as a user.
        LdapGroup ops = group("cn=ops,ou=users-archive,ou=groups,dc=x", "ops");
        LdapGroup eng = group("cn=eng,ou=groups,dc=x", "eng", "cn=ops,ou=users-archive,ou=groups,dc=x");
        LdapMemberResolver.resolve(List.of(user("uid=ops,ou=users,dc=x", "ops")), List.of(ops, eng));

        assertEquals(List.of("ops"), eng.getMemberGroupIds(), "the nested group");
        assertEquals(List.of(), eng.getMemberUserIds(), "the nested group was read as the user ops");
    }

    @Test
    @DisplayName("a nested group's id is the group id attribute on its entry, not its RDN value")
    void aNestedGroupIsTheIdOnItsOwnEntry() {
        LdapGroup team = group("CN=Engineering Team,OU=Groups,DC=x", "eng-team");
        LdapGroup all = group("CN=Everyone,OU=Groups,DC=x", "everyone", "CN=Engineering Team,OU=Groups,DC=x");
        LdapMemberResolver.resolve(List.of(), List.of(team, all));

        assertEquals(List.of("eng-team"), all.getMemberGroupIds());
    }

    @Test
    @DisplayName("a value that is not a DN (memberUid) is a member only when it is the id of a user this sync read")
    void aMemberUidIsAnIdOfAUserThisSyncRead() {
        LdapGroup devs = group("cn=devs,ou=groups,dc=x", "devs", "alice", "ghost");
        LdapMemberResolver.resolve(List.of(user("uid=alice,ou=users,dc=x", "alice")), List.of(devs));

        assertEquals(List.of("alice"), devs.getMemberUserIds());
        assertEquals(List.of("ghost"), devs.getUnresolvedMembers());
    }

    @Test
    @DisplayName("an entry both searches returned is ambiguous — neither a user nor a group is written for it")
    void anEntryBothSearchesReturnedIsAmbiguous() {
        LdapGroup ops = group("cn=ops,ou=people,dc=x", "ops");
        LdapGroup eng = group("cn=eng,ou=groups,dc=x", "eng", "cn=ops,ou=people,dc=x");
        LdapMemberResolver.resolve(List.of(user("cn=ops,ou=people,dc=x", "ops")), List.of(ops, eng));

        assertEquals(List.of(), eng.getMemberUserIds(), "the entry was taken for the user");
        assertEquals(List.of(), eng.getMemberGroupIds(), "the entry was taken for the group");
        assertEquals(List.of("cn=ops,ou=people,dc=x"), eng.getAmbiguousMembers());
        assertEquals(List.of("ops"), eng.getAmbiguousGroupIds(), "the group the entry could be was not marked (R151)");
    }

    @Test
    @DisplayName("a member whose id another user entry also has is ambiguous — the two would be one principal")
    void anIdTwoUserEntriesHaveIsAmbiguous() {
        // uid=sato in ou=alumni is not the sato who logs in from ou=staff.
        LdapGroup eng = group("cn=eng,ou=groups,dc=x", "eng", "uid=sato,ou=alumni,dc=x");
        LdapMemberResolver.resolve(List.of(user("uid=sato,ou=staff,dc=x", "sato"),
                user("uid=sato,ou=alumni,dc=x", "sato")), List.of(eng));

        assertEquals(List.of(), eng.getMemberUserIds(), "the alumnus was written as the sato who logs in");
        assertEquals(List.of("uid=sato,ou=alumni,dc=x"), eng.getAmbiguousMembers());
    }

    @Test
    @DisplayName("a memberUid that more than one user entry has as its id is ambiguous")
    void aMemberUidTwoEntriesHaveIsAmbiguous() {
        LdapGroup devs = group("cn=devs,ou=groups,dc=x", "devs", "sato");
        LdapMemberResolver.resolve(List.of(user("uid=sato,ou=staff,dc=x", "sato"),
                user("uid=sato,ou=alumni,dc=x", "sato")), List.of(devs));

        assertEquals(List.of(), devs.getMemberUserIds());
        assertEquals(List.of("sato"), devs.getAmbiguousMembers());
    }

    @Test
    @DisplayName("a group whose id another group entry has is marked, and naming it as a member is ambiguous among groups")
    void aGroupIdTwoEntriesHaveIsMarked() {
        LdapGroup eng1 = group("cn=eng,ou=groups,dc=x", "eng");
        LdapGroup eng2 = group("cn=eng,ou=archive,dc=x", "eng");
        LdapGroup all = group("cn=all,ou=groups,dc=x", "all", "cn=eng,ou=archive,dc=x");
        LdapMemberResolver.resolve(List.of(), List.of(eng1, eng2, all));

        assertTrue(eng1.isIdShared() && eng2.isIdShared(), "two entries with one group id were not marked");
        assertFalse(all.isIdShared());
        assertEquals(List.of(), all.getMemberGroupIds());
        assertEquals(List.of("cn=eng,ou=archive,dc=x"), all.getAmbiguousGroupMembers());
        // Only the group search returned it: it is a nested group whichever entry it is (R149 (c)).
        assertEquals(List.of(), all.getAmbiguousMembers(), "a group-only ambiguity was counted as one that could be a user");
        assertEquals(List.of("eng"), all.getAmbiguousGroupIds(), "the group the value could name was not marked (R151)");
    }

    @Test
    @DisplayName("a DN written with an attribute's long name is not named, but marked as one the group could be (R151)")
    void aDnWrittenWithAnAliasCouldBeTheGroup() {
        assertCouldBeG("commonName=G,ou=groups,dc=x");
    }

    @Test
    @DisplayName("a DN written with an attribute's OID is not named, but marked as one the group could be (R151)")
    void aDnWrittenWithAnOidCouldBeTheGroup() {
        assertCouldBeG("2.5.4.3=g,2.5.4.11=groups,0.9.2342.19200300.100.1.25=x");
    }

    @Test
    @DisplayName("a DN with a value written in hex is not named, but marked as one the group could be (R151)")
    void aDnWithAHexValueCouldBeTheGroup() {
        assertCouldBeG("cn=#0c0167,ou=groups,dc=x");
    }

    @Test
    @DisplayName("a DN whose value is spaced differently — the directory does not count the extra spaces — could be the group (R151)")
    void aDnSpacedDifferentlyCouldBeTheGroup() {
        LdapGroup rnd = group("cn=r and d,ou=groups,dc=x", "rnd");
        LdapGroup p = group("cn=p,ou=teams,dc=x", "p", "commonName=r  and  d,ou=groups,dc=x");
        LdapMemberResolver.resolve(List.of(), List.of(rnd, p));

        assertEquals(List.of("rnd"), p.getAmbiguousGroupIds(), "the group the value could be was not marked");
    }

    @Test
    @DisplayName("a DN whose value is written in a compatibility form (full-width) could be the group (R151)")
    void aDnInACompatibilityFormCouldBeTheGroup() {
        assertCouldBeG("commonName=\uFF47,ou=groups,dc=x");
    }

    @Test
    @DisplayName("a DN of another entry is not marked as one the group could be — its nesting can be checked (R151)")
    void aDnOfAnotherEntryIsNotTheGroup() {
        LdapGroup g = group("cn=g,ou=groups,dc=x", "g");
        LdapGroup p = group("cn=p,ou=groups,dc=x", "p", "uid=g,ou=contractors,dc=x", "cn=g,ou=archive,dc=x");
        LdapMemberResolver.resolve(List.of(), List.of(g, p));

        assertEquals(2, p.getUnresolvedMembers().size());
        assertEquals(List.of(), p.getAmbiguousGroupIds(), "a DN of another entry was taken for one g could be");
    }

    /**
     * The value names no entry as written — the group is refused as before (R148) — and g is the group it
     * could name (p sits in another container: a value in hex could be p's name too).
     */
    private static void assertCouldBeG(String value) {
        LdapGroup g = group("cn=g,ou=groups,dc=x", "g");
        LdapGroup p = group("cn=p,ou=teams,dc=x", "p", value);
        LdapMemberResolver.resolve(List.of(), List.of(g, p));

        assertEquals(List.of(value), p.getUnresolvedMembers(), "the value was named");
        assertEquals(List.of(), p.getMemberGroupIds());
        assertEquals(List.of("g"), p.getAmbiguousGroupIds(), "the group the value could be was not marked");
    }

    @Test
    @DisplayName("the same member written twice is one member")
    void aRepeatedMemberIsOneMember() {
        LdapGroup eng = group("cn=eng,ou=groups,dc=x", "eng",
                "uid=alice,ou=users,dc=x", "UID=Alice,OU=Users,DC=X");
        LdapMemberResolver.resolve(List.of(user("uid=alice,ou=users,dc=x", "alice")), List.of(eng));

        assertEquals(List.of("alice"), eng.getMemberUserIds());
    }
}
