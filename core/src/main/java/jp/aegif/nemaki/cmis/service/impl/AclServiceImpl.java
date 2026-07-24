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
	/**
	 * Durable reconciliation queue for search-index ACL refreshes that fail
	 * asynchronously. Optional — when unwired (tests / RAG-disabled minimal
	 * deployments), failures degrade to WARN as before.
	 */
	private jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconciliationService;

	public void setReconciliationService(
			jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconciliationService) {
		this.reconciliationService = reconciliationService;
	}

	/**
	 * Content-DB connector pool — used ONLY by the reconciliation re-drive to make an
	 * authoritative (cache-bypassing) existence probe that distinguishes a genuine
	 * 404 from a transient read error (the DAO layers collapse both to {@code null}).
	 * Optional; when unwired the re-drive treats an unresolvable object as a retry.
	 */
	private jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool connectorPool;

	public void setConnectorPool(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool connectorPool) {
		this.connectorPool = connectorPool;
	}

	/** Tri-state authoritative existence for the reconciliation re-drive. */
	private enum DocState { FOUND, NOT_FOUND, ERROR }

	/**
	 * Authoritative (cache-bypassing) existence probe against the repository's
	 * content DB: a genuine 404 (or a {@code _deleted} tombstone) is
	 * {@link DocState#NOT_FOUND}; any other failure is {@link DocState#ERROR} (so it
	 * is retried, not mistaken for a deletion). {@link DocState#FOUND} otherwise.
	 * ERROR when the pool is unwired (fail-safe: the caller retries rather than
	 * completing on an unverifiable read).
	 */
	private DocState probeContentExists(String repositoryId, String objectId) {
		if (connectorPool == null) {
			return DocState.ERROR;
		}
		try {
			jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper client =
					connectorPool.getClient(repositoryId);
			if (client == null) {
				return DocState.ERROR;
			}
			com.ibm.cloud.cloudant.v1.model.Document doc = client.getClient().getDocument(
					new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
							.db(client.getDatabaseName()).docId(objectId).build())
					.execute().getResult();
			if (doc == null) {
				return DocState.NOT_FOUND;
			}
			if (doc.getProperties() != null && Boolean.TRUE.equals(doc.getProperties().get("_deleted"))) {
				return DocState.NOT_FOUND; // tombstone
			}
			return DocState.FOUND;
		} catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
			return DocState.NOT_FOUND;
		} catch (Exception e) {
			log.warn("Reconcile: existence probe errored for " + objectId + ": " + e.getMessage());
			return DocState.ERROR;
		}
	}

	// Shared single-thread executor for async RAG ACL updates.
	// CallerRunsPolicy provides backpressure: when the queue is full the calling
	// thread executes the task synchronously, ensuring no ACL update is silently
	// lost (critical for RAG search permission consistency).
	private final ExecutorService ragAclExecutor = new java.util.concurrent.ThreadPoolExecutor(
			0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
			new java.util.concurrent.LinkedBlockingQueue<>(256),
			Thread.ofVirtual().name("RAG-ACL-vt-", 0).factory(),
			new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

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
	
			// ACL-in-Solr: evict THIS object's cached ACL before the re-index that
			// updateInternal triggers, so createSolrDocument -> expandToReaders ->
			// calculateAcl recomputes the `readers` field from the just-applied ACL
			// instead of a stale cache entry (calculateAcl returns the cached Acl
			// when present). Descendants are evicted by clearCachesRecursively AFTER
			// the DB update below, so they re-read the new parent ACL.
			nemakiCachePool.get(repositoryId).removeCmisAndContentCache(content.getId());

			// skipRAGIndexing=true: ACL change does not alter document content,
			// so TEI re-embedding is unnecessary. RAG ACL is updated separately below.
			contentService.updateInternal(repositoryId, content, true);
			contentService.writeChangeEvent(callContext, repositoryId, content, nemakiAcl, ChangeType.SECURITY );

			// CRITICAL FIX (2025-01-23): Synchronously clear ACL caches for this object and all descendants
			// that inherit ACL. This prevents race conditions where child documents show stale permissions.
			clearCachesRecursively(repositoryId, content);

			// Async update RAG index ACL for this object and descendants
			updateRAGIndexACLAsync(repositoryId, content);

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
	 * Get SolrUtil from the Spring context (lazy). Used to refresh the CMIS
	 * content `readers` field (ACL-in-Solr) on inheriting descendants after an
	 * ACL change — the changed object itself is re-indexed by updateInternal.
	 */
	private jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil getSolrUtil() {
		try {
			return SpringContext.getApplicationContext()
					.getBean(jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil.class);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Refresh the search-index ACL (`readers`) impacted by a move: the INHERITING
	 * DESCENDANTS of a moved folder, AND the RELATIONSHIPS referencing the moved
	 * object (leaf or folder). When an object is moved, the effective (inherited)
	 * ACL of the object and of every ACL-inheriting descendant changes because
	 * their ancestor chain changed. The moved object's OWN content readers + RAG
	 * block are re-indexed by {@code ContentServiceImpl.move} (which evicts its ACL
	 * cache first), but move does NOT reverse-look-up relationships; this method
	 * evicts the affected ACL caches and re-indexes the descendants' readers plus
	 * the readers of relationships whose source/target is the moved object
	 * (mirroring the applyAcl path), so a public->private move leaves neither stale
	 * over-permissive descendant readers nor a stale/never-updated relationship. A
	 * moved LEAF still enters here (it has no descendants but can be a relationship
	 * endpoint); only {@code content == null} is a no-op.
	 */
	public void refreshMovedSubtreeSearchIndexAcl(String repositoryId, Content content) {
		if (content == null) {
			return;
		}
		// The moved object's OWN content readers + RAG block are already refreshed
		// by ContentServiceImpl.move. What move does NOT do is reverse-look-up the
		// RELATIONSHIPS that reference the moved object — their readers derive from
		// readers(source) UNION readers(target), and the moved object's effective
		// ACL just changed, so they must be re-indexed here as well. This is needed
		// for a moved LEAF (e.g. an ingest-created document with hasAttachment /
		// attachedToRecord / derivedFromContext relationships) just as much as for a
		// folder — a leaf has no inheriting descendants but it can be a relationship
		// endpoint. updateSearchIndexACLRecursively(isRoot=true) skips the root's own
		// content-readers re-index but still refreshes its relationships, and only
		// recurses into inheriting descendants when the root is a folder, so passing a
		// leaf here does exactly the relationship refresh and nothing more.
		final jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconcile = reconciliationService;
		// Evict aclCache for the root + all inheriting descendants so their readers
		// are recomputed from the new ancestor chain (must happen before re-index).
		// Eviction is a PRECONDITION — if it fails, do NOT submit the re-index (it
		// would overwrite correct readers with stale-cache values); enqueue for the
		// reconciliation poll to re-drive later with a fresh eviction.
		try {
			clearCachesRecursively(repositoryId, content);
		} catch (Exception e) {
			log.warn("Moved-subtree cache eviction failed for " + content.getId()
					+ " — deferring re-index to reconciliation: " + e.getMessage());
			if (reconcile != null) {
				reconcile.enqueue(repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.CACHE_EVICTION_FAILURE);
			}
			return;
		}

		RAGIndexingService ragService = getRagIndexingService();
		ACLExpander expander = getAclExpander();
		final jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil solrUtil = getSolrUtil();
		final boolean ragEnabled = ragService != null && ragService.isEnabled() && expander != null;
		final boolean contentAclInSolr = solrUtil != null && expander != null;
		if (!ragEnabled && !contentAclInSolr) {
			return;
		}
		final RAGIndexingService ragRef = ragEnabled ? ragService : null;
		ragAclExecutor.submit(() -> {
			try {
				// isRoot=true: the moved object itself was already re-indexed by
				// ContentServiceImpl.move; re-index only the inheriting descendants.
				// syncConfirm=false: async best-effort with enqueue-on-failure.
				updateSearchIndexACLRecursively(repositoryId, content, ragRef, expander, solrUtil,
						new java.util.HashSet<>(), true, reconcile, null, false, null);
				log.info("Moved-subtree search index ACL refresh triggered for: " + content.getId());
			} catch (Exception e) {
				log.warn("Failed to refresh moved-subtree search index ACL for " + content.getId()
						+ ": " + e.getMessage());
				if (reconcile != null) {
					reconcile.enqueue(repositoryId, content.getId(),
							jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE);
				}
			}
		});
	}

	/**
	 * Asynchronously update RAG index ACL for a document/folder and its descendants.
	 * This ensures that RAG search results reflect the latest permission changes.
	 * Uses the shared ragAclExecutor to prevent thread leak.
	 */
	private void updateRAGIndexACLAsync(String repositoryId, Content content) {
		// Get search services from the Spring context (optional dependencies).
		RAGIndexingService ragService = getRagIndexingService();
		ACLExpander expander = getAclExpander();
		final jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil solrUtil = getSolrUtil();

		// RAG readers are only refreshed when RAG is enabled; CMIS content
		// `readers` (ACL-in-Solr) must refresh regardless of RAG so the query-time
		// readers fq stays correct on inheriting descendants.
		final boolean ragEnabled = ragService != null && ragService.isEnabled() && expander != null;
		final boolean contentAclInSolr = solrUtil != null && expander != null;
		if (!ragEnabled && !contentAclInSolr) {
			return;
		}
		final RAGIndexingService ragRef = ragEnabled ? ragService : null;
		final jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconcile = reconciliationService;

		ragAclExecutor.submit(() -> {
			try {
				// syncConfirm=false: async best-effort with enqueue-on-failure.
				updateSearchIndexACLRecursively(repositoryId, content, ragRef, expander, solrUtil,
						new java.util.HashSet<>(), true, reconcile, null, false, null);
				log.info("Search index ACL update triggered for: " + content.getId());
			} catch (Exception e) {
				log.warn("Failed to update search index ACL for " + content.getId() + ": " + e.getMessage());
				if (reconcile != null) {
					reconcile.enqueue(repositoryId, content.getId(),
							jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE);
				}
			}
		});
	}

	/**
	 * Re-drive a single object's search-index ACL refresh (content {@code readers}
	 * + RAG block + its relationships + inheriting descendants) OUT OF the
	 * reconciliation queue. Unlike the async applyAcl/move path this runs with
	 * {@code isRoot=false} (so the object's OWN readers are refreshed — that write
	 * may be exactly what failed) and does NOT re-enqueue on failure (the caller,
	 * the reconciliation scheduler, owns the retry accounting); instead it counts
	 * failures and returns {@code true} only when the whole re-drive was clean.
	 *
	 * @return {@code true} if the object no longer needs reconciliation (refresh
	 *         re-driven with no failure, or the object was deleted / there is no
	 *         search index); {@code false} if a failure was hit and the task should
	 *         be retried later.
	 */
	/** Thrown internally when the reconciliation lease is lost mid re-drive (cooperative fencing). */
	private static final class LeaseLostException extends RuntimeException {
		LeaseLostException() { super("reconciliation lease lost"); }
	}

	@Override
	public boolean reindexSearchIndexAclForObject(String repositoryId, String objectId) {
		return reindexSearchIndexAclForObject(repositoryId, objectId, null);
	}

	@Override
	public boolean reindexSearchIndexAclForObject(String repositoryId, String objectId,
			java.util.function.BooleanSupplier leaseStillHeld) {
		// EVICT FIRST, then read authoritatively. A stale JVM cache (e.g. an ACL
		// change made on another replica) must not be re-indexed as if fresh: if we
		// read before evicting, the already-fetched Java object still holds the OLD
		// ACL even after the cache is cleared. Evicting first makes the read below a
		// cache miss that re-loads from the store. If eviction fails we cannot
		// guarantee a fresh read, so retry rather than write a stale value.
		try {
			if (nemakiCachePool != null) {
				nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
			}
		} catch (Exception e) {
			log.warn("Reconcile: root cache eviction failed for " + objectId + ": " + e.getMessage());
			return false;
		}

		Content content;
		try {
			content = contentService.getContent(repositoryId, objectId);
		} catch (Exception e) {
			log.warn("Reconcile: failed to load " + objectId + ": " + e.getMessage());
			return false;
		}
		if (content == null) {
			// Ambiguous: the DAO layers collapse a genuine 404 AND a transient read
			// error to null. Probe the store authoritatively — only treat it as
			// reconciled (complete) when it is genuinely gone; on any read error,
			// retry (never CAS-delete the task on a read blip).
			DocState state = probeContentExists(repositoryId, objectId);
			if (state == DocState.NOT_FOUND) {
				return true; // genuinely deleted — nothing to reconcile
			}
			return false; // ERROR (or FOUND-but-unconvertible) — retry later
		}

		RAGIndexingService ragService = getRagIndexingService();
		ACLExpander expander = getAclExpander();
		jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil solrUtil = getSolrUtil();
		boolean ragEnabled = ragService != null && ragService.isEnabled() && expander != null;
		boolean contentAclInSolr = solrUtil != null && expander != null;
		if (!ragEnabled && !contentAclInSolr) {
			// No search index wired — nothing to reconcile.
			return true;
		}
		RAGIndexingService ragRef = ragEnabled ? ragService : null;
		// Evict the INHERITING DESCENDANTS' caches too (the root was evicted above).
		// Eviction is a PRECONDITION for a correct re-index — if it fails, the readers
		// would be recomputed from a stale cache, so ABORT this re-drive (retry later)
		// rather than overwriting the index with stale values.
		try {
			clearCachesRecursively(repositoryId, content);
		} catch (Exception e) {
			log.warn("Reconcile: descendant cache eviction failed for " + objectId + ": " + e.getMessage());
			return false;
		}
		java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);
		// syncConfirm=true: writes are forced synchronous so a Solr failure is counted
		// (the task is only completed/deleted when the re-drive is genuinely clean).
		try {
			updateSearchIndexACLRecursively(repositoryId, content, ragRef, expander, solrUtil,
					new java.util.HashSet<>(), false, null, failures, true, leaseStillHeld);
		} catch (LeaseLostException e) {
			// Lost the lease to a reclaiming worker mid-flight — stop writing and let
			// the reclaimer own it (not clean, so the task is not completed here).
			log.info("Reconcile: lease lost for " + objectId + " — aborting re-drive (reclaimer owns it)");
			return false;
		}
		return failures.get() == 0;
	}

	/**
	 * Recursively refresh search-index ACL after an ACL change: the CMIS content
	 * {@code readers} field (all queryable content) and, when RAG is enabled, the
	 * RAG document readers, plus the readers of the relationships referencing each
	 * node. The root object was already re-indexed synchronously by
	 * {@code updateInternal} when {@code isRoot=true}; inheriting descendants are
	 * re-indexed here.
	 *
	 * <p>Failure handling depends on the caller:
	 * <ul>
	 *   <li>{@code reconcile != null} (the async applyAcl/move path): each caught
	 *       failure is logged and the failing object is recorded in the durable
	 *       reconciliation queue so a scheduled poll can re-drive it; the traversal
	 *       continues (best-effort, siblings unaffected).</li>
	 *   <li>{@code failureCounter != null} (the reconciliation re-drive): each
	 *       caught failure increments the counter instead of re-enqueuing, so the
	 *       scheduler can tell whether the re-drive was clean.</li>
	 * </ul>
	 */
	private void updateSearchIndexACLRecursively(String repositoryId, Content content,
			RAGIndexingService ragService, ACLExpander expander,
			jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil solrUtil,
			java.util.Set<String> visitedIds, boolean isRoot,
			jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconcile,
			java.util.concurrent.atomic.AtomicInteger failureCounter, boolean syncConfirm,
			java.util.function.BooleanSupplier leaseStillHeld) {
		if (content == null || visitedIds.contains(content.getId())) {
			return;
		}
		// Cooperative fencing: before writing this node, confirm we still hold the
		// reconciliation lease (it also renews the lease when it is running low). If
		// it has been reclaimed by another worker, abort the whole re-drive so we do
		// not overwrite the reclaimer's fresher readers. Bounds a lease-lost worker to
		// at most the writes it already started before this checkpoint.
		if (leaseStillHeld != null && !leaseStillHeld.getAsBoolean()) {
			throw new LeaseLostException();
		}
		visitedIds.add(content.getId());

		// Callback run when a per-node async index write ultimately fails after its
		// bounded retries — record the object so the reconciliation poll re-drives it.
		final Runnable onWriteFailed = (reconcile == null) ? null : () -> {
			reconcile.enqueue(repositoryId, content.getId(),
					jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.INDEX_WRITE_FAILURE);
			if (failureCounter != null) failureCounter.incrementAndGet();
		};

		// CMIS content readers: refresh the Solr `readers` field so the query-time
		// readers fq stays correct. Skip the root (updateInternal already did it);
		// re-index inheriting descendants (their effective ACL changed).
		if (!isRoot && solrUtil != null) {
			try {
				// syncConfirm (reconciliation re-drive): force a synchronous write so a
				// Solr failure THROWS here and is counted — otherwise a fire-and-forget
				// async submit would report clean and the task would be deleted before
				// the write is known to have landed.
				solrUtil.indexDocument(repositoryId, content, syncConfirm, true, onWriteFailed);
			} catch (Exception e) {
				log.warn("Failed to refresh content readers for " + content.getId() + ": " + e.getMessage());
				recordNodeFailure(reconcile, failureCounter, repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
			}
		}

		// RAG readers (documents only, when RAG enabled) — unchanged behavior.
		if (ragService != null && expander != null && content instanceof Document) {
			try {
				java.util.List<String> readers = expander.expandToReaders(repositoryId, content);
				ragService.updateDocumentACL(repositoryId, content.getId(), readers);
			} catch (Exception e) {
				log.warn("Failed to update RAG ACL for document " + content.getId() + ": " + e.getMessage());
				recordNodeFailure(reconcile, failureCounter, repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
			}
		}

		// Relationships that reference this object (as source OR target) derive
		// their `readers` from readers(source) UNION readers(target)
		// (SolrUtil.relationshipReaders). This object's effective ACL just
		// changed, so re-index its relationships too — this runs for the ROOT as
		// well as inheriting descendants. Without it a GRANT to this object never
		// becomes searchable on its relationships (the in-memory getFiltered can
		// only REMOVE hits, never ADD one Solr already excluded), and a REVOKE
		// leaves a stale over-permissive relationship inflating numFound (which
		// can trip the ACL scan cap).
		if (solrUtil != null) {
			try {
				List<jp.aegif.nemaki.model.Relationship> rels = contentService.getRelationsipsOfObject(
						repositoryId, content.getId(),
						org.apache.chemistry.opencmis.commons.enums.RelationshipDirection.EITHER);
				if (rels != null) {
					for (jp.aegif.nemaki.model.Relationship rel : rels) {
						solrUtil.indexDocument(repositoryId, rel, syncConfirm, true, onWriteFailed);
					}
				}
			} catch (Exception e) {
				log.warn("Failed to refresh relationship readers for " + content.getId() + ": " + e.getMessage());
				recordNodeFailure(reconcile, failureCounter, repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.RELATIONSHIP_REFRESH_FAILURE);
			}
		}

		// Recursively process children that inherit ACL. Guard per node so a
		// transient backend failure (e.g. a CouchDB view timeout on getChildren, or
		// one bad child) is bounded to that node/subtree instead of abandoning the
		// entire remaining traversal — the un-refreshed nodes keep stale readers,
		// which stays result-safe (CMIS getFiltered / RAG filterByLiveAcl re-check
		// live ACL) but must not silently take down siblings. Best-effort: results
		// stay correct, only search visibility of the failed node lags until the
		// reconciliation poll (or the next ACL touch) re-drives it.
		if (content.isFolder()) {
			List<Content> children = null;
			try {
				children = contentService.getChildren(repositoryId, content.getId());
			} catch (Exception e) {
				log.warn("Failed to list children of " + content.getId()
						+ " during search-index ACL refresh (subtree skipped): " + e.getMessage());
				recordNodeFailure(reconcile, failureCounter, repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE);
			}
			if (!CollectionUtils.isEmpty(children)) {
				for (Content child : children) {
					try {
						if (contentService.getAclInheritedWithDefault(repositoryId, child)) {
							updateSearchIndexACLRecursively(repositoryId, child, ragService, expander,
									solrUtil, visitedIds, false, reconcile, failureCounter, syncConfirm, leaseStillHeld);
						}
					} catch (Exception e) {
						log.warn("Failed to refresh search-index ACL for child " + child.getId()
								+ " (continuing with siblings): " + e.getMessage());
						recordNodeFailure(reconcile, failureCounter, repositoryId, child.getId(),
								jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE);
					}
				}
			}
		}
	}

	/** Record a per-node ACL-refresh failure: enqueue for reconciliation and/or count it. */
	private void recordNodeFailure(jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconcile,
			java.util.concurrent.atomic.AtomicInteger failureCounter, String repositoryId, String objectId,
			String reason) {
		if (reconcile != null) {
			reconcile.enqueue(repositoryId, objectId, reason);
		}
		if (failureCounter != null) {
			failureCounter.incrementAndGet();
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

	/**
	 * Shuts down the shared RAG ACL executor. Called by Spring destroy-method.
	 */
	public void destroy() {
		ragAclExecutor.shutdown();
		try {
			if (!ragAclExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
				log.warn("ragAclExecutor did not terminate within timeout, forcing shutdown. "
					+ "Active tasks may be interrupted.");
				ragAclExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			log.warn("ragAclExecutor shutdown interrupted, forcing shutdown");
			ragAclExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
