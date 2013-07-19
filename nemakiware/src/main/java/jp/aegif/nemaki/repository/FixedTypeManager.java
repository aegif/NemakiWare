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
package jp.aegif.nemaki.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.DocumentTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.FolderTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RelationshipTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;

public class FixedTypeManager {

	/**
	 * Pre-defined types.
	 */
	public final static String DOCUMENT_TYPE_ID = "cmis:document";
	public final static String FOLDER_TYPE_ID = "cmis:folder";
	public final static String RELATIONSHIP_TYPE_ID = "cmis:relationship";
	public final static String POLICY_TYPE_ID = "cmis:policy";
	public final static String SECONDARY_TYPE_ID = "cmis:secondary";

	
	private final static boolean required = true;
	private final static boolean queryable = true;
	private final static boolean orderable = true;

	/**
	 * Types namespace.
	 */
	private static final String NAMESPACE = "http://www.aegif.jp/Nemaki";

	/**
	 * Map of all types.
	 */
	public Map<String, TypeDefinitionContainer> types;


	/**
	 * Constructor.
	 */
	public FixedTypeManager() {
		setup();
	}
	
	public Map<String, TypeDefinitionContainer> getTypes() {
		return types;
	}

	/**
	 * Creates the base types.
	 */
	private void setup() {
		types = new HashMap<String, TypeDefinitionContainer>();

		// folder type
		FolderTypeDefinitionImpl folderType = new FolderTypeDefinitionImpl();
		folderType.setId(FOLDER_TYPE_ID);
		folderType.setLocalName("folder");
		folderType.setLocalNamespace(NAMESPACE);
		folderType.setQueryName(FOLDER_TYPE_ID);
		folderType.setDisplayName("folder");
		folderType.setBaseTypeId(BaseTypeId.CMIS_FOLDER);
		folderType.setDescription("Folder");
		folderType.setIsCreatable(true);
		folderType.setIsFileable(true);
		folderType.setIsQueryable(true);
		folderType.setIsControllablePolicy(false);
		folderType.setIsControllableAcl(true);
		folderType.setIsFulltextIndexed(false);
		folderType.setIsIncludedInSupertypeQuery(true);
		
		addBasePropertyDefinitions(folderType);
		addFolderPropertyDefinitions(folderType);

		//addTypeInternal(folderType);
		TypeManagerUtil.addTypeInternal(types, folderType);
		
		// document type
		DocumentTypeDefinitionImpl documentType = new DocumentTypeDefinitionImpl();
		documentType.setId(DOCUMENT_TYPE_ID);
		documentType.setLocalName("document");
		documentType.setLocalNamespace(NAMESPACE);
		documentType.setQueryName(DOCUMENT_TYPE_ID);
		documentType.setDisplayName("document");
		documentType.setDescription("Document");
		documentType.setBaseTypeId(BaseTypeId.CMIS_DOCUMENT);
		documentType.setIsCreatable(true);
		documentType.setIsFileable(true);
		documentType.setIsQueryable(true);
		documentType.setIsControllablePolicy(false);
		documentType.setIsControllableAcl(true);
		documentType.setIsIncludedInSupertypeQuery(true);
		documentType.setIsFulltextIndexed(true);
		documentType.setIsVersionable(true);
		documentType.setContentStreamAllowed(ContentStreamAllowed.REQUIRED);

		addBasePropertyDefinitions(documentType);
		addDocumentPropertyDefinitions(documentType);

		//addTypeInternal(documentType);
		TypeManagerUtil.addTypeInternal(types, documentType);
		
		// relationship types
		RelationshipTypeDefinitionImpl relationshipType = new RelationshipTypeDefinitionImpl();
		relationshipType.setId(RELATIONSHIP_TYPE_ID);
		relationshipType.setLocalName("relationship");
		relationshipType.setLocalNamespace(NAMESPACE);
		relationshipType.setQueryName(RELATIONSHIP_TYPE_ID);
		relationshipType.setDisplayName("relationship");
		relationshipType.setBaseTypeId(BaseTypeId.CMIS_RELATIONSHIP);
		relationshipType.setDescription("Relationship");
		relationshipType.setIsCreatable(false);
		relationshipType.setIsFileable(false);
		relationshipType.setIsQueryable(false);
		relationshipType.setIsControllablePolicy(false);
		relationshipType.setIsControllableAcl(false);
		relationshipType.setIsIncludedInSupertypeQuery(true);
		relationshipType.setIsFulltextIndexed(false);

		List<String> allowedTypes = new ArrayList<String>();
		allowedTypes.add(DOCUMENT_TYPE_ID);
		allowedTypes.add(FOLDER_TYPE_ID);
		relationshipType.setAllowedSourceTypes(allowedTypes);
		relationshipType.setAllowedTargetTypes(allowedTypes);
		
		addBasePropertyDefinitions(relationshipType);
		addRelationshipPropertyDefinitions(relationshipType);
		
		//addTypeInternal(relationshipType);
		TypeManagerUtil.addTypeInternal(types, relationshipType);
		
		// policy type
		PolicyTypeDefinitionImpl policyType = new PolicyTypeDefinitionImpl();
		policyType.setId(POLICY_TYPE_ID);
		policyType.setLocalName("policy");
		policyType.setLocalNamespace(NAMESPACE);
		policyType.setQueryName(POLICY_TYPE_ID);
		policyType.setDisplayName("policy");
		policyType.setBaseTypeId(BaseTypeId.CMIS_POLICY);
		policyType.setDescription("Policy");
		policyType.setIsCreatable(false);
		policyType.setIsFileable(false);
		policyType.setIsQueryable(false);
		policyType.setIsControllablePolicy(false);
		policyType.setIsControllableAcl(false);
		policyType.setIsIncludedInSupertypeQuery(true);
		policyType.setIsFulltextIndexed(false);

		addBasePropertyDefinitions(policyType);
		addPolicyPropertyDefinitions(policyType);
		
		//addTypeInternal(policyType);
		TypeManagerUtil.addTypeInternal(types, policyType);
		
		
		// secondary type
		SecondaryTypeDefinitionImpl secondaryType = new SecondaryTypeDefinitionImpl();
		secondaryType.setId(SECONDARY_TYPE_ID);
		secondaryType.setLocalName("secondary");
		secondaryType.setLocalNamespace(NAMESPACE);
		secondaryType.setQueryName(SECONDARY_TYPE_ID);
		secondaryType.setDisplayName("secondary");
		secondaryType.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
		secondaryType.setDescription("Secondary");
		secondaryType.setIsCreatable(false);
		secondaryType.setIsFileable(false);
		secondaryType.setIsQueryable(true);
		secondaryType.setIsControllablePolicy(false);
		secondaryType.setIsControllableAcl(false);
		secondaryType.setIsIncludedInSupertypeQuery(true);
		secondaryType.setIsFulltextIndexed(true);
		secondaryType.setPropertyDefinitions(new HashMap<String, PropertyDefinition<?>>());

		
		//addTypeInternal(policyType);
		TypeManagerUtil.addTypeInternal(types, secondaryType);
	}

