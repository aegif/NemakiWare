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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jp.aegif.nemaki.util.constant.NodeType;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.lucene.document.DateTools.Resolution;

public class NemakiPropertyDefinition extends NodeBase {
	private String detailNodeId;

	// Attributes common
	private String propertyId;
	private String localName;
	private String localNameSpace;
	private String queryName;
	private String displayName;
	private String description;
	private PropertyType propertyType;
	private Cardinality cardinality;
	private Updatability updatability;
	private boolean required;
	private boolean queryable;
	private boolean orderable;
	private List<Choice> choices;
	private boolean openChoice;
	private List<Object> defaultValue;
	private boolean inherited = false;  // CMIS 1.1 inherited flag - true for properties inherited from parent types

	// Attributes specific to Integer
	private Long minValue;
	private Long maxValue;

	// Attributes specific to DateTime
	private Resolution resolution;

	// Attributes specific to Decimal
	private DecimalPrecision decimalPrecision;
	private BigDecimal decimalMinValue;
	private BigDecimal decimalMaxValue;

	// Attributes specific to String
	private Long maxLength;

	public NemakiPropertyDefinition() {
		super();
		setType(NodeType.TYPE_DEFINITION.value());
		
	}

	public NemakiPropertyDefinition(NodeBase n) {
		
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}

	public NemakiPropertyDefinition(NemakiPropertyDefinitionCore core, NemakiPropertyDefinitionDetail detail){
		//TODO Output error when core and detail don't match


		setId(detail.getId());
		setType(NodeType.TYPE_DEFINITION.value());
		setCreated(detail.getCreated());
		setCreator(detail.getCreator());
		setModified(detail.getModified());
		setModifier(detail.getModifier());

		setDetailNodeId(detail.getId());

		// CRITICAL FIX: Use detail's localName as the correct property ID instead of contaminated core.propertyId
		// The core object is being reused and contains contaminated property IDs from previous properties
		String intendedPropertyId = determineCorrectPropertyId(detail, core);
		setPropertyId(intendedPropertyId);
		
		setLocalName(detail.getLocalName());
		setLocalNameSpace(detail.getLocalNameSpace());
		setQueryName(core.getQueryName());
		setDisplayName(detail.getDisplayName());
		setDescription(detail.getDescription());
		setPropertyType(core.getPropertyType());
		setCardinality(core.getCardinality());
		setUpdatability(detail.getUpdatability());
		setRequired(detail.isRequired());
		setQueryable(detail.isQueryable());
		setOrderable(detail.isOrderable());
		setChoices(detail.getChoices());
		setOpenChoice(detail.isOpenChoice());
		setDefaultValue(detail.getDefaultValue());

		setMinValue(detail.getMinValue());
		setMaxValue(detail.getMaxValue());
		setResolution(detail.getResolution());
		setDecimalPrecision(detail.getDecimalPrecision());
		setDecimalMinValue(detail.getDecimalMinValue());
		setDecimalMaxValue(detail.getDecimalMaxValue());
		setMaxLength(detail.getMaxLength());
		
		// CRITICAL FIX: Set inherited flag from core
		setInherited(core.isInherited());
	}
	
	/**
	 * CRITICAL FIX: Safety contamination check for PropertyDefinition<?> constructor
	 * This simplified version handles cases where we only have the PropertyDefinition object
	 * without separate Core/Detail objects.
	 * 
	 * @param originalPropertyId The property ID from the PropertyDefinition object
	 * @param propertyDefinition The source PropertyDefinition object
	 * @return The corrected property ID that should be used
	 */
	private String applySafetyContaminationCheck(String originalPropertyId, PropertyDefinition<?> propertyDefinition) {
		
		
		// STRATEGY 1: Check if this looks like a namespace property (contains colon)
		if (originalPropertyId != null && originalPropertyId.contains(":")) {
			
			// STRATEGY 1a: All namespace properties should preserve their original ID
			// This includes CMIS standard properties (cmis:*) and custom properties (custom:*, vendor:*, tck:*, etc.)
			return originalPropertyId;
		}
		
		// STRATEGY 2: Use LocalName as fallback if it looks like a proper property ID
		String localName = propertyDefinition.getLocalName();
		if (localName != null && localName.contains(":")) {
			return localName;
		}
		
		// STRATEGY 3: Last resort - return original ID with warning
		return originalPropertyId;
	}
	
