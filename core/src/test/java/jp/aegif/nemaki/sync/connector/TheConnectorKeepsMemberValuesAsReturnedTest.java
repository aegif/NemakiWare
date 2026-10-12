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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import javax.naming.NamingException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.sync.model.LdapGroup;

/**
 * The connector keeps a group's member values as the directory returned them, and says when it
 * did not get them (R148).
 *
 * <p>It used to turn each value into an id there and then — the DN's first RDN value, and "a group"
 * when the DN contained a search base's string — and an Active Directory group returned in ranges
 * ({@code member;range=0-1499}) read as a group with no members.
 */
class TheConnectorKeepsMemberValuesAsReturnedTest {

    private static Object mapEntry(String method, BasicAttributes attrs, String dn) throws Exception {
        DirectorySyncConfig config = new DirectorySyncConfig();
        config.setGroupIdAttribute("cn");
        config.setGroupNameAttribute("cn");
        config.setGroupMemberAttribute("member");
        config.setUserIdAttribute("uid");
        LdapDirectoryConnector connector = new LdapDirectoryConnector(config);
        SearchResult result = new SearchResult(dn, null, attrs);
        result.setNameInNamespace(dn);
        Method map = LdapDirectoryConnector.class.getDeclaredMethod(method, SearchResult.class);
        map.setAccessible(true);
        try {
            return map.invoke(connector, result);
        } catch (InvocationTargetException thrown) {
            throw (Exception) thrown.getCause();
        }
    }

    private static LdapGroup map(BasicAttributes attrs) throws Exception {
        return (LdapGroup) mapEntry("mapToLdapGroup", attrs, "cn=eng,ou=groups,dc=x");
    }

    private static BasicAttributes eng() {
        BasicAttributes attrs = new BasicAttributes(true);
        attrs.put(new BasicAttribute("cn", "eng"));
        return attrs;
    }

    @Test
    @DisplayName("member values are kept as returned, and no id is read out of them")
    void theValuesAreKeptAndNotInterpreted() throws Exception {
        BasicAttributes attrs = eng();
        BasicAttribute member = new BasicAttribute("member");
        member.add("CN=Alice Smith,OU=Users,DC=x");
        member.add("cn=ops,ou=users-archive,ou=groups,dc=x");
        attrs.put(member);

        LdapGroup group = map(attrs);

        assertEquals(List.of("CN=Alice Smith,OU=Users,DC=x", "cn=ops,ou=users-archive,ou=groups,dc=x"),
                group.getMemberDns());
        assertEquals(List.of(), group.getMemberUserIds(), "an id was read out of a DN: " + group.getMemberUserIds());
        assertEquals(List.of(), group.getMemberGroupIds(), "an id was read out of a DN: " + group.getMemberGroupIds());
        assertTrue(group.isMemberAttributePresent());
        assertFalse(group.isMembersRanged());
    }

    @Test
    @DisplayName("a member attribute returned in ranges is marked as not read — not as no members")
    void aRangedAttributeIsMarked() throws Exception {
        BasicAttributes attrs = eng();
        BasicAttribute ranged = new BasicAttribute("member;range=0-1499");
        ranged.add("CN=Alice Smith,OU=Users,DC=x");
        attrs.put(ranged);

        LdapGroup group = map(attrs);

        assertTrue(group.isMembersRanged(), "a ranged member attribute read as an ordinary one");
        assertTrue(group.isMemberAttributePresent(), "a ranged member attribute read as no member attribute");
    }

    @Test
    @DisplayName("an entry with more than one value for its id attribute stops the search — which value is its id is not known")
    void aMultiValuedIdStopsTheSearch() {
        BasicAttributes group = new BasicAttributes(true);
        BasicAttribute cn = new BasicAttribute("cn");
        cn.add("engineering");
        cn.add("eng");
        group.put(cn);
        NamingException refused = assertThrows(NamingException.class, () -> map(group),
                "a group was read under one of its two ids");
        assertTrue(refused.getMessage().contains("cn=eng,ou=groups,dc=x"), refused.getMessage());

        BasicAttributes user = new BasicAttributes(true);
        BasicAttribute uid = new BasicAttribute("uid");
        uid.add("alice");
        uid.add("asmith");
        user.put(uid);
        assertThrows(NamingException.class, () -> mapEntry("mapToLdapUser", user, "uid=alice,ou=users,dc=x"),
                "a user was read under one of its two ids");
    }

    @Test
    @DisplayName("an entry whose id attribute is empty is skipped like one without it")
    void anEmptyIdIsNoId() throws Exception {
        BasicAttributes user = new BasicAttributes(true);
        user.put(new BasicAttribute("uid", ""));
        assertNull(mapEntry("mapToLdapUser", user, "uid=,ou=users,dc=x"), "a user was read with an empty id");
    }

    @Test
    @DisplayName("a group without the member attribute is marked as such")
    void anAbsentAttributeIsMarked() throws Exception {
        LdapGroup group = map(eng());

        assertFalse(group.isMemberAttributePresent());
        assertFalse(group.isMembersRanged());
        assertEquals(List.of(), group.getMemberDns());
    }
}
