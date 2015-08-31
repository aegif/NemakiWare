package jp.aegif.nemaki.bjornloka;

import java.net.MalformedURLException;

import org.ektorp.CouchDbConnector;
import org.ektorp.CouchDbInstance;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.impl.StdCouchDbInstance;

public class CouchFactory {
	public static CouchDbInstance createCouchDbInstance(String url){
		HttpClient httpClient;
		try {
			httpClient = new StdHttpClient.Builder().url(url)
					.maxConnections(1000).socketTimeout(100000000).build();
			return new StdCouchDbInstance(httpClient);
		} catch (MalformedURLException e) {
			System.err.println("URL is not well-formed!: " + url);
			e.printStackTrace();
		}
		
		return null;
	}
	
	public static CouchDbConnector createCouchDbConnector(CouchDbInstance dbInstance, String repositoryId){
		CouchDbConnector connector = dbInstance.createConnector(repositoryId, true);
		return connector;
	}
}