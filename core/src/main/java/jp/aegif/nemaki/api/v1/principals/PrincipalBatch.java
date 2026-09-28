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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The vocabulary of the bulk principal correction — one job is one {@link Kind} and one
 * {@link Operation}, every row gets one {@link Verdict}, and every verdict that is not
 * {@code expected} names a {@link Reason} from this registry.
 *
 * <p>Design: {@code docs/design/principal-batch.md}. Nothing here writes; the words are shared by
 * the planner (which decides), the applier (which writes through the canonical methods) and the
 * resource (which reports).
 */
public final class PrincipalBatch {

    private PrincipalBatch() {
    }

    /** What the rows describe. */
    public enum Kind {
        USERS, GROUPS, MEMBERSHIPS;

        public static Kind parse(String raw) {
            return parseEnum(raw, values(), "kind");
        }
    }

    /** What is done to every row. {@code CREATE / UPDATE / DELETE} for users and groups; {@code ADD /
     *  REMOVE / REPLACE} for memberships. */
    public enum Operation {
        CREATE, UPDATE, DELETE, ADD, REMOVE, REPLACE;

        public static Operation parse(String raw) {
            return parseEnum(raw, values(), "operation");
        }

        public boolean appliesTo(Kind kind) {
            return switch (kind) {
                case USERS, GROUPS -> this == CREATE || this == UPDATE || this == DELETE;
                case MEMBERSHIPS -> this == ADD || this == REMOVE || this == REPLACE;
            };
        }
    }

    /** What an immediate execute does with rows that are not {@code expected}. */
    public enum OnUnexpected {
        ABORT, SKIP;

        public static OnUnexpected parse(String raw) {
            return raw == null || raw.isBlank() ? ABORT : parseEnum(raw, values(), "onUnexpected");
        }
    }

    /** The three answers the preview gives a row. */
    public enum Verdict {
        /** Applying the row does what the operation says. */
        EXPECTED,
        /** The world is not as the row assumes ({@link Reason}); {@code skip} passes over it. */
        UNEXPECTED,
        /** Never applied, not even with {@code skip}: the built-in accounts and the actor. */
        FORBIDDEN
    }

    /** The registry of reasons. A verdict other than {@code EXPECTED} carries exactly one. */
    public enum Reason {
        ALREADY_EXISTS, NOT_FOUND, GROUP_NOT_FOUND, MEMBER_NOT_FOUND, ALREADY_MEMBER, NOT_MEMBER,
        NESTED_CYCLE, PASSWORD_POLICY, LOOKS_DIRECTORY_SYNCED,
        BUILT_IN_ADMIN, BUILT_IN_SERVICE_ACCOUNT, ACTOR_ITSELF
    }

    /** The CSV columns each (kind, operation) accepts — the header may not carry any other. */
    public static Set<String> columnsFor(Kind kind, Operation operation) {
        return switch (kind) {
            case USERS -> switch (operation) {
                case CREATE -> Set.of("userId", "name", "firstName", "lastName", "email", "password",
                        "admin", "groups");
                case UPDATE -> Set.of("userId", "name", "firstName", "lastName", "email", "password",
                        "admin", "groups");
                case DELETE -> Set.of("userId");
                default -> throw new IllegalArgumentException(operation + " does not apply to users");
            };
            case GROUPS -> switch (operation) {
                case CREATE, UPDATE -> Set.of("groupId", "name", "users", "groups");
                case DELETE -> Set.of("groupId");
                default -> throw new IllegalArgumentException(operation + " does not apply to groups");
            };
            case MEMBERSHIPS -> switch (operation) {
                case ADD, REMOVE -> Set.of("groupId", "memberId", "memberType");
                case REPLACE -> Set.of("groupId", "members");
                default -> throw new IllegalArgumentException(operation + " does not apply to memberships");
            };
        };
    }

    /** The column that identifies a row's target, and must be present and non-blank. */
    public static String idColumnFor(Kind kind) {
        return switch (kind) {
            case USERS -> "userId";
            case GROUPS, MEMBERSHIPS -> "groupId";
        };
    }

    /**
     * The columns a row must STATE for the operation to mean anything: the id, and for memberships
     * the member columns. A {@code replace} file that dropped its {@code members} column is refused
     * here, not read as "make every group empty" — a stated EMPTY cell is that (design §4); an
     * absent column is nothing.
     */
    public static Set<String> requiredColumnsFor(Kind kind, Operation operation) {
        if (kind == Kind.MEMBERSHIPS) {
            return operation == Operation.REPLACE
                    ? Set.of(idColumnFor(kind), "members")
                    : Set.of(idColumnFor(kind), "memberId", "memberType");
        }
        return Set.of(idColumnFor(kind));
    }

    /** Columns whose VALUE must never appear in a response, a plan, an audit line or a log. */
    public static final Set<String> SECRET_COLUMNS = Set.of("password");

    /** One row as the planner read it: its line, its target id and its raw cells. */
    public record Row(int line, String id, Map<String, String> cells) {
        public Row {
            if (line < 1) {
                throw new IllegalArgumentException("a row's line number starts at 1");
            }
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("a row without a target id cannot be planned");
            }
            cells = Map.copyOf(cells);
        }

        /** The cell, or null when the column is absent or blank — "not stated", never "empty". */
        public String cell(String column) {
            String v = cells.get(column);
            return v == null || v.isBlank() ? null : v;
        }

        /** A stated boolean; null when the column is absent or blank. */
        public Boolean flag(String column) {
            String v = cell(column);
            if (v == null) {
                return null;
            }
            String lower = v.toLowerCase(Locale.ROOT);
            if (lower.equals("true")) {
                return Boolean.TRUE;
            }
            if (lower.equals("false")) {
                return Boolean.FALSE;
            }
            throw new PrincipalBatchRequestException(400, "line " + line + ": column '" + column
                    + "' must be true or false, not '" + v + "'");
        }
    }

    /** What the preview said about one row. */
    public record RowVerdict(int line, String id, Verdict verdict, Reason reason, String message) {
        public RowVerdict {
            if ((verdict == Verdict.EXPECTED) != (reason == null)) {
                throw new IllegalArgumentException("a row is either expected or carries a reason");
            }
        }

        public Map<String, Object> asMap() {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("line", line);
            out.put("id", id);
            out.put("verdict", verdict.name().toLowerCase(Locale.ROOT));
            if (reason != null) {
                out.put("reason", reason.name());
            }
            if (message != null) {
                out.put("message", message);
            }
            return out;
        }
    }

    /** The outcome of applying one row. */
    public enum Outcome { APPLIED, SKIPPED, FORBIDDEN, NOT_APPLIED, FAILED }

    /** Limits: rows and bytes, both refused with 413 before anything is read into a plan. */
    public static final int MAX_ROWS = 5_000;
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    static <E extends Enum<E>> E parseEnum(String raw, E[] values, String field) {
        if (raw == null || raw.isBlank()) {
            throw new PrincipalBatchRequestException(400, field + " is required");
        }
        for (E e : values) {
            if (e.name().equalsIgnoreCase(raw.trim())) {
                return e;
            }
        }
        throw new PrincipalBatchRequestException(400, field + " '" + raw + "' is not one of "
                + List.of(values));
    }
}
