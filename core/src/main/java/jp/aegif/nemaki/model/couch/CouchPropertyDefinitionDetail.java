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

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jp.aegif.nemaki.model.Choice;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.lucene.document.DateTools.Resolution;

public class CouchPropertyDefinitionDetail extends CouchNodeBase {

	private static final long serialVersionUID = 4477156425295443676L;

	private String coreNodeId;
	
	// CRITICAL FIX: Add @JsonProperty annotations to prevent field mapping contamination
	// This fixes the property ID inversion issue where tck:boolean→cmis:name, tck:id→cmis:description
	// Pattern matches CouchTypeDefinition which has proper @JsonProperty annotations
	
	// Attributes common
	@JsonProperty("propertyId")  // CRITICAL: Prevents propertyId field contamination from CouchDB
	private String propertyId;
	
	@JsonProperty("localName")   // CRITICAL: Prevents localName field contamination - primary contamination source
	private String localName;
	
	@JsonProperty("localNameSpace")
	private String localNameSpace;
	
	@JsonProperty("queryName")   // CRITICAL: Prevents queryName field contamination - fallback strategy source
	private String queryName;
	
	@JsonProperty("displayName")
	private String displayName;
	
	@JsonProperty("description")
	private String description;
	@JsonProperty("propertyType")
	private PropertyType propertyType;
	
	@JsonProperty("cardinality")
	private Cardinality cardinality;
	
	@JsonProperty("updatability")
	private Updatability updatability;
	
	@JsonProperty("required")
	private boolean required;
	
	@JsonProperty("queryable")
	private boolean queryable;
	
	@JsonProperty("orderable")
	private boolean orderable;
	
	@JsonProperty("choices")
	private List<Choice> choices;
	
	@JsonProperty("openChoice")
	private boolean openChoice;
	
	@JsonProperty("defaultValue")
	private List<Object> defaultValue;
	
	// Attributes specific to Integer
	@JsonProperty("minValue")
	private Long minValue;
	
	@JsonProperty("maxValue")
	private Long maxValue;

	// Attributes specific to DateTime
	@JsonProperty("resolution")
	private Resolution resolution;

	// Attributes specific to Decimal
	@JsonProperty("decimalPrecision")
	private DecimalPrecision decimalPrecision;
	
	@JsonProperty("decimalMinValue")
	private BigDecimal decimalMinValue;
	
	@JsonProperty("decimalMaxValue")
	private BigDecimal decimalMaxValue;

	// Attributes specific to String
	@JsonProperty("maxLength")
	private Long maxLength;

	public CouchPropertyDefinitionDetail(){
		super();
	}
	
	public CouchPropertyDefinitionDetail(NemakiPropertyDefinitionDetail p){
		super(p);
		setCoreNodeId(p.getCoreNodeId());
		setLocalName(p.getLocalName());
		setLocalNameSpace(p.getLocalNameSpace());
		setDisplayName(p.getDisplayName());
		setDescription(p.getDescription());
		setUpdatability(p.getUpdatability());
		setRequired(p.isRequired());
		setQueryable(p.isQueryable());
		setOrderable(p.isOrderable());
		setChoices(p.getChoices());
		setOpenChoice(p.isOpenChoice());
		setDefaultValue(p.getDefaultValue());
		
		setMinValue(p.getMinValue());
		setMaxValue(p.getMaxValue());
		setResolution(p.getResolution());
		setDecimalPrecision(p.getDecimalPrecision());
		setDecimalMinValue(p.getDecimalMinValue());
		setDecimalMaxValue(p.getDecimalMaxValue());
		setMaxLength(p.getMaxLength());
	}
	
	/**
	 * Getter & Setter 
	 */
	public String getPropertyId() {
		return propertyId;
	}

	public String getCoreNodeId() {
		return coreNodeId;
	}

	public void setCoreNodeId(String coreNodeId) {
		this.coreNodeId = coreNodeId;
	}

	public void setPropertyId(String propertyId) {
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
	
	public NemakiPropertyDefinitionDetail convert(){
		NemakiPropertyDefinitionDetail p = new NemakiPropertyDefinitionDetail(super.convert());
		
		p.setCoreNodeId(getCoreNodeId());
		p.setLocalName(getLocalName());
		p.setLocalNameSpace(getLocalNameSpace());
		p.setDisplayName(getDisplayName());
		p.setDescription(getDescription());
		p.setUpdatability(getUpdatability());
		p.setRequired(isRequired());
		p.setQueryable(isQueryable());
		p.setOrderable(isOrderable());
		p.setChoices(getChoices());
		p.setOpenChoice(isOpenChoice());
		p.setDefaultValue(getDefaultValue());
		
		p.setMinValue(getMinValue());
		// CRITICAL BUG FIX: Line 306 was incorrectly using getMaxValue() for setMaxLength()
		p.setMaxValue(getMaxValue()); // Fixed: was setMaxLength(getMaxValue())
		p.setResolution(getResolution());
		p.setDecimalPrecision(getDecimalPrecision());
		p.setDecimalMinValue(getDecimalMinValue());
		p.setDecimalMaxValue(getDecimalMaxValue());
		p.setMaxLength(getMaxLength());
		

		return p;
	}
}