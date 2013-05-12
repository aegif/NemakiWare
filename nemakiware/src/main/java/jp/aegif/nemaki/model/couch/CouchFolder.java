package jp.aegif.nemaki.model.couch;

import java.util.List;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

import jp.aegif.nemaki.model.Folder;
@JsonIgnoreProperties(ignoreUnknown=true)
public class CouchFolder extends CouchContent{
	
	private static final long serialVersionUID = 358898003870344923L;

	private List<String> allowedChildTypeIds;
	private List<String> renditionIds;
	
	public CouchFolder(){
		super();
	}
	
	public CouchFolder(Folder f){
		super(f);
		setAllowedChildTypeIds(f.getAllowedChildTypeIds());
		setRenditionIds(f.getRenditionIds());
	}
	
	public List<String> getAllowedChildTypeIds() {
		return allowedChildTypeIds;
	}

	public void setAllowedChildTypeIds(List<String> allowedChildTypeIds) {
		this.allowedChildTypeIds = allowedChildTypeIds;
	}

	public List<String> getRenditionIds() {
		return renditionIds;
	}

	public void setRenditionIds(List<String> renditionIds) {
		this.renditionIds = renditionIds;
	}

	public Folder convert(){
		Folder f = new Folder(super.convert());
		f.setAllowedChildTypeIds(getAllowedChildTypeIds());
		f.setRenditionIds(getRenditionIds());
		return f;
	}
}
