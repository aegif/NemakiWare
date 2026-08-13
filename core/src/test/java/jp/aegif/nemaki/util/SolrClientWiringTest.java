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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.rag.config.SolrClientProvider;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * Both production Solr clients must actually be built with {@link SolrHttpExecutor}.
 *
 * <h2>Why this file exists separately from the mechanism test</h2>
 *
 * <p>{@code SolrHttpExecutorStarvationTest} proves the executor SHAPE is right — that a cached pool
 * survives the writer/reader workload and that SolrJ's own 4/1024 shape does not. It never builds a
 * production client, so deleting {@code .withExecutor(...)} from either construction site leaves it
 * fully green while the release-blocking deadlock is back. That gap was pointed out in review, and
 * it is the gap this file closes: here the clients are constructed exactly as the application
 * constructs them, and the executor they ended up holding is read back out.
 *
 * <p>Nothing here talks to Solr. Building an {@code HttpJdkSolrClient} does not connect, so these
 * assertions hold on a machine with no Solr running.
 */
class SolrClientWiringTest {

	/** SolrJ's default, for the "not this" half of each assertion. */
	private static final int SOLRJ_DEFAULT_MAX_POOL = 256;

	private static ExecutorService executorOf(SolrClient client) throws Exception {
		assertInstanceOf(HttpJdkSolrClient.class, client,
				"the wiring assertions below read a field of HttpJdkSolrClient");
		Field field = HttpJdkSolrClient.class.getDeclaredField("executor");
		field.setAccessible(true);
		return (ExecutorService) field.get(client);
	}

	/**
	 * Assert the client holds an executor of the shape {@link SolrHttpExecutor} produces, and
	 * explicitly not the one SolrJ builds for itself.
	 */
	private static void assertWiredToOurExecutor(SolrClient client, String site) throws Exception {
		ExecutorService executor = executorOf(client);
		assertNotNull(executor, site + ": the client has no executor at all");
		ThreadPoolExecutor pool = assertInstanceOf(ThreadPoolExecutor.class, executor,
				site + ": expected the pool SolrHttpExecutor.create() returns");

		assertEquals(Integer.MAX_VALUE, pool.getMaximumPoolSize(),
				site + ": the pool is bounded, so it can starve a request of its body reader — "
						+ "SolrJ's own default caps at " + SOLRJ_DEFAULT_MAX_POOL
						+ " and deadlocks at four concurrent large writes");
		assertInstanceOf(SynchronousQueue.class, pool.getQueue(),
				site + ": a queueing pool defers the reader instead of running it, which is exactly "
						+ "the deadlock — SolrHttpExecutor uses a SynchronousQueue so every task "
						+ "gets a thread immediately");
	}

	/** The RAG client — the one that actually deadlocked in production. */
	@Test
	void theRagClientIsWiredToOurExecutor() throws Exception {
		SolrClientProvider provider = new SolrClientProvider();
		set(provider, "solrProtocol", "http");
		set(provider, "solrHost", "solr");
		set(provider, "solrPort", 8983);
		try {
			assertWiredToOurExecutor(provider.getClient(), "SolrClientProvider.createSolrClient");
		} finally {
			provider.cleanup();
		}
	}

	/** The CMIS client. Same defect, only less likely to be reached first. */
	@Test
	void theCmisClientIsWiredToOurExecutor() throws Exception {
		SolrUtil util = new SolrUtil();
		util.setPropertyManager(propertyManager());
		try {
			SolrClient client = util.getSolrClient();
			assertNotNull(client, "the client could not be built, so this test proved nothing");
			assertWiredToOurExecutor(client, "SolrUtil.getSolrClient");
		} finally {
			util.destroy();
		}
	}

	/**
	 * Teardown is terminal.
	 *
	 * <p>Without this, a shutdown racing a lazy getter could publish a client whose executor has
	 * just been shut down — a transport that is dead in a different way.
	 */
	@Test
	void aDestroyedProviderRefusesToBuildAnotherClient() {
		SolrClientProvider provider = new SolrClientProvider();
		set(provider, "solrProtocol", "http");
		set(provider, "solrHost", "solr");
		set(provider, "solrPort", 8983);
		provider.getClient();
		provider.cleanup();

		assertThrows(IllegalStateException.class, provider::getClient,
				"a client created after cleanup is one nothing will ever close, and its executor "
						+ "may already have been shut down");
	}

	/** Same for SolrUtil, whose contract is to answer null rather than throw. */
	@Test
	void aDestroyedSolrUtilReturnsNoClient() {
		SolrUtil util = new SolrUtil();
		util.setPropertyManager(propertyManager());
		util.getSolrClient();
		util.destroy();

		org.junit.jupiter.api.Assertions.assertNull(util.getSolrClient(),
				"getSolrClient() documents null as 'no client available'; after destroy() that is "
						+ "the honest answer, not a freshly built client nothing owns");
	}

	private static jp.aegif.nemaki.util.PropertyManager propertyManager() {
		jp.aegif.nemaki.util.PropertyManager pm = mock(jp.aegif.nemaki.util.PropertyManager.class);
		when(pm.readValue(PropertyKey.SOLR_PROTOCOL)).thenReturn("http");
		when(pm.readValue(PropertyKey.SOLR_HOST)).thenReturn("solr");
		when(pm.readValue(PropertyKey.SOLR_PORT)).thenReturn("8983");
		when(pm.readValue(PropertyKey.SOLR_CONTEXT)).thenReturn("solr");
		return pm;
	}

	private static void set(Object target, String field, Object value) {
		try {
			Field f = target.getClass().getDeclaredField(field);
			f.setAccessible(true);
			f.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not set " + field + " — the field was renamed?", e);
		}
	}
}
