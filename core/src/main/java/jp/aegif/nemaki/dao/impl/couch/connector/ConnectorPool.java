package jp.aegif.nemaki.dao.impl.couch.connector;

import java.util.HashMap;
import java.util.Map;

import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.http.StdHttpClient.Builder;
import org.ektorp.impl.StdCouchDbInstance;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

public class ConnectorPool {

	private RepositoryInfoMap repositoryInfoMap;
	private String host;
	private int port;
	private int maxConnections;
	private int connectionTimeout;
	private int socketTimeout;
	private boolean authEnabled;
	private String authUserName;
	private String authPassword;
	
	private Builder builder;
	private Map<String, CouchDbConnector> pool = new HashMap<String, CouchDbConnector>();
	
	public void init() {
		//Builder
		this.builder = new StdHttpClient.Builder()
		.host(host)
		.port(port)
		.maxConnections(maxConnections)
		.connectionTimeout(connectionTimeout)
		.socketTimeout(socketTimeout)
		.cleanupIdleConnections(true);
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
		return pool.get(repositoryId);
	}
	
	public CouchDbConnector add(String repositoryId){
		CouchDbConnector connector = get(repositoryId);
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

	public void setHost(String host) {
		this.host = host;
	}

	public void setPort(int port) {
		this.port = port;
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
