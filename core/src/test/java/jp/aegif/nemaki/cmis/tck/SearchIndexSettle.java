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
package jp.aegif.nemaki.cmis.tck;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Session;

/**
 * Waits for a freshly created object to become searchable before a TCK test queries for it.
 *
 * <h2>Why the TCK needs this and the product does not</h2>
 *
 * <p>NemakiWare indexes into Solr asynchronously: measured on a running server, a document is
 * queryable <strong>1,671 ms</strong> after {@code createDocument} returns (642 ms to reach the
 * index, then the {@code commitWithin(1000)} window before a searcher sees it). The OpenCMIS TCK
 * query tests create objects and query for them immediately, which is a read-your-writes
 * expectation that CMIS does not require and this repository does not offer.
 *
 * <p>That mismatch used to be hidden by the server being slow. Verifying a Basic-auth password
 * cost 225 ms of BCrypt on every request, so the TCK's own pacing exceeded the indexing window by
 * accident. Once that cost was removed the tests began failing — the same binary passes 6/6 with
 * the password cache switched off, and fails 2/6 with it on, while taking 2,292 s instead of 98 s.
 * The tests were always racing; they simply never lost.
 *
 * <h2>Why a poll and not a sleep</h2>
 *
 * <p>A fixed sleep would have to be long enough for the worst case on every run, and would still
 * be a guess. This waits for the objects the test actually created to appear in a query, so it
 * costs exactly what the index costs on that machine that day, and it fails loudly rather than
 * silently: after {@link #TIMEOUT_MS} it gives up and lets the test query anyway, so a genuine
 * indexing bug still surfaces as the same TCK failure it always did.
 *
 * <h2>What it must not do</h2>
 *
 * <p>It only ever delays. It does not retry assertions, swallow empty results, or touch what the
 * test observes — the query the test runs is the query the server answers. A test that would fail
 * for a real reason fails identically, {@code TIMEOUT_MS} later.
 */
public final class SearchIndexSettle {

    /**
     * How long to wait for the index, in milliseconds.
     *
     * <p>Measured settle times are single-digit seconds when {@code QueryTestGroup} runs on its
     * own, and 99–135 s in a single {@code mvn test} run of the whole suite, where the CRUD groups
     * ahead of it leave the asynchronous indexer with a backlog. The default is sized for the
     * latter; configurable so a slower or more loaded machine can raise it without a code change.
     */
    private static final long TIMEOUT_MS =
            Long.getLong("nemakiware.tck.indexSettleTimeoutMs", 240_000L);
    private static final long POLL_MS = 200L;

    /** Consecutive probe failures tolerated before the wait is abandoned. */
    private static final int PROBE_FAILURES_BEFORE_GIVING_UP = 5;

    /**
     * Every way the TCK reaches the index through a {@code Session}.
     *
     * <p>{@code createQueryStatement} has to be here even though it runs nothing: the statement it
     * returns is bound to the real session, so {@code statement.query(...)} never comes back
     * through this proxy. Both {@code QueryForObject} and {@code QueryInFolderTest} build their
     * queries that way, so leaving it out silently returns them to the race.
     */
    private static final java.util.Set<String> TRIGGERS =
            java.util.Set.of("query", "queryObjects", "createQueryStatement");

    /**
     * Every object created through the wrapped helpers, in creation order.
     *
     * <p>All of them, not just the newest. Indexing is dispatched to a bounded thread pool
     * ({@code SolrUtil}'s {@code solr-async-*} executor, 2–4 threads over a queue), so the order
     * in which documents reach Solr is not the order in which they were created — waiting for the
     * last one says nothing about the rest. The earlier version of this class waited for one
     * object and reasoned that a commit makes everything before it visible; that reasoning needs
     * "created before" to imply "indexed before", which the executor does not provide.
     */
    private final java.util.List<String[]> pending =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String[]>());

    /** Records an object whose visibility the next query depends on. */
    public void created(String typeQueryName, String objectId) {
        if (objectId != null && !objectId.isEmpty()) {
            pending.add(new String[] { typeQueryName, objectId });
        }
    }

    /**
     * Returns a session that settles the index before the first query it is asked to run.
     *
     * <p>Every other call passes straight through. The wrapper is a proxy rather than a subclass
     * because {@code Session} is an interface with a large surface that the TCK uses freely.
     */
    public Session wrap(final Session session) {
        return (Session) Proxy.newProxyInstance(
                Session.class.getClassLoader(),
                new Class<?>[] { Session.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (TRIGGERS.contains(method.getName())) {
                            awaitVisible(session);
                        }
                        try {
                            return method.invoke(session, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }

    /**
     * Blocks until every created object can be found by a query, or the timeout expires.
     * Clears the pending list either way, so one wait covers one fixture.
     */
    private void awaitVisible(Session session) {
        List<String[]> waitFor;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            waitFor = new java.util.ArrayList<String[]>(pending);
            pending.clear();
        }

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        long start = System.currentTimeMillis();
        int probeFailures = 0;
        // Newest first: it is the most likely to still be missing, so a fixture that is already
        // settled costs one probe rather than one per object.
        for (int i = waitFor.size() - 1; i >= 0; i--) {
            String[] entry = waitFor.get(i);
            String statement = "SELECT cmis:objectId FROM " + entry[0]
                    + " WHERE cmis:objectId = '" + entry[1] + "'";
            while (true) {
                Boolean visible = probe(session, statement);
                if (Boolean.TRUE.equals(visible)) {
                    probeFailures = 0;
                    break;
                }
                if (visible == null && ++probeFailures >= PROBE_FAILURES_BEFORE_GIVING_UP) {
                    // The repository cannot answer this query at all. Waiting longer will not help,
                    // and the test's own assertion says more than a settle-helper timeout would.
                    System.err.println("[TCK] settle probe failed " + probeFailures
                            + " times in a row; querying anyway");
                    return;
                }
                if (System.currentTimeMillis() >= deadline) {
                    // Deliberately not an exception: the test proceeds and fails on its own
                    // assertion, which says far more than "the settle helper timed out" would.
                    System.err.println("[TCK] search index did not settle within " + TIMEOUT_MS
                            + " ms (" + (waitFor.size() - i) + " of " + waitFor.size()
                            + " objects checked); querying anyway so the test reports the real"
                            + " failure. In a whole-suite run the indexer is still draining the"
                            + " backlog the CRUD groups left — raise"
                            + " -Dnemakiware.tck.indexSettleTimeoutMs or run QueryTestGroup alone.");
                    return;
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        System.out.println("[TCK] search index settled after "
                + (System.currentTimeMillis() - start) + " ms (" + waitFor.size() + " objects)");
    }

    /**
     * {@code TRUE} visible, {@code FALSE} not yet, {@code null} the probe itself failed.
     *
     * <p>A failed probe is NOT treated as "visible". Under exactly the conditions this wait exists
     * for — a loaded server during a whole-suite run — a transient error is the likeliest reason a
     * probe throws, and answering TRUE there abandons the wait at the moment it is needed most.
     */
    private Boolean probe(Session session, String statement) {
        try {
            ItemIterable<QueryResult> results = session.query(statement, false);
            for (QueryResult ignored : results.getPage(1)) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (RuntimeException e) {
            System.err.println("[TCK] settle probe error (" + e.getClass().getSimpleName()
                    + "): " + e.getMessage());
            return null;
        }
    }
}
