package model;

import java.util.List;

import util.Util;

import com.fasterxml.jackson.databind.JsonNode;

public class Group {
	public String id;
	public String name;
	public int usersSize;
	public int groupsSize;
	public List<String> users;
	public List<String> groups;
	
	public Group(){
		
	}

	public Group(JsonNode json){
		this.id = json.get("groupId").asText();
		this.name = json.get("groupName").asText();
		this.usersSize = json.get("usersSize").asInt();
		this.users =  Util.convertToList(json.get("users"));
		this.groupsSize = json.get("groupsSize").asInt();
		this.groups = Util.convertToList(json.get("groups"));
	}
}
