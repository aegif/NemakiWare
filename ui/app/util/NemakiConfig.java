package util;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.ctc.wstx.util.StringUtil;

import play.Play;
import play.Configuration;
import constant.PropertyKey;

public class NemakiConfig {

	public static String getValue(String key){
		Configuration configuration = Play.application().configuration();
		return getValue( configuration,  key);
	}

	public static String getValue(Configuration configuration, String key){
		String originalValue = configuration.getString(key);
		List<String> files = configuration.getStringList(PropertyKey.PROPERTY_FILES);

		if(CollectionUtils.isEmpty(files)){
			return originalValue;
		}else{
			//Load property files
			List<Properties>list = new ArrayList<Properties>();
			for(String file : files){
				InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(file);
				Properties properties = new Properties();
				try{
					if(is != null){
						properties.load(is);
						list.add(properties);
					}
				}catch(Exception e){
					e.printStackTrace();
				}
			}

			//Overwrite value
			String value = null;
			for(Properties config : list){
				value = config.getProperty(key);
				if(value != null){
					break;
				}
			}

			if(value == null){
				value = originalValue;
			}

			return value;
		}
	}

	public static List<String> getValues(String key){
		List<String> result = new ArrayList<String>();

		String value = getValue(key);
		if(StringUtils.isNotEmpty(value)){
			String[] values = value.split(",");
			for(String v : values){
				result.add(v.trim());
			}
		}


		return result;
	}

	public static String getLabel(String propertyId, String language){
		String _propertyId = propertyId.replaceAll(":", "-");

		String v = getValue("label-" + _propertyId + "_" + language);
		if(StringUtils.isEmpty(v)){
			v = getValue("label-" + _propertyId);
		}
		if(StringUtils.isEmpty(v)){
			return propertyId;
		}else{
			return v;
		}
	}
	public static boolean isEnableSamlSSO(Configuration configuration){
		String enableSamlSSO = NemakiConfig.getValue(configuration, PropertyKey.SSO_SAML_ATUTHENTICATION_ENABLE);
	    return Boolean.parseBoolean(enableSamlSSO);
	}

	public static String getApplicationBaseUri(Configuration configuration){
		String protocol = NemakiConfig.getValue(configuration, PropertyKey.NEMAKI_UI_URI_PROTOCOL);
		if(protocol == null){
			protocol = "http";
		}

		String host = NemakiConfig.getValue(configuration, PropertyKey.NEMAKI_UI_URI_HOST);
		if(host == null){
			host = NemakiConfig.getValue(configuration, PropertyKey.PLAY_SERVER_HTTP_ADDRESS);
			if(host == null){
				host = "localhost";
			}
		}

		String port = NemakiConfig.getValue(configuration, PropertyKey.NEMAKI_UI_URI_PORT);
		if(port == null){
			port  = NemakiConfig.getValue(configuration, PropertyKey.PLAY_SERVER_HTTP_PORT);
		}
		String ctxPath = NemakiConfig.getValue(configuration, PropertyKey.PLAY_HTTP_CONTEXT);
		if(ctxPath == null){
			ctxPath = "";
		}
        URI baseUri = URI.create(protocol + "://" + host + ( StringUtils.isBlank(port) ? "" : ":" + port ) + ctxPath);
		return baseUri.toString();
	}

	public static String getDefualtRepositoryId(Configuration configuration){
		return NemakiConfig.getValue(configuration, PropertyKey.NEMAKI_DEFAULT_REPOSITRY_ID);
	}

	public static String getDefualtRepositoryId(){
		return NemakiConfig.getValue(PropertyKey.NEMAKI_DEFAULT_REPOSITRY_ID);
	}

	public static String getPlayHttpContext(Configuration configuration){
		return NemakiConfig.getValue(configuration, PropertyKey.PLAY_HTTP_CONTEXT);
	}

	public static String getPlayHttpContext(){
		return NemakiConfig.getValue(PropertyKey.PLAY_HTTP_CONTEXT);
	}

	public static String getSSOLogoutURI(){
		return NemakiConfig.getValue(PropertyKey.SSO_LOGOUT_REDIRECT_URI);
	}

	public static String getRemoteAuthHeader(){
		return NemakiConfig.getValue(PropertyKey.SSO_HEADER_REMOTE_AUTHENTICATED_USER);
	}

	public static String getRemoteUserIdKey(){
		return NemakiConfig.getValue(PropertyKey.SSO_MAPPER_KEY_USERID);
	}

	public static String getSamlIdPMetadataPath(Configuration configuration) {
		return NemakiConfig.getValue(configuration, PropertyKey.SSO_SAML_IDP_METADATA_PATH);
	}
}
