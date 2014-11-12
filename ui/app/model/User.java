package model;

import com.fasterxml.jackson.databind.JsonNode;

public class User {
	public String id;
	public String password;
	public String name;
	public String firstName;
	public String lastName;
	public String email;
	public boolean isAdmin;
	
	public User(){
		
	}
	
	public User(JsonNode json){
		this.id = json.get("userId").asText();
		this.name = json.get("userName").asText();
		this.firstName = json.get("firstName").asText();
		this.lastName = json.get("lastName").asText();
		this.email = json.get("email").asText();
		this.isAdmin = json.get("isAdmin").asBoolean();
	}
}
