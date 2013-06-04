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
 *     linzhixing - initial API and implementation
 ******************************************************************************/
/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.model;

import java.io.InputStream;
import java.util.GregorianCalendar;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

/**
 * CMIS content stream (attachment for document)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttachmentNode extends NodeBase {

	public static final String TYPE = "attachment";
	
	private String name;
	private long length;
	private String mimeType;
	private InputStream inputStream;
	
	public AttachmentNode(){
		super();
	}
	
	public AttachmentNode(String name, String mimeType, long length,
			GregorianCalendar created, String creator) {
		super();
		this.name = name;
		this.mimeType = mimeType;
		this.length = length;
		this.created = created;
		this.creator = creator;
	}
	
	public AttachmentNode(NodeBase n){
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}

	
	/**
	 * Getter & Setter
	 */
	public String getName(){
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}

	public String getMimeType() {
		return mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public long getLength() {
		return length;
	}

	public void setLength(long length) {
		this.length = length;
	}

	public InputStream getInputStream() {
		return inputStream;
	}

	public void setInputStream(InputStream inputStream) {
		this.inputStream = inputStream;
	}
}
