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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Solr HTTP executor must never starve a request of its reader.
 *
 * <h2>What deadlocked</h2>
 *
 * <p>SolrJ 10.0.0 builds {@code MDCAwareThreadPoolExecutor(4, 256, 60s, LinkedBlockingQueue(1024))}
 * and gives that same instance to {@code HttpClient.Builder.executor(...)}. One request uses two
 * slots on it: SolrJ writes the body into a {@code PipedOutputStream} on the pool, and the JDK
 * HTTP client drains the pipe on the pool. Because a {@code ThreadPoolExecutor} grows past its
 * core size only when the queue is full, that pool is four threads until a thousand tasks pile up
 * — so four concurrent bodies larger than the pipe buffer take every slot as a writer, no reader
 * can start, and the reader tasks sit in a queue nothing will ever reach.
 *
 * <p>Observed against nb33 on 2026-08-14: all four {@code HttpJdkSolrClient-2-thread-*} in
 * {@code PipedInputStream.awaitSpace}, 42 threads parked in {@code HttpClientImpl.send}, 25 queued
 * on the per-ragId stripe lock, and two thread dumps minutes apart identical down to the frame.
 * The configured 30-second request timeout did not break the cycle; <b>why</b> it did not was never
 * established, and nothing beyond that observation should be asserted here.
 *
 * <h2>Why the test looks like this</h2>
 *
 * <p>The failure is not "slow", it is "never", so the assertion is completion within a bound.
 * {@link #theSolrJDefaultShapeIsTheOneThatDeadlocks()} runs the identical workload on SolrJ's own
 * configuration and shows it does NOT complete — without it, this file would pass just as well
 * against the broken client and prove nothing.
 */
class SolrHttpExecutorStarvationTest {

	/** Comfortably larger than the 1 KiB pipe buffer, as a RAG body with float vectors is. */
	private static final int BODY_BYTES = 256 * 1024;

	/** Four is the smallest number that fills SolrJ's core pool. Eight leaves no doubt. */
	private static final int CONCURRENT_REQUESTS = 8;

	/**
	 * Model {@value #CONCURRENT_REQUESTS} Solr requests, each needing its body writer and its body
	 * reader to run at the same time.
	 *
	 * <p>Every writer is submitted before any reader, because that is the state actually observed:
	 * several requests had begun producing their bodies and every pool thread was a writer. The
	 * ordering matters — interleaving writer, reader, writer, reader lets a FIFO queue pair them up
	 * and drain even on a starved pool, which would make this test pass against the broken
	 * configuration and prove nothing.
	 *
	 * @return true if every request completed before the deadline
	 */
	private boolean allRequestsComplete(ExecutorService executor, long timeoutSeconds)
			throws InterruptedException {
		CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);
		PipedInputStream[] pipes = new PipedInputStream[CONCURRENT_REQUESTS];
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			pipes[i] = new PipedInputStream();
			final PipedInputStream in = pipes[i];
			executor.execute(() -> {
				try (PipedOutputStream out = new PipedOutputStream(in)) {
					out.write(new byte[BODY_BYTES]);
				} catch (Exception e) {
					// Interrupted during cleanup, or the reader never arrived. Either way this
					// request did not complete, which is what the latch records by staying up.
				}
			});
		}
		for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
			final PipedInputStream in = pipes[i];
			executor.execute(() -> {
				try {
					byte[] buf = new byte[8192];
					long total = 0;
					int n;
					while ((n = in.read(buf)) != -1) {
						total += n;
					}
					if (total == BODY_BYTES) {
						done.countDown();
					}
				} catch (Exception e) {
					// same as above
				}
			});
		}
		return done.await(timeoutSeconds, TimeUnit.SECONDS);
	}

	/** The fix: every request gets its reader, so all of them finish. */
	@Test
	@Timeout(60)
	void concurrentLargeBodiesAllComplete() throws Exception {
		ExecutorService executor = SolrHttpExecutor.create("test-solr-http");
		try {
			assertTrue(allRequestsComplete(executor, 20),
					"a request's body writer and body reader share this executor, so an executor "
							+ "that cannot hand out both at once hangs the request forever");
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * The negative control: SolrJ's own configuration, same workload, does not complete.
	 *
	 * <p>This is what shipped. Deleting {@code withExecutor(...)} from either client restores it.
	 */
	@Test
	@Timeout(60)
	void theSolrJDefaultShapeIsTheOneThatDeadlocks() throws Exception {
		ThreadPoolExecutor solrJDefault = new ThreadPoolExecutor(4, 256, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(1024), r -> {
					Thread t = new Thread(r, "solrj-default-shape");
					t.setDaemon(true);
					return t;
				});
		try {
			assertFalse(allRequestsComplete(solrJDefault, 5),
					"if this now passes, ThreadPoolExecutor's growth rule changed and the whole "
							+ "premise of SolrHttpExecutor needs re-deriving — do not just delete "
							+ "this assertion");
		} finally {
			solrJDefault.shutdownNow();
		}
	}

	/**
	 * Platform threads, deliberately — but not for the reason first written here.
	 *
	 * <p>Verified against Temurin 21.0.10: {@code PipedInputStream.receive} is
	 * {@code synchronized} and {@code awaitSpace} is called while it holds that monitor
	 * ({@code awaitSpace} is not itself declared {@code synchronized}), and
	 * {@code Object.wait(long)} goes through {@code jdk.internal.misc.Blocker}, so the ForkJoinPool
	 * scheduler <b>compensates</b> by adding carriers. Four virtual writers would therefore NOT
	 * reproduce the four-thread starvation above.
	 *
	 * <p>The reason to stay on platform threads is that compensation is itself bounded — the
	 * default carrier maximum is {@code max(parallelism, 256)} — so virtual threads would move the
	 * cliff from 4 to a few hundred rather than remove it. The property wanted here is "a reader is
	 * always schedulable, until the OS refuses to create a thread". If this is ever revisited, that
	 * ceiling, not pinning, is the thing to argue about.
	 */
	@Test
	@Timeout(30)
	void theThreadsArePlatformThreads() throws Exception {
		ExecutorService executor = SolrHttpExecutor.create("test-solr-http-kind");
		try {
			java.util.concurrent.atomic.AtomicBoolean virtual =
					new java.util.concurrent.atomic.AtomicBoolean(true);
			CountDownLatch seen = new CountDownLatch(1);
			executor.execute(() -> {
				virtual.set(Thread.currentThread().isVirtual());
				seen.countDown();
			});
			assertTrue(seen.await(10, TimeUnit.SECONDS), "the task never ran");
			assertFalse(virtual.get(),
					"virtual threads would bound the reader supply at the carrier-compensation "
							+ "ceiling (default max(parallelism, 256)) instead of at the OS thread "
							+ "limit — the cliff moves rather than disappears");
		} finally {
			executor.shutdownNow();
		}
	}
}
