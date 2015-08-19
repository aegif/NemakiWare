package jp.aegif.nemaki.util;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.core.MediaType;

import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;

public class NemakiTokenManager {
	Logger logger = Logger.getLogger(NemakiTokenManager.class);

	private String restResourceUrl;
	private Map<String, Token> tokenMap;

	public NemakiTokenManager() {
		tokenMap = new HashMap<String, Token>();
		restResourceUrl = getRestResourceUrl();
	}

	public String register(String userName, String password) {
		String apiResult = null;
		try {
			Client c = Client.create();
			c.setConnectTimeout(3 * 1000);
			c.setReadTimeout(5 * 1000);
			c.setFollowRedirects(Boolean.TRUE);
			c.addFilter(new HTTPBasicAuthFilter(userName, password));

			apiResult = c.resource(restResourceUrl).path("admin" + "/register")
					.queryParam("app", "ui")
					.accept(MediaType.APPLICATION_JSON_TYPE).get(String.class);
		} catch (Exception e) {
			logger.error("Cannot connect to Core REST API", e);
		}

		try {
			JSONObject result = (JSONObject) new JSONParser().parse(apiResult);
			if ("success".equals(result.get("status").toString())) {
				JSONObject value = (JSONObject) result.get("value");
				String token = value.get("token").toString();
				long expiration = (Long) value.get("expiration");
				tokenMap.put(userName, new Token(userName, token, expiration));
				return token;
			}

		} catch (ParseException e) {
			logger.error("Cannot connect to Core REST API", e);
		}

		return null;
	}

	public String getOrRegister(String userName, String password) {
		String token = getStoredToken(userName);

		if (StringUtils.isBlank(token)) {
			return register(userName, password);
		} else {
			return token;
		}
	}

	public String getStoredToken(String userName) {
		Token token = tokenMap.get(userName);
		if (token != null) {
			if (token.getExpiration() < System.currentTimeMillis()) {
				System.out.println(userName + ":basic auth token has expired");
				return null;
			} else {
				return token.getToken();
			}

		} else {
			return null;
		}
	}

	private String getRestResourceUrl() {
		PropertyManager pm = new PropertyManagerImpl(StringPool.PROPERTIES_NAME);
		String protocol = pm.readValue(PropertyKey.CMIS_SERVER_PROTOCOL);
		String host = pm.readValue(PropertyKey.CMIS_SERVER_HOST);
		String port = pm.readValue(PropertyKey.CMIS_SERVER_PORT);
		String context = pm.readValue(PropertyKey.CMIS_SERVER_CONTEXT);
		String repositoryId = pm.readValue(PropertyKey.CMIS_REPOSITORY_MAIN);

		try {
			URL url = new URL(protocol, host, Integer.parseInt(port), "");
			return url.toString() + "/" + context + "/" + "rest/repo/" + repositoryId + "/authtoken/";
		} catch (NumberFormatException e) {
			e.printStackTrace();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		return null;
	}

	private class Token {
		private String userName;
		private String token;
		private long expiration;

		public Token() {

		}

		public Token(String userName, String token, long expiration) {
			super();
			this.userName = userName;
			this.token = token;
			this.expiration = expiration;
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
