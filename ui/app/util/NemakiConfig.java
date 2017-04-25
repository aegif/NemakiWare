package util;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import play.Play;
import constant.PropertyKey;

public class NemakiConfig {
	public static String getValue(String key){
		String originalValue = Play.application().configuration().getString(key);
		List<String> files = Play.application().configuration().getStringList(PropertyKey.PROPERTY_FILES);

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




	public static String getPlayHttpContext(){
		return NemakiConfig.getValue(PropertyKey.PLAY_HTTP_CONTEXT);
	}


	public static String getSSOLogoutURI(){
		return NemakiConfig.getValue(PropertyKey.SSO_LOGOUT_REDIRECT_URI);
	}

	public static String getRemoteAuthHeader(){
		return NemakiConfig.getValue(PropertyKey.SSO_HEADER_REMOTE_AUTHENTICATED_USER);
	}

}
