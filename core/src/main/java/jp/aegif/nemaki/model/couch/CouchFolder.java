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

import java.util.List;

import jp.aegif.nemaki.model.Folder;

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
