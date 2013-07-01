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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;

import org.apache.chemistry.opencmis.commons.impl.WSConverter;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionContainerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.TypeDefinitionListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

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
	
	/**
	 * Map of all types. It is abbreviation of fixedTypeManager.getTypes()
	 */
	private Map<String, TypeDefinitionContainer> types;
	
	public TypeManager() {

	}

	public TypeManager(FixedTypeManager fixedTypeManager) {
		types = fixedTypeManager.getTypes();
	}

	
	/**
	 * Adds a type to collection with inheriting base type properties.
	 */
	public boolean addType(TypeDefinition type) {
		if (type == null) {
			return false;
		}

		if (type.getBaseTypeId() == null) {
			return false;
		}

		// find base type
		TypeDefinition baseType = null;
		if (type.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT) {
			baseType = copyTypeDefinition(types.get(DOCUMENT_TYPE_ID)
					.getTypeDefinition());
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_FOLDER) {
			baseType = copyTypeDefinition(types.get(FOLDER_TYPE_ID)
					.getTypeDefinition());
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP) {
			baseType = copyTypeDefinition(types.get(RELATIONSHIP_TYPE_ID)
					.getTypeDefinition());
		} else if (type.getBaseTypeId() == BaseTypeId.CMIS_POLICY) {
			baseType = copyTypeDefinition(types.get(POLICY_TYPE_ID)
					.getTypeDefinition());
		} else {
			return false;
		}

		AbstractTypeDefinition newType = (AbstractTypeDefinition) copyTypeDefinition(type);

		// copy property definition
		for (PropertyDefinition<?> propDef : baseType.getPropertyDefinitions()
				.values()) {
			((AbstractPropertyDefinition<?>) propDef).setIsInherited(true);
			newType.addPropertyDefinition(propDef);
		}

		// add it
		addTypeInternal(newType);

		log.info("Added type '" + newType.getId() + "'.");

		return true;
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
			if (skip < 1) {
				result.getList().add(
						copyTypeDefinition(types.get(FOLDER_TYPE_ID)
								.getTypeDefinition()));
				max--;
			}
			if ((skip < 2) && (max > 0)) {
				result.getList().add(
						copyTypeDefinition(types.get(DOCUMENT_TYPE_ID)
								.getTypeDefinition()));
				max--;
			}

			result.setHasMoreItems((result.getList().size() + skip) < 2);
			result.setNumItems(BigInteger.valueOf(2));
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
				type.getPropertyDefinitions().clear();
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