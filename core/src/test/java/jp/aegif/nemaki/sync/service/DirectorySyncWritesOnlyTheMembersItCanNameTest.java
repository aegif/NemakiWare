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
package jp.aegif.nemaki.sync.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.sync.connector.LdapDirectoryConnector;
import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.sync.model.DirectorySyncResult;
import jp.aegif.nemaki.sync.model.LdapGroup;
import jp.aegif.nemaki.sync.model.LdapUser;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * The directory sync writes the members it can name, and does not write a group whose membership
 * it could not establish (R148, 3.4.1).
 *
 * <p>Until 3.4.1 the sync could not update an existing group — the groups it was handed had no
 * revision — so the ids it read out of member DNs were written only when it created a group, and
 * an administrator's corrections stayed. 3.4.1's group list carries the revision, and the first
 * sync would have overwritten those corrections: with the DN's first RDN value where that is not
 * the id (Active Directory), with no members where Active Directory returned them in ranges, and
 * with nested groups read as users where a DN contained the user search base's string.
 *
 * <p>A group is written only when every member value names exactly one entry the sync read, and
 * only with the members that are NemakiWare users; anything else refuses the group, so a member it
 * cannot name keeps whatever membership it has (review round 1 of the fix).
 *
 * <p>Driven through {@code syncGroups} — the entry point the REST trigger, the preview and the
 * scheduler call — with a stand-in directory in place of the LDAP connection.
 */
class DirectorySyncWritesOnlyTheMembersItCanNameTest {

    private static final String REPO = "bedroom";
    private static final String PREFIX = DirectorySyncConfig.DEFAULT_GROUP_PREFIX;

    private ContentService contentService;
    private PropertyManager properties;
    private LdapDirectoryConnector directory;
    private DirectorySyncServiceImpl sync;

