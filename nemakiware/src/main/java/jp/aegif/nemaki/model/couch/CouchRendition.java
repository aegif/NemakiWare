/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
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
