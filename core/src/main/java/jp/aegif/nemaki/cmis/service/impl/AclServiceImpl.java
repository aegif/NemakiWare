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
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.rag.acl.ACLExpander;
import jp.aegif.nemaki.rag.indexing.RAGIndexingService;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.DomainType;
import jp.aegif.nemaki.util.constant.PrincipalId;
import jp.aegif.nemaki.util.lock.ThreadLockService;
import jp.aegif.nemaki.util.spring.SpringContext;

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
	private RAGIndexingService ragIndexingService;
	private ACLExpander aclExpander;

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
			jp.aegif.nemaki.model.Acl acl = contentService.calculateAcl(repositoryId, content);
			Acl result = compileService.compileAcl(acl, contentService.getAclInheritedWithDefault(repositoryId, content), onlyBasicPermissions);
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
			// CRITICAL FIX (2025-12-26): Handle orphaned documents with deleted type definitions
			// When a custom type is deleted but documents using that type still exist,
			// TypeDefinition will be null. In this case, deny ACL operations on orphaned documents.
			if (td == null) {
				log.warn("ORPHANED DOCUMENT ACL: Cannot apply ACL to document '" + content.getName() +
						"' (id=" + objectId + ") - type '" + content.getObjectType() + "' no longer exists.");
				exceptionService.constraint(objectId, "applyAcl cannot be performed on orphaned document - type definition not found");
				return null;
			}
			if(!td.isControllableAcl()) exceptionService.constraint(objectId, "applyAcl cannot be performed on the object whose controllableAcl = false");
			exceptionService.constraintAclPropagationDoesNotMatch(aclPropagation);
			exceptionService.constraintPermissionDefined(repositoryId, acl, objectId);

			// //////////////////
			// Body of the method
			// //////////////////
			//Check ACL inheritance
			boolean inherited = true;	//Inheritance defaults to true if nothing input
			boolean inheritedExplicitlySet = false;  // Track if inherited was explicitly set via extension
			boolean breakingInheritance = false;
			List<CmisExtensionElement> exts = acl.getExtensions();
			if(!CollectionUtils.isEmpty(exts)){
				for(CmisExtensionElement ext : exts){
					if(ext.getName().equals("inherited")){
						inherited = Boolean.valueOf(ext.getValue());
						inheritedExplicitlySet = true;
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
				jp.aegif.nemaki.model.Acl currentAcl = contentService.calculateAcl(repositoryId, content);

				for(jp.aegif.nemaki.model.Ace localAce : currentAcl.getLocalAces()){
					jp.aegif.nemaki.model.Ace nemakiAce = new jp.aegif.nemaki.model.Ace(localAce.getPrincipalId(), localAce.getPermissions(), objectOnly);
					nemakiAcl.getLocalAces().add(nemakiAce);
				}

				for(jp.aegif.nemaki.model.Ace inheritedAce : currentAcl.getInheritedAces()){
					jp.aegif.nemaki.model.Ace nemakiAce = new jp.aegif.nemaki.model.Ace(inheritedAce.getPrincipalId(), inheritedAce.getPermissions(), objectOnly);
					nemakiAcl.getLocalAces().add(nemakiAce);
				}
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
			if(inheritedExplicitlySet){
				content.setAclInherited(inherited);
			}
	
			contentService.updateInternal(repositoryId, content);
			contentService.writeChangeEvent(callContext, repositoryId, content, nemakiAcl, ChangeType.SECURITY );

			// CRITICAL FIX (2025-01-23): Synchronously clear ACL caches for this object and all descendants
			// that inherit ACL. This prevents race conditions where child documents show stale permissions.
			clearCachesRecursively(repositoryId, content);

			// Async update RAG index ACL for this object and descendants
			updateRAGIndexACLAsync(Executors.newSingleThreadExecutor(), repositoryId, content);

			return getAcl(callContext, repositoryId, objectId, false, null);
		}finally{
			lock.unlock();
		}

	}

	/**
	 * Get RAGIndexingService from Spring context (lazy loading, optional dependency).
	 */
	private RAGIndexingService getRagIndexingService() {
		if (ragIndexingService != null) {
			return ragIndexingService;
		}
		try {
			return SpringContext.getApplicationContext().getBean(RAGIndexingService.class);
		} catch (Exception e) {
			// RAG service not available
			return null;
		}
	}

	/**
	 * Get ACLExpander from Spring context (lazy loading, optional dependency).
	 */
	private ACLExpander getAclExpander() {
		if (aclExpander != null) {
			return aclExpander;
		}
		try {
			return SpringContext.getApplicationContext().getBean(ACLExpander.class);
		} catch (Exception e) {
			// ACLExpander not available
			return null;
		}
	}

	/**
	 * Asynchronously update RAG index ACL for a document/folder and its descendants.
	 * This ensures that RAG search results reflect the latest permission changes.
	 */
	private void updateRAGIndexACLAsync(ExecutorService executorService, String repositoryId, Content content) {
		// Get RAG services from Spring context (optional dependencies)
		RAGIndexingService ragService = getRagIndexingService();
		ACLExpander expander = getAclExpander();

		// Skip if RAG indexing is not enabled or dependencies are not available
		if (ragService == null || !ragService.isEnabled() || expander == null) {
			return;
		}

		executorService.submit(() -> {
			try {
				updateRAGIndexACLRecursively(repositoryId, content, ragService, expander, new java.util.HashSet<>());
				log.info("RAG index ACL update triggered for: " + content.getId());
			} catch (Exception e) {
				log.warn("Failed to update RAG index ACL for " + content.getId() + ": " + e.getMessage());
			}
		});
	}

	/**
	 * Recursively update RAG index ACL for documents.
	 */
	private void updateRAGIndexACLRecursively(String repositoryId, Content content,
			RAGIndexingService ragService, ACLExpander expander, java.util.Set<String> visitedIds) {
		if (content == null || visitedIds.contains(content.getId())) {
			return;
		}
		visitedIds.add(content.getId());

		// Update this content's RAG ACL if it's a document
		if (content instanceof Document) {
			try {
				java.util.List<String> readers = expander.expandToReaders(repositoryId, content);
				ragService.updateDocumentACL(repositoryId, content.getId(), readers);
			} catch (Exception e) {
				log.warn("Failed to update RAG ACL for document " + content.getId() + ": " + e.getMessage());
			}
		}

		// Recursively process children if this is a folder
		if (content.isFolder()) {
			List<Content> children = contentService.getChildren(repositoryId, content.getId());
			if (!CollectionUtils.isEmpty(children)) {
				for (Content child : children) {
					// Only update children that inherit ACL
					if (contentService.getAclInheritedWithDefault(repositoryId, child)) {
						updateRAGIndexACLRecursively(repositoryId, child, ragService, expander, visitedIds);
					}
				}
			}
		}
	}

	/**
	 * Synchronously clear ACL caches for a content item and all its descendants that inherit ACL.
	 * This ensures that child documents immediately see updated permissions after parent ACL changes.
	 *
	 * CRITICAL FIX (2025-01-23): Changed from async to sync execution to prevent race conditions
	 * where users see stale cached ACL data on child documents after changing parent permissions.
	 */
	private void clearCachesRecursively(String repositoryId, Content content) {
		java.util.Set<String> visitedIds = new java.util.HashSet<>();
		java.util.Queue<Content> queue = new java.util.LinkedList<>();
		queue.offer(content);

		while (!queue.isEmpty()) {
			Content current = queue.poll();

			if (visitedIds.contains(current.getId())) {
				continue;
			}
			visitedIds.add(current.getId());

			// SYNC: Clear cache immediately instead of submitting async task
			nemakiCachePool.get(repositoryId).removeCmisAndContentCache(current.getId());

			if (current.isFolder()) {
				List<Content> children = contentService.getChildren(repositoryId, current.getId());
				if (!CollectionUtils.isEmpty(children)) {
					for (Content child : children) {
						// Only clear cache for children that inherit ACL (their calculated ACL depends on parent)
						if (contentService.getAclInheritedWithDefault(repositoryId, child) && !visitedIds.contains(child.getId())) {
							queue.offer(child);
						}
					}
				}
			}
		}

		log.debug("Synchronously cleared ACL caches for " + visitedIds.size() + " items");
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
			nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
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
			// Using getAcl() for stored ACL; calculateAcl() would compute inherited ACL
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

	public void setRagIndexingService(RAGIndexingService ragIndexingService) {
		this.ragIndexingService = ragIndexingService;
	}

	public void setAclExpander(ACLExpander aclExpander) {
		this.aclExpander = aclExpander;
	}
}
