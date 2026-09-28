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
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Reason;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Row;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.RowVerdict;
import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Verdict;
import jp.aegif.nemaki.model.GroupItem;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PasswordPolicyService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides, without writing, what applying each row would do (design §5).
 *
 * <p>Three answers per row: {@code expected} (the operation does what it says),
 * {@code unexpected} (the world is not as the row assumes — a reason from the registry), and
 * {@code forbidden} (the built-in accounts and the actor; never applied, not even with
 * {@code skip}). The reads go through {@link PrincipalWorld}, so a store that could not answer
 * stops the whole plan with a 503 instead of becoming a column of {@code NOT_FOUND}.
 */
final class PrincipalBatchPlanner {

    /** The ids the batch never deletes or demotes, and how directory-synced ids are recognised. */
    record Guards(String adminUserId, String solrUserId, String mcpServiceUserId, String actorUserId,
                  Set<String> groupPrefixes, String userPrefix) {

        Reason forbiddenReasonFor(String userId) {
            if (userId == null) {
                return null;
            }
            if (userId.equals(adminUserId)) {
                return Reason.BUILT_IN_ADMIN;
            }
            if (userId.equals(solrUserId) || userId.equals(mcpServiceUserId)) {
                return Reason.BUILT_IN_SERVICE_ACCOUNT;
            }
            if (actorUserId != null && userId.equals(actorUserId)) {
                return Reason.ACTOR_ITSELF;
            }
            return null;
        }

        /** A GUESS from the id (design §2.3): LDAP users carry no marker at all. */
        boolean looksDirectorySynced(Kind kind, String id) {
            if (id == null) {
                return false;
            }
            if (kind == Kind.GROUPS || kind == Kind.MEMBERSHIPS) {
                for (String prefix : groupPrefixes) {
                    if (prefix != null && !prefix.isEmpty() && id.startsWith(prefix)) {
                        return true;
                    }
                }
                return false;
            }
            return userPrefix != null && !userPrefix.isEmpty() && id.startsWith(userPrefix);
        }
    }

    /** What the planner found: a verdict per row, and the state it was decided against. */
    record Planned(List<RowVerdict> verdicts, String snapshotHash, boolean passwordPresent,
                   int adminGrants) {
        long count(Verdict verdict) {
            return verdicts.stream().filter(v -> v.verdict() == verdict).count();
        }
    }

    private final PrincipalWorld world;
    private final PasswordPolicyService passwordPolicy;
    private final Guards guards;
    private final String repositoryId;
    private final TreeSet<String> snapshot = new TreeSet<>();

    PrincipalBatchPlanner(PrincipalWorld world, PasswordPolicyService passwordPolicy, Guards guards,
            String repositoryId) {
        this.world = world;
        this.passwordPolicy = passwordPolicy;
        this.guards = guards;
        this.repositoryId = repositoryId;
    }

    Planned plan(Kind kind, Operation operation, List<Row> rows) {
        if (!operation.appliesTo(kind)) {
            throw new PrincipalBatchRequestException(400, "operation " + operation.name().toLowerCase(Locale.ROOT)
                    + " does not apply to " + kind.name().toLowerCase(Locale.ROOT));
        }
        List<RowVerdict> verdicts = new ArrayList<>();
        int adminGrants = 0;
        for (Row row : rows) {
            RowVerdict verdict = switch (kind) {
                case USERS -> userRow(operation, row);
                case GROUPS -> groupRow(operation, row);
                case MEMBERSHIPS -> membershipRow(operation, row);
            };
            verdicts.add(verdict);
            if (kind == Kind.USERS && Boolean.TRUE.equals(row.flag("admin"))
                    && verdict.verdict() == Verdict.EXPECTED) {
                adminGrants++;
            }
        }
        return new Planned(List.copyOf(verdicts), snapshotHash(), PrincipalBatchPlan.anySecretStated(rows),
                adminGrants);
    }

    // ---- users ----

    private RowVerdict userRow(Operation operation, Row row) {
        String userId = row.id();
        UserItem existing = world.user(userId);
        remember("user", userId, existing == null ? null : existing.getRevision());
        switch (operation) {
            case CREATE -> {
                if (existing != null) {
                    return unexpected(row, Reason.ALREADY_EXISTS, "a user with this id exists");
                }
                if (world.groupExists(userId)) {
                    return unexpected(row, Reason.ALREADY_EXISTS, "a group with this id exists");
                }
                String password = row.cell("password");
                if (password == null) {
                    return unexpected(row, Reason.PASSWORD_POLICY, "a new user needs a password");
                }
                RowVerdict policy = passwordVerdict(row, password);
                if (policy != null) {
                    return policy;
                }
                return groupsColumnVerdict(row);
            }
            case UPDATE -> {
                if (existing == null) {
                    return unexpected(row, Reason.NOT_FOUND, "no user with this id");
                }
                Boolean admin = row.flag("admin");
                if (Boolean.FALSE.equals(admin)) {
                    Reason forbidden = guards.forbiddenReasonFor(userId);
                    if (forbidden != null) {
                        return forbidden(row, forbidden, "this account keeps its admin flag");
                    }
                }
                String password = row.cell("password");
                if (password != null) {
                    RowVerdict policy = passwordVerdict(row, password);
                    if (policy != null) {
                        return policy;
                    }
                }
                return groupsColumnVerdict(row);
            }
            case DELETE -> {
                Reason forbidden = guards.forbiddenReasonFor(userId);
                if (forbidden != null) {
                    return forbidden(row, forbidden, "this account is never deleted by a batch");
                }
                if (existing == null) {
                    return unexpected(row, Reason.NOT_FOUND, "no user with this id");
                }
                if (guards.looksDirectorySynced(Kind.USERS, userId)) {
                    return unexpected(row, Reason.LOOKS_DIRECTORY_SYNCED,
                            "the id carries the directory-sync prefix; the directory would recreate it");
                }
                return expected(row);
            }
            default -> throw new IllegalStateException(operation + " for users");
        }
    }

