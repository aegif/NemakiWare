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

import java.io.InputStream;
import java.util.Map.Entry;

import org.apache.commons.collections.MapUtils;
import org.ektorp.Attachment;
import org.springframework.util.CollectionUtils;

import jp.aegif.nemaki.model.Rendition;

public class CouchRendition extends CouchNodeBase{
	private static final long serialVersionUID = -9012249344879285010L;
	private String mimetype;
	private long length;
	private String title;
	private String kind;
	private long height;
	private long width;
	private String renditionDocumentId;
	private InputStream inputStream;

	public CouchRendition(){
		super();
	}
	
	public CouchRendition(Rendition r){
		super(r);
		setKind(r.getKind());
		setTitle(r.getTitle());
		setHeight(r.getHeight());
		setWidth(r.getWidth());
		setLength(r.getLength());
		setMimetype(r.getMimetype());
	}
	
	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
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

	public Rendition convert(){
		Rendition r = new Rendition(super.convert());
		r.setKind(getKind());
		r.setTitle(getTitle());
		r.setHeight(getHeight());
		r.setWidth(getWidth());
		r.setLength(getLength());
		r.setMimetype(getMimetype());
		r.setRenditionDocumentId(getRenditionDocumentId());
		return r;
	}
}
