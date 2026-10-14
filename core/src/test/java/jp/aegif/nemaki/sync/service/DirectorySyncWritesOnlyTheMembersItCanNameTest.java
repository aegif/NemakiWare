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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.naming.NamingException;

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
import jp.aegif.nemaki.sync.model.DirectorySyncResult.SyncStatus;
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
        // The list is read from the store as it is now — a sync reads it again after the user side has written
        // (R153 (a)) — in the order the groups were stored.
        when(contentService.getGroupItems(REPO)).thenAnswer(list -> {
            List<GroupItem> now = new ArrayList<>();
            for (GroupItem group : stored) {
                GroupItem held = persisted.get(group.getGroupId());
                now.add(held == null ? group : copy(held));
            }
            return now;
        });
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

        // A write stores a copy — refused, as CouchDB refuses it, when it carries a revision the store no longer
        // has — and a read past the cache answers what is stored (R151, R152, R153 (a)).
        doAnswer(write -> {
            Object content = write.getArgument(2);
            if (content instanceof GroupItem) {
                store((GroupItem) content);
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

    /** Stores a copy of the group under the next revision — refused when it carries a revision that is not the store's. */
    private void store(GroupItem group) {
        GroupItem held = persisted.get(group.getGroupId());
        if (held != null && held.getRevision() != null && !held.getRevision().equals(group.getRevision())) {
            throw new IllegalStateException("conflict: " + group.getGroupId() + " is at " + held.getRevision()
                    + ", the write carried " + group.getRevision());
        }
        GroupItem stored = copy(group);
        String at = held == null ? group.getRevision() : held.getRevision();
        int n = at == null ? 0 : Integer.parseInt(at.substring(0, at.indexOf('-')));
        stored.setRevision((n + 1) + "-abc");
        persisted.put(group.getGroupId(), stored);
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
                store(group);
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
            store(group);
            return group;
        }).when(contentService).update(any(), eq(REPO),
                argThat(content -> content instanceof GroupItem && (PREFIX + id).equals(((GroupItem) content).getGroupId())));
    }

    /** The first writes of the group store it; the later ones fail before storing. */
    private void failWritesAfter(String id, int storing) {
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(write -> {
            GroupItem group = write.getArgument(2);
            if (writes.getAndIncrement() >= storing) {
                throw new IllegalStateException("store down");
            }
            store(group);
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

    @Test
    @DisplayName("a group list read before the users but not after them syncs no group — the second read decides as the first does (R153 (a))")
    void aGroupListThatCannotBeReadAgainSyncsNoGroup() {
        user("uid=alice,ou=users,dc=example,dc=com", "alice");
        group(G_DN, "g", "uid=alice,ou=users,dc=example,dc=com");
        int[] reads = {0};
        when(contentService.getGroupItems(REPO)).thenAnswer(list -> reads[0]++ == 0 ? new ArrayList<GroupItem>() : null);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(2, reads[0], "the group list was not read again after the users");
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
        assertTrue(warningsOf(result, "p").contains("put back"), "the member put back was not reported: "
                + warnings(result));
    }

    @Test
    @DisplayName("only the members LDAP names in the group are written in with the removal — not everyone below the nesting")
    void onlyMembersLdapNamesAreWrittenInWithTheRemoval() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(G_DN, "g", ALICE, DAVE);
        // NemakiWare's g lists bob and dave; LDAP names neither in p. Dave is a NemakiWare user the sync looked up, so
        // only LDAP's naming keeps him out (bob is no directory user, and his state alone would). p is refused for a value
        // two user entries could be — one outside the searches could name dave, and keep the nesting (R157).
        twoSatos();
        stored("g", List.of("alice", "bob", "dave"), List.of());
        group(P_DN, "p", ALICE, SATO_ALUMNI);
        stored("p", List.of(), List.of(PREFIX + "g"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), persisted.get(PREFIX + "p").getUsers(),
                "dave or bob, whom LDAP does not name in p, was put back into it before the removal");
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

    @Test
    @DisplayName("an empty group below a group returned without its members gains no one — the nesting is not checked, though no one reaches the group above through it yet")
    void anEmptyGroupBelowAGroupReturnedWithoutMembersGainsNoOne() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        // No one reaches p through g yet, so nothing would hold a removal of the nesting back: only the check of p's
        // members does (batch 13 review round 1: the hold on members whom LDAP may name covered the other lock).
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of(), List.of());
        group(P_DN, "p");
        stored("p", List.of("carol"), List.of(PREFIX + "g"));

        sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "the nesting of a group returned without its members was removed");
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "g").equals(w.getGroupId())),
                "g gained alice and dave, who reach p");
    }


    // ---------------------------------------------------------------- R153 (a): the users this sync creates (batch 13)

    private static final String CAROL = "uid=carol,ou=users,dc=example,dc=com";

    /** carol is a directory user NemakiWare does not have: this sync creates her. */
    private void carolIsNew() {
        createdUsersGoIntoUsers();
        user(CAROL, "carol");
        when(contentService.getUserItemById(REPO, "carol")).thenReturn(null);
    }

    @Test
    @DisplayName("a sync that cannot read the groups from the directory creates no one — it stops before writing any user")
    void aGroupSearchThatFailsCreatesNoOne() throws Exception {
        carolIsNew();
        when(directory.searchGroups()).thenThrow(new NamingException("directory down"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(SyncStatus.FAILED, result.getStatus());
        verify(contentService, never()).createUserItem(any(), any(), any(UserItem.class));
    }

    @Test
    @DisplayName("a sync that cannot read NemakiWare's group list creates no one")
    void anUnreadableGroupListCreatesNoOne() {
        carolIsNew();
        group(G_DN, "g", CAROL);
        when(contentService.getGroupItems(REPO)).thenReturn(null);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(SyncStatus.FAILED, result.getStatus());
        verify(contentService, never()).createUserItem(any(), any(), any(UserItem.class));
    }

    @Test
    @DisplayName("before a user is created, her id is taken out of the groups of this sync that list it from before")
    void anOldIdIsTakenOutBeforeTheUserIsCreated() {
        carolIsNew();
        user(ALICE, "alice");
        group(G_DN, "g", ALICE, CONTRACTOR);
        stored("g", List.of("alice", "carol"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), persisted.get(PREFIX + "g").getUsers(),
                "carol, created now, was left in a group LDAP does not let the sync write");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(contentService);
        order.verify(contentService).update(any(), eq(REPO),
                argThat(content -> content instanceof GroupItem && (PREFIX + "g").equals(((GroupItem) content).getGroupId())));
        order.verify(contentService).createUserItem(any(), eq(REPO), any(UserItem.class));
        assertTrue(warningsOf(result, "g").contains("taken out"), "the id taken out was not reported: " + warnings(result));
    }

    @Test
    @DisplayName("a user whose old id cannot be taken out of a group is not created this sync")
    void aUserWhoseOldIdCannotBeTakenOutIsNotCreated() {
        carolIsNew();
        user(ALICE, "alice");
        group(G_DN, "g", ALICE, CONTRACTOR);
        stored("g", List.of("alice", "carol"), List.of());
        failWrites("g", false);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService, never()).createUserItem(any(), any(), any(UserItem.class));
        assertTrue(errors(result).contains(PREFIX + "g") && errors(result).contains("could not be taken out"),
                "the id left in the group was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a user not created because her old id could not be taken out is no member, and refuses no group")
    void aUserNotCreatedForAnOldIdIsNoMemberAndRefusesNothing() {
        carolIsNew();
        user(ALICE, "alice");
        // h listed carol from before and cannot be written, so carol is not created; LDAP names her in g.
        group("cn=h,ou=groups,dc=example,dc=com", "h", ALICE);
        stored("h", List.of("alice", "carol"), List.of());
        failWrites("h", false);
        group(G_DN, "g", ALICE, CAROL);
        stored("g", List.of("alice"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService, never()).createUserItem(any(), any(), any(UserItem.class));
        assertTrue(!errors(result).contains(PREFIX + "g"), "g was refused for carol, whom the sync did not create: "
                + errors(result));
    }

    @Test
    @DisplayName("a member DN with the dotless \u0131 is written as the user whose DN has it, not the user whose DN has i (R155)")
    void aDotlessIIsTheDotlessUserAtTheEntryPoint() {
        // The directory keeps both; LdapName took them for one, and the entry read last took the other's memberships.
        user("uid=\u0131lgaz,ou=users,dc=example,dc=com", "\u0131lgaz");
        user("uid=ilgaz,ou=users,dc=example,dc=com", "ilgaz");
        group(G_DN, "g", "uid=\u0131lgaz,ou=users,dc=example,dc=com");
        stored("g", List.of(), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of("\u0131lgaz"), persisted.get(PREFIX + "g").getUsers(), "the group was written with another user");
    }

    @Test
    @DisplayName("a group write that fails after the user was created leaves no old id of hers in the group")
    void aGroupWriteThatFailsLeavesNoOldIdOfACreatedUser() {
        carolIsNew();
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of("alice", "carol"), List.of());
        // Taking carol out stores; writing g's members fails before storing.
        failWritesAfter("g", 1);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), persisted.get(PREFIX + "g").getUsers(),
                "carol's old id stayed in g after its write failed: she gains it though LDAP does not name her there");
        assertTrue(errors(result).contains(PREFIX + "g"), "the failed write was not reported: " + errors(result));
    }

    @Test
    @DisplayName("the groups are read again after the users are synced — a write from the list read before carries a revision the store no longer has")
    void theGroupsAreReadAgainAfterTheUsers() {
        carolIsNew();
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(G_DN, "g", ALICE, DAVE);
        stored("g", List.of("alice", "carol"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals("", errors(result));
        assertEquals(List.of("alice", "dave"), persisted.get(PREFIX + "g").getUsers());
    }

    @Test
    @DisplayName("a user stored before a later step of her creation failed is a member — read back, she is there")
    void aUserStoredBeforeALaterStepFailedIsAMember() {
        createdUsersGoIntoUsers();
        user(CAROL, "carol");
        Set<String> created = new HashSet<>();
        when(contentService.getUserItemById(REPO, "carol"))
                .thenAnswer(read -> created.contains("carol") ? mock(UserItem.class) : null);
        doAnswer(create -> {
            created.add("carol");
            throw new IllegalStateException("change log down");
        }).when(contentService).createUserItem(any(), eq(REPO), any(UserItem.class));
        group(G_DN, "g", CAROL);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("carol"), the(creates(), "g").getUsers(), "a user who was stored was taken for one who was not");
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.getMessage().contains("was stored")),
                "the later failure was not reported: " + warnings(result));
    }

    @Test
    @DisplayName("a user whose creation failed and who is not there is no member — and the group naming her is still written")
    void aUserNotStoredIsNoMemberAndRefusesNothing() {
        createdUsersGoIntoUsers();
        user(ALICE, "alice");
        user(CAROL, "carol");
        when(contentService.getUserItemById(REPO, "carol")).thenReturn(null);
        doAnswer(create -> {
            throw new IllegalStateException("store down");
        }).when(contentService).createUserItem(any(), eq(REPO), any(UserItem.class));
        group(G_DN, "g", ALICE, CAROL);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), the(creates(), "g").getUsers());
        assertTrue(!errors(result).contains(PREFIX + "g"), "a member read as absent refused the group: " + errors(result));
    }

    @Test
    @DisplayName("a second directory entry with an id already seen is not looked up or created again")
    void aSecondEntryWithTheSameIdIsCreatedOnce() {
        createdUsersGoIntoUsers();
        user(CAROL, "carol");
        user("uid=carol,ou=archive,dc=example,dc=com", "carol");
        when(contentService.getUserItemById(REPO, "carol")).thenReturn(null);

        sync.syncGroups(REPO, false);

        verify(contentService, times(1)).createUserItem(any(), eq(REPO), any(UserItem.class));
    }

    // ---------------------------------------------------------------- R154: the members put back (batch 13)

    private static final String X_DN = "cn=x,ou=groups,dc=example,dc=com";
    private static final String Y_DN = "cn=y,ou=groups,dc=example,dc=com";
    private static final String Z_DN = "cn=z,ou=groups,dc=example,dc=com";
    private static final String BOB = "uid=bob,ou=users,dc=example,dc=com";

    /** LDAP took y out of x and x out of p; p names alice itself (and someone outside the search). */
    private void aliceReachesPThroughXAndY(boolean pFirst) {
        aliceReachesPThroughXAndY(pFirst, CONTRACTOR);
    }

    /** The same, p refused for the value given — CONTRACTOR, outside the searches, may name anyone there (R157). */
    private void aliceReachesPThroughXAndY(boolean pFirst, String refusing) {
        user(ALICE, "alice");
        user(BOB, "bob");
        if (pFirst) {
            group(P_DN, "p", ALICE, refusing);
        }
        group(X_DN, "x", BOB);
        if (!pFirst) {
            group(P_DN, "p", ALICE, refusing);
        }
        group(Y_DN, "y", ALICE);
        stored("p", List.of(), List.of(PREFIX + "x"));
        stored("x", List.of("bob"), List.of(PREFIX + "y"));
        stored("y", List.of("alice"), List.of());
    }

    @Test
    @DisplayName("the member LDAP names in a group is put back before the nesting she reached it through is removed — x read first")
    void aMemberIsPutBackWhenTheGroupBelowIsReadFirst() {
        // p is refused for a value two user entries could be: whom else LDAP names in p is known, so x is taken out.
        twoSatos();
        aliceReachesPThroughXAndY(false, SATO_ALUMNI);

        sync.syncGroups(REPO, false);

        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whom LDAP names in p, lost p's permissions: p was written after x let go of y");
        assertEquals(List.of(), persisted.get(PREFIX + "p").getGroups());
    }

    @Test
    @DisplayName("the same when p is read first — the order the directory returns the groups in decides nothing")
    void aMemberIsPutBackWhenTheGroupAboveIsReadFirst() {
        aliceReachesPThroughXAndY(true);

        sync.syncGroups(REPO, false);

        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whom LDAP names in p, lost p's permissions");
    }

    @Test
    @DisplayName("a nesting removed further down puts back, in the group above, the members LDAP names there")
    void aMemberIsPutBackInTheGroupAboveTheRemovedNesting() {
        user(ALICE, "alice");
        user(BOB, "bob");
        // p still nests x in LDAP; LDAP took y out of x.
        group(P_DN, "p", ALICE, CONTRACTOR, X_DN);
        group(X_DN, "x", BOB);
        group(Y_DN, "y", ALICE);
        stored("p", List.of(), List.of(PREFIX + "x"));
        stored("x", List.of("bob"), List.of(PREFIX + "y"));
        stored("y", List.of("alice"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of(), persisted.get(PREFIX + "x").getGroups());
        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whom LDAP names in p, lost p when y was taken out of x");
    }

    @Test
    @DisplayName("a member LDAP names in a group through a group it nests is put back too")
    void aMemberNamedThroughANestedGroupIsPutBack() {
        user(ALICE, "alice");
        user(BOB, "bob");
        // LDAP: p nests z, and z names bob; p no longer nests x, where bob is.
        group(P_DN, "p", ALICE, CONTRACTOR, Z_DN);
        group(Z_DN, "z", BOB);
        group(X_DN, "x", BOB);
        stored("p", List.of("alice"), List.of(PREFIX + "x"));
        stored("x", List.of("bob"), List.of());
        stored("z", List.of("bob"), List.of());

        sync.syncGroups(REPO, false);

        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("bob"),
                "bob, whom LDAP names in p through z, lost p when x was taken out — p is refused, so it does not nest z");
    }

    @Test
    @DisplayName("a member whose lookup failed is put back — she reached the group already")
    void aMemberWhoseLookupFailedIsPutBack() {
        aliceReachesPThroughXAndY(false);
        when(contentService.getUserItemById(REPO, "alice")).thenThrow(new IllegalStateException("store down"));

        sync.syncGroups(REPO, false);

        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whose lookup failed, lost p's permissions");
    }

    @Test
    @DisplayName("a user the sync created is not put back — she reached nothing before she existed")
    void aUserTheSyncCreatedIsNotPutBack() {
        carolIsNew();
        user(ALICE, "alice");
        // A local group lists carol from before; p nested it, which LDAP cannot state.
        local("team", List.of("carol"), List.of());
        group(P_DN, "p", ALICE, CAROL, CONTRACTOR);
        stored("p", List.of("alice"), List.of("team"));

        sync.syncGroups(REPO, false);

        assertTrue(!persisted.get(PREFIX + "p").getUsers().contains("carol"),
                "carol, created now, was put into p, which the sync does not write");
    }

    @Test
    @DisplayName("a nesting is kept when the members it would take a group above from cannot be put back there — and the groups below gain no one")
    void aNestingIsKeptWhenItsMembersCannotBePutBack() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        group(P_DN, "p", ALICE, CONTRACTOR, X_DN);
        group(X_DN, "x", BOB);
        group(Y_DN, "y", ALICE, DAVE);
        stored("p", List.of(), List.of(PREFIX + "x"));
        stored("x", List.of("bob"), List.of(PREFIX + "y"));
        stored("y", List.of("alice"), List.of());
        failWrites("p", false);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "y"), persisted.get(PREFIX + "x").getGroups(),
                "y was taken out of x though alice could not be put back in p: she lost p's permissions");
        assertTrue(!persisted.get(PREFIX + "y").getUsers().contains("dave"),
                "y gained dave through the nesting kept: he reaches p");
        assertTrue(errors(result).contains(PREFIX + "p") && errors(result).contains("could not be put back"),
                "the member that could not be put back was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a group whose id two entries share keeps the nesting when a member it would lose is named by only one of them")
    void aSharedIdKeepsTheNestingForAMemberOnlyOneEntryNames() {
        user(ALICE, "alice");
        user(BOB, "bob");
        // Which of the two entries NemakiWare's p is, is not known; x no longer nests y in LDAP. Both name alice, one
        // names bob: if p is that one, LDAP names bob in p, and taking y out of x would take p from him.
        // x names dave alone: a value outside the searches in x would keep the nesting by itself (R157).
        user(DAVE, "dave");
        group(P_DN, "p", ALICE, X_DN);
        group("cn=p,ou=archive,dc=example,dc=com", "p", ALICE, BOB, X_DN);
        group(X_DN, "x", DAVE);
        group(Y_DN, "y", ALICE, BOB);
        stored("p", List.of(), List.of(PREFIX + "x"));
        stored("x", List.of(), List.of(PREFIX + "y"));
        stored("y", List.of("alice", "bob"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "y"), persisted.get(PREFIX + "x").getGroups(),
                "y was taken out of x, though LDAP may still name bob in p: he lost p");
        assertTrue(errors(result).contains(PREFIX + "p") && errors(result).contains("not all known"),
                "the nesting kept was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a nesting is kept when a member value the sync cannot name could be the NemakiWare user it did not look up who would lose the group")
    void aNestingIsKeptForAUserNotLookedUpWhomAValueCouldName() {
        user(DAVE, "dave");
        // LDAP names, in p, dave and a DN outside the user search, and no longer nests x in p. Alice is a NemakiWare user
        // the sync did not look up: the DN outside may be hers.
        String outside = "uid=alice,ou=outside,dc=example,dc=com";
        group(P_DN, "p", DAVE, outside);
        group(X_DN, "x", outside);
        stored("p", List.of("dave"), List.of(PREFIX + "x"));
        stored("x", List.of("alice"), List.of());
        when(contentService.getUserItemById(REPO, "alice")).thenReturn(mock(UserItem.class));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "x"), persisted.get(PREFIX + "p").getGroups(),
                "x was taken out of p, though LDAP may name alice in p: she lost p");
        assertTrue(errors(result).contains(PREFIX + "p") && errors(result).contains("not all known"),
                "the nesting kept was not reported: " + errors(result));
    }

    @Test
    @DisplayName("an id no NemakiWare user has keeps no nesting, though a member value the sync cannot name could be anyone outside")
    void anIdNoUserHasKeepsNoNesting() {
        user(DAVE, "dave");
        group(P_DN, "p", DAVE, CONTRACTOR);
        group(X_DN, "x", "uid=ghost,ou=outside,dc=example,dc=com");
        stored("p", List.of("dave"), List.of(PREFIX + "x"));
        // No NemakiWare user has the id ghost: the store answers none.
        stored("x", List.of("ghost"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of(), persisted.get(PREFIX + "p").getGroups(), "a nesting LDAP took out was kept for an id no one has");
    }

    @Test
    @DisplayName("a nesting is kept when a member value written differently could be the user who would lose the group")
    void aNestingIsKeptWhenAValueWrittenDifferentlyCouldBeTheUser() {
        user(DAVE, "dave");
        user(BOB, "bob");
        // LDAP names bob in p by his DN written with an alias of uid: the sync does not name it, but it could be him.
        group(P_DN, "p", DAVE, "userid=bob,ou=users,dc=example,dc=com");
        group(X_DN, "x", BOB);
        stored("p", List.of("dave"), List.of(PREFIX + "x"));
        stored("x", List.of("bob"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "x"), persisted.get(PREFIX + "p").getGroups(),
                "x was taken out of p, though LDAP may name bob in p: he lost p");
    }

    @Test
    @DisplayName("a nesting is kept when a group above whose members could not be read could lose a member through it")
    void aNestingIsKeptWhenAGroupAboveCouldNotBeRead() {
        user(BOB, "bob");
        user(DAVE, "dave");
        // q came back in ranges: whom it names is not known. It nests x; LDAP took y, where bob is, out of x.
        ranged("cn=q,ou=groups,dc=example,dc=com", "q");
        group(X_DN, "x", DAVE);
        group(Y_DN, "y", BOB);
        stored("q", List.of(), List.of(PREFIX + "x"));
        stored("x", List.of("dave"), List.of(PREFIX + "y"));
        stored("y", List.of("bob"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "y"), persisted.get(PREFIX + "x").getGroups(),
                "y was taken out of x, though q may name bob: he lost q");
        assertTrue(errors(result).contains(PREFIX + "q") && errors(result).contains("not all known"),
                "the nesting kept was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a nesting is kept when a member it would take a group from is named there through a group whose id two entries share")
    void aNestingIsKeptForAMemberNamedThroughASharedId() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        // LDAP: p names dave and one of two group entries with the id z; both name alice. p no longer nests x.
        group(P_DN, "p", DAVE, Z_DN);
        group(Z_DN, "z", ALICE);
        group("cn=z,ou=archive,dc=example,dc=com", "z", ALICE);
        group(X_DN, "x", ALICE);
        stored("p", List.of("dave"), List.of(PREFIX + "x"));
        stored("x", List.of("alice"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "x"), persisted.get(PREFIX + "p").getGroups(),
                "x was taken out of p, though LDAP names alice in p through z: she lost p");
    }

    @Test
    @DisplayName("a member LDAP names in a group through a group it cannot nest stays a member when the group is written")
    void aMemberNamedThroughAGroupItCannotNestStays() {
        user(DAVE, "dave");
        user(BOB, "bob");
        // LDAP: p names dave and nests z; z names bob and a member the sync cannot name, so z is not written and p does
        // not nest it. NemakiWare's p has bob from before.
        group(P_DN, "p", DAVE, Z_DN);
        group(Z_DN, "z", BOB, CONTRACTOR);
        stored("p", List.of("dave", "bob"), List.of());

        sync.syncGroups(REPO, false);

        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("bob"),
                "bob, whom LDAP names in p through z, lost p when it was written with its direct members alone");
    }

    @Test
    @DisplayName("a member the group reaches through a group it nests is not kept as a direct member too")
    void aMemberReachedThroughANestedGroupIsNotKeptDirectly() {
        user(DAVE, "dave");
        user(BOB, "bob");
        group(P_DN, "p", DAVE, Z_DN);
        group(Z_DN, "z", BOB);
        stored("p", List.of("dave", "bob"), List.of(PREFIX + "z"));
        stored("z", List.of("bob"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "p").getUsers(),
                "bob, whom p reaches through z, was kept as a direct member of p");
    }

    // ---------------------------------------------------------------- batch 14: R157, R156, R158

    private static final String A_DN = "cn=a,ou=groups,dc=example,dc=com";
    private static final String H_DN = "cn=h,ou=groups,dc=example,dc=com";
    private static final String J_DN = "cn=j,ou=groups,dc=example,dc=com";
    private static final String K_DN = "cn=k,ou=groups,dc=example,dc=com";
    private static final String M_DN = "cn=m,ou=groups,dc=example,dc=com";
    private static final String U_DN = "cn=u,ou=groups,dc=example,dc=com";
    private static final String CA_DN = "cn=ca,ou=groups,dc=example,dc=com";
    private static final String CB_DN = "cn=cb,ou=groups,dc=example,dc=com";
    private static final String D_DN = "cn=d,ou=groups,dc=example,dc=com";
    private static final String E_DN = "cn=e,ou=groups,dc=example,dc=com";
    private static final String V_DN = "cn=v,ou=groups,dc=example,dc=com";
    private static final String G1_DN = "cn=g1,ou=groups,dc=example,dc=com";
    private static final String G2_DN = "cn=g2,ou=groups,dc=example,dc=com";
    private static final String SATO_STAFF = "uid=sato,ou=staff,dc=example,dc=com";
    private static final String SATO_ALUMNI = "uid=sato,ou=alumni,dc=example,dc=com";

    /** Two user entries have the id sato: a value naming one of them is ambiguous — it could be the sato who logs in. */
    private void twoSatos() {
        user(SATO_STAFF, "sato");
        user(SATO_ALUMNI, "sato");
    }

    @Test
    @DisplayName("a nesting is kept when a member value outside the searches could be a group naming a user the sync looked up (R157)")
    void aNestingIsKeptWhenAValueOutsideTheSearchesCouldNameASearchedUser() {
        // LDAP no longer nests x in p, and names in p a DN no search returned: it may be a group outside the searches
        // that names bob, who reaches p through x.
        aliceReachesPThroughXAndY(false);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "x"), persisted.get(PREFIX + "p").getGroups(),
                "x was taken out of p, though the value outside the searches may name bob there: he lost p");
        assertTrue(errors(result).contains(PREFIX + "p") && errors(result).contains("not all known"),
                "the nesting kept was not reported: " + errors(result));
    }

    @Test
    @DisplayName("an orphan is kept when a member value outside the searches could be it — naming a user the sync looked up (R157)")
    void anOrphanIsKeptWhenAValueOutsideTheSearchesCouldBeIt() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        user(ALICE, "alice");
        user(BOB, "bob");
        // o was moved out of the group search; p names it still, by a DN no search returns now. o names bob.
        group(P_DN, "p", ALICE, "cn=o,ou=archive,dc=example,dc=com");
        stored("p", List.of("alice"), List.of(PREFIX + "o"));
        stored("o", List.of("bob"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService, never()).deleteGroup(REPO, PREFIX + "o");
        assertTrue(errors(result).contains(PREFIX + "p") && errors(result).contains("not all known"),
                "the orphan kept was not reported: " + errors(result));
    }

    @Test
    @DisplayName("where directory users have a prefix, a NemakiWare user without it is no one LDAP names: a nesting LDAP took out is removed (R158 (b))")
    void aUserWithoutThePrefixKeepsNoNesting() {
        when(properties.readValue(PropertyKey.DIRECTORY_SYNC_USER_PREFIX)).thenReturn("u_");
        ldapUsers.add(new LdapUser(ALICE, "alice", "alice"));
        when(contentService.getUserItemById(REPO, "u_alice")).thenReturn(mock(UserItem.class));
        // carol is a NemakiWare user of its own, without the prefix: no value of LDAP's names her, outside the searches
        // or not.
        when(contentService.getUserItemById(REPO, "carol")).thenReturn(mock(UserItem.class));
        group(P_DN, "p", ALICE, CONTRACTOR);
        local("team", List.of("carol"), List.of());
        stored("p", List.of("u_alice"), List.of("team"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of(), persisted.get(PREFIX + "p").getGroups(), "team was kept under p for carol, whom LDAP cannot name");
    }

    @Test
    @DisplayName("where directory users have a prefix, a user with it whom the sync did not look up may still be named: the nesting is kept")
    void aUserWithThePrefixNotLookedUpKeepsTheNesting() {
        when(properties.readValue(PropertyKey.DIRECTORY_SYNC_USER_PREFIX)).thenReturn("u_");
        ldapUsers.add(new LdapUser(ALICE, "alice", "alice"));
        when(contentService.getUserItemById(REPO, "u_alice")).thenReturn(mock(UserItem.class));
        // u_dave is a directory user's id the searches did not return: the value outside may be him.
        when(contentService.getUserItemById(REPO, "u_dave")).thenReturn(mock(UserItem.class));
        group(P_DN, "p", ALICE, CONTRACTOR);
        local("team", List.of("u_dave"), List.of());
        stored("p", List.of("u_alice"), List.of("team"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of("team"), persisted.get(PREFIX + "p").getGroups(),
                "team was taken out of p, though the value outside the searches may be u_dave: he lost p");
    }

    @Test
    @DisplayName("a member LDAP names in a group the sync does not write is put back there before the group below is written without her (R156)")
    void aMemberIsPutBackBeforeTheGroupBelowIsWrittenWithoutHer() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        // LDAP: p names alice and nests g, with a member the sync cannot name, so p is not written. g names dave alone now.
        group(P_DN, "p", ALICE, CONTRACTOR, G_DN);
        group(G_DN, "g", DAVE);
        stored("p", List.of(), List.of(PREFIX + "g"));
        stored("g", List.of("alice", "dave"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "g").getUsers());
        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whom LDAP names in p, lost p when g was written without her");
        assertTrue(errors(result).contains(PREFIX + "p: group " + PREFIX + "p: ") && errors(result).contains("changed it first"),
                "p's refusal did not say the sync wrote it first: " + errors(result));
    }

    @Test
    @DisplayName("the same when g's write fails after storing it: she is put back before, whatever the write does")
    void aMemberIsPutBackBeforeAGroupBelowStoredBeforeAFailure() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        group(P_DN, "p", ALICE, CONTRACTOR, G_DN);
        group(G_DN, "g", DAVE);
        stored("p", List.of(), List.of(PREFIX + "g"));
        stored("g", List.of("alice", "dave"), List.of());
        failWrites("g", true);

        sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "g").getUsers(), "g was not stored before the failure");
        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whom LDAP names in p, lost p when g was stored without her");
    }

    @Test
    @DisplayName("a member LDAP may name in a group the sync does not write, who loses it only through the group below written as LDAP states it, is said — not put back, not held (R156)")
    void aMemberLdapMayNameWhoLosesTheGroupThroughAWriteBelowIsSaid() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        // LDAP: p names alice, nests g, and has a value outside the searches, which may name bob. g names dave alone now.
        group(P_DN, "p", ALICE, CONTRACTOR, G_DN);
        group(G_DN, "g", DAVE);
        stored("p", List.of(), List.of(PREFIX + "g"));
        stored("g", List.of("alice", "bob", "dave"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "g").getUsers(), "g was not written as LDAP states it");
        assertEquals(List.of("alice"), persisted.get(PREFIX + "p").getUsers(), "a member LDAP may not name was put back in p");
        assertTrue(warningsOf(result, "p").contains("they lose the group when that group is written"),
                "bob's loss of p was not said: " + warnings(result));
        assertTrue(!errors(result).contains("so that nesting is kept"), "a nesting was said to be kept: " + errors(result));
    }

    @Test
    @DisplayName("where nested groups are not synced, a member LDAP names in a group the sync does not write is put back there before the group below is written without her (R156)")
    void aMemberIsPutBackWhereNestingIsNotSynced() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_NESTED_GROUPS)).thenReturn(false);
        user(ALICE, "alice");
        user(DAVE, "dave");
        // NemakiWare nests g in p. The sync leaves nestings alone, but writes g's users — alice's way into p.
        group(P_DN, "p", ALICE, CONTRACTOR);
        group(G_DN, "g", DAVE);
        stored("p", List.of(), List.of(PREFIX + "g"));
        stored("g", List.of("alice", "dave"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "g").getUsers());
        assertEquals(List.of(PREFIX + "g"), persisted.get(PREFIX + "p").getGroups(), "a nesting was touched");
        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whom LDAP names in p, lost p when g was written without her");
    }

    @Test
    @DisplayName("where nested groups are not synced, LDAP's nesting is not followed: a member LDAP names in a group only through a group it nests is neither put back there nor said to be (R156)")
    void whereNestingIsNotSyncedLdapsNestingIsNotFollowed() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_NESTED_GROUPS)).thenReturn(false);
        user(ALICE, "alice");
        user(DAVE, "dave");
        // LDAP: p names dave and nests g; g names alice and a value outside the searches. NemakiWare nests h in p; h
        // names dave alone now. Where nested groups are not synced, p's members are dave alone.
        group(P_DN, "p", DAVE, G_DN);
        group(G_DN, "g", ALICE, CONTRACTOR);
        group(H_DN, "h", DAVE);
        stored("p", List.of("dave"), List.of(PREFIX + "h"));
        stored("h", List.of("alice", "dave"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "h").getUsers());
        assertEquals(List.of("dave"), persisted.get(PREFIX + "p").getUsers(),
                "alice was put back in p for LDAP's nesting, which this sync does not follow");
        assertTrue(!warningsOf(result, "p").contains("could be among them"),
                "p counted g's value outside the searches, through LDAP's nesting: " + warnings(result));
    }

    @Test
    @DisplayName("where nested groups are not synced, a member LDAP names in a group the sync does not write is put back there before the orphan she reached it through is deleted (R154, R156)")
    void aMemberIsPutBackBeforeAnOrphanIsDeletedWhereNestingIsNotSynced() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_NESTED_GROUPS)).thenReturn(false);
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        user(ALICE, "alice");
        // NemakiWare nests the orphan o in p; deleting o takes the nesting along — alice's way into p.
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of(), List.of(PREFIX + "o"));
        stored("o", List.of("alice"), List.of());

        sync.syncGroups(REPO, false);

        verify(contentService).deleteGroup(REPO, PREFIX + "o");
        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whom LDAP names in p, lost p when the orphan was deleted");
    }

    @Test
    @DisplayName("a member LDAP names in a group through a group it cannot nest is put back, and kept when the group is written, before the group below drops her (R156)")
    void aMemberNamedThroughAGroupItCannotNestKeepsTheGroupWhenTheGroupBelowDropsHer() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        // LDAP: a nests g and h; g names dave alone now; h names alice and a member the sync cannot name, so h is not
        // written and a does not nest it. NemakiWare's alice reached a through g.
        group(A_DN, "a", G_DN, H_DN);
        group(G_DN, "g", DAVE);
        group(H_DN, "h", ALICE, CONTRACTOR);
        stored("a", List.of(), List.of(PREFIX + "g"));
        stored("g", List.of("alice", "dave"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "g").getUsers());
        assertEquals(List.of("alice"), persisted.get(PREFIX + "a").getUsers(),
                "alice, whom LDAP names in a through h, lost a when g was written without her");
    }

    @Test
    @DisplayName("the group below is not written when the member it would take a group above from cannot be put back there (R156)")
    void theGroupBelowIsNotWrittenWhenTheMemberCannotBePutBackAbove() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        // p nests m, m nests g: the group whose write would take p from alice is two levels down.
        group(P_DN, "p", ALICE, CONTRACTOR, M_DN);
        group(M_DN, "m", BOB, G_DN);
        group(G_DN, "g", DAVE);
        stored("p", List.of(), List.of(PREFIX + "m"));
        stored("m", List.of("bob"), List.of(PREFIX + "g"));
        stored("g", List.of("alice", "dave"), List.of());
        failWrites("p", false);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("alice", "dave"), persisted.get(PREFIX + "g").getUsers(),
                "g was written without alice, who could not be put back in p: she lost p");
        assertTrue(errors(result).contains(PREFIX + "g: group " + PREFIX + "g: writing LDAP's members would take"),
                "the group not written was not reported: " + errors(result));
    }

    @Test
    @DisplayName("a nesting is kept for a member a group above may name, though the group below would drop her — its write can fail (batch 14 design review)")
    void aNestingIsKeptThoughTheGroupBelowWouldDropTheMember() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        // u came back in ranges: whom it names is not known. LDAP took g out of m and no longer names alice in g; g's
        // write fails before storing, so alice stays in g — and reaches u through m only if g stays in m.
        ranged(U_DN, "u");
        group(M_DN, "m", BOB);
        group(G_DN, "g", DAVE);
        stored("u", List.of(), List.of(PREFIX + "m"));
        stored("m", List.of("bob"), List.of(PREFIX + "g"));
        stored("g", List.of("alice"), List.of());
        failWrites("g", false);

        sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), persisted.get(PREFIX + "g").getUsers());
        assertEquals(List.of(PREFIX + "g"), persisted.get(PREFIX + "m").getGroups(),
                "g was taken out of m on the word of g's write, which failed: alice, whom u may name, lost u");
    }

    @Test
    @DisplayName("a nesting is kept for a member a group above may name who loses it through that nesting and through a write below together (batch 14)")
    void aNestingIsKeptForAMemberWhoLosesTheGroupThroughItAndAWriteBelow() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        // u came back in ranges: whom it names is not known. alice reaches u through m twice — through g1, which LDAP
        // took out of m, and through g2, which LDAP keeps in m but no longer names her in. Either alone leaves her u.
        ranged(U_DN, "u");
        group(M_DN, "m", BOB, G2_DN);
        group(G1_DN, "g1", ALICE);
        group(G2_DN, "g2", DAVE);
        stored("u", List.of(), List.of(PREFIX + "m"));
        stored("m", List.of("bob"), List.of(PREFIX + "g1", PREFIX + "g2"));
        stored("g1", List.of("alice"), List.of());
        stored("g2", List.of("alice", "dave"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "g2").getUsers());
        assertTrue(persisted.get(PREFIX + "m").getGroups().contains(PREFIX + "g1"),
                "g1 was taken out of m because alice still reached u through g2 — which was then written without her");
    }

    @Test
    @DisplayName("a member the group below keeps — LDAP naming her there through a group it cannot nest — is not put back in the group above (batch 14)")
    void aMemberTheGroupBelowKeepsIsNotPutBackAbove() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        // LDAP: p nests g and has a value outside the searches, so p is not written; g names dave and nests h; h names
        // alice and a member the sync cannot name, so g cannot nest h — and keeps alice, whom it has from before.
        group(P_DN, "p", G_DN, CONTRACTOR);
        group(G_DN, "g", DAVE, H_DN);
        group(H_DN, "h", ALICE, CONTRACTOR);
        stored("p", List.of(), List.of(PREFIX + "g"));
        stored("g", List.of("alice", "dave"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(persisted.get(PREFIX + "g").getUsers().contains("alice"), "g dropped alice");
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "alice was put back in p, though g keeps her and she reaches p through it");
        assertTrue(!warningsOf(result, "g").contains("put back") && !warningsOf(result, "p").contains("put back"),
                "alice was counted lost and put back, though g keeps her: " + warnings(result));
    }

    @Test
    @DisplayName("a member of a group the sync does not write is not put back in the group above — the group keeps her (batch 14)")
    void aMemberOfAGroupNotWrittenIsNotPutBackAbove() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        // LDAP: p names alice and nests g; g names dave; both have a value outside the searches, so neither is written —
        // g keeps alice, whom it has from before.
        group(P_DN, "p", ALICE, G_DN, CONTRACTOR);
        group(G_DN, "g", DAVE, "uid=y,ou=contractors,dc=example,dc=com");
        stored("p", List.of(), List.of(PREFIX + "g"));
        stored("g", List.of("alice", "dave"), List.of());

        sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "alice was put back in p, though g is not written and she reaches p through it");
    }

    @Test
    @DisplayName("a member put back in a group below counts for the groups above — they reach her still, and are not written (batch 14)")
    void aMemberPutBackBelowCountsForTheGroupsAbove() {
        user(ALICE, "alice");
        // LDAP: a nests m and has a value outside the searches, so a is not written; m names alice and no longer nests
        // team, through which alice reached m — and a. She is put back in m, and so reaches a still.
        group(A_DN, "a", M_DN, CONTRACTOR);
        group(M_DN, "m", ALICE);
        local("team", List.of("alice"), List.of());
        stored("a", List.of(), List.of(PREFIX + "m"));
        stored("m", List.of(), List.of("team"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of(), persisted.get(PREFIX + "m").getGroups());
        assertEquals(List.of("alice"), persisted.get(PREFIX + "m").getUsers());
        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "a").equals(w.getGroupId())),
                "alice was put back in a, though she reaches it through m, where she was put back");
    }

    @Test
    @DisplayName("a nesting kept below counts as kept for the groups above — none of them reports it again (batch 14)")
    void aNestingKeptBelowCountsForTheGroupsAbove() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        // u came back in ranges; v nests u. LDAP took team, where alice is, out of m, below u: u may name her, so the
        // nesting is kept — and v, which reaches her through u, still does.
        ranged(U_DN, "u");
        group(V_DN, "v", BOB, U_DN);
        group(M_DN, "m", DAVE);
        local("team", List.of("alice"), List.of());
        stored("v", List.of("bob"), List.of(PREFIX + "u"));
        stored("u", List.of(), List.of(PREFIX + "m"));
        stored("m", List.of("dave"), List.of("team"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertEquals(List.of("team"), persisted.get(PREFIX + "m").getGroups());
        assertTrue(errors(result).contains(PREFIX + "u: group " + PREFIX + "u: whom LDAP names"),
                "the nesting kept was not reported: " + errors(result));
        assertTrue(!errors(result).contains(PREFIX + "v: group " + PREFIX + "v: whom LDAP names"),
                "v reported a loss the nesting kept below does not make: " + errors(result));
    }

    @Test
    @DisplayName("an orphan kept below counts as kept for the groups above — none of them reports it again (batch 14)")
    void anOrphanKeptBelowCountsForTheGroupsAbove() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        user(ALICE, "alice");
        user(BOB, "bob");
        // u came back in ranges and nests the orphan o, where alice is; v nests u. u may name her, so o is kept — and v,
        // which reaches her through u, still does.
        ranged(U_DN, "u");
        group(V_DN, "v", BOB, U_DN);
        stored("v", List.of("bob"), List.of(PREFIX + "u"));
        stored("u", List.of(), List.of(PREFIX + "o"));
        stored("o", List.of("alice"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService, never()).deleteGroup(REPO, PREFIX + "o");
        assertTrue(!errors(result).contains(PREFIX + "v: group " + PREFIX + "v: whom LDAP names"),
                "v reported a loss the orphan kept below does not make: " + errors(result));
    }

    @Test
    @DisplayName("only the nesting that takes from a group above a member LDAP may name there is kept; LDAP's other removals are made, and the members it names there put back (batch 14)")
    void onlyTheNestingThatCarriesAMemberLdapMayNameIsKept() {
        user(ALICE, "alice");
        user(BOB, "bob");
        twoSatos();
        // LDAP: u names alice and the alumnus sato — a value two user entries have, which could be the sato who logs in —
        // and nests m; m names bob and no longer nests team1 or team2. sato reaches u through team1, alice through team2.
        group(U_DN, "u", ALICE, SATO_ALUMNI, M_DN);
        group(M_DN, "m", BOB);
        local("team1", List.of("sato"), List.of());
        local("team2", List.of("alice"), List.of());
        stored("u", List.of(), List.of(PREFIX + "m"));
        stored("m", List.of("bob"), List.of("team1", "team2"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of("team1"), persisted.get(PREFIX + "m").getGroups(),
                "the nesting of team2, through which only alice — put back in u — reached u, was kept too");
        assertTrue(persisted.get(PREFIX + "u").getUsers().contains("alice"), "alice was not put back in u");
    }

    @Test
    @DisplayName("a member put back in a group is kept when it is written, though she reaches it now through an orphan this sync deletes (R158 (a))")
    void aMemberPutBackStaysThoughAnOrphanBelowStillHoldsHer() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        user(ALICE, "alice");
        user(BOB, "bob");
        twoSatos();
        // LDAP: a nests h and j; h names bob; j names alice and the alumnus sato, so j is not written and a does not nest
        // it. NemakiWare's h nests the orphan o, where alice is; h cannot be written, so the nesting stays until o goes.
        group(A_DN, "a", H_DN, J_DN);
        group(H_DN, "h", BOB);
        group(J_DN, "j", ALICE, SATO_ALUMNI);
        stored("a", List.of(), List.of(PREFIX + "h"));
        stored("h", List.of("bob"), List.of(PREFIX + "o"));
        stored("o", List.of("alice"), List.of());
        failWrites("h", false);

        sync.syncGroups(REPO, false);

        verify(contentService).deleteGroup(REPO, PREFIX + "o");
        assertTrue(persisted.get(PREFIX + "a").getUsers().contains("alice"),
                "alice, whom LDAP names in a through j, was dropped from a as if o — deleted now — still took her there");
    }

    @Test
    @DisplayName("a member put back in a group is kept when it is written, though she reaches it now through a group the sync writes later (R158 (a))")
    void aMemberPutBackStaysThoughAGroupWrittenLaterStillHoldsHer() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        // LDAP: a nests h and j; h names bob and no longer nests k; k names dave alone now; j names alice and a member
        // the sync cannot name, so j is not written and a does not nest it. That member may name dave in a, so h keeps k
        // this sync (R157) — and k, written after a, drops alice.
        group(A_DN, "a", H_DN, J_DN);
        group(H_DN, "h", BOB);
        group(K_DN, "k", DAVE);
        group(J_DN, "j", ALICE, CONTRACTOR);
        stored("a", List.of(), List.of(PREFIX + "h"));
        stored("h", List.of("bob"), List.of(PREFIX + "k"));
        stored("k", List.of("alice", "dave"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "k"), persisted.get(PREFIX + "h").getGroups());
        assertEquals(List.of("dave"), persisted.get(PREFIX + "k").getUsers());
        assertTrue(persisted.get(PREFIX + "a").getUsers().contains("alice"),
                "alice, whom LDAP names in a through j, was dropped from a as if k — written after a — still took her there");
    }

    @Test
    @DisplayName("members LDAP names through a nesting cycle are counted the same whichever group of the cycle is counted first (R158 (d))")
    void membersNamedThroughACycleAreCountedWhicheverGroupComesFirst() {
        user(ALICE, "alice");
        user(BOB, "bob");
        // LDAP nests ca and cb in each other — a cycle, so neither is written, and d and e cannot nest them. Through the
        // cycle LDAP names alice and bob in both d and e; NemakiWare's d has alice, e has bob, from before.
        group(CA_DN, "ca", ALICE, CB_DN);
        group(CB_DN, "cb", BOB, CA_DN);
        group(D_DN, "d", CB_DN);
        group(E_DN, "e", CA_DN);
        stored("d", List.of("alice"), List.of());
        stored("e", List.of("bob"), List.of());

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(result.getStatus() != SyncStatus.FAILED, "the sync failed on the cycle: " + errors(result));
        assertEquals(List.of("alice"), persisted.get(PREFIX + "d").getUsers(), "alice, whom LDAP names in d through cb and ca, lost d");
        assertEquals(List.of("bob"), persisted.get(PREFIX + "e").getUsers(), "bob, whom LDAP names in e through ca and cb, lost e");
    }

    @Test
    @DisplayName("an id whose user was not created is not kept in a group, though LDAP names her there through a group it cannot nest (R158 (d))")
    void anIdWhoseUserWasNotCreatedIsNotKept() {
        carolIsNew();
        user(DAVE, "dave");
        // LDAP: p names dave and nests z; z names carol and a member the sync cannot name, so z is not written and p does
        // not nest it. NemakiWare's p lists the id carol from before; taking it out fails, so carol is not created.
        group(P_DN, "p", DAVE, Z_DN);
        group(Z_DN, "z", CAROL, CONTRACTOR);
        stored("p", List.of("dave", "carol"), List.of());
        failFirstWrites("p", 1);

        sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "p").getUsers(),
                "the id carol, whose user does not exist, was kept in p for whoever takes the id later");
    }

    @Test
    @DisplayName("a member is kept when the group is written, though the group it nests had her until this sync wrote it without her (R158 (d))")
    void aMemberIsKeptThoughTheGroupBelowHadHerUntilItWasWritten() {
        user(ALICE, "alice");
        user(DAVE, "dave");
        // LDAP: a nests z and j; z names dave alone now; j names alice and a member the sync cannot name, so j is not
        // written and a does not nest it. NemakiWare's a has alice directly, and z has her too, from before.
        group(A_DN, "a", Z_DN, J_DN);
        group(Z_DN, "z", DAVE);
        group(J_DN, "j", ALICE, CONTRACTOR);
        stored("a", List.of("alice"), List.of(PREFIX + "z"));
        stored("z", List.of("alice", "dave"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of("dave"), persisted.get(PREFIX + "z").getUsers());
        assertEquals(List.of("alice"), persisted.get(PREFIX + "a").getUsers(),
                "alice, whom LDAP names in a through j, was dropped from a as if z — written without her — still took her there");
    }

    @Test
    @DisplayName("a member is kept when the group is written, though the group it nests nested her group until this sync wrote it (R158 (d))")
    void aMemberIsKeptThoughTheGroupBelowNestedHerGroupUntilItWasWritten() {
        user(ALICE, "alice");
        user(BOB, "bob");
        // LDAP: a nests z and j; z names bob and no longer nests y, where alice is; j names alice and a member the sync
        // cannot name. Taking y out of z fails before the loop — z's first writes fail — and z's own write takes it out.
        group(A_DN, "a", Z_DN, J_DN);
        group(Z_DN, "z", BOB);
        group(J_DN, "j", ALICE, CONTRACTOR);
        local("y", List.of("alice"), List.of());
        stored("a", List.of("alice"), List.of(PREFIX + "z"));
        stored("z", List.of("bob"), List.of("y"));
        failFirstWrites("z", 2);

        sync.syncGroups(REPO, false);

        assertEquals(List.of(), persisted.get(PREFIX + "z").getGroups());
        assertEquals(List.of("alice"), persisted.get(PREFIX + "a").getUsers(),
                "alice, whom LDAP names in a through j, was dropped from a as if z — written without y — still took her there");
    }

    @Test
    @DisplayName("whom LDAP names in a group through the groups it nests is counted before the loop writes them (R158 (d))")
    void whomLdapNamesThroughNestedGroupsIsCountedBeforeTheyAreWritten() {
        user(ALICE, "alice");
        user(BOB, "bob");
        // LDAP: a nests z; z names bob and nests j; j names alice and a member the sync cannot name, so j is not written,
        // z does not nest it, and the loop writes z's entry without it. NemakiWare's a has alice directly.
        group(A_DN, "a", Z_DN);
        group(Z_DN, "z", BOB, J_DN);
        group(J_DN, "j", ALICE, CONTRACTOR);
        stored("a", List.of("alice"), List.of(PREFIX + "z"));
        stored("z", List.of("bob"), List.of());

        sync.syncGroups(REPO, false);

        assertEquals(List.of("alice"), persisted.get(PREFIX + "a").getUsers(),
                "alice, whom LDAP names in a through z and j, lost a when z's entry was written without j");
    }

    @Test
    @DisplayName("a nesting is kept when a group the group above nests has a member value the sync cannot name — it may name the member there (R158 (d))")
    void aNestingIsKeptWhenAGroupNestedAboveCouldNameTheMember() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        // LDAP: a nests m and j; m names bob and no longer nests team, where dave is; j names alice and a member the sync
        // cannot name — which may be a group naming dave. Only a's count meets both.
        group(A_DN, "a", M_DN, J_DN);
        group(M_DN, "m", BOB);
        group(J_DN, "j", ALICE, CONTRACTOR);
        local("team", List.of("dave"), List.of());
        stored("a", List.of(), List.of(PREFIX + "m"));
        stored("m", List.of("bob"), List.of("team"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of("team"), persisted.get(PREFIX + "m").getGroups(),
                "team was taken out of m, though j, nested in a, may name dave in a: he lost a");
    }

    @Test
    @DisplayName("a nesting is kept when a group above has a value that cannot be read as a DN among values that are — it could be any group (R158 (d))")
    void aNestingIsKeptWhenAValueCouldBeAnyGroup() {
        user(ALICE, "alice");
        user(BOB, "bob");
        user(DAVE, "dave");
        // LDAP: p names alice, nests m, and has a value with '=' that is no DN; m names dave and no longer nests team,
        // where bob is.
        group(P_DN, "p", ALICE, M_DN, "cn=x,=y,dc=example,dc=com");
        group(M_DN, "m", DAVE);
        local("team", List.of("bob"), List.of());
        stored("p", List.of(), List.of(PREFIX + "m"));
        stored("m", List.of("dave"), List.of("team"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of("team"), persisted.get(PREFIX + "m").getGroups(),
                "team was taken out of m, though the value p could not read may be a group naming bob: he lost p");
    }

    @Test
    @DisplayName("a nesting is kept for an id the store could not say whether a user has, where a value outside the searches may name her (R158 (d))")
    void aNestingIsKeptForAnIdTheStoreCouldNotLookUp() {
        user(DAVE, "dave");
        String outside = "uid=alice,ou=outside,dc=example,dc=com";
        group(P_DN, "p", DAVE, outside);
        group(X_DN, "x", outside);
        stored("p", List.of("dave"), List.of(PREFIX + "x"));
        stored("x", List.of("alice"), List.of());
        when(contentService.getUserItemById(REPO, "alice")).thenThrow(new IllegalStateException("store down"));

        sync.syncGroups(REPO, false);

        assertEquals(List.of(PREFIX + "x"), persisted.get(PREFIX + "p").getGroups(),
                "x was taken out of p, though whether alice is a NemakiWare user could not be read: she may have lost p");
    }

    @Test
    @DisplayName("an orphan's members LDAP names in a group above are put back before the orphan is deleted")
    void anOrphansMembersArePutBackBeforeItIsDeleted() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        user(ALICE, "alice");
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of(), List.of(PREFIX + "o"));
        stored("o", List.of("alice"), List.of());

        sync.syncGroups(REPO, false);

        assertTrue(persisted.get(PREFIX + "p").getUsers().contains("alice"),
                "alice, whom LDAP names in p, lost p when the orphan was deleted");
        verify(contentService).deleteGroup(REPO, PREFIX + "o");
    }

    @Test
    @DisplayName("an orphan is kept when the members it would take a group above from cannot be put back there")
    void anOrphanIsKeptWhenItsMembersCannotBePutBack() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        user(ALICE, "alice");
        group(P_DN, "p", ALICE, CONTRACTOR);
        stored("p", List.of(), List.of(PREFIX + "o"));
        stored("o", List.of("alice"), List.of());
        failWrites("p", false);

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        verify(contentService, never()).deleteGroup(REPO, PREFIX + "o");
        assertTrue(errors(result).contains(PREFIX + "o"), "the orphan kept was not reported: " + errors(result));
    }

    @Test
    @DisplayName("the preview puts the member back on paper only: it says so, and writes nothing")
    void thePreviewPutsBackOnPaperOnly() {
        aliceReachesPThroughXAndY(false);

        DirectorySyncResult preview = sync.previewSync(REPO);

        verify(contentService, never()).update(any(), any(), any());
        assertTrue(warningsOf(preview, "p").contains("put back"), "the preview did not say it: " + warnings(preview));
    }

    @Test
    @DisplayName("the preview sees the groups as the sync leaves them after taking out the ids of the users it creates")
    void thePreviewSeesTheGroupsAsTheSyncLeavesThem() {
        carolIsNew();
        user(ALICE, "alice");
        group(G_DN, "g", ALICE, CAROL);
        stored("g", List.of("alice", "carol"), List.of());
        ranged(P_DN, "p");
        stored("p", List.of("bob"), List.of(PREFIX + "g"));

        DirectorySyncResult preview = sync.previewSync(REPO);

        assertTrue(warningsOf(preview, "g").contains(PREFIX + "p"),
                "the preview kept carol in g as if her old id were still there; the sync holds her back: " + warnings(preview));
    }

    @Test
    @DisplayName("a user whose creation was attempted and cannot be read back is not put back — she may have been nobody until now")
    void aUserWhoseCreationCannotBeReadBackIsNotPutBack() {
        createdUsersGoIntoUsers();
        user(ALICE, "alice");
        user(CAROL, "carol");
        java.util.concurrent.atomic.AtomicBoolean tried = new java.util.concurrent.atomic.AtomicBoolean();
        when(contentService.getUserItemById(REPO, "carol")).thenAnswer(read -> {
            if (tried.get()) {
                throw new IllegalStateException("store down");
            }
            return null;
        });
        doAnswer(create -> {
            tried.set(true);
            throw new IllegalStateException("change log down");
        }).when(contentService).createUserItem(any(), eq(REPO), any(UserItem.class));
        // A local group lists carol from before; p nested it, which LDAP cannot state.
        local("team", List.of("carol"), List.of());
        group(P_DN, "p", ALICE, CAROL, CONTRACTOR);
        stored("p", List.of("alice"), List.of("team"));

        sync.syncGroups(REPO, false);

        assertTrue(!persisted.get(PREFIX + "p").getUsers().contains("carol"),
                "carol, whose creation was attempted, was put into p, which the sync does not write");
    }

    @Test
    @DisplayName("a directory user who is not a NemakiWare user is not put back — an id no user has is nobody's")
    void aUserWhoIsNotANemakiWareUserIsNotPutBack() {
        user(ALICE, "alice");
        user("uid=erin,ou=users,dc=example,dc=com", "erin");
        when(contentService.getUserItemById(REPO, "erin")).thenReturn(null);
        local("team", List.of("erin"), List.of());
        group(P_DN, "p", ALICE, "uid=erin,ou=users,dc=example,dc=com", CONTRACTOR);
        stored("p", List.of("alice"), List.of("team"));

        sync.syncGroups(REPO, false);

        assertTrue(!persisted.get(PREFIX + "p").getUsers().contains("erin"),
                "erin, no NemakiWare user, was put into p: whoever takes the id later gets p");
    }

    @Test
    @DisplayName("an orphan to be deleted loses the old id of a user created now first — it stays if its deletion fails")
    void anOrphanToBeDeletedLosesTheOldIdFirst() {
        when(properties.readBoolean(PropertyKey.DIRECTORY_SYNC_DELETE_ORPHAN_GROUPS)).thenReturn(true);
        carolIsNew();
        stored("o", List.of("carol"), List.of());
        doAnswer(delete -> {
            throw new IllegalStateException("store down");
        }).when(contentService).deleteGroup(REPO, PREFIX + "o");

        sync.syncGroups(REPO, false);

        assertEquals(List.of(), persisted.get(PREFIX + "o").getUsers(),
                "carol, created now, is in the orphan that could not be deleted");
    }

    @Test
    @DisplayName("a value that looks like a DN but cannot be parsed, among DN values, could name any group — the nesting is kept")
    void aValueThatCannotBeParsedKeepsTheNesting() {
        gGainsDave();
        group(P_DN, "p", ALICE, "cn=x,=y,dc=example,dc=com");
        stored("p", List.of("alice"), List.of(PREFIX + "g"));

        DirectorySyncResult result = sync.syncGroups(REPO, false);

        assertTrue(writes().stream().noneMatch(w -> (PREFIX + "p").equals(w.getGroupId())),
                "a nesting the unparsed value may still state was removed");
        assertTrue(warningsOf(result, "p").contains("could not be checked"), "the unchecked nesting was not reported: "
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
