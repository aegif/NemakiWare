package model;

import com.fasterxml.jackson.databind.JsonNode;


public class Config {
	public String key;
	public String value;
	public boolean isDefault;

	public Config(){

	}
	public Config(JsonNode json){
		this.key = json.get("key").asText();
		this.value = json.get("value").asText();
		this.isDefault = json.get("isDefault").asBoolean();
	}

}
