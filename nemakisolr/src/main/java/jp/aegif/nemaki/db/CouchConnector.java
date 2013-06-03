package jp.aegif.nemaki.db;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.impl.StdCouchDbInstance;


public class CouchConnector {
	/**
	 * CouchDB connector.
	 */
	private CouchDbConnector connector;

	/**
	 * Host, for instance: 127.0.0.1
	 */
	private String host;

	/**
	 * Repository id, for instance: books
	 */
	
	private String repositoryId;

	/**
	 * Max connections, for instance: 40
	 */
	private int maxConnections;


	
	//Originally this constructor was "init" method called by Spring.
	/**
	 * Initialize this class with host, maxConnections.
	 */
	public CouchConnector() {
		
		//TODO Spring or プロパティファイルに外出し
		PropertyManager manager = new PropertyManagerImpl("nemakisolr.properties");
		host = manager.readValue("couchdb.server.url");
		repositoryId = manager.readValue("couchdb.db.repository");
		maxConnections = Integer.parseInt(manager.readValue("couchdb.maxconnection"));
		
		HttpClient httpClient = new StdHttpClient.Builder().host(host)
				.maxConnections(maxConnections).build();
		CouchDbInstance dbInstance = new StdCouchDbInstance(httpClient);
		
		String repo = "";		
		try{
			repo = repositoryId;
		}catch(Exception ex){
			ex.printStackTrace();
		}
		connector = dbInstance.createConnector(repo, true);
	}

	public CouchDbConnector getConnection() {
		return connector;
	}

	public void setRepositoryId(String repositoryId) {	
		this.repositoryId = repositoryId;
	}
	
	public void setHost(String host) {
		this.host = host;
	}

	public void setMaxConnections(int maxConnections) {
		this.maxConnections = maxConnections;
	}
}
