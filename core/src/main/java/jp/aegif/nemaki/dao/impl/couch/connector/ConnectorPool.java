package jp.aegif.nemaki.dao.impl.couch.connector;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.DocumentNotFoundException;
import org.ektorp.ViewQuery;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.http.StdHttpClient.Builder;
import org.ektorp.impl.StdCouchDbInstance;
import org.ektorp.support.DesignDocument;
import org.ektorp.support.DesignDocument.View;
import org.ektorp.support.StdDesignDocumentFactory;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.model.couch.CouchConfiguration;
import jp.aegif.nemaki.util.constant.SystemConst;

public class ConnectorPool {

	private RepositoryInfoMap repositoryInfoMap;
	private String url;
	private int maxConnections;
	private int connectionTimeout;
	private int socketTimeout;
	private boolean authEnabled;
	private String authUserName;
	private String authPassword;

	private Builder builder;
	private Map<String, CouchDbConnector> pool = new HashMap<String, CouchDbConnector>();
	private boolean initialized = false;
	private final Object initLock = new Object();

	private static final Log log = LogFactory.getLog(ConnectorPool.class);

	public void init() {
		System.out.println("=== ConnectorPool.init() CALLED ===");
		System.out.println("CouchDB URL from Spring injection: " + url);
		System.out.println("Authentication enabled: " + authEnabled);
		log.info("CouchDB URL:" + url);
		log.info("Authentication enabled: " + authEnabled);
		log.info("ConnectorPool initialized with aggressive lazy connection strategy");
		
		// Log system properties for debugging config loading
		System.out.println("=== Configuration Debug ===");
		System.out.println("nemakiware.properties system property: " + System.getProperty("nemakiware.properties"));
		System.out.println("db.couchdb.url system property: " + System.getProperty("db.couchdb.url"));
		
		// Log system proxy settings
		log.info("System proxy settings:");
		log.info("http.proxyHost: " + System.getProperty("http.proxyHost"));
		log.info("http.proxyPort: " + System.getProperty("http.proxyPort"));
		log.info("http.nonProxyHosts: " + System.getProperty("http.nonProxyHosts"));

		//Builder - prepare but don't connect yet
		try {
			System.out.println("=== Ektorp Builder Configuration ===");
			System.out.println("Builder URL: " + url);
			System.out.println("Builder maxConnections: " + maxConnections);
			System.out.println("Builder connectionTimeout: " + connectionTimeout);
			System.out.println("Builder socketTimeout: " + socketTimeout);
			
			this.builder = new StdHttpClient.Builder()
			.url(url)
			.maxConnections(maxConnections)
			.connectionTimeout(connectionTimeout)
			.socketTimeout(socketTimeout)
			.cleanupIdleConnections(false);  // Fixed: prevents thread leaks and startup failures
			
			System.out.println("StdHttpClient.Builder created successfully");
		} catch (MalformedURLException e) {
			log.error("CouchDB URL is not well-formed!: " + url, e);
			System.out.println("MalformedURLException: " + e.getMessage());
			throw new RuntimeException("CouchDB URL is not well-formed: " + url, e);
		}
		if(authEnabled){
			log.info("Configuring Ektorp authentication - Username: " + authUserName);
			System.out.println("Configuring Ektorp authentication - Username: " + authUserName);
			builder.username(authUserName).password(authPassword);
			log.info("Authentication credentials configured for Ektorp");
			System.out.println("Authentication credentials configured for Ektorp");
		}

		// Mark as initialized immediately to prevent blocking Spring startup
		synchronized (initLock) {
			initialized = true;
		}

		log.info("ConnectorPool configuration completed - CouchDB connections deferred until actual use");
	}

	/**
	 * Ensures that the CouchDB connection has been initialized.
	 * This method is called lazily when the first connector is requested.
	 */
	private void ensureInitialized() {
		// Simple check - init() should have been called during Spring startup
		if (!initialized || builder == null) {
			synchronized (initLock) {
				if (!initialized || builder == null) {
					log.warn("ConnectorPool not properly initialized, calling init() now");
					init(); // This will set up the builder and mark as initialized
				}
			}
		}
	}
	
