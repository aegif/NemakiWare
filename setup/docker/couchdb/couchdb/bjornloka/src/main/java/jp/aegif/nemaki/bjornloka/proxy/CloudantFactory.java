package jp.aegif.nemaki.bjornloka.proxy;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.apache.commons.collections.CollectionUtils;

import com.cloudant.client.api.CloudantClient;
import com.cloudant.client.api.Database;

public class CloudantFactory implements CouchFactory{
	private static final CloudantFactory instance = new CloudantFactory();
	private CloudantFactory(){
		
	}
	
	public static CloudantFactory getInstance(){
		return CloudantFactory.instance;
	}
	
	@Override
	public CloudantProxy createProxy(String url, String repositoryId) {
		CloudantClient client = createClient(url);
		Database database = client.database(repositoryId, false);
		return new CloudantProxy(client, database);
	}

	private static CloudantClient createClient(String url){
		//String loginName = "linzhixing";
		//String password = "tWKgFgxE7jFT";
		String loginName = null;
		String password = null;
		
		URL _url = convert(url);
		if (_url.getUserInfo() != null) {
			String[] userInfoParts = _url.getUserInfo().split(":");
			if (userInfoParts.length == 2) {
				loginName = userInfoParts[0];
				password = userInfoParts[1];
			}
		}
		
		System.out.println("loginName:" + loginName);
		System.out.println("password:" + password);
		String account = loginName; //TODO
		CloudantClient client = new CloudantClient(account, loginName, password); 
		return client;
	}
	
	private static URL convert(String url){
		try {
			return new URL(url);
		} catch (MalformedURLException e) {
			System.err.println("URL is not well-formed: " + url);
			e.printStackTrace();
		}
		return null;
	}
	
	@Override
	public boolean initRepository(String url, String repositoryId, boolean force) {
		CloudantClient client = createClient(url);
		List<String> dbs = client.getAllDbs();
		if(CollectionUtils.isNotEmpty(dbs) && dbs.contains(repositoryId)){
			if(!force){
				return false; //do nothing
			}else{
				client.deleteDB(repositoryId);
			}
		}
		
		client.createDB(repositoryId);
		return true;
	}
	
	
}
