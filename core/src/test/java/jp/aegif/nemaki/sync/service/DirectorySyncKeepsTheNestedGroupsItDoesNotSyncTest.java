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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.sync.model.DirectorySyncResult;
import jp.aegif.nemaki.sync.model.LdapGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The LDAP directory sync replaces a group's nested groups only when it syncs them (3.4.1).
 *
 * <p>Updating an existing group wrote the LDAP side's nested groups — an empty list when nested
 * groups are not synced — so every nested group an administrator had added was removed, and every
 * permission granted through it. Until 3.4.1 the update never reached the store: the groups it
 * was handed came from the group list, which carried no revision, and the DAO refuses an update
 * without one. 3.4.1's list read carries the revision, so the first sync after an upgrade would
 * have removed them all.
 *
 * <p>Driven through {@code performGroupSync} — the step that reads the existing groups, decides
 * which changed and writes them — with the LDAP side handed in, because reaching it through
 * {@code sync} needs a directory.
 */
class DirectorySyncKeepsTheNestedGroupsItDoesNotSyncTest {

    private static final String REPO = "bedroom";
    private static final String PREFIX = DirectorySyncConfig.DEFAULT_GROUP_PREFIX;

    private ContentService contentService;
    private DirectorySyncServiceImpl sync;

    /** What the store holds after each write, read back past the cache. */
    private final Map<String, GroupItem> persisted = new HashMap<>();

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        sync = new DirectorySyncServiceImpl();
        sync.setContentService(contentService);
        doAnswer(write -> {
            Object content = write.getArgument(2);
            if (content instanceof GroupItem) {
                GroupItem group = (GroupItem) content;
                persisted.put(group.getGroupId(), new GroupItem(group.getId(), group.getObjectType(),
                        group.getGroupId(), group.getName(), new ArrayList<>(group.getUsers()),
                        new ArrayList<>(group.getGroups())));
            }
            return content;
        }).when(contentService).update(any(), eq(REPO), any());
        when(contentService.getGroupItemByIdFresh(eq(REPO), any())).thenAnswer(read -> persisted.get(read.getArgument(1)));
    }

    /**
     * A synced group as the list returns it: with its revision, and a nested group added by hand.
     * The synced group {@code qa} exists too, so that LDAP's nesting names a group that exists.
     */
    private GroupItem existing(List<String> users) {
        GroupItem group = new GroupItem("doc-eng", "nemaki:group", PREFIX + "eng", "Engineering",
                new ArrayList<>(users), new ArrayList<>(List.of("ops-by-hand")));
        group.setRevision("3-abc");
        GroupItem qa = new GroupItem("doc-qa", "nemaki:group", PREFIX + "qa", "QA",
                new ArrayList<>(), new ArrayList<>());
        qa.setRevision("1-abc");
        when(contentService.getGroupItems(REPO)).thenReturn(new ArrayList<>(List.of(group, qa)));
        return group;
    }

    /** A group as the member resolver leaves it: its members already looked up (R148). */
    private static LdapGroup ldap(List<String> users, List<String> groups) {
        return ldap("eng", "Engineering", users, groups);
    }

    private static LdapGroup ldap(String id, String name, List<String> users, List<String> groups) {
        LdapGroup group = new LdapGroup("cn=" + id + ",ou=groups,dc=example", id, name);
        group.setMemberAttributePresent(true);
        group.setMemberUserIds(new ArrayList<>(users));
        group.setMemberGroupIds(new ArrayList<>(groups));
        return group;
    }

    private DirectorySyncResult performGroupSync(LdapGroup ldapGroup, boolean syncNestedGroups,
            LdapGroup... alsoInLdap) throws Exception {
        DirectorySyncConfig config = new DirectorySyncConfig();
        config.setSyncNestedGroups(syncNestedGroups);
        Method step = DirectorySyncServiceImpl.class.getDeclaredMethod("performGroupSync", String.class,
                List.class, DirectorySyncConfig.class, DirectorySyncResult.class, boolean.class, Map.class);
        step.setAccessible(true);
        DirectorySyncResult result = new DirectorySyncResult(REPO, false);
        // Every member user is a NemakiWare user: this measures the nesting.
        Map<String, DirectorySyncServiceImpl.UserState> users = new HashMap<>();
        for (String userId : ldapGroup.getMemberUserIds()) {
            users.put(userId, DirectorySyncServiceImpl.UserState.EXISTS);
        }
        List<LdapGroup> groups = new ArrayList<>(List.of(ldapGroup));
        groups.addAll(List.of(alsoInLdap));
        step.invoke(sync, REPO, groups, config, result, false, users);
        return result;
    }

    /** The last write: what the group holds when the step is done. */
    private GroupItem written() {
        ArgumentCaptor<GroupItem> group = ArgumentCaptor.forClass(GroupItem.class);
        verify(contentService, atLeastOnce()).update(any(), eq(REPO), group.capture());
        List<GroupItem> writes = group.getAllValues();
        return writes.get(writes.size() - 1);
    }

    @Test
    @DisplayName("not syncing nested groups, a changed group keeps the nested groups added by hand")
    void anUnsyncedNestingIsKept() throws Exception {
        existing(List.of("alice"));
        performGroupSync(ldap(List.of("alice", "bob"), List.of("qa")), false);

        GroupItem group = written();
        assertEquals(List.of("alice", "bob"), group.getUsers(), "the members LDAP says");
        assertEquals(List.of("ops-by-hand"), group.getGroups(),
                "the sync removed nested groups it does not sync — and the permissions granted through them");
    }

    @Test
    @DisplayName("syncing nested groups, a changed group takes LDAP's nested groups")
    void aSyncedNestingIsReplaced() throws Exception {
        existing(List.of("alice"));
        // qa is one of this sync's LDAP groups too, unchanged. Handed only eng, the nesting named a
        // group only NemakiWare had, which reaches the parent through the groups NemakiWare has alone —
        // the half of the nesting decision that R149 (a) records as a defect (review round 3).
        performGroupSync(ldap(List.of("alice", "bob"), List.of("qa")), true,
                ldap("qa", "QA", List.of(), List.of()));

        GroupItem group = written();
        assertEquals(List.of("alice", "bob"), group.getUsers());
        assertEquals(List.of(PREFIX + "qa"), group.getGroups(), "LDAP's nested groups, prefixed");
    }

    @Test
    @DisplayName("not syncing nested groups, a group whose only difference is its nesting is not written")
    void aNestingDifferenceAloneIsNotAChange() throws Exception {
        existing(List.of("alice"));
        DirectorySyncResult result = performGroupSync(ldap(List.of("alice"), List.of("qa")), false);

        verify(contentService, never()).update(any(), any(), any());
        // Not written because it was judged unchanged — the per-group catch also writes nothing,
        // and answers with an error instead.
        assertEquals(1, result.getGroupsSkipped(), "the group was not judged unchanged");
        assertEquals(List.of(), result.getErrors(), "the group failed instead of being unchanged");
    }
}
