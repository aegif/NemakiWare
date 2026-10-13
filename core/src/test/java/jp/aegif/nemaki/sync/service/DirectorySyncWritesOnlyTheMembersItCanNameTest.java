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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** What the store holds after each write — copies, so that a later change to an argument is not stored. */
    private final Map<String, GroupItem> persisted = new HashMap<>();

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

        // A write stores a copy, and a read past the cache answers what is stored (R151, R152).
        doAnswer(write -> {
            Object content = write.getArgument(2);
            if (content instanceof GroupItem) {
                persisted.put(((GroupItem) content).getGroupId(), copy((GroupItem) content));
            }
            return content;
        }).when(contentService).update(any(), eq(REPO), any());
        doAnswer(create -> {
            GroupItem group = create.getArgument(2);
            persisted.put(group.getGroupId(), copy(group));
            return group;
        }).when(contentService).createGroupItem(any(), eq(REPO), any(GroupItem.class));
        when(contentService.getGroupItemByIdFresh(eq(REPO), any())).thenAnswer(read -> {
            GroupItem held = persisted.get((String) read.getArgument(1));
            return held == null ? null : copy(held);
        });

        sync = new DirectorySyncServiceImpl() {
            @Override
            LdapDirectoryConnector createConnector(DirectorySyncConfig config) {
                return directory;
            }
        };
        sync.setContentService(contentService);
        sync.setPropertyManager(properties);
    }

    private static GroupItem copy(GroupItem group) {
        GroupItem copy = new GroupItem(group.getId(), group.getObjectType(), group.getGroupId(), group.getName(),
                group.getUsers() == null ? new ArrayList<>() : new ArrayList<>(group.getUsers()),
                group.getGroups() == null ? new ArrayList<>() : new ArrayList<>(group.getGroups()));
        copy.setRevision(group.getRevision());
        return copy;
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
        return held(PREFIX + id, id, users, groups);
    }

    /** A group of NemakiWare's own, without the prefix: the sync does not write it. */
    private GroupItem local(String id, List<String> users, List<String> groups) {
        return held(id, id, users, groups);
    }

    private GroupItem held(String groupId, String name, List<String> users, List<String> groups) {
        GroupItem item = new GroupItem("doc-" + groupId, "nemaki:group", groupId, name,
                new ArrayList<>(users), new ArrayList<>(groups));
        item.setRevision("1-abc");
        stored.add(item);
        // The store keeps its own copy: the sync changes the object it is handed before writing it.
        persisted.put(groupId, copy(item));
        return item;
    }

    /** Writes of the group fail — after storing it (a later step failed), or before. */
    private void failWrites(String id, boolean afterStoring) {
        doAnswer(write -> {
            GroupItem group = write.getArgument(2);
            if (afterStoring) {
                persisted.put(group.getGroupId(), copy(group));
            }
            throw new IllegalStateException(afterStoring ? "change log down" : "store down");
        }).when(contentService).update(any(), eq(REPO),
                argThat(content -> content instanceof GroupItem && (PREFIX + id).equals(((GroupItem) content).getGroupId())));
    }

    /** The first writes of the group fail before storing it; the later ones store it. */
    private void failFirstWrites(String id, int failing) {
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(write -> {
            GroupItem group = write.getArgument(2);
            if (writes.getAndIncrement() < failing) {
                throw new IllegalStateException("store down");
            }
            persisted.put(group.getGroupId(), copy(group));
            return group;
        }).when(contentService).update(any(), eq(REPO),
                argThat(content -> content instanceof GroupItem && (PREFIX + id).equals(((GroupItem) content).getGroupId())));
    }

    private void failCreate(String id, boolean afterStoring) {
        doAnswer(create -> {
            GroupItem group = create.getArgument(2);
            if (afterStoring) {
                persisted.put(group.getGroupId(), copy(group));
            }
            throw new IllegalStateException(afterStoring ? "change log down" : "store down");
        }).when(contentService).createGroupItem(any(), eq(REPO),
                argThat((GroupItem group) -> group != null && (PREFIX + id).equals(group.getGroupId())));
    }

    /** A group whose member attribute came back in ranges: the sync refuses it and cannot read it. */
    private LdapGroup ranged(String dn, String id) {
        LdapGroup group = group(dn, id);
        group.setMemberAttributePresent(true);
        group.setMembersRanged(true);
        return group;
    }

    /** The groups written, in the order they were written — updates and creations together. */
    private List<String> writeOrder() {
        return org.mockito.Mockito.mockingDetails(contentService).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("update") || call.getMethod().getName().equals("createGroupItem"))
                .map(call -> call.getArguments()[2])
                .filter(argument -> argument instanceof GroupItem)
                .map(argument -> ((GroupItem) argument).getGroupId())
                .collect(Collectors.toList());
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

        GroupItem eng = the(writes(), "eng");
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

        assertEquals(List.of(PREFIX + "qa"), the(writes(), "eng").getGroups(), "the local \"qa\" was taken for LDAP's");
    }

    @Test
    @DisplayName("a nesting the first step could not remove is replaced when the group is written — the local group is not taken for LDAP's")
    void aNestingTheFirstStepCouldNotRemoveIsReplacedWhenTheGroupIsWritten() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group("cn=qa,ou=groups,dc=example,dc=com", "qa", "uid=alice,ou=users,dc=example,dc=com");
        group("cn=eng,ou=groups,dc=example,dc=com", "eng",
                "uid=alice,ou=users,dc=example,dc=com", "cn=qa,ou=groups,dc=example,dc=com");
        stored("qa", List.of("alice"), List.of());
        stored("eng", List.of("alice"), List.of("qa"));
        // The first step's writes of eng — removing the local "qa", and the one more try — fail before storing;
        // the later one does not.
        failFirstWrites("eng", 2);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "qa"), the(writes(), "eng").getGroups(),
                "the local \"qa\" was taken for LDAP's, so eng's nesting was left as it was");
        assertTrue(errors(result).contains(PREFIX + "eng") && errors(result).contains("could not be removed"),
                "the failed removal was not reported: " + errors(result));
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

    /** The last write of the group: what it holds when the sync is done. */
    private static GroupItem the(List<GroupItem> groups, String id) {
        return groups.stream().filter(g -> (PREFIX + id).equals(g.getGroupId())).reduce((first, second) -> second)
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
    @DisplayName("every cycle is refused, of two groups or of three, and a group nesting one is still written")
    void everyNestingCycleIsRefusedWhateverItsLength() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        // a and b nest each other; c, d and e nest in a ring; f nests the ring.
        for (String[] g : new String[][] {{"a", "b"}, {"b", "a"}, {"c", "d"}, {"d", "e"}, {"e", "c"}, {"f", "c"}}) {
            group("cn=" + g[0] + ",ou=groups,dc=example,dc=com", g[0], "uid=alice,ou=users,dc=example,dc=com",
                    "cn=" + g[1] + ",ou=groups,dc=example,dc=com");
        }

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "f"), ids(creates()), "a group in a cycle was written");
        assertEquals(List.of(), the(creates(), "f").getGroups(), "a group in a cycle was nested");
        for (String id : List.of("a", "b", "c", "d", "e")) {
            assertTrue(errors(result).contains(PREFIX + id + ": group " + PREFIX + id + ": LDAP nests it in a cycle"),
                    PREFIX + id + " was not reported as in a cycle: " + errors(result));
        }
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

    // ---------------------------------------------------------------- R151: a nesting kept by a group the sync does not write

    private static final String ALICE = "uid=alice,ou=users,dc=example,dc=com";
    private static final String DAVE = "uid=dave,ou=users,dc=example,dc=com";
    private static final String CONTRACTOR = "uid=x,ou=contractors,dc=example,dc=com";

    /** g, which LDAP now gives dave; NemakiWare's g names alice only. */
    private void gGainsDave() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of("alice"), List.of());
    }

    @Test
    @DisplayName("a nesting LDAP took out of a group the sync refuses is removed before the group below gains anyone")
    void aStaleNestingOfARefusedGroupIsRemovedFirst() {
        gGainsDave();
        // p is refused (a member outside the user search) and LDAP no longer nests g in it.
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "p", PREFIX + "g"), writeOrder(), "g gained dave while p still nested it");
        GroupItem p = writes().get(0);
        assertEquals(List.of(), p.getGroups(), "the nesting LDAP took out was kept: dave gains p");
        assertEquals(List.of("alice"), p.getUsers(), "removing the nesting rewrote the refused group's members");
        assertEquals(List.of("alice", "dave"), the(writes(), "g").getUsers());
        assertTrue(warningsOf(result, "p").contains("no longer names"), "the removal was not reported: " + warnings(result));
        assertTrue(errors(result).contains(PREFIX + "p"), "p was not reported as refused: " + errors(result));
    }

    @Test
    @DisplayName("a group the sync writes lets go of a nesting LDAP took out before the group below gains anyone")
    void aParentLetsGoOfAStaleNestingBeforeTheGroupBelowGainsAnyone() {
        gGainsDave();
        group(P_DN, "p", ALICE);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "p", PREFIX + "g"), writeOrder(),
                "g was written before p let go of it: dave held p in between");
        assertEquals(List.of(), the(writes(), "p").getGroups());
    }

    @Test
    @DisplayName("the preview removes the nesting on paper: same warnings, same counts, nothing written")
    void thePreviewRemovesAStaleNestingOnPaperOnly() {
        gGainsDave();
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        DirectorySyncResult preview = sync.previewSync(REPO);

        verify(contentService, never()).update(any(), any(), any());
        assertTrue(warningsOf(preview, "p").contains("no longer names"), "the preview did not report the removal: "
                + warnings(preview));
        assertEquals(1, preview.getGroupsUpdated(), "the preview held back what the sync adds: " + warnings(preview));
        assertTrue(warningsOf(preview, "g").isEmpty(), "the preview held g back: " + warnings(preview));
    }

    @Test
    @DisplayName("a nesting whose removal failed holds back the group below: it loses whom LDAP removes and gains no one")
    void aNestingThatCouldNotBeRemovedHoldsBackTheGroupBelow() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of("alice", "bob"), List.of());
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));
        failWrites("p", false);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), the(writes(), "g").getUsers(),
                "g gained dave, who reaches p through the nesting that could not be removed, or kept bob");
        assertTrue(warningsOf(result, "g").contains(PREFIX + "p"), "g's held-back member was not reported: "
                + warnings(result));
        assertTrue(errors(result).contains("could not be removed"), "the failed removal was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a removal stored before a later step failed counts as done — the group below is written as usual")
    void aRemovalStoredBeforeAFailureCountsAsDone() {
        gGainsDave();
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));
        failWrites("p", true);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice", "dave"), the(writes(), "g").getUsers(), "a stored removal was taken for a failed one");
        assertTrue(warningsOf(result, "p").contains("a step after storing the group failed"),
                "the later failure was not reported: " + warnings(result));
    }

    @Test
    @DisplayName("below a group whose members cannot be read, a group loses whom LDAP removes and gains no one")
    void aGroupBelowAnUncheckedNestingGainsNoOne() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of("alice", "bob"), List.of());
        ranged(P_DN, "p");
        stored("p", List.of("carol"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), the(writes(), "g").getUsers(), "dave was added, or bob kept");
        assertTrue(warningsOf(result, "g").contains(PREFIX + "p"), "the held-back member was not reported: "
                + warnings(result));
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "the nesting of a group whose members could not be read was changed");
    }

    @Test
    @DisplayName("an id that a nesting the sync cannot check names is not created — creating it would bring the nesting to life")
    void anIdAnUncheckedNestingNamesIsNotCreated() {
        user(ALICE, "alice");
        group("cn=x,ou=groups,dc=example,dc=com", "x", ALICE);
        ranged(P_DN, "p");
        stored("p", List.of("carol"), List.of(PREFIX + "x"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), ids(creates()), "x was created under the nesting of a group whose members could not be read");
        assertTrue(errors(result).contains(PREFIX + "x: group " + PREFIX + "x: " + PREFIX + "p nests this id"),
                "the refusal was not reported: " + errors(result));
    }

    @Test
    @DisplayName("an id further down — a group the unchecked nesting reaches names it — is not created either")
    void anIdFurtherDownAnUncheckedNestingIsNotCreated() {
        user(ALICE, "alice");
        ranged(P_DN, "p");
        stored("p", List.of("carol"), List.of(PREFIX + "y"));
        // y nests z in LDAP too; z is new.
        group("cn=y,ou=groups,dc=example,dc=com", "y", ALICE, "cn=z,ou=groups,dc=example,dc=com");
        stored("y", List.of("alice"), List.of(PREFIX + "z"));
        group("cn=z,ou=groups,dc=example,dc=com", "z", ALICE);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(ids(creates()).isEmpty(), "z was created below the unchecked nesting: " + ids(creates()));
        assertTrue(errors(result).contains(PREFIX + "z: group " + PREFIX + "z: " + PREFIX + "p nests this id"),
                "the refusal was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a local group between does not stop it: the group below gains no one")
    void aLocalGroupBetweenDoesNotStopTheHoldBack() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of("alice"), List.of());
        local("team", List.of(), List.of(PREFIX + "g"));
        ranged(P_DN, "p");
        stored("p", List.of("carol"), List.of("team"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained dave, who reaches p through the local team");
        assertTrue(warningsOf(result, "g").contains(PREFIX + "p"), "the held-back member was not reported: "
                + warnings(result));
    }

    @Test
    @DisplayName("a nesting a member value of the refused group could name is not removed — it is not known to be gone")
    void aNestingAnAmbiguousValueCouldNameIsKept() {
        user(ALICE, "alice");
        group(G_DN, "g", ALICE);
        group("cn=g,ou=archive,dc=example,dc=com", "g", ALICE);
        // p names one of the two g entries: which, is not known.
        group(P_DN, "p", ALICE, "cn=g,ou=archive,dc=example,dc=com");
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "a nesting p may still have in LDAP was removed");
        assertTrue(warningsOf(result, "p").contains("could not be checked"), "the unchecked nesting was not reported: "
                + warnings(result));
    }

    @Test
    @DisplayName("a refused group below an unchecked nesting is left as it is — none of its members is taken out")
    void aRefusedGroupBelowAnUncheckedNestingIsLeftAsItIs() {
        user(ALICE, "alice");
        group(G_DN, "g", ALICE, CONTRACTOR);
        stored("g", List.of("alice", "bob"), List.of());
        ranged(P_DN, "p");
        stored("p", List.of("carol"), List.of(PREFIX + "g"));

        sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "a group whose members are not known was rewritten: bob may be the member outside the search");
    }

    @Test
    @DisplayName("an orphan to be deleted lets go of its nesting before the groups below gain anyone")
    void anOrphanLetsGoOfItsNestingFirst() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        gGainsDave();
        stored("o", List.of("carol"), List.of(PREFIX + "g"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "o", PREFIX + "g"), writeOrder(), "g gained dave while the orphan still nested it");
        verify(contentService).deleteGroup(REPO, PREFIX + "o");
    }

    @Test
    @DisplayName("not syncing nested groups, no nesting is touched — of a refused group either")
    void withoutNestedSyncNoNestingIsTouched() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_NESTED_GROUPS)).thenReturn(false);
        gGainsDave();
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "g"), writeOrder(), "a nesting was touched though nested groups are not synced");
    }

    @Test
    @DisplayName("a nesting of a group the search does not return is kept when the refused group has a value it cannot name — the value may be that group")
    void aNestingOfAGroupOutsideTheSearchIsKept() {
        gGainsDave();
        // w is a group of the sync's that the group search no longer returns; it nests g.
        stored("w", List.of(), List.of(PREFIX + "g"));
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of("alice"), List.of(PREFIX + "w"));

        sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "p's nesting of w was removed, though the value p cannot name may be w");
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained dave, who reaches p through w");
    }

    @Test
    @DisplayName("below an unchecked nesting, a group nests no new group either")
    void aGroupBelowAnUncheckedNestingNestsNoNewGroup() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        ranged(P_DN, "p");
        stored("p", List.of("carol"), List.of(PREFIX + "g"));
        // LDAP now nests h, a new group, in g.
        group(G_DN, "g", ALICE, "cn=h,ou=groups,dc=example,dc=com");
        stored("g", List.of("alice"), List.of());
        group("cn=h,ou=groups,dc=example,dc=com", "h", DAVE);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "h"), ids(creates()));
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g newly nested h: dave reaches p through it");
        assertTrue(warningsOf(result, "g").contains(PREFIX + "p"), "the held-back nesting was not reported: "
                + warnings(result));
    }

    @Test
    @DisplayName("a group that cannot be read back after its nesting was taken out is not written by the sync, and the groups below it gain no one")
    void aGroupThatCannotBeReadBackAfterTheFirstStepIsNotWritten() {
        gGainsDave();
        // p is written by the sync — LDAP gives it dave too — and no longer nests g.
        group(P_DN, "p", ALICE, DAVE);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));
        when(contentService.getGroupItemByIdFresh(REPO, PREFIX + "p")).thenThrow(new IllegalStateException("store down"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(1, writes().stream().filter(w -> (PREFIX + "p").equals(w.getGroupId())).count(),
                "p was written again, from a state that could not be read back");
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained dave, though whether p still nests it is not known");
        assertTrue(errors(result).contains(PREFIX + "p: group " + PREFIX + "p: removing the nested groups"),
                "p was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a nesting a value written differently could name is kept — commonName=g is cn=g to the directory, though not as written")
    void aNestingAValueWrittenDifferentlyCouldNameIsKept() {
        gGainsDave();
        // LDAP still nests g in p, written with the attribute's long name; the group search returns cn=g.
        group(P_DN, "p", ALICE, "commonName=g,ou=groups,dc=example,dc=com");
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "a nesting LDAP still has was removed: g's members lose p's permissions");
        assertTrue(warningsOf(result, "p").contains("could not be checked"), "the unchecked nesting was not reported: "
                + warnings(result));
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained dave through a nesting the sync could not check");
    }

    @Test
    @DisplayName("a removal that failed before storing is tried once more, and the group below is written as usual")
    void aRemovalThatFailedOnceIsTriedAgain() {
        gGainsDave();
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));
        failFirstWrites("p", 1);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(), persisted.get(PREFIX + "p").getGroups(), "the removal was not tried again");
        assertEquals(List.of("alice", "dave"), the(writes(), "g").getUsers(), "g was held back by a nesting that is gone");
        assertTrue(warningsOf(result, "p").contains("on a second try"), "the first failure was not reported: "
                + warnings(result));
        assertTrue(!errors(result).contains("could not be removed"), "a removal that was done was reported as failed: "
                + errors(result));
    }

    @Test
    @DisplayName("once the group above is written and lets go of the nesting the first step could not remove, the groups written after it are not held back")
    void aGroupBelowIsLetGoOnceTheGroupAboveIsWritten() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        // p is written by the sync and no longer nests g; the first step cannot remove the nesting (both tries
        // fail before storing), writing p later succeeds, and g comes after p.
        group(P_DN, "p", ALICE);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of("alice"), List.of());
        failFirstWrites("p", 2);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "p", PREFIX + "p", PREFIX + "p", PREFIX + "g"), writeOrder());
        assertEquals(List.of(), persisted.get(PREFIX + "p").getGroups());
        assertEquals(List.of("alice", "dave"), the(writes(), "g").getUsers(),
                "g stayed held back after p let go of it: dave is not added until the next sync");
        assertTrue(errors(result).contains("could not be removed"), "the failed first step was not reported: "
                + errors(result));
    }

    @Test
    @DisplayName("the nesting of a group whose id two entries share is not checked, though the entry read first names it — which entry the group is, is not known")
    void aNestingOfAGroupWhoseIdTwoEntriesShareIsNotCheckedWhenTheFirstNamesIt() {
        gGainsDave();
        group(P_DN, "p", ALICE, G_DN);
        group("cn=p,ou=archive,dc=example,dc=com", "p", ALICE);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained dave, who reaches p — though which entry p is, is not known");
        assertTrue(warningsOf(result, "g").contains(PREFIX + "p"), "the held-back member was not reported: "
                + warnings(result));
    }

    @Test
    @DisplayName("the same when the entry read first does not name it — the order the directory returns them in decides nothing")
    void aNestingOfAGroupWhoseIdTwoEntriesShareIsNotCheckedWhenTheSecondNamesIt() {
        gGainsDave();
        group("cn=p,ou=archive,dc=example,dc=com", "p", ALICE);
        group(P_DN, "p", ALICE, G_DN);
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained dave, who reaches p — though which entry p is, is not known");
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "the nesting of a group whose entry is not known was removed");
    }

    @Test
    @DisplayName("below a nesting the sync cannot check, a user the sync created is not let in, though the group listed the id before")
    void aUserTheSyncCreatedIsNotLetInBelowAnUncheckedNesting() {
        createdUsersGoIntoUsers();
        user(ALICE, "alice");
        user("uid=carol,ou=users,dc=example,dc=com", "carol");
        when(contentService.getUserItemById(REPO, "carol")).thenReturn(null);
        group(G_DN, "g", ALICE, "uid=carol,ou=users,dc=example,dc=com");
        // 3.4.0 wrote carol's id into g before she was a NemakiWare user.
        stored("g", List.of("alice", "carol"), List.of());
        ranged(P_DN, "p");
        stored("p", List.of("bob"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), the(writes(), "g").getUsers(),
                "carol, whom this sync created, was let into g — and through the unchecked nesting into p");
        assertTrue(warningsOf(result, "g").contains(PREFIX + "p"), "the held-back member was not reported: "
                + warnings(result));
    }

    /** g is refused (a member outside the search) and lists carol from before she was a user; p's members cannot be read. */
    private void carolListedInARefusedGroup() {
        createdUsersGoIntoUsers();
        user(ALICE, "alice");
        user("uid=carol,ou=users,dc=example,dc=com", "carol");
        when(contentService.getUserItemById(REPO, "carol")).thenReturn(null);
        group(G_DN, "g", ALICE, CONTRACTOR);
        stored("g", List.of("alice", "carol"), List.of());
        ranged(P_DN, "p");
        stored("p", List.of("bob"), List.of(PREFIX + "g"));
    }

    @Test
    @DisplayName("a user the sync created is taken out of a group it does not write that listed her before she existed — the group gains no one, nor the group above it")
    void aUserTheSyncCreatedIsTakenOutOfAGroupItDoesNotWrite() {
        carolListedInARefusedGroup();

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), persisted.get(PREFIX + "g").getUsers(),
                "carol, created now and listed in a group the sync does not write, gains it — and p above it (or alice was taken out)");
        assertTrue(warningsOf(result, "g").contains("taken out"), "the created user taken out was not reported: "
                + warnings(result));
        assertTrue(errors(result).contains(PREFIX + "g"), "g is no longer reported as refused: " + errors(result));
    }

    @Test
    @DisplayName("the preview says which created users it would take out of a group it does not write, and writes nothing")
    void thePreviewSaysWhichCreatedUsersItWouldTakeOut() {
        carolListedInARefusedGroup();

        DirectorySyncResult preview = sync.previewSync(REPO);

        verify(contentService, never()).update(any(), any(), any());
        assertTrue(warningsOf(preview, "g").contains("taken out"), "the preview did not say it: " + warnings(preview));
    }

    @Test
    @DisplayName("removing a nesting from a group the sync does not write puts in the members LDAP names in it who belonged to it only through that nesting")
    void aMemberLdapNamesInTheGroupKeepsItWhenTheNestingIsRemoved() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of("alice"), List.of());
        // p names alice and someone outside the search (it is refused), and no longer nests g; NemakiWare's p
        // has alice only through g.
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of(), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        GroupItem p = persisted.get(PREFIX + "p");
        assertEquals(List.of(), p.getGroups());
        assertEquals(List.of("alice"), p.getUsers(),
                "alice, whom LDAP names in p, lost p's permissions with the nesting (or dave, whom it does not, gained them)");
        assertEquals(List.of("alice", "dave"), the(writes(), "g").getUsers());
        assertTrue(warningsOf(result, "p").contains("written into it"), "the member written in was not reported: "
                + warnings(result));
    }

    @Test
    @DisplayName("a write that failed before storing is not taken for stored because the read-back shows the same members — the new name was not stored")
    void aNameThatWasNotStoredIsNotTakenForStored() {
        user(ALICE, "alice");
        LdapGroup g = group(G_DN, "g", ALICE);
        g.setGroupName("Engineering");
        stored("g", List.of("alice"), List.of());
        failWrites("g", false);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(errors(result).contains(PREFIX + "g"), "a write that stored nothing was taken for one that stored: "
                + errors(result));
        assertTrue(!warningsOf(result, "g").contains("was stored"), "a write that stored nothing was reported as stored: "
                + warnings(result));
    }

    @Test
    @DisplayName("an orphan that cannot be read back after the first step is reported — no LDAP group reports it")
    void anOrphanThatCannotBeReadBackIsReported() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        gGainsDave();
        stored("o", List.of("carol"), List.of(PREFIX + "g"));
        when(contentService.getGroupItemByIdFresh(REPO, PREFIX + "o")).thenThrow(new IllegalStateException("store down"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(errors(result).contains(PREFIX + "o: group " + PREFIX + "o: removing the nested groups"),
                "the orphan that could not be read back was not reported: " + errors(result));
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained dave, though whether the orphan still nests it is not known");
    }

    @Test
    @DisplayName("below a group returned without its member attribute, a group gains no one — the nesting is not known to be gone")
    void aGroupBelowAGroupReturnedWithoutMembersGainsNoOne() {
        gGainsDave();
        group(P_DN, "p");
        stored("p", List.of("carol"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "the nesting of a group returned without its members was removed");
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained dave, who reaches p");
        assertTrue(warningsOf(result, "g").contains(PREFIX + "p"), "the held-back member was not reported: "
                + warnings(result));
    }

    // ---------------------------------------------------------------- R152: a write that threw after storing

    @Test
    @DisplayName("a group stored before a later step of the write failed is nested by its parent — read back past the cache")
    void aGroupStoredBeforeAFailureIsNested() {
        gGainsDave();
        group(P_DN, "p", ALICE, G_DN);
        failWrites("g", true);
        // The cache still holds g as it was: the answer has to come from the store.
        when(contentService.getGroupItemById(REPO, PREFIX + "g")).thenReturn(
                new GroupItem("doc-g", "nemaki:group", PREFIX + "g", "g", new ArrayList<>(List.of("alice")), new ArrayList<>()));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "g"), the(creates(), "p").getGroups(), "a stored group was taken for a failed one");
        assertTrue(warningsOf(result, "g").contains("a step after storing it failed"),
                "the later failure was not reported: " + warnings(result));
    }

    @Test
    @DisplayName("a group created before a later step failed is nested by its parent")
    void aGroupCreatedBeforeAFailureIsNested() {
        user(ALICE, "alice");
        group(G_DN, "g", ALICE);
        group(P_DN, "p", ALICE, G_DN);
        failCreate("g", true);

        sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "g"), the(creates(), "p").getGroups(), "a created group was taken for a failed one");
    }
}
