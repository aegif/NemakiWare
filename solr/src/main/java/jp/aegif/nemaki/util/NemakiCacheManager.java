package jp.aegif.nemaki.util;

import java.util.GregorianCalendar;

import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;

import jp.aegif.nemaki.util.yaml.RepositorySetting;

public class NemakiCacheManager {
	private static final Logger logger = LoggerFactory.getLogger(NemakiCacheManager.class);

	String userName = "";
	String password = "";
	String restEndPoint = "";
	String repositoryId = "";

	public NemakiCacheManager(String repositoryId){
		RepositorySetting setting = CmisSessionFactory.getRepositorySettings().get(repositoryId);
		this.userName = setting.getUser();
		 this.password = setting.getPassword();
		 this.restEndPoint = NemakiServer.getRestEndpoint();
		 this.repositoryId = repositoryId;
	}

	public void delete(String objectId, GregorianCalendar date){
		String apiResult = null;
		String restUri = getRestUri(repositoryId);

		try {
			Client c = Client.create();
			c.setConnectTimeout(3 * 1000);
			c.setReadTimeout(5 * 1000);
			c.setFollowRedirects(Boolean.TRUE);
			c.addFilter(new HTTPBasicAuthFilter(userName, password));

			apiResult = c.resource(restUri)
							.path(objectId)
							.queryParam("date", date.toString())
							.accept(MediaType.APPLICATION_JSON_TYPE)
							.delete(String.class);


		} catch (Exception e) {
			logger.error("Cannot connect to Core REST API : {}", restUri, e);
			throw e;
		}


	}

	private  String getRestUri(String repositoryId){
		return restEndPoint + "/repo/" + repositoryId + "/cache/";
	}

}