	private void addBasePropertyDefinitions(AbstractTypeDefinition type) {
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.NAME,
				PropertyType.STRING, Cardinality.SINGLE,
				Updatability.READWRITE, required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.DESCRIPTION,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READWRITE,
				!required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.OBJECT_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.BASE_TYPE_ID, PropertyType.ID, Cardinality.SINGLE,
				Updatability.READONLY, !required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.OBJECT_TYPE_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.ONCREATE, required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.SECONDARY_OBJECT_TYPE_IDS, PropertyType.ID,
				Cardinality.MULTI, Updatability.READWRITE, !required, queryable,
				!orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.CREATED_BY,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!required, queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CREATION_DATE, PropertyType.DATETIME,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.LAST_MODIFIED_BY, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.LAST_MODIFICATION_DATE, PropertyType.DATETIME,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));

		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CHANGE_TOKEN, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
	}

	private void addFolderPropertyDefinitions(FolderTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.PARENT_ID,
				PropertyType.ID, Cardinality.SINGLE, Updatability.READONLY,
				!required, queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(PropertyIds.PATH,
				PropertyType.STRING, Cardinality.SINGLE, Updatability.READONLY,
				!required, !queryable, !orderable, null));
		
		List<String> defaults = new ArrayList<String>();
		defaults.add(BaseTypeId.CMIS_FOLDER.value());
		defaults.add(BaseTypeId.CMIS_DOCUMENT.value());
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS, PropertyType.ID,
				Cardinality.MULTI, Updatability.READONLY, !required,
				!queryable, !orderable, defaults));
	}

	private void addDocumentPropertyDefinitions(DocumentTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_IMMUTABLE, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_LATEST_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_MAJOR_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_LATEST_MAJOR_VERSION, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_PRIVATE_WORKING_COPY, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_LABEL, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, PropertyType.BOOLEAN,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				queryable, orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CHECKIN_COMMENT, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_LENGTH, PropertyType.INTEGER,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_MIME_TYPE, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_FILE_NAME, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.CONTENT_STREAM_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
	}

	private void addRelationshipPropertyDefinitions(RelationshipTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.SOURCE_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, required,
				!queryable, !orderable, null));
		
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.TARGET_ID, PropertyType.ID,
				Cardinality.SINGLE, Updatability.READONLY, required,
				!queryable, !orderable, null));
	}
	
	private void addPolicyPropertyDefinitions(PolicyTypeDefinitionImpl type) {
		type.addPropertyDefinition(createDefaultPropDef(
				PropertyIds.POLICY_TEXT, PropertyType.STRING,
				Cardinality.SINGLE, Updatability.READONLY, !required,
				!queryable, !orderable, null));
	}
	
	/**
	 * Adds a type to collection.
	 */
	public void addTypeInternal(AbstractTypeDefinition type) {
		if (type == null) {
			return;
		}

		if (types.containsKey(type.getId())) {
			// TODO Logging
			// log.warn("Can't overwrite a type");
			return;
		}

		TypeDefinitionContainerImpl tc = new TypeDefinitionContainerImpl();
		tc.setTypeDefinition(type);

		// add to parent
		if (type.getParentTypeId() != null) {
			TypeDefinitionContainerImpl tdc = (TypeDefinitionContainerImpl) types
					.get(type.getParentTypeId());
			if (tdc != null) {
				if (tdc.getChildren() == null) {
					tdc.setChildren(new ArrayList<TypeDefinitionContainer>());
				}
				tdc.getChildren().add(tc);
			}
		}

		types.put(type.getId(), tc);
	}

	public PropertyDefinition<?> createDefaultPropDef(String id,
			PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean required, boolean queryable,
			boolean orderable, List<?> defaultValue) {
		PropertyDefinition<?> result = null;

		// Default values
		String localName = id;
		String localNameSpace = NAMESPACE;
		String queryName = id;
		String displayName = id;
		String description = id;
		boolean inherited = false;
		boolean openChoice = false;

		result = TypeManagerUtil.createPropDef(id, localName, localNameSpace, queryName, displayName, description, datatype, cardinality, updatability, required, queryable, inherited, openChoice, orderable, defaultValue);
		
		return result;

	}
}