    private final List<LdapUser> ldapUsers = new ArrayList<>();
    private final List<LdapGroup> ldapGroups = new ArrayList<>();
    private final List<GroupItem> stored = new ArrayList<>();
    private final List<Content> systemChildren = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        contentService = mock(ContentService.class);
        properties = mock(PropertyManager.class);
        directory = mock(LdapDirectoryConnector.class);
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_ENABLED)).thenReturn(true);
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_NESTED_GROUPS)).thenReturn(true);
        when(properties.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_URL)).thenReturn("ldap://directory:389");
        when(properties.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_BASE_DN)).thenReturn("dc=example,dc=com");
        when(properties.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_BIND_DN)).thenReturn("cn=sync,dc=example,dc=com");
        when(properties.readValue(PropertyKey.DIRECTORY_SYNC_LDAP_BIND_PASSWORD)).thenReturn("secret");
        when(directory.searchUsers()).thenReturn(ldapUsers);
        when(directory.searchGroups()).thenReturn(ldapGroups);
        when(contentService.getGroupItems(REPO)).thenReturn(stored);
        // Every directory user already exists (user() says so), and users are not updated: this
        // measures groups. No other id is a user, so creating a group meets no user with its id.
        // Where a new group goes: .system/groups.
        Folder system = mock(Folder.class);
        when(system.getId()).thenReturn("system-folder");
        Folder groups = mock(Folder.class);
        when(groups.getId()).thenReturn("groups-folder");
        when(groups.getName()).thenReturn("groups");
        when(contentService.getSystemFolder(REPO)).thenReturn(system);
        systemChildren.add(groups);
        when(contentService.getChildren(REPO, "system-folder")).thenReturn(systemChildren);

        sync = new DirectorySyncServiceImpl() {
            @Override
            LdapDirectoryConnector createConnector(DirectorySyncConfig config) {
                return directory;
            }
        };
        sync.setContentService(contentService);
        sync.setPropertyManager(properties);
    }

    private void user(String dn, String id) {
        ldapUsers.add(new LdapUser(dn, id, id));
        when(contentService.getUserItemById(REPO, id)).thenReturn(mock(UserItem.class));
    }

    /** A group as the connector returns it: the member values as the directory gave them. */
    private LdapGroup group(String dn, String id, String... members) {
        LdapGroup group = new LdapGroup(dn, id, id);
        group.setMemberAttributePresent(members.length > 0);
        group.setMemberDns(new ArrayList<>(List.of(members)));
        ldapGroups.add(group);
        return group;
    }

    private GroupItem stored(String id, List<String> users, List<String> groups) {
        GroupItem item = new GroupItem("doc-" + id, "nemaki:group", PREFIX + id, id,
                new ArrayList<>(users), new ArrayList<>(groups));
        item.setRevision("1-abc");
        stored.add(item);
        return item;
    }

    private List<GroupItem> writes() {
        ArgumentCaptor<GroupItem> written = ArgumentCaptor.forClass(GroupItem.class);
        verify(contentService, atLeast(0)).update(any(), eq(REPO), written.capture());
        return written.getAllValues();
    }

    private static String warnings(DirectorySyncResult result) {
        return result.getWarnings().stream().map(w -> w.getGroupId() + ": " + w.getMessage())
                .collect(Collectors.joining("; "));
    }

    private static String errors(DirectorySyncResult result) {
        return result.getErrors().stream().map(e -> e.getGroupId() + ": " + e.getMessage())
                .collect(Collectors.joining("; "));
    }

    @Test
    @DisplayName("Active Directory: members are written as their account names, not as the names in their DNs")
    void anActiveDirectoryMemberIsItsAccountName() {
        user("CN=Alice Smith,OU=Users,DC=example,DC=com", "asmith");
        user("CN=Bob Jones,OU=Users,DC=example,DC=com", "bjones");
        group("CN=Engineering,OU=Groups,DC=example,DC=com", "eng",
                "CN=Alice Smith,OU=Users,DC=example,DC=com", "CN=Bob Jones,OU=Users,DC=example,DC=com");
        // The administrator corrected the membership by hand; LDAP has since added Bob.
        stored("eng", List.of("asmith"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        List<GroupItem> writes = writes();
        assertEquals(1, writes.size(), "the group was not written: " + errors(result));
        assertEquals(List.of("asmith", "bjones"), writes.get(0).getUsers(),
                "the membership was written from the DNs, not from the entries");
        assertEquals("", errors(result));
    }

    @Test
    @DisplayName("a membership that already names the right accounts is not written again")
    void aCorrectMembershipIsNotRewritten() {
        user("CN=Alice Smith,OU=Users,DC=example,DC=com", "asmith");
        group("CN=Engineering,OU=Groups,DC=example,DC=com", "eng", "CN=Alice Smith,OU=Users,DC=example,DC=com");
        stored("eng", List.of("asmith"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), writes(), "a correct membership was overwritten");
        assertEquals(1, result.getGroupsSkipped());
        assertEquals("", errors(result));
    }

    @Test
    @DisplayName("a group whose members came back in ranges is not written, and says so")
    void aRangedGroupIsNotWritten() {
        user("CN=Alice Smith,OU=Users,DC=example,DC=com", "asmith");
        LdapGroup big = group("CN=Everyone,OU=Groups,DC=example,DC=com", "everyone");
        big.setMemberAttributePresent(true);
        big.setMembersRanged(true);
        stored("everyone", List.of("asmith"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), writes(), "a group whose members were not read was written");
        assertTrue(errors(result).contains(PREFIX + "everyone") && errors(result).contains("ranges"),
                "the group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a group none of whose members is a user or group the sync read is not written")
    void aGroupOfNoKnownMembersIsNotWritten() {
        // memberUid values, with the id attribute set to something they are not.
        user("uid=alice,ou=users,dc=example,dc=com", "alice@example.com");
        group("cn=devs,ou=groups,dc=example,dc=com", "devs", "alice", "bob");
        stored("devs", List.of("alice@example.com"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), writes(), "an unreadable membership was written as empty");
        assertTrue(errors(result).contains(PREFIX + "devs"), "the group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a group returned without a member attribute keeps the members it has")
    void anAbsentMemberAttributeKeepsTheMembers() {
        group("CN=Finance,OU=Groups,DC=example,DC=com", "finance");
        stored("finance", List.of("asmith"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), writes(), "members were removed because the attribute was not returned");
        assertTrue(errors(result).contains(PREFIX + "finance"), "the group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a new group returned without a member attribute is not created either — empty and unreadable look the same")
    void aNewGroupWithoutAMemberAttributeIsNotCreated() {
        group("CN=Finance,OU=Groups,DC=example,DC=com", "finance");

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService, never()).createGroupItem(any(), any(), any());
        assertTrue(errors(result).contains(PREFIX + "finance"), "the group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a member the sync cannot name refuses the group — whoever it is keeps the membership they have")
    void aMemberItCannotNameRefusesTheGroup() {
        user("CN=Alice Smith,OU=Users,DC=example,DC=com", "asmith");
        user("uid=bob,ou=users,dc=example,dc=com", "bob");
        group("CN=Engineering,OU=Groups,DC=example,DC=com", "eng",
                "CN=Alice Smith,OU=Users,DC=example,DC=com", "uid=bob,OU=Contractors,DC=example,DC=com");
        // An administrator added the contractor by hand; the directory's entry for him is outside
        // the user search, and the synced bob is someone else.
        stored("eng", List.of("asmith", "bob"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), writes(), "the group was rewritten from the members the sync could name");
        assertTrue(errors(result).contains(PREFIX + "eng") && errors(result).contains("OU=Contractors"),
                "the group and the member were not named: " + errors(result));
    }

    @Test
    @DisplayName("a directory user that is not a NemakiWare user is not written, and the group is")
    void aDirectoryUserWhoIsNotANemakiWareUserIsNotWritten() {
        user("CN=Alice Smith,OU=Users,DC=example,DC=com", "asmith");
        user("CN=Bob Jones,OU=Users,DC=example,DC=com", "bjones");
        // Users are not created by this sync, and bjones was never created by hand.
        when(contentService.getUserItemById(REPO, "bjones")).thenReturn(null);
        group("CN=Engineering,OU=Groups,DC=example,DC=com", "eng",
                "CN=Alice Smith,OU=Users,DC=example,DC=com", "CN=Bob Jones,OU=Users,DC=example,DC=com");
        stored("eng", List.of(), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        List<GroupItem> writes = writes();
        assertEquals(1, writes.size(), "the group was not written: " + errors(result));
        assertEquals(List.of("asmith"), writes.get(0).getUsers(),
                "an id no NemakiWare user has was written — whoever takes it later gets the group");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.getMessage().contains("bjones")),
                "the member that was not written was not reported");
    }

    @Test
    @DisplayName("a user this sync creates is a member it writes — the shipped settings create users, so the first sync after upgrading takes this path")
    void aUserTheSyncCreatesIsAMemberItWrites() {
        createdUsersGoIntoUsers();
        user("CN=Carol Doe,OU=Users,DC=example,DC=com", "cdoe");
        when(contentService.getUserItemById(REPO, "cdoe")).thenReturn(null);
        group("CN=Engineering,OU=Groups,DC=example,DC=com", "eng", "CN=Carol Doe,OU=Users,DC=example,DC=com");

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService).createUserItem(any(), eq(REPO), any());
        List<GroupItem> created = creates();
        assertEquals(1, created.size(), "the group of a user this sync created was not written: " + errors(result));
        assertEquals(List.of("cdoe"), created.get(0).getUsers());
        assertEquals("", errors(result));
    }

    @Test
    @DisplayName("the preview counts a user it would create as a member, as the sync does")
    void aUserThePreviewWouldCreateIsAMemberToo() {
        createdUsersGoIntoUsers();
        user("CN=Carol Doe,OU=Users,DC=example,DC=com", "cdoe");
        when(contentService.getUserItemById(REPO, "cdoe")).thenReturn(null);
        group("CN=Engineering,OU=Groups,DC=example,DC=com", "eng", "CN=Carol Doe,OU=Users,DC=example,DC=com");

        DirectorySyncResult preview = sync.previewSync(REPO);

        verify(contentService, never()).createUserItem(any(), any(), any());
        assertEquals(1, preview.getGroupsCreated(),
                "the preview refused the group of a user it would create: " + errors(preview));
        assertEquals("", errors(preview));
        assertEquals("", warnings(preview), "the preview counted a user it would create as one it would not write");
    }

    /** Missing users are created (as the shipped settings say), into .system/users. */
    private void createdUsersGoIntoUsers() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_CREATE_MISSING_USERS)).thenReturn(true);
        Folder users = mock(Folder.class);
        when(users.getId()).thenReturn("users-folder");
        when(users.getName()).thenReturn("users");
        systemChildren.add(users);
    }

    private List<GroupItem> creates() {
        ArgumentCaptor<GroupItem> created = ArgumentCaptor.forClass(GroupItem.class);
        verify(contentService, atLeast(0)).createGroupItem(any(), eq(REPO), created.capture());
        return created.getAllValues();
    }

    @Test
    @DisplayName("a member whose user sync failed refuses the group — whether it is a NemakiWare user is not known")
    void aMemberWhoseUserSyncFailedRefusesTheGroup() {
        user("CN=Alice Smith,OU=Users,DC=example,DC=com", "asmith");
        user("CN=Bob Jones,OU=Users,DC=example,DC=com", "bjones");
        when(contentService.getUserItemById(REPO, "bjones")).thenThrow(new IllegalStateException("store down"));
        group("CN=Engineering,OU=Groups,DC=example,DC=com", "eng",
                "CN=Alice Smith,OU=Users,DC=example,DC=com", "CN=Bob Jones,OU=Users,DC=example,DC=com");
        stored("eng", List.of("asmith", "bjones"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), writes(), "bjones was dropped because his own sync failed");
        assertTrue(errors(result).contains(PREFIX + "eng"), "the group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a member entry both searches returned refuses the group")
    void anEntryBothSearchesReturnedRefusesTheGroup() {
        user("cn=ops,ou=people,dc=example,dc=com", "ops");
        group("cn=ops,ou=people,dc=example,dc=com", "ops", "cn=ops,ou=people,dc=example,dc=com");
        group("cn=eng,ou=groups,dc=example,dc=com", "eng", "cn=ops,ou=people,dc=example,dc=com");
        stored("eng", List.of("ops"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(g -> (PREFIX + "eng").equals(g.getGroupId())),
                "eng was written from an entry that is both a user and a group");
        assertTrue(errors(result).contains(PREFIX + "eng"), "the group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a member whose id another user entry also has refuses the group")
    void twoEntriesWithOneIdRefuseTheGroup() {
        user("uid=sato,ou=staff,dc=example,dc=com", "sato");
        user("uid=sato,ou=alumni,dc=example,dc=com", "sato");
        group("cn=eng,ou=groups,dc=example,dc=com", "eng", "uid=sato,ou=alumni,dc=example,dc=com");
        stored("eng", List.of(), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), writes(), "the alumnus was written as the sato who logs in");
        assertTrue(errors(result).contains(PREFIX + "eng"), "the group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("two group entries with one id are neither written")
    void aGroupIdTwoEntriesShareIsNotWritten() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group("cn=eng,ou=groups,dc=example,dc=com", "eng", "uid=alice,ou=users,dc=example,dc=com");
        group("cn=eng,ou=archive,dc=example,dc=com", "eng", "uid=alice,ou=users,dc=example,dc=com");

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService, never()).createGroupItem(any(), any(), any());
        assertTrue(errors(result).contains(PREFIX + "eng"), "the groups were not reported: " + errors(result));
    }

    @Test
    @DisplayName("a nested group that NemakiWare does not have and the sync will not create is not named by its parent, and the parent is written")
    void aRefusedGroupNemakiWareDoesNotHaveIsNotNested() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        LdapGroup big = group("cn=big,ou=groups,dc=example,dc=com", "big");
        big.setMemberAttributePresent(true);
        big.setMembersRanged(true);
        group("cn=all,ou=groups,dc=example,dc=com", "all",
                "uid=alice,ou=users,dc=example,dc=com", "cn=big,ou=groups,dc=example,dc=com");

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        ArgumentCaptor<GroupItem> created = ArgumentCaptor.forClass(GroupItem.class);
        verify(contentService).createGroupItem(any(), eq(REPO), created.capture());
        assertEquals(PREFIX + "all", created.getValue().getGroupId(), "big was created");
        assertEquals(List.of(), created.getValue().getGroups(), "a group that does not exist was nested");
        assertEquals(List.of("alice"), created.getValue().getUsers());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.getMessage().contains("big")),
                "the nested group that was not written was not reported");
    }

    @Test
    @DisplayName("a nested group is written as a nested group, though its DN contains the user search base's string")
    void aNestedGroupIsANestedGroup() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group("cn=ops,ou=users-archive,ou=groups,dc=example,dc=com", "ops", "uid=alice,ou=users,dc=example,dc=com");
        group("cn=eng,ou=groups,dc=example,dc=com", "eng", "cn=ops,ou=users-archive,ou=groups,dc=example,dc=com");
        stored("eng", List.of("ops"), List.of());

        sync.syncGroups(REPO, false);

        GroupItem eng = writes().stream().filter(g -> (PREFIX + "eng").equals(g.getGroupId())).findFirst()
                .orElseThrow(() -> new AssertionError("eng was not written"));
        assertEquals(List.of(), eng.getUsers(), "the nested group was written as a user");
        assertEquals(List.of(PREFIX + "ops"), eng.getGroups());
    }

    @Test
    @DisplayName("a local nested group that shares an LDAP group's id is not taken for it")
    void aLocalNestedGroupIsNotTheLdapGroup() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group("cn=qa,ou=groups,dc=example,dc=com", "qa", "uid=alice,ou=users,dc=example,dc=com");
        group("cn=eng,ou=groups,dc=example,dc=com", "eng",
                "uid=alice,ou=users,dc=example,dc=com", "cn=qa,ou=groups,dc=example,dc=com");
        stored("qa", List.of("alice"), List.of());
        // Same users and name as LDAP; the nesting names a LOCAL group called "qa".
        stored("eng", List.of("alice"), List.of("qa"));

        sync.syncGroups(REPO, false);

        GroupItem eng = writes().stream().filter(g -> (PREFIX + "eng").equals(g.getGroupId())).findFirst()
                .orElseThrow(() -> new AssertionError("the local \"qa\" was taken for LDAP's, so eng was not written"));
        assertEquals(List.of(PREFIX + "qa"), eng.getGroups());
    }

    @Test
    @DisplayName("the preview refuses the same groups, and writes nothing")
    void thePreviewReportsTheSameRefusals() {
        LdapGroup big = group("CN=Everyone,OU=Groups,DC=example,DC=com", "everyone");
        big.setMemberAttributePresent(true);
        big.setMembersRanged(true);
        stored("everyone", List.of("asmith"), List.of());

        DirectorySyncResult result = sync.previewSync(REPO);

        verify(contentService, never()).update(any(), any(), any());
        assertTrue(errors(result).contains(PREFIX + "everyone"), "the preview did not report the group");
    }

    // ---------------------------------------------------------------- R149: which groups a parent may nest

    private static final String G_DN = "cn=g,ou=groups,dc=example,dc=com";
    private static final String P_DN = "cn=p,ou=groups,dc=example,dc=com";

    /** G, which this sync refuses: one of its members is outside the user search. */
    private void refusedG() {
        user("uid=bob,ou=users,dc=example,dc=com", "bob");
        group(G_DN, "g", "uid=bob,ou=users,dc=example,dc=com", "uid=x,ou=contractors,dc=example,dc=com");
    }

    private static List<String> ids(List<GroupItem> groups) {
        return groups.stream().map(GroupItem::getGroupId).collect(Collectors.toList());
    }

    private static GroupItem the(List<GroupItem> groups, String id) {
        return groups.stream().filter(g -> (PREFIX + id).equals(g.getGroupId())).findFirst()
                .orElseThrow(() -> new AssertionError(PREFIX + id + " was not written: " + ids(groups)));
    }

    private static String warningsOf(DirectorySyncResult result, String id) {
        return result.getWarnings().stream().filter(w -> (PREFIX + id).equals(w.getGroupId()))
                .map(w -> w.getMessage()).collect(Collectors.joining("; "));
    }

    @Test
    @DisplayName("a group the sync refuses is not newly nested — whoever its old membership names would gain the parent's permissions")
    void aGroupItRefusesIsNotNewlyNested() {
        refusedG();
        // NemakiWare's g still names alice, whom LDAP took out of it.
        stored("g", List.of("alice"), List.of());
        user("uid=carol,ou=users,dc=example,dc=com", "carol");
        group(P_DN, "p", "uid=carol,ou=users,dc=example,dc=com", G_DN);
        stored("p", List.of(), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        GroupItem p = the(writes(), "p");
        assertEquals(List.of("carol"), p.getUsers(), "the parent's own members were not written");
        assertEquals(List.of(), p.getGroups(), "a group whose membership the sync refused was nested: alice gains p");
        assertTrue(warningsOf(result, "p").contains(PREFIX + "g"), "the nesting left out was not reported: "
                + warnings(result));
        assertTrue(errors(result).contains(PREFIX + "g"), "the refused group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("the preview leaves the refused group out of the parent too, and writes nothing")
    void thePreviewDoesNotNestARefusedGroupEither() {
        refusedG();
        stored("g", List.of("alice"), List.of());
        user("uid=carol,ou=users,dc=example,dc=com", "carol");
        group(P_DN, "p", "uid=carol,ou=users,dc=example,dc=com", G_DN);
        stored("p", List.of(), List.of());

        DirectorySyncResult preview = sync.previewSync(REPO);

        verify(contentService, never()).update(any(), any(), any());
        assertEquals(1, preview.getGroupsUpdated(), "the parent was not counted as written: " + errors(preview));
        assertTrue(warningsOf(preview, "p").contains(PREFIX + "g"), "the preview did not report the nesting left out: "
                + warnings(preview));
    }

    @Test
    @DisplayName("a nesting the parent already has is kept, but the parent then vouches for nobody: a group newly nesting it does not")
    void aNestingAlreadyInPlaceIsKeptButVouchesForNothing() {
        refusedG();
        stored("g", List.of("alice"), List.of());
        user("uid=carol,ou=users,dc=example,dc=com", "carol");
        group(P_DN, "p", "uid=carol,ou=users,dc=example,dc=com", G_DN);
        // p already nests g, as LDAP still says: removing it would take p from bob, whom LDAP names.
        stored("p", List.of("carol"), List.of(PREFIX + "g"));
        group("cn=q,ou=groups,dc=example,dc=com", "q", P_DN);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(g -> (PREFIX + "p").equals(g.getGroupId())),
                "p's nesting of g was rewritten: " + ids(writes()));
        assertTrue(warningsOf(result, "p").contains(PREFIX + "g"), "the kept nesting was not reported: " + warnings(result));
        assertEquals(List.of(), the(creates(), "q").getGroups(),
                "q newly nests p, and through it the alice that g still names");
    }

    @Test
    @DisplayName("a group whose write failed is not nested, though LDAP lists its parent first")
    void aGroupWhoseWriteFailedIsNotNested() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        user("uid=bob,ou=users,dc=example,dc=com", "bob");
        group(P_DN, "p", "uid=alice,ou=users,dc=example,dc=com", G_DN);
        group(G_DN, "g", "uid=bob,ou=users,dc=example,dc=com");
        when(contentService.createGroupItem(any(), eq(REPO), argThat((GroupItem g) -> g != null
                && (PREFIX + "g").equals(g.getGroupId())))).thenThrow(new IllegalStateException("store down"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "g", PREFIX + "p"), ids(creates()), "the parent was written before its child");
        assertEquals(List.of(), the(creates(), "p").getGroups(), "a group that was not written was nested");
        assertTrue(errors(result).contains(PREFIX + "g"), "the failed write was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a group is written before the groups that nest it, so they can nest it")
    void aGroupIsWrittenBeforeTheGroupsThatNestIt() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        user("uid=bob,ou=users,dc=example,dc=com", "bob");
        group(P_DN, "p", "uid=alice,ou=users,dc=example,dc=com", G_DN);
        group(G_DN, "g", "uid=bob,ou=users,dc=example,dc=com");

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "g", PREFIX + "p"), ids(creates()), "the parent was written before its child");
        assertEquals(List.of(PREFIX + "g"), the(creates(), "p").getGroups(), "the nesting LDAP names was left out");
        assertEquals("", errors(result));
    }

    @Test
    @DisplayName("a nesting LDAP reversed is written child first: the old nesting goes before the new one is added")
    void aReversedNestingIsWrittenChildFirst() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        user("uid=bob,ou=users,dc=example,dc=com", "bob");
        // NemakiWare: a nests b. LDAP now: b nests a.
        stored("a", List.of("alice"), List.of(PREFIX + "b"));
        stored("b", List.of("bob"), List.of());
        group("cn=b,ou=groups,dc=example,dc=com", "b", "uid=bob,ou=users,dc=example,dc=com",
                "cn=a,ou=groups,dc=example,dc=com");
        group("cn=a,ou=groups,dc=example,dc=com", "a", "uid=alice,ou=users,dc=example,dc=com");

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "a", PREFIX + "b"), ids(writes()),
                "b was written before a let go of it — the cycle check refuses that");
        assertEquals(List.of(), the(writes(), "a").getGroups());
        assertEquals(List.of(PREFIX + "a"), the(writes(), "b").getGroups());
        assertEquals("", errors(result));
    }

    @Test
    @DisplayName("groups LDAP nests in a cycle are none of them written, and no parent newly nests them")
    void aNestingCycleWritesNoneOfItsGroups() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        user("uid=bob,ou=users,dc=example,dc=com", "bob");
        user("uid=carol,ou=users,dc=example,dc=com", "carol");
        group("cn=a,ou=groups,dc=example,dc=com", "a", "uid=alice,ou=users,dc=example,dc=com",
                "cn=b,ou=groups,dc=example,dc=com");
        group("cn=b,ou=groups,dc=example,dc=com", "b", "uid=bob,ou=users,dc=example,dc=com",
                "cn=a,ou=groups,dc=example,dc=com");
        group("cn=c,ou=groups,dc=example,dc=com", "c", "uid=carol,ou=users,dc=example,dc=com",
                "cn=a,ou=groups,dc=example,dc=com");

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "c"), ids(creates()), "a group in the cycle was written");
        assertEquals(List.of(), the(creates(), "c").getGroups(), "a group in the cycle was nested");
        String errors = errors(result);
        assertTrue(errors.contains(PREFIX + "a: group " + PREFIX + "a: LDAP nests it in a cycle")
                && errors.contains(PREFIX + "b: group " + PREFIX + "b: LDAP nests it in a cycle"),
                "the cycle was not reported for both groups: " + errors);
    }

    @Test
    @DisplayName("a group naming itself as a member is written without that member — it adds no one")
    void aGroupNamingItselfIsWrittenWithoutThatMember() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group(G_DN, "g", "uid=alice,ou=users,dc=example,dc=com", G_DN);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        GroupItem g = the(creates(), "g");
        assertEquals(List.of("alice"), g.getUsers());
        assertEquals(List.of(), g.getGroups());
        assertTrue(warningsOf(result, "g").contains("names itself"), "the left-out member was not reported: "
                + warnings(result));
    }

    @Test
    @DisplayName("a nesting that names no group in NemakiWare is removed — whoever later took the id would gain the parent")
    void aNestingThatNamesNoGroupIsRemoved() {
        user("uid=carol,ou=users,dc=example,dc=com", "carol");
        user("uid=bob,ou=users,dc=example,dc=com", "bob");
        group(P_DN, "p", "uid=carol,ou=users,dc=example,dc=com", G_DN);
        group(G_DN, "g", "uid=bob,ou=users,dc=example,dc=com");
        // p names ldap_g, which NemakiWare does not have — and this sync cannot create it.
        stored("p", List.of("carol"), List.of(PREFIX + "g"));
        when(contentService.createGroupItem(any(), eq(REPO), argThat((GroupItem g) -> g != null
                && (PREFIX + "g").equals(g.getGroupId())))).thenThrow(new IllegalStateException("store down"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), the(writes(), "p").getGroups(), "a nesting naming no group was kept");
        assertTrue(warningsOf(result, "p").contains("named no group"), "the removal was not reported: "
                + warnings(result));
    }

    @Test
    @DisplayName("a group whose id a NemakiWare user has is not created, the preview says so too, and no parent nests it")
    void aGroupWhoseIdAUserHasIsNotCreated() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group(G_DN, "g", "uid=alice,ou=users,dc=example,dc=com");
        group(P_DN, "p", "uid=alice,ou=users,dc=example,dc=com", G_DN);
        when(contentService.getUserItemById(REPO, PREFIX + "g")).thenReturn(mock(UserItem.class));

        DirectorySyncResult preview = sync.previewSync(REPO);
        assertTrue(errors(preview).contains(PREFIX + "g: group " + PREFIX + "g: a NemakiWare user has this id"),
                "the preview did not report the clash: " + errors(preview));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "p"), ids(creates()), "the group was created over the user's id");
        assertEquals(List.of(), the(creates(), "p").getGroups(), "a group that could not be created was nested");
        assertTrue(errors(result).contains(PREFIX + "g: group " + PREFIX + "g: a NemakiWare user has this id"),
                "the clash was not reported: " + errors(result));
    }

    @Test
    @DisplayName("where users and groups share a prefix, the preview sees the clash with a user it would create")
    void thePreviewSeesAUserItWouldCreateWithTheGroupsId() {
        when(properties.readValue(PropertyKey.DIRECTORY_SYNC_USER_PREFIX)).thenReturn(PREFIX);
        createdUsersGoIntoUsers();
        // The user g does not exist yet: the preview would create it as ldap_g, the group's id.
        ldapUsers.add(new LdapUser("uid=g,ou=users,dc=example,dc=com", "g", "g"));
        group(G_DN, "g", "uid=g,ou=users,dc=example,dc=com");

        DirectorySyncResult preview = sync.previewSync(REPO);

        assertTrue(errors(preview).contains(PREFIX + "g: group " + PREFIX + "g: a NemakiWare user has this id"),
                "the preview did not see the clash with the user it would create: " + errors(preview));
    }

    @Test
    @DisplayName("not syncing nested groups, a member naming a group whose id two group entries have does not refuse the parent")
    void aSharedGroupIdDoesNotRefuseAParentWhereNestingIsNotSynced() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_NESTED_GROUPS)).thenReturn(false);
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group("cn=eng,ou=groups,dc=example,dc=com", "eng", "uid=alice,ou=users,dc=example,dc=com");
        group("cn=eng,ou=archive,dc=example,dc=com", "eng", "uid=alice,ou=users,dc=example,dc=com");
        // p's only member is the group: its direct members are none. NemakiWare still names alice.
        group(P_DN, "p", "cn=eng,ou=archive,dc=example,dc=com");
        stored("p", List.of("alice"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), the(writes(), "p").getUsers(), "p kept a member LDAP does not name");
        assertTrue(result.getErrors().stream().noneMatch(e -> (PREFIX + "p").equals(e.getGroupId())),
                "p was refused for a member that is a group whichever entry it is: " + errors(result));
    }

    @Test
    @DisplayName("syncing nested groups, the same member refuses the parent — which group it nests is not known")
    void aSharedGroupIdRefusesAParentWhereNestingIsSynced() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group("cn=eng,ou=groups,dc=example,dc=com", "eng", "uid=alice,ou=users,dc=example,dc=com");
        group("cn=eng,ou=archive,dc=example,dc=com", "eng", "uid=alice,ou=users,dc=example,dc=com");
        group(P_DN, "p", "cn=eng,ou=archive,dc=example,dc=com");
        stored("p", List.of("alice"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(g -> (PREFIX + "p").equals(g.getGroupId())), "p was written");
        assertTrue(errors(result).contains(PREFIX + "p: group " + PREFIX + "p: 1 nested group value(s)"),
                "p was not refused for its ambiguous nested group: " + errors(result));
    }

    @Test
    @DisplayName("not syncing nested groups, an entry both searches returned still refuses the group — it could be a user")
    void anEntryBothSearchesReturnedRefusesTheGroupWhereNestingIsNotSynced() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_NESTED_GROUPS)).thenReturn(false);
        user("cn=ops,ou=people,dc=example,dc=com", "ops");
        group("cn=ops,ou=people,dc=example,dc=com", "ops", "cn=ops,ou=people,dc=example,dc=com");
        group(P_DN, "p", "cn=ops,ou=people,dc=example,dc=com");
        stored("p", List.of("ops"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(g -> (PREFIX + "p").equals(g.getGroupId())), "p was written");
        assertTrue(errors(result).contains(PREFIX + "p: group " + PREFIX + "p: 1 member value(s) could name more than one entry"),
                "p was not refused: " + errors(result));
    }

    @Test
    @DisplayName("a group list that answers nothing syncs no group — which groups exist decides what is kept")
    void anUnansweredGroupListSyncsNoGroup() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group(G_DN, "g", "uid=alice,ou=users,dc=example,dc=com");
        when(contentService.getGroupItems(REPO)).thenReturn(null);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService, never()).createGroupItem(any(), any(), any());
        verify(contentService, never()).update(any(), any(), any());
        assertEquals(DirectorySyncResult.SyncStatus.FAILED, result.getStatus(), errors(result));
    }
}
