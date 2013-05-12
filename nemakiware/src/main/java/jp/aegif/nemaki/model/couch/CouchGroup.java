package jp.aegif.nemaki.model.couch;

import java.util.List;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

import jp.aegif.nemaki.model.Group;
@JsonIgnoreProperties(ignoreUnknown=true)
public class CouchGroup  extends CouchNodeBase{
	
	private static final long serialVersionUID = -5513898484272039889L;
	private String groupId;
	private String name;
	private List<String> users;
	private List<String> groups;
	
	public CouchGroup(){
		super();
	}

	public CouchGroup(Group g){
		super(g);
		setGroupId(g.getGroupId());
		setName(g.getName());
		setUsers(g.getUsers());
		setGroups(g.getGroups());
	}
	
	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<String> getUsers() {
		return users;
	}

	public void setUsers(List<String> users) {
		this.users = users;
	}

	public List<String> getGroups() {
		return groups;
	}

	public void setGroups(List<String> groups) {
		this.groups = groups;
	}

	public Group convert(){
		Group g = new Group(super.convert());
		g.setGroupId(getGroupId());
		g.setName(getName());
		g.setUsers(getUsers());
		g.setGroups(getGroups());
		return g;
	}
}
