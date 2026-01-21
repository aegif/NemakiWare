package jp.aegif.nemaki.bjornloka.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import jp.aegif.nemaki.bjornloka.proxy.ProxyType;

public class Util {
	public static byte[] readAll(InputStream inputStream) throws IOException {
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
		while (true) {
			int len = inputStream.read(buffer);
			if (len < 0) {
				break;
			}
			bout.write(buffer, 0, len);
		}

		byte[] result = bout.toByteArray();
		bout.close();
		return result;
	}

	public static String getCurrentDateString() {
		Date date = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
		return sdf.format(date);
	}
	
	public static ProxyType checkProxyType(String url){
		try {
			URL _url = new URL(url);
			//TODO trim slash
			if(_url.getHost().endsWith("cloudant.com") || 
				_url.getHost().endsWith("cloudant.com/")){
				return ProxyType.CLOUDANT;
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		
		return ProxyType.EKTORP;
	}
	
	public static JsonObject convertToGson(ObjectNode jackson){
		String json = jackson.toString();
		JsonObject gson = new Gson().fromJson(json, JsonObject.class);
		return gson;
	}
	
	public static ObjectNode convertToJackson(JsonObject gson){
		String json = gson.toString();
		ObjectMapper mapper = new ObjectMapper();
		JsonNode jackson;
		try {
			jackson = mapper.readTree(json);
			return (ObjectNode)jackson;
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	//izPack replace "/" to "¥" in arguments
	public static String sanitizeUrl(String url){
		return url.replaceAll("\\\\", "/");
	}
}
