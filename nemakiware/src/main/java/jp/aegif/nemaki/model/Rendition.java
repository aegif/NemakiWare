package jp.aegif.nemaki.model;

import java.io.InputStream;


public class Rendition extends NodeBase{
	public static final String TYPE = "rendition";
	
	private String mimetype;
	private long length;
	private String title;
	private String kind;
	private long height;
	private long width;
	private String renditionDocumentId;
	private InputStream inputStream;

	public Rendition(){
		super();
	}

	public Rendition(NodeBase n){
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}

	public String getMimetype() {
		return mimetype;
	}

	public void setMimetype(String mimetype) {
		this.mimetype = mimetype;
	}

	public long getLength() {
		return length;
	}

	public void setLength(long length) {
		this.length = length;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public long getHeight() {
		return height;
	}

	public void setHeight(long height) {
		this.height = height;
	}

	public long getWidth() {
		return width;
	}

	public void setWidth(long width) {
		this.width = width;
	}

	public String getRenditionDocumentId() {
		return renditionDocumentId;
	}

	public void setRenditionDocumentId(String renditionDocumentId) {
		this.renditionDocumentId = renditionDocumentId;
	}

	public InputStream getInputStream() {
		return inputStream;
	}

	public void setInputStream(InputStream inputStream) {
		this.inputStream = inputStream;
	}
}
