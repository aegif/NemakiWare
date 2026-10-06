package jp.aegif.nemaki.api.v1.principals;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import jp.aegif.nemaki.api.v1.principals.PrincipalBatch.Row;

/**
 * The credentials a principal-batch request carries, and the one way its log lines are written
 * so that none of them carries one.
 *
 * <p>The design says a password's value is in no response, plan, audit line or LOG. The log is
 * the exit the layers below write into: a store or a policy that echoes its input puts the cell
 * into an exception message, and the applier and the resource both log those messages under an
 * incident id. So every logged text goes through {@link #redact(String)} — each known secret
 * replaced, and the text WITHHELD when a secret still survives (a password equal to the
 * placeholder, or part of it) — and every logged throwable through {@link #loggable(Throwable)},
 * which keeps the classes and frames of the whole cause chain and redacts each message (9-6
 * review of area C and its confirmation, 2026-10-06: nothing measured the log; the first fix
 * redacted one message with a fixed placeholder and dropped the cause chain).
 *
 * <p>Until the request has been read as rows ({@link #learn}), what it carries is not known, so
 * nothing of a message is logged: a parser's text may quote the body.
 */
public final class Secrets {

    static final String PLACEHOLDER = "[password redacted]";
    static final String WITHHELD_UNREAD =
            "[message withheld: the request was not yet read as rows, and its text may carry a credential]";
    static final String WITHHELD_ECHO =
            "[message withheld: it echoed a credential the placeholder did not cover]";
    static final String WITHHELD_SHORT =
            "[message withheld: it may echo a credential too short to replace without mangling the text]";
    /** Below this length a credential's value is too common a substring to replace: the text is withheld instead. */
    static final int SHORTEST_REPLACEABLE = 4;

    private boolean known;
    private final Set<String> values = new LinkedHashSet<>();

    /** The rows have been read: their password cells are the secrets (none, when {@code rows} is null). */
    public void learn(Collection<Row> rows) {
        known = true;
        if (rows != null) {
            for (Row row : rows) {
                learn(row);
            }
        }
    }

    /** Every column the registry {@link PrincipalBatch#SECRET_COLUMNS} names — not "password" spelt here. */
    public void learn(Row row) {
        known = true;
        if (row == null) {
            return;
        }
        for (String column : PrincipalBatch.SECRET_COLUMNS) {
            String value = row.cell(column);
            if (value != null) {
                values.add(value);
            }
        }
    }

    /** {@code text} with every secret replaced — or withheld, when one would still be in it. */
    public String redact(String text) {
        if (text == null) {
            return null;
        }
        if (!known) {
            return WITHHELD_UNREAD;
        }
        String out = text;
        for (String value : values) {
            if (value.length() < SHORTEST_REPLACEABLE) {
                // "e" is in almost every message; replacing it mangles the one diagnostic line
                // the operator has, and leaves the question open anyway.
                if (out.contains(value)) {
                    return WITHHELD_SHORT;
                }
                continue;
            }
            out = out.replace(value, PLACEHOLDER);
        }
        for (String value : values) {
            if (out.contains(value)) {
                return WITHHELD_ECHO;
            }
        }
        return out;
    }

    /**
     * {@code e} rebuilt for the log: the same classes and stack frames down the cause chain,
     * each message redacted. The original is never handed to the logger — its messages, and
     * its causes' messages, are whatever the failing layers wrote.
     */
    public Throwable loggable(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        RuntimeException top = null;
        RuntimeException tail = null;
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            RuntimeException copy = new RuntimeException(t.getClass().getName() + ": " + redact(t.getMessage()));
            copy.setStackTrace(t.getStackTrace());
            if (top == null) {
                top = copy;
            } else {
                tail.initCause(copy);
            }
            tail = copy;
        }
        return top;
    }
}
