package jp.aegif.nemaki.util;

import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class NemakiTokenManager {
	private static final Logger logger = LoggerFactory.getLogger(NemakiTokenManager.class);

	private final String restEndpoint;
	private Map<String, Token> tokenMap;

	public NemakiTokenManager() {
		tokenMap = new HashMap<String, Token>();

		restEndpoint = NemakiServer.getRestEndpoint();
	}

	public String register(String repositoryId, String userName, String password) {
		String apiResult = null;
		String restUri = getRestUri(repositoryId);
		try {
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(3))
					.followRedirects(HttpClient.Redirect.NORMAL)
					.build();
			
			String auth = userName + ":" + password;
			String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
			
			String requestUri = restUri + userName + "/register?app=solr";
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(requestUri))
					.timeout(Duration.ofSeconds(5))
					.header("Authorization", "Basic " + encodedAuth)
					.header("Accept", "application/json")
					.GET()
					.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			apiResult = response.body();
		} catch (Exception e) {
			logger.error("Cannot connect to Core REST API : {}", restUri, e);
			throw new RuntimeException(e);
		}

		try {
			JSONObject result = (JSONObject) new JSONParser().parse(apiResult);
			if ("success".equals(result.get("status").toString())) {
				JSONObject value = (JSONObject) result.get("value");
				String token = value.get("token").toString();
				long expiration = (Long) value.get("expiration");
				tokenMap.put(repositoryId, new Token(repositoryId, userName, token, expiration));
				return token;
			}else{
				logger.error("Return failure status from REST API response : {}",restUri  );
			}
		} catch (Exception e) {
			logger.error("Cannot export token from REST API response : {}",restUri, e);
		}

		return null;
	}

	public String getOrRegister(String repositoryId, String userName, String password) {
		String token = getStoredToken(repositoryId);

		if (StringUtils.isBlank(token)) {
			return register(repositoryId, userName, password);
		} else {
			return token;
		}
	}

	public String getStoredToken(String repositoryId) {
		Token token = tokenMap.get(repositoryId);
		if (token != null ) {
			if (token.getExpiration() < System.currentTimeMillis()) {
				logger.info("{}: Basic auth token has expired!", repositoryId );
				return null;
			} else {
				return token.getToken();
			}
		} else {
			return null;
		}
	}

	private String getRestUri(String repositoryId){
		return restEndpoint + "/repo/" + repositoryId + "/authtoken/";
	}


	private class Token {
		private String repositoryId;
		private String userName;
		private String token;
		private long expiration;

		public Token() {

		}

		public Token(String repositoryId, String userName, String token, long expiration) {
			super();
			this.userName = userName;
			this.token = token;
			this.expiration = expiration;
		}

		public String getRepositoryId() {
			return repositoryId;
		}

		public void setRepositoryId(String repositoryId) {
			this.repositoryId = repositoryId;
		}

		public String getUserName() {
			return userName;
		}

		public void setUserName(String userName) {
			this.userName = userName;
		}

		public String getToken() {
			return token;
		}

		public void setToken(String token) {
			this.token = token;
		}

		public long getExpiration() {
			return expiration;
		}

		public void setExpiration(long expiration) {
			this.expiration = expiration;
		}

	}
}
