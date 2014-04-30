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
import java.util.List;

import jp.aegif.nemaki.util.constant.NodeType;

import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.lucene.document.DateTools.Resolution;

public class NemakiPropertyDefinitionDetail extends NodeBase {
	private String coreNodeId;

	// Attributes common
	private String localName;
	private String localNameSpace;
	private String displayName;
	private String description;
	private Updatability updatability;
	private boolean required;
	private boolean queryable;
	private boolean orderable;
	private List<Choice> choices;
	private boolean openChoice;
	private List<Object> defaultValue;

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

	public NemakiPropertyDefinitionDetail() {
		super();
		setType(NodeType.PROPERTY_DEFINITION_DETAIL.value());
	}

	public NemakiPropertyDefinitionDetail(NodeBase n) {
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}

	public NemakiPropertyDefinitionDetail(NemakiPropertyDefinition p, String coreNodeId){
		setType(NodeType.PROPERTY_DEFINITION_DETAIL.value());

		setCoreNodeId(coreNodeId);

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
		setDecimalMinValue(p.getDecimalMinValue());
		setDecimalMaxValue(p.getDecimalMaxValue());
		setMaxLength(p.getMaxLength());
	}

	/**
	 * Getter & Setter
	 */
	public String getCoreNodeId() {
		return coreNodeId;
	}

	public void setCoreNodeId(String coreNodeId) {
		this.coreNodeId = coreNodeId;
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

}
