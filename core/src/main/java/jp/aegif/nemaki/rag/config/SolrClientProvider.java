package jp.aegif.nemaki.rag.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Provides a singleton SolrClient instance for RAG operations.
 *
 * Uses Http2SolrClient which provides:
 * - Built-in connection pooling
 * - HTTP/2 support for better performance
 * - Thread-safe operations
 *
 * This class is shared across all RAG services to avoid creating
 * new connections for each operation, which was a P0 performance issue.
 */
@Component
public class SolrClientProvider {

    private static final Logger log = LoggerFactory.getLogger(SolrClientProvider.class);

    /** Connection timeout in milliseconds (30 seconds) */
    private static final long CONNECTION_TIMEOUT_MS = 30000;

    /** Socket/read timeout in milliseconds (60 seconds for large KNN queries) */
    private static final long SOCKET_TIMEOUT_MS = 60000;

    /** Idle timeout for pooled connections (5 minutes) */
    private static final long IDLE_TIMEOUT_MS = 300000;

    @Value("${solr.host:solr}")
    private String solrHost;

    @Value("${solr.port:8983}")
    private int solrPort;

    @Value("${solr.protocol:http}")
    private String solrProtocol;

    private volatile SolrClient solrClient;
    private final Object lock = new Object();

    /**
     * The HTTP executor backing {@link #solrClient}. Ours to shut down, because supplying one to
     * the builder makes SolrJ stop shutting it down itself.
     */
    private volatile java.util.concurrent.ExecutorService httpExecutor;

    /** Guarded by {@link #lock}. Once set, no further client is created. */
    private boolean destroyed;

    @PostConstruct
    public void init() {
        log.info("SolrClientProvider initialized - Solr URL: {}://{}:{}/solr",
                solrProtocol, solrHost, solrPort);
    }

    /**
     * Get the shared SolrClient instance.
     * Creates the client lazily on first access for better startup performance.
     *
     * @return Thread-safe SolrClient instance
     */
    public SolrClient getClient() {
        if (solrClient == null) {
            synchronized (lock) {
                if (destroyed) {
                    // Creating one here would build a client nothing will ever close, and — worse
                    // — cleanup() may already have shut down the executor it would be handed.
                    throw new IllegalStateException(
                            "SolrClientProvider has been destroyed; no new Solr client will be created");
                }
                if (solrClient == null) {
                    solrClient = createSolrClient();
                    log.info("Created shared HttpSolrClient for RAG operations");
                }
            }
        }
        return solrClient;
    }

    /**
     * Create a new HttpJdkSolrClient (JDK-built-in HTTP client) for reliable Solr
     * communication. Solr 10 removed HttpSolrClient (Apache HttpClient) and
     * Http2SolrClient (Jetty); HttpJdkSolrClient is the dependency-free
     * replacement.
     *
     * HTTP/1.1 is forced (useHttp1_1): the JDK client defaults to HTTP/2, which
     * against Solr 10 / Jetty 12 throws intermittent "RST_STREAM: Protocol
     * error" on UpdateRequest / block-join (RAG parent-child) documents — the
     * same class of HTTP/2 defect the previous HttpSolrClient (HTTP/1.1) was
     * chosen to avoid.
     */
    private SolrClient createSolrClient() {
        String url = String.format("%s://%s:%d/solr", solrProtocol, solrHost, solrPort);

        // withExecutor is not optional. SolrJ's default is a 4-thread pool shared by the thread
        // writing a request body into a pipe and the thread draining it, so four concurrent
        // updates deadlock it permanently. RAG bodies always exceed the 1 KiB pipe buffer because
        // they carry float embedding vectors, so this client reaches that state first — measured.
        // See SolrHttpExecutor.
        httpExecutor = jp.aegif.nemaki.util.SolrHttpExecutor.create("solr-http-rag");
        return new HttpJdkSolrClient.Builder(url)
                .useHttp1_1(true)
                .withExecutor(httpExecutor)
                .withConnectionTimeout(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .withRequestTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .withIdleTimeout(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Get the base Solr URL for this provider.
     *
     * @return Base Solr URL (e.g., "http://solr:8983/solr")
     */
    public String getSolrUrl() {
        return String.format("%s://%s:%d/solr", solrProtocol, solrHost, solrPort);
    }

    /**
     * Terminal teardown.
     *
     * <p>The client and its executor are detached together under the same lock creation uses, and
     * {@code destroyed} is set inside it. Without that, teardown can interleave with a concurrent
     * {@link #getClient()}: cleanup clears the field, the getter builds a replacement, and cleanup
     * then shuts down the REPLACEMENT's executor while leaking the original's — leaving a
     * published client whose transport is dead. Closing happens outside the lock so a slow close
     * does not block getters that are about to be refused anyway.
     */
    @PreDestroy
    public void cleanup() {
        SolrClient client;
        java.util.concurrent.ExecutorService executor;
        synchronized (lock) {
            destroyed = true;
            client = solrClient;
            executor = httpExecutor;
            solrClient = null;
            httpExecutor = null;
        }

        if (client != null) {
            try {
                log.info("Closing shared SolrClient...");
                client.close();
                log.info("SolrClient closed successfully");
            } catch (Exception e) {
                log.error("Error closing SolrClient", e);
            }
        }
        // After the client, so in-flight requests can finish writing their bodies.
        jp.aegif.nemaki.util.SolrHttpExecutor.shutdown(executor);
    }
}