	private void retryConnectionInBackground() {
		log.info("Starting background CouchDB connection retry process");
		
		// Reset the initialized flag so we can actually connect
		synchronized (initLock) {
			initialized = false;
		}
		
		int maxRetries = 60;
		int retryIntervalMs = 5000; // Longer interval for background retries
		int retryCount = 0;
		boolean connected = false;
		
		while (!connected && retryCount < maxRetries) {
			try {
				log.info("Background connection attempt " + (retryCount + 1) + " of " + maxRetries);
				
				//Create connector(all-repository config)
				initNemakiConfDb();

				//Create connectors for all repositories
				for(String key : repositoryInfoMap.keys()){
					addWithoutLazyInit(key);
					addWithoutLazyInit(repositoryInfoMap.getArchiveId(key));
				}
				
				connected = true;
				synchronized (initLock) {
					initialized = true;
				}
				log.info("Background CouchDB connection successful!");
			} catch (Exception e) {
				retryCount++;
				log.info("Background connection attempt failed (" + retryCount + "/" + maxRetries + "): " + e.getMessage());
				
				if (retryCount < maxRetries) {
					try {
						Thread.sleep(retryIntervalMs);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						log.error("Background retry thread interrupted", ie);
						return;
					}
				} else {
					log.error("Background connection retry failed after " + maxRetries + " attempts");
					return;
				}
			}
		}
	}
	
	/**
	 * Internal add method that doesn't trigger lazy initialization (used during initialization)
	 */
	private CouchDbConnector addWithoutLazyInit(String repositoryId){
		CouchDbConnector connector = pool.get(repositoryId);
		if(connector == null){
			log.info("No existing connector found for repository '" + repositoryId + "'. Creating a new one.");
			log.info("CouchDB URL: " + url);
			log.info("Auth enabled: " + authEnabled);
			log.info("Auth username: " + authUserName);
			log.info("Connection timeout: " + connectionTimeout + "ms");
			log.info("Socket timeout: " + socketTimeout + "ms");
			
			try {
				if (builder == null) {
					log.error("Builder is null! Need to initialize builder first.");
					throw new IllegalStateException("Builder not initialized");
				}
				
				// Debug DNS resolution and test various connection methods
				try {
					URL couchUrl = new URL(url);
					String host = couchUrl.getHost();
					int port = couchUrl.getPort() != -1 ? couchUrl.getPort() : couchUrl.getDefaultPort();
					
					System.out.println("=== CONNECTION DEBUG (addWithoutLazyInit) ===");
					System.out.println("CouchDB URL: " + url);
					System.out.println("Parsed host: " + host);
					System.out.println("Parsed port: " + port);
					log.info("=== CONNECTION DEBUG (addWithoutLazyInit) ===");
					log.info("Resolving hostname: " + host);
					InetAddress addr = InetAddress.getByName(host);
					System.out.println("Resolved " + host + " to: " + addr.getHostAddress());
					log.info("Resolved " + host + " to: " + addr.getHostAddress());
					
					// Test 1: Direct socket connection to IP
					log.info("Test 1: Direct socket to IP " + addr.getHostAddress() + ":" + port);
					try (Socket socket = new Socket()) {
						socket.connect(new InetSocketAddress(addr.getHostAddress(), port), 5000);
						log.info("✓ Direct socket to IP successful");
					} catch (Exception se) {
						log.error("✗ Direct socket to IP failed: " + se.getMessage());
					}
					
					// Test 2: Direct socket connection to hostname
					log.info("Test 2: Direct socket to hostname " + host + ":" + port);
					try (Socket socket = new Socket()) {
						socket.connect(new InetSocketAddress(host, port), 5000);
						log.info("✓ Direct socket to hostname successful");
					} catch (Exception se) {
						log.error("✗ Direct socket to hostname failed: " + se.getMessage());
					}
					
					// Test 3: Check Java system properties
					log.info("=== JAVA NETWORK PROPERTIES ===");
					log.info("java.net.useSystemProxies: " + System.getProperty("java.net.useSystemProxies"));
					log.info("http.proxyHost: " + System.getProperty("http.proxyHost"));
					log.info("http.proxyPort: " + System.getProperty("http.proxyPort"));
					log.info("http.nonProxyHosts: " + System.getProperty("http.nonProxyHosts"));
					log.info("networkaddress.cache.ttl: " + System.getProperty("networkaddress.cache.ttl"));
					
				} catch (Exception e) {
					log.error("Connection debug failed: " + e.getMessage(), e);
				}
				
				// Log the exact URL being used
				log.info("Building HttpClient with URL: " + url);
				HttpClient httpClient = builder.build();
				log.info("HttpClient created successfully");
				
				// Try to get the actual connection URL from the client if possible
				log.info("HttpClient class: " + httpClient.getClass().getName());
				
				CouchDbInstance dbInstance = new StdCouchDbInstance(httpClient);
				log.info("CouchDbInstance created successfully");
				
				// Create connector with retry mechanism
				try {
					connector = createConnectorWithRetry(dbInstance, repositoryId);
					pool.put(repositoryId, connector);
					log.info("Successfully created and pooled new CouchDbConnector for repository: " + repositoryId);
				} catch (Exception e) {
					log.error("Failed to create connector after retries: " + e.getMessage(), e);
					throw e; // Re-throw to maintain error handling
				}
			} catch (Exception e) {
				log.error("Failed to create CouchDbConnector for repository: " + repositoryId + 
				         " - Error type: " + e.getClass().getName() + 
				         " - Message: " + e.getMessage(), e);
				throw new RuntimeException("Failed to create CouchDbConnector for repository: " + repositoryId, e);
			}
		} else {
			log.info("Found existing connector for repository '" + repositoryId + "' in pool.");
		}

		return connector;
	}

