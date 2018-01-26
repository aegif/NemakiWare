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
package jp.aegif.nemaki.cmis.aspect;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;

import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;

public interface CompileService {
	public ObjectData compileObjectData(CallContext context,
			String repositoryId, Content content, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter, Boolean includeAcl);

	public <T extends Content> ObjectList compileObjectDataList(CallContext callContext,
			String repositoryId, List<T> contents, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter, Boolean includeAcl, BigInteger maxItems, BigInteger skipCount, boolean folderOnly, String orderBy);
	
	public <T extends Content> ObjectList compileObjectDataListForSearchResult(CallContext callContext,
			String repositoryId, List<T> contents, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships, String renditionFilter, Boolean includeAcl, BigInteger maxItems, BigInteger skipCount, boolean folderOnly, String orderBy, long numFound);

	public ObjectList compileChangeDataList(CallContext context, String repositoryId,
			List<Change> changes, Holder<String> changeLogToken, Boolean includeProperties,
			String filter, Boolean includePolicyIds, Boolean includeAcl);

	public org.apache.chemistry.opencmis.commons.data.Acl compileAcl(
			Acl acl, Boolean isInherited, Boolean onlyBasicPermissions);


	public PropertiesImpl compileProperties(CallContext callContext, String repositoryId, Content content);

	public AllowableActions compileAllowableActions(CallContext callContext, String repositoryId, Content content, Acl acl);

	public AllowableActions compileAllowableActions(CallContext callContext,
			String repositoryId, Content content);

	public Set<String> splitFilter(String filter);
}
