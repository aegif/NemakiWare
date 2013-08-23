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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jp.aegif.nemaki.model.constant.NemakiConstant;
import jp.aegif.nemaki.util.YamlManager;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.WSConverter;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.SecondaryTypeDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.CollectionUtils;

/**
 * Type Manager class, defines document/folder/relationship/policy
 */
public class TypeManager implements
		org.apache.chemistry.opencmis.server.support.TypeManager {

	private static final Log log = LogFactory.getLog(TypeManager.class);
	/**
	* Pre-defined types.
	*/
	public final static String DOCUMENT_TYPE_ID = BaseTypeId.CMIS_DOCUMENT.value();
	public final static String FOLDER_TYPE_ID = BaseTypeId.CMIS_FOLDER.value();
	public final static String RELATIONSHIP_TYPE_ID = BaseTypeId.CMIS_RELATIONSHIP.value();
	public final static String POLICY_TYPE_ID = BaseTypeId.CMIS_POLICY.value();
	public final static String ITEM_TYPE_ID = BaseTypeId.CMIS_ITEM.value();
	public final static String SECONDARY_TYPE_ID = BaseTypeId.CMIS_SECONDARY.value();

	private FixedTypeManager fixedTypeManager;
	private final String ASPECT_FILE_PATH = "base_model.yml";
	
	/**
	 * Map of all types. It is abbreviation of fixedTypeManager.getTypes()
	 */
	private Map<String, TypeDefinitionContainer> types;
	
	public TypeManager() {
	}

	public TypeManager(FixedTypeManager fixedTypeManager) {
		setFixedTypeManager(fixedTypeManager);
		
		//Copy baseTypes
		types = new HashMap<String, TypeDefinitionContainer>();
		for(Entry<String, TypeDefinitionContainer>entry : fixedTypeManager.getTypes().entrySet()){
			types.put(entry.getKey(), entry.getValue());
		}

		//Add SecondaryTypes
		addSecondaryTypes();
	}
	
	private void addSecondaryTypes(){
		Map<String, Object>map = getCustomModelInfo();
		Map<String, Object> aspects = (Map<String, Object>) map.get(NemakiConstant.EXTNAME_ASPECTS);
	
		// set aspect
		List<String> parentIds = new ArrayList<String>();
		//初期値
		parentIds.add("cmis:secondary");
		
		addSecondryTypesnternal(parentIds, aspects);
		
	}
	
	@SuppressWarnings("unchecked")
	private void addSecondryTypesnternal(List<String> parentIds, Map<String,Object> aspects){
		List<String>list = new ArrayList<String>();
		
		for(Entry<String, Object> entry : aspects.entrySet()){
			Map<String, Object> aspect = (Map<String, Object>) entry.getValue();
			Map<String, Object> attributes = (Map<String, Object>) aspect.get(NemakiConstant.EXTNAME_ASPECT_ATTRIBUTES);
			String parentId = (String) attributes.get("parentId");
			
			if(parentIds.contains(parentId)){
				//Build SecondaryTypeDefinition from yaml
				addSecondaryType(attributes, entry.getKey(), aspect);
				//ループ用変数に追加
				list.add(entry.getKey());
			}
		}
		
		if(CollectionUtils.isEmpty(list)){
			return;
		}else{
			addSecondryTypesnternal(list, aspects);
		}
	}
	
	
	@SuppressWarnings("unchecked")
	private void addSecondaryType(Map<String, Object> attributes, String aspectKey, Map<String, Object> aspect){
		
		SecondaryTypeDefinitionImpl type = new SecondaryTypeDefinitionImpl();
		
		// set attributes
		
		type.setId(aspectKey);
		type.setBaseTypeId(BaseTypeId.CMIS_SECONDARY);
		String parentId = (attributes.get("parentId") == null)? BaseTypeId.CMIS_SECONDARY.value() : (String)attributes.get("parentId");
		type.setParentTypeId(parentId);
		
		//TODO なんか他にもいろいろattribute設定しないといけないはず
		
		for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
			//displayNameとか
			String attrVal = (String)attribute.getValue();
			if(attribute.getKey().equals("displayName")){
				type.setDisplayName(attrVal);
			}else if(attribute.getKey().equals("description")){
				type.setDescription(attrVal);
			}else if(attribute.getKey().equals("localName")){
				type.setLocalName(attrVal);
			}else if(attribute.getKey().equals("localNameSpace")){
				type.setLocalNamespace(attrVal);
			}else if(attribute.getKey().equals("queryName")){
				type.setQueryName(attrVal);
			}
		}

		// set properties
		Map<String, Object> properties = (Map<String, Object>) aspect
				.get(NemakiConstant.EXTNAME_ASPECT_PROPERTIES);

		for (String propertyKey : properties.keySet()) {
			Map<String, Object> property = (Map<String, Object>) properties
					.get(propertyKey);
			
			PropertyType datatype = PropertyType.fromValue((String)property.get("datatype"));
			Cardinality cardinality = Cardinality.fromValue((String)property.get("cardinality"));
			Updatability updatability = Updatability.fromValue((String)property.get("updatability"));
			Boolean inherited = ((Boolean)property.get("inherited") == null)? false:(Boolean)property.get("inherited");
			Boolean required = ((Boolean)property.get("required") == null)? false:(Boolean)property.get("required");
			Boolean queryable = ((Boolean)property.get("queryable") == null)? false:(Boolean)property.get("queryable");
			Boolean orderable = ((Boolean)property.get("orderable") == null)? false:(Boolean)property.get("orderable");
			Boolean openChoice = ((Boolean)property.get("openChoice") == null)? false:(Boolean)property.get("openChoice");
			
			type.addPropertyDefinition(
					createSecondaryTypePropDef(aspectKey, propertyKey, (String)property.get("localName"), (String)property.get("localNameSpace"), (String)property.get("queryName"), (String)property.get("displayName"), (String)property.get("description"), datatype, cardinality, updatability, required, queryable, inherited, openChoice, orderable, null));
		}
		
		TypeManagerUtil.addTypeInternal(types, type);
	}
	
	private PropertyDefinition createSecondaryTypePropDef(String typeId, String id,String localName, String localNameSpace, String queryName, String displayName, String description,
			PropertyType datatype, Cardinality cardinality,
			Updatability updatability, boolean required, boolean queryable,
			boolean inherited, boolean openChoice, boolean orderable, List<?> defaultValue){
		//String _queryName = (StringUtils.isEmpty(queryName))? typeId + "." + id : typeId + "." + queryName;
		String _localName = (StringUtils.isEmpty(localName))? id : localName;
		String _localNameSpace = (StringUtils.isEmpty(localNameSpace))? NemakiConstant.NAMESPACE_ASPECTS : localNameSpace;
		String _displayName = (StringUtils.isEmpty(displayName))? id : displayName;
		
		return TypeManagerUtil.createPropDef(id, _localName, _localNameSpace, queryName, _displayName, description, datatype, cardinality, updatability, required, queryable, inherited, openChoice, orderable, defaultValue);
		
	}
	
	/**
	 * For internal(Chemistry) use
	 */
	@Override
	public TypeDefinitionContainer getTypeById(String typeId) {
		return types.get(typeId);
	}

	@Override
	public TypeDefinition getTypeByQueryName(String typeQueryName) {
		for (Entry<String, TypeDefinitionContainer> entry : types.entrySet()) {
			if (entry.getValue().getTypeDefinition().getQueryName()
					.equals(typeQueryName))
				return entry.getValue().getTypeDefinition();
		}
		return null;
	}
	
	@Override
	public Collection<TypeDefinitionContainer> getTypeDefinitionList() {
		List<TypeDefinitionContainer> typeRoots = new ArrayList<TypeDefinitionContainer>();
		// iterate types map and return a list collecting the root types:
		for (TypeDefinitionContainer typeDef : types.values()) {
			if (typeDef.getTypeDefinition().getParentTypeId() == null) {
				typeRoots.add(typeDef);
			}
		}
		return typeRoots;
	}
	
	@Override
	public List<TypeDefinitionContainer> getRootTypes() {
		// just take first repository
		List<TypeDefinitionContainer> rootTypes = new ArrayList<TypeDefinitionContainer>();
		for (TypeDefinitionContainer type : types.values()) {
			if (isRootType(type)) {
				rootTypes.add(type);
			}
		}
		return rootTypes;
	}
	
	@Override
	public String getPropertyIdForQueryName(TypeDefinition typeDefinition,
			String propQueryName) {
		// TODO Auto-generated method stub
		return null;
	}
	
	/**
	 * For Nemaki use
	 */
	public TypeDefinition getTypeDefinition(String typeId) {
		TypeDefinitionContainer tc = types.get(typeId);
		if (tc == null) {
			return null;
		}

		return tc.getTypeDefinition();
	}
	
	/**
	 * CMIS getTypesChildren.
	 * If parent type id is not specified, return only base types.
	 */
	public TypeDefinitionList getTypesChildren(CallContext context,
			String typeId, boolean includePropertyDefinitions,
			BigInteger maxItems, BigInteger skipCount) {
		TypeDefinitionListImpl result = new TypeDefinitionListImpl(
				new ArrayList<TypeDefinition>());

		int skip = (skipCount == null ? 0 : skipCount.intValue());
		if (skip < 0) {
			skip = 0;
		}

		int max = (maxItems == null ? Integer.MAX_VALUE : maxItems.intValue());
		if (max < 1) {
			return result;
		}

		if (typeId == null) {
			int count = skip;
			Map<String, TypeDefinitionContainer> t = fixedTypeManager.types;
			Map<String, TypeDefinitionContainer> baseTypes = fixedTypeManager.getTypes();
			for(String key : baseTypes.keySet()){
				count --;
				if(count >= 0) continue;
				
				TypeDefinitionContainer type = baseTypes.get(key);
				result.getList().add(copyTypeDefinition(type.getTypeDefinition()));
			}

			result.setHasMoreItems((result.getList().size() + skip) < max);
			result.setNumItems(BigInteger.valueOf(baseTypes.size()));
		} else {
			TypeDefinitionContainer tc = types.get(typeId);
			if ((tc == null) || (tc.getChildren() == null)) {
				return result;
			}

			for (TypeDefinitionContainer child : tc.getChildren()) {
				if (skip > 0) {
					skip--;
					continue;
				}

				result.getList().add(
						copyTypeDefinition(child.getTypeDefinition()));

				max--;
				if (max == 0) {
					break;
				}
			}

			result.setHasMoreItems((result.getList().size() + skip) < tc
					.getChildren().size());
			result.setNumItems(BigInteger.valueOf(tc.getChildren().size()));
		}

		if (!includePropertyDefinitions) {
			for (TypeDefinition type : result.getList()) {
				try{
					if(type.getPropertyDefinitions() != null){
						type.getPropertyDefinitions().clear();
					}
				}catch(Exception e){
					System.out.print(e);
				}
				
			}
		}

		return result;
	}

	/**
	 * CMIS getTypesDescendants.
	 */
	public List<TypeDefinitionContainer> getTypesDescendants(String typeId,
			BigInteger depth, Boolean includePropertyDefinitions) {
		List<TypeDefinitionContainer> result = new ArrayList<TypeDefinitionContainer>();

		// check depth
		int d = (depth == null ? -1 : depth.intValue());
		if (d == 0) {
			throw new CmisInvalidArgumentException("Depth must not be 0!");
		}

		// set property definition flag to default value if not set
		boolean ipd = (includePropertyDefinitions == null ? false
				: includePropertyDefinitions.booleanValue());

		if (typeId == null) {
			result.add(getTypesDescendants(d, types.get(FOLDER_TYPE_ID), ipd));
			result.add(getTypesDescendants(d, types.get(DOCUMENT_TYPE_ID), ipd));
			result.add(getTypesDescendants(d, types.get(RELATIONSHIP_TYPE_ID),
					includePropertyDefinitions));
			result.add(getTypesDescendants(d, types.get(POLICY_TYPE_ID),
					includePropertyDefinitions));
		} else {
			TypeDefinitionContainer tc = types.get(typeId);
			if (tc != null) {
				result.add(getTypesDescendants(d, tc, ipd));
			}
		}

		return result;
	}

	/**
	 * Adds a type to collection.
	 */
	private void addTypeInternal(AbstractTypeDefinition type) {
		if (type == null) {
			return;
		}

		if (types.containsKey(type.getId())) {
			log.warn("Can't overwrite a type");
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

	/**
	 * Gathers the type descendants tree.
	 */
	private TypeDefinitionContainer getTypesDescendants(int depth,
			TypeDefinitionContainer tc, boolean includePropertyDefinitions) {
		TypeDefinitionContainerImpl result = new TypeDefinitionContainerImpl();

		TypeDefinition type = copyTypeDefinition(tc.getTypeDefinition());
		if (!includePropertyDefinitions) {
			type.getPropertyDefinitions().clear();
		}

		result.setTypeDefinition(type);

		if (depth != 0) {
			if (tc.getChildren() != null) {
				result.setChildren(new ArrayList<TypeDefinitionContainer>());
				for (TypeDefinitionContainer tdc : tc.getChildren()) {
					result.getChildren().add(
							getTypesDescendants(depth < 0 ? -1 : depth - 1,
									tdc, includePropertyDefinitions));
				}
			}
		}

		return result;
	}
	
	// //////////////////////////////////////////////////////////////////////////////
	// Internal Use
	// //////////////////////////////////////////////////////////////////////////////
	public Map<String,Object> getCustomModelInfo(){
		YamlManager manager = new YamlManager(ASPECT_FILE_PATH);
				
		Map<String, Object> map = new HashMap<String, Object>();
		try{
			map = (Map<String, Object>) manager.loadYml();
		}catch(Exception e){
			//TODO logging
			e.printStackTrace();
		}
		return map;
	}

	private TypeDefinition copyTypeDefinition(TypeDefinition type) {
		return WSConverter.convert(WSConverter.convert(type));
	}

	private static boolean isRootType(TypeDefinitionContainer c) {
		log.debug("c.getTypeDefinition(): " + c.getTypeDefinition());

		return false;
	}

	public void setFixedTypeManager(FixedTypeManager fixedTypeManager) {
		this.fixedTypeManager = fixedTypeManager;
	}
}