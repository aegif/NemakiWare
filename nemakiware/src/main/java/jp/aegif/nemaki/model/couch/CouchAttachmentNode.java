package jp.aegif.nemaki.model.couch;

import java.io.InputStream;

import jp.aegif.nemaki.model.AttachmentNode;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.ektorp.Attachment;

@JsonIgnoreProperties(ignoreUnknown=true)
public class CouchAttachmentNode extends CouchNodeBase{
	
	private static final long serialVersionUID = 1984059866949665299L;
	public static final String TYPE = "attachment"; 
	private static final String ATTACHMENT_NAME = "content";

	private String name;
	private long length;
	private String mimeType;
	private InputStream inputStream;

	
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
	
	public long getLength() {
		return length;
	}

	public void setLength(long length) {
		this.length = length;
	}

	public String getMimeType() {
		return mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public AttachmentNode convert(){
		AttachmentNode a = new AttachmentNode(super.convert());
		
		a.setName(getName());
	
		return a;
	}
}
