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
package jp.aegif.nemaki.cmis.service;

import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

import jp.aegif.nemaki.util.spring.aspect.log.LogParam;

/**
 * Discovery Service interface.
 */
public interface DiscoveryService {

	/**
	 * Executes a CMIS query statement against the contents of the repository.
	 * @param repositoryId TODO
	 */
	ObjectList query(@LogParam("callContext")CallContext callContext, @LogParam("repositoryId")String repositoryId,
			@LogParam("statement")String statement, @LogParam("searchAllVersions")Boolean searchAllVersions, @LogParam("includeAllowableActions")Boolean includeAllowableActions,
			@LogParam("includeRelationships")IncludeRelationships includeRelationships,
			@LogParam("renditionFilter")String renditionFilter, @LogParam("maxItems")BigInteger maxItems,
			@LogParam("skipCount")BigInteger skipCount, @LogParam("extension")ExtensionsData extension);

	/**
	 * Get the list of object that have changed since a given point in the past.
	 * 
	 * TODO Not Yet Implemented
	 * @param repositoryId TODO
	 */
	ObjectList getContentChanges(@LogParam("callContext")CallContext callContext,
			@LogParam("repositoryId")String repositoryId, @LogParam("changeLogToken") Holder<String> changeLogToken,
			@LogParam("includeProperties")Boolean includeProperties, @LogParam("filter")String filter, @LogParam("includePolicyIds")Boolean includePolicyIds,
			@LogParam("includeAcl")Boolean includeAcl, @LogParam("maxItems")BigInteger maxItems, @LogParam("extension")ExtensionsData extension);
}
