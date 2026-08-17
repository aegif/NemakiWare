package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.acl.AclSemantics;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delegate for ACL calculation operations.
 * Extracted from ContentServiceImpl as part of class decomposition.
 */
public class AclServiceDelegate {

	private static final Logger log = LoggerFactory.getLogger(AclServiceDelegate.class);

	private final ContentService contentService;
	private final ContentDaoService contentDaoService;
	private final NemakiCachePool nemakiCachePool;
	private final RepositoryInfoMap repositoryInfoMap;
	private final PropertyManager propertyManager;

	public AclServiceDelegate(ContentService contentService, ContentDaoService contentDaoService,
			NemakiCachePool nemakiCachePool, RepositoryInfoMap repositoryInfoMap, PropertyManager propertyManager) {
		this.contentService = contentService;
		this.contentDaoService = contentDaoService;
		this.nemakiCachePool = nemakiCachePool;
		this.repositoryInfoMap = repositoryInfoMap;
		this.propertyManager = propertyManager;
	}

	public Acl calculateAcl(String repositoryId, Content content) {
		return calculateAcl(repositoryId, content, false);
	}

	/**
	 * As {@link #calculateAcl(String, Content)} but with a {@code strict} mode for the
	 * search-index ACL reconciliation re-drive. In strict mode an unreadable inherited
	 * PARENT (a non-null {@code parentId} whose folder cannot be loaded) THROWS instead
	 * of silently degrading to local-ACEs-only — a transient parent-read failure would
	 * otherwise drop every inherited grant, and the reconcile would then CAS-write those
	 * under-visible readers and delete its task as if clean (no leak, but permanent
	 * under-visibility until the next ACL touch). Strict also bypasses the ACL cache so
	 * the walk is always fresh (the reconcile evicts first, but this guarantees it).
	 */
	public Acl calculateAcl(String repositoryId, Content content, boolean strict) {
		NemakiCache<Acl> aclCache = nemakiCachePool.get(repositoryId).getAclCache();
		Acl acl = strict ? null : aclCache.get(content.getId());

		if (acl == null) {
			// Date the computation BEFORE reading any ancestor. applyAcl evicts this object's
			// memo and its descendants' while holding only a read lock, and the eviction walk
			// locks nothing, so a computation that started before the change can finish after the
			// sweep and republish a stale answer that nothing will remove again. Recording the
			// generation up front lets the put below decline instead.
			long generation = jp.aegif.nemaki.util.cache.AclCacheGeneration.current(repositoryId);
			acl = resolveAcl(repositoryId, content, strict);
			if (!strict) {
				putIfStillCurrent(repositoryId, aclCache, content.getId(), acl, generation);
			}
		}
		return acl;
	}

	/**
	 * Publish a computed effective ACL only if no ACL write landed while it was being computed.
	 *
	 * <p>Declining costs one recomputation on the next read. Publishing a stale answer costs an
	 * authorization decision made against permissions that no longer exist, for as long as the
	 * entry lives.
	 */
	private void putIfStillCurrent(String repositoryId, NemakiCache<Acl> aclCache, String objectId,
			Acl acl, long observedGeneration) {
		if (jp.aegif.nemaki.util.cache.AclCacheGeneration.isStillCurrent(repositoryId, observedGeneration)) {
			aclCache.put(objectId, acl);
		}
	}

	public Map<String, Content> getContentsByIds(String repositoryId, List<String> objectIds) {
		return contentDaoService.getContentsByIds(repositoryId, objectIds);
	}

	public Map<String, Acl> calculateAcls(String repositoryId, Collection<Content> contents) {
		Map<String, Acl> result = new HashMap<>();
		if (contents == null || contents.isEmpty()) {
			return result;
		}

		NemakiCache<Acl> aclCache = nemakiCachePool.get(repositoryId).getAclCache();

		for (Content content : contents) {
			if (content == null) {
				continue;
			}
			String contentId = content.getId();

			Acl cachedAcl = aclCache.get(contentId);
			if (cachedAcl != null) {
				result.put(contentId, cachedAcl);
				continue;
			}

			long generation = jp.aegif.nemaki.util.cache.AclCacheGeneration.current(repositoryId);
			Acl acl = resolveAcl(repositoryId, content, false);
			putIfStillCurrent(repositoryId, aclCache, contentId, acl, generation);
			result.put(contentId, acl);
		}

		return result;
	}

	/**
	 * The ONE place the CMIS runtime turns a Content into its effective {@code Acl} — including the
	 * OUTER root/non-inheriting branch, which used to be duplicated between {@link #calculateAcl}
	 * and {@link #calculateAcls} (increment 5S step 2). The ACL-epoch side calls the same
	 * {@link AclSemantics#resolveAcl} over its authoritative chain, so neither the recursion NOR the
	 * outer branch can drift between the two.
	 */
	private Acl resolveAcl(String repositoryId, Content content, boolean strict) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		return AclSemantics.resolveAcl(new CmisChainNode(repositoryId, content), strict,
				info.getPrincipalIdAnyone(), info.getPrincipalIdAnonymous());
	}

	/**
	 * The CMIS-runtime view of one inheritance-chain node (design §5.3, increment 5S).
	 *
	 * <p>The TRAVERSAL stays here — a parent is resolved lazily through {@code getFolder}, which
	 * goes via the CACHED content DAO, exactly as before — while the SHAPE of the recursion lives
	 * in {@link AclSemantics}. The ACL-epoch side supplies the same interface over documents it
	 * read authoritatively, so the two sides cannot disagree about when inheritance stops.
	 *
	 * <p>{@code getFolder} collapses a genuine 404 and a transient read error into null; that is
	 * why {@link AclSemantics#effectiveAces} takes {@code strict}.
	 */
	private final class CmisChainNode implements AclSemantics.ChainNode {
		private final String repositoryId;
		private final Content content;

		CmisChainNode(String repositoryId, Content content) {
			this.repositoryId = repositoryId;
			this.content = content;
		}

		@Override public String id() { return content.getId(); }

		@Override public List<Ace> localAces() {
			Acl contentAcl = content.getAcl();
			if (contentAcl == null) {
				log.error("Invalid Acl, content ACL is null! [ID=" + content.getId() + "]" + content.getName());
				return new ArrayList<Ace>();
			}
			return contentAcl.getLocalAces();
		}

		@Override public Acl storedAcl() { return content.getAcl(); }

		@Override public boolean root() { return contentService.isRoot(repositoryId, content); }

		@Override public boolean inherited() {
			return getAclInheritedWithDefault(repositoryId, content);
		}

		@Override public String parentId() { return content.getParentId(); }

		@Override public AclSemantics.ChainNode parent() {
			Folder parent = contentService.getFolder(repositoryId, content.getParentId());
			return parent == null ? null : new CmisChainNode(repositoryId, parent);
		}
	}

	public Boolean getAclInheritedWithDefault(String repositoryId, Content content) {
		boolean inheritedAtTopLevel = propertyManager
				.readBoolean(PropertyKey.CAPABILITY_EXTENDED_PERMISSION_INHERITANCE_TOPLEVEL);

		if (contentService.isRoot(repositoryId, content)) {
			return false;
		} else {
			if (contentService.isTopLevel(repositoryId, content) && !inheritedAtTopLevel) {
				return (content.isAclInherited() == null) ? Boolean.TRUE : content.isAclInherited();
			} else {
				return (content.isAclInherited() == null) ? Boolean.TRUE : content.isAclInherited();
			}
		}
	}
}
