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

import jp.aegif.nemaki.model.Policy;

public class CouchPolicy extends CouchContent{

	private static final long serialVersionUID = -3126374299666885813L;
	
	private String policyText;
	private List<String> appliedIds;
	
	public CouchPolicy(){
		super();
	}
	
	public CouchPolicy(Policy p){
		super(p);
		setPolicyText(p.getPolicyText());
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

	public Policy convert(){
		Policy p = new Policy(super.convert());
		p.setPolicyText(getPolicyText());
		p.setAppliedIds(getAppliedIds());
		return p;
	}
}
