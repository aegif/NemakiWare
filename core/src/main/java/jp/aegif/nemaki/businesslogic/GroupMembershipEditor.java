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
 *     aegif - shared group membership edit logic (#14 REST consolidation)
 ******************************************************************************/
package jp.aegif.nemaki.businesslogic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure, binding-agnostic logic for adding/removing members (users or groups) of
 * a group. Consolidates the member-merge that was independently re-implemented —
 * with divergent validation — in the legacy Jersey {@code GroupItemResource},
 * the Spring MVC {@code GroupController} and the api/v1 {@code GroupResource}.
 *
 * <p>Each REST layer maps the returned {@link Outcome}s to its own response
 * shape (legacy JSON {@code errMsg} array, ResponseEntity, ProblemDetail), but
 * the merge decisions (existence check on add, already/not-member detection,
 * self-add prevention for the group dimension) are now identical.
 *
 * <p>The input list is never mutated; a new list is returned.
 */
public final class GroupMembershipEditor {

    private GroupMembershipEditor() {
    }

    public enum Outcome {
        ADDED, REMOVED, ALREADY_MEMBER, NOT_MEMBER, NOT_FOUND, GROUP_ITSELF
    }

    /** Per-target outcome of an edit. */
    public static final class Result {
        private final String id;
        private final Outcome outcome;

        Result(String id, Outcome outcome) {
            this.id = id;
            this.outcome = outcome;
        }

        public String getId() {
            return id;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        /** True when this target actually changed the membership list. */
        public boolean isApplied() {
            return outcome == Outcome.ADDED || outcome == Outcome.REMOVED;
        }
    }

    /** Result of an edit: the new member list plus a per-target outcome list. */
    public static final class EditResult {
        private final List<String> list;
        private final List<Result> results;

        EditResult(List<String> list, List<Result> results) {
            this.list = list;
            this.results = results;
        }

        public List<String> getList() {
            return list;
        }

        public List<Result> getResults() {
            return results;
        }
    }

    /**
     * Add or remove {@code targets} from {@code current}.
     *
     * @param current  existing member ids (not mutated; may be null = empty)
     * @param targets  ids to add/remove (may be null = no-op)
     * @param add      true to add, false to remove
     * @param validIds existing-id set used for the add existence check; pass
     *                 {@code null} to skip the existence check (e.g. on remove)
     * @param selfId   id that must not be added to itself (the group's own id for
     *                 the group dimension); {@code null} disables the self check
     */
    public static EditResult edit(List<String> current, List<String> targets, boolean add,
            Set<String> validIds, String selfId) {
        List<String> list = new ArrayList<>(current == null ? List.of() : current);
        List<Result> results = new ArrayList<>();
        if (targets == null) {
            return new EditResult(list, results);
        }
        for (String id : targets) {
            if (id == null) {
                continue;
            }
            if (add) {
                if (validIds != null && !validIds.contains(id)) {
                    results.add(new Result(id, Outcome.NOT_FOUND));
                } else if (list.contains(id)) {
                    results.add(new Result(id, Outcome.ALREADY_MEMBER));
                } else if (selfId != null && id.equals(selfId)) {
                    results.add(new Result(id, Outcome.GROUP_ITSELF));
                } else {
                    list.add(id);
                    results.add(new Result(id, Outcome.ADDED));
                }
            } else {
                if (list.remove(id)) {
                    results.add(new Result(id, Outcome.REMOVED));
                } else {
                    results.add(new Result(id, Outcome.NOT_MEMBER));
                }
            }
        }
        return new EditResult(list, results);
    }
}
