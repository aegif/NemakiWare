package jp.aegif.nemaki.model.couch;

import java.util.Map.Entry;

import org.ektorp.Attachment;
import org.springframework.util.CollectionUtils;

import jp.aegif.nemaki.model.Rendition;

public class CouchRendition extends CouchNodeBase{
	private static final long serialVersionUID = -9012249344879285010L;
	private String kind;

	public CouchRendition(){
		super();
	}
	
	public CouchRendition(Rendition r){
		super(r);
		setKind(r.getKind());
	}
	
	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}
	
	public Rendition convert(){
		Rendition r = new Rendition(super.convert());
		r.setKind(getKind());
		if(CollectionUtils.isEmpty(getAttachments())){
			for(Entry<String,Attachment> entry : getAttachments().entrySet()){
				r.setTitle(entry.getKey());
				Attachment a = entry.getValue();
				r.setLength(a.getContentLength());
				r.setMimetype(a.getContentType());
				r.setRenditionDocumentId(getId());
				return r;
			}
		}else{
			//TODO logging
			return null;
		}

		return r;
	}
}