    private RowVerdict passwordVerdict(Row row, String password) {
        PasswordPolicyService.PasswordPolicyResult result = passwordPolicy.validate(password, repositoryId);
        if (result != null && !result.isOk()) {
            return unexpected(row, Reason.PASSWORD_POLICY, result.getErrorMessage());
        }
        return null;
    }

    /** A stated {@code groups} column names groups that must exist; a blank one is untouched. */
    private RowVerdict groupsColumnVerdict(Row row) {
        String groups = row.cell("groups");
        if (groups == null) {
            return expected(row);
        }
        for (String groupId : split(groups)) {
            if (!world.groupExists(groupId)) {
                return unexpected(row, Reason.GROUP_NOT_FOUND, "no group '" + groupId + "'");
            }
        }
        return expected(row);
    }

    // ---- groups ----

    private RowVerdict groupRow(Operation operation, Row row) {
        String groupId = row.id();
        GroupItem existing = world.group(groupId);
        remember("group", groupId, existing == null ? null : existing.getRevision());
        switch (operation) {
            case CREATE -> {
                if (existing != null) {
                    return unexpected(row, Reason.ALREADY_EXISTS, "a group with this id exists");
                }
                if (world.userExists(groupId)) {
                    // createGroupItem refuses a group id that is a user's id, after preview would
                    // have said expected (survey, 2026-09-29). Decide it here.
                    return unexpected(row, Reason.ALREADY_EXISTS, "a user with this id exists");
                }
                if (row.cell("name") == null) {
                    // The file's own defect, like a missing memberId: a 400 for the file, not a
                    // registry reason that means something else (c39 subagent P3).
                    throw new PrincipalBatchRequestException(400, "line " + row.line()
                            + ": a new group needs a name");
                }
                return membersColumnsVerdict(row, groupId);
            }
            case UPDATE -> {
                if (existing == null) {
                    return unexpected(row, Reason.NOT_FOUND, "no group with this id");
                }
                return membersColumnsVerdict(row, groupId);
            }
            case DELETE -> {
                if (existing == null) {
                    return unexpected(row, Reason.NOT_FOUND, "no group with this id");
                }
                if (guards.looksDirectorySynced(Kind.GROUPS, groupId)) {
                    return unexpected(row, Reason.LOOKS_DIRECTORY_SYNCED,
                            "the id carries a directory-sync prefix; the directory would recreate it");
                }
                return expected(row);
            }
            default -> throw new IllegalStateException(operation + " for groups");
        }
    }

    /** Stated {@code users} / {@code groups} columns must name existing members; nested groups
     *  must not close a cycle. Blank columns are untouched. */
    private RowVerdict membersColumnsVerdict(Row row, String groupId) {
        String users = row.cell("users");
        if (users != null) {
            for (String userId : split(users)) {
                if (!world.userExists(userId)) {
                    return unexpected(row, Reason.MEMBER_NOT_FOUND, "no user '" + userId + "'");
                }
            }
        }
        String groups = row.cell("groups");
        if (groups != null) {
            List<String> nested = split(groups);
            for (String nestedId : nested) {
                if (nestedId.equals(groupId)) {
                    return unexpected(row, Reason.NESTED_CYCLE, "a group cannot contain itself");
                }
                if (!world.groupExists(nestedId)) {
                    return unexpected(row, Reason.MEMBER_NOT_FOUND, "no group '" + nestedId + "'");
                }
            }
            if (world.wouldCycle(groupId, nested)) {
                return unexpected(row, Reason.NESTED_CYCLE, "these nested groups lead back to this group");
            }
        }
        return expected(row);
    }

    // ---- memberships ----

