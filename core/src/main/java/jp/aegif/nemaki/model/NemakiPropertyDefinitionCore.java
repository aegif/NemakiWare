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

import jp.aegif.nemaki.util.constant.NodeType;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;

public class NemakiPropertyDefinitionCore extends NodeBase{
	private String propertyId;
	private PropertyType propertyType;
	private String queryName;
	private Cardinality cardinality;
	private boolean inherited = false;  // CMIS 1.1 inherited flag - true for properties inherited from parent types

	public NemakiPropertyDefinitionCore() {
		super();
		setType(NodeType.PROPERTY_DEFINITION_CORE.value());
	}

	public NemakiPropertyDefinitionCore(NodeBase n) {
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
		
		// CRITICAL FIX FOR CLOUDANT SDK MIGRATION: Preserve _rev field
		// In Ektorp era, _rev was handled automatically by the library
		// With Cloudant SDK, application must manually preserve _rev for subsequent updates
		setRevision(n.getRevision());
		
		// Initialize PropertyDefinition-specific fields to prevent contamination
		this.propertyId = null;        
		this.propertyType = null;      
		this.queryName = null;         
		this.cardinality = null;       
	}

	public NemakiPropertyDefinitionCore(NemakiPropertyDefinition p){
		setType(NodeType.PROPERTY_DEFINITION_CORE.value());
		setPropertyId(p.getPropertyId());
		setPropertyType(p.getPropertyType());
		setQueryName(p.getQueryName());
		setCardinality(p.getCardinality());
		
		// CRITICAL FIX: Set inherited flag based on property namespace
		// CMIS standard properties (cmis:*) are inherited, custom properties are not
		String propertyId = p.getPropertyId();
		if (propertyId != null && propertyId.startsWith("cmis:")) {
			setInherited(true);  // CMIS standard properties are inherited
		} else {
			setInherited(false); // Custom properties are not inherited
		}
	}

	/**
	 * CRITICAL FIX: Constructor for PropertyDefinition<?> to prevent contamination
	 * This constructor handles direct PropertyDefinition objects from OpenCMIS TCK
	 * and ensures no property ID/type contamination from object reuse.
	 */
	public NemakiPropertyDefinitionCore(org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition<?> propertyDefinition) {
		super();
		setType(NodeType.PROPERTY_DEFINITION_CORE.value());
		
		// CRITICAL FIX: Directly extract properties from PropertyDefinition without contamination
		String originalPropertyId = propertyDefinition.getId();
		
		// CRITICAL FIX: Preserve exact property ID and type from source PropertyDefinition
		setPropertyId(originalPropertyId);
		setPropertyType(propertyDefinition.getPropertyType());
		setQueryName(propertyDefinition.getQueryName());
		setCardinality(propertyDefinition.getCardinality());
		
		// CRITICAL FIX: Set inherited flag based on property namespace
		// CMIS standard properties (cmis:*) are inherited, custom properties are not
		if (originalPropertyId != null && originalPropertyId.startsWith("cmis:")) {
			setInherited(true);  // CMIS standard properties are inherited
		} else {
			setInherited(false); // Custom properties are not inherited
		}
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
	
	public boolean isInherited() {
		return inherited;
	}
	
	public void setInherited(boolean inherited) {
		this.inherited = inherited;
	}
}
