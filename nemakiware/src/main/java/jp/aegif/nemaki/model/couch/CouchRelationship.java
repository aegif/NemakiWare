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
package jp.aegif.nemaki.model.couch;

import jp.aegif.nemaki.model.Relationship;

public class CouchRelationship extends CouchContent {
	private static final long serialVersionUID = 9105018834841974510L;
	
	private String sourceId;
	private String targetId;
	
	public CouchRelationship(){
		super();
	}
	
	public CouchRelationship(Relationship r){
		super(r);
		setSourceId(r.getSourceId());
		setTargetId(r.getTargetId());
	}
	
	public String getSourceId() {
		return sourceId;
	}
	public void setSourceId(String sourceId) {
		this.sourceId = sourceId;
	}
	public String getTargetId() {
		return targetId;
	}
	public void setTargetId(String targetId) {
		this.targetId = targetId;
	}
	
	public Relationship convert(){
		Relationship r = new Relationship(super.convert());
		r.setSourceId(getSourceId());
		r.setTargetId(getTargetId());
		return r;
	}
}
