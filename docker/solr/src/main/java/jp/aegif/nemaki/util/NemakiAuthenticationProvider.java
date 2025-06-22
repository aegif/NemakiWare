package jp.aegif.nemaki.util;

import java.util.Arrays;
import java.util.List;
import java.util.Map;


import org.apache.chemistry.opencmis.client.bindings.spi.StandardAuthenticationProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class NemakiAuthenticationProvider extends StandardAuthenticationProvider{
	private static final long serialVersionUID = -6364098356084893084L;

	@Override
	public Map<String, List<String>> getHTTPHeaders(String url) {
		Map<String, List<String>> header = super.getHTTPHeaders(url); 
		header.put(Constant.AUTH_TOKEN, Arrays.asList((String)getSession().get(Constant.AUTH_TOKEN)));
		header.put(Constant.AUTH_TOKEN_APP, Arrays.asList((String)getSession().get(Constant.AUTH_TOKEN_APP)));
		return header;
	}

	@Override
	public Element getSOAPHeaders(Object portObject) {
		String token = (String)getSession().get(Constant.AUTH_TOKEN);
		String app = (String)getSession().get(Constant.AUTH_TOKEN_APP);
		
		Element wsseSecurityElement = super.getSOAPHeaders(portObject);
		Document document = wsseSecurityElement.getOwnerDocument();
		
		Element tokenElement = document.createElementNS(WSSE_NAMESPACE, Constant.AUTH_TOKEN);
		tokenElement.appendChild(document.createTextNode(token));
		wsseSecurityElement.appendChild(tokenElement);
		
		Element appElement = document.createElementNS(WSSE_NAMESPACE, Constant.AUTH_TOKEN_APP);
		appElement.appendChild(document.createTextNode(app));
		wsseSecurityElement.appendChild(appElement);
		
		Element tokenObjectElement = document.createElementNS(WSSE_NAMESPACE, "nemaki_auth_token_object");
		wsseSecurityElement.appendChild(tokenObjectElement);
		tokenObjectElement.appendChild(tokenElement);
		tokenObjectElement.appendChild(appElement);
		
		return wsseSecurityElement;
	}
	
}
