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
 * You should have received a copy of the GNU General Public Licensealong with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.cmis.service.impl;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.ExceptionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.service.AclService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.constant.PrincipalId;
import jp.aegif.nemaki.util.lock.ThreadLockService;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Discovery Service implementation for CouchDB.
 *
 */
public class AclServiceImpl implements AclService {

	private static final Log log = LogFactory.getLog(AclServiceImpl.class);

	private ContentService contentService;
	private CompileService compileService;
	private ExceptionService exceptionService;
	private TypeManager typeManager;
	private ThreadLockService threadLockService;
	private NemakiCachePool nemakiCachePool;
	private RepositoryInfoMap repositoryInfoMap;

	@Override
	public Acl getAcl(CallContext callContext, String repositoryId,
			String objectId, Boolean onlyBasicPermissions, ExtensionsData extension) {

		exceptionService.invalidArgumentRequired("objectId", objectId);

		Lock lock = threadLockService.getReadLock(repositoryId, objectId);

		try{
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////

			Content content = contentService.getContent(repositoryId, objectId);
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			exceptionService.permissionDenied(callContext,repositoryId, PermissionMapping.CAN_GET_ACL_OBJECT, content);

			// //////////////////
			// Body of the method
			// //////////////////
			System.err.println("!!! ACL SERVICE: getAcl() called for objectId=" + objectId + ", name=" + content.getName());
			jp.aegif.nemaki.model.Acl acl = contentService.calculateAcl(repositoryId, content);
			System.err.println("!!! ACL SERVICE: calculateAcl() returned: localAces=" + acl.getLocalAces().size() + ", inheritedAces=" + acl.getInheritedAces().size());
			Acl result = compileService.compileAcl(acl, contentService.getAclInheritedWithDefault(repositoryId, content), onlyBasicPermissions);
			System.err.println("!!! ACL SERVICE: compileAcl() returned " + result.getAces().size() + " ACEs");
			for (Ace ace : result.getAces()) {
				System.err.println("!!!   ACE: principalId=" + ace.getPrincipalId() + ", direct=" + ace.isDirect() + ", permissions=" + ace.getPermissions());
			}
			return result;
		}finally{
			lock.unlock();
		}
	}

