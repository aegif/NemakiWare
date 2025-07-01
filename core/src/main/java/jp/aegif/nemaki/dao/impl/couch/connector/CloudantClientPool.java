package jp.aegif.nemaki.dao.impl.couch.connector;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.DatabaseInformation;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.model.couch.CouchConfiguration;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * Cloudant Java SDK based connection pool
 * Replaces the Ektorp-based ConnectorPool
 */
public class CloudantClientPool {

	private RepositoryInfoMap repositoryInfoMap;
	private String url;
	private int maxConnections;
	private int connectionTimeout;
	private int socketTimeout;
	private boolean authEnabled;
	private String authUserName;
	private String authPassword;

	private Map<String, CloudantClientWrapper> pool = new HashMap<String, CloudantClientWrapper>();
	private boolean initialized = false;
	private final Object initLock = new Object();

	private static final Log log = LogFactory.getLog(CloudantClientPool.class);

	/**
	 * Initialize the Cloudant client pool
	 */
	public void initialize() {
		synchronized (initLock) {
			if (initialized) {
				return;
			}

			log.info("Initializing Cloudant client pool...");

			try {
				// Create Cloudant client with Basic Authentication
				Cloudant cloudantClient = createCloudantClient();

				// Test connection
				if (isConnected()) {
					log.info("Successfully connected to CouchDB at: " + url);

					// Initialize repository connections
					initializeRepositories(cloudantClient);

					initialized = true;
					log.info("Cloudant client pool initialization completed successfully");
				} else {
					log.error("Failed to connect to CouchDB at: " + url);
				}

			} catch (Exception e) {
				log.error("Error initializing Cloudant client pool", e);
				throw new RuntimeException("Failed to initialize Cloudant client pool", e);
			}
		}
	}

	/**
	 * Create Cloudant client with authentication
	 */
	private Cloudant createCloudantClient() {
		try {
			// Configure authentication if enabled
			if (authEnabled && authUserName != null && authPassword != null) {
				BasicAuthenticator authenticator = new BasicAuthenticator.Builder()
					.username(authUserName)
					.password(authPassword)
					.build();
				
				Cloudant cloudant = new Cloudant("cloudant-service", authenticator);
				cloudant.setServiceUrl(url);
				log.info("Cloudant client configured with Basic Authentication for user: " + authUserName);
				return cloudant;
			} else {
				// Use NoAuth authenticator for no authentication
				Cloudant cloudant = new Cloudant("cloudant-service", null);
				cloudant.setServiceUrl(url);
				log.info("Cloudant client configured without authentication");
				return cloudant;
			}

		} catch (Exception e) {
			log.error("Error creating Cloudant client", e);
			throw new RuntimeException("Failed to create Cloudant client", e);
		}
	}

	/**
	 * Initialize repository connections
	 */
	private void initializeRepositories(Cloudant cloudantClient) {
		if (repositoryInfoMap != null) {
			java.util.Set<String> repositoryIdSet = repositoryInfoMap.keys();
			List<String> repositoryIds = new ArrayList<String>(repositoryIdSet);
			
			if (CollectionUtils.isNotEmpty(repositoryIds)) {
				for (String repositoryId : repositoryIds) {
					log.info("Initializing repository: " + repositoryId);
					
					// Create wrapper for this repository
					CloudantClientWrapper wrapper = new CloudantClientWrapper(cloudantClient, repositoryId);
					pool.put(repositoryId, wrapper);
					
					// Verify database exists
					verifyDatabase(wrapper, repositoryId);
				}
			}
		}
	}

	/**
	 * Verify database exists and is accessible
	 */
	private void verifyDatabase(CloudantClientWrapper wrapper, String repositoryId) {
		try {
			GetDatabaseInformationOptions options = new GetDatabaseInformationOptions.Builder()
				.db(repositoryId)
				.build();
				
			DatabaseInformation dbInfo = wrapper.getClient().getDatabaseInformation(options).execute().getResult();
			log.info("Repository '" + repositoryId + "' verified successfully. Doc count: " + dbInfo.getDocCount());
			
		} catch (NotFoundException e) {
			log.warn("Database '" + repositoryId + "' not found - may need to be created");
		} catch (Exception e) {
			log.error("Error verifying database '" + repositoryId + "'", e);
		}
	}

	/**
	 * Get Cloudant client wrapper for repository
	 */
	public CloudantClientWrapper getClient(String repositoryId) {
		if (!initialized) {
			initialize();
		}

		CloudantClientWrapper wrapper = pool.get(repositoryId);
		if (wrapper != null) {
			log.info("Retrieved Cloudant client for repository '" + repositoryId + "' from pool.");
			return wrapper;
		} else {
			log.error("No Cloudant client found for repository: " + repositoryId);
			throw new RuntimeException("No Cloudant client found for repository: " + repositoryId);
		}
	}

	/**
	 * Get Cloudant client wrapper for archive database (backward compatibility)
	 * This method is used for archive operations and maps to repository databases
	 */
	public CloudantClientWrapper get(String archiveId) {
		// For now, map archive access to the same client pool
		// Archive databases typically follow naming pattern: repositoryId_archive
		return getClient(archiveId);
	}

	/**
	 * Test connection to CouchDB
	 */
	private boolean isConnected() {
		try {
			URL couchUrl = new URL(url);
			String host = couchUrl.getHost();
			int port = couchUrl.getPort();
			if (port == -1) {
				port = couchUrl.getDefaultPort();
			}

			log.debug("Testing connection to " + host + ":" + port);

			InetAddress address = InetAddress.getByName(host);
			Socket socket = new Socket();
			socket.connect(new InetSocketAddress(address, port), connectionTimeout);
			socket.close();

			return true;

		} catch (Exception e) {
			log.error("Connection test failed", e);
			return false;
		}
	}

	// Getters and Setters
	public RepositoryInfoMap getRepositoryInfoMap() {
		return repositoryInfoMap;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public int getMaxConnections() {
		return maxConnections;
	}

	public void setMaxConnections(int maxConnections) {
		this.maxConnections = maxConnections;
	}

	public int getConnectionTimeout() {
		return connectionTimeout;
	}

	public void setConnectionTimeout(int connectionTimeout) {
		this.connectionTimeout = connectionTimeout;
	}

	public int getSocketTimeout() {
		return socketTimeout;
	}

	public void setSocketTimeout(int socketTimeout) {
		this.socketTimeout = socketTimeout;
	}

	public boolean isAuthEnabled() {
		return authEnabled;
	}

	public void setAuthEnabled(boolean authEnabled) {
		this.authEnabled = authEnabled;
	}

	public String getAuthUserName() {
		return authUserName;
	}

	public void setAuthUserName(String authUserName) {
		this.authUserName = authUserName;
	}

	public String getAuthPassword() {
		return authPassword;
	}

	public void setAuthPassword(String authPassword) {
		this.authPassword = authPassword;
	}
}