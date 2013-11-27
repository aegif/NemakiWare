package jp.aegif.nemaki.model;

import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;

public class NemakiPropertyDefinitionCore extends NodeBase{
	private String propertyId;
	private PropertyType propertyType;
	private String queryName;
	private Cardinality cardinality;
	
	public NemakiPropertyDefinitionCore() {
		super();
	}

	public NemakiPropertyDefinitionCore(NodeBase n) {
		setId(n.getId());
		setType(n.getType());
		setCreated(n.getCreated());
		setCreator(n.getCreator());
		setModified(n.getModified());
		setModifier(n.getModifier());
	}
	
	public NemakiPropertyDefinitionCore(NemakiPropertyDefinition p){
		setType("propertyDefinitionCore");
		setPropertyId(p.getPropertyId());
		setPropertyType(p.getPropertyType());
		setQueryName(p.getQueryName());
		setCardinality(p.getCardinality());
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
}