	@Override
	public Acl applyAcl(CallContext callContext, String repositoryId, String objectId,
			Acl acl, AclPropagation aclPropagation) {
		exceptionService.invalidArgumentRequired("objectId", objectId);

		Lock lock = threadLockService.getReadLock(repositoryId, objectId);

		try{
			lock.lock();

			// //////////////////
			// General Exception
			// //////////////////

			Content content = contentService.getContent(repositoryId, objectId);
			exceptionService.objectNotFound(DomainType.OBJECT, content, objectId);
			exceptionService.permissionDenied(callContext,repositoryId, PermissionMapping.CAN_APPLY_ACL_OBJECT, content);

			// //////////////////
			// Specific Exception
			// //////////////////
			TypeDefinition td = typeManager.getTypeDefinition(repositoryId, content);
			if(!td.isControllableAcl()) exceptionService.constraint(objectId, "applyAcl cannot be performed on the object whose controllableAcl = false");
			exceptionService.constraintAclPropagationDoesNotMatch(aclPropagation);
			exceptionService.constraintPermissionDefined(repositoryId, acl, objectId);

			// //////////////////
			// Body of the method
			// //////////////////
			//Check ACL inheritance
			boolean inherited = true;	//Inheritance defaults to true if nothing input
			boolean breakingInheritance = false;
			List<CmisExtensionElement> exts = acl.getExtensions();
			if(!CollectionUtils.isEmpty(exts)){
				for(CmisExtensionElement ext : exts){
					if(ext.getName().equals("inherited")){
						inherited = Boolean.valueOf(ext.getValue());
						// If changing from inherited=true to inherited=false, we're breaking inheritance
						if(!inherited && contentService.getAclInheritedWithDefault(repositoryId, content)){
							breakingInheritance = true;
						}
					}
				}
			}

			jp.aegif.nemaki.model.Acl nemakiAcl = new jp.aegif.nemaki.model.Acl();
			//REPOSITORYDETERMINED or PROPAGATE is considered as PROPAGATE
			boolean objectOnly = (aclPropagation == AclPropagation.OBJECTONLY)? true : false;
	
			if(breakingInheritance){
				System.err.println("!!! ACL SERVICE: Breaking inheritance for objectId=" + objectId);
				jp.aegif.nemaki.model.Acl currentAcl = contentService.calculateAcl(repositoryId, content);
				System.err.println("!!! ACL SERVICE: Current ACL has " + currentAcl.getLocalAces().size() + " local ACEs and " + currentAcl.getInheritedAces().size() + " inherited ACEs");
		
				for(jp.aegif.nemaki.model.Ace localAce : currentAcl.getLocalAces()){
					jp.aegif.nemaki.model.Ace nemakiAce = new jp.aegif.nemaki.model.Ace(localAce.getPrincipalId(), localAce.getPermissions(), objectOnly);
					nemakiAcl.getLocalAces().add(nemakiAce);
				}
		
				for(jp.aegif.nemaki.model.Ace inheritedAce : currentAcl.getInheritedAces()){
					jp.aegif.nemaki.model.Ace nemakiAce = new jp.aegif.nemaki.model.Ace(inheritedAce.getPrincipalId(), inheritedAce.getPermissions(), objectOnly);
					nemakiAcl.getLocalAces().add(nemakiAce);
					System.err.println("!!! ACL SERVICE: Converted inherited ACE to direct: principalId=" + inheritedAce.getPrincipalId() + ", permissions=" + inheritedAce.getPermissions());
				}
				System.err.println("!!! ACL SERVICE: After breaking inheritance, nemakiAcl has " + nemakiAcl.getLocalAces().size() + " local ACEs");
			} else {
				for(Ace ace : acl.getAces()){
					if(ace.isDirect()){
						jp.aegif.nemaki.model.Ace nemakiAce = new jp.aegif.nemaki.model.Ace(ace.getPrincipalId(), ace.getPermissions(), objectOnly);
						nemakiAcl.getLocalAces().add(nemakiAce);
					}
				}
			}

			convertSystemPrinciaplId(repositoryId, nemakiAcl);
			content.setAcl(nemakiAcl);
	
			// CRITICAL: Set aclInherited flag AFTER building the ACL and BEFORE updating
			if(!CollectionUtils.isEmpty(exts)){
				Boolean currentInherited = contentService.getAclInheritedWithDefault(repositoryId, content);
				System.err.println("!!! ACL SERVICE: Before update - currentInherited=" + currentInherited + ", newInherited=" + inherited + " for objectId=" + objectId);
				if(!currentInherited.equals(inherited)){
					content.setAclInherited(inherited);
					System.err.println("!!! ACL SERVICE: Set aclInherited=" + inherited + " for objectId=" + objectId);
				} else {
					System.err.println("!!! ACL SERVICE: Skipping setAclInherited because values are equal");
				}
			}
	
			contentService.updateInternal(repositoryId, content);
			contentService.writeChangeEvent(callContext, repositoryId, content, nemakiAcl, ChangeType.SECURITY );

			// CRITICAL FIX (2025-11-12): Clear BOTH CMIS and Content caches synchronously
			// before calling getAcl() to return updated ACL. Without this, getAcl() returns stale cached data.
			nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
			System.err.println("!!! ACL SERVICE: Cleared CMIS and Content caches for objectId=" + objectId);

			clearCachesRecursively(Executors.newWorkStealingPool(), callContext, repositoryId, content, true);

			// Temporary stopping write change evnets descendant
			// TODO : depend on configuration
			//writeChangeEventsRecursively(Executors.newWorkStealingPool(), callContext, repositoryId, content, true);

			return getAcl(callContext, repositoryId, objectId, false, null);
		}finally{
			lock.unlock();
		}

	}

	private void clearCachesRecursively(ExecutorService executorService, CallContext callContext, final String repositoryId, Content content, boolean executeOnParent){
		clearCachesRecursively(executorService, callContext, repositoryId, content, executeOnParent, new java.util.HashSet<String>());
	}

	private void clearCachesRecursively(ExecutorService executorService, CallContext callContext, final String repositoryId, Content content, boolean executeOnParent, java.util.Set<String> visitedIds){
		if (visitedIds.contains(content.getId())) {
			return;
		}
		visitedIds.add(content.getId());

		java.util.Queue<Content> queue = new java.util.LinkedList<>();
		queue.offer(content);
		
		while (!queue.isEmpty()) {
			Content current = queue.poll();
			
			if (visitedIds.contains(current.getId())) {
				continue;
			}
			visitedIds.add(current.getId());
			
			executorService.submit(new ClearCacheTask(repositoryId, current.getId()));
			
			if (current.isFolder()) {
				List<Content> children = contentService.getChildren(repositoryId, current.getId());
				if (!CollectionUtils.isEmpty(children)) {
					for (Content child : children) {
						if (contentService.getAclInheritedWithDefault(repositoryId, child) && !visitedIds.contains(child.getId())) {
							queue.offer(child);
						}
					}
				}
			}
		}
	}

