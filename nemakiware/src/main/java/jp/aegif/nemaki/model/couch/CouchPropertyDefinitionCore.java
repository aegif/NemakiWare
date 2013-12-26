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

import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;

public class CouchPropertyDefinitionCore extends CouchNodeBase{
	
	private static final long serialVersionUID = -213127366706433797L;
	
	private String propertyId;
	private PropertyType propertyType;
	private String queryName;
	private Cardinality cardinality;
	
	public CouchPropertyDefinitionCore(){
		super();	
	}
	
	public CouchPropertyDefinitionCore(NemakiPropertyDefinitionCore np){
		super(np);
		setPropertyId(np.getPropertyId());
		setPropertyType(np.getPropertyType());
		setQueryName(np.getQueryName());
		setCardinality(np.getCardinality());
	}
	
	public String getPropertyId() {
		return propertyId;
	}
	public void setPropertyId(String propertyId) {
		this.propertyId = propertyId;
	}
	public PropertyType getPropertyType() {
		return propertyType;
	}
	public void setPropertyType(PropertyType propertyType) {
		this.propertyType = propertyType;
	}
	public String getQueryName() {
		return queryName;
	}
	public void setQueryName(String queryName) {
		this.queryName = queryName;
	}
	public Cardinality getCardinality() {
		return cardinality;
	}
	public void setCardinality(Cardinality cardinality) {
		this.cardinality = cardinality;
	}
	
	public NemakiPropertyDefinitionCore convert(){
		NemakiPropertyDefinitionCore p = new NemakiPropertyDefinitionCore(super.convert());
		
		p.setPropertyId(getPropertyId());
		p.setQueryName(getQueryName());
		p.setPropertyType(getPropertyType());
		p.setCardinality(getCardinality());
		return p;
	}
}
