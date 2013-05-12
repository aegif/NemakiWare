package jp.aegif.nemaki.model;

import java.util.ArrayList;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.Principal;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;

//This class need to be calculated for the path of inherited ACEs
public class Acl {
	private List<Ace> inheritedAces;
	private List<Ace> localAces;
	
	public Acl(){
		inheritedAces = new ArrayList<Ace>();
		localAces = new ArrayList<Ace>();
	}
	
	public List<Ace> getInheritedAces() {
		return inheritedAces;
	}
	public void setInheritedAces(List<Ace> inheritedAces) {
		this.inheritedAces = inheritedAces;
	}
	public List<Ace> getLocalAces() {
		return localAces;
	}
	public void setLocalAces(List<Ace> localAces) {
		this.localAces = localAces;
	}
	
	public List<Ace> getAllAces(){
		List<Ace> merged = inheritedAces;
		merged.addAll(localAces);
		return merged;
	}
	
	public List<Ace>getPropagatingAces(){
		List<Ace> merged = inheritedAces;
		for(Ace ace : localAces){
			if(!ace.isObjectOnly()) merged.add(ace);
		}
		return merged;
	}
}