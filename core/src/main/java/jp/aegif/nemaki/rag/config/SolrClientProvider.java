package jp.aegif.nemaki.rag.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
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
                if (solrClient == null) {
                    solrClient = createSolrClient();
                    log.info("Created shared Http2SolrClient for RAG operations");
                }
            }
        }
        return solrClient;
    }

    /**
     * Create a new Http2SolrClient with connection pooling.
     */
    private SolrClient createSolrClient() {
        String url = String.format("%s://%s:%d/solr", solrProtocol, solrHost, solrPort);

        return new Http2SolrClient.Builder(url)
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

    @PreDestroy
    public void cleanup() {
        if (solrClient != null) {
            try {
                log.info("Closing shared SolrClient...");
                solrClient.close();
                log.info("SolrClient closed successfully");
            } catch (Exception e) {
                log.error("Error closing SolrClient", e);
            }
        }
    }
}
