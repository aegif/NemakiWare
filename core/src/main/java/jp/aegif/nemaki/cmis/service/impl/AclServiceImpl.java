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

import java.util.ArrayList;
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

	// ── ACL-epoch production wiring (design §11, increment 12; flag-gated, default OFF) ──
	private jp.aegif.nemaki.util.PropertyManager propertyManager;
	private jp.aegif.nemaki.epoch.AclEpochFinalizationService aclEpochFinalizationService;
	private jp.aegif.nemaki.epoch.AclEpochIndexWriter aclEpochIndexWriter;

	public void setPropertyManager(jp.aegif.nemaki.util.PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public void setAclEpochFinalizationService(jp.aegif.nemaki.epoch.AclEpochFinalizationService s) {
		this.aclEpochFinalizationService = s;
	}

	public void setAclEpochIndexWriter(jp.aegif.nemaki.epoch.AclEpochIndexWriter w) {
		this.aclEpochIndexWriter = w;
	}

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
		// The ACL is required. acl.getExtensions() is dereferenced unconditionally below, so a
		// null here has always been an NPE — a 500 for what is a malformed request. The
		// "a null ACL cannot reach the break branch" reasoning covers the BREAK flag only
		// (breakingInheritance is derived from those same extensions); it says nothing about a
		// non-breaking call that simply omits the ACL. Reject it as the client error it is.
		exceptionService.invalidArgumentRequired("acl", acl);

		// WRITE, not read. This method reads the object's current ACL, decides the new one from
		// it (inheritance flag, add/remove semantics resolved by the caller) and writes it back —
		// a read-modify-write. Under a READ lock two concurrent applyAcl calls on the same object
		// both proceed, both compute from the same pre-state, and the second write silently
		// discards the first. On an ordinary property that would be an annoying lost update; on
		// an ACL it is a permission grant or revocation that the client was told succeeded and
		// that is not in the repository.
		//
		// The lock is not held across the asynchronous descendant refresh, so making it exclusive
		// serializes applyAcl on the same object only — concurrent applyAcl on DIFFERENT objects
		// is unaffected, and reads of this object wait the same amount they already waited for
		// the write itself.
		Lock lock = threadLockService.getWriteLock(repositoryId, objectId);

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

			// Every ACE stored here is BY DEFINITION direct: applyAcl writes to this object's
			// own ACL. CMIS 1.1 §2.1.12.1 defines direct=TRUE as "the ACE is directly assigned
			// to the object". The previous code passed `aclPropagation == OBJECTONLY` into this
			// slot, which inverted the meaning (a PROPAGATE request stored direct=false).
			//
			// That was harmless in practice and the fix needs no data migration, for two reasons
			// worth recording so nobody "restores" the old behaviour:
			//   1. The flag is never persisted. CouchContent.convertToCouchAcl writes only
			//      principal + permissions, and CouchAcl.convert reconstructs every entry with
			//      direct=true ("DB-stored ACL is only localACE(direct = true)").
			//   2. The wire value is recomputed. CompileServiceImpl.compileAcl derives isDirect
			//      from list membership (localAces → true, inheritedAces → false), not from this
			//      field. Verified on a live stack: granting via PROPAGATE on an
			//      inheritance-broken folder and then issuing an add/remove applyACL does NOT
			//      drop the earlier ACE.
			// What the inversion did corrupt was the in-memory value while it was still in
			// flight — the orphan passthrough in AclSemantics (parentId == null, or a parent
			// read that failed in non-strict mode) hands the stored flag straight to the
			// direct/inherited classification, and ZipExporter writes it out verbatim.
			//
			// AclPropagation itself is deliberately not consulted: see the note above the
			// aclPropagation validation in ExceptionServiceImpl.
	
			// THE REQUESTED ACEs WIN, breaking inheritance or not.
			//
			// This branch used to ignore acl.getAces() entirely when inheritance was being
			// broken: it copied the CURRENT effective ACL into the new local list and threw the
			// request away. A caller that sent "detach, and here is the ACL I want" got "detach,
			// and keep exactly what you had" — with a success response. A removal the caller had
			// asked for simply was not there.
			//
			// On a break, EVERY requested ACE is taken, whatever its direct flag says. After the
			// break there is no inheritance, so every surviving entry is local by definition, and
			// the flag on the way in describes where the entry came FROM, not where it should go.
			// Filtering to direct=true here (a first attempt at this fix) broke the product's own
			// Break Inheritance button, which sends the effective ACL back unchanged — inherited
			// entries included, flagged direct=false — and would have had it silently drop every
			// inherited grant. Detaching a folder is not a request to revoke everyone.
			//
			// The non-breaking path keeps the direct-only filter: with inheritance still in
			// effect, inherited entries are not this object's to store, and writing them locally
			// would freeze a copy that then stops tracking the ancestor.
			//
			// There is no "break, but keep what I had" case, and no branch pretending to offer
			// one. breakingInheritance is only ever set from acl.getExtensions(), which is
			// dereferenced unconditionally above — so a null ACL cannot reach this point at all,
			// and `breakingInheritance && acl == null` was unreachable code with a comment
			// describing behaviour that could not occur. Breaking inheritance always means
			// "these entries are now the ACL".
			List<Ace> requested = acl.getAces() == null
					? java.util.Collections.<Ace>emptyList() : acl.getAces();
			List<Ace> requestedDirect = new ArrayList<>();
			for (Ace ace : requested) {
				if (ace.isDirect()) {
					requestedDirect.add(ace);
				}
			}
			List<Ace> toStore = breakingInheritance ? requested : requestedDirect;

			if (breakingInheritance && requested.isEmpty()) {
				// An EMPTY list with a break is honoured as an empty ACL, not read as "keep
				// what you had". CMIS defines applyACL as setting the ACL to the entries
				// given, and this case is reachable from a real operation: the add/remove
				// overload computes the resulting list, so removing an object's last ACE
				// while detaching it arrives here as an empty list. Reading that as "keep
				// current" would undo the removal and answer success — the silent
				// over-permission this whole branch was fixed for. The object is left
				// readable only by administrators, which is recoverable and visible;
				// a revocation that did not happen is neither.
				log.warn("applyAcl on " + objectId + ": inheritance broken with an EMPTY ACE"
						+ " list — the object will have no local permissions and no inherited"
						+ " ones. Only administrators will be able to read it.");
			}
			// Duplicate principals are stored as sent, but the read path keys by principal
			// (Acl.buildMap) so only the LAST entry's permissions survive a round trip — a
			// silent narrowing or widening the caller never asked for. The product's own UI
			// cannot produce this (getMergedAces already collapses by principal), but a direct
			// CMIS caller can, so say so rather than let it happen invisibly.
			java.util.Set<String> seenPrincipals = new java.util.HashSet<>();
			for(Ace ace : toStore){
				if (!seenPrincipals.add(ace.getPrincipalId())) {
					log.warn("applyAcl on " + objectId + ": the requested ACL lists principal '"
							+ ace.getPrincipalId() + "' more than once. Only the last entry's"
							+ " permissions will survive a read — send one ACE per principal.");
				}
				jp.aegif.nemaki.model.Ace nemakiAce = new jp.aegif.nemaki.model.Ace(ace.getPrincipalId(), ace.getPermissions(), true);
				nemakiAcl.getLocalAces().add(nemakiAce);
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

			// ── ACL-epoch Phase 1 (§11.2): mark the model so the SAME PUT that persists the ACL
			// change persists PENDING_EPOCH + a fresh mutation id — one _rev, atomic by
			// construction (the carrier plumbing of step 0 makes this non-destructive).
			String epochMutationId = jp.aegif.nemaki.epoch.AclEpochPhase1.markPending(content);

			// skipRAGIndexing=true: ACL change does not alter document content,
			// so TEI re-embedding is unnecessary. RAG ACL is updated separately below.
			contentService.updateInternal(repositoryId, content, true);
			contentService.writeChangeEvent(callContext, repositoryId, content, nemakiAcl, ChangeType.SECURITY );

			// CRITICAL FIX (2025-01-23): Synchronously clear ACL caches for this object and all descendants
			// that inherit ACL. This prevents race conditions where child documents show stale permissions.
			// B4: the move path already treats an eviction failure as a reconciliation obligation;
			// applyAcl used to let it escape with the ACL committed, epoch Phase 2 not run and the
			// subtree only partly evicted — a state nothing would come back to. A failure here
			// means descendants may still serve the old effective ACL, so it must be queued.
			// The index refresh is NOT submitted in that case: refreshing readers from caches we
			// could not evict would write the stale values into Solr and make them durable.
			int subtreeNodes;
			try {
				subtreeNodes = clearCachesRecursively(repositoryId, content);
			} catch (Exception evictionFailure) {
				log.warn("applyAcl: descendant cache eviction failed for " + content.getId()
						+ " — deferring the search-index refresh to reconciliation: "
						+ evictionFailure.getMessage());
				if (reconciliationService != null) {
					reconciliationService.enqueue(repositoryId, content.getId(),
							jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.CACHE_EVICTION_FAILURE);
				}
				return getAcl(callContext, repositoryId, objectId, false, null);
			}

			// ── ACL-epoch Phase 2 + own-node fenced write + ACK (§11.4). Never fails the
			// request: the ACL change is committed and authoritative; every early stop lands in
			// a state the scanner or the queue already recovers (§11.6).
			EpochCycleResult epochCycle = (epochMutationId != null)
					? finalizeWriteOwnNodeAndAck(repositoryId, content, epochMutationId) : null;
			Long epochE = (epochCycle == null) ? null : epochCycle.epoch;

			// Async update RAG index ACL for this object and descendants. The async tail consumes
			// the TASK only, and ONLY when the sync half actually cleared the marker — otherwise
			// the task must survive for the re-drive, whose terminus clears and completes.
			updateRAGIndexACLAsync(repositoryId, content, epochE,
					(epochCycle != null && epochCycle.cleared) ? epochMutationId : null, subtreeNodes);

			return getAcl(callContext, repositoryId, objectId, false, null);
		}finally{
			lock.unlock();
		}

	}

	/**
	 * Get RAGIndexingService from Spring context (lazy loading, optional dependency).
	 */
	RAGIndexingService getRagIndexingService() {
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
	ACLExpander getAclExpander() {
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
	jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil getSolrUtil() {
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

		// ── ACL-epoch Phase 2 for MOVE (§11.2/§11.4): ContentServiceImpl.move wrote the Phase-1
		// marker in the parent-change PUT; finalize + ACK here. No synchronous own-node write on
		// this path — the recursion below refreshes the root too (review round 3 #2) — so the
		// settle happens at the async completion, keeping the root task alive until the subtree
		// is actually covered (a crash before that leaves the task, and the queue re-drives).
		final String epochMutationId =
				content.aclEpochFieldAsString(jp.aegif.nemaki.epoch.AclEpochState.FIELD_MUTATION_ID);
		final Long epochE = (epochMutationId != null) ? finalizeAndAck(repositoryId, content.getId()) : null;

		final Thread movedSubmittingThread = Thread.currentThread();
		ragAclExecutor.submit(() -> {
			// Same executor, same queue: a move refresh can also be forced inline when the queue
			// is full, so it is registered and flagged the same way (see PropagationProgress).
			PropagationProgress movedProgress = PropagationProgress.begin(repositoryId,
					content.getId(), -1, Thread.currentThread() == movedSubmittingThread);
			try {
				// isRoot=true: the moved object itself was already re-indexed by
				// ContentServiceImpl.move; re-index only the inheriting descendants.
				// syncConfirm=false: async best-effort with enqueue-on-failure.
				updateSearchIndexACLRecursively(repositoryId, content, ragRef, expander, solrUtil,
						new java.util.HashSet<>(), true, reconcile, null, false, null, epochE,
						movedProgress);
				log.info("Moved-subtree search index ACL refresh COMPLETED for: " + content.getId());
				// NO inline settle on the move path: no synchronous own-node write ran here, so the
				// marker was never cleared — consuming the task would strand RECONCILE_ENQUEUED.
				// The retained task's re-drive performs the terminus (one re-drive per move,
				// accepted in the design).
			} catch (Exception e) {
				log.warn("Failed to refresh moved-subtree search index ACL for " + content.getId()
						+ ": " + e.getMessage());
				if (reconcile != null) {
					reconcile.enqueue(repositoryId, content.getId(),
							jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE,
							epochE);
				}
			} finally {
				movedProgress.end();
			}
		});
	}

	/**
	 * Hand a search-index refresh that landed on a request thread to the durable queue.
	 *
	 * <p>Package-private so it can be tested on its own: the branch it implements only fires when
	 * a {@code ThreadPoolExecutor} is saturated, which is not a state a unit test can arrange
	 * around the surrounding closure.
	 *
	 * @return {@code true} if the caller should return without walking the subtree. {@code false}
	 *         means there is no durable queue to hand the work to, so the caller must walk it
	 *         inline after all — losing the work outright would be worse than a slow request.
	 */
	static boolean deferInlineRefreshToReconciliation(String repositoryId, String rootId,
			Long epochE, jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconcile) {
		if (reconcile == null) {
			log.warn("Search index ACL refresh for " + rootId + " is running INLINE on a request"
					+ " thread and cannot be deferred (no reconciliation service) — this request"
					+ " will block until the subtree is refreshed.");
			return false;
		}
		// Same reason code as a mid-traversal failure: from the queue's point of view this is the
		// same obligation — "this root's descendants were not refreshed, re-drive them" — and the
		// epoch is carried so the re-drive is fenced exactly as the original would have been.
		//
		// enqueueOrThrow, not the fail-soft enqueue: returning true here is a claim that the work
		// is now somebody else's, and the caller acts on it by returning. If the write did not
		// land, that claim would turn a slow request into a lost refresh. On failure we fall back
		// to walking inline — the outcome this method exists to avoid, but the safe one.
		try {
			reconcile.enqueueOrThrow(repositoryId, rootId,
					jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE,
					jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Operation.ACL_REINDEX,
					// Same convention as the fail-soft overload: no known obligation is 0, and the
					// merge is monotonic-max, so this can never lower an obligation already recorded.
					epochE == null ? 0L : epochE.longValue());
		} catch (Exception e) {
			log.warn("Could not defer the search index ACL refresh for " + rootId
					+ " to the reconciliation queue (" + e.getClass().getSimpleName()
					+ ") — walking the subtree on this request thread instead so the refresh is"
					+ " not lost.");
			return false;
		}
		PropagationProgress.recordDeferredFromRequestThread();
		log.warn("Search index ACL refresh DEFERRED for " + rootId + ": the refresh queue is full"
				+ " and this task fell back onto a request thread. Queued for reconciliation"
				+ " instead of walking the subtree inline.");
		return true;
	}

	/**
	 * Asynchronously update RAG index ACL for a document/folder and its descendants.
	 * This ensures that RAG search results reflect the latest permission changes.
	 * Uses the shared ragAclExecutor to prevent thread leak.
	 */
	private void updateRAGIndexACLAsync(String repositoryId, Content content) {
		updateRAGIndexACLAsync(repositoryId, content, null, null, -1);
	}

	private void updateRAGIndexACLAsync(String repositoryId, Content content, Long epochE,
			String epochMutationId) {
		updateRAGIndexACLAsync(repositoryId, content, epochE, epochMutationId, -1);
	}

	/**
	 * @param subtreeNodes size of the inheriting subtree as counted by the synchronous cache
	 *     eviction, or -1 when this path did not count it. Logged so an operator can see how
	 *     much work the asynchronous refresh is about to do.
	 */
	private void updateRAGIndexACLAsync(String repositoryId, Content content, Long epochE,
			String epochMutationId, int subtreeNodes) {
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

		final long submittedAtNanos = System.nanoTime();
		// Whether the task ends up running inline on THIS thread (CallerRunsPolicy, queue full)
		// is only knowable from inside the task: compare the running thread with the submitting
		// one. It matters because an inline run overtakes everything already queued, which is
		// exactly when the FIFO assumption behind the progress estimate stops holding.
		final Thread submittingThread = Thread.currentThread();
		ragAclExecutor.submit(() -> {
			boolean inlineOnCaller = Thread.currentThread() == submittingThread;
			PropagationProgress progress = PropagationProgress.begin(
					repositoryId, content.getId(), subtreeNodes, inlineOnCaller);
			// START marker. Without it the only INFO on this path is the completion line below,
			// so "a propagation is running right now" was not observable at all (E1).
			log.info("Search index ACL refresh STARTED: repository=" + repositoryId
					+ " root=" + content.getId()
					+ (subtreeNodes >= 0 ? " expectedNodes=" + subtreeNodes : "")
					+ " queuedForMs=" + ((System.nanoTime() - submittedAtNanos) / 1_000_000L)
					+ (inlineOnCaller ? " INLINE-ON-REQUEST-THREAD (queue full)" : ""));

			// The refresh queue is full, so this task was handed back to the REQUEST thread by
			// CallerRunsPolicy. Walking the subtree here is the one thing that turns "a permission
			// change is propagating" into "the application is slow": an ordinary user's request
			// would sit rewriting someone else's descendants, for as long as that takes.
			//
			// Yielding cooperatively does not help — the queue is full, so a re-submitted
			// continuation is handed straight back to this same thread. Deferring the whole
			// subtree to the durable queue does: the work is not lost, the re-drive owns it, and
			// the request returns. It costs a re-walk from the root, which is the right trade
			// against holding a user's request.
			//
			// Settling here would be actively wrong, not merely premature: the terminus consumes
			// the queue TASK (settleIfCovered), so settling straight after enqueueing would delete
			// the obligation we just created and the subtree would never be refreshed. The outbox
			// marker was already cleared synchronously inside the mutation request, so skipping
			// the terminus strands nothing. Convergence is the scheduler completing the task on a
			// successful re-drive; a newer mutation's terminus may also consume it, which is
            // correct — a later epoch's traversal covers this one.
			if (inlineOnCaller
					&& deferInlineRefreshToReconciliation(repositoryId, content.getId(), epochE,
							reconcile)) {
				progress.end();
				return;
			}
			try {
				// syncConfirm=false: async best-effort with enqueue-on-failure.
				updateSearchIndexACLRecursively(repositoryId, content, ragRef, expander, solrUtil,
						new java.util.HashSet<>(), true, reconcile, null, false, null, epochE, progress);
				// This line used to read "triggered", but it is emitted AFTER the recursive walk
				// returns — an operator watching the log read a completion as a start.
				log.info("Search index ACL refresh COMPLETED: repository=" + repositoryId
						+ " root=" + content.getId()
						+ (subtreeNodes >= 0 ? " nodes=" + subtreeNodes : "")
						+ " elapsedMs=" + ((System.nanoTime() - submittedAtNanos) / 1_000_000L));
				// §11.4 inline settle, deferred to SUBTREE COMPLETION (not the sync request):
				// settling before the async refresh finished would let a crash in the window
				// lose the descendants with no task left to recover them. Per-node failures
				// above enqueued their own tasks, so settling the root here is safe.
				settleEpochObligationAfterRefresh(repositoryId, content.getId(), epochE, epochMutationId);
			} catch (Exception e) {
				log.warn("Failed to update search index ACL for " + content.getId() + ": " + e.getMessage());
				if (reconcile != null) {
					reconcile.enqueue(repositoryId, content.getId(),
							jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE,
							epochE);
				}
			} finally {
				progress.end();
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
	public boolean purgeRagBlockForObject(String repositoryId, String objectId) {
		RAGIndexingService ragService = getRagIndexingService();
		if (ragService == null) {
			// Cannot even verify — unknown ≠ purged. Keep the task (retry / FAILED
			// keeps it visible to operators).
			log.warn("RAG purge: RAGIndexingService unavailable for " + objectId + " — cannot purge/verify");
			return false;
		}
		try {
			// purgeDocumentBlocks ignores rag.enabled (a disabled RAG must not turn a
			// security purge into a silent no-op) and is repository-scoped.
			ragService.purgeDocumentBlocks(repositoryId, objectId);
		} catch (Exception e) {
			log.warn("RAG purge: delete failed for " + objectId + ": " + e.getMessage());
			return false;
		}
		try {
			boolean stillThere = ragService.isDocumentInRagIndex(repositoryId, objectId);
			if (stillThere) {
				log.warn("RAG purge: block still present after delete for " + objectId + " — will retry");
				return false;
			}
			return true; // confirmed absent
		} catch (Exception e) {
			// Unknown ≠ absent — never complete an unverified purge.
			log.warn("RAG purge: absence verification failed for " + objectId + ": " + e.getMessage());
			return false;
		}
	}

	/**
	 * Cooperative-fencing checkpoint: poll the (latched) lease guard before an index
	 * write. Throwing {@link LeaseLostException} aborts the whole re-drive so a worker
	 * that outlived its lease stops writing. A null guard (async refresh / short manual
	 * paths) disables fencing.
	 */
	private void checkpointLease(java.util.function.BooleanSupplier leaseStillHeld) {
		if (leaseStillHeld != null && !leaseStillHeld.getAsBoolean()) {
			throw new LeaseLostException();
		}
	}

	/**
	 * Write a node's search-index readers, choosing the write strategy by path:
	 * <ul>
	 *   <li><b>Reconciliation re-drive ({@code syncConfirm=true})</b>: an ATOMIC
	 *       ACL-group-only, {@code _version_}-CAS + EPOCH-fenced update
	 *       ({@link #writeContentReadersEpochFenced}). This closes the TOCTOU (the fence
	 *       comparison and the write are atomic) and cannot clobber body/path (it only
	 *       sets the ACL group). If the doc is not yet indexed there is nothing to
	 *       clobber, so it falls back to a strict full index (a genuine build failure
	 *       then propagates and is counted).</li>
	 *   <li><b>Async applyAcl/move refresh ({@code syncConfirm=false})</b>: the
	 *       pre-existing best-effort full re-index (last-writer-wins; a transient failure
	 *       is enqueued via {@code onWriteFailed}). Concurrent async writers reordering
	 *       each other is a pre-existing eventual-consistency property of the async index
	 *       executor, not introduced here — see the class Javadoc / RELEASE_NOTES.</li>
	 * </ul>
	 * Throws on a genuine failure so the caller records/retries it.
	 */
	/**
	 * §11.4 for the applyAcl path: Phase 2 (finalize), the SYNCHRONOUS own-node fenced write, and
	 * the ACK. Returns the finalized epoch {@code E}, or {@code null} when the cycle stopped early
	 * (superseded / failure) — every early stop leaves a state the scanner or the queue already
	 * recovers (§11.6 crash matrix), so this must NEVER fail the mutation request.
	 */
	/** Outcome of the synchronous epoch cycle: the finalized epoch, and whether the marker was cleared. */
	static final class EpochCycleResult {
		final Long epoch;
		final boolean cleared;
		EpochCycleResult(Long epoch, boolean cleared) { this.epoch = epoch; this.cleared = cleared; }
	}

	private EpochCycleResult finalizeWriteOwnNodeAndAck(String repositoryId, Content content, String mutationId) {
		final String objectId = content.getId();
		Long epochE = finalizeAndAckPrepare(repositoryId, objectId);
		if (epochE == null) {
			evictAfterRawEpochWrite(repositoryId, objectId); // finalize may still have CAS-bumped
			return new EpochCycleResult(null, false);
		}
		// ACK BEFORE the own-node write — found live, not by reasoning: the §4.2 pending gate
		// refuses FINALIZED_NEEDS_RECONCILE as a mid-mutation source, so a write attempted between
		// finalize and ack can NEVER pass the walk. RECONCILE_ENQUEUED is settled and does not gate
		// (the obligation is durable), so the order here is finalize → ACK → write → clear. The
		// proposal's write-before-ack order (§11.4) was wrong on exactly this point.
		Long acked = ackAfterFinalize(repositoryId, objectId, epochE);
		if (acked == null) {
			evictAfterRawEpochWrite(repositoryId, objectId);
			return new EpochCycleResult(epochE, false);
		}
		// Own-node fenced write — synchronous so search reflects the mutated object itself
		// immediately. A failure is NOT terminal: the ACK above already recorded the durable
		// obligation, and the queue re-drives.
		boolean ownNodeWritten = false;
		try {
			writeContentReaders(getSolrUtil(), repositoryId, content, null);
			ownNodeWritten = true;
		} catch (Exception e) {
			log.warn("ACL-epoch own-node write failed for " + objectId
					+ " — the obligation stays queued for the re-drive: " + e.getMessage());
		}
		// TERMINUS half 1, SYNCHRONOUS (flag-ON TCK finding): every content-doc rev bump must
		// happen inside the request — a deferred async clear raced the next mutation's Phase-1 PUT
		// ("Document update conflict"). Cleared ONLY after a successful own-node write, and the
		// async half consumes the task ONLY when this cleared: consuming the task with the marker
		// still standing would strand a RECONCILE_ENQUEUED document nothing can ever clear (the
		// exact shape D5 exists to prevent — observed live when the gate bug made the write fail).
		boolean cleared = false;
		if (ownNodeWritten && mutationId != null) {
			try {
				jp.aegif.nemaki.epoch.AclEpochFinalizationService.ClearResult cr =
						aclEpochFinalizationService.clearMarkerAfterReconcile(repositoryId, objectId, mutationId);
				cleared = (cr == jp.aegif.nemaki.epoch.AclEpochFinalizationService.ClearResult.CLEARED
						|| cr == jp.aegif.nemaki.epoch.AclEpochFinalizationService.ClearResult.ABANDONED);
			} catch (Exception e) {
				log.warn("ACL-epoch inline clear skipped for " + objectId
						+ " (re-drive terminus converges): " + e.getMessage());
			}
		}
		evictAfterRawEpochWrite(repositoryId, objectId);
		return new EpochCycleResult(epochE, cleared);
	}

	/**
	 * The raw epoch CAS writes bump the content document's {@code _rev} BEHIND the model cache; a
	 * later mutation reading the stale cached model would 409 on its PUT. Evict after every burst
	 * of raw writes so the next reader re-loads the current revision.
	 */
	private void evictAfterRawEpochWrite(String repositoryId, String objectId) {
		try {
			if (nemakiCachePool != null) {
				nemakiCachePool.get(repositoryId).removeCmisAndContentCache(objectId);
			}
		} catch (Exception e) {
			log.warn("post-epoch cache eviction failed for " + objectId + ": " + e.getMessage());
		}
	}

	/** §11.4 for the move path: finalize + ACK only (the recursion writes the root). */
	private Long finalizeAndAck(String repositoryId, String objectId) {
		Long epochE = finalizeAndAckPrepare(repositoryId, objectId);
		Long acked = (epochE == null) ? null : ackAfterFinalize(repositoryId, objectId, epochE);
		evictAfterRawEpochWrite(repositoryId, objectId);
		return acked;
	}

	private Long finalizeAndAckPrepare(String repositoryId, String objectId) {
		try {
			jp.aegif.nemaki.epoch.AclEpochFinalizationService fin = aclEpochFinalizationService;
			if (fin == null) {
				log.warn("ACL-epoch wiring is ON but the finalization service is not wired — Phase 2 "
						+ "left to the scanner for " + objectId);
				return null;
			}
			jp.aegif.nemaki.epoch.AclEpochFinalizationService.FinalizeOutcome fo =
					fin.finalizePending(repositoryId, objectId);
			if (fo.result != jp.aegif.nemaki.epoch.AclEpochFinalizationService.FinalizeResult.FINALIZED
					|| fo.epoch == null) {
				// Superseded / vanished / not pending — the newer mutation's own cycle owns it.
				return null;
			}
			return fo.epoch;
		} catch (Exception e) {
			log.warn("ACL-epoch finalize failed for " + objectId
					+ " (the crash-recovery scanner converges): " + e.getMessage());
			return null;
		}
	}

	private Long ackAfterFinalize(String repositoryId, String objectId, Long epochE) {
		try {
			// Durable obligation FIRST, marker second — the 7b invariant lives inside ackFinalized.
			aclEpochFinalizationService.ackFinalized(repositoryId, objectId);
			return epochE;
		} catch (Exception e) {
			log.warn("ACL-epoch ACK failed for " + objectId
					+ " (the scanner's ACK pass converges): " + e.getMessage());
			// The epoch IS finalized; returning it lets per-node enqueues carry the obligation
			// even though the root task does not exist yet (the scanner will create it).
			return epochE;
		}
	}

	/**
	 * §11.4 terminus for the HAPPY path, run at ASYNC-REFRESH COMPLETION: clear the outbox marker
	 * (scoped to OUR mutation id — a newer in-flight mutation makes it ABANDONED and owns its own
	 * terminus), THEN consume the task. The order is load-bearing (approved D5): the reverse
	 * strands a RECONCILE_ENQUEUED marker with no task, which nothing can ever clear.
	 */
	void settleEpochObligationAfterRefresh(String repositoryId, String objectId, Long epochE,
			String epochMutationId) {
		if (epochE == null || epochMutationId == null) {
			return;
		}
		try {
			// TERMINUS half 2: consume the TASK only. The marker was cleared SYNCHRONOUSLY inside
			// the mutation request (see finalizeWriteOwnNodeAndAck) — clearing here raced the next
			// mutation's Phase-1 PUT on the content document, which the live TCK caught as a
			// CouchDB update conflict. The D5 order (clear BEFORE complete) is preserved across the
			// two halves; a crash between them is §11.6 row 5 and converges via the re-drive.
			if (reconciliationService != null) {
				reconciliationService.settleIfCovered(repositoryId, objectId, epochE);
			}
		} catch (Exception e) {
			// The task survives — the queue owns convergence.
			log.warn("ACL-epoch inline settle skipped for " + objectId + ": " + e.getMessage());
		}
	}

	/**
	 * §11.3: the ACL group is written by the epoch writer in bootstrap-tolerant mode (approved D2).
	 * Since increment 14 this is the ONLY ACL-group write path — there is no second implementation
	 * and no flag selecting between them.
	 */
	private void writeContentReadersEpochFenced(jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil solrUtil,
			String repositoryId, Content content, Runnable onWriteFailed) throws Exception {
		jp.aegif.nemaki.epoch.AclEpochIndexWriter writer = aclEpochIndexWriter;
		ACLExpander expander = getAclExpander();
		if (writer == null || expander == null || solrUtil == null) {
			// Fail the WRITE, not silently fall back: a generation-fenced write slipping through
			// while the rest of the system speaks epochs would be a second ACL implementation.
			throw new IllegalStateException("ACL-epoch wiring is ON but writer/expander/solr are unavailable");
		}
		jp.aegif.nemaki.epoch.AclEpochIndexWriter.WriteOutcome outcome = writer.writeAllowingBootstrap(
				repositoryId, content.getId(), solrUtil.getSolrClient(), expander.principalResolver());
		if (outcome.result == jp.aegif.nemaki.epoch.AclEpochIndexWriter.WriteResult.NOT_INDEXED) {
			// Not in Solr at all — same fallback as the generation path: strict full index
			// (create-if-absent). The fresh document is then unfenced until its next ACL-group
			// write bootstraps it (§11.5).
			solrUtil.indexDocument(repositoryId, content, true, true, onWriteFailed, true);
		}
		// UPDATED / SKIPPED_FRESHER / SKIPPED_IDEMPOTENT / SKIPPED_DELETED → done (§11.3).
	}

	void writeContentReaders(jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil solrUtil,
			String repositoryId, Content content, Runnable onWriteFailed) throws Exception {
		// UNIFIED write path: BOTH the async applyAcl/move refresh AND the reconciliation
		// re-drive write the ACL group through the SAME epoch-fenced atomic CAS. Two writers
		// with two fences would be two answers to "which ACL state is newer", and the older
		// one would eventually win a race with no convergence event behind it.
		writeContentReadersEpochFenced(solrUtil, repositoryId, content, onWriteFailed);
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
		RefreshCounters counters = new RefreshCounters();
		// The re-drive runs on the reconciliation poller, not on the refresh executor, so it is
		// never an inline caller-run. Node count is unknown up front here (the eviction BFS above
		// counted it, but this path does not carry it), so no estimate is offered — only the fact
		// that a re-drive is in flight, which is itself what an operator is missing today.
		PropagationProgress redriveProgress =
				PropagationProgress.begin(repositoryId, objectId, -1, false);
		try {
		// syncConfirm=true: writes are forced synchronous so a Solr failure is counted
		// (the task is only completed/deleted when the re-drive is genuinely clean).
		try {
			updateSearchIndexACLRecursively(repositoryId, content, ragRef, expander, solrUtil,
					new java.util.HashSet<>(), false, null, counters, true, leaseStillHeld, null,
					redriveProgress);
		} catch (LeaseLostException e) {
			// Lost the lease to a reclaiming worker mid-flight — stop writing and let
			// the reclaimer own it (not clean, so the task is not completed here).
			log.info("Reconcile: lease lost for " + objectId + " — aborting re-drive (reclaimer owns it)");
			return false;
		}
		// NOTE: an AclEpochQuarantineBlockedException deliberately PROPAGATES out of this method —
		// the scheduler's §5.1 branch retains the task under capped backoff without consuming an
		// attempt; catching it here would flatten it into an ordinary failure and burn attempts.
		if (counters.blockedOnlyByPendingGates()) {
			// Every failure was "an ancestor is mid-mutation" — not this task's fault and not
			// fixed by spending an attempt. Surface it as the typed signal the scheduler retains
			// on, mirroring the quarantine branch.
			throw new SearchIndexRefreshPendingException(objectId, counters.pendingBlocks());
		}
		if (counters.failures() != 0) {
			return false;
		}
		// §11.4 re-drive terminus: this clean re-drive satisfied whatever obligation the PRE-read
		// document carried, so clear its marker — scoped to the PRE-read mutation id, which makes a
		// newer in-flight mutation ABANDON the clear (its own cycle owns its terminus). A clear
		// FAILURE makes the re-drive NOT clean: completing the task while the marker survives would
		// strand a RECONCILE_ENQUEUED document with no task (§11.6 row 5's inverse).
		return epochTerminusAfterCleanReDrive(repositoryId, objectId, content);
		} finally {
			redriveProgress.end();
		}
	}

	/** Returns true when the terminus is settled (cleared / abandoned / nothing to do). */
	private boolean epochTerminusAfterCleanReDrive(String repositoryId, String objectId, Content preRead) {
		String mutationId = preRead.aclEpochFieldAsString(
				jp.aegif.nemaki.epoch.AclEpochState.FIELD_MUTATION_ID);
		if (mutationId == null) {
			return true; // no outbox cycle on this document
		}
		try {
			jp.aegif.nemaki.epoch.AclEpochFinalizationService fin = aclEpochFinalizationService;
			if (fin == null) {
				return false; // wiring ON but bean missing — keep the task, fail loudly in logs
			}
			// CLEARED or ABANDONED are both settled: abandoned means a newer mutation (or the
			// scanner) owns the marker now. Only an EXCEPTION (contention / unavailability) keeps
			// the task for a retry.
			fin.clearMarkerAfterReconcile(repositoryId, objectId, mutationId);
			evictAfterRawEpochWrite(repositoryId, objectId);
			return true;
		} catch (Exception e) {
			log.warn("Reconcile: epoch terminus failed for " + objectId + " — task retained: " + e.getMessage());
			return false;
		}
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
			RefreshCounters counters, boolean syncConfirm,
			java.util.function.BooleanSupplier leaseStillHeld, Long epochObligation, PropagationProgress progress) {
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
					jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.INDEX_WRITE_FAILURE,
					epochObligation);
			if (counters != null) counters.recordFailure();
		};

		// CMIS content readers: refresh the Solr `readers` field so the query-time
		// readers fq stays correct — for EVERY node INCLUDING the root (review round 3,
		// #2). The root object's ACL was persisted + full-doc indexed by updateInternal
		// (applyAcl) / ContentServiceImpl.move, but that write is fire-and-forget with no
		// permanent-failure callback: if it exhausts its retries the root's readers are
		// lost with nothing to reconcile them. Writing the root here too — through the
		// generation-fenced readers-only CAS with the enqueue-on-failure hook — makes a
		// permanent root Solr failure land in the reconciliation queue, and the fence
		// keeps this (idempotent) write from clobbering a fresher generation.
		if (solrUtil != null) {
			try {
				checkpointLease(leaseStillHeld);
				writeContentReaders(solrUtil, repositoryId, content, onWriteFailed);
			} catch (LeaseLostException lle) {
				throw lle;
			} catch (jp.aegif.nemaki.epoch.AclEpochQuarantineBlockedException qbe) {
				// §5.1 via §11.3: a quarantine block must reach the scheduler's retention branch
				// (re-drive) or the async root-enqueue (refresh) — NEVER be flattened into a counted
				// per-node failure, which would burn attempts toward terminal-FAILED.
				throw qbe;
			} catch (jp.aegif.nemaki.epoch.AclEffectiveEpochService.AclEpochPendingException pe) {
				// The PENDING GATE is a "come back in a moment", not a failure: an ancestor is
				// mid-mutation, and the finalizer will advance its marker without anyone's help.
				// It used to fall into the generic branch below and consume one of the ten
				// attempts, so a chain under sustained ACL churn could exhaust the budget and be
				// abandoned as terminal FAILED — leaving those descendants' readers stale until
				// a human noticed. Same treatment as a quarantine block: record the work so it is
				// retried, but count it separately so the scheduler can retain without charging
				// an attempt.
				log.info("Pending gate deferred readers refresh for " + content.getId()
						+ " (dependency " + pe.dependencyId + " is " + pe.state + ")");
				recordPendingBlockedNode(reconcile, counters, repositoryId, content.getId(),
						epochObligation);
			} catch (Exception e) {
				log.warn("Failed to refresh content readers for " + content.getId() + ": " + e.getMessage());
				recordNodeFailure(reconcile, counters, repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
						epochObligation);
			}
		}

		// RAG readers (documents only, when RAG enabled). The guard is also passed INTO
		// updateDocumentACL so a large block rebuild is fenced per chunk-page (a single
		// document's block can outlive the lease), not just once before the call.
		if (ragService != null && expander != null && content instanceof Document) {
			try {
				checkpointLease(leaseStillHeld);
				java.util.List<String> readers = expander.expandToReaders(repositoryId, content);
				ragService.updateDocumentACL(repositoryId, content.getId(), readers, leaseStillHeld);
			} catch (LeaseLostException lle) {
				throw lle;
			} catch (Exception e) {
				log.warn("Failed to update RAG ACL for document " + content.getId() + ": " + e.getMessage());
				recordNodeFailure(reconcile, counters, repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
						epochObligation);
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
						// Re-check the lease before EACH relationship write: an endpoint
						// with thousands of relationships must not let a lease-lost worker
						// keep writing for the whole batch (finer than per-node fencing).
						checkpointLease(leaseStillHeld);
						writeContentReaders(solrUtil, repositoryId, rel, onWriteFailed);
					}
				}
			} catch (LeaseLostException lle) {
				throw lle;
			} catch (jp.aegif.nemaki.epoch.AclEpochQuarantineBlockedException qbe) {
				throw qbe; // §5.1: reach the scheduler's retention branch, never a counted failure
			} catch (Exception e) {
				log.warn("Failed to refresh relationship readers for " + content.getId() + ": " + e.getMessage());
				recordNodeFailure(reconcile, counters, repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.RELATIONSHIP_REFRESH_FAILURE,
						epochObligation);
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
		// This node's own refresh is done (successfully or not — a failed node is still a node
		// the traversal will not revisit). Counted before recursing so the progress reflects
		// breadth already covered rather than jumping only when a whole subtree finishes.
		if (progress != null) {
			progress.nodeDone();
		}

		if (content.isFolder()) {
			List<Content> children = null;
			try {
				children = contentService.getChildren(repositoryId, content.getId());
			} catch (Exception e) {
				log.warn("Failed to list children of " + content.getId()
						+ " during search-index ACL refresh (subtree skipped): " + e.getMessage());
				recordNodeFailure(reconcile, counters, repositoryId, content.getId(),
						jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.TRAVERSAL_FAILURE,
						epochObligation);
			}
			if (!CollectionUtils.isEmpty(children)) {
				for (Content child : children) {
					try {
						if (contentService.getAclInheritedWithDefault(repositoryId, child)) {
							updateSearchIndexACLRecursively(repositoryId, child, ragService, expander,
									solrUtil, visitedIds, false, reconcile, counters, syncConfirm,
									leaseStillHeld, epochObligation, progress);
						}
					} catch (LeaseLostException lle) {
						// A descendant lost the reconciliation lease — propagate so the
						// WHOLE re-drive aborts. Must be re-thrown BEFORE the generic catch,
						// otherwise the traversal would swallow it and keep writing after
						// the lease was reclaimed (defeats cooperative fencing).
						throw lle;
					} catch (jp.aegif.nemaki.epoch.AclEpochQuarantineBlockedException qbe) {
						throw qbe; // §5.1: the whole traversal is blocked by ONE ancestor — propagate
					} catch (Exception e) {
						log.warn("Failed to refresh search-index ACL for child " + child.getId()
								+ " (continuing with siblings): " + e.getMessage());
						recordNodeFailure(reconcile, counters, repositoryId, child.getId(),
								jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
								epochObligation);
					}
				}
			}
		}
	}

	/** Record a per-node ACL-refresh failure: enqueue for reconciliation and/or count it. */
	private void recordNodeFailure(jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconcile,
			RefreshCounters counters, String repositoryId, String objectId,
			String reason, Long epochObligation) {
		if (reconcile != null) {
			reconcile.enqueue(repositoryId, objectId, reason, epochObligation);
		}
		if (counters != null) {
			counters.recordFailure();
		}
	}

	/**
	 * A node deferred by the pending gate: queued for re-drive exactly like a failure, but
	 * tallied separately so the caller can retain the task without spending an attempt.
	 */
	private void recordPendingBlockedNode(
			jp.aegif.nemaki.reconcile.SearchIndexReconciliationService reconcile,
			RefreshCounters counters, String repositoryId, String objectId, Long epochObligation) {
		if (counters != null) {
			counters.recordPendingBlock();
		}
		if (reconcile != null) {
			reconcile.enqueue(repositoryId, objectId,
					jp.aegif.nemaki.reconcile.SearchIndexAclReindexTask.Reason.NODE_REFRESH_FAILURE,
					epochObligation);
		}
	}


	/**
	 * Synchronously clear ACL caches for a content item and all its descendants that inherit ACL.
	 * This ensures that child documents immediately see updated permissions after parent ACL changes.
	 *
	 * CRITICAL FIX (2025-01-23): Changed from async to sync execution to prevent race conditions
	 * where users see stale cached ACL data on child documents after changing parent permissions.
	 */
	private int clearCachesRecursively(String repositoryId, Content content) {
		// Advance the generation BEFORE evicting anything. Every caller of this method has just
		// committed an ACL-affecting write, so any effective-ACL computation already in flight is
		// suspect. Doing it first means a reader that finishes mid-sweep is declined by the
		// compare-and-put in AclServiceDelegate rather than republishing behind us.
		jp.aegif.nemaki.util.cache.AclCacheGeneration.advance(repositoryId);

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

		// INFO, not debug: this count is the size of the inheriting subtree, and it is the
		// ONLY place the server knows it before the asynchronous readers refresh starts. An
		// operator asking "is this repository busy, and with how much work?" has nothing else
		// to read — see docs/design/v3.3-release-blockers.md E2.
		log.info("ACL cache eviction (sync): repository=" + repositoryId + " root=" + content.getId()
				+ " inheritingSubtreeNodes=" + visitedIds.size());
		return visitedIds.size();
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
