package jp.aegif.nemaki.cmis.factory.auth;

import java.util.Map;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;

import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.server.impl.webservices.AbstractService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.sun.xml.ws.api.handler.MessageHandlerContext;
import com.sun.xml.ws.api.message.Header;

public class NemakiWSAuthHandler extends org.apache.chemistry.opencmis.server.impl.webservices.AuthHandler{
	@Override
	public boolean handleMessage(MessageHandlerContext context) {
		boolean result = super.handleMessage(context);
		
		Map<String, String> callContextMap = (Map<String, String>) context.get(AbstractService.CALL_CONTEXT_MAP);
        Header securityHeader = context.getMessage().getHeaders().get(WSSE_SECURITY, true);

        try {
   		 	JAXBElement<SecurityHeaderType> sht;
        	sht = securityHeader.readAsJAXB(WSSE_CONTEXT.createUnmarshaller());
			extractAuthToken(callContextMap, sht);
		} catch (JAXBException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return result;
	}
	
    protected void extractAuthToken(Map<String, String> callContextMap, JAXBElement<SecurityHeaderType> sht) {
        String token = null;
        String app = null;
        
        for (Object uno : sht.getValue().getAny()) {
    		if(uno instanceof Element && 
    				((Element)uno).getNodeName().equals("nemaki_auth_token_object")){
    			NodeList children = ((Element)uno).getChildNodes();
    			if(children != null){
    				for(int i=0; i<children.getLength(); i++){
    					Node child = children.item(i);
    					
    					if(CallContextKey.AUTH_TOKEN.equals(child.getNodeName())){
    						token = child.getFirstChild().getNodeValue();
    					}else if(CallContextKey.AUTH_TOKEN_APP.equals(child.getNodeName())){
    						app = child.getFirstChild().getNodeValue();
    						continue;
    					}
    				}
    			}
    			
    			break;
    		}
        }

       //Update callContextMap
        if (StringUtils.isNotBlank(token)) {
        	callContextMap.put(CallContextKey.AUTH_TOKEN, token);
        	callContextMap.put(CallContextKey.AUTH_TOKEN_APP, app);
        }
    }
}
