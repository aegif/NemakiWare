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

import java.util.GregorianCalendar;

import jp.aegif.nemaki.model.NodeBase;
import jp.aegif.nemaki.util.constant.NodeType;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.ektorp.support.CouchDbDocument;

@JsonIgnoreProperties(ignoreUnknown=true)
public class CouchNodeBase extends CouchDbDocument{
	private static final long serialVersionUID = 8798101386986624403L;

	protected String type;
	protected GregorianCalendar created;
	protected String creator;
	protected GregorianCalendar modified;
	protected String modifier;
	
	public CouchNodeBase(){
		super();
	}
	
	public CouchNodeBase(NodeBase nb){
		super();
		//CouchDbDocument doesn't allow setId(null)
		if(nb.getId() != null) setId(nb.getId());
		setType(nb.getType());
		setCreated(nb.getCreated());
		setCreator(nb.getCreator());
		setModified(nb.getModified());
		setModifier(nb.getModifier());
	}
	
	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}
	
	@JsonIgnore
	public Boolean isFolder(){
		return (NodeType.CMIS_FOLDER.value().equals(type)) ? true : false;  
	}
	
	@JsonIgnore
	public Boolean isDocument(){
		return (NodeType.CMIS_DOCUMENT.value().equals(type)) ? true : false;
	}
	
	@JsonIgnore
	public Boolean isRelationship(){
		return (NodeType.CMIS_RELATIONSHIP.value().equals(type)) ? true : false;
	}
	
	@JsonIgnore
	public Boolean isPolicy(){
		return (NodeType.CMIS_POLICY.value().equals(type)) ? true : false;
	}

	@JsonIgnore
	public Boolean isContent(){
		return isDocument() || isFolder() || isRelationship() || isPolicy();
	}
	
	@JsonIgnore
	public Boolean isAttachment(){
		return (NodeType.ATTACHMENT.value().equals(type)) ? true : false;
	}
	
	public GregorianCalendar getCreated() {
		return created;
	}

	public void setCreated(GregorianCalendar created) {
		this.created = created;
	}

	public String getCreator() {
		return creator;
	}

	public void setCreator(String creator) {
		this.creator = creator;
	}

	public GregorianCalendar getModified() {
		return modified;
	}

	public void setModified(GregorianCalendar modified) {
		this.modified = modified;
	}

	public String getModifier() {
		return modifier;
	}

	public void setModifier(String modifier) {
		this.modifier = modifier;
	}
	
	public NodeBase convert(){
		NodeBase n = new NodeBase();
		n.setId(getId());
		n.setType(getType());
		n.setCreated(getCreated());
		n.setCreator(getCreator());
		n.setModified(getModified());
		n.setModifier(getModifier());
		return n;
	}
}
