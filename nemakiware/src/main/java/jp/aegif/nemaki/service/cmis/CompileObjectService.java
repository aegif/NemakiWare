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
package jp.aegif.nemaki.service.cmis;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;

import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public interface CompileObjectService {
	public ObjectData compileObjectData(CallContext context,
			Content content, String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter, Boolean includeAcl, Map<String, String> aliases);
	
	public <T> ObjectList compileObjectDataList(CallContext callContext,
			List<T> contents, String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter, Boolean includeAcl, BigInteger maxItems, BigInteger skipCount);
	
	public ObjectList compileChangeDataList(CallContext context, List<Change> changes,
			Holder<String> changeLogToken, Boolean includeProperties, String filter,
			Boolean includePolicyIds, Boolean includeAcl);
	
	public Properties compileProperties(Content content, Set<String> filter,
			ObjectInfoImpl objectInfo);
	
	public AllowableActions compileAllowableActions(CallContext callContext,
			Content content);
	
	public Set<String> splitFilter(String filter);
}
