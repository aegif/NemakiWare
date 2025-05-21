package jp.aegif.nemaki.dao.impl.couch.connector;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.DocumentNotFoundException;
import org.ektorp.ViewQuery;
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

	private static final Log log = LogFactory.getLog(ConnectorPool.class);

	public void init() {
		log.info("CouchDB URL:" + url);

		System.setProperty("org.ektorp.http.IdleConnectionMonitor.enabled", "false");
		System.setProperty("org.apache.http.impl.conn.PoolingClientConnectionManager.idleConnectionMonitor", "false");
		System.setProperty("org.apache.http.impl.conn.PoolingHttpClientConnectionManager.idleConnectionMonitor", "false");
		System.setProperty("org.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES", "true");
		
		disableIdleConnectionMonitor();
		
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					disableIdleConnectionMonitor();
					log.info("Shutdown hook: Successfully disabled Ektorp IdleConnectionMonitor");
				} catch (Exception e) {
					log.warn("Shutdown hook: Failed to disable Ektorp IdleConnectionMonitor: " + e.getMessage());
				}
			}
		});
		
		try {
			this.builder = new StdHttpClient.Builder();
			
			builder.url(url)
			.maxConnections(maxConnections)
			.connectionTimeout(connectionTimeout)
			.socketTimeout(socketTimeout)
			.cleanupIdleConnections(false);
			
			if(authEnabled){
				builder.username(authUserName).password(authPassword);
			}
		} catch (MalformedURLException e) {
			log.error("CouchDB URL is not well-formed!: " + url, e);
			e.printStackTrace();
		}
		
		initNemakiConfDb();
		
		for(String key : repositoryInfoMap.keys()){
			add(key);
			add(repositoryInfoMap.getArchiveId(key));
		}
	}
	
	/**
	 * Disable Ektorp IdleConnectionMonitor to prevent thread leaks
	 */
	private void disableIdleConnectionMonitor() {
		try {
			Class<?> idleMonitorClass = Class.forName("org.ektorp.http.IdleConnectionMonitor");
			
			try {
				java.lang.reflect.Field schedulerField = idleMonitorClass.getDeclaredField("scheduler");
				schedulerField.setAccessible(true);
				Object scheduler = schedulerField.get(null);
				if (scheduler != null) {
					java.lang.reflect.Method shutdownNowMethod = scheduler.getClass().getMethod("shutdownNow");
					shutdownNowMethod.invoke(scheduler);
					
					schedulerField.set(null, null);
					log.info("Successfully shutdown IdleConnectionMonitor scheduler");
				}
			} catch (Exception e) {
				log.warn("Failed to shutdown IdleConnectionMonitor scheduler: " + e.getMessage());
			}
			
			try {
				java.lang.reflect.Field instanceField = idleMonitorClass.getDeclaredField("INSTANCE");
				instanceField.setAccessible(true);
				Object instance = instanceField.get(null);
				if (instance != null) {
					java.lang.reflect.Method shutdownMethod = idleMonitorClass.getDeclaredMethod("shutdown");
					shutdownMethod.setAccessible(true);
					shutdownMethod.invoke(instance);
					
					instanceField.set(null, null);
					log.info("Successfully disabled Ektorp IdleConnectionMonitor instance");
				}
			} catch (Exception e) {
				log.warn("Failed to disable IdleConnectionMonitor instance: " + e.getMessage());
			}
			
			try {
				java.lang.reflect.Field enabledField = idleMonitorClass.getDeclaredField("MONITOR_ENABLED");
				enabledField.setAccessible(true);
				enabledField.set(null, false);
				log.info("Successfully disabled IdleConnectionMonitor MONITOR_ENABLED flag");
			} catch (Exception e) {
				log.warn("Failed to disable IdleConnectionMonitor MONITOR_ENABLED flag: " + e.getMessage());
			}
			
			try {
				Thread.currentThread().setContextClassLoader(new ClassLoader(Thread.currentThread().getContextClassLoader()) {
					@Override
					public Class<?> loadClass(String name) throws ClassNotFoundException {
						if (name.equals("org.ektorp.http.IdleConnectionMonitor")) {
							return null;
						}
						return super.loadClass(name);
					}
				});
				log.info("Successfully overrode ClassLoader for IdleConnectionMonitor");
			} catch (Exception e) {
				log.warn("Failed to override ClassLoader for Ektorp IdleConnectionMonitor: " + e.getMessage());
			}
		} catch (Exception e) {
			log.warn("Failed to completely disable Ektorp IdleConnectionMonitor: " + e.getMessage());
		}
	}

	private void initNemakiConfDb(){
		CouchDbInstance dbInstance = new StdCouchDbInstance(builder.build());
		if(dbInstance.checkIfDbExists(SystemConst.NEMAKI_CONF_DB)){
			add(SystemConst.NEMAKI_CONF_DB);
		}else{
			addNemakiConfDb();
			add(SystemConst.NEMAKI_CONF_DB);
			createConfiguration(get(SystemConst.NEMAKI_CONF_DB));
		}
	}

	public CouchDbConnector get(String repositoryId){
		CouchDbConnector connector = pool.get(repositoryId);
		if(connector == null){
			throw new Error("CouchDbConnector for repository:" + " cannot be found!");
		}

		return connector;
	}

	public CouchDbConnector add(String repositoryId){
		CouchDbConnector connector = pool.get(repositoryId);
		if(connector == null){
			org.ektorp.http.HttpClient httpClient = builder.build();
			CouchDbInstance dbInstance = new StdCouchDbInstance(httpClient);
			connector = dbInstance.createConnector(repositoryId, true);
			pool.put(repositoryId, connector);
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
		CouchDbConnector connector = add(dbName);

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
		CouchDbConnector connector = get(repositoryId);
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
}
