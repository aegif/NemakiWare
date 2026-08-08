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
 * be a guess. This waits for the specific object the test just created to appear in a query, so it
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
     * <p>60 s is plenty when {@code QueryTestGroup} runs on its own — measured settle times there
     * are single-digit seconds. It is <em>not</em> enough in a single {@code mvn test} run of the
     * whole suite: the CRUD groups ahead of it leave the asynchronous indexer with a backlog, and
     * a fixture created after that can take minutes to become searchable. Configurable so a slow
     * or loaded machine can raise it without a code change.
     */
    private static final long TIMEOUT_MS =
            Long.getLong("nemakiware.tck.indexSettleTimeoutMs", 240_000L);
    private static final long POLL_MS = 200L;

    /**
     * Every way the TCK reaches the index through a {@code Session}.
     *
     * <p>{@code createQueryStatement} has to be here even though it runs nothing: the statement it
     * returns is bound to the real session, so {@code statement.query(...)} never comes back
     * through this proxy. Missing it is not a compile error and not an obvious bug — it simply
     * leaves {@code QueryForObject}, the one test that builds its queries that way, racing exactly
     * as it did before.
     */
    private static final java.util.Set<String> TRIGGERS =
            java.util.Set.of("query", "queryObjects", "createQueryStatement");

    /** The last object created through the wrapped helpers, or null if none. */
    private volatile String pendingTypeQueryName;
    private volatile String pendingObjectId;

    /**
     * Records an object whose visibility the next query depends on.
     *
     * <p>Only the most recent one is kept on purpose. Solr's commit makes everything added before
     * it visible at once, so once the newest object can be found, everything created earlier can
     * be too — and the TCK query tests create their whole fixture before they query any of it.
     */
    public void created(String typeQueryName, String objectId) {
        if (objectId != null && !objectId.isEmpty()) {
            this.pendingTypeQueryName = typeQueryName;
            this.pendingObjectId = objectId;
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
     * Blocks until the last created object can be found by a query, or the timeout expires.
     * Clears the pending object either way, so one wait covers one fixture.
     */
    private void awaitVisible(Session session) {
        String objectId = pendingObjectId;
        String typeQueryName = pendingTypeQueryName;
        if (objectId == null) {
            return;
        }
        pendingObjectId = null;
        pendingTypeQueryName = null;

        String statement = "SELECT cmis:objectId FROM " + typeQueryName
                + " WHERE cmis:objectId = '" + objectId + "'";
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            if (isVisible(session, statement)) {
                System.out.println("[TCK] search index settled after "
                        + (System.currentTimeMillis() - start) + " ms");
                return;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Deliberately not an exception: the test proceeds and fails on its own assertion, which
        // says far more about what is broken than "the settle helper timed out" would.
        System.err.println("[TCK] search index did not settle within " + TIMEOUT_MS
                + " ms; querying anyway so the test reports the real failure."
                + " If this is a whole-suite run, the indexer is still draining the backlog the"
                + " CRUD groups left — raise -Dnemakiware.tck.indexSettleTimeoutMs or run"
                + " QueryTestGroup on its own.");
    }

    private boolean isVisible(Session session, String statement) {
        try {
            ItemIterable<QueryResult> results = session.query(statement, false);
            for (QueryResult ignored : results.getPage(1)) {
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            // A repository that cannot answer this at all should not turn into a 60 s wait.
            System.err.println("[TCK] settle probe failed (" + e.getClass().getSimpleName()
                    + "); continuing without waiting");
            return true;
        }
    }
}
