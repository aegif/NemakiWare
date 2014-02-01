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
package jp.aegif.nemaki.repository.type;

import java.math.BigInteger;
import java.util.List;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.server.CallContext;

/**
 * Type Manager class
 */
public interface TypeManager extends
		org.apache.chemistry.opencmis.server.support.TypeManager {

	
	/**
	 * Refresh global variables and sync with DB
	 */
	public void refreshTypes();

	/**
	 * Get only TypeDefinition(not TypeDefinitionContainer)
	 * @param typeId
	 * @return
	 */
	public TypeDefinition getTypeDefinition(String typeId);

	/**
	 * Get properties other than
	 * @param typeId
	 * @return
	 */
	public List<PropertyDefinition<?>> getSpecificPropertyDefinitions(
			String typeId);

	/**
	 * CMIS getTypesChildren. If parent type id is not specified, return only
	 * base types.
	 */
	public TypeDefinitionList getTypesChildren(CallContext context,
			String typeId, boolean includePropertyDefinitions,
			BigInteger maxItems, BigInteger skipCount);

	/**
	 * CMIS getTypesDescendants.
	 */
	public List<TypeDefinitionContainer> getTypesDescendants(String typeId,
			BigInteger depth, Boolean includePropertyDefinitions);
	
	/**
	 * Get a type definition Internal Use
	 *
	 * @param content
	 * @return
	 */
	public TypeDefinition getTypeDefinition(Content content);
	
	/**
	 * List up specification-default property ids
	 *
	 * @return
	 */
	public List<String> getSystemPropertyIds();

	public AbstractTypeDefinition buildTypeDefinitionFromDB(NemakiTypeDefinition nemakiType);
	
	public Object getSingleDefaultValue(String propertyId, String typeId);

}
