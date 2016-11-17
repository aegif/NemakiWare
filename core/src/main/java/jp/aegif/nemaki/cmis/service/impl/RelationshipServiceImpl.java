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
package jp.aegif.nemaki.cmis.service.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.RelationshipService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.lock.ThreadLockService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;

public class RelationshipServiceImpl implements RelationshipService {
	private TypeManager typeManager;
	private ContentService contentService;
	private CompileService compileService;
	private ExceptionService exceptionService;
	private ThreadLockService threadLockService;

	@Override
	public ObjectList getObjectRelationships(CallContext callContext,
			String repositoryId, String objectId,
			Boolean includeSubRelationshipTypes, RelationshipDirection relationshipDirection,
			String typeId, String filter,
			Boolean includeAllowableActions, BigInteger maxItems, BigInteger skipCount, ExtensionsData extension) {

		exceptionService.invalidArgumentRequiredString("objectId", objectId);

		Lock lock = threadLockService.getReadLock(repositoryId, objectId);
		try{
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////

			Content content = contentService.getContent(repositoryId, objectId);
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			exceptionService.permissionDenied(callContext,
					repositoryId, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, content);

			// //////////////////
			// Body of the method
			// //////////////////
			// Set default
			relationshipDirection = (relationshipDirection == null) ? RelationshipDirection.SOURCE
					: relationshipDirection;

			List<Relationship> rels = contentService.getRelationsipsOfObject(
					repositoryId, objectId, relationshipDirection);

			// Filtering results
			List<Relationship> extracted = new ArrayList<Relationship>();
			if (typeId != null) {
				Set<String> typeIds = new HashSet<String>();
				typeIds.add(typeId);

				if (includeSubRelationshipTypes) {
					List<TypeDefinitionContainer> descendants = typeManager
							.getTypesDescendants(repositoryId, typeId,
									BigInteger.valueOf(-1), false);
					for (TypeDefinitionContainer tdc : descendants) {
						typeIds.add(tdc.getTypeDefinition().getId());
					}
				}

				for (Relationship rel : rels) {
					ObjectData objectData = compileService.compileObjectData(callContext, repositoryId, rel, null, false, null, null, false);
					String objectTypeId = DataUtil.getIdProperty(objectData.getProperties(), PropertyIds.OBJECT_TYPE_ID);
					if (typeIds.contains(objectTypeId) ){
						extracted.add(rel);
					}
				}
			} else {
				extracted = rels;
			}

			// Compile to ObjectData
			return compileService.compileObjectDataList(callContext,
					repositoryId, extracted, filter,
					includeAllowableActions, IncludeRelationships.NONE, null, false, maxItems, skipCount, false, null);
		}finally{
			lock.unlock();
		}
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setCompileService(CompileService compileService) {
		this.compileService = compileService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}
}
