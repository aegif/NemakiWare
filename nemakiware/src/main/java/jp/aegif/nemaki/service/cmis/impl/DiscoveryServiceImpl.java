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
package jp.aegif.nemaki.service.cmis.impl;

import java.math.BigInteger;
import java.util.List;

import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.query.QueryProcessor;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.DiscoveryService;
import jp.aegif.nemaki.service.cmis.ExceptionService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections.CollectionUtils;

/**
 * Discovery Service implementation for CouchDB.
 * 
 */
public class DiscoveryServiceImpl implements DiscoveryService {

	private QueryProcessor queryProcessor;
	private ContentService contentService;
	private ExceptionService exceptionService;
	private CompileObjectService compileObjectService;

	public ObjectList query(TypeManager typeManager, CallContext context,
			String repositoryId, String statement, Boolean searchAllVersions,
			Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		exceptionService.invalidArgumentRequiredString("statement", statement);
		
		// //////////////////
		// Specific Exception
		// //////////////////
		//TODO implement
		
		// //////////////////
		// Body of the method
		return queryProcessor.query(typeManager,
				context, context.getUsername(), repositoryId, statement,
				searchAllVersions, includeAllowableActions,
				includeRelationships, renditionFilter, maxItems, skipCount);
	}

	/**
	 *Return ChangeLog just for Documents & Folder type, and Not for their attachments 
	 *TODO includeAcl,includePolicyIds is not valid
	 */
	public ObjectList getContentChanges(CallContext context,
			Holder<String> changeLogToken, Boolean includeProperties,
			String filter, Boolean includePolicyIds, Boolean includeAcl,
			BigInteger maxItems, ExtensionsData extension) {
		// //////////////////
		// General Exception
		// //////////////////
		//NONE
		
		// //////////////////
		// Specific Exception
		// //////////////////
		exceptionService.invalidArgumentChangeEventNotAvailable(changeLogToken);
		
		// //////////////////
		// Body of the method
		// //////////////////
		List<Change> changes = contentService.getLatestChanges(context, changeLogToken, includeProperties, filter, includePolicyIds, includeAcl, maxItems, extension);
		if(!CollectionUtils.isEmpty(changes)){
			String latestToken = String.valueOf(changes.get(changes.size()-1).getChangeToken());
			changeLogToken.setValue(latestToken);
		}
		
		return compileObjectService.compileChangeDataList(context, changes, changeLogToken, includeProperties, filter, includePolicyIds, includeAcl);
	}
	
	public void setQueryProcessor(QueryProcessor queryProcessor) {
		this.queryProcessor = queryProcessor;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setCompileObjectService(CompileObjectService compileObjectService) {
		this.compileObjectService = compileObjectService;
	}
}