	private class ClearCacheTask implements Runnable{
		private String repositoryId;
		private String objectId;

		public ClearCacheTask(String repositoryId, String objectId) {
			super();
			this.repositoryId = repositoryId;
			this.objectId = objectId;
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub
			nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
		}
	}

	private class ClearCachesRecursivelyTask implements Runnable{
		private ExecutorService executorService;
		private CallContext callContext;
		private String repositoryId;
		private Content content;

		public ClearCachesRecursivelyTask(ExecutorService executorService, CallContext callContext, String repositoryId, Content content) {
			super();
			this.executorService = executorService;
			this.callContext = callContext;
			this.repositoryId = repositoryId;
			this.content = content;
		}

		@Override
		public void run() {
			clearCachesRecursively(executorService, callContext, repositoryId, content, true);
		}
	}

private void writeChangeEventsRecursively(ExecutorService executorService, CallContext callContext, final String repositoryId, Content content, boolean executeOnParent){

		//Call threads for recursive applyAcl
		if(content.isFolder()){
			if(executeOnParent){
				executorService.submit(new ClearCacheTask(repositoryId, content.getId()));
			}

			List<Content> children = contentService.getChildren(repositoryId, content.getId());
			if(CollectionUtils.isEmpty(children)){
				return;
			}

			for(Content child : children){
				if(contentService.getAclInheritedWithDefault(repositoryId, child)){
					executorService.submit(new WriteChangeEventsRecursivelyTask(executorService, callContext, repositoryId, child));
				}
			}
		}else{
			executorService.submit(new WriteChangeEventTask(callContext, repositoryId, content));
		}
	}

	private class WriteChangeEventTask implements Runnable{
		private CallContext callContext;
		private String repositoryId;
		private Content content;

		public WriteChangeEventTask(CallContext callContext, String repositoryId, Content content) {
			super();
			this.callContext = callContext;
			this.repositoryId = repositoryId;
			this.content = content;
		}

		@Override
		public void run() {
			// TODO content.getAcl()? content.calculateAcl()?
			contentService.writeChangeEvent(callContext, repositoryId, content, content.getAcl(), ChangeType.SECURITY);
		}
	}

	private class WriteChangeEventsRecursivelyTask implements Runnable{
		private ExecutorService executorService;
		private CallContext callContext;
		private String repositoryId;
		private Content content;

		public WriteChangeEventsRecursivelyTask(ExecutorService executorService, CallContext callContext, String repositoryId, Content content) {
			super();
			this.executorService = executorService;
			this.callContext = callContext;
			this.repositoryId = repositoryId;
			this.content = content;
		}

		@Override
		public void run() {
			writeChangeEventsRecursively(executorService, callContext, repositoryId, content, true);
		}
	}

	private void convertSystemPrinciaplId(String repositoryId, jp.aegif.nemaki.model.Acl acl){
		List<jp.aegif.nemaki.model.Ace> aces = acl.getAllAces();
		for (jp.aegif.nemaki.model.Ace ace : aces) {
			RepositoryInfo info = repositoryInfoMap.get(repositoryId);

			//Convert anonymous to the form of database
			String anonymous = info.getPrincipalIdAnonymous();
			if (anonymous.equals(ace.getPrincipalId())) {
				ace.setPrincipalId(PrincipalId.ANONYMOUS_IN_DB);
			}

			//Convert anyone to the form of database
			String anyone = info.getPrincipalIdAnyone();
			if (anyone.equals(ace.getPrincipalId())) {
				ace.setPrincipalId(PrincipalId.ANYONE_IN_DB);

			}
		}
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setExceptionService(ExceptionService exceptionService) {
		this.exceptionService = exceptionService;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setThreadLockService(ThreadLockService threadLockService) {
		this.threadLockService = threadLockService;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

	public void setCompileService(CompileService compileService) {
		this.compileService = compileService;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}
}
