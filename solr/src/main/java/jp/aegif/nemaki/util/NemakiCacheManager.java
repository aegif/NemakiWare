package jp.aegif.nemaki.util;

import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;

// import jakarta.ws.rs.core.MediaType; // Removed due to Jersey 1.x compatibility

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;

import jp.aegif.nemaki.util.yaml.RepositorySetting;

public class NemakiCacheManager {
	private static final Logger logger = LoggerFactory.getLogger(NemakiCacheManager.class);
	
	public static final String DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
	private static DateTimeFormatter dtf = DateTimeFormatter.ofPattern(DATETIME_FORMAT);

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
							.queryParam("date", dtf.format(date.toZonedDateTime()))
							.accept("application/json")
							.delete(String.class);


		} catch (Exception e) {
			logger.error("Cannot connect to Core REST API : {}", restUri, e);
			throw e;
		}


	}


	public void deleteTree(String objectId){
		String apiResult = null;
		String restUri = getRestUri(repositoryId) + "tree/";

		try {
			Client c = Client.create();
			c.setConnectTimeout(3 * 1000);
			c.setReadTimeout(5 * 1000);
			c.setFollowRedirects(Boolean.TRUE);
			c.addFilter(new HTTPBasicAuthFilter(userName, password));

			apiResult = c.resource(restUri)
							.path(objectId)
							.accept("application/json")
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
