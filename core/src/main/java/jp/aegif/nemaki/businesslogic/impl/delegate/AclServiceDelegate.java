package jp.aegif.nemaki.businesslogic.impl.delegate;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.constant.PrincipalId;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.commons.collections4.CollectionUtils;
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
		NemakiCache<Acl> aclCache = nemakiCachePool.get(repositoryId).getAclCache();
		Acl acl = aclCache.get(content.getId());

		if (acl == null) {
			boolean iht = getAclInheritedWithDefault(repositoryId, content);
			boolean isRootContent = contentService.isRoot(repositoryId, content);

			if (!isRootContent && iht) {
				List<Ace> aces = new ArrayList<Ace>();
				List<Ace> result = calculateAclInternal(repositoryId, aces, content);

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
			aclCache.put(content.getId(), acl);
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
				Ace rootAce = deepCopy(ace);
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
					return aces;
				} else {
					return mergeAcl(repositoryId, aces,
							calculateAclInternal(repositoryId, new ArrayList<Ace>(), parent));
				}
			}
		}
	}

	private List<Ace> mergeAcl(String repositoryId, List<Ace> target, List<Ace> source) {
		HashMap<String, Ace> _result = new HashMap<String, Ace>();

		convertSystemPrincipalId(repositoryId, target);

		HashMap<String, Ace> targetMap = buildAceMap(target);
		HashMap<String, Ace> sourceMap = buildAceMap(source);

		for (Entry<String, Ace> t : targetMap.entrySet()) {
			Ace ace = deepCopy(t.getValue());
			ace.setDirect(true);
			_result.put(t.getKey(), ace);
		}

		for (Entry<String, Ace> s : sourceMap.entrySet()) {
			if (!targetMap.containsKey(s.getKey())) {
				Ace ace = deepCopy(s.getValue());
				ace.setDirect(false);
				_result.put(s.getKey(), ace);
			}
		}

		List<Ace> resultList = new ArrayList<Ace>();
		for (Entry<String, Ace> r : _result.entrySet()) {
			resultList.add(r.getValue());
		}

		return resultList;
	}

	private HashMap<String, Ace> buildAceMap(List<Ace> aces) {
		HashMap<String, Ace> map = new HashMap<String, Ace>();

		for (Ace ace : aces) {
			map.put(ace.getPrincipalId(), ace);
		}

		return map;
	}

	private Ace deepCopy(Ace ace) {
		Ace result = new Ace();

		result.setPrincipalId(ace.getPrincipalId());
		result.setDirect(ace.isDirect());
		if (CollectionUtils.isEmpty(ace.getPermissions())) {
			result.setPermissions(new ArrayList<String>());
		} else {
			List<String> l = new ArrayList<String>();
			for (String p : ace.getPermissions()) {
				l.add(p);
			}
			result.setPermissions(l);
		}

		return result;
	}

	private void convertSystemPrincipalId(String repositoryId, List<Ace> aces) {
		RepositoryInfo info = repositoryInfoMap.get(repositoryId);

		for (Ace ace : aces) {
			if (PrincipalId.ANONYMOUS_IN_DB.equals(ace.getPrincipalId())) {
				String anonymous = info.getPrincipalIdAnonymous();
				ace.setPrincipalId(anonymous);
			}
			if (PrincipalId.ANYONE_IN_DB.equals(ace.getPrincipalId())) {
				String anyone = info.getPrincipalIdAnyone();
				ace.setPrincipalId(anyone);
			}
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
