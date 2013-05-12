package jp.aegif.nemaki.model.couch;

import jp.aegif.nemaki.model.AttachmentNode;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown=true)
public class CouchAttachmentNode extends CouchNodeBase{
	
	private static final long serialVersionUID = 1984059866949665299L;
	public static final String TYPE = "attachment"; 

	private String name;
	public CouchAttachmentNode(){
		super();
	}
	
	public CouchAttachmentNode(AttachmentNode a){
		super(a);
		setName(a.getName());
	}
	
	/**
	 *Getter & Setter 
	 */
	public String getName(){
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public AttachmentNode convert(){
		AttachmentNode a = new AttachmentNode(super.convert());
		a.setName(getName());
		return a;
	}
}
