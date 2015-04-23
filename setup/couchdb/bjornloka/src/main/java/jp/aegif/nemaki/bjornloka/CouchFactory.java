package jp.aegif.nemaki.bjornloka;

import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.impl.StdCouchDbInstance;

public class CouchFactory {
	public static CouchDbInstance createCouchDbInstance(String host, int port){
		HttpClient httpClient = new StdHttpClient.Builder().host(host)
				.maxConnections(1000).socketTimeout(1000000).build();
		return new StdCouchDbInstance(httpClient);
	}

	public static CouchDbConnector createCouchDbConnector(String host, int port, String repositoryId){
		CouchDbInstance dbInstance = createCouchDbInstance(host, port);
		CouchDbConnector connector = dbInstance.createConnector(repositoryId, true);
		return connector;
	}
}