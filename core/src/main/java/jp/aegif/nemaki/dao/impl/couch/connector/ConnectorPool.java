package jp.aegif.nemaki.dao.impl.couch.connector;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.http.StdHttpClient.Builder;
import org.ektorp.impl.StdCouchDbInstance;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

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
	
	private Logger logger = Logger.getLogger(ConnectorPool.class);
	
	public void init() {
		//Builder
		try {
			this.builder = new StdHttpClient.Builder()
			.url(url)
			.maxConnections(maxConnections)
			.connectionTimeout(connectionTimeout)
			.socketTimeout(socketTimeout)
			.cleanupIdleConnections(true);
		} catch (MalformedURLException e) {
			logger.error("CouchDB URL is not well-formed!: " + url, e);
			e.printStackTrace();
		}
		if(authEnabled){
			builder.username(authUserName).password(authPassword);
		}
		
		//Create connectors
		for(String key : repositoryInfoMap.keys()){
			add(key);
			add(repositoryInfoMap.getArchiveId(key));
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
			HttpClient httpClient = builder.build();
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
	
	
}
