package jp.aegif.nemaki.dao.impl.couch.connector;

import java.net.MalformedURLException;
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

	private static final Log log = LogFactory.getLog(ConnectorPool.class);

	public void init() {
		log.info("CouchDB URL:" + url);
		log.info("Authentication enabled: " + authEnabled);

		//Builder
		try {
			this.builder = new StdHttpClient.Builder()
			.url(url)
			.maxConnections(maxConnections)
			.connectionTimeout(connectionTimeout)
			.socketTimeout(socketTimeout)
			.cleanupIdleConnections(true);
		} catch (MalformedURLException e) {
			log.error("CouchDB URL is not well-formed!: " + url, e);
			e.printStackTrace();
		}
		if(authEnabled){
			builder.username(authUserName).password(authPassword);
		}

		//Create connector(all-repository config)
		initNemakiConfDb();


		//Create connectors
		for(String key : repositoryInfoMap.keys()){
			add(key);
			add(repositoryInfoMap.getArchiveId(key));
		}
	}

	private void initNemakiConfDb(){
		CouchDbInstance dbInstance = new StdCouchDbInstance(builder.build());
		log.info("Checking if Nemaki configuration database exists: " + SystemConst.NEMAKI_CONF_DB);
		boolean dbExists = dbInstance.checkIfDbExists(SystemConst.NEMAKI_CONF_DB);
		log.info("Nemaki configuration database (" + SystemConst.NEMAKI_CONF_DB + ") exists: " + dbExists);
		if(dbExists){
			add(SystemConst.NEMAKI_CONF_DB);
		}else{
			log.info("Nemaki configuration database (" + SystemConst.NEMAKI_CONF_DB + ") not found, creating it.");
			addNemakiConfDb();
			add(SystemConst.NEMAKI_CONF_DB);
			createConfiguration(get(SystemConst.NEMAKI_CONF_DB));
		}
	}

	public CouchDbConnector get(String repositoryId){
		CouchDbConnector connector = pool.get(repositoryId);
		if(connector == null){
			log.error("CouchDbConnector for repository '" + repositoryId + "' cannot be found in the pool!");
			throw new Error("CouchDbConnector for repository:" + repositoryId + " cannot be found!");
		}
		log.info("Retrieved CouchDbConnector for repository '" + repositoryId + "' from pool.");
		return connector;
	}

	public CouchDbConnector add(String repositoryId){
		CouchDbConnector connector = pool.get(repositoryId);
		if(connector == null){
			log.info("No existing connector found for repository '" + repositoryId + "'. Creating a new one.");
			HttpClient httpClient = builder.build();
			CouchDbInstance dbInstance = new StdCouchDbInstance(httpClient);
			try {
				log.info("Attempting to create new CouchDbConnector for repository: " + repositoryId);
				connector = dbInstance.createConnector(repositoryId, true);
				pool.put(repositoryId, connector);
				log.info("Successfully created and pooled new CouchDbConnector for repository: " + repositoryId);
			} catch (Exception e) {
				log.error("Failed to create CouchDbConnector for repository: " + repositoryId, e);
				// Depending on desired behavior, we might re-throw or handle differently
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
