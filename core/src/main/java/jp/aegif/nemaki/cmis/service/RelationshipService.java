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
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import jp.aegif.nemaki.util.spring.aspect.log.LogParam;

public interface RelationshipService {
	public ObjectList getObjectRelationships(@LogParam("callContext") CallContext callContext, @LogParam("repositoryId") String repositoryId,
			@LogParam("objectId") String objectId, @LogParam("includeSubRelationshipTypes") Boolean includeSubRelationshipTypes,
			@LogParam("relationshipDirection") RelationshipDirection relationshipDirection, @LogParam("typeId") String typeId,
			@LogParam("filter") String filter, @LogParam("includeAllowableActions") Boolean includeAllowableActions,
			@LogParam("maxItems") BigInteger maxItems, @LogParam("skipCount") BigInteger skipCount,
			@LogParam("extension") ExtensionsData extension);
}
