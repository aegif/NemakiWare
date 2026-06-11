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
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - shared group membership edit logic tests
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.GroupMembershipEditor.EditResult;
import jp.aegif.nemaki.businesslogic.GroupMembershipEditor.Outcome;

public class GroupMembershipEditorTest {

    @Test
    public void addsNewExistingMember() {
        EditResult r = GroupMembershipEditor.edit(
                new ArrayList<>(List.of("alice")), List.of("bob"), true, Set.of("alice", "bob"), null);
        assertEquals(List.of("alice", "bob"), r.getList());
        assertEquals(Outcome.ADDED, r.getResults().get(0).getOutcome());
    }

    @Test
    public void addRejectsNonExistentMember() {
        EditResult r = GroupMembershipEditor.edit(
                new ArrayList<>(List.of("alice")), List.of("ghost"), true, Set.of("alice", "bob"), null);
        assertEquals(List.of("alice"), r.getList(), "non-existent id must not be added");
        assertEquals(Outcome.NOT_FOUND, r.getResults().get(0).getOutcome());
    }

    @Test
    public void addDetectsAlreadyMember() {
        EditResult r = GroupMembershipEditor.edit(
                new ArrayList<>(List.of("alice")), List.of("alice"), true, Set.of("alice"), null);
        assertEquals(List.of("alice"), r.getList());
        assertEquals(Outcome.ALREADY_MEMBER, r.getResults().get(0).getOutcome());
    }

    @Test
    public void addPreventsGroupAddingItself() {
        // selfId set → adding the group to itself is rejected (unified correct behavior).
        EditResult r = GroupMembershipEditor.edit(
                new ArrayList<>(), List.of("g1"), true, Set.of("g1"), "g1");
        assertTrue(r.getList().isEmpty(), "a group must not be added to itself");
        assertEquals(Outcome.GROUP_ITSELF, r.getResults().get(0).getOutcome());
    }

    @Test
    public void removeExistingMember() {
        EditResult r = GroupMembershipEditor.edit(
                new ArrayList<>(List.of("alice", "bob")), List.of("bob"), false, null, null);
        assertEquals(List.of("alice"), r.getList());
        assertEquals(Outcome.REMOVED, r.getResults().get(0).getOutcome());
    }

    @Test
    public void removeDetectsNotMember() {
        EditResult r = GroupMembershipEditor.edit(
                new ArrayList<>(List.of("alice")), List.of("bob"), false, null, null);
        assertEquals(List.of("alice"), r.getList());
        assertEquals(Outcome.NOT_MEMBER, r.getResults().get(0).getOutcome());
    }

    @Test
    public void nullCurrentAndTargetsAreSafe() {
        EditResult r = GroupMembershipEditor.edit(null, null, true, null, null);
        assertTrue(r.getList().isEmpty());
        assertTrue(r.getResults().isEmpty());
    }

    @Test
    public void addWithoutValidIdsSkipsExistenceCheck() {
        // validIds=null → no existence check (caller opts out).
        EditResult r = GroupMembershipEditor.edit(
                new ArrayList<>(), List.of("anything"), true, null, null);
        assertEquals(List.of("anything"), r.getList());
        assertEquals(Outcome.ADDED, r.getResults().get(0).getOutcome());
    }

    @Test
    public void doesNotMutateInputList() {
        List<String> input = new ArrayList<>(List.of("alice"));
        GroupMembershipEditor.edit(input, List.of("bob"), true, null, null);
        assertEquals(List.of("alice"), input, "input list must be left unchanged");
    }
}
