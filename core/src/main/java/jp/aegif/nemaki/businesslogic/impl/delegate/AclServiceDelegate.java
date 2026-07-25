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
import java.util.Map.Entry;

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
			boolean iht = getAclInheritedWithDefault(repositoryId, content);
			boolean isRootContent = contentService.isRoot(repositoryId, content);

			if (!isRootContent && iht) {
				List<Ace> aces = new ArrayList<Ace>();
				List<Ace> result = calculateAclInternal(repositoryId, aces, content, strict);

				acl = new Acl();
				for (Ace r : result) {
					if (r.isDirect()) {
						acl.getLocalAces().add(r);
					} else {
						acl.getInheritedAces().add(r);
					}
				}
			} else {
				acl = content.getAcl();
			}

			convertSystemPrincipalId(repositoryId, acl.getAllAces());
			if (!strict) {
				aclCache.put(content.getId(), acl);
			}
		}
		return acl;
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

			Acl acl;
			boolean iht = getAclInheritedWithDefault(repositoryId, content);
			boolean isRootContent = contentService.isRoot(repositoryId, content);

			if (!isRootContent && iht) {
				List<Ace> aces = new ArrayList<Ace>();
				List<Ace> calcResult = calculateAclInternal(repositoryId, aces, content);

				acl = new Acl();
				for (Ace r : calcResult) {
					if (r.isDirect()) {
						acl.getLocalAces().add(r);
					} else {
						acl.getInheritedAces().add(r);
					}
				}
			} else {
				acl = content.getAcl();
			}

			convertSystemPrincipalId(repositoryId, acl.getAllAces());
			aclCache.put(contentId, acl);
			result.put(contentId, acl);
		}

		return result;
	}

	private List<Ace> calculateAclInternal(String repositoryId, List<Ace> result, Content content) {
		return calculateAclInternal(repositoryId, result, content, false);
	}

	private List<Ace> calculateAclInternal(String repositoryId, List<Ace> result, Content content, boolean strict) {
		Acl contentAcl = content.getAcl();
		List<Ace> aces = null;
		if (contentAcl == null) {
			log.error("Invalid Acl, content ACL is null! [ID=" + content.getId() + "]" + content.getName());
			aces = new ArrayList<Ace>();
		} else {
			aces = contentAcl.getLocalAces();
		}

		if (contentService.isRoot(repositoryId, content) || !getAclInheritedWithDefault(repositoryId, content)) {
			List<Ace> rootAces = new ArrayList<Ace>();

			for (Ace ace : aces) {
				Ace rootAce = AclSemantics.deepCopy(ace);
				rootAce.setDirect(true);
				rootAces.add(rootAce);
			}
			return mergeAcl(repositoryId, result, rootAces);
		} else {
			if (content.getParentId() == null) {
				return aces;
			} else {
				Folder parent = contentService.getFolder(repositoryId, content.getParentId());
				if (parent == null) {
					// getFolder collapses a genuine 404 AND a transient read error to null.
					// Non-strict: keep the historical best-effort (local ACEs only). Strict
					// (reconciliation re-drive): an inheriting object MUST have a readable
					// parent — a null here is either a transient failure or a data
					// inconsistency, and silently dropping the inherited grants would write
					// under-visible readers and complete the task. Fail so it is retried.
					if (strict) {
						throw new IllegalStateException("Strict ACL: parent " + content.getParentId()
								+ " of " + content.getId() + " is unreadable — cannot compute inherited ACL");
					}
					return aces;
				} else {
					return mergeAcl(repositoryId, aces,
							calculateAclInternal(repositoryId, new ArrayList<Ace>(), parent, strict));
				}
			}
		}
	}

	/**
	 * Delegates to {@link AclSemantics#mergeAces} — the ONE implementation of the inheritance
	 * merge, shared with the ACL-epoch (authoritative-traversal) side so the two cannot diverge
	 * (design §5.3). Behaviour is unchanged, including the in-place system-principal conversion
	 * of {@code target} and the non-contractual result ordering.
	 */
	private List<Ace> mergeAcl(String repositoryId, List<Ace> target, List<Ace> source) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		return AclSemantics.mergeAces(target, source,
				info.getPrincipalIdAnyone(), info.getPrincipalIdAnonymous());
	}

	/** Delegates to {@link AclSemantics#convertSystemPrincipalIds} (shared semantics, §5.3). */
	private void convertSystemPrincipalId(String repositoryId, List<Ace> aces) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);
		AclSemantics.convertSystemPrincipalIds(aces, info.getPrincipalIdAnyone(),
				info.getPrincipalIdAnonymous());
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
