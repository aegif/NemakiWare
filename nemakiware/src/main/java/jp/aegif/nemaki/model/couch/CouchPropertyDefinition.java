package jp.aegif.nemaki.model.couch;

import java.math.BigDecimal;
import java.util.List;

import jp.aegif.nemaki.model.NemakiPropertyDefinition;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.DecimalPrecision;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.lucene.document.DateTools.Resolution;

public class CouchPropertyDefinition extends CouchNodeBase {

	private static final long serialVersionUID = 4477156425295443676L;

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
	private List<Object> choices;
	private boolean openChoice;
	private List<Object> defaultValue;
	// Attributes specific to Integer
	private long minValue;
	private long maxValue;

	// Attributes specific to DateTime
	private Resolution resolution;

	// Attributes specific to Decimal
	private DecimalPrecision decimalPrecision;
	private BigDecimal decimalMinValue;
	private BigDecimal decimalMaxValue;

	// Attributes specific to String
	private long maxLength;

	public CouchPropertyDefinition(){
		super();
	}
	
	public CouchPropertyDefinition(NemakiPropertyDefinition p){
		super(p);
		setPropertyId(p.getPropertyId());
		setLocalName(p.getLocalName());
		setLocalNameSpace(p.getLocalNameSpace());
		setQueryName(p.getQueryName());
		setDisplayName(p.getDisplayName());
		setDescription(p.getDescription());
		setPropertyType(p.getPropertyType());
		setCardinality(p.getCardinality());
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

	public List<Object> getChoices() {
		return choices;
	}

	public void setChoices(List<Object> choices) {
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

	public long getMinValue() {
		return minValue;
	}

	public void setMinValue(long minValue) {
		this.minValue = minValue;
	}

	public long getMaxValue() {
		return maxValue;
	}

	public void setMaxValue(long maxValue) {
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

	public long getMaxLength() {
		return maxLength;
	}

	public void setMaxLength(long maxLength) {
		this.maxLength = maxLength;
	}
	
	public NemakiPropertyDefinition convert(){
		NemakiPropertyDefinition p = new NemakiPropertyDefinition(super.convert());
		p.setPropertyId(getPropertyId());
		p.setLocalName(getLocalName());
		p.setLocalNameSpace(getLocalNameSpace());
		p.setQueryName(getQueryName());
		p.setDisplayName(getDisplayName());
		p.setDescription(getDescription());
		p.setPropertyType(getPropertyType());
		p.setCardinality(getCardinality());
		p.setUpdatability(getUpdatability());
		p.setRequired(isRequired());
		p.setQueryable(isQueryable());
		p.setOrderable(isOrderable());
		p.setChoices(getChoices());
		p.setOpenChoice(isOpenChoice());
		p.setDefaultValue(getDefaultValue());
		
		p.setMinValue(getMinValue());
		p.setMaxLength(getMaxValue());
		p.setResolution(getResolution());
		p.setDecimalPrecision(getDecimalPrecision());
		p.setDecimalMinValue(getDecimalMinValue());
		p.setDecimalMaxValue(getDecimalMaxValue());
		p.setMaxLength(getMaxLength());

		return p;
	}
}