	private void initNemakiConfDb(){
		log.info("Initializing Nemaki configuration database connection");
		
		try {
			// Try to create connector directly - if database doesn't exist, it will be created
			addWithoutLazyInit(SystemConst.NEMAKI_CONF_DB);
			log.info("Nemaki configuration database connection established");
			
			// Try to ensure configuration exists
			try {
				createConfiguration(pool.get(SystemConst.NEMAKI_CONF_DB));
			} catch (Exception e) {
				log.warn("Failed to verify/create configuration, but continuing: " + e.getMessage());
			}
			
		} catch (Exception e) {
			log.warn("Failed to connect to Nemaki configuration database, creating setup: " + e.getMessage());
			
			// If direct connection fails, try the traditional approach
			HttpClient httpClient = builder.build();
			CouchDbInstance dbInstance = new StdCouchDbInstance(httpClient);
			
			// Check if the configuration database exists
			boolean dbExists = dbInstance.checkIfDbExists(SystemConst.NEMAKI_CONF_DB);
			log.info("Nemaki configuration database (" + SystemConst.NEMAKI_CONF_DB + ") exists: " + dbExists);
			
			if(dbExists){
				addWithoutLazyInit(SystemConst.NEMAKI_CONF_DB);
			}else{
				log.info("Nemaki configuration database (" + SystemConst.NEMAKI_CONF_DB + ") not found, creating it.");
				addDb(SystemConst.NEMAKI_CONF_DB);
				addConfigurationView(SystemConst.NEMAKI_CONF_DB);
				createConfiguration(pool.get(SystemConst.NEMAKI_CONF_DB));
			}
		}
		
		log.info("Nemaki configuration database initialization completed");
	}

	public CouchDbConnector get(String repositoryId){
		ensureInitialized();
		
		CouchDbConnector connector = pool.get(repositoryId);
		if(connector == null){
			// Instead of throwing an error, create the connector on-demand
			log.info("CouchDbConnector for repository '" + repositoryId + "' not found in pool, creating on-demand");
			return add(repositoryId);
		}
		log.info("Retrieved CouchDbConnector for repository '" + repositoryId + "' from pool.");
		return connector;
	}