	/**
	 * CRITICAL FIX: Determine the correct property ID to prevent contamination
	 * from PropertyCore reuse during type reconstruction.
	 * 
	 * The issue: PropertyDefinitionCore may have been reused with a different property ID
	 * (e.g., custom:boolean gets assigned cmis:name's PropertyCore), causing contamination.
	 * 
	 * @param detail The property detail containing the original intended property metadata
	 * @param core The property core that may contain a contaminated property ID
	 * @return The correct property ID that should be used for this property
	 */
	private String determineCorrectPropertyId(NemakiPropertyDefinitionDetail detail, NemakiPropertyDefinitionCore core) {
		
		// CRITICAL FIX: Custom namespace properties protection (CMIS standard compliant)
		// All custom namespace properties (custom:, vendor:, tck:, etc.) should preserve their original ID
		if (detail != null && detail.getLocalName() != null && detail.getLocalName().contains(":") && !detail.getLocalName().startsWith("cmis:")) {
			return detail.getLocalName(); // Preserve custom namespace properties
		}
		
		// STRATEGY 1: Use detail's localName as the intended property ID
		// For custom properties like tck:boolean, the localName should contain the correct ID
		if (detail != null) {
			String localName = detail.getLocalName();
			if (localName != null && !localName.trim().isEmpty()) {
				// Check if this looks like a proper property ID (contains namespace)
				if (localName.contains(":")) {
					return localName;
				}
			}
			
			// STRATEGY 2: Use detail's displayName as fallback
			String displayName = detail.getDisplayName();
			if (displayName != null && !displayName.trim().isEmpty()) {
				if (displayName.contains(":")) {
					return displayName;
				}
			}
		}
		
		// STRATEGY 3: For CMIS system properties, core.getPropertyId() should be correct
		if (core != null) {
			String corePropertyId = core.getPropertyId();
			if (corePropertyId != null && corePropertyId.startsWith("cmis:")) {
				return corePropertyId;
			}
		}
		
		// STRATEGY 4: Safe fallback - use core propertyId as last resort
		// CRITICAL FIX: Only use core.getPropertyId() if no other options and validate it's not contaminated
		if (core != null) {
			String corePropertyId = core.getPropertyId();
			if (corePropertyId != null && !corePropertyId.trim().isEmpty()) {
				// Validate it's not obviously contaminated (custom namespace properties should not be in CMIS core)
				// Generic check: if detail has custom namespace but core has CMIS namespace, it's contaminated
				if (detail != null && detail.getLocalName() != null && 
					detail.getLocalName().contains(":") && !detail.getLocalName().startsWith("cmis:") &&
					corePropertyId.startsWith("cmis:")) {
					// This is contamination - custom property got CMIS core
					return detail.getLocalName(); // Use detail's correct namespace ID
				}
				return corePropertyId;
			}
		}
		
		// STRATEGY 5: Ultimate fallback - return null to indicate failure
		// This will cause the calling code to handle the error appropriately
		return null;
	}

	public NemakiPropertyDefinition(PropertyDefinition<?> propertyDefinition) {
		
		// CRITICAL FIX: Apply contamination prevention to this constructor too
		String originalPropertyId = propertyDefinition.getId();
		String correctedPropertyId = applySafetyContaminationCheck(originalPropertyId, propertyDefinition);
		
		setPropertyId(correctedPropertyId);
		setLocalName(propertyDefinition.getLocalName());
		setLocalNameSpace(propertyDefinition.getLocalNamespace());
		setQueryName(propertyDefinition.getQueryName());
		setDisplayName(propertyDefinition.getQueryName());
		setPropertyType(propertyDefinition.getPropertyType());
		setCardinality(propertyDefinition.getCardinality());
		setUpdatability(propertyDefinition.getUpdatability());
		// CRITICAL FIX: Add null safety for Boolean PropertyDefinition methods
		// OpenCMIS JSONConverter may return null Boolean objects instead of primitive booleans
		setRequired(propertyDefinition.isRequired() != null ? propertyDefinition.isRequired() : false);
		setQueryable(propertyDefinition.isQueryable() != null ? propertyDefinition.isQueryable() : true);
		setOrderable(propertyDefinition.isOrderable() != null ? propertyDefinition.isOrderable() : false);
		setChoices(buildChoices(propertyDefinition.getChoices()));
		setOpenChoice(propertyDefinition.isOpenChoice() != null ? propertyDefinition.isOpenChoice() : false);
		
		setDefaultValue(new ArrayList<Object>(propertyDefinition.getDefaultValue()));
		
		// CRITICAL FIX: Set inherited flag based on property namespace
		// CMIS standard properties (cmis:*) are inherited, custom properties are not
		if (correctedPropertyId != null && correctedPropertyId.startsWith("cmis:")) {
			setInherited(true);  // CMIS standard properties are inherited
		} else {
			setInherited(false); // Custom properties are not inherited
		}

	}

