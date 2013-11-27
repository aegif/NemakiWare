package jp.aegif.nemaki.model.couch;

import java.util.List;

import jp.aegif.nemaki.model.NemakiTypeDefinition;

import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;

public class CouchTypeDefinition extends CouchNodeBase {

	private static final long serialVersionUID = 8066284826946206320L;

	// Attributes Common
	private String typeId;
	private String localName;
	private String localNameSpace;
	private String queryName;
	private String displayName;
	private BaseTypeId baseId;
	private String parentId;
	private String description;
	private Boolean creatable;
	private Boolean filable;
	private Boolean queryable;
	private Boolean controllablePolicy;
	private Boolean controllableACL;
	private Boolean fulltextIndexed;
	private Boolean includedInSupertypeQuery;
	private Boolean typeMutabilityCreate;
	private Boolean typeMutabilityUpdate;
	private Boolean typeMutabilityDelete;
	private List<String> properties;

	// Attributes specific to Document
	private ContentStreamAllowed contentStreamAllowed;
	private Boolean versionable;

	// Attributes specific to Relationship
	private List<String> allowedSourceTypes;
	private List<String> allowedTargetTypes;

	public CouchTypeDefinition() {
		super();
	}

	public CouchTypeDefinition(NemakiTypeDefinition t) {
		super(t);
		setTypeId(t.getTypeId());
		setLocalName(t.getLocalName());
		setLocalNameSpace(t.getLocalNameSpace());
		setQueryName(t.getQueryName());
		setDisplayName(t.getDisplayName());
		setBaseTypeId(t.getBaseId());
		setParentId(t.getParentId());
		setDescription(t.getDescription());
		setCreatable(t.isCreatable());
		setFilable(t.isFilable());
		setQueryable(t.isQueryable());
		setControllablePolicy(t.isControllablePolicy());
		setControllableACL(t.isControllableACL());
		setFulltextIndexed(t.isFulltextIndexed());
		setIncludedInSupertypeQuery(t.isIncludedInSupertypeQuery());
		setTypeMutabilityCreate(t.isTypeMutabilityCreate());
		setTypeMutabilityUpdate(t.isTypeMutabilityUpdate());
		setTypeMutabilityDelete(t.isTypeMutabilityDelete());
		
		setProperties(t.getProperties());
		
		setContentStreamAllowed(t.getContentStreamAllowed());
		setVersionable(t.isVersionable());
		setAllowedSourceTypes(t.getAllowedSourceTypes());
		setAllowedTargetTypes(t.getAllowedTargetTypes());
	}

	/**
	 * Getter & Setter
	 */
	public String getTypeId() {
		return typeId;
	}

	public void setTypeId(String typeId) {
		this.typeId = typeId;
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

	public BaseTypeId getBaseId() {
		return baseId;
	}

	public void setBaseTypeId(BaseTypeId baseId) {
		this.baseId = baseId;
	}

	public String getParentId() {
		return parentId;
	}

	public void setParentId(String parentId) {
		this.parentId = parentId;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Boolean isCreatable() {
		return creatable;
	}

	public void setCreatable(Boolean creatable) {
		this.creatable = creatable;
	}

	public Boolean isFilable() {
		return filable;
	}

	public void setFilable(Boolean filable) {
		this.filable = filable;
	}

	public Boolean isQueryable() {
		return queryable;
	}

	public void setQueryable(Boolean queryable) {
		this.queryable = queryable;
	}

	public Boolean isControllablePolicy() {
		return controllablePolicy;
	}

	public void setControllablePolicy(Boolean controllablePolicy) {
		this.controllablePolicy = controllablePolicy;
	}

	public Boolean isControllableACL() {
		return controllableACL;
	}

	public void setControllableACL(Boolean controllableACL) {
		this.controllableACL = controllableACL;
	}

	public Boolean isFulltextIndexed() {
		return fulltextIndexed;
	}

	public void setFulltextIndexed(Boolean fulltextIndexed) {
		this.fulltextIndexed = fulltextIndexed;
	}

	public Boolean isIncludedInSupertypeQuery() {
		return includedInSupertypeQuery;
	}

	public void setIncludedInSupertypeQuery(Boolean includedInSupertypeQuery) {
		this.includedInSupertypeQuery = includedInSupertypeQuery;
	}

	public Boolean isTypeMutabilityCreate() {
		return typeMutabilityCreate;
	}

	public void setTypeMutabilityCreate(Boolean typeMutabilityCreate) {
		this.typeMutabilityCreate = typeMutabilityCreate;
	}

	public Boolean isTypeMutabilityUpdate() {
		return typeMutabilityUpdate;
	}

	public void setTypeMutabilityUpdate(Boolean typeMutabilityUpdate) {
		this.typeMutabilityUpdate = typeMutabilityUpdate;
	}

	public Boolean isTypeMutabilityDelete() {
		return typeMutabilityDelete;
	}

	public void setTypeMutabilityDelete(Boolean typeMutabilityDelete) {
		this.typeMutabilityDelete = typeMutabilityDelete;
	}

	public List<String> getProperties() {
		return properties;
	}

	public void setProperties(List<String> properties) {
		this.properties = properties;
	}
	
	public ContentStreamAllowed getContentStreamAllowed() {
		return contentStreamAllowed;
	}

	public void setContentStreamAllowed(ContentStreamAllowed contentStreamAllowed) {
		this.contentStreamAllowed = contentStreamAllowed;
	}

	public Boolean isVersionable() {
		return versionable;
	}

	public void setVersionable(Boolean versionable) {
		this.versionable = versionable;
	}

	public List<String> getAllowedSourceTypes() {
		return allowedSourceTypes;
	}

	public void setAllowedSourceTypes(List<String> allowedSourceTypes) {
		this.allowedSourceTypes = allowedSourceTypes;
	}

	public List<String> getAllowedTargetTypes() {
		return allowedTargetTypes;
	}

	public void setAllowedTargetTypes(List<String> allowedTargetTypes) {
		this.allowedTargetTypes = allowedTargetTypes;
	}

	public void setBaseId(BaseTypeId baseId) {
		this.baseId = baseId;
	}

	public NemakiTypeDefinition convert() {
		NemakiTypeDefinition t = new NemakiTypeDefinition(super.convert());
		t.setTypeId(getTypeId());
		t.setLocalName(getLocalName());
		t.setLocalNameSpace(getLocalNameSpace());
		t.setQueryName(getQueryName());
		t.setDisplayName(getDisplayName());
		t.setBaseId(getBaseId());
		t.setParentId(getParentId());
		t.setDescription(getDescription());
		t.setCreatable(isCreatable());
		t.setFilable(isFilable());
		t.setQueryable(isQueryable());
		t.setControllablePolicy(isControllablePolicy());
		t.setControllableACL(isControllableACL());
		t.setFulltextIndexed(isFulltextIndexed());
		t.setIncludedInSupertypeQuery(isIncludedInSupertypeQuery());
		t.setTypeMutabilityCreate(isTypeMutabilityCreate());
		t.setTypeMutabilityUpdate(isTypeMutabilityUpdate());
		t.setTypeMutabilityDelete(isTypeMutabilityDelete());
		t.setProperties(getProperties());
		
		t.setContentStreamAllowed(getContentStreamAllowed());
		t.setVersionable(isVersionable());
		t.setAllowedSourceTypes(getAllowedSourceTypes());
		t.setAllowedTargetTypes(getAllowedTargetTypes());

		return t;
	}
}