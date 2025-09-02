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
package jp.aegif.nemaki.cmis.aspect.type;

import java.math.BigInteger;
import java.util.Collection;
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
public interface TypeManager{

	
	/**
	 * Refresh global variables and sync with DB
	 */
	public void refreshTypes();

	/**
	 * Get only TypeDefinition(not TypeDefinitionContainer)
	 * @param repositoryId TODO
	 * @param typeId
	 * @return
	 */
	public TypeDefinition getTypeDefinition(String repositoryId, String typeId);

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
	 * @param repositoryId TODO
	 */
	public TypeDefinitionList getTypesChildren(CallContext context,
			String repositoryId, String typeId,
			boolean includePropertyDefinitions, BigInteger maxItems, BigInteger skipCount);

	/**
	 * CMIS getTypesDescendants.
	 * @param repositoryId TODO
	 */
	public List<TypeDefinitionContainer> getTypesDescendants(String repositoryId,
			String typeId, BigInteger depth, Boolean includePropertyDefinitions);
	
	/**
	 * Get a type definition Internal Use
	 * @param repositoryId TODO
	 * @param content
	 *
	 * @return
	 */
	public TypeDefinition getTypeDefinition(String repositoryId, Content content);
	
	/**
	 * List up specification-default property ids
	 *
	 * @return
	 */
	public List<String> getSystemPropertyIds();

	public AbstractTypeDefinition buildTypeDefinitionFromDB(String repositoryId, NemakiTypeDefinition nemakiType);
	
	public Object getSingleDefaultValue(String propertyId, String typeId, String repositoryId);
	
	/**
	 * Get a property definition specified with its query name and under specified type
	 * @param repositoryId TODO
	 * @param typeDefinition
	 * @param propQueryName
	 * @return
	 */
	public PropertyDefinition<?> getPropertyDefinitionForQueryName(String repositoryId,
			TypeDefinition typeDefinition, String propQueryName);
	/**
	 * Get core attributes of PropertyDefinition specified with given query name
	 * @param queryName
	 * @return PropertyDefinition with only core attribute(Id, QueryName, ProeprtyType, Cardinality)
	 */
	public PropertyDefinition<?> getPropertyDefinitionCoreForQueryName(String queryName);
	
	/**
     * return a type definition from the type definition id
     * 
     * @param typeId
     *            id of the type definition
     * @return type definition for this id
     */
    TypeDefinitionContainer getTypeById(String repositoryId, String typeId);

    /**
     * return a type definition from the type query name or null if not found
     * @param repositoryId TODO
     * @param typeQueryName
     *            query name of the type definition
     * 
     * @return type definition for this query name
     */
    TypeDefinition getTypeByQueryName(String repositoryId, String typeQueryName);

    /**
     * return a list of all types known in this repository
     * Note: This method is not needed for the query parser.
     * @param repositoryId TODO
     * 
     * @return
     *      list of type definitions
     */
    Collection<TypeDefinitionContainer> getTypeDefinitionList(String repositoryId);

    /**
     * return a list of the root types as defined in the CMIS spec (for
     * document, folder, policy and relationship
     * Note: This method is not needed for the query parser.
     * @param repositoryId TODO
     * 
     * @return
     *      list of type definitions
     */
    List<TypeDefinitionContainer> getRootTypes(String repositoryId);

    /**
     * retrieve the property id from a type for a given property query name 
     * @param repositoryId TODO
     * @param typeDefinition
     *      type definition containing query name
     * @param propQueryName
     *      query name of property
     * 
     * @return
     *      property id of property or null if not found
     */
    String getPropertyIdForQueryName(String repositoryId, TypeDefinition typeDefinition, String propQueryName);

    /**
     * Add a type to the type system. Add all properties from inherited types,
     * add type to children of parent types.
     * Note: This method is not needed for the query parser.
     * @param repositoryId TODO
     * @param typeDefinition
     *            new type to add
     * @param addInheritedProperties
     *            add properties from supertype to type definition
     */
    void addTypeDefinition(String repositoryId, TypeDefinition typeDefinition, boolean addInheritedProperties);

    /**
     * Modify an existing type definition.
     * Note: This method is not needed for the query parser.
     * @param repositoryId TODO
     * @param typeDefinition
     *            type to be modified
     */
    void updateTypeDefinition(String repositoryId, TypeDefinition typeDefinition);

    /**
     * Delete a type from the type system. Delete will succeed only if type is
     * not in use. Otherwise an exception is thrown.
     * Note: This method is not needed for the query parser.
     * @param repositoryId TODO
     * @param typeId
     *            id of type to be deleted
     */
    void deleteTypeDefinition(String repositoryId, String typeId);
    
    /**
     * Mark a type as being deleted to prevent infinite recursion during cache refresh
     * @param typeId the type ID being deleted
     */
    void markTypeBeingDeleted(String typeId);
    
    /**
     * Unmark a type as being deleted after deletion completes
     * @param typeId the type ID that was deleted
     */
    void unmarkTypeBeingDeleted(String typeId);
}
