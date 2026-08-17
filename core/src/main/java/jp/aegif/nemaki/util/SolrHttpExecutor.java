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
package jp.aegif.nemaki.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The executor every {@code HttpJdkSolrClient} in this application must be built with.
 *
 * <h2>The deadlock this exists to prevent</h2>
 *
 * <p>SolrJ 10.0.0 builds its own executor when the caller supplies none:
 *
 * <pre>
 *   new MDCAwareThreadPoolExecutor(4, 256, 60L, SECONDS, new LinkedBlockingQueue&lt;&gt;(1024), ...)
 * </pre>
 *
 * <p>and hands that same instance to {@code HttpClient.Builder.executor(...)}. Two facts turn
 * that into a permanent hang:
 *
 * <ol>
 *   <li>{@code ThreadPoolExecutor} only grows past its core size <b>once the queue is full</b>.
 *       With a 1024-deep queue and a core of 4, the pool is a <b>4-thread pool</b> for any load
 *       that never accumulates a thousand pending tasks — which is all realistic load.
 *   <li>Both halves of one request run on that pool. SolrJ serializes the request body into a
 *       {@code PipedOutputStream} on it, and the JDK HTTP client drains the other end of that
 *       pipe on it too. A request whose body exceeds the pipe buffer therefore needs <b>two</b>
 *       slots at the same time: a writer and a reader.
 * </ol>
 *
 * <p>So four concurrent requests take all four slots as writers, no slot is left to read, and
 * every writer blocks in {@code PipedInputStream.awaitSpace} forever. The queued reader tasks
 * are never reached, so the pool never grows and the state cannot resolve itself.
 *
 * <p>Measured on 2026-08-14 against nb33 while indexing into a RAG-enabled repository: all four
 * {@code HttpJdkSolrClient-2-thread-*} threads in {@code awaitSpace}, 42 threads parked in
 * {@code HttpClientImpl.send} awaiting responses, and 25 more queued behind them on the per-ragId
 * stripe lock in {@code RAGIndexingServiceImpl.indexToSolr}. Two thread dumps taken minutes apart
 * were identical — 75 threads, zero entering, zero leaving. Only a JVM restart clears it. The
 * configured 30-second request timeout did not break the cycle; <b>why</b> it did not was never
 * established, and no explanation should be asserted here beyond that observation.
 *
 * <p>RAG traffic hits this and ordinary CMIS indexing usually does not, because the pipe buffer
 * is 1&nbsp;KiB and a RAG block-join document carries float embedding vectors for the parent and
 * every chunk. Such a body cannot fit, so the writer <i>always</i> needs a concurrent reader.
 * That is a difference of likelihood, not of kind: any sufficiently large update body reaches the
 * same state, which is why this is applied to both Solr clients rather than only the RAG one.
 *
 * <h2>Why unbounded, and what that costs</h2>
 *
 * <p>A pool whose tasks wait on each other cannot be safely bounded: with a ceiling of N threads,
 * N concurrent requests deadlock. {@link SynchronousQueue} plus an effectively unlimited maximum
 * hands every task a thread immediately, so a reader is always schedulable. Threads are reused and
 * expire after 60 seconds idle, so the steady-state cost is bounded by actual concurrency.
 *
 * <p><b>This does not make the exposure zero — it changes its shape.</b> Nothing upstream caps the
 * number of concurrent Solr requests: Tomcat serves requests on an unlimited virtual-thread
 * executor, {@code SolrUtil.asyncSolrExecutor} uses {@code CallerRunsPolicy} (back-pressure, which
 * makes the caller do the work rather than refusing it), and query and {@code forceSync} paths do
 * not go through that pool at all. So if Solr itself stalls, in-flight requests accumulate and this
 * executor creates a platform thread for each. A deterministic deadlock at four concurrent writers
 * has been traded for native-thread exhaustion under a sustained downstream stall — strictly better,
 * but not free. {@link #create} therefore logs as the pool grows, so the climb is visible in the
 * log before it becomes a failure.
 *
 * <h2>Why platform threads and not virtual ones</h2>
 *
 * <p>This is the one place that deliberately departs from the codebase-wide virtual-thread
 * convention, and the reason is narrower than "virtual threads would deadlock".
 *
 * <p>Verified against the Temurin 21.0.10 runtime this ships on: {@code PipedInputStream.receive}
 * is {@code synchronized} and {@code awaitSpace} is called while it holds that monitor
 * ({@code awaitSpace} itself is not declared {@code synchronized}); {@code Object.wait(long)} goes
 * through {@code jdk.internal.misc.Blocker}, so the ForkJoinPool scheduler <b>compensates</b> by
 * adding carriers. Four virtual writers would therefore NOT reproduce the four-thread starvation
 * observed above — the scheduler would add carriers and the readers would run.
 *
 * <p>The reason to stay on platform threads is that compensation is itself bounded: the default
 * maximum carrier count is {@code max(parallelism, 256)}. Virtual threads would move the cliff from
 * 4 to a few hundred rather than remove it, and the property wanted here is "a reader is always
 * schedulable, until the OS refuses to create a thread". If this is ever revisited, that ceiling —
 * not pinning — is the thing to argue about.
 *
 * <p>The caller owns the returned executor: supplying an executor to the builder makes SolrJ set
 * {@code shutdownExecutor = false}, so closing the client no longer shuts it down. Every caller
 * must call {@link #shutdown} alongside the client's own close.
 */
public final class SolrHttpExecutor {

	private static final Log log = LogFactory.getLog(SolrHttpExecutor.class);

	/**
	 * Pool sizes worth saying something about. Ordinary load never reaches the first one; crossing
	 * them in sequence is the signature of Solr stalling while requests keep arriving.
	 */
	private static final int[] GROWTH_MILESTONES = { 64, 128, 256, 512, 1024, 2048, 4096 };

	private SolrHttpExecutor() {
	}

	/**
	 * Create the executor for one {@code HttpJdkSolrClient}.
	 *
	 * @param name short name used as the thread-name prefix, e.g. {@code "solr-http-cmis"}
	 */
	public static ExecutorService create(String name) {
		final AtomicInteger seq = new AtomicInteger();
		final AtomicInteger milestone = new AtomicInteger();
		final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L,
				TimeUnit.SECONDS, new SynchronousQueue<>());
		pool.setThreadFactory(r -> {
			// Platform, not virtual: see the class javadoc.
			Thread t = new Thread(r, name + "-" + seq.incrementAndGet());
			t.setDaemon(true);
			// Only runs when the pool actually has to grow, so this costs nothing on the hot path.
			int size = pool.getPoolSize();
			int next = milestone.get();
			if (next < GROWTH_MILESTONES.length && size >= GROWTH_MILESTONES[next]
					&& milestone.compareAndSet(next, next + 1)) {
				log.warn("Solr HTTP executor '" + name + "' has grown to " + size
						+ " threads. This pool is deliberately unbounded so a request's body reader "
						+ "is always schedulable; sustained growth means Solr is not draining "
						+ "requests as fast as they arrive. Check Solr before this reaches the "
						+ "operating system's thread limit.");
			}
			return t;
		});
		return pool;
	}

	/**
	 * Stop an executor obtained from {@link #create}. Safe to call with {@code null}.
	 *
	 * <p>Shut the executor down only AFTER closing the client it belongs to, so in-flight requests
	 * can finish writing their bodies.
	 */
	public static void shutdown(ExecutorService executor) {
		if (executor == null) {
			return;
		}
		executor.shutdown();
		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
