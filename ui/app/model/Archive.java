package model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;



public class Archive {
	public String id;
	public String name;
	public String originalId;
	public String parentId;
	public String created;
	public String type;
	public String creator;
	
	public Archive(ObjectNode json){
		
		JsonNode id = json.get("id");
		if(id != null) this.id = id.textValue();
	
		JsonNode parentId = json.get("parentId");
		if(parentId != null) this.parentId = parentId.textValue();
	
		JsonNode created = json.get("created");
		if(created != null) this.created = created.textValue();
	
		JsonNode name = json.get("name");
		if(name != null) this.name = name.textValue();
	
		JsonNode type = json.get("type");
		if(type != null) this.type = type.textValue();
	
		JsonNode creator = json.get("creator");
		if(creator != null) this.creator = creator.textValue();
	}
		
}
