package jp.aegif.nemaki.util;

import java.net.URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.util.impl.PropertyManagerImpl;

public class NemakiServer {
	private static final Logger logger = LoggerFactory.getLogger(NemakiServer.class);

	public static String getRestEndpoint() {
		PropertyManager pm = new PropertyManagerImpl(StringPool.PROPERTIES_NAME);
		String protocol = pm.readValue(PropertyKey.CMIS_SERVER_PROTOCOL);
		String host = pm.readValue(PropertyKey.CMIS_SERVER_HOST);
		String port = pm.readValue(PropertyKey.CMIS_SERVER_PORT);
		String context = pm.readValue(PropertyKey.CMIS_SERVER_CONTEXT);

		try {
			URL url = new URL(protocol, host, Integer.parseInt(port), "");
			return url.toString() + "/" + context + "/rest";
		} catch (Exception e) {
			logger.error("Error occurred during getting REST endpoint.", e);
		}
		return null;
	}

}
