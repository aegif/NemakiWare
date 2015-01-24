package jp.aegif.nemaki.service.cache;

import net.sf.ehcache.Cache;

public interface NemakiCache {
	public Cache getTypeCache();
	public Cache getContentCache();
	public Cache getVersionSeriesCache();
	public Cache getAttachmentCache();
	public Cache getChangeEventCache();
	public Cache getLatestChangeTokenCache();
	public Cache getUserCache();
	public Cache getUsersCache();
	public Cache getGroupCache();
	public Cache getGroupsCache();
	
	
}
