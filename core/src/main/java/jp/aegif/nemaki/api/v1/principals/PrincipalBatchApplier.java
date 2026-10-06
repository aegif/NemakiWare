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
package jp.aegif.nemaki.api.v1.principals;

import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Kind;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Operation;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Outcome;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Row;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.RowVerdict;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Verdict;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.GroupMembershipEditor;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Applies the rows a preview called {@code expected}, in file order, through the canonical
 * write methods and nothing else (design §7): {@code buildAndCreateUser} / {@code applyUserUpdate}
 * / {@code deleteUser} / {@code buildAndCreateGroup} / {@code applyGroupUpdate} / {@code deleteGroup},
 * with memberships edited by {@link GroupMembershipEditor} and written by {@code applyGroupUpdate}.
 *
 * <p>The first failure stops the job (design §6.3): the failed row is {@code FAILED}, the rows
 * after it {@code NOT_APPLIED}, and nothing is rolled back — the operator re-previews the same
 * file and the applied rows show up as {@code unexpected}. The exception's text goes to the log
 * under an incident id; the response carries the id, not the text (the #1410 rule).
 */
final class PrincipalBatchApplier {

    private static final Logger logger = LoggerFactory.getLogger(PrincipalBatchApplier.class);

    /** One row's fate. */
    record RowOutcome(int line, String id, Outcome outcome, String reason) {
        Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("line", line);
            out.put("id", id);
            out.put("outcome", outcome.name().toLowerCase(Locale.ROOT));
            if (reason != null) {
                out.put("reason", reason);
            }
            return out;
        }
    }

    /** What happened: every row's outcome, where it stopped, and the incident id if it failed. */
    record Applied(List<RowOutcome> outcomes, Integer stoppedAtLine, String incidentId) {
        long count(Outcome outcome) {
            return outcomes.stream().filter(o -> o.outcome() == outcome).count();
        }

        boolean stopped() {
            return stoppedAtLine != null;
        }
    }

    private final ContentService contentService;
    private final String repositoryId;
    private final String actor;

    PrincipalBatchApplier(ContentService contentService, String repositoryId, String actor) {
        this.contentService = contentService;
        this.repositoryId = repositoryId;
        this.actor = actor;
    }

    Applied apply(Kind kind, Operation operation, List<Row> rows, List<RowVerdict> verdicts) {
        if (rows.size() != verdicts.size()) {
            throw new IllegalArgumentException("every row needs exactly one verdict");
        }
        List<RowOutcome> outcomes = new ArrayList<>();
        Integer stoppedAt = null;
        String incidentId = null;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            RowVerdict verdict = verdicts.get(i);
            if (stoppedAt != null) {
                outcomes.add(new RowOutcome(row.line(), row.id(), Outcome.NOT_APPLIED, null));
                continue;
            }
            if (verdict.verdict() == Verdict.FORBIDDEN) {
                outcomes.add(new RowOutcome(row.line(), row.id(), Outcome.FORBIDDEN, verdict.reason().name()));
                continue;
            }
            if (verdict.verdict() == Verdict.UNEXPECTED) {
                outcomes.add(new RowOutcome(row.line(), row.id(), Outcome.SKIPPED, verdict.reason().name()));
                continue;
            }
            try {
                String notApplied = applyOne(kind, operation, row);
                if (notApplied != null) {
                    outcomes.add(new RowOutcome(row.line(), row.id(), Outcome.FAILED, notApplied));
                    stoppedAt = row.line();
                } else {
                    outcomes.add(new RowOutcome(row.line(), row.id(), Outcome.APPLIED, null));
                }
            } catch (RuntimeException e) {
                // The text is whatever the failing layer wrote — a store URL, a class name. It
                // goes to the log under an incident id; the client gets the id (#1410 rule).
                // The row's CELLS are not logged either: one of them may be a password — and a
                // lower layer that echoes its input would put that cell into the message, so the
                // password's value is redacted from the text, and the throwable logged carries
                // the frames of the failure without its message or its cause chain (9-6 review
                // of area C, P1: nothing measured the log, and the claim rested on the layers
                // below never echoing a cell).
                incidentId = UUID.randomUUID().toString();
                String why = withoutThePassword(e.getMessage(), row);
                RuntimeException frames = new RuntimeException(e.getClass().getName() + ": " + why);
                frames.setStackTrace(e.getStackTrace());
                logger.warn("Principal batch {} {} stopped at line {} (id {}) [incident {}]: {}",
                        kind, operation, row.line(), row.id(), incidentId, why, frames);
                outcomes.add(new RowOutcome(row.line(), row.id(), Outcome.FAILED, "INCIDENT_" + incidentId));
                stoppedAt = row.line();
            }
        }
        return new Applied(List.copyOf(outcomes), stoppedAt, incidentId);
    }

    /** {@code text} with every occurrence of the row's password cell replaced, when it has one. */
    static String withoutThePassword(String text, Row row) {
        String password = row.cell("password");
        if (text == null || password == null || password.isBlank()) {
            return text;
        }
        return text.replace(password, "[password redacted]");
    }

    /** Applies one expected row. Returns null when applied, or a registry reason when the
     *  canonical method reported "there was nothing to do" (the target vanished since preview). */
    private String applyOne(Kind kind, Operation operation, Row row) {
        return switch (kind) {
            case USERS -> userRow(operation, row);
            case GROUPS -> groupRow(operation, row);
            case MEMBERSHIPS -> membershipRow(operation, row);
        };
    }

    // ---- users ----

    private String userRow(Operation operation, Row row) {
        String userId = row.id();
        switch (operation) {
            case CREATE -> {
                String name = row.cell("name") == null ? userId : row.cell("name");
                contentService.buildAndCreateUser(repositoryId, userId, name, row.cell("password"),
                        row.cell("firstName"), row.cell("lastName"), row.cell("email"), actor);
                if (Boolean.TRUE.equals(row.flag("admin"))) {
                    // buildAndCreateUser writes admin=false and returns the local object; the
                    // flag is a second write on the stored one.
                    UserItem created = contentService.getUserItemById(repositoryId, userId);
                    if (created == null) {
                        return "NOT_FOUND";
                    }
                    created.setAdmin(Boolean.TRUE);
                    contentService.applyUserUpdate(repositoryId, created, actor);
                }
                String groups = row.cell("groups");
                if (groups != null) {
                    setMemberships(userId, PrincipalBatchPlanner.split(groups));
                }
                return null;
            }
            case UPDATE -> {
                UserItem user = contentService.getUserItemById(repositoryId, userId);
                if (user == null) {
                    return "NOT_FOUND";
                }
                if (row.cell("name") != null) {
                    user.setName(row.cell("name"));
                }
                // The same copy-all-then-overlay the product's own update does: a stated cell
                // replaces its property, an unstated one leaves it as it is.
                Map<String, Object> props = new LinkedHashMap<>();
                if (user.getSubTypeProperties() != null) {
                    for (Property p : user.getSubTypeProperties()) {
                        props.put(p.getKey(), p.getValue());
                    }
                }
                overlay(props, "nemaki:firstName", row.cell("firstName"));
                overlay(props, "nemaki:lastName", row.cell("lastName"));
                overlay(props, "nemaki:email", row.cell("email"));
                List<Property> properties = new ArrayList<>();
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    properties.add(new Property(e.getKey(), e.getValue()));
                }
                user.setSubTypeProperties(properties);
                Boolean admin = row.flag("admin");
                if (admin != null) {
                    user.setAdmin(admin);
                }
                if (row.cell("password") != null) {
                    user.setPassowrd(contentService.hashPassword(row.cell("password")));
                }
                contentService.applyUserUpdate(repositoryId, user, actor);
                String groups = row.cell("groups");
                if (groups != null) {
                    setMemberships(userId, PrincipalBatchPlanner.split(groups));
                }
                return null;
            }
            case DELETE -> {
                return contentService.deleteUser(repositoryId, userId) ? null : "NOT_FOUND";
            }
            default -> throw new IllegalStateException(operation + " for users");
        }
    }

    private static void overlay(Map<String, Object> props, String key, String value) {
        if (value != null) {
            props.put(key, value);
        }
    }

    /** The user is a member of exactly {@code groupIds} afterwards — the product's own replace
     *  semantics for a stated groups list, written through applyGroupUpdate. */
    private void setMemberships(String userId, List<String> groupIds) {
        List<GroupItem> all = contentService.getGroupItems(repositoryId);
        if (all == null) {
            all = List.of();
        }
        for (GroupItem stored : all) {
            boolean shouldBe = groupIds.contains(stored.getGroupId());
            boolean is = stored.getUsers().contains(userId);
            if (shouldBe == is) {
                continue;
            }
            GroupItem group = contentService.getGroupItemByIdFresh(repositoryId, stored.getGroupId());
            if (group == null) {
                continue;
            }
            GroupMembershipEditor.EditResult edit = GroupMembershipEditor.edit(group.getUsers(),
                    List.of(userId), shouldBe, null, null);
            group.setUsers(edit.getList());
            contentService.applyGroupUpdate(repositoryId, group, actor);
        }
    }

    // ---- groups ----

    private String groupRow(Operation operation, Row row) {
        String groupId = row.id();
        switch (operation) {
            case CREATE -> {
                List<String> users = row.cell("users") == null ? List.of()
                        : PrincipalBatchPlanner.split(row.cell("users"));
                List<String> groups = row.cell("groups") == null ? List.of()
                        : PrincipalBatchPlanner.split(row.cell("groups"));
                contentService.buildAndCreateGroup(repositoryId, groupId, row.cell("name"), users, groups, actor);
                return null;
            }
            case UPDATE -> {
                GroupItem group = contentService.getGroupItemByIdFresh(repositoryId, groupId);
                if (group == null) {
                    return "NOT_FOUND";
                }
                if (row.cell("name") != null) {
                    group.setName(row.cell("name"));
                }
                if (row.cell("users") != null) {
                    group.setUsers(PrincipalBatchPlanner.split(row.cell("users")));
                }
                if (row.cell("groups") != null) {
                    group.setGroups(PrincipalBatchPlanner.split(row.cell("groups")));
                }
                contentService.applyGroupUpdate(repositoryId, group, actor);
                return null;
            }
            case DELETE -> {
                return contentService.deleteGroup(repositoryId, groupId) ? null : "NOT_FOUND";
            }
            default -> throw new IllegalStateException(operation + " for groups");
        }
    }

    // ---- memberships ----

    private String membershipRow(Operation operation, Row row) {
        String groupId = row.id();
        GroupItem group = contentService.getGroupItemByIdFresh(repositoryId, groupId);
        if (group == null) {
            return "GROUP_NOT_FOUND";
        }
        if (operation == Operation.REPLACE) {
            PrincipalBatchPlanner.Members members = PrincipalBatchPlanner.Members.parse(row, row.cell("members"));
            // An EMPTY members cell is the one place "empty" means "make it empty" (design §4).
            group.setUsers(new ArrayList<>(members.users()));
            group.setGroups(new ArrayList<>(members.groups()));
            contentService.applyGroupUpdate(repositoryId, group, actor);
            return null;
        }
        String memberId = row.cell("memberId");
        boolean isUser = "user".equalsIgnoreCase(row.cell("memberType"));
        boolean add = operation == Operation.ADD;
        if (isUser) {
            GroupMembershipEditor.EditResult edit = GroupMembershipEditor.edit(group.getUsers(),
                    List.of(memberId), add, null, null);
            group.setUsers(edit.getList());
        } else {
            GroupMembershipEditor.EditResult edit = GroupMembershipEditor.edit(group.getGroups(),
                    List.of(memberId), add, null, groupId);
            group.setGroups(edit.getList());
        }
        contentService.applyGroupUpdate(repositoryId, group, actor);
        return null;
    }
}
