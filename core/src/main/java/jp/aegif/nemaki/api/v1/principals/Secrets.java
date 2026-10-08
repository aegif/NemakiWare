package jp.aegif.nemaki.api.v1.principals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 * which keeps the classes and frames of the cause chain and of the suppressed throwables, up to
 * a bound, and redacts each message (9-6 review of area C and its confirmation, 2026-10-06:
 * nothing measured the log; the first fix redacted one message with a fixed placeholder and
 * dropped the cause chain).
 *
 * <p>Until the request has been read as rows ({@link #learn}), what it carries is not known, so
 * nothing of a message is logged: a parser's text may quote the body. This arm is defensive and
 * UNMEASURED: every parse failure the resource knows of is reported as a 400 that is not logged
 * (the one body found to reach this arm by reading — the literal {@code null} — is a 400 since
 * round 5), so only a parser bug would reach it (confirmation review, rounds 4 and 5, P3).
 *
 * <p>The resource learns every row of a request, so a request of thousands of rows is redacted
 * against all of their passwords at once: a one-letter password anywhere withholds the text of
 * an unrelated store failure, and a four-letter common word mangles it. Not a leak; the cost of
 * one log line per batch rather than one per row.
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
        for (String value : values) {
            // "e" is in almost every message; replacing it mangles the one diagnostic line
            // the operator has, and leaves the question open anyway.
            if (value.length() < SHORTEST_REPLACEABLE && text.contains(value)) {
                return WITHHELD_SHORT;
            }
        }
        // Every occurrence of every value, as spans on the ORIGINAL text, merged, then replaced.
        // Replacing one value after another left the tail of a longer one in place when a
        // shorter one was its prefix — "Welcome1" before "Welcome1-Tokyo" logged "-Tokyo"
        // (confirmation review, round 4, P2).
        List<int[]> spans = new ArrayList<>();
        for (String value : values) {
            if (value.length() < SHORTEST_REPLACEABLE) {
                continue;
            }
            for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
                spans.add(new int[] {at, at + value.length()});
            }
        }
        spans.sort(Comparator.comparingInt(span -> span[0]));
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int[] span : spans) {
            if (span[0] < cursor) {
                // Inside, or overlapping, the span just replaced: extend it.
                cursor = Math.max(cursor, span[1]);
                continue;
            }
            out.append(text, cursor, span[0]).append(PLACEHOLDER);
            cursor = span[1];
        }
        out.append(text, cursor, text.length());
        String result = out.toString();
        for (String value : values) {
            // Short values were decided against the original text above; the only way one can
            // be in the result now is as a substring of the placeholder itself, which is not an
            // echo (confirmation review, round 5, P3: "sw" inside "[password redacted]").
            if (value.length() >= SHORTEST_REPLACEABLE && result.contains(value)) {
                return WITHHELD_ECHO;
            }
        }
        return result;
    }

    /**
     * How many throwables the logged copy carries at most, counting each cause and each
     * suppressed one. It is also the deepest the copy can nest, which is what keeps logback's
     * per-link recursion bounded.
     */
    static final int MOST = 64;

    /** A copied throwable whose suppressed throwables are still to be copied. */
    private record Pending(Throwable original, RuntimeException copy) {
    }

    /**
     * {@code e} rebuilt for the log: the same classes and stack frames down the cause chain and
     * on every suppressed throwable, each message redacted. The original is never handed to the
     * logger — its messages, its causes' and its suppressed throwables' messages are whatever
     * the failing layers wrote.
     *
     * <p>Suppressed throwables were dropped (Codex, confirmation review c98, P2): a store write
     * that failed inside try-with-resources, and whose close failed too, logged the write's
     * failure and lost the close's — often the connection-level reason an incident is about.
     *
     * <p>Nothing here recurses, and the copy carries at most {@link #MOST} throwables; what is
     * beyond that is replaced by one line saying it was left out. The suppressed throwables used
     * to be copied by recursion, so a throwable nested a few thousand suppressed deep overflowed
     * the stack inside the applier's catch: no incident line, no incident response, and no audit
     * of the rows already applied (R146). Logback's {@code ThrowableProxy} recurses once per link
     * too, so a copy as deep as its original would overflow there instead: the bound is on what
     * the logger is given, not only on how it is built.
     *
     * <p>The primary cause chain is copied before any suppressed throwable, and a throwable met a
     * second time — a cycle, or one throwable that is both a cause and a suppressed one — is
     * written as a line naming it. The suppressed ones used to be walked first, so a throwable
     * that was both cut the primary chain short at that point, with no mark (R146).
     */
    public Throwable loggable(Throwable e) {
        if (e == null) {
            return null;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Pending> pending = new ArrayDeque<>();
        int[] left = {MOST};
        RuntimeException top = copyChain(e, seen, left, pending);
        while (!pending.isEmpty()) {
            Pending node = pending.removeFirst();
            Throwable[] suppressed = node.original().getSuppressed();
            for (int i = 0; i < suppressed.length; i++) {
                if (left[0] <= 0) {
                    node.copy().addSuppressed(leftOut((suppressed.length - i) + " suppressed throwable(s) left out,"
                            + " with what they carry: the logged copy stops at " + MOST + " throwables"));
                    break;
                }
                node.copy().addSuppressed(copyChain(suppressed[i], seen, left, pending));
            }
        }
        return top;
    }

    /**
     * The cause chain from {@code head}, walked iteratively. Each copied throwable is queued, so
     * that its suppressed throwables are copied after every chain already begun.
     */
    private RuntimeException copyChain(Throwable head, Set<Throwable> seen, int[] left,
            Deque<Pending> pending) {
        RuntimeException top = null;
        RuntimeException tail = null;
        for (Throwable t = head; t != null; t = t.getCause()) {
            RuntimeException copy;
            boolean last = true;
            if (!seen.add(t)) {
                copy = leftOut("the same " + t.getClass().getName() + " as logged above");
            } else if (left[0] <= 0) {
                copy = leftOut("the rest of this cause chain left out: the logged copy stops at " + MOST
                        + " throwables");
            } else {
                copy = new RuntimeException(t.getClass().getName() + ": " + redact(t.getMessage()));
                copy.setStackTrace(t.getStackTrace());
                left[0]--;
                pending.addLast(new Pending(t, copy));
                last = false;
            }
            if (top == null) {
                top = copy;
            } else {
                tail.initCause(copy);
            }
            tail = copy;
            if (last) {
                break;
            }
        }
        return top;
    }

    /** One line in place of what was not copied: no frames of its own, nothing below it. */
    private static RuntimeException leftOut(String what) {
        RuntimeException line = new RuntimeException("[" + what + "]");
        line.setStackTrace(new StackTraceElement[0]);
        return line;
    }
}
