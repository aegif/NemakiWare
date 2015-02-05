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
package jp.aegif.nemaki.cmis.factory.info;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.chemistry.opencmis.commons.enums.CapabilityAcl;
import org.apache.chemistry.opencmis.commons.enums.CapabilityChanges;
import org.apache.chemistry.opencmis.commons.enums.CapabilityContentStreamUpdates;
import org.apache.chemistry.opencmis.commons.enums.CapabilityJoin;
import org.apache.chemistry.opencmis.commons.enums.CapabilityOrderBy;
import org.apache.chemistry.opencmis.commons.enums.CapabilityQuery;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CreatablePropertyTypesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.NewTypeSettableAttributesImpl;
import org.apache.commons.collections.CollectionUtils;

public class Capabilities extends org.apache.chemistry.opencmis.commons.impl.dataobjects.RepositoryCapabilitiesImpl {

	private static final long serialVersionUID = -7037495456587139344L;

	private PropertyManager pm;
	
	@PostConstruct
	public void init() {
		//////////////////////////////////////////////////////////////////
		// Navigation Capabilities
		//////////////////////////////////////////////////////////////////
		// capabiliyGetDescendants
		setSupportsGetDescendants(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_GET_DESCENDENTS)));
		// capabilityGetFolderTree
		setSupportsGetFolderTree(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_GET_FOLDER_TREE)));
		// capabilityOrderBy
		setCapabilityOrderBy(CapabilityOrderBy.fromValue(pm.readValue(PropertyKey.CAPABILITY_ORDER_BY)));
		
		//////////////////////////////////////////////////////////////////
		// Object Capabilities
		//////////////////////////////////////////////////////////////////
		// capabilityContentStreamUpdatability
		setCapabilityContentStreamUpdates(CapabilityContentStreamUpdates.fromValue(pm.readValue(PropertyKey.CAPABILITY_CONTENT_STREAM_UPDATABILITY)));
		//capabilityChanges
		setCapabilityChanges(CapabilityChanges.fromValue(pm.readValue(PropertyKey.CAPABILITY_CHANGES)));
		//capabilityRenditions
		setCapabilityRendition(CapabilityRenditions.fromValue(pm.readValue(PropertyKey.CAPABILITY_RENDITIONS)));

		//////////////////////////////////////////////////////////////////
		// Filing Capabilities
		//////////////////////////////////////////////////////////////////
		//capabilityMultifiling
		setSupportsMultifiling(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_MULTIFILING)));
		//capabilityUnfiling
		setSupportsUnfiling(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_UNFILING)));
		//capabilityVersionSpecificFiling
		setSupportsVersionSpecificFiling(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_VERSION_SPECIFIC_FILING)));

		//////////////////////////////////////////////////////////////////
		// Versioning Capabilities
		//////////////////////////////////////////////////////////////////
		//capabilityPWCUpdatable
		setIsPwcUpdatable(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_PWC_UPDATABLE)));
		//capabilityPWCSearchable
		setIsPwcSearchable(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_PWC_SEARCHABLE)));
		// capabilityAllVersionsSearchable
		setAllVersionsSearchable(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_ALL_VERSION_SEARCHABLE)));

		//////////////////////////////////////////////////////////////////
		// Query Capabilities
		//////////////////////////////////////////////////////////////////		
		// capabilityQuery
		setCapabilityQuery(CapabilityQuery.fromValue(pm.readValue(PropertyKey.CAPABILITY_QUERY)));
		// capabilityJoin
		setCapabilityJoin(CapabilityJoin.fromValue(pm.readValue(PropertyKey.CAPABILITY_JOIN)));

		//////////////////////////////////////////////////////////////////
		// Type Capabilities
		//////////////////////////////////////////////////////////////////		
		//capabilityCreatablPopertyTypes
		CreatablePropertyTypesImpl creatablePropertyTypes = new CreatablePropertyTypesImpl();
		
		List<String> _propertyTypes = pm.readValues(PropertyKey.CAPABILITY_CREATABLE_PROPERTY_TYPES);
		Set<PropertyType> propertyTypes = new HashSet<PropertyType>();
		if(CollectionUtils.isNotEmpty(_propertyTypes)){
			for(String _pt : _propertyTypes){
				propertyTypes.add(PropertyType.fromValue(_pt));
			}
		}
		creatablePropertyTypes.setCanCreate(propertyTypes);
		setCreatablePropertyTypes(creatablePropertyTypes);

		//capabilityNewTypeSettableAttributes
		NewTypeSettableAttributesImpl newTypeSettableAttributes = new NewTypeSettableAttributesImpl();
		
		newTypeSettableAttributes.setCanSetId(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_ID)));
		newTypeSettableAttributes.setCanSetLocalName(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCAL_NAME)));
		newTypeSettableAttributes.setCanSetLocalNamespace(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_LOCAL_NAME_SPACE)));
		newTypeSettableAttributes.setCanSetQueryName(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERY_NAME)));
		newTypeSettableAttributes.setCanSetDisplayName(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_DISPLAY_NAME)));
		newTypeSettableAttributes.setCanSetDescription(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_DESCRIPTION)));
		newTypeSettableAttributes.setCanSetCreatable(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_CREATABLE)));
		newTypeSettableAttributes.setCanSetFileable(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_FILEABLE)));
		newTypeSettableAttributes.setCanSetQueryable(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_QUERYABLE)));
		newTypeSettableAttributes.setCanSetFulltextIndexed(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_FULLTEXT_INDEXED)));
		newTypeSettableAttributes.setCanSetIncludedInSupertypeQuery(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_INCLUDE_IN_SUPERTYPE_QUERY)));
		newTypeSettableAttributes.setCanSetControllablePolicy(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLLABLE_POLICY)));
		newTypeSettableAttributes.setCanSetControllableAcl(Boolean.valueOf(pm.readValue(PropertyKey.CAPABILITY_NEW_TYPE_SETTABLE_ATTRIBUTES_CONTROLLABLE_ACL)));
		
		setNewTypeSettableAttributes(newTypeSettableAttributes);
		
		//////////////////////////////////////////////////////////////////
		// ACL Capabilities
		//////////////////////////////////////////////////////////////////				
		// capabilityACL
		setCapabilityAcl(CapabilityAcl.fromValue(pm.readValue(PropertyKey.CAPABILITY_ACL)));
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.pm = propertyManager;
	}
}