	private <T> List<Choice> buildChoices(List<org.apache.chemistry.opencmis.commons.definitions.Choice<T>> choices){
		List<Choice> list = new ArrayList<Choice>();
		if(org.apache.commons.collections4.CollectionUtils.isNotEmpty(choices)){
			for(org.apache.chemistry.opencmis.commons.definitions.Choice<T> choice : choices){
				List<Object> values = new ArrayList<Object>(choice.getValue());
				Choice c = new Choice(choice.getDisplayName(), values, buildChoices(choice.getChoice()));
				list.add(c);
			}
		}
		return list;
	}

	/**
	 * Getter & Setter
	 */
	public String getPropertyId() {
		return propertyId;
	}

	public String getDetailNodeId() {
		return detailNodeId;
	}

	public void setDetailNodeId(String detailNodeId) {
		this.detailNodeId = detailNodeId;
	}

	public void setPropertyId(String propertyId) {
		// Detect contamination pattern for validation
		boolean isContamination = false;
		if (this.propertyId != null && propertyId != null) {
			// Check if this looks like a contamination (custom namespace -> cmis: change)
			if (this.propertyId.contains(":") && !this.propertyId.startsWith("cmis:") && propertyId.startsWith("cmis:")) {
				isContamination = true;
			}
		}
		
		// Set the property ID
		this.propertyId = propertyId;
		
	}

	public String getLocalName() {
		return localName;
	}

	public void setLocalName(String localName) {
		this.localName = localName;
	}

	public String getLocalNameSpace() {
		return localNameSpace;
	}

	public void setLocalNameSpace(String localNameSpace) {
		this.localNameSpace = localNameSpace;
	}

	public String getQueryName() {
		return queryName;
	}

	public void setQueryName(String queryName) {
		this.queryName = queryName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public PropertyType getPropertyType() {
		return propertyType;
	}

	public void setPropertyType(PropertyType propertyType) {
		this.propertyType = propertyType;
	}

	public Cardinality getCardinality() {
		return cardinality;
	}

	public void setCardinality(Cardinality cardinality) {
		this.cardinality = cardinality;
	}

	public Updatability getUpdatability() {
		return updatability;
	}

	public void setUpdatability(Updatability updatability) {
		this.updatability = updatability;
	}

	public boolean isRequired() {
		return required;
	}

	public void setRequired(boolean required) {
		this.required = required;
	}

	public boolean isQueryable() {
		return queryable;
	}

	public void setQueryable(boolean queryable) {
		this.queryable = queryable;
	}

	public boolean isOrderable() {
		return orderable;
	}

	public void setOrderable(boolean orderable) {
		this.orderable = orderable;
	}

	public List<Choice> getChoices() {
		return choices;
	}

	public void setChoices(List<Choice> choices) {
		this.choices = choices;
	}

	public boolean isOpenChoice() {
		return openChoice;
	}

	public void setOpenChoice(boolean openChoice) {
		this.openChoice = openChoice;
	}
	/*
	public void setOpenChoice(Boolean openChoice) {
		this.openChoice = (openChoice == null) ? true: false;
	}
*/
	public List<Object> getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(List<Object> defaultValue) {
		this.defaultValue = defaultValue;
	}

	public Long getMinValue() {
		return minValue;
	}

	public void setMinValue(Long minValue) {
		this.minValue = minValue;
	}

	public Long getMaxValue() {
		return maxValue;
	}

	public void setMaxValue(Long maxValue) {
		this.maxValue = maxValue;
	}

	public Resolution getResolution() {
		return resolution;
	}

	public void setResolution(Resolution resolution) {
		this.resolution = resolution;
	}

	public DecimalPrecision getDecimalPrecision() {
		return decimalPrecision;
	}

	public void setDecimalPrecision(DecimalPrecision decimalPrecision) {
		this.decimalPrecision = decimalPrecision;
	}

	public BigDecimal getDecimalMinValue() {
		return decimalMinValue;
	}

	public void setDecimalMinValue(BigDecimal decimalMinValue) {
		this.decimalMinValue = decimalMinValue;
	}

	public BigDecimal getDecimalMaxValue() {
		return decimalMaxValue;
	}

	public void setDecimalMaxValue(BigDecimal decimalMaxValue) {
		this.decimalMaxValue = decimalMaxValue;
	}

	public Long getMaxLength() {
		return maxLength;
	}

	public void setMaxLength(Long maxLength) {
		this.maxLength = maxLength;
	}

	public boolean isInherited() {
		return inherited;
	}

	public void setInherited(boolean inherited) {
		this.inherited = inherited;
	}

}