	public CouchDbConnector add(String repositoryId){
		ensureInitialized();
		CouchDbConnector connector = pool.get(repositoryId);
		if(connector == null){
			log.info("No existing connector found for repository '" + repositoryId + "'. Creating a new one.");
			log.info("CouchDB URL: " + url);
			log.info("Auth enabled: " + authEnabled);
			log.info("Auth username: " + authUserName);
			log.info("Connection timeout: " + connectionTimeout + "ms");
			log.info("Socket timeout: " + socketTimeout + "ms");
			
			try {
				if (builder == null) {
					log.error("Builder is null! Need to initialize builder first.");
					throw new IllegalStateException("Builder not initialized");
				}
				
				// Debug DNS resolution and test various connection methods
				try {
					URL couchUrl = new URL(url);
					String host = couchUrl.getHost();
					int port = couchUrl.getPort() != -1 ? couchUrl.getPort() : couchUrl.getDefaultPort();
					
					log.info("=== CONNECTION DEBUG ===");
					log.info("Resolving hostname: " + host);
					InetAddress addr = InetAddress.getByName(host);
					log.info("Resolved " + host + " to: " + addr.getHostAddress());
					
					// Test 1: Direct socket connection to IP
					log.info("Test 1: Direct socket to IP " + addr.getHostAddress() + ":" + port);
					try (Socket socket = new Socket()) {
						socket.connect(new InetSocketAddress(addr.getHostAddress(), port), 5000);
						log.info("✓ Direct socket to IP successful");
					} catch (Exception se) {
						log.error("✗ Direct socket to IP failed: " + se.getMessage());
					}
					
					// Test 2: Direct socket connection to hostname
					log.info("Test 2: Direct socket to hostname " + host + ":" + port);
					try (Socket socket = new Socket()) {
						socket.connect(new InetSocketAddress(host, port), 5000);
						log.info("✓ Direct socket to hostname successful");
					} catch (Exception se) {
						log.error("✗ Direct socket to hostname failed: " + se.getMessage());
					}
					
					// Test 3: Check Java system properties
					log.info("=== JAVA NETWORK PROPERTIES ===");
					log.info("java.net.useSystemProxies: " + System.getProperty("java.net.useSystemProxies"));
					log.info("http.proxyHost: " + System.getProperty("http.proxyHost"));
					log.info("http.proxyPort: " + System.getProperty("http.proxyPort"));
					log.info("http.nonProxyHosts: " + System.getProperty("http.nonProxyHosts"));
					log.info("networkaddress.cache.ttl: " + System.getProperty("networkaddress.cache.ttl"));
					
				} catch (Exception e) {
					log.error("Connection debug failed: " + e.getMessage(), e);
				}
				
				// Log the exact URL being used
				log.info("Building HttpClient with URL: " + url);
				HttpClient httpClient = builder.build();
				log.info("HttpClient created successfully");
				
				// Try to get the actual connection URL from the client if possible
				log.info("HttpClient class: " + httpClient.getClass().getName());
				
				CouchDbInstance dbInstance = new StdCouchDbInstance(httpClient);
				log.info("CouchDbInstance created successfully");
				
				// Create connector with retry mechanism
				try {
					connector = createConnectorWithRetry(dbInstance, repositoryId);
					pool.put(repositoryId, connector);
					log.info("Successfully created and pooled new CouchDbConnector for repository: " + repositoryId);
				} catch (Exception e) {
					log.error("Failed to create connector after retries: " + e.getMessage(), e);
					throw e; // Re-throw to maintain error handling
				}
			} catch (Exception e) {
				log.error("Failed to create CouchDbConnector for repository: " + repositoryId + 
				         " - Error type: " + e.getClass().getName() + 
				         " - Message: " + e.getMessage(), e);
				throw new RuntimeException("Failed to create CouchDbConnector for repository: " + repositoryId, e);
			}
		} else {
			log.info("Found existing connector for repository '" + repositoryId + "' in pool.");
		}

		return connector;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public void setUrl(String url) {
		this.url = url;
	}
	public String getUrl(){
		return this.url;
	}

	public void setMaxConnections(int maxConnections) {
		this.maxConnections = maxConnections;
	}

	public void setConnectionTimeout(int connectionTimeout) {
		this.connectionTimeout = connectionTimeout;
	}

	public void setSocketTimeout(int socketTimeout) {
		this.socketTimeout = socketTimeout;
	}

	public void setAuthEnabled(boolean authEnabled) {
		this.authEnabled = authEnabled;
	}

	public void setAuthUserName(String authUserName) {
		this.authUserName = authUserName;
	}

	public void setAuthPassword(String authPassword) {
		this.authPassword = authPassword;
	}

	public void setBuilder(Builder builder) {
		this.builder = builder;
	}

	public void setPool(Map<String, CouchDbConnector> pool) {
		this.pool = pool;
	}

	private void addNemakiConfDb(){
		final String dbName = SystemConst.NEMAKI_CONF_DB;
		addDb(dbName);
		addConfigurationView(dbName);
	}

	protected void addDb(String dbName){
		// add connector (or create if not exist)
		CouchDbConnector connector = addWithoutLazyInit(dbName);

		// add design doc
		StdDesignDocumentFactory factory = new StdDesignDocumentFactory();

		DesignDocument designDoc;
		try{
			designDoc = factory.getFromDatabase(connector, "_design/_repo");
		}catch(DocumentNotFoundException e){
			designDoc = factory.newDesignDocumentInstance();
			designDoc.setId("_design/_repo");
			connector.create(designDoc);
		}
	}

	private void addConfigurationView(String repositoryId){
		addView(repositoryId, "configuration", "function(doc) { if (doc.type == 'configuration')  emit(doc._id, doc) }");
	}

	private void addView(String repositoryId, String viewName, String map){
		addView(repositoryId, viewName, map, false);
	}

	private void addView(String repositoryId, String viewName, String map, boolean force){
		CouchDbConnector connector = pool.get(repositoryId);
		StdDesignDocumentFactory factory = new StdDesignDocumentFactory();
		DesignDocument designDoc = factory.getFromDatabase(connector, "_design/_repo");

		if(force || !designDoc.containsView(viewName)){
			designDoc.addView(viewName, new View(map));
			connector.update(designDoc);
		}
	}

	private void createConfiguration(CouchDbConnector connector){
		List<CouchConfiguration> list = connector.queryView(new ViewQuery().designDocId("_design/_repo").viewName("configuration"), CouchConfiguration.class);
		if(CollectionUtils.isEmpty(list)){
			Configuration configuration = new Configuration();
			connector.create(new CouchConfiguration(configuration));
		}
	}
	
	/**
	 * Create CouchDB connector with retry mechanism for startup timing issues
	 */
	private CouchDbConnector createConnectorWithRetry(CouchDbInstance dbInstance, String repositoryId) {
		int maxRetries = 30;
		int retryDelayMs = 5000; // 5 seconds between retries
		
		System.out.println("=== createConnectorWithRetry CALLED for repository: " + repositoryId + " ===");
		log.info("=== createConnectorWithRetry CALLED for repository: " + repositoryId + " ===");
		
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				System.out.println("Creating CouchDB connector (attempt " + attempt + "/" + maxRetries + ") for repository: " + repositoryId);
				log.info("Creating CouchDB connector (attempt " + attempt + "/" + maxRetries + ") for repository: " + repositoryId);
				
				// Add detailed debugging before creating connector
				if (attempt == 1) {
					System.out.println("=== Ektorp Connection Attempt Debug ===");
					try {
						// Test if we can make a simple HTTP connection to the same URL
						java.net.URL testUrl = new java.net.URL("http://localhost:5984");
						java.net.HttpURLConnection testConn = (java.net.HttpURLConnection) testUrl.openConnection();
						testConn.setConnectTimeout(5000);
						testConn.setRequestMethod("GET");
						int responseCode = testConn.getResponseCode();
						System.out.println("Raw HttpURLConnection to " + testUrl + " response: " + responseCode);
						testConn.disconnect();
						
						// Debug Ektorp CouchDbInstance and HttpClient settings
						System.out.println("=== Ektorp Internal Configuration Debug ===");
						System.out.println("CouchDbInstance class: " + dbInstance.getClass().getName());
						
						// Try to access internal HttpClient if possible
						if (dbInstance instanceof org.ektorp.impl.StdCouchDbInstance) {
							System.out.println("StdCouchDbInstance detected");
							try {
								// Use reflection to get internal HttpClient details
								java.lang.reflect.Field httpClientField = org.ektorp.impl.StdCouchDbInstance.class.getDeclaredField("client");
								httpClientField.setAccessible(true);
								Object internalClient = httpClientField.get(dbInstance);
								System.out.println("Internal HttpClient: " + internalClient.getClass().getName());
								
								// Check if it's StdHttpClient and get its URL
								if (internalClient instanceof org.ektorp.http.StdHttpClient) {
									java.lang.reflect.Field urlField = org.ektorp.http.StdHttpClient.class.getDeclaredField("url");
									urlField.setAccessible(true);
									String clientUrl = (String) urlField.get(internalClient);
									System.out.println("Ektorp HttpClient URL: " + clientUrl);
									
									// Get connection timeout
									java.lang.reflect.Field connTimeoutField = org.ektorp.http.StdHttpClient.class.getDeclaredField("connectionTimeout");
									connTimeoutField.setAccessible(true);
									int connTimeout = connTimeoutField.getInt(internalClient);
									System.out.println("Ektorp connection timeout: " + connTimeout);
									
									// Get socket timeout
									java.lang.reflect.Field sockTimeoutField = org.ektorp.http.StdHttpClient.class.getDeclaredField("socketTimeout");
									sockTimeoutField.setAccessible(true);
									int sockTimeout = sockTimeoutField.getInt(internalClient);
									System.out.println("Ektorp socket timeout: " + sockTimeout);
								}
							} catch (Exception reflectEx) {
								System.out.println("Reflection debug failed: " + reflectEx.getMessage());
							}
						}
						
						// Try to call checkIfDbExists to see what exactly fails
						System.out.println("Testing dbInstance.checkIfDbExists...");
						try {
							boolean exists = dbInstance.checkIfDbExists(repositoryId);
							System.out.println("checkIfDbExists result: " + exists);
						} catch (Exception checkEx) {
							System.out.println("checkIfDbExists failed: " + checkEx.getClass().getName() + " - " + checkEx.getMessage());
							if (checkEx.getCause() != null) {
								System.out.println("Underlying cause: " + checkEx.getCause().getClass().getName() + " - " + checkEx.getCause().getMessage());
							}
						}
						
					} catch (Exception testEx) {
						System.out.println("Raw HttpURLConnection failed: " + testEx.getMessage());
					}
				}
				
				CouchDbConnector connector = dbInstance.createConnector(repositoryId, true);
				System.out.println("Connector creation successful for repository: " + repositoryId);
				log.info("Connector creation successful for repository: " + repositoryId);
				return connector; // Success - return the connector
				
			} catch (Exception e) {
				System.out.println("Connector creation failed: " + e.getClass().getName() + " - " + e.getMessage());
				log.warn("Connector creation attempt " + attempt + " failed: " + e.getMessage());
				
				if (attempt < maxRetries) {
					log.info("Waiting " + retryDelayMs + "ms before retry...");
					try {
						Thread.sleep(retryDelayMs);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("Connector creation retry interrupted", ie);
					}
				} else {
					log.error("All " + maxRetries + " connector creation attempts failed");
					throw new RuntimeException("Failed to create CouchDB connector after " + maxRetries + " attempts", e);
				}
			}
		}
		
		// This should never be reached due to the exception thrown above
		throw new RuntimeException("Unexpected state in createConnectorWithRetry");
	}
}
