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
package jp.aegif.nemaki.model;

import java.util.List;

import jp.aegif.nemaki.model.constant.NodeType;

/**
 * 
 * @author linzhixing
 * 
 */
public class Policy extends Content {

	public final static String TYPE = "policy";
	private String policyText;
	private List<String> appliedIds;

	public Policy(){
		super();
		setType(NodeType.CMIS_POLICY.value());
	}
	
	public Policy(Content c){
		super(c);
		setName(c.getName());
		setDescription(c.getDescription());
		setParentId(c.getParentId());
		setAcl(c.getAcl());
		setAclInherited(c.isAclInherited());
		setSubTypeProperties(c.getSubTypeProperties());
		setAspects(c.getAspects());
		setSecondaryIds(c.getSecondaryIds());
		setObjectType(c.getObjectType());
		setChangeToken(c.getChangeToken());
	}
	
	public String getPolicyText() {
		return policyText;
	}

	public void setPolicyText(String policyText) {
		this.policyText = policyText;
	}

	public List<String> getAppliedIds() {
		return appliedIds;
	}

	public void setAppliedIds(List<String> appliedIds) {
		this.appliedIds = appliedIds;
	}
	
}
