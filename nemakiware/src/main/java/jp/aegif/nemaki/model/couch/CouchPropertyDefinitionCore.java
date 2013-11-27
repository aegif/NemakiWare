package jp.aegif.nemaki.model.couch;

import jp.aegif.nemaki.model.NemakiPropertyDefinitionCore;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;

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