    private RowVerdict membershipRow(Operation operation, Row row) {
        String groupId = row.id();
        GroupItem group = world.group(groupId);
        remember("group", groupId, group == null ? null : group.getRevision());
        if (group == null) {
            return unexpected(row, Reason.GROUP_NOT_FOUND, "no group with this id");
        }
        if (operation == Operation.REPLACE) {
            Members members = Members.parse(row, row.cell("members"));
            for (String userId : members.users()) {
                if (!world.userExists(userId)) {
                    return unexpected(row, Reason.MEMBER_NOT_FOUND, "no user '" + userId + "'");
                }
            }
            for (String nestedId : members.groups()) {
                if (nestedId.equals(groupId)) {
                    return unexpected(row, Reason.NESTED_CYCLE, "a group cannot contain itself");
                }
                if (!world.groupExists(nestedId)) {
                    return unexpected(row, Reason.MEMBER_NOT_FOUND, "no group '" + nestedId + "'");
                }
            }
            if (!members.groups().isEmpty() && world.wouldCycle(groupId, members.groups())) {
                return unexpected(row, Reason.NESTED_CYCLE, "these nested groups lead back to this group");
            }
            return expected(row);
        }
        String memberId = row.cell("memberId");
        String memberType = row.cell("memberType");
        if (memberId == null || memberType == null) {
            throw new PrincipalBatchRequestException(400, "line " + row.line()
                    + ": memberId and memberType are required");
        }
        boolean isUser = switch (memberType.toLowerCase(Locale.ROOT)) {
            case "user" -> true;
            case "group" -> false;
            default -> throw new PrincipalBatchRequestException(400, "line " + row.line()
                    + ": memberType must be user or group, not '" + memberType + "'");
        };
        List<String> current = isUser ? group.getUsers() : group.getGroups();
        boolean member = current.contains(memberId);
        if (operation == Operation.ADD) {
            if (isUser ? !world.userExists(memberId) : !world.groupExists(memberId)) {
                return unexpected(row, Reason.MEMBER_NOT_FOUND, "no " + memberType + " '" + memberId + "'");
            }
            if (member) {
                return unexpected(row, Reason.ALREADY_MEMBER, memberId + " is already a member");
            }
            if (!isUser) {
                if (memberId.equals(groupId)) {
                    return unexpected(row, Reason.NESTED_CYCLE, "a group cannot contain itself");
                }
                List<String> after = new ArrayList<>(group.getGroups());
                after.add(memberId);
                if (world.wouldCycle(groupId, after)) {
                    return unexpected(row, Reason.NESTED_CYCLE, "adding " + memberId + " leads back to this group");
                }
            }
            return expected(row);
        }
        // REMOVE
        if (!member) {
            return unexpected(row, Reason.NOT_MEMBER, memberId + " is not a member");
        }
        if (guards.looksDirectorySynced(Kind.MEMBERSHIPS, groupId)) {
            // Design §5.1: delete AND remove. The directory would put the membership back.
            return unexpected(row, Reason.LOOKS_DIRECTORY_SYNCED,
                    "the group id carries a directory-sync prefix; the directory would restore the membership");
        }
        return expected(row);
    }

    /** {@code user:u001;group:g002} — the whole membership of one group, stated once. */
    record Members(List<String> users, List<String> groups) {
        static Members parse(Row row, String raw) {
            List<String> users = new ArrayList<>();
            List<String> groups = new ArrayList<>();
            if (raw == null) {
                return new Members(users, groups); // EMPTY means "make it empty" — only here
            }
            for (String part : split(raw)) {
                int colon = part.indexOf(':');
                if (colon <= 0) {
                    throw new PrincipalBatchRequestException(400, "line " + row.line()
                            + ": members entries are user:<id> or group:<id>, not '" + part + "'");
                }
                String type = part.substring(0, colon).toLowerCase(Locale.ROOT);
                String id = part.substring(colon + 1);
                if (id.isBlank()) {
                    throw new PrincipalBatchRequestException(400, "line " + row.line()
                            + ": members entry '" + part + "' has no id");
                }
                switch (type) {
                    case "user" -> users.add(id);
                    case "group" -> groups.add(id);
                    default -> throw new PrincipalBatchRequestException(400, "line " + row.line()
                            + ": members entry type must be user or group, not '" + type + "'");
                }
            }
            return new Members(users, groups);
        }
    }

    static List<String> split(String semicolonSeparated) {
        List<String> out = new ArrayList<>();
        for (String part : semicolonSeparated.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    // ---- verdicts and the snapshot ----

    private static RowVerdict expected(Row row) {
        return new RowVerdict(row.line(), row.id(), Verdict.EXPECTED, null, null);
    }

    private static RowVerdict unexpected(Row row, Reason reason, String message) {
        return new RowVerdict(row.line(), row.id(), Verdict.UNEXPECTED, reason, message);
    }

    private static RowVerdict forbidden(Row row, Reason reason, String message) {
        return new RowVerdict(row.line(), row.id(), Verdict.FORBIDDEN, reason, message);
    }

    private void remember(String kind, String id, String revision) {
        snapshot.add(kind + ":" + id + "|" + (revision == null ? "-" : revision));
    }

    /** The state the verdicts were decided against: every target's id and stored revision. */
    private String snapshotHash() {
        return sha256(String.join("\n", snapshot));
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
