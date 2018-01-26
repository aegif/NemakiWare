package model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import util.DateTimeUtil;

public class User {
	public String id;
	public String password;
	public String name;
	public String firstName;
	public String lastName;
	public String email;
	public boolean isAdmin;
	public GregorianCalendar created;
	public String creator;
	public GregorianCalendar modified;
	public String modifier;

	public Set<String> favorites;

	public User(){

	}

	public User(JsonNode json){
		SimpleDateFormat dateFormat = new SimpleDateFormat();

		this.id = json.get("userId").asText();
		this.name = json.get("userName").asText();
		this.firstName = json.get("firstName").asText();
		this.lastName = json.get("lastName").asText();
		this.email = json.get("email").asText();
		this.isAdmin = json.get("isAdmin").asBoolean();
		this.created = DateTimeUtil.convertStringToCalendar(json.get("created").asText(), Locale.getDefault());
		this.modified = DateTimeUtil.convertStringToCalendar(json.get("modified").asText(), Locale.getDefault());



		JsonNode jfs = json.get("favorites");
		Set<String> ufs = new HashSet<String>();
		if(jfs != null && jfs.isArray()){
			ArrayNode _jfs = (ArrayNode)jfs;
			Iterator<JsonNode> itrJfs = _jfs.iterator();
			while(itrJfs.hasNext()){
				ufs.add(itrJfs.next().textValue());
			}
		}
		this.favorites = ufs;
	}

	public User(String id, String password, String name, String firstName,
			String lastName, String email, boolean isAdmin, Set<String>favorites) {
		super();
		this.id = id;
		this.password = password;
		this.name = name;
		this.firstName = firstName;
		this.lastName = lastName;
		this.email = email;
		this.isAdmin = isAdmin;
		this.favorites = favorites;
	}
